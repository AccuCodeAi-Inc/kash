package com.accucodeai.kash.interpreter

import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart

// Bash brace expansion (`{a,b,c}`, `{1..5}`, etc.). Runs as a pre-pass on
// the Word AST, BEFORE tilde / parameter / arithmetic / cmdsub / split /
// glob — matching bash's `brace expansion` ordering.
//
// We work on a token model: each Literal-WordPart char is a scannable Ch;
// every other WordPart (SingleQuoted, DoubleQuoted, Escaped, ParameterExpansion,
// CommandSubstitution, ArithmeticExpansion, ExpandedText) is an Opaque token —
// the scanner never descends into it. That matches bash's brace scanner, which
// treats `$(...)`, `${...}`, backticks, and quoted regions as opaque.

/**
 * Cap on words produced per brace expansion. Bash 5+ defaults its
 * the number of brace-expansion results to ~524288; we mirror that. On overflow the original
 * Word is returned unchanged (the user sees the literal braces back, rather
 * than an OOM).
 */
private const val BRACE_EXPAND_LIMIT = 524288

private sealed class Tok {
    data class Ch(
        val c: Char,
    ) : Tok()

    data class Opaque(
        val part: WordPart,
    ) : Tok()
}

internal fun expandBraces(
    word: Word,
    enabled: Boolean,
): List<Word> {
    if (!enabled) return listOf(word)
    val toks = tokenize(word.parts)
    val expanded =
        try {
            expandTokens(toks)
        } catch (_: BraceOverflow) {
            return listOf(word)
        }
    if (expanded.size == 1 && expanded[0] === toks) return listOf(word)
    return expanded.map { toks2 ->
        val parts = reconstruct(toks2)
        // Empty brace element (e.g. `{a,,b}` → middle is "") must survive as an
        // explicit empty word, not get dropped by IFS-split / glob. We emit it
        // as an empty DoubleQuoted, which [ExpanderParameter.appendPart] turns
        // into a `Fragment("", quoted=true, splittable=false)` — the same shape
        // a source-level `""` produces.
        Word(if (parts.isEmpty()) listOf(WordPart.DoubleQuoted(emptyList())) else parts, word.line)
    }
}

private class BraceOverflow : RuntimeException()

private fun tokenize(parts: List<WordPart>): List<Tok> {
    val out = ArrayList<Tok>(parts.sumOf { if (it is WordPart.Literal) it.value.length else 1 })
    for (p in parts) {
        if (p is WordPart.Literal) {
            for (c in p.value) out += Tok.Ch(c)
        } else {
            out += Tok.Opaque(p)
        }
    }
    return out
}

private fun reconstruct(toks: List<Tok>): List<WordPart> {
    val out = ArrayList<WordPart>()
    val sb = StringBuilder()

    fun flush() {
        if (sb.isNotEmpty()) {
            out += WordPart.Literal(sb.toString())
            sb.clear()
        }
    }
    for (t in toks) {
        when (t) {
            is Tok.Ch -> {
                sb.append(t.c)
            }

            is Tok.Opaque -> {
                flush()
                out += t.part
            }
        }
    }
    flush()
    return mergeBareParamPrefix(out)
}

/**
 * Bash quirk preserved by brace expansion: `$var{x,y}` produces `$varx` and
 * `$vary`, not `$var` followed by literal `x` / `y`. Brace expansion is
 * textual in bash, so a bare `$NAME` followed by identifier-continuation
 * chars binds the whole token as one parameter name. Our parser already
 * tokenized `$var` and `{x,y}` into separate WordParts, so we recover the
 * bash semantics here by merging adjacent `bare-PE + Literal-with-leading-
 * ident-chars` into a PE with the extended name. Braced `${var}` is
 * untouched (the explicit `}` is the delimiter bash respects).
 */
private fun mergeBareParamPrefix(parts: List<WordPart>): List<WordPart> {
    if (parts.size < 2) return parts
    val out = ArrayList<WordPart>(parts.size)
    var i = 0
    while (i < parts.size) {
        val p = parts[i]
        val next = parts.getOrNull(i + 1)
        if (p is WordPart.ParameterExpansion &&
            !p.braced &&
            p.op == null &&
            !p.lengthOf &&
            p.arg1 == null &&
            p.arg2 == null &&
            p.subscript == null &&
            !p.indirect &&
            p.nameGlob == null &&
            isValidBareName(p.parameter) &&
            next is WordPart.Literal &&
            next.value.isNotEmpty() &&
            isIdentContinue(next.value[0])
        ) {
            var split = 1
            while (split < next.value.length && isIdentContinue(next.value[split])) split++
            val nameExt = next.value.substring(0, split)
            val rest = next.value.substring(split)
            out += WordPart.ParameterExpansion(p.parameter + nameExt, braced = false)
            if (rest.isNotEmpty()) out += WordPart.Literal(rest)
            i += 2
        } else {
            out += p
            i++
        }
    }
    return out
}

