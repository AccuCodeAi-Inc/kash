package com.accucodeai.kash.tools.ed

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem

/**
 * The ed command loop. One [run] call drives the entire editor session.
 *
 * Errors are signalled per POSIX: the line `?` is emitted to stdout, and a
 * single-line explanation is remembered for the `h` / `H` commands. Help
 * text is original (POSIX defines the behaviour, not the wording — the
 * specific phrasings GNU ed uses are GPL'd).
 */
public class EdInterpreter(
    private val stdin: SuspendSource,
    private val stdout: SuspendSink,
    private val stderr: SuspendSink,
    private val fs: FileSystem,
    private val cwd: String,
    private val prompt: String? = null,
    private val suppressDiagnostics: Boolean = false,
    private val initialFile: String? = null,
) {
    private var buf: EdBuffer = EdBuffer()
    private var undo: EdBuffer? = null
    private var lastError: String? = null
    private var printErrors: Boolean = false
    private var promptOn: Boolean = prompt != null
    private var quitConfirmed: Boolean = false // for q on dirty: first q sets it, second quits

    public suspend fun run(): Int {
        // Load initial file (if any). Failure is non-fatal: ed leaves the
        // buffer empty and prints `?`.
        if (initialFile != null) {
            buf = buf.withFilename(initialFile)
            loadFile(initialFile, replace = true)
        }
        while (true) {
            if (promptOn && prompt != null) {
                stdout.writeUtf8(prompt)
            }
            val line = stdin.readUtf8LineOrNull() ?: break
            val rc = executeCommand(line)
            if (rc == EXIT) return 0
            if (rc != 0 && rc != IGNORE) {
                // Internal error path; keep looping.
            }
        }
        return 0
    }

    private suspend fun executeCommand(input: String): Int {
        try {
            val cursor = EdLineCursor(input)
            val range = EdAddressParser.parseRange(cursor)
            // Any explicit `/re/` or `?re?` in the address resolves AND
            // stores the pattern for later `//` / `??` / `s//.../` reuse.
            range.first?.let { rememberSearch(it) }
            range.second?.let { rememberSearch(it) }
            val first =
                range.first?.let {
                    EdAddressResolver.resolve(it, buf)
                }
            if (range.semicolon && first != null) {
                buf = buf.setDot(first)
            }
            val second =
                range.second?.let {
                    EdAddressResolver.resolve(it, buf)
                }
            val (lo, hi) = normaliseRange(first, second)

            // Skip whitespace before command letter.
            while (!cursor.eof() && cursor.peek() == ' ') cursor.pos++

            if (cursor.eof()) {
                // Empty command (after maybe-addresses): advance to next line and print.
                return cmdEmpty(lo, hi)
            }
            val cmd = cursor.next()
            return dispatch(cmd, lo, hi, cursor)
        } catch (e: EdError) {
            return reportError(e.message ?: "error")
        } catch (e: Exception) {
            return reportError(e.message ?: "internal error")
        }
    }

    private suspend fun dispatch(
        cmd: Char,
        lo: Int?,
        hi: Int?,
        c: EdLineCursor,
    ): Int =
        when (cmd) {
            'a' -> {
                cmdAppend(hi ?: lo)
            }

            'i' -> {
                cmdInsert(hi ?: lo)
            }

            'c' -> {
                cmdChange(lo, hi)
            }

            'd' -> {
                cmdDelete(lo, hi)
            }

            'p' -> {
                cmdPrint(lo, hi, numbered = false, literal = false)
            }

            'n' -> {
                cmdPrint(lo, hi, numbered = true, literal = false)
            }

            'l' -> {
                cmdPrint(lo, hi, numbered = false, literal = true)
            }

            '=' -> {
                cmdEquals(hi ?: lo)
            }

            's' -> {
                cmdSubstitute(lo, hi, c)
            }

            'g' -> {
                cmdGlobal(lo, hi, c, invert = false)
            }

            'v' -> {
                cmdGlobal(lo, hi, c, invert = true)
            }

            'w' -> {
                cmdWrite(lo, hi, c, append = false, quitAfter = false)
            }

            'W' -> {
                cmdWrite(lo, hi, c, append = true, quitAfter = false)
            }

            'r' -> {
                cmdRead(hi ?: lo, c)
            }

            'e' -> {
                cmdEdit(c, force = false)
            }

            'E' -> {
                cmdEdit(c, force = true)
            }

            'f' -> {
                cmdFile(c)
            }

            'q' -> {
                cmdQuit(force = false)
            }

            'Q' -> {
                cmdQuit(force = true)
            }

            'h' -> {
                cmdHelp(toggle = false)
            }

            'H' -> {
                cmdHelp(toggle = true)
            }

            'P' -> {
                promptOn = !promptOn
                0
            }

            'u' -> {
                cmdUndo()
            }

            'm' -> {
                cmdMove(lo, hi, c)
            }

            't' -> {
                cmdTransfer(lo, hi, c)
            }

            'j' -> {
                cmdJoin(lo, hi)
            }

            'k' -> {
                cmdMark(hi ?: lo, c)
            }

            'z' -> {
                cmdScroll(hi ?: lo, c)
            }

            'x' -> {
                reportError("unknown command")
            }

            else -> {
                reportError("unknown command")
            }
        }

    // --- commands -------------------------------------------------------

    private suspend fun cmdEmpty(
        lo: Int?,
        hi: Int?,
    ): Int {
        // If a range was given, just go to and print last line of it.
        val target =
            when {
                hi != null -> hi
                lo != null -> lo
                else -> buf.dot + 1
            }
        if (target < 1 || target > buf.size) return reportError("address out of range")
        buf = buf.setDot(target)
        emitLine(target, numbered = false, literal = false)
        return 0
    }

    private suspend fun cmdAppend(after: Int?): Int {
        val a = after ?: buf.dot
        if (a < 0 || a > buf.size) return reportError("address out of range")
        val text = readInputMode()
        snapshot()
        buf = buf.append(a, text)
        return 0
    }

    private suspend fun cmdInsert(before: Int?): Int {
        val a = (before ?: buf.dot).let { if (it == 0) 0 else it - 1 }
        if (a < 0 || a > buf.size) return reportError("address out of range")
        val text = readInputMode()
        snapshot()
        buf = buf.append(a, text)
        return 0
    }

    private suspend fun cmdChange(
        lo: Int?,
        hi: Int?,
    ): Int {
        val (from, to) = defaultDot(lo, hi)
        if (buf.size == 0) return reportError("buffer empty")
        if (from < 1 || to > buf.size) return reportError("address out of range")
        val text = readInputMode()
        snapshot()
        buf = buf.change(from, to, text)
        return 0
    }

    private suspend fun cmdDelete(
        lo: Int?,
        hi: Int?,
    ): Int {
        val (from, to) = defaultDot(lo, hi)
        if (buf.size == 0) return reportError("buffer empty")
        if (from < 1 || to > buf.size) return reportError("address out of range")
        snapshot()
        buf = buf.delete(from, to)
        return 0
    }

    private suspend fun cmdPrint(
        lo: Int?,
        hi: Int?,
        numbered: Boolean,
        literal: Boolean,
    ): Int {
        val (from, to) = defaultDot(lo, hi)
        if (buf.size == 0) return reportError("buffer empty")
        if (from < 1 || to > buf.size) return reportError("address out of range")
        for (i in from..to) emitLine(i, numbered, literal)
        buf = buf.setDot(to)
        return 0
    }

    private suspend fun cmdEquals(addr: Int?): Int {
        val n = addr ?: buf.size
        stdout.writeLine(n.toString())
        if (n in 1..buf.size) buf = buf.setDot(n)
        return 0
    }

    private suspend fun cmdSubstitute(
        lo: Int?,
        hi: Int?,
        c: EdLineCursor,
    ): Int {
        val (from, to) = defaultDot(lo, hi)
        if (buf.size == 0) return reportError("buffer empty")
        if (from < 1 || to > buf.size) return reportError("address out of range")
        if (c.eof()) return reportError("missing pattern delimiter")
        val delim = c.next()
        val pat = EdAddressParser.readDelimited(c, delim)
        val repl = EdAddressParser.readDelimited(c, delim)
        // Flags: g, p, n (number), or just trailing print.
        var global = false
        var printAfter = false
        var nth = 0
        while (!c.eof()) {
            when (val f = c.peek()) {
                'g' -> {
                    global = true
                    c.next()
                }

                'p' -> {
                    printAfter = true
                    c.next()
                }

                in '0'..'9' -> {
                    var n = 0
                    while (!c.eof() && c.peek()!! in '0'..'9') {
                        n = n * 10 + (c.next() - '0')
                    }
                    nth = n
                }

                ' ' -> {
                    c.next()
                }

                null -> {
                    Unit
                }

                else -> {
                    return reportError("unknown s flag: $f")
                }
            }
            if (c.eof()) break
        }
        val patFinal = pat.ifEmpty { buf.lastSearch ?: return reportError("no previous pattern") }
        val replFinal =
            if (repl == "%") buf.lastReplace ?: return reportError("no previous replacement") else repl
        val regex = EdRegex.compile(patFinal)
        snapshot()
        var anyHit = false
        var lastModified = -1
        val newLines = buf.lines.toMutableList()
        // We may produce extra lines via \n in replacement; build a parallel list.
        val expandedLines = mutableListOf<String>()
        var leadingKeep = newLines.subList(0, from - 1).toMutableList()
        var trailingKeep = newLines.subList(to, newLines.size).toMutableList()
        for (i in from..to) {
            val orig = newLines[i - 1]
            val replaced =
                applySubstitution(orig, regex, replFinal, global, nth)
            if (replaced != orig) {
                anyHit = true
                lastModified = i
            }
            // \n in replacement -> split into multiple lines.
            val pieces = replaced.split('\n')
            expandedLines.addAll(pieces)
        }
        if (!anyHit) return reportError("no match")
        val rebuilt = leadingKeep
        rebuilt.addAll(expandedLines)
        val growthDelta = expandedLines.size - (to - from + 1)
        rebuilt.addAll(trailingKeep)
        // Adjust marks: marks in the modified range may need to shift if
        // multi-line replacements happened. Keep marks outside range stable.
        val newMarks = mutableMapOf<Char, Int>()
        for ((k, v) in buf.marks) {
            when {
                v < from -> {
                    newMarks[k] = v
                }

                v > to -> {
                    newMarks[k] = v + growthDelta
                }

                else -> {
                    // mark in modified range — peg to its original line if still in bounds
                    newMarks[k] = v
                }
            }
        }
        buf =
            buf.copy(
                lines = rebuilt,
                dot = lastModified + growthDelta,
                dirty = true,
                marks = newMarks,
                lastSearch = patFinal,
                lastReplace = replFinal,
            )
        if (printAfter && buf.size > 0) emitLine(buf.dot, numbered = false, literal = false)
        return 0
    }

    private fun applySubstitution(
        line: String,
        regex: Regex,
        repl: String,
        global: Boolean,
        nth: Int,
    ): String {
        val matches = regex.findAll(line).toList()
        if (matches.isEmpty()) return line
        val sb = StringBuilder()
        var idx = 0
        var count = 0
        for ((mi, m) in matches.withIndex()) {
            sb.append(line, idx, m.range.first)
            val shouldReplace =
                when {
                    nth > 0 -> (mi + 1 == nth) || (global && mi + 1 >= nth)
                    global -> true
                    else -> count == 0
                }
            if (shouldReplace) {
                sb.append(EdRegex.substituteOne(m, repl))
                count++
            } else {
                sb.append(m.value)
            }
            idx = m.range.last + 1
        }
        sb.append(line, idx, line.length)
        return sb.toString()
    }

    private suspend fun cmdGlobal(
        lo: Int?,
        hi: Int?,
        c: EdLineCursor,
        invert: Boolean,
    ): Int {
        val from = lo ?: 1
        val to = hi ?: buf.size
        if (buf.size == 0) return reportError("buffer empty")
        if (c.eof()) return reportError("missing pattern delimiter")
        val delim = c.next()
        val pat = EdAddressParser.readDelimited(c, delim)
        val patFinal = pat.ifEmpty { buf.lastSearch ?: return reportError("no previous pattern") }
        val regex = EdRegex.compile(patFinal)
        // The rest of the cursor is the command to run; empty means `p`.
        val cmdText = c.input.substring(c.pos).ifEmpty { "p" }
        // First pass: tag matching lines (by current text identity / index).
        val matchLines = mutableListOf<String>()
        for (i in from..to) {
            val hit = regex.containsMatchIn(buf.line(i))
            if (hit xor invert) matchLines.add(buf.line(i))
        }
        snapshot()
        // Make the pattern available to `s//.../` within the body command.
        buf = buf.withSearch(patFinal)
        // Execute the command on each match. After each iteration we
        // re-locate the line by matching text — POSIX allows the body
        // command to delete/modify lines, so we mark matching lines up
        // front and visit each surviving one by linear search.
        for (target in matchLines) {
            val newIdx = buf.lines.indexOf(target) + 1
            if (newIdx == 0) continue // line was deleted
            buf = buf.setDot(newIdx)
            // Recursive g/v is rejected per POSIX.
            val sub = EdLineCursor(cmdText)
            val parsedRange = EdAddressParser.parseRange(sub)
            val firstA = parsedRange.first?.let { EdAddressResolver.resolve(it, buf) }
            if (parsedRange.semicolon && firstA != null) buf = buf.setDot(firstA)
            val secondA = parsedRange.second?.let { EdAddressResolver.resolve(it, buf) }
            val (slo, shi) = normaliseRange(firstA, secondA)
            while (!sub.eof() && sub.peek() == ' ') sub.pos++
            if (sub.eof()) {
                cmdEmpty(slo, shi)
                continue
            }
            val sc = sub.next()
            if (sc == 'g' || sc == 'v' || sc == 'G' || sc == 'V') {
                return reportError("nested global not allowed")
            }
            dispatch(sc, slo, shi, sub)
        }
        buf = buf.withSearch(patFinal)
        return 0
    }

    private suspend fun cmdWrite(
        lo: Int?,
        hi: Int?,
        c: EdLineCursor,
        append: Boolean,
        quitAfter: Boolean,
    ): Int {
        skipSpaces(c)
        val rawFile = c.input.substring(c.pos).trim()
        val target = rawFile.ifEmpty { buf.filename ?: return reportError("no current filename") }
        val from = lo ?: 1
        val to = hi ?: buf.size
        if (from > to + 1 || (from < 1 && buf.size > 0)) return reportError("address out of range")
        val text =
            if (buf.size == 0) {
                ""
            } else {
                buf.lines.subList(from - 1, to).joinToString(separator = "\n", postfix = "\n")
            }
        try {
            val resolved = resolvePath(target)
            val sink = fs.sink(resolved, append = append)
            try {
                sink.writeUtf8(text)
                sink.flush()
            } finally {
                sink.close()
            }
        } catch (e: Exception) {
            return reportError(e.message ?: "write failed")
        }
        if (buf.filename == null) buf = buf.withFilename(target)
        buf = buf.clean()
        if (!suppressDiagnostics) {
            // POSIX: byte count on a `w`.
            stdout.writeLine(text.length.toString())
        }
        return if (quitAfter) EXIT else 0
    }

    private suspend fun cmdRead(
        after: Int?,
        c: EdLineCursor,
    ): Int {
        skipSpaces(c)
        val rawFile = c.input.substring(c.pos).trim()
        val target = rawFile.ifEmpty { buf.filename ?: return reportError("no current filename") }
        val text: String =
            try {
                val src = fs.source(resolvePath(target))
                try {
                    src.readUtf8Text()
                } finally {
                    src.close()
                }
            } catch (e: Exception) {
                return reportError(e.message ?: "read failed")
            }
        // Drop trailing newline so the buffer doesn't get a phantom empty line.
        val trimmed = if (text.endsWith("\n")) text.dropLast(1) else text
        val lines = if (trimmed.isEmpty()) emptyList() else trimmed.split("\n")
        val a = after ?: buf.size
        snapshot()
        buf = buf.append(a, lines)
        if (buf.filename == null) buf = buf.withFilename(target)
        if (!suppressDiagnostics) stdout.writeLine(text.length.toString())
        return 0
    }

    private suspend fun cmdEdit(
        c: EdLineCursor,
        force: Boolean,
    ): Int {
        if (buf.dirty && !force) {
            quitConfirmed = false
            return reportError("buffer modified")
        }
        skipSpaces(c)
        val rawFile = c.input.substring(c.pos).trim()
        val target = rawFile.ifEmpty { buf.filename ?: return reportError("no current filename") }
        buf = EdBuffer(filename = target)
        undo = null
        loadFile(target, replace = true)
        return 0
    }

    private suspend fun loadFile(
        target: String,
        replace: Boolean,
    ): Int {
        val resolved = resolvePath(target)
        if (!fs.exists(resolved)) {
            if (!suppressDiagnostics) stdout.writeLine("0")
            return 0
        }
        val text: String =
            try {
                val src = fs.source(resolved)
                try {
                    src.readUtf8Text()
                } finally {
                    src.close()
                }
            } catch (e: Exception) {
                return reportError(e.message ?: "read failed")
            }
        val trimmed = if (text.endsWith("\n")) text.dropLast(1) else text
        val lines = if (trimmed.isEmpty()) emptyList() else trimmed.split("\n")
        buf = buf.copy(lines = lines, dot = lines.size, dirty = false)
        if (!suppressDiagnostics) stdout.writeLine(text.length.toString())
        return 0
    }

    private suspend fun cmdFile(c: EdLineCursor): Int {
        skipSpaces(c)
        val name = c.input.substring(c.pos).trim()
        if (name.isEmpty()) {
            stdout.writeLine(buf.filename ?: "")
            return 0
        }
        buf = buf.withFilename(name)
        return 0
    }

    private suspend fun cmdQuit(force: Boolean): Int {
        if (buf.dirty && !force && !quitConfirmed) {
            quitConfirmed = true
            return reportError("buffer modified")
        }
        return EXIT
    }

    private suspend fun cmdHelp(toggle: Boolean): Int {
        if (toggle) {
            printErrors = !printErrors
            if (printErrors && lastError != null) stdout.writeLine(lastError!!)
        } else {
            stdout.writeLine(lastError ?: "no error")
        }
        return 0
    }

    private suspend fun cmdUndo(): Int {
        val prev = undo ?: return reportError("nothing to undo")
        val swap = buf
        buf = prev
        undo = swap
        return 0
    }

    private suspend fun cmdMove(
        lo: Int?,
        hi: Int?,
        c: EdLineCursor,
    ): Int {
        val (from, to) = defaultDot(lo, hi)
        if (from < 1 || to > buf.size) return reportError("address out of range")
        skipSpaces(c)
        val destCursor = EdLineCursor(c.input.substring(c.pos))
        val da = EdAddressParser.parseRange(destCursor)
        val dest = da.first?.let { EdAddressResolver.resolve(it, buf) } ?: return reportError("missing destination")
        if (dest in (from - 1)..to) return reportError("invalid move destination")
        snapshot()
        buf = buf.move(from, to, dest)
        return 0
    }

    private suspend fun cmdTransfer(
        lo: Int?,
        hi: Int?,
        c: EdLineCursor,
    ): Int {
        val (from, to) = defaultDot(lo, hi)
        if (from < 1 || to > buf.size) return reportError("address out of range")
        skipSpaces(c)
        val destCursor = EdLineCursor(c.input.substring(c.pos))
        val da = EdAddressParser.parseRange(destCursor)
        val dest = da.first?.let { EdAddressResolver.resolve(it, buf) } ?: return reportError("missing destination")
        snapshot()
        buf = buf.transfer(from, to, dest)
        return 0
    }

    private suspend fun cmdJoin(
        lo: Int?,
        hi: Int?,
    ): Int {
        if (buf.size == 0) return reportError("buffer empty")
        val from = lo ?: buf.dot
        val to = hi ?: (from + 1)
        if (from < 1 || to > buf.size) return reportError("address out of range")
        if (from == to) return 0
        snapshot()
        buf = buf.join(from, to)
        return 0
    }

    private suspend fun cmdMark(
        addr: Int?,
        c: EdLineCursor,
    ): Int {
        val a = addr ?: buf.dot
        if (a < 1 || a > buf.size) return reportError("address out of range")
        if (c.eof()) return reportError("missing mark letter")
        val ch = c.next()
        buf = buf.setMark(ch, a)
        return 0
    }

    private suspend fun cmdScroll(
        from: Int?,
        c: EdLineCursor,
    ): Int {
        val start = (from ?: buf.dot) + 1
        skipSpaces(c)
        val nText = c.input.substring(c.pos).trim()
        val n = if (nText.isEmpty()) 22 else nText.toIntOrNull() ?: return reportError("bad scroll count")
        val end = minOf(start + n - 1, buf.size)
        if (start > buf.size) return reportError("address out of range")
        for (i in start..end) emitLine(i, numbered = false, literal = false)
        buf = buf.setDot(end)
        return 0
    }

    // --- input mode -----------------------------------------------------

    /** Read lines from stdin until a line containing only `.`. */
    private suspend fun readInputMode(): List<String> {
        val acc = mutableListOf<String>()
        while (true) {
            val line = stdin.readUtf8LineOrNull() ?: break
            if (line == ".") break
            acc.add(line)
        }
        return acc
    }

    // --- helpers --------------------------------------------------------

    private fun snapshot() {
        undo = buf
    }

    private fun normaliseRange(
        first: Int?,
        second: Int?,
    ): Pair<Int?, Int?> {
        if (first != null && second != null) {
            if (second < first) throw EdError("destination before source")
        }
        return first to second
    }

    private fun defaultDot(
        lo: Int?,
        hi: Int?,
    ): Pair<Int, Int> {
        val a = lo ?: buf.dot
        val b = hi ?: a
        return a to b
    }

    private suspend fun emitLine(
        n: Int,
        numbered: Boolean,
        literal: Boolean,
    ) {
        val s = buf.line(n)
        val rendered = if (literal) renderLiteral(s) else s
        val out = if (numbered) "$n\t$rendered" else rendered
        stdout.writeLine(out)
    }

    private fun renderLiteral(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c == '\\' -> {
                    sb.append("\\\\")
                }

                c == '\t' -> {
                    sb.append("\\t")
                }

                c == '\n' -> {
                    sb.append("\\n")
                }

                c.code < 0x20 || c.code == 0x7f -> {
                    sb.append('\\')
                    val v = c.code
                    sb.append((((v shr 6) and 7) + '0'.code).toChar())
                    sb.append((((v shr 3) and 7) + '0'.code).toChar())
                    sb.append(((v and 7) + '0'.code).toChar())
                }

                else -> {
                    sb.append(c)
                }
            }
        }
        sb.append('$')
        return sb.toString()
    }

    private suspend fun reportError(msg: String): Int {
        lastError = msg
        stdout.writeLine("?")
        if (printErrors) stdout.writeLine(msg)
        return 0
    }

    private fun rememberSearch(addr: EdAddress) {
        when (val p = addr.primary) {
            is EdAddress.Primary.SearchForward -> p.re?.let { buf = buf.withSearch(it) }
            is EdAddress.Primary.SearchBackward -> p.re?.let { buf = buf.withSearch(it) }
            else -> Unit
        }
    }

    private fun skipSpaces(c: EdLineCursor) {
        while (!c.eof() && c.peek() == ' ') c.pos++
    }

    private fun resolvePath(p: String): String {
        if (p.startsWith("/")) return p
        return if (cwd.endsWith("/")) "$cwd$p" else "$cwd/$p"
    }

    private companion object {
        const val EXIT = -1
        const val IGNORE = -2
    }
}
