package com.accucodeai.kash.tools.vi

/**
 * Result of running an ex command. The editor loop reads this to decide
 * whether to keep editing, save, quit, or report a message.
 */
internal data class ExResult(
    val buf: ViBuffer,
    val message: String = "",
    val quit: Boolean = false,
    val forceQuit: Boolean = false,
    val saveRequested: Boolean = false,
    val saveAs: String? = null,
    val openFile: String? = null,
)

internal sealed interface ExParsed {
    data class Goto(
        val line: Int,
    ) : ExParsed

    data class Write(
        val path: String?,
    ) : ExParsed

    data object Quit : ExParsed

    data object QuitForce : ExParsed

    data object WriteQuit : ExParsed

    data class Edit(
        val path: String?,
    ) : ExParsed

    data class Substitute(
        val pattern: String,
        val replacement: String,
        val global: Boolean,
        val wholeFile: Boolean,
    ) : ExParsed

    data class Set(
        val option: String,
        val on: Boolean,
    ) : ExParsed

    data object NoHighlight : ExParsed

    data object Help : ExParsed

    data class Unknown(
        val raw: String,
    ) : ExParsed
}

internal object ViExCommands {
    /**
     * Parse the raw ex command (without the leading `:`).
     * Supports a leading numeric address-only form (`:42`).
     */
    fun parse(raw: String): ExParsed {
        val s = raw.trim()
        if (s.isEmpty()) return ExParsed.Unknown(s)
        // pure line number → :N
        if (s.all { it.isDigit() }) return ExParsed.Goto(s.toInt())
        // %s/pat/rep/flags
        if (s.startsWith("%s")) {
            return parseSubst(s.substring(2), wholeFile = true) ?: ExParsed.Unknown(s)
        }
        // s/pat/rep/flags  (current line)
        if (s.startsWith("s/") || (s.startsWith("s") && s.length > 1 && !s[1].isLetter())) {
            return parseSubst(s.substring(1), wholeFile = false) ?: ExParsed.Unknown(s)
        }
        // Tokenized command + optional argument
        val (cmd, arg) = splitCmd(s)
        return when (cmd) {
            "w", "write" -> ExParsed.Write(arg.ifEmpty { null })
            "q", "quit" -> ExParsed.Quit
            "q!" -> ExParsed.QuitForce
            "wq", "x", "wq!" -> ExParsed.WriteQuit
            "e", "edit" -> ExParsed.Edit(arg.ifEmpty { null })
            "set" -> parseSet(arg) ?: ExParsed.Unknown(s)
            "noh", "nohl", "nohlsearch" -> ExParsed.NoHighlight
            "help", "h" -> ExParsed.Help
            else -> ExParsed.Unknown(s)
        }
    }

    private fun splitCmd(s: String): Pair<String, String> {
        val i = s.indexOf(' ')
        return if (i < 0) s to "" else s.substring(0, i) to s.substring(i + 1).trim()
    }

    private fun parseSubst(
        rest: String,
        wholeFile: Boolean,
    ): ExParsed.Substitute? {
        // rest is "/pat/rep/flags" — first char is the delimiter.
        if (rest.isEmpty()) return null
        val delim = rest[0]
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 1
        while (i < rest.length && parts.size < 2) {
            val c = rest[i]
            if (c == '\\' && i + 1 < rest.length) {
                sb.append(rest[i + 1])
                i += 2
                continue
            }
            if (c == delim) {
                parts.add(sb.toString())
                sb.clear()
                i++
                continue
            }
            sb.append(c)
            i++
        }
        if (parts.size < 2) {
            // Pattern but no replacement delimiter — treat as empty replacement
            parts.add(sb.toString())
            sb.clear()
            if (parts.size < 2) parts.add("")
        }
        val flags = rest.substring(i)
        val global = flags.contains('g')
        return ExParsed.Substitute(
            pattern = parts[0],
            replacement = parts.getOrElse(1) { "" },
            global = global,
            wholeFile = wholeFile,
        )
    }