private fun isIdentContinue(c: Char): Boolean = c == '_' || c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'

private fun isValidBareName(s: String): Boolean {
    if (s.isEmpty()) return false
    val first = s[0]
    if (first != '_' && first !in 'A'..'Z' && first !in 'a'..'z') return false
    for (j in 1 until s.length) if (!isIdentContinue(s[j])) return false
    return true
}

/**
 * Core recursion. Finds the first eligible brace group (balanced AND containing
 * either a top-level unquoted comma, or a valid sequence expression). If none
 * found, returns the input as a single result.
 */
private fun expandTokens(toks: List<Tok>): List<List<Tok>> {
    var i = 0
    while (i < toks.size) {
        val t = toks[i]
        if (t is Tok.Ch && t.c == '{') {
            val close = findMatchingClose(toks, i)
            if (close == null) {
                i++
                continue
            }
            val inner = toks.subList(i + 1, close)
            // Try sequence form first — `{1..5}`, `{a..z..2}`, `{01..05}`.
            val seqElems = parseSequence(inner)
            if (seqElems != null) {
                val prefix = toks.subList(0, i)
                val suffix = toks.subList(close + 1, toks.size)
                return cross(prefix, seqElems.map { charsAsToks(it) }, suffix)
            }
            // Then comma-list. Returns null if there is no top-level comma.
            val commaElems = splitTopLevelCommas(inner)
            if (commaElems != null) {
                val prefix = toks.subList(0, i)
                val suffix = toks.subList(close + 1, toks.size)
                // Recursively expand each element so nested braces inside an
                // element become independent product entries.
                val expandedElems: List<List<List<Tok>>> = commaElems.map { expandTokens(it) }
                return crossNested(prefix, expandedElems, suffix)
            }
            // Balanced but not eligible (`{abc}`, `{}`): advance past the `{`
            // but NOT past the close — a nested eligible group inside may
            // still need to fire (`{{{{a,b}}}}` → `{{{a}}} {{{b}}}`).
            i++
            continue
        }
        i++
    }
    return listOf(toks)
}

private fun cross(
    prefix: List<Tok>,
    elemAlternatives: List<List<Tok>>,
    suffix: List<Tok>,
): List<List<Tok>> {
    val suffExpanded = expandTokens(suffix)
    val out = ArrayList<List<Tok>>(elemAlternatives.size * suffExpanded.size)
    for (elem in elemAlternatives) {
        for (rest in suffExpanded) {
            if (out.size >= BRACE_EXPAND_LIMIT) throw BraceOverflow()
            out += prefix + elem + rest
        }
    }
    return out
}

private fun crossNested(
    prefix: List<Tok>,
    elemAlternativesPerElem: List<List<List<Tok>>>,
    suffix: List<Tok>,
): List<List<Tok>> {
    val suffExpanded = expandTokens(suffix)
    val out = ArrayList<List<Tok>>()
    for (alts in elemAlternativesPerElem) {
        for (alt in alts) {
            for (rest in suffExpanded) {
                if (out.size >= BRACE_EXPAND_LIMIT) throw BraceOverflow()
                out += prefix + alt + rest
            }
        }
    }
    return out
}

private fun charsAsToks(s: String): List<Tok> = s.map { Tok.Ch(it) }

/**
 * Find the position of the `}` that closes the `{` at [open]. Returns null
 * if unbalanced. Opaque tokens never count as `{` or `}`.
 */
