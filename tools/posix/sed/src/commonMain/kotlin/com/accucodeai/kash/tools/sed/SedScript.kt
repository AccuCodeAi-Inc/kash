package com.accucodeai.kash.tools.sed

import com.accucodeai.kash.shared.regex.LinearRegex

/**
 * Parsed sed program. Supports the high-leverage POSIX/GNU subset:
 *
 * Addresses:
 *  - none (every line), `N` (line number), `$` (last line), `/regex/`.
 *  - Ranges: `addr1,addr2`, `addr1,+N` (GNU), `addr1,~M` (GNU step), `0,/re/` (GNU).
 *  - `!` negates the (possibly ranged) address.
 *
 * Commands:
 *  - `s/pattern/replacement/flags` (flags `g`, `p`, `i`/`I`, Nth).
 *  - `d` `D` delete; `p` `P` print; `n` `N` next/append next; `=` line number.
 *  - `q [N]` `Q [N]` quit (Q silent).
 *  - `a TEXT` / `i TEXT` / `c TEXT` (POSIX `a\<NL>TEXT` and GNU one-liner forms).
 *  - `y/abc/xyz/` transliterate (lengths must match).
 *  - `h` `H` `g` `G` `x` hold-space ops.
 *  - `:label`, `b [LABEL]`, `t [LABEL]`, `T [LABEL]`.
 *  - `{ ... }` blocks (commands share an address).
 *
 * Not implemented: GNU `s` flag `e` (exec replacement), `addr1,addr2` where
 * addr1 is a regex that re-matches mid-range (POSIX allows the engine to
 * re-enter the range; we don't).
 */
internal class SedScript(
    val commands: List<SedInstruction>,
)

/** A single endpoint of an address. */
internal sealed class AddressUnit {
    object LastLine : AddressUnit()

    data class LineNumber(
        val n: Int,
    ) : AddressUnit()

    data class Match(
        val regex: LinearRegex,
        val raw: String,
    ) : AddressUnit()

    /** GNU `first~step` — matches every line at `first + k*step` for k >= 0 (step > 0). */
    data class Step(
        val first: Int,
        val step: Int,
    ) : AddressUnit()
}

/** Range end: a unit, or a GNU relative form. */
internal sealed class RangeEnd {
    data class Unit(
        val u: AddressUnit,
    ) : RangeEnd()

    /** `addr,+N` — end at start + N lines (inclusive of start). */
    data class PlusLines(
        val n: Int,
    ) : RangeEnd()

    /** `addr,~M` — end at first line whose number is a multiple of M (>= start). */
    data class TildeStep(
        val m: Int,
    ) : RangeEnd()
}

internal sealed class Address {
    object Every : Address()

    data class Single(
        val unit: AddressUnit,
    ) : Address()

    data class Range(
        val start: AddressUnit,
        val end: RangeEnd,
    ) : Address()
}

internal class SedInstruction(
    val address: Address,
    val negate: Boolean,
    val op: SedOp,
)

internal sealed class SedOp {
    class Substitute(
        val pattern: LinearRegex,
        val rawPattern: String,
        val replacement: ReplacementTemplate,
        val global: Boolean,
        val nth: Int,
        val print: Boolean,
        /** Optional `w FILE` flag: write replaced pattern space to FILE on success. */
        val writeFile: String?,
        /** GNU `e` flag: after substitution succeeds, exec pattern space as a shell command, replace pattern with output. */
        val execAfter: Boolean,
    ) : SedOp()

    object Delete : SedOp() // d

    object DeleteFirst : SedOp() // D — delete up to first \n, restart cycle without reading

    object Print : SedOp() // p

    object PrintFirst : SedOp() // P — print up to first \n

    object NextLine : SedOp() // n — auto-print, replace pattern with next input

    object AppendNext : SedOp() // N — append \n + next input to pattern

    object PrintLineNum : SedOp() // =

    data class Quit(
        val exit: Int,
        val silent: Boolean,
    ) : SedOp() // q / Q

    data class Append(
        val text: String,
    ) : SedOp() // a

    data class Insert(
        val text: String,
    ) : SedOp() // i

    data class Change(
        val text: String,
    ) : SedOp() // c

    data class Translit(
        val from: String,
        val to: String,
    ) : SedOp() // y

    object Hold : SedOp() // h

    object HoldAppend : SedOp() // H

    object Get : SedOp() // g

