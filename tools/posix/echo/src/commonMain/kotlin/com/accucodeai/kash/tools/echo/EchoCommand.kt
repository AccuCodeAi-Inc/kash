package com.accucodeai.kash.tools.echo

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.io.writeUtf8

public class EchoCommand :
    Command,
    CommandSpec {
    override val name: String = "echo"
    override val kind: CommandKind = CommandKind.BUILTIN
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.BASH_BUILTIN)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var noNewline = false
        var interpretEscapes = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.length < 2 || a[0] != '-') break
            if (!a.drop(1).all { it == 'n' || it == 'e' || it == 'E' }) break
            for (ch in a.drop(1)) {
                when (ch) {
                    'n' -> noNewline = true
                    'e' -> interpretEscapes = true
                    'E' -> interpretEscapes = false
                }
            }
            i++
        }
        val rest = args.drop(i)
        val joined = rest.joinToString(" ")
        val (text, suppressNewlineFromEscape) =
            if (interpretEscapes) unescape(joined) else joined to false
        ctx.stdout.writeUtf8(text)
        if (!noNewline && !suppressNewlineFromEscape) ctx.stdout.writeUtf8("\n")
        return CommandResult()
    }
}

/**
 * Decode bash-style `echo -e` escapes. Handles `\a`, `\b`, `\e`, `\E`, `\f`,
 * `\n`, `\r`, `\t`, `\v`, `\\`, `\0NNN` (1-3 octal digits), and `\xHH`
 * (1-2 hex digits). `\c` truncates output at that point. Unrecognized escapes
 * keep both chars literal.
 */
private fun unescape(s: String): Pair<String, Boolean> {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i + 1 >= s.length) {
            sb.append(c)
            i++
            continue
        }
        when (val n = s[i + 1]) {
            'a' -> {
                sb.append('\u0007')
                i += 2
            }

            'b' -> {
                sb.append('\b')
                i += 2
            }

            'c' -> {
                // `\c` stops output immediately AND suppresses the
                // trailing newline (the `-n` semantics applied locally).
                return sb.toString() to true
            }

            'e', 'E' -> {
                sb.append('\u001B')
                i += 2
            }

            'f' -> {
                sb.append('\u000C')
                i += 2
            }

            'n' -> {
                sb.append('\n')
                i += 2
            }

            'r' -> {
                sb.append('\r')
                i += 2
            }

            't' -> {
                sb.append('\t')
                i += 2
            }

            'v' -> {
                sb.append('\u000B')
                i += 2
            }

            '\\' -> {
                sb.append('\\')
                i += 2
            }

            '0' -> {
                var p = i + 2
                val oct = StringBuilder()
                while (p < s.length && oct.length < 3 && s[p] in '0'..'7') {
                    oct.append(s[p])
                    p++
                }
                if (oct.isEmpty()) {
                    sb.append(Ansi.NUL)
                    i += 2
                } else {
                    sb.append(oct.toString().toInt(8).toChar())
                    i = p
                }
            }

            'x' -> {
                var p = i + 2
                val hex = StringBuilder()
                while (p < s.length && hex.length < 2 &&
                    (s[p] in '0'..'9' || s[p] in 'a'..'f' || s[p] in 'A'..'F')
                ) {
                    hex.append(s[p])
                    p++
                }
                if (hex.isEmpty()) {
                    sb.append('\\').append('x')
                    i += 2
                } else {
                    sb.append(hex.toString().toInt(16).toChar())
                    i = p
                }
            }

            else -> {
                sb.append('\\').append(n)
                i += 2
            }
        }
    }
    return sb.toString() to false
}
