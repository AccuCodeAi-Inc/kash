package com.accucodeai.kash.tools.make

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

public class MakeCommand :
    Command,
    CommandSpec {
    override val name: String = "make"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE, CommandTag.FS_WRITE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val files = mutableListOf<String>()
        val targets = mutableListOf<String>()
        var workdir: String? = null
        var keepGoing = false
        var stopOnFirstError = false
        var dryRun = false
        var silent = false
        var ignoreErrors = false
        var envOverride = false
        var noBuiltinRules = false
        var touchMode = false
        var questionMode = false
        var printDatabase = false
        val cliMacros = LinkedHashMap<String, Pair<MacroFlavor, String>>()
        var jobs = 1

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--help" -> {
                    ctx.stdout.writeUtf8("Usage: make [-f FILE] [-C DIR] [-knsiepqrtS] [VAR=value] [target...]\n")
                    return CommandResult(0)
                }

                a == "--version" -> {
                    ctx.stdout.writeUtf8("kash make 1.0\n")
                    return CommandResult(0)
                }

                a == "-f" || a == "--file" || a == "--makefile" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("make: option requires an argument -- 'f'\n")
                        return CommandResult(2)
                    }
                    files += args[i + 1]
                    i += 2
                    continue
                }

                a.startsWith("--file=") -> {
                    files += a.substring("--file=".length)
                }

                a.startsWith("--makefile=") -> {
                    files += a.substring("--makefile=".length)
                }

                a == "-C" || a == "--directory" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("make: option requires an argument -- 'C'\n")
                        return CommandResult(2)
                    }
                    workdir = args[i + 1]
                    i += 2
                    continue
                }

                a.startsWith("--directory=") -> {
                    workdir = a.substring("--directory=".length)
                }

                a == "-j" || a == "--jobs" -> {
                    if (i + 1 < args.size && args[i + 1].toIntOrNull() != null) {
                        jobs = args[i + 1].toInt()
                        i += 2
                        continue
                    }
                    jobs = 1
                }

                a.startsWith("--jobs=") -> {
                    jobs = a.substring("--jobs=".length).toIntOrNull() ?: 1
                }

                a.startsWith("-j") && a.length > 2 -> {
                    jobs = a.substring(2).toIntOrNull() ?: 1
                }

                a == "-k" || a == "--keep-going" -> {
                    keepGoing = true
                }

                a == "-S" || a == "--no-keep-going" || a == "--stop" -> {
                    keepGoing = false
                    stopOnFirstError = true
                }

                a == "-n" || a == "--just-print" || a == "--dry-run" || a == "--recon" -> {
                    dryRun = true
                }

                a == "-s" || a == "--silent" || a == "--quiet" -> {
                    silent = true
                }

                a == "-i" || a == "--ignore-errors" -> {
                    ignoreErrors = true
                }

                a == "-e" || a == "--environment-overrides" -> {
                    envOverride = true
                }

                a == "-r" || a == "--no-builtin-rules" -> {
                    noBuiltinRules = true
                }

                a == "-R" || a == "--no-builtin-variables" -> {
                    noBuiltinRules = true
                }

                a == "-t" || a == "--touch" -> {
                    touchMode = true
                }

                a == "-q" || a == "--question" -> {
                    questionMode = true
                }

                a == "-p" || a == "--print-data-base" -> {
                    printDatabase = true
                }

                a == "-w" || a == "--print-directory" -> {
                    Unit
                }

                a == "--no-print-directory" -> {
                    Unit
                }

                a == "--" -> {
                    i++
                    while (i < args.size) {
                        consumeArg(args[i], targets, cliMacros)
                        i++
                    }
                    break
                }

                a.startsWith("-") && a.length > 1 && a[1] != '=' -> {
                    var consumedExtra = false
                    for ((idx, ch) in a.drop(1).withIndex()) {
                        when (ch) {
                            'k' -> {
                                keepGoing = true
                            }

                            'S' -> {
                                keepGoing = false
                                stopOnFirstError = true
                            }

                            'n' -> {
                                dryRun = true
                            }

                            's' -> {
                                silent = true
                            }

                            'i' -> {
                                ignoreErrors = true
                            }

                            'e' -> {
                                envOverride = true
                            }

                            'r', 'R' -> {
                                noBuiltinRules = true
                            }

                            't' -> {
                                touchMode = true
                            }

                            'q' -> {
                                questionMode = true
                            }

                            'p' -> {
                                printDatabase = true
                            }

                            'w' -> {
                                Unit
                            }

                            'f' -> {
                                val rest = a.substring(idx + 2)
                                if (rest.isNotEmpty()) {
                                    files += rest
                                } else if (i + 1 < args.size) {
                                    files += args[i + 1]
                                    consumedExtra = true
                                } else {
                                    ctx.stderr.writeUtf8("make: option requires an argument -- 'f'\n")
                                    return CommandResult(2)
                                }
                                break
                            }

                            'C' -> {
                                val rest = a.substring(idx + 2)
                                if (rest.isNotEmpty()) {
                                    workdir = rest
                                } else if (i + 1 < args.size) {
                                    workdir = args[i + 1]
                                    consumedExtra = true
                                } else {
                                    ctx.stderr.writeUtf8("make: option requires an argument -- 'C'\n")
                                    return CommandResult(2)
                                }
                                break
                            }

                            'j' -> {
                                val rest = a.substring(idx + 2)
                                jobs =
                                    if (rest.isNotEmpty()) {
                                        rest.toIntOrNull() ?: 1
                                    } else if (i + 1 < args.size && args[i + 1].toIntOrNull() != null) {
                                        consumedExtra = true
                                        args[i + 1].toInt()
                                    } else {
                                        1
                                    }
                                break
                            }

                            else -> {
                                ctx.stderr.writeUtf8("make: invalid option -- '$ch'\n")
                                return CommandResult(2)
                            }
                        }
                    }
                    if (consumedExtra) i++
                }

                else -> {
                    consumeArg(a, targets, cliMacros)
                }
            }
            i++
        }

        if (jobs > 1) {
            ctx.stderr.writeUtf8("make: -j$jobs: parallel execution not implemented; running serially\n")
            jobs = 1
        }

        val opts =
            MakeOptions(
                files = files,
                targets = targets,
                workdir = workdir,
                keepGoing = keepGoing,
                stopOnFirstError = stopOnFirstError,
                dryRun = dryRun,
                silent = silent,
                ignoreErrors = ignoreErrors,
                envOverride = envOverride,
                noBuiltinRules = noBuiltinRules,
                touchMode = touchMode,
                questionMode = questionMode,
                printDatabase = printDatabase,
                cliMacros = cliMacros,
                jobs = jobs,
            )

        val outcome = MakeEngine(ctx, opts).run()
        return CommandResult(outcome.exitCode)
    }

    private fun consumeArg(
        a: String,
        targets: MutableList<String>,
        cliMacros: MutableMap<String, Pair<MacroFlavor, String>>,
    ) {
        val eq = findCliAssign(a)
        if (eq != null) {
            cliMacros[eq.first] = eq.second to eq.third
            return
        }
        targets += a
    }

    private fun findCliAssign(s: String): Triple<String, MacroFlavor, String>? {
        for ((op, flavor) in OPS) {
            val idx = s.indexOf(op)
            if (idx > 0) {
                val name = s.substring(0, idx)
                if (name.all { isMacroChar(it) }) {
                    return Triple(name, flavor, s.substring(idx + op.length))
                }
            }
        }
        return null
    }

    private fun isMacroChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.'

    private companion object {
        val OPS =
            listOf(
                ":::=" to MacroFlavor.IMMEDIATE_TRIPLE,
                "::=" to MacroFlavor.IMMEDIATE,
                ":=" to MacroFlavor.IMMEDIATE,
                "?=" to MacroFlavor.CONDITIONAL,
                "+=" to MacroFlavor.APPEND,
                "!=" to MacroFlavor.SHELL,
                "=" to MacroFlavor.RECURSIVE,
            )
    }
}
