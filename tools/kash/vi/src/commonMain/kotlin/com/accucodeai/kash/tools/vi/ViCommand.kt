package com.accucodeai.kash.tools.vi

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `vi` — modal text editor (POSIX vi subset). Interactive only.
 *
 * v1 ships: normal/insert/visual/command modes; motions
 * `h j k l w W b B e E 0 ^ $ gg G H M L f F t T ; , %`;
 * counts (`3w`, `5dd`); operators `d c y` + doubled forms;
 * `x X D C Y s S r ~ o O i I a A`; registers `"a..z` and unnamed;
 * marks `m`/`'`/`` ` ``; undo `u` / redo `Ctrl-R`;
 * search `/ ? n N`; visual `v V`; ex `:w :q :q! :wq :x :e :s/ :%s/ :N :set number :noh :help`.
 *
 * Out of scope for v1: complex text objects (`iw`, `ip`, `i(`), macros `q`,
 * `.` repeat, folds, splits, syntax highlighting, full ex language.
 */
public class ViCommand :
    Command,
    CommandSpec {
    override val name: String = "vi"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val parsed = parseArgs(args)
        when (parsed) {
            is Parsed.Usage -> {
                ctx.stderr.writeUtf8(USAGE)
                return CommandResult(exitCode = 2)
            }

            is Parsed.ShowVersion -> {
                ctx.stdout.writeUtf8("vi (kash) 1.0\n")
                return CommandResult()
            }

            is Parsed.Ok -> {
                Unit
            }
        }
        if (!ctx.process.isTty(0) || !ctx.process.isTty(1)) {
            ctx.stderr.writeUtf8("vi: stdin/stdout is not a TTY\n")
            return CommandResult(exitCode = 1)
        }
        val term =
            ctx.process.fdTable[0]
                ?.ofd
                ?.terminalControl ?: run {
                ctx.stderr.writeUtf8("vi: no terminal control available in this session\n")
                return CommandResult(exitCode = 1)
            }
        val editor =
            ViEditor(
                term = term,
                fs = ctx.process.fs,
                cwd = ctx.process.cwd,
                initialFile = parsed.file,
                initialLine = parsed.gotoLine,
                initialSearch = parsed.search,
            )
        return CommandResult(exitCode = editor.run())
    }

    private sealed interface Parsed {
        data class Ok(
            val file: String?,
            val gotoLine: Int? = null,
            val search: String? = null,
        ) : Parsed

        data object Usage : Parsed

        data object ShowVersion : Parsed
    }

    private fun parseArgs(args: List<String>): Parsed {
        var file: String? = null
        var gotoLine: Int? = null
        var search: String? = null
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-V" || a == "--version" -> {
                    return Parsed.ShowVersion
                }

                a == "-h" || a == "--help" -> {
                    return Parsed.Usage
                }

                a == "--" -> {
                    if (i + 1 < args.size) file = args[i + 1]
                    return Parsed.Ok(file, gotoLine, search)
                }

                a.startsWith("+/") -> {
                    search = a.substring(2)
                }

                a.startsWith("+") && a.length > 1 -> {
                    val rest = a.substring(1)
                    if (rest.all { it.isDigit() }) {
                        gotoLine = rest.toInt()
                    } else {
                        return Parsed.Usage
                    }
                }

                a == "+" -> {
                    gotoLine = Int.MAX_VALUE
                }

                // start at last line
                a.startsWith("-") && a.length > 1 -> {
                    return Parsed.Usage
                }

                else -> {
                    if (file != null) return Parsed.Usage
                    file = a
                }
            }
            i++
        }
        return Parsed.Ok(file, gotoLine, search)
    }

    private companion object {
        const val USAGE: String =
            "Usage: vi [OPTIONS] [+N | +/PAT] [FILE]\n" +
                "  -h, --help      show this help\n" +
                "  -V, --version   show version\n" +
                "  +N              open at line N\n" +
                "  +/PAT           open at first match of PAT\n"
    }
}