    object GetAppend : SedOp() // G

    object Exchange : SedOp() // x

    data class ReadFile(
        val path: String,
    ) : SedOp() // r — read file, queue its contents to append after current line

    data class WriteFile(
        val path: String,
    ) : SedOp() // w — write pattern space + newline to FILE

    data class PrintUnambiguous(
        val wrapAt: Int,
    ) : SedOp() // l [WIDTH] — print pattern space with C-style escapes; 0 disables wrap

    data class Label(
        val name: String,
    ) : SedOp() // :name — no-op marker

    data class Branch(
        val target: String?,
    ) : SedOp() // b — null target means "to end"

    data class BranchIfSub(
        val target: String?,
    ) : SedOp() // t

    data class BranchIfNotSub(
        val target: String?,
    ) : SedOp() // T (GNU)

    /** Beginning of a `{ ... }` block. The address on the surrounding instruction gates it. */
    object BlockBegin : SedOp()

    object BlockEnd : SedOp() // }
}

internal class ReplacementTemplate(
    private val parts: List<Part>,
) {
    sealed class Part {
        data class Literal(
            val text: String,
        ) : Part()

        object WholeMatch : Part()

        data class Backref(
            val group: Int,
        ) : Part()
    }

    fun render(
        whole: String,
        groups: List<String?>,
    ): String {
        val sb = StringBuilder()
        for (p in parts) {
            when (p) {
                is Part.Literal -> {
                    sb.append(p.text)
                }

                Part.WholeMatch -> {
                    sb.append(whole)
                }

                is Part.Backref -> {
                    val g = groups.getOrNull(p.group - 1) ?: ""
                    sb.append(g)
                }
            }
        }
        return sb.toString()
    }

    companion object {
        fun parse(raw: String): ReplacementTemplate {
            val parts = mutableListOf<Part>()
            val lit = StringBuilder()
            var i = 0

            fun flush() {
                if (lit.isNotEmpty()) {
                    parts.add(Part.Literal(lit.toString()))
                    lit.clear()
                }
            }
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length -> {
                        val nxt = raw[i + 1]
                        when (nxt) {
                            in '1'..'9' -> {
                                flush()
                                parts.add(Part.Backref(nxt.digitToInt()))
                            }

                            'n' -> {
                                lit.append('\n')
                            }

                            't' -> {
                                lit.append('\t')
                            }

                            '\\' -> {
                                lit.append('\\')
                            }

                            '&' -> {
                                lit.append('&')
                            }

                            '/' -> {
                                lit.append('/')
                            }

                            else -> {
                                lit.append(nxt)
                            }
                        }
                        i += 2
                    }

                    c == '&' -> {
                        flush()
                        parts.add(Part.WholeMatch)
                        i++
                    }

                    else -> {
                        lit.append(c)
                        i++
                    }
                }
            }
            flush()
            return ReplacementTemplate(parts)
        }
    }
}

/**
 * Translate a POSIX BRE regex into RE2/ERE form by swapping which characters
 * are escaped: in BRE, `\(\)\{\}\+\?\|` are metas and bare `()` `{}` `+?|`
 * are literals; in ERE it's the inverse. Other escapes (`\n`, `\t`, `\\`,
 * `\1`..`\9`, `\.`, etc.) pass through unchanged. Characters inside `[...]`
 * are not transformed (POSIX bracket-expression syntax doesn't vary).
 */