    private fun parseSet(arg: String): ExParsed.Set? {
        val a = arg.trim()
        return when (a) {
            "number", "nu" -> ExParsed.Set("number", true)
            "nonumber", "nonu" -> ExParsed.Set("number", false)
            else -> null
        }
    }

    // ---------- Executor ----------

    fun execute(
        cmd: ExParsed,
        buf: ViBuffer,
    ): ExResult =
        when (cmd) {
            is ExParsed.Goto -> {
                val pos = ViMotions.gotoLine(buf, cmd.line)
                ExResult(buf.copy(cursorRow = pos.row, cursorCol = pos.col, desiredCol = pos.col))
            }

            is ExParsed.Write -> {
                ExResult(buf, saveRequested = true, saveAs = cmd.path)
            }

            ExParsed.Quit -> {
                if (buf.dirty) {
                    ExResult(buf, message = "No write since last change (add ! to override)")
                } else {
                    ExResult(buf, quit = true)
                }
            }

            ExParsed.QuitForce -> {
                ExResult(buf, quit = true, forceQuit = true)
            }

            ExParsed.WriteQuit -> {
                ExResult(buf, saveRequested = true, quit = true)
            }

            is ExParsed.Edit -> {
                ExResult(buf, openFile = cmd.path ?: buf.filename)
            }

            is ExParsed.Substitute -> {
                doSubstitute(buf, cmd)
            }

            is ExParsed.Set -> {
                doSet(buf, cmd)
            }

            ExParsed.NoHighlight -> {
                ExResult(buf.copy(lastSearch = null))
            }

            ExParsed.Help -> {
                ExResult(
                    buf,
                    message =
                        "kash vi: h j k l move; i a o insert; ESC normal; :w save :q quit :wq save&quit " +
                            ":%s/p/r/g sub; /pat search; dd yy p paste; u undo Ctrl-R redo",
                )
            }

            is ExParsed.Unknown -> {
                ExResult(buf, message = "Not an editor command: ${cmd.raw}")
            }
        }

    private fun doSubstitute(
        buf: ViBuffer,
        s: ExParsed.Substitute,
    ): ExResult {
        val rx =
            runCatching { Regex(s.pattern) }.getOrNull()
                ?: return ExResult(buf, message = "Bad regex: ${s.pattern}")
        val b = buf.pushUndo()
        var totalReplacements = 0
        val rows = if (s.wholeFile) (0 until b.rowCount) else (b.cursorRow..b.cursorRow)
        val newLines = b.lines.toMutableList()
        var anyChange = false
        for (r in rows) {
            val line = newLines[r]
            val (newLine, n) =
                if (s.global) {
                    val updated = line.replace(rx, s.replacement)
                    val count = rx.findAll(line).count()
                    updated to count
                } else {
                    val m = rx.find(line)
                    if (m == null) {
                        line to 0
                    } else {
                        val replaced =
                            line.substring(0, m.range.first) + s.replacement + line.substring(m.range.last + 1)
                        replaced to 1
                    }
                }
            if (n > 0) {
                newLines[r] = newLine
                totalReplacements += n
                anyChange = true
            }
        }
        if (!anyChange) {
            // No undo push effect — restore original by popping the snapshot we just pushed.
            val popped = b.copy(undoStack = b.undoStack.dropLast(1))
            return ExResult(popped, message = "Pattern not found: ${s.pattern}")
        }
        return ExResult(
            b.copy(lines = newLines, dirty = true),
            message = "$totalReplacements substitution${if (totalReplacements == 1) "" else "s"}",
        )
    }

    private fun doSet(
        buf: ViBuffer,
        s: ExParsed.Set,
    ): ExResult =
        when (s.option) {
            "number" -> ExResult(buf.copy(showLineNumbers = s.on))
            else -> ExResult(buf, message = "Unknown option: ${s.option}")
        }
}
