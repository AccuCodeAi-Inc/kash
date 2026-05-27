package com.accucodeai.kash.conformance

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `recho` — bash test-suite helper. Source at `external/bash/support/recho.c`.
 * Prints `argv[N] = <text>` per arg, with control chars rendered as `^X`.
 * Lives in the conformance test source set so production registries don't
 * carry it; registered by [ScriptPairRunner] / [XfailDiagnosticsRunner].
 */
public class RechoCommand :
    Command,
    CommandSpec {
    override val name: String = "recho"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        for ((i, a) in args.withIndex()) {
            val out = StringBuilder()
            out.append("argv[").append(i + 1).append("] = <")
            for (ch in a) {
                val code = ch.code
                if (code == 0) break // bash treats argv as C strings — NUL terminates.
                when {
                    code < 0x20 -> {
                        out.append('^')
                        out.append((code + 64).toChar())
                    }

                    code == 0x7f -> {
                        out.append("^?")
                    }

                    else -> {
                        out.append(ch)
                    }
                }
            }
            out.append(">\n")
            ctx.stdout.writeUtf8(out.toString())
        }
        return CommandResult()
    }
}