internal fun breToEre(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    var inClass = false
    var classOpen = -1
    while (i < s.length) {
        val c = s[i]
        if (inClass) {
            sb.append(c)
            val sincOpen = i - classOpen
            // A `]` is literal if it appears first (after `[` or `[^`).
            if (c == ']' && sincOpen > 1 && !(sincOpen == 2 && s[classOpen + 1] == '^')) {
                inClass = false
            } else if (c == '\\' && i + 1 < s.length) {
                sb.append(s[i + 1])
                i += 2
                continue
            }
            i++
            continue
        }
        when (c) {
            '[' -> {
                inClass = true
                classOpen = i
                sb.append(c)
                i++
            }

            '\\' -> {
                if (i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        '(', ')', '{', '}', '+', '?', '|' -> sb.append(n)
                        else -> sb.append('\\').append(n)
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }

            '(', ')', '{', '}', '+', '?', '|' -> {
                sb.append('\\').append(c)
                i++
            }

            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

internal object SedScriptParser {
    /**
     * Parse [source]. When [breMode] is true (POSIX default), regexes in
     * substitutions and address matches are treated as Basic Regular
     * Expressions and translated to ERE before being handed to the engine.
     * With `-E`/`-r`, callers pass `breMode = false` to get ERE directly.
     */
    fun parse(
        source: String,
        breMode: Boolean = true,
    ): SedScript {
        val out = mutableListOf<SedInstruction>()
        val ctx = ParseCtx(source, 0, breMode)
        parseInstructions(ctx, out, inBlock = false)
        if (ctx.pos < source.length) {
            throw SedScriptError("unexpected trailing input at position ${ctx.pos}")
        }
        return SedScript(out)
    }

    private class ParseCtx(
        val src: String,
        var pos: Int,
        val breMode: Boolean,
    )

    private fun parseInstructions(
        ctx: ParseCtx,
        out: MutableList<SedInstruction>,
        inBlock: Boolean,
    ) {
        val src = ctx.src
        while (ctx.pos < src.length) {
            skipSeparators(ctx)
            if (ctx.pos >= src.length) return
            if (src[ctx.pos] == '}') {
                if (!inBlock) throw SedScriptError("unmatched '}'")
                out.add(SedInstruction(Address.Every, false, SedOp.BlockEnd))
                ctx.pos++
                return
            }

            val addr = parseAddressMaybeRange(ctx)
            var negate = false
            if (ctx.pos < src.length && src[ctx.pos] == '!') {
                negate = true
                ctx.pos++
            }
            while (ctx.pos < src.length && (src[ctx.pos] == ' ' || src[ctx.pos] == '\t')) ctx.pos++

            if (ctx.pos >= src.length) throw SedScriptError("missing command after address")
            val op = src[ctx.pos]
            ctx.pos++

            when (op) {
                's' -> {
                    val sub = parseSubstitute(ctx)
                    out.add(SedInstruction(addr, negate, sub))
                }

                'd' -> {
                    out.add(SedInstruction(addr, negate, SedOp.Delete))
                }

                'D' -> {
                    out.add(SedInstruction(addr, negate, SedOp.DeleteFirst))
                }

                'p' -> {
                    out.add(SedInstruction(addr, negate, SedOp.Print))
                }

                'P' -> {
                    out.add(SedInstruction(addr, negate, SedOp.PrintFirst))
                }

                'n' -> {
                    out.add(SedInstruction(addr, negate, SedOp.NextLine))
                }

                'N' -> {
                    out.add(SedInstruction(addr, negate, SedOp.AppendNext))
                }

                '=' -> {
                    out.add(SedInstruction(addr, negate, SedOp.PrintLineNum))
                }

                'q', 'Q' -> {
                    val exit = parseOptionalExit(ctx)
                    out.add(SedInstruction(addr, negate, SedOp.Quit(exit, silent = op == 'Q')))
                }

                'h' -> {
                    out.add(SedInstruction(addr, negate, SedOp.Hold))
                }

                'H' -> {
                    out.add(SedInstruction(addr, negate, SedOp.HoldAppend))
                }

                'g' -> {
                    out.add(SedInstruction(addr, negate, SedOp.Get))
                }

                'G' -> {
                    out.add(SedInstruction(addr, negate, SedOp.GetAppend))
                }

                'x' -> {
                    out.add(SedInstruction(addr, negate, SedOp.Exchange))
                }

                'a' -> {
                    val text = parseTextArg(ctx)
                    out.add(SedInstruction(addr, negate, SedOp.Append(text)))
                }

                'i' -> {
                    val text = parseTextArg(ctx)
                    out.add(SedInstruction(addr, negate, SedOp.Insert(text)))
                }

                'c' -> {
                    val text = parseTextArg(ctx)
                    out.add(SedInstruction(addr, negate, SedOp.Change(text)))
                }

                'y' -> {
                    val y = parseTranslit(ctx)
                    out.add(SedInstruction(addr, negate, y))
                }

                'r' -> {
                    val path = parseLabelArg(ctx) ?: throw SedScriptError("'r' requires a file path")
                    out.add(SedInstruction(addr, negate, SedOp.ReadFile(path)))
                }

                'w' -> {
                    val path = parseLabelArg(ctx) ?: throw SedScriptError("'w' requires a file path")
                    out.add(SedInstruction(addr, negate, SedOp.WriteFile(path)))
                }

                'l' -> {
                    // Optional width argument; default 70 (GNU default), 0 disables wrapping.
                    while (ctx.pos < src.length && (src[ctx.pos] == ' ' || src[ctx.pos] == '\t')) ctx.pos++
                    val width =
                        if (ctx.pos < src.length && src[ctx.pos].isDigit()) {
                            val start = ctx.pos
                            while (ctx.pos < src.length && src[ctx.pos].isDigit()) ctx.pos++
                            src.substring(start, ctx.pos).toInt()
                        } else {
                            70
                        }
                    out.add(SedInstruction(addr, negate, SedOp.PrintUnambiguous(width)))
                }

                'b' -> {
                    val label = parseLabelArg(ctx)
                    out.add(SedInstruction(addr, negate, SedOp.Branch(label)))
                }

                't' -> {
                    val label = parseLabelArg(ctx)
                    out.add(SedInstruction(addr, negate, SedOp.BranchIfSub(label)))
                }

                'T' -> {
                    val label = parseLabelArg(ctx)
                    out.add(SedInstruction(addr, negate, SedOp.BranchIfNotSub(label)))
                }

                ':' -> {
                    if (addr !is Address.Every || negate) {
                        throw SedScriptError("labels may not have an address")
                    }
                    val name =
                        parseLabelArg(ctx)
                            ?: throw SedScriptError("missing label name after ':'")
                    out.add(SedInstruction(Address.Every, false, SedOp.Label(name)))
                }

                '{' -> {
                    out.add(SedInstruction(addr, negate, SedOp.BlockBegin))
                    parseInstructions(ctx, out, inBlock = true)
                }

                '}' -> {
                    throw SedScriptError("unexpected '}'")
                }

                else -> {
                    throw SedScriptError("unsupported sed command: '$op'")
                }
            }

            skipBlanksAndSeparators(ctx)
        }
        if (inBlock) throw SedScriptError("unmatched '{' (missing '}')")
    }

    private fun skipSeparators(ctx: ParseCtx) {
        val s = ctx.src
        while (ctx.pos < s.length) {
            val c = s[ctx.pos]
            if (c == ';' || c == '\n' || c == ' ' || c == '\t') ctx.pos++ else break
        }
        // Skip `#` comments to end-of-line.
        if (ctx.pos < s.length && s[ctx.pos] == '#') {
            while (ctx.pos < s.length && s[ctx.pos] != '\n') ctx.pos++
            if (ctx.pos < s.length) ctx.pos++
            skipSeparators(ctx)
        }
    }

    private fun skipBlanksAndSeparators(ctx: ParseCtx) {
        val s = ctx.src
        while (ctx.pos < s.length && (s[ctx.pos] == ' ' || s[ctx.pos] == '\t')) ctx.pos++
        if (ctx.pos < s.length && (s[ctx.pos] == ';' || s[ctx.pos] == '\n')) ctx.pos++
    }

    private fun parseAddressMaybeRange(ctx: ParseCtx): Address {
        val first = parseAddressUnitOrNull(ctx) ?: return Address.Every
        if (ctx.pos < ctx.src.length && ctx.src[ctx.pos] == ',') {
            ctx.pos++
            // Range end: `+N`, `~M`, or another unit.
            if (ctx.pos < ctx.src.length && ctx.src[ctx.pos] == '+') {
                ctx.pos++
                val n = readNonNegInt(ctx) ?: throw SedScriptError("expected number after ',+'")
                return Address.Range(first, RangeEnd.PlusLines(n))
            }
            if (ctx.pos < ctx.src.length && ctx.src[ctx.pos] == '~') {
                ctx.pos++
                val m = readNonNegInt(ctx) ?: throw SedScriptError("expected number after ',~'")
                if (m <= 0) throw SedScriptError("step in ',~M' must be > 0")
                return Address.Range(first, RangeEnd.TildeStep(m))
            }
            val end = parseAddressUnitOrNull(ctx) ?: throw SedScriptError("expected address after ','")
            return Address.Range(first, RangeEnd.Unit(end))
        }
        return Address.Single(first)
    }

    private fun parseAddressUnitOrNull(ctx: ParseCtx): AddressUnit? {
        val s = ctx.src
        if (ctx.pos >= s.length) return null
        val c = s[ctx.pos]
        return when {
            c == '$' -> {
                ctx.pos++
                AddressUnit.LastLine
            }

            c.isDigit() -> {
                val start = ctx.pos
                while (ctx.pos < s.length && s[ctx.pos].isDigit()) ctx.pos++
                val n = s.substring(start, ctx.pos).toInt()
                if (ctx.pos < s.length && s[ctx.pos] == '~' &&
                    ctx.pos + 1 < s.length && s[ctx.pos + 1].isDigit()
                ) {
                    ctx.pos++
                    val stepStart = ctx.pos
                    while (ctx.pos < s.length && s[ctx.pos].isDigit()) ctx.pos++
                    val step = s.substring(stepStart, ctx.pos).toInt()
                    if (step <= 0) throw SedScriptError("step in 'N~M' must be > 0")
                    AddressUnit.Step(n, step)
                } else {
                    AddressUnit.LineNumber(n)
                }
            }

            c == '/' || c == '\\' -> {
                // `/re/` or `\Dre D` (GNU: backslash chooses an alternate delimiter).
                val delim: Char
                if (c == '\\') {
                    if (ctx.pos + 1 >= s.length) throw SedScriptError("unterminated address")
                    delim = s[ctx.pos + 1]
                    ctx.pos += 2
                } else {
                    delim = '/'
                    ctx.pos++
                }
                val pat = StringBuilder()
                while (ctx.pos < s.length) {
                    val cc = s[ctx.pos]
                    if (cc == '\\' && ctx.pos + 1 < s.length) {
                        if (s[ctx.pos + 1] == delim) {
                            pat.append(delim)
                            ctx.pos += 2
                            continue
                        }
                        pat.append(cc).append(s[ctx.pos + 1])
                        ctx.pos += 2
                        continue
                    }
                    if (cc == delim) {
                        ctx.pos++
                        val raw = pat.toString()
                        val ere = if (ctx.breMode) breToEre(raw) else raw
                        return AddressUnit.Match(LinearRegex(ere, ""), raw)
                    }
                    if (cc == '\n') throw SedScriptError("unterminated address regex: $pat")
                    pat.append(cc)
                    ctx.pos++
                }
                throw SedScriptError("unterminated address regex: $pat")
            }

            else -> {
                null
            }
        }
    }

    private fun readNonNegInt(ctx: ParseCtx): Int? {
        val s = ctx.src
        if (ctx.pos >= s.length || !s[ctx.pos].isDigit()) return null
        val start = ctx.pos
        while (ctx.pos < s.length && s[ctx.pos].isDigit()) ctx.pos++
        return s.substring(start, ctx.pos).toInt()
    }

    private fun parseOptionalExit(ctx: ParseCtx): Int {
        val s = ctx.src
        while (ctx.pos < s.length && (s[ctx.pos] == ' ' || s[ctx.pos] == '\t')) ctx.pos++
        return readNonNegInt(ctx) ?: 0
    }

    private fun parseLabelArg(ctx: ParseCtx): String? {
        val s = ctx.src
        while (ctx.pos < s.length && (s[ctx.pos] == ' ' || s[ctx.pos] == '\t')) ctx.pos++
        val start = ctx.pos
        while (ctx.pos < s.length) {
            val c = s[ctx.pos]
            if (c == ';' || c == '\n' || c == '}') break
            ctx.pos++
        }
        val name = s.substring(start, ctx.pos).trimEnd()
        return name.ifEmpty { null }
    }

    /**
     * Parse text argument for `a`/`i`/`c`. Forms supported:
     *  - Classic: `a\` then a newline, then text lines (a backslash at end of a
     *    text line continues to the next line, joined with `\n`).
     *  - GNU one-liner: `a TEXT` (rest of the current line, after optional
     *    leading whitespace).
     */
    private fun parseTextArg(ctx: ParseCtx): String {
        val s = ctx.src
        // Skip optional `\`.
        if (ctx.pos < s.length && s[ctx.pos] == '\\') ctx.pos++
        // Skip leading spaces/tabs (but not the newline if present right after).
        while (ctx.pos < s.length && (s[ctx.pos] == ' ' || s[ctx.pos] == '\t')) ctx.pos++
        // Optional leading newline (classic form).
        if (ctx.pos < s.length && s[ctx.pos] == '\n') ctx.pos++
        val sb = StringBuilder()
        while (ctx.pos < s.length) {
            val c = s[ctx.pos]
            if (c == '\\' && ctx.pos + 1 < s.length) {
                val nxt = s[ctx.pos + 1]
                when (nxt) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    '\n' -> sb.append('\n')
                    else -> sb.append(nxt)
                }
                ctx.pos += 2
                continue
            }
            if (c == '\n') break
            sb.append(c)
            ctx.pos++
        }
        return sb.toString()
    }

    private fun parseTranslit(ctx: ParseCtx): SedOp.Translit {
        val s = ctx.src
        if (ctx.pos >= s.length) throw SedScriptError("missing delimiter for 'y'")
        val delim = s[ctx.pos]
        ctx.pos++
        val from = StringBuilder()
        while (ctx.pos < s.length && s[ctx.pos] != delim) {
            val c = s[ctx.pos]
            if (c == '\\' && ctx.pos + 1 < s.length) {
                val nxt = s[ctx.pos + 1]
                when (nxt) {
                    'n' -> from.append('\n')
                    't' -> from.append('\t')
                    '\\' -> from.append('\\')
                    else -> from.append(nxt)
                }
                ctx.pos += 2
            } else {
                from.append(c)
                ctx.pos++
            }
        }
        if (ctx.pos >= s.length) throw SedScriptError("unterminated 'y' command")
        ctx.pos++ // skip mid-delim
        val to = StringBuilder()
        while (ctx.pos < s.length && s[ctx.pos] != delim) {
            val c = s[ctx.pos]
            if (c == '\\' && ctx.pos + 1 < s.length) {
                val nxt = s[ctx.pos + 1]
                when (nxt) {
                    'n' -> to.append('\n')
                    't' -> to.append('\t')
                    '\\' -> to.append('\\')
                    else -> to.append(nxt)
                }
                ctx.pos += 2
            } else {
                to.append(c)
                ctx.pos++
            }
        }
        if (ctx.pos >= s.length) throw SedScriptError("unterminated 'y' command (no trailing delimiter)")
        ctx.pos++ // skip end delim
        if (from.length != to.length) {
            throw SedScriptError("'y' source and target strings must have equal length")
        }
        return SedOp.Translit(from.toString(), to.toString())
    }

    private fun parseSubstitute(ctx: ParseCtx): SedOp.Substitute {
        val s = ctx.src
        if (ctx.pos >= s.length) throw SedScriptError("missing pattern delimiter for 's'")
        val delim = s[ctx.pos]
        val pattern = StringBuilder()
        ctx.pos++
        while (ctx.pos < s.length) {
            val c = s[ctx.pos]
            if (c == '\\' && ctx.pos + 1 < s.length) {
                if (s[ctx.pos + 1] == delim) {
                    pattern.append(delim)
                    ctx.pos += 2
                    continue
                }
                pattern.append(c).append(s[ctx.pos + 1])
                ctx.pos += 2
                continue
            }
            if (c == delim) break
            if (c == '\n') throw SedScriptError("unterminated 's' command")
            pattern.append(c)
            ctx.pos++
        }
        if (ctx.pos >= s.length) throw SedScriptError("unterminated 's' command (no replacement delimiter)")
        ctx.pos++
        val repl = StringBuilder()
        while (ctx.pos < s.length) {
            val c = s[ctx.pos]
            if (c == '\\' && ctx.pos + 1 < s.length) {
                if (s[ctx.pos + 1] == delim) {
                    repl.append(delim)
                    ctx.pos += 2
                    continue
                }
                repl.append(c).append(s[ctx.pos + 1])
                ctx.pos += 2
                continue
            }
            if (c == delim) break
            if (c == '\n') break
            repl.append(c)
            ctx.pos++
        }
        if (ctx.pos < s.length && s[ctx.pos] == delim) ctx.pos++
        var global = false
        var print = false
        var nth = 0
        var caseInsensitive = false
        var writeFile: String? = null
        var execAfter = false
        flagLoop@ while (ctx.pos < s.length) {
            val c = s[ctx.pos]
            when {
                c == 'g' -> {
                    global = true
                    ctx.pos++
                }

                c == 'p' -> {
                    print = true
                    ctx.pos++
                }

                c == 'i' || c == 'I' -> {
                    caseInsensitive = true
                    ctx.pos++
                }

                c.isDigit() -> {
                    val start = ctx.pos
                    while (ctx.pos < s.length && s[ctx.pos].isDigit()) ctx.pos++
                    nth = s.substring(start, ctx.pos).toInt()
                }

                c == 'e' -> {
                    if (writeFile != null) throw SedScriptError("'s' flags 'e' and 'w' are mutually exclusive")
                    execAfter = true
                    ctx.pos++
                }

                c == 'w' -> {
                    if (execAfter) throw SedScriptError("'s' flags 'e' and 'w' are mutually exclusive")
                    // `w` consumes the rest of the line as the filename.
                    ctx.pos++
                    while (ctx.pos < s.length && (s[ctx.pos] == ' ' || s[ctx.pos] == '\t')) ctx.pos++
                    val pstart = ctx.pos
                    while (ctx.pos < s.length && s[ctx.pos] != '\n') ctx.pos++
                    val path = s.substring(pstart, ctx.pos).trimEnd()
                    if (path.isEmpty()) throw SedScriptError("'s' flag 'w' requires a file path")
                    writeFile = path
                    break@flagLoop
                }

                c == ';' || c == '\n' || c == '}' -> {
                    break
                }

                c == ' ' || c == '\t' -> {
                    ctx.pos++
                }

                else -> {
                    throw SedScriptError("unknown 's' flag: '$c'")
                }
            }
        }
        val raw = pattern.toString()
        val ere = if (ctx.breMode) breToEre(raw) else raw
        val regex = LinearRegex(ere, if (caseInsensitive) "i" else "")
        return SedOp.Substitute(
            pattern = regex,
            rawPattern = raw,
            replacement = ReplacementTemplate.parse(repl.toString()),
            global = global,
            nth = nth,
            print = print,
            writeFile = writeFile,
            execAfter = execAfter,
        )
    }
}

/**
 * Compiled program: instructions plus index resolution for branches, blocks,
 * and a stable slot per range-addressed instruction so the engine can track
 * "are we currently inside that range" across cycles.
 */
internal class CompiledScript(
    val instructions: List<SedInstruction>,
    /** For BlockBegin at index i, the index *after* the matching BlockEnd. */
    val blockEndAfter: IntArray,
    /** For branches at index i, the resolved target index (one past the label), or instructions.size for end-of-script. */
    val branchTarget: IntArray,
    /** For instructions with a Range address, a unique 0..rangeCount-1 slot. -1 otherwise. */
    val rangeSlot: IntArray,
    val rangeCount: Int,
) {
    companion object {
        fun compile(script: SedScript): CompiledScript {
            val ins = script.commands
            val n = ins.size
            val blockEndAfter = IntArray(n) { -1 }
            val branchTarget = IntArray(n) { -1 }
            val rangeSlot = IntArray(n) { -1 }

            // Resolve block pairs via a stack.
            val stack = ArrayDeque<Int>()
            for (i in 0 until n) {
                when (ins[i].op) {
                    SedOp.BlockBegin -> {
                        stack.addLast(i)
                    }

                    SedOp.BlockEnd -> {
                        if (stack.isEmpty()) throw SedScriptError("unmatched '}'")
                        val opener = stack.removeLast()
                        blockEndAfter[opener] = i + 1
                    }

                    else -> {
                        Unit
                    }
                }
            }
            if (stack.isNotEmpty()) throw SedScriptError("unmatched '{'")

            // Index labels.
            val labels = HashMap<String, Int>()
            for (i in 0 until n) {
                val op = ins[i].op
                if (op is SedOp.Label) {
                    if (labels.containsKey(op.name)) throw SedScriptError("duplicate label: ${op.name}")
                    labels[op.name] = i + 1 // jump to instruction AFTER the label marker
                }
            }
            for (i in 0 until n) {
                val op = ins[i].op
                val target: String? =
                    when (op) {
                        is SedOp.Branch -> {
                            op.target
                        }

                        is SedOp.BranchIfSub -> {
                            op.target
                        }

                        is SedOp.BranchIfNotSub -> {
                            op.target
                        }

                        else -> {
                            continue
                        }
                    }
                if (target == null) {
                    branchTarget[i] = n // end of script
                } else {
                    branchTarget[i] = labels[target]
                        ?: throw SedScriptError("undefined label: $target")
                }
            }

            // Assign range slots.
            var slot = 0
            for (i in 0 until n) {
                if (ins[i].address is Address.Range) {
                    rangeSlot[i] = slot++
                }
            }
            return CompiledScript(ins, blockEndAfter, branchTarget, rangeSlot, slot)
        }
    }
}
