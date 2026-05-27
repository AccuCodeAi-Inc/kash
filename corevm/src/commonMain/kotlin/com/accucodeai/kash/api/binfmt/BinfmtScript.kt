package com.accucodeai.kash.api.binfmt

/**
 * Linux `binfmt_script.c` equivalent: parses `#!interpreter [optarg]` on the
 * first line of the file and returns [ExecOutcome.Reexec] so the [KashMachine]
 * restarts the chain with the interpreter as the new target.
 *
 * argv layout matches Linux exactly:
 *  - `argv[0]` = interpreter path
 *  - `argv[1]` = optional single arg from the shebang line (if present)
 *  - `argv[N]` = the original script path
 *  - `argv[N+1..]` = the caller's original argv[1..]
 *
 * Priority 20: runs after the kash-tool fast path (10) and before
 * native-reject (30). The recursion cap lives in [KashMachine.execFile].
 */
public class BinfmtScript : BinfmtHandler {
    override val name: String = "script"
    override val priority: Int = 20

    override suspend fun tryExec(req: ExecRequest): ExecOutcome {
        val head = req.headPeek
        if (head.size < 2 || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) {
            return ExecOutcome.NotMine
        }
        // Slice from after `#!` up to the first newline (LF or CR), then decode UTF-8.
        var end = 2
        while (end < head.size && head[end] != '\n'.code.toByte() && head[end] != '\r'.code.toByte()) end++
        val firstLine = head.copyOfRange(2, end).decodeToString().trim()
        if (firstLine.isEmpty()) return ExecOutcome.NotMine

        // Linux's binfmt_script splits the shebang into "interpreter" and at
        // most one "optarg" — everything past the first space is one arg,
        // not multiple. (This is the historical Linux behavior; FreeBSD and
        // a few others split on every space, but we match Linux.)
        val firstSpace = firstLine.indexOfAny(charArrayOf(' ', '\t'))
        val interpreter: String
        val optArg: String?
        if (firstSpace < 0) {
            interpreter = firstLine
            optArg = null
        } else {
            interpreter = firstLine.substring(0, firstSpace)
            val tail = firstLine.substring(firstSpace + 1).trim()
            optArg = if (tail.isEmpty()) null else tail
        }
        if (interpreter.isEmpty()) return ExecOutcome.NotMine

        val newArgv =
            buildList {
                add(interpreter)
                if (optArg != null) add(optArg)
                add(req.path)
                // Drop the caller's argv[0] (conventionally `req.path`); preserve the tail.
                if (req.argv.size > 1) addAll(req.argv.subList(1, req.argv.size))
            }
        return ExecOutcome.Reexec(newPath = interpreter, newArgv = newArgv)
    }
}
