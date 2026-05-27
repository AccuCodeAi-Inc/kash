package com.accucodeai.kash.tools.sed

import com.accucodeai.kash.shared.regex.RegexMatch

/**
 * Result of a script run. [exitCode] is 0 unless `q N` / `Q N` set an explicit
 * exit code; the caller decides what to do with it.
 */
internal class SedRunResult(
    val exitCode: Int,
)

/**
 * Hookpoints for `r FILE` and `w FILE`. The engine itself has no idea what a
 * filesystem looks like — the caller (`SedCommand`) plugs the real
 * [com.accucodeai.kash.fs.FileSystem] in here. A test or non-IO engine use
 * can pass [NoSedIo].
 */
internal interface SedIo {
    /** Return file content as UTF-8 text, or null if missing (POSIX: `r` silently skips). */
    suspend fun readForR(path: String): String?

    /** Write [line] (no trailing newline) followed by a newline to [path]. First call truncates. */
    suspend fun writeForW(
        path: String,
        line: String,
    )

    /**
     * Execute [commandLine] as a shell command and return its stdout (with
     * one trailing newline stripped, GNU-compatible). Return null if the
     * environment doesn't support shell execution — the engine emits an
     * error and aborts the cycle. Used by the `e` flag on `s` and the
     * standalone `e` command.
     */
    suspend fun execForS(commandLine: String): String?

    /** Called after a run completes — close any cached sinks. */
    fun close()
}

internal object NoSedIo : SedIo {
    override suspend fun readForR(path: String): String? = null

    override suspend fun writeForW(
        path: String,
        line: String,
    ) = Unit

    override suspend fun execForS(commandLine: String): String? = null

    override fun close() = Unit
}

/**
 * Executes a parsed [SedScript] against a stream of input lines.
 *
 * Model: GNU/POSIX sed cycle. Read a line into pattern space; execute the
 * (compiled) program, which may mutate pattern space, jump, queue appended
 * text, read more input via `n`/`N`, or quit; then auto-print pattern space
 * unless suppressed; then drain appended text.
 *
 * One-line lookahead is maintained so the `$` address resolves live.
 */