private fun findMatchingClose(
    toks: List<Tok>,
    open: Int,
): Int? {
    var depth = 0
    var i = open
    while (i < toks.size) {
        val t = toks[i]
        if (t is Tok.Ch) {
            when (t.c) {
                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        i++
    }
    return null
}

/**
 * Split [inner] on top-level (depth 0) unquoted commas. Returns null if there
 * is no top-level comma — caller then tries sequence form or leaves the brace
 * literal.
 */
private fun splitTopLevelCommas(inner: List<Tok>): List<List<Tok>>? {
    val parts = ArrayList<List<Tok>>()
    var depth = 0
    var start = 0
    var sawComma = false
    for (i in inner.indices) {
        val t = inner[i]
        if (t is Tok.Ch) {
            when (t.c) {
                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                }

                ',' -> {
                    if (depth == 0) {
                        parts += inner.subList(start, i)
                        start = i + 1
                        sawComma = true
                    }
                }
            }
        }
    }
    if (!sawComma) return null
    parts += inner.subList(start, inner.size)
    return parts
}

/**
 * Parse bash sequence-expression form, returning the list of generated
 * elements as strings. Returns null if [inner] is not a valid sequence.
 * Requires all-literal content (no opaque chunks) and a recognized shape.
 *
 *   `{N..M}`         — integers, default step ±1.
 *   `{N..M..S}`      — explicit step.
 *   `{a..z}`         — single ASCII letters.
 *   `{a..z..S}`      — letters with integer step.
 *
 *  Zero-pad: if either bound has a leading zero (or `-0`), all outputs are
 *  zero-padded to the widest bound width (sign included in width).
 *  Mixed types (`{1..a}`) → null (caller leaves literal).
 *  Overflow / huge ranges → null via element-count guard at the cap.
 */
private fun parseSequence(inner: List<Tok>): List<String>? {
    if (inner.isEmpty()) return null
    val sb = StringBuilder(inner.size)
    for (t in inner) {
        if (t !is Tok.Ch) return null
        sb.append(t.c)
    }
    val s = sb.toString()
    val parts = s.split("..")
    if (parts.size !in 2..3) return null
    val a = parts[0]
    val b = parts[1]
    val step =
        if (parts.size == 3) {
            parts[2].toIntOrNull() ?: return null
        } else {
            0
        }
    // Integer form.
    if (a.isInteger() && b.isInteger()) {
        val lo = a.toLongOrNull() ?: return null
        val hi = b.toLongOrNull() ?: return null
        val absStep = if (step == 0) 1 else kotlin.math.abs(step)
        val direction = if (hi >= lo) 1 else -1
        val width =
            if (a.hasLeadingZero() || b.hasLeadingZero()) {
                maxOf(a.length, b.length)
            } else {
                0
            }
        val count = (kotlin.math.abs(hi - lo) / absStep) + 1
        if (count > BRACE_EXPAND_LIMIT) throw BraceOverflow()
        val out = ArrayList<String>(count.toInt())
        var cur = lo
        var produced = 0L
        while ((direction == 1 && cur <= hi) || (direction == -1 && cur >= hi)) {
            out += formatPadded(cur, width)
            produced++
            if (produced >= count) break
            cur += direction.toLong() * absStep
        }
        return out
    }
    // Letter form: single ASCII chars on both ends, same type.
    if (a.length == 1 && b.length == 1 && a[0].isAsciiLetter() && b[0].isAsciiLetter()) {
        val lo = a[0].code
        val hi = b[0].code
        val absStep = if (step == 0) 1 else kotlin.math.abs(step)
        val direction = if (hi >= lo) 1 else -1
        val count = (kotlin.math.abs(hi - lo) / absStep) + 1
        if (count > BRACE_EXPAND_LIMIT) throw BraceOverflow()
        val out = ArrayList<String>(count)
        var cur = lo
        var produced = 0
        while ((direction == 1 && cur <= hi) || (direction == -1 && cur >= hi)) {
            // POSIX §2.6.7 quote removal strips unquoted `\`, `'`, `"` from
            // expansion results. Bash's brace expansion is one such expansion,
            // so `{a..A}` — which includes 0x5C `\` between `]` and `[` — emits
            // an EMPTY word at that position, not a literal backslash. We don't
            // have a general quote-removal pass; reproduce the observable
            // behavior by emitting empty for these three chars.
            out +=
                when (cur.toChar()) {
                    '\\', '\'', '"' -> ""
                    else -> cur.toChar().toString()
                }
            produced++
            if (produced >= count) break
            cur += direction * absStep
        }
        return out
    }
    return null
}

private fun String.isInteger(): Boolean {
    if (isEmpty()) return false
    val s = if (startsWith('-') || startsWith('+')) substring(1) else this
    if (s.isEmpty()) return false
    return s.all { it in '0'..'9' }
}

private fun String.hasLeadingZero(): Boolean {
    val body = if (startsWith('-') || startsWith('+')) substring(1) else this
    return body.length > 1 && body[0] == '0'
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/**
 * Bash zero-padding: width includes the sign. `{-01..2}` → width=3, so `-01`,
 * `000`, `001`, `002`. We compute by padding the absolute-value digits to
 * (width - signLen) zeros, then prefix the sign.
 */
private fun formatPadded(
    n: Long,
    width: Int,
): String {
    if (width <= 0) return n.toString()
    val neg = n < 0
    val digits = kotlin.math.abs(n).toString()
    val signLen = if (neg) 1 else 0
    val pad = width - signLen - digits.length
    val padded = if (pad > 0) "0".repeat(pad) + digits else digits
    return if (neg) "-$padded" else padded
}
