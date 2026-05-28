package com.accucodeai.kash.api.binfmt

import com.accucodeai.kash.api.ShellInvocation

/**
 * Kash's POSIX-shell-flavored exec convention bundled as one handler:
 *
 *  - `#!interpreter [optarg]` first line → dispatch by **basename** through
 *    [ExecRequest.utilityRunner], not by full path. This is the kash
 *    divergence from Linux's `binfmt_script`: `#!/bin/bash` and
 *    `#!/usr/local/bin/bash` both find the registered `bash` utility,
 *    regardless of what's on the host PATH. Falls through to the
 *    shell-script path if no utility-runner is supplied.
 *  - Otherwise → run the file's bytes as a kash shell script via
 *    [ExecRequest.shellRunner]. This is POSIX §2.9.1.1 step 1.e, the
 *    "no-shebang text → /bin/sh" fallback.
 *
 * Priority **1000** — runs last among built-ins so [BinfmtNativeReject]
 * and any binfmt_misc-style userspace handlers get first refusal. This is
 * the universal terminator of the chain for shell-driven calls.
 *
 * Returns [ExecOutcome.NotMine] when the corresponding runner is null
 * (non-shell hosts), so the rest of the chain still works in headless
 * embedded scenarios.
 */
public class BinfmtShellConvention : BinfmtHandler {
    override val name: String = "shell-script"
    override val priority: Int = 1000

    override suspend fun tryExec(req: ExecRequest): ExecOutcome {
        val head = req.headPeek
        // Shebang path.
        if (head.size >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) {
            val util = req.utilityRunner ?: return ExecOutcome.NotMine
            var end = 2
            while (end < head.size && head[end] != '\n'.code.toByte() && head[end] != '\r'.code.toByte()) end++
            val firstLine = head.copyOfRange(2, end).decodeToString().trim()
            if (firstLine.isEmpty()) return ExecOutcome.NotMine
            val parts = firstLine.split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return ExecOutcome.NotMine
            // Basename dispatch: `/usr/bin/env python3` → `env`.
            val interp = parts[0].substringAfterLast('/')
            val interpArgs = parts.drop(1) + listOf(req.path) + req.argv.drop(1)
            return ExecOutcome.Ran(
                util.run(interp, interpArgs, req.stdin, req.stdout, req.stderr),
            )
        }
        // Shell-script fallback.
        val shell = req.shellRunner ?: return ExecOutcome.NotMine
        val text =
            try {
                // Read through the invoking process's facade (not raw
                // machine.fs) so executing a script file is recorded as a
                // READ of its path, attributed to the caller.
                req.parent.fs
                    .readBytes(req.path)
                    .decodeToString()
            } catch (e: Throwable) {
                req.stderr // surfaced by caller; handler just propagates failure
                return ExecOutcome.Ran(126)
            }
        return ExecOutcome.Ran(
            shell.run(
                ShellInvocation(
                    script = text,
                    stdout = req.stdout,
                    scriptName = req.path,
                    positional = req.argv.drop(1),
                    stdin = req.stdin,
                    stderr = req.stderr,
                ),
            ),
        )
    }
}
