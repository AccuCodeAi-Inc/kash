package com.accucodeai.kash.tools.tr

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `tr` — translate, squeeze, or delete characters from stdin.
 *
 * Forms:
 * - `tr SET1 SET2`           translate SET1 → SET2
 * - `tr -d SET1`             delete chars in SET1
 * - `tr -s SET1`             squeeze repeats of chars in SET1
 * - `tr SET1 SET2 -s`        translate then squeeze (squeeze on SET2)
 * - `tr -d -s SET1 SET2`     delete SET1, squeeze SET2
 *
 * Flags: `-c`/`-C` complement SET1, `-d` delete, `-s` squeeze, `-t` truncate SET1.
 *
 * SET syntax: literals, `\\` `\a` `\b` `\f` `\n` `\r` `\t` `\v` and `\NNN` octal,
 * ranges `a-z`, classes `[:alpha:]` etc., repeats `[x*N]` and `[x*]`.
 * Equivalence classes `[=c=]` are accepted but treated as the single char `c`.
 */
public class TrCommand :
    Command,
    CommandSpec {
    override val name: String = "tr"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var complement = false
        var delete = false
        var squeeze = false
        var truncate = false
        val positional = mutableListOf<String>()
        var i = 0
        var endOfOpts = false
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                positional += a
            } else if (a == "--") {
                endOfOpts = true
            } else if (a.startsWith("-") && a.length > 1 && a != "-") {
                for (c in a.substring(1)) {
                    when (c) {
                        'c', 'C' -> {
                            complement = true
                        }

                        'd' -> {
                            delete = true
                        }

                        's' -> {
                            squeeze = true
                        }

                        't' -> {
                            truncate = true
                        }

                        else -> {
                            ctx.stderr.writeUtf8("tr: invalid option -- '$c'\n")
                            return CommandResult(exitCode = 2)
                        }
                    }
                }
            } else {
                positional += a
            }
            i++
        }

        if (positional.isEmpty()) {
            ctx.stderr.writeUtf8("tr: missing operand\n")
            return CommandResult(exitCode = 2)
        }
        if (delete && !squeeze && positional.size != 1) {
            ctx.stderr.writeUtf8("tr: extra operand '${positional.getOrNull(1) ?: ""}'\n")
            return CommandResult(exitCode = 2)
        }
        if (delete && squeeze && positional.size != 2) {
            ctx.stderr.writeUtf8("tr: with -d -s, two SETs required\n")
            return CommandResult(exitCode = 2)
        }
        if (!delete && !squeeze && positional.size != 2) {
            ctx.stderr.writeUtf8("tr: usage: tr SET1 SET2\n")
            return CommandResult(exitCode = 2)
        }
        if (!delete && squeeze && positional.size !in 1..2) {
            ctx.stderr.writeUtf8("tr: with -s, 1 or 2 SETs required\n")
            return CommandResult(exitCode = 2)
        }

        val set1Raw: String = positional[0]
        val set2Raw: String? =
            when {
                delete && squeeze -> positional[1]
                delete -> null
                !delete && squeeze && positional.size == 1 -> null
                else -> positional[1]
            }

        val set1Chars: List<Char> =
            try {
                expandSet(set1Raw, fillTo = -1)
            } catch (e: TrParseException) {
                ctx.stderr.writeUtf8("tr: ${e.message}\n")
                return CommandResult(exitCode = 1)
            }

        val set2Chars: List<Char>? =
            if (set2Raw == null) {
                null
            } else {
                try {
                    expandSet(set2Raw, fillTo = set1Chars.size)
                } catch (e: TrParseException) {
                    ctx.stderr.writeUtf8("tr: ${e.message}\n")
                    return CommandResult(exitCode = 1)
                }
            }

        val text = ctx.stdin.readUtf8Text()
        val out = StringBuilder(text.length)

        val set1Effective: List<Char> =
            if (!delete && set2Chars != null && truncate && set1Chars.size > set2Chars.size) {
                set1Chars.subList(0, set2Chars.size)
            } else {
                set1Chars
            }

        val translateMap: Map<Char, Char>? =
            if (!delete && set2Chars != null) {
                buildTranslateMap(set1Effective, set2Chars)
            } else {
                null
            }

        val set1MemberSet: Set<Char> = set1Chars.toSet()
        val set2MemberSet: Set<Char>? = set2Chars?.toSet()

        val squeezeMembers: Set<Char>? =
            when {
                !squeeze -> null
                delete -> set2MemberSet
                set2Chars != null -> set2MemberSet
                else -> set1MemberSet
            }

        var prevOut: Char? = null
        var idx = 0
        while (idx < text.length) {
            val c = text[idx]
            idx++
            // Delete phase
            if (delete) {
                val inSet1 = c in set1MemberSet
                val drop = if (complement) !inSet1 else inSet1
                if (drop) continue
            }
            // Translate phase
            val mapped: Char =
                if (translateMap != null && set2Chars != null) {
                    if (complement) {
                        if (c in set1MemberSet) c else set2Chars.last()
                    } else {
                        translateMap[c] ?: c
                    }
                } else {
                    c
                }
            // Squeeze phase
            if (squeezeMembers != null && mapped in squeezeMembers && prevOut == mapped) {
                continue
            }
            out.append(mapped)
            prevOut = mapped
        }

        ctx.stdout.writeUtf8(out.toString())
        return CommandResult()
    }

    private fun buildTranslateMap(
        set1: List<Char>,
        set2: List<Char>,
    ): Map<Char, Char> {
        val m = HashMap<Char, Char>(set1.size)
        if (set2.isEmpty()) return m
        val lastS2 = set2.last()
        for ((i, c) in set1.withIndex()) {
            val t = if (i < set2.size) set2[i] else lastS2
            if (c !in m) m[c] = t
        }
        return m
    }

    private class TrParseException(
        message: String,
    ) : RuntimeException(message)

    private fun expandSet(
        raw: String,
        fillTo: Int,
    ): List<Char> {
        // Note: equivalence class [=c=] is accepted as a single char `c` (POSIX exotic, simplified).
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\') {
                val (ch, consumed) = parseEscape(raw, i)
                // After escape, check for range like \n-z
                val next = i + consumed
                if (next + 1 < raw.length && raw[next] == '-' && raw[next + 1] != ']') {
                    val (endCh, c2) = parseCharAt(raw, next + 1)
                    tokens += Token.Range(ch, endCh)
                    i = next + 1 + c2
                    continue
                }
                tokens += Token.Lit(ch)
                i += consumed
                continue
            }
            if (c == '[' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == ':') {
                    val end = raw.indexOf(":]", i + 2)
                    if (end >= 0) {
                        tokens += Token.Class(raw.substring(i + 2, end))
                        i = end + 2
                        continue
                    }
                }
                if (next == '=') {
                    val end = raw.indexOf("=]", i + 2)
                    if (end >= 0) {
                        val inner = raw.substring(i + 2, end)
                        if (inner.isNotEmpty()) tokens += Token.Lit(inner[0])
                        i = end + 2
                        continue
                    }
                }
                val close = raw.indexOf(']', i + 1)
                if (close >= 0) {
                    val inner = raw.substring(i + 1, close)
                    val star = inner.indexOf('*')
                    if (star >= 1) {
                        val charPart = inner.substring(0, star)
                        val ch: Char? =
                            if (charPart.startsWith("\\")) {
                                parseEscape(charPart, 0).first
                            } else if (charPart.length == 1) {
                                charPart[0]
                            } else {
                                null
                            }
                        if (ch != null) {
                            val countStr = inner.substring(star + 1)
                            if (countStr.isEmpty()) {
                                tokens += Token.Fill(ch)
                            } else {
                                val n = parseRepeatCount(countStr)
                                if (n != null) {
                                    tokens += Token.Repeat(ch, n)
                                } else {
                                    tokens += Token.Lit(c)
                                    i++
                                    continue
                                }
                            }
                            i = close + 1
                            continue
                        }
                    }
                }
            }
            if (i + 2 < raw.length && raw[i + 1] == '-' && raw[i + 2] != ']') {
                val (endCh, c2) = parseCharAt(raw, i + 2)
                tokens += Token.Range(c, endCh)
                i += 2 + c2
                continue
            }
            tokens += Token.Lit(c)
            i++
        }

        val expandedNoFill = mutableListOf<Char>()
        val fillSlots = mutableListOf<Int>()
        val fillChars = mutableListOf<Char>()
        for (tok in tokens) {
            when (tok) {
                is Token.Lit -> {
                    expandedNoFill += tok.c
                }

                is Token.Range -> {
                    if (tok.start > tok.end) {
                        throw TrParseException(
                            "range-endpoints of '${tok.start}-${tok.end}' are in reverse collating sequence order",
                        )
                    }
                    for (x in tok.start.code..tok.end.code) expandedNoFill += x.toChar()
                }

                is Token.Class -> {
                    expandedNoFill += expandClass(tok.name)
                }

                is Token.Repeat -> {
                    repeat(tok.n) { expandedNoFill += tok.c }
                }

                is Token.Fill -> {
                    fillSlots += expandedNoFill.size
                    fillChars += tok.c
                }
            }
        }

        if (fillSlots.isEmpty()) return expandedNoFill

        val target = if (fillTo >= 0) fillTo else expandedNoFill.size
        val deficit = (target - expandedNoFill.size).coerceAtLeast(0)
        val result = expandedNoFill.toMutableList()
        if (fillSlots.size == 1) {
            val ch = fillChars[0]
            val pos = fillSlots[0]
            repeat(deficit) { result.add(pos, ch) }
        } else {
            val per = deficit / fillSlots.size
            val rem = deficit % fillSlots.size
            for (idx in fillSlots.indices.reversed()) {
                val pos = fillSlots[idx]
                val n = per + if (idx < rem) 1 else 0
                val ch = fillChars[idx]
                repeat(n) { result.add(pos, ch) }
            }
        }
        return result
    }

    private fun parseRepeatCount(s: String): Int? {
        if (s.isEmpty()) return null
        return try {
            if (s.startsWith("0") && s.length > 1) s.toInt(8) else s.toInt()
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun parseCharAt(
        raw: String,
        i: Int,
    ): Pair<Char, Int> {
        if (raw[i] == '\\') return parseEscape(raw, i)
        return raw[i] to 1
    }

    private fun parseEscape(
        raw: String,
        i: Int,
    ): Pair<Char, Int> {
        if (i + 1 >= raw.length) return '\\' to 1
        val n = raw[i + 1]
        if (n in '0'..'7') {
            var j = i + 1
            var value = 0
            var count = 0
            while (j < raw.length && count < 3 && raw[j] in '0'..'7') {
                value = value * 8 + (raw[j].code - '0'.code)
                j++
                count++
            }
            return (value and 0xFF).toChar() to (j - i)
        }
        val ch =
            when (n) {
                '\\' -> '\\'
                'a' -> 0x07.toChar()
                'b' -> '\b'
                'f' -> 0x0C.toChar()
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'v' -> 0x0B.toChar()
                else -> n
            }
        return ch to 2
    }

    private fun expandClass(name: String): List<Char> =
        when (name) {
            "alpha" -> {
                ('A'..'Z').toList() + ('a'..'z').toList()
            }

            "upper" -> {
                ('A'..'Z').toList()
            }

            "lower" -> {
                ('a'..'z').toList()
            }

            "digit" -> {
                ('0'..'9').toList()
            }

            "alnum" -> {
                ('0'..'9').toList() + ('A'..'Z').toList() + ('a'..'z').toList()
            }

            "xdigit" -> {
                ('0'..'9').toList() + ('A'..'F').toList() + ('a'..'f').toList()
            }

            "space" -> {
                listOf(' ', '\t', '\n', 0x0B.toChar(), 0x0C.toChar(), '\r')
            }

            "blank" -> {
                listOf(' ', '\t')
            }

            "cntrl" -> {
                (0..31).map { it.toChar() } + listOf(127.toChar())
            }

            "print" -> {
                (32..126).map { it.toChar() }
            }

            "graph" -> {
                (33..126).map { it.toChar() }
            }

            "punct" -> {
                (33..126).map { it.toChar() }.filter { c ->
                    !c.isLetterOrDigit() && c != ' '
                }
            }

            else -> {
                throw TrParseException("invalid character class '$name'")
            }
        }

    private sealed class Token {
        data class Lit(
            val c: Char,
        ) : Token()

        data class Range(
            val start: Char,
            val end: Char,
        ) : Token()

        data class Class(
            val name: String,
        ) : Token()

        data class Repeat(
            val c: Char,
            val n: Int,
        ) : Token()

        data class Fill(
            val c: Char,
        ) : Token()
    }
}
