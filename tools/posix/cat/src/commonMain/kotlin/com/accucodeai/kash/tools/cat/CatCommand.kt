package com.accucodeai.kash.tools.cat

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.transferFrom
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

public class CatCommand :
    Command,
    CommandSpec {
    override val name: String = "cat"
    override val kind: CommandKind = CommandKind.BUILTIN
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private data class Flags(
        val showNonprinting: Boolean = false,
        val showEnds: Boolean = false,
        val showTabs: Boolean = false,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var flags = Flags()
        val files = mutableListOf<String>()
        var i = 0
        var optionsEnded = false
        while (i < args.size) {
            val a = args[i]
            when {
                optionsEnded || a == "-" || !a.startsWith("-") || a.length == 1 -> {
                    files += a
                }

                a == "--" -> {
                    optionsEnded = true
                }

                a == "--show-nonprinting" -> {
                    flags = flags.copy(showNonprinting = true)
                }

                a == "--show-ends" -> {
                    flags = flags.copy(showEnds = true)
                }

                a == "--show-tabs" -> {
                    flags = flags.copy(showTabs = true)
                }

                a == "--show-all" -> {
                    flags = flags.copy(showNonprinting = true, showEnds = true, showTabs = true)
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("cat: unrecognized option '$a'\n")
                    return CommandResult(exitCode = 1)
                }

                else -> {
                    // Bundled short flags: -vET, -vt, etc.
                    for (c in a.drop(1)) {
                        flags =
                            when (c) {
                                'v' -> {
                                    flags.copy(showNonprinting = true)
                                }

                                'E' -> {
                                    flags.copy(showEnds = true)
                                }

                                'T' -> {
                                    flags.copy(showTabs = true)
                                }

                                'A' -> {
                                    flags.copy(showNonprinting = true, showEnds = true, showTabs = true)
                                }

                                'e' -> {
                                    flags.copy(showNonprinting = true, showEnds = true)
                                }

                                't' -> {
                                    flags.copy(showNonprinting = true, showTabs = true)
                                }

                                'u' -> {
                                    flags
                                }

                                // unbuffered — no-op
                                else -> {
                                    ctx.stderr.writeUtf8("cat: invalid option -- '$c'\n")
                                    return CommandResult(exitCode = 1)
                                }
                            }
                    }
                }
            }
            i++
        }

        suspend fun pump(src: SuspendSource) {
            if (flags == Flags()) {
                ctx.stdout.transferFrom(src)
            } else {
                copyTransformed(src, ctx, flags)
            }
        }

        if (files.isEmpty()) {
            pump(ctx.stdin)
            return CommandResult()
        }
        var anyErr = false
        for (arg in files) {
            if (arg == "-") {
                pump(ctx.stdin)
                continue
            }
            val path = Paths.resolve(ctx.process.cwd, arg)
            try {
                pump(ctx.process.fs.source(path))
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("cat: $arg: No such file or directory\n")
                anyErr = true
                continue
            }
        }
        return CommandResult(exitCode = if (anyErr) 1 else 0)
    }

    /**
     * Stream [src] to stdout with byte-level transforms:
     *   -v   non-printing bytes → caret notation (`^G` = BEL, `^?` = DEL) and
     *        high-bit bytes prefixed `M-`. `\n` and `\t` are exempt; `-T`
     *        replaces `\t` with `^I`. `-E` appends `$` immediately before `\n`.
     */
    private suspend fun copyTransformed(
        src: SuspendSource,
        ctx: CommandContext,
        flags: Flags,
    ) {
        val buf = Buffer()
        val out = Buffer()
        while (src.readAtMostTo(buf, 8 * 1024L) > 0L) {
            while (!buf.exhausted()) {
                val v = buf.readByte().toInt() and 0xff
                when {
                    v == 0x0A -> {
                        if (flags.showEnds) out.writeUtf8("$")
                        out.writeUtf8("\n")
                    }

                    v == 0x09 -> {
                        if (flags.showTabs) out.writeUtf8("^I") else out.writeByte(v.toByte())
                    }

                    !flags.showNonprinting -> {
                        out.writeByte(v.toByte())
                    }

                    v < 0x20 -> {
                        out.writeUtf8("^")
                        out.writeByte((v + 0x40).toByte())
                    }

                    v == 0x7F -> {
                        out.writeUtf8("^?")
                    }

                    v >= 0x80 -> {
                        out.writeUtf8("M-")
                        val low = v and 0x7F
                        when {
                            low < 0x20 -> {
                                out.writeUtf8("^")
                                out.writeByte((low + 0x40).toByte())
                            }

                            low == 0x7F -> {
                                out.writeUtf8("^?")
                            }

                            else -> {
                                out.writeByte(low.toByte())
                            }
                        }
                    }

                    else -> {
                        out.writeByte(v.toByte())
                    }
                }
            }
            ctx.stdout.writeBytes(out.readByteArray())
            kotlinx.coroutines.yield()
        }
    }
}