internal class SedEngine(
    script: SedScript,
    private val suppressDefault: Boolean,
    private val io: SedIo = NoSedIo,
) {
    private val compiled: CompiledScript = CompiledScript.compile(script)

    private var pattern: String = ""
    private var hold: String = ""
    private var subMade: Boolean = false
    private val appendQueue = ArrayDeque<String>()

    private val rangeActive: BooleanArray = BooleanArray(compiled.rangeCount)
    private val rangeStartLine: IntArray = IntArray(compiled.rangeCount)

    /** -1, or the line number on which this range just deactivated. */
    private val rangeExitedOnLine: IntArray = IntArray(compiled.rangeCount) { -1 }

    private var lineNo: Int = 0

    suspend fun run(
        lines: Iterator<String>,
        emit: suspend (String) -> Unit,
    ): SedRunResult {
        var nextLine: String? = if (lines.hasNext()) lines.next() else null
        var exitCode = 0

        mainLoop@ while (nextLine != null) {
            pattern = nextLine
            nextLine = if (lines.hasNext()) lines.next() else null
            lineNo++
            subMade = false
            appendQueue.clear()

            cycleLoop@ while (true) {
                var ip = 0
                var skipAutoPrint = false
                var terminate = false
                var silentQuit = false
                var restart = false

                execLoop@ while (ip < compiled.instructions.size) {
                    val cmd = compiled.instructions[ip]
                    val isLast = nextLine == null

                    when (cmd.op) {
                        SedOp.BlockBegin -> {
                            val active = evalAddress(cmd, ip, isLast)
                            ip = if (active) ip + 1 else compiled.blockEndAfter[ip]
                            continue@execLoop
                        }

                        SedOp.BlockEnd, is SedOp.Label -> {
                            ip++
                            continue@execLoop
                        }

                        else -> {
                            Unit
                        }
                    }

                    val active = evalAddress(cmd, ip, isLast)
                    if (!active) {
                        ip++
                        continue@execLoop
                    }

                    when (val op = cmd.op) {
                        is SedOp.Substitute -> {
                            val (after, replaced) = applySubstitute(pattern, op)
                            pattern = after
                            if (replaced) {
                                subMade = true
                                if (op.print) emit(pattern)
                                op.writeFile?.let { io.writeForW(it, pattern) }
                                if (op.execAfter) {
                                    val out = io.execForS(pattern)
                                    if (out == null) {
                                        // Caller didn't wire a shell — bail the cycle cleanly.
                                        skipAutoPrint = true
                                        exitCode = 2
                                        terminate = true
                                        break@execLoop
                                    }
                                    pattern = out
                                }
                            }
                            ip++
                        }

                        SedOp.Delete -> {
                            skipAutoPrint = true
                            break@execLoop
                        }

                        SedOp.DeleteFirst -> {
                            val nl = pattern.indexOf('\n')
                            if (nl < 0) {
                                skipAutoPrint = true
                                break@execLoop
                            } else {
                                pattern = pattern.substring(nl + 1)
                                restart = true
                                break@execLoop
                            }
                        }

                        SedOp.Print -> {
                            emit(pattern)
                            ip++
                        }

                        SedOp.PrintFirst -> {
                            val nl = pattern.indexOf('\n')
                            emit(if (nl < 0) pattern else pattern.substring(0, nl))
                            ip++
                        }

                        SedOp.NextLine -> {
                            if (!suppressDefault) emit(pattern)
                            while (appendQueue.isNotEmpty()) emit(appendQueue.removeFirst())
                            if (nextLine == null) {
                                skipAutoPrint = true
                                terminate = true
                                break@execLoop
                            }
                            pattern = nextLine
                            nextLine = if (lines.hasNext()) lines.next() else null
                            lineNo++
                            ip++
                        }

                        SedOp.AppendNext -> {
                            if (nextLine == null) {
                                // No more input — POSIX: terminate without reading.
                                // We still auto-print the current pattern.
                                terminate = true
                                break@execLoop
                            }
                            pattern = pattern + "\n" + nextLine
                            nextLine = if (lines.hasNext()) lines.next() else null
                            lineNo++
                            ip++
                        }

                        SedOp.PrintLineNum -> {
                            emit(lineNo.toString())
                            ip++
                        }

                        is SedOp.Quit -> {
                            if (op.silent) {
                                skipAutoPrint = true
                                silentQuit = true
                            }
                            exitCode = op.exit
                            terminate = true
                            break@execLoop
                        }

                        is SedOp.Append -> {
                            appendQueue.addLast(op.text)
                            ip++
                        }

                        is SedOp.Insert -> {
                            emit(op.text)
                            ip++
                        }

                        is SedOp.Change -> {
                            val isRange = cmd.address is Address.Range
                            val exitingRange =
                                if (isRange) {
                                    rangeExitedOnLine[compiled.rangeSlot[ip]] == lineNo
                                } else {
                                    true
                                }
                            if (isRange && !exitingRange) {
                                skipAutoPrint = true
                            } else {
                                emit(op.text)
                                skipAutoPrint = true
                            }
                            break@execLoop
                        }

                        is SedOp.Translit -> {
                            pattern = applyTranslit(pattern, op.from, op.to)
                            ip++
                        }

                        SedOp.Hold -> {
                            hold = pattern
                            ip++
                        }

                        SedOp.HoldAppend -> {
                            hold = hold + "\n" + pattern
                            ip++
                        }

                        SedOp.Get -> {
                            pattern = hold
                            ip++
                        }

                        SedOp.GetAppend -> {
                            pattern = pattern + "\n" + hold
                            ip++
                        }

                        SedOp.Exchange -> {
                            val tmp = pattern
                            pattern = hold
                            hold = tmp
                            ip++
                        }

                        is SedOp.ReadFile -> {
                            val text = io.readForR(op.path)
                            if (text != null) {
                                // POSIX: contents are appended after the current
                                // line, as if via `a`. Strip a single trailing
                                // newline since our emitter always adds one.
                                val trimmed = if (text.endsWith("\n")) text.substring(0, text.length - 1) else text
                                if (trimmed.isNotEmpty()) appendQueue.addLast(trimmed)
                            }
                            ip++
                        }

                        is SedOp.WriteFile -> {
                            io.writeForW(op.path, pattern)
                            ip++
                        }

                        is SedOp.PrintUnambiguous -> {
                            emit(unambiguousForm(pattern, op.wrapAt))
                            ip++
                        }

                        is SedOp.Branch -> {
                            ip = compiled.branchTarget[ip]
                        }

                        is SedOp.BranchIfSub -> {
                            if (subMade) {
                                subMade = false
                                ip = compiled.branchTarget[ip]
                            } else {
                                ip++
                            }
                        }

                        is SedOp.BranchIfNotSub -> {
                            if (!subMade) {
                                ip = compiled.branchTarget[ip]
                            } else {
                                subMade = false
                                ip++
                            }
                        }

                        SedOp.BlockBegin,
                        SedOp.BlockEnd,
                        is SedOp.Label,
                        -> {
                            ip++ // unreachable — already handled above
                        }
                    }
                }

                if (restart) {
                    // D restarts the cycle WITHOUT auto-printing and WITHOUT
                    // draining queued appends — those wait for the real
                    // end-of-cycle.
                    continue@cycleLoop
                }
                if (!skipAutoPrint && !suppressDefault && !silentQuit) emit(pattern)
                if (!silentQuit) {
                    while (appendQueue.isNotEmpty()) emit(appendQueue.removeFirst())
                }
                if (terminate) break@mainLoop
                break@cycleLoop
            }
        }
        return SedRunResult(exitCode)
    }

    private fun evalAddress(
        cmd: SedInstruction,
        idx: Int,
        isLast: Boolean,
    ): Boolean {
        val raw =
            when (val a = cmd.address) {
                Address.Every -> true
                is Address.Single -> matchesUnit(a.unit, isLast)
                is Address.Range -> evalRange(a, idx, isLast)
            }
        return if (cmd.negate) !raw else raw
    }

    private fun evalRange(
        a: Address.Range,
        idx: Int,
        isLast: Boolean,
    ): Boolean {
        val slot = compiled.rangeSlot[idx]
        if (!rangeActive[slot]) {
            val started =
                when (val s = a.start) {
                    is AddressUnit.LineNumber -> if (s.n == 0) lineNo == 1 else lineNo == s.n
                    AddressUnit.LastLine -> isLast
                    is AddressUnit.Match -> s.regex.containsMatch(pattern)
                    is AddressUnit.Step -> lineNo >= s.first && (lineNo - s.first) % s.step == 0
                }
            if (!started) return false
            rangeActive[slot] = true
            rangeStartLine[slot] = lineNo
            if (endMatches(a.end, slot, isLast, a.start, justEntered = true)) {
                rangeActive[slot] = false
                rangeExitedOnLine[slot] = lineNo
            }
            return true
        }
        if (endMatches(a.end, slot, isLast, a.start, justEntered = false)) {
            rangeActive[slot] = false
            rangeExitedOnLine[slot] = lineNo
        }
        return true
    }

    private fun endMatches(
        end: RangeEnd,
        slot: Int,
        isLast: Boolean,
        startUnit: AddressUnit,
        justEntered: Boolean,
    ): Boolean =
        when (end) {
            is RangeEnd.Unit -> {
                when (val u = end.u) {
                    is AddressUnit.LineNumber -> {
                        if (u.n < lineNo) true else lineNo == u.n
                    }

                    AddressUnit.LastLine -> {
                        isLast
                    }

                    is AddressUnit.Step -> {
                        lineNo >= u.first && (lineNo - u.first) % u.step == 0
                    }

                    is AddressUnit.Match -> {
                        // POSIX: the end regex is not tested on the very line that
                        // matched the start regex — otherwise `/foo/,/foo/` would be
                        // a one-line range for every match of /foo/. Numeric starts
                        // don't have this restriction.
                        if (justEntered && startUnit is AddressUnit.Match) {
                            false
                        } else {
                            u.regex.containsMatch(pattern)
                        }
                    }
                }
            }

            is RangeEnd.PlusLines -> {
                lineNo >= rangeStartLine[slot] + end.n
            }

            is RangeEnd.TildeStep -> {
                lineNo > rangeStartLine[slot] && lineNo % end.m == 0
            }
        }

    private fun matchesUnit(
        u: AddressUnit,
        isLast: Boolean,
    ): Boolean =
        when (u) {
            AddressUnit.LastLine -> isLast
            is AddressUnit.LineNumber -> lineNo == u.n
            is AddressUnit.Match -> u.regex.containsMatch(pattern)
            is AddressUnit.Step -> lineNo >= u.first && (lineNo - u.first) % u.step == 0
        }

    private fun applySubstitute(
        input: String,
        op: SedOp.Substitute,
    ): Pair<String, Boolean> {
        val matches = op.pattern.findAll(input).toList()
        if (matches.isEmpty()) return input to false
        val chosen: List<RegexMatch> =
            when {
                op.nth > 0 && op.global -> {
                    if (op.nth > matches.size) emptyList() else matches.subList(op.nth - 1, matches.size)
                }

                op.nth > 0 -> {
                    if (op.nth > matches.size) emptyList() else listOf(matches[op.nth - 1])
                }

                op.global -> {
                    matches
                }

                else -> {
                    listOf(matches.first())
                }
            }
        if (chosen.isEmpty()) return input to false
        val sb = StringBuilder()
        var cursor = 0
        for (m in chosen) {
            sb.append(input, cursor, m.offset)
            val groups = m.captures.map { it.text }
            sb.append(op.replacement.render(m.text, groups))
            cursor = m.offset + m.length
            if (m.length == 0 && cursor < input.length) {
                sb.append(input[cursor])
                cursor++
            }
        }
        sb.append(input, cursor, input.length)
        return sb.toString() to true
    }

    private fun unambiguousForm(
        s: String,
        width: Int,
    ): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> {
                    sb.append("\\\\")
                }

                '' -> {
                    sb.append("\\a")
                }

                '\b' -> {
                    sb.append("\\b")
                }

                '' -> {
                    sb.append("\\f")
                }

                '\r' -> {
                    sb.append("\\r")
                }

                '\t' -> {
                    sb.append("\\t")
                }

                '' -> {
                    sb.append("\\v")
                }

                '\n' -> {
                    sb.append("\\n")
                }

                else -> {
                    val code = c.code
                    if (code in 0x20..0x7E) {
                        sb.append(c)
                    } else {
                        sb.append('\\').append(code.toString(8).padStart(3, '0'))
                    }
                }
            }
        }
        sb.append('$')
        if (width <= 0 || sb.length <= width) return sb.toString()
        // Wrap: each non-final chunk is (width-1) chars plus a trailing '\'.
        val text = sb.toString()
        val out = StringBuilder()
        var i = 0
        val chunk = width - 1
        while (text.length - i > width) {
            out.append(text, i, i + chunk).append("\\\n")
            i += chunk
        }
        out.append(text, i, text.length)
        return out.toString()
    }

    private fun applyTranslit(
        input: String,
        from: String,
        to: String,
    ): String {
        if (from.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (c in input) {
            val idx = from.indexOf(c)
            sb.append(if (idx >= 0) to[idx] else c)
        }
        return sb.toString()
    }
}
