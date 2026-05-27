package com.accucodeai.kash.conformance

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import kotlinx.io.Buffer

/**
 * `xcase` — bash test-suite helper. Reads stdin and writes stdout with each
 * character's case shifted. Not POSIX, not on any real system; the source
 * lives at `external/bash/support/xcase.c` and ships only with the bash
 * tarball as a `make check` utility. It exists to give coproc tests
 * something deterministic, line-oriented, and side-effect-free to feed and
 * drain — `cat` won't do because cat is fully buffered and a parent's
 * `read` would deadlock waiting for a flush.
 *
 * Flags (matching the C source):
 *   - `-u`  uppercase every character
 *   - `-l`  lowercase every character
 *   - `-n`  unbuffered: flush after each byte. Without this, the parent's
 *           `read <&${COPROC[0]}` would never see the bytes a paired
 *           `echo foo >&${COPROC[1]}` wrote, because we'd be batching.
 *
 * `bash/tests/coproc.tests` invokes `coproc xcase -n -u`. Living in
 * `commonTest` (registered into the conformance harness's registry by
 * [ScriptPairRunner]) keeps this bash-test-suite artifact out of any
 * production registry.
 */
public class XcaseCommand :
    Command,
    CommandSpec {
    override val name: String = "xcase"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var upper = false
        var lower = false
        // `-n` is accepted-and-ignored: kash AsyncPipe already enqueues each
        // write atomically, so `xcase -n` and `xcase` behave identically here.
        // We still parse it so the bash test's literal `xcase -n -u` args
        // don't error.
        for (a in args) {
            when (a) {
                "-u" -> {
                    upper = true
                }

                "-l" -> {
                    lower = true
                }

                "-n" -> { /* unbuffered; we're already line-immediate */ }

                else -> {
                    // Unknown flag — bash's xcase exits 1; mirror that so a
                    // typo'd test surfaces.
                    return CommandResult(exitCode = 1)
                }
            }
        }
        if (upper && lower) return CommandResult(exitCode = 1)

        // Read byte-by-byte (the bash C source uses unbuffered stdio when
        // `-n` is set; we treat every invocation that way for simplicity —
        // see comment on `-n` above). Each byte's case is shifted in the
        // ASCII range; bytes outside [A-Za-z] pass through unchanged.
        val inBuf = Buffer()
        val outBuf = Buffer()
        while (true) {
            val n = ctx.stdin.readAtMostTo(inBuf, 1L)
            if (n == -1L) break
            val b = inBuf.readByte().toInt() and 0xff
            val shifted =
                when {
                    upper && b in 'a'.code..'z'.code -> (b - 32)
                    lower && b in 'A'.code..'Z'.code -> (b + 32)
                    else -> b
                }
            outBuf.writeByte(shifted.toByte())
            ctx.stdout.write(outBuf, 1L)
            ctx.stdout.flush()
        }
        return CommandResult(exitCode = 0)
    }
}
