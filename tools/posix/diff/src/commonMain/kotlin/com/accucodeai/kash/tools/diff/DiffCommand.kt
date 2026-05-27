package com.accucodeai.kash.tools.diff

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.diff.splitLines
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.NotADirectory
import com.accucodeai.kash.fs.Paths

/**
 * POSIX `diff [OPTIONS] FILE1 FILE2`.
 *
 * Output formats: normal (default), unified (`-u`/`-U N`), context
 * (`-c`/`-C N`), ed script (`-e`). Brief/report modes: `-q`/`--brief`,
 * `-s`/`--report-identical-files`. Directory comparison: `-r`/`--recursive`.
 * Comparison folding: `-i` (case), `-w` (all whitespace), `-b` (whitespace
 * amount), `-B` (blank lines). A `-` operand reads stdin.
 *
 * Exit codes: 0 no differences, 1 differences found, 2 trouble.
 */
public class DiffCommand :
    Command,
    CommandSpec {
    override val name: String = "diff"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    private enum class Format { NORMAL, UNIFIED, CONTEXT, ED }

    private class Opts(
        var format: Format = Format.NORMAL,
        var context: Int = 3,
        var brief: Boolean = false,
        var reportIdentical: Boolean = false,
        var recursive: Boolean = false,
        var ignore: IgnoreOptions = IgnoreOptions(),
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = Opts()
        var ignoreCase = false
        var ignoreAllWs = false
        var ignoreWsChange = false
        var ignoreBlank = false
        val operands = mutableListOf<String>()
        var optionsEnded = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                optionsEnded || a == "-" || !a.startsWith("-") || a.length == 1 -> {
                    operands += a
                }

                a == "--" -> {
                    optionsEnded = true
                }

                a == "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult(exitCode = 0)
                }

                a == "--normal" -> {
                    opts.format = Format.NORMAL
                }

                a == "-u" || a == "--unified" -> {
                    opts.format = Format.UNIFIED
                }

                a == "-c" || a == "--context" -> {
                    opts.format = Format.CONTEXT
                }

                a == "-e" || a == "--ed" -> {
                    opts.format = Format.ED
                }

                a == "-q" || a == "--brief" -> {
                    opts.brief = true
                }

                a == "-s" || a == "--report-identical-files" -> {
                    opts.reportIdentical = true
                }

                a == "-r" || a == "--recursive" -> {
                    opts.recursive = true
                }

                a == "-i" || a == "--ignore-case" -> {
                    ignoreCase = true
                }

                a == "-w" || a == "--ignore-all-space" -> {
                    ignoreAllWs = true
                }

                a == "-b" || a == "--ignore-space-change" -> {
                    ignoreWsChange = true
                }

                a == "-B" || a == "--ignore-blank-lines" -> {
                    ignoreBlank = true
                }

                a == "-U" || a == "--unified-lines" -> {
                    opts.format = Format.UNIFIED
                    val n = args.getOrNull(++i) ?: return usage(ctx, "option '-U' requires an argument")
                    opts.context = n.toIntOrNull() ?: return usage(ctx, "invalid context length '$n'")
                }

                a == "-C" -> {
                    opts.format = Format.CONTEXT
                    val n = args.getOrNull(++i) ?: return usage(ctx, "option '-C' requires an argument")
                    opts.context = n.toIntOrNull() ?: return usage(ctx, "invalid context length '$n'")
                }

                a.startsWith("-U") && a.length > 2 -> {
                    opts.format = Format.UNIFIED
                    opts.context =
                        a.substring(2).toIntOrNull() ?: return usage(ctx, "invalid context length '${a.substring(2)}'")
                }

                a.startsWith("-C") && a.length > 2 -> {
                    opts.format = Format.CONTEXT
                    opts.context =
                        a.substring(2).toIntOrNull() ?: return usage(ctx, "invalid context length '${a.substring(2)}'")
                }

                a.startsWith("--unified=") -> {
                    opts.format = Format.UNIFIED
                    opts.context = a.substringAfter('=').toIntOrNull() ?: return usage(ctx, "invalid context length")
                }

                a.startsWith("--context=") -> {
                    opts.format = Format.CONTEXT
                    opts.context = a.substringAfter('=').toIntOrNull() ?: return usage(ctx, "invalid context length")
                }

                a.startsWith("--") -> {
                    return usage(ctx, "unrecognized option '$a'")
                }

                else -> {
                    // Bundled short flags.
                    for (c in a.drop(1)) {
                        when (c) {
                            'u' -> opts.format = Format.UNIFIED
                            'c' -> opts.format = Format.CONTEXT
                            'e' -> opts.format = Format.ED
                            'q' -> opts.brief = true
                            's' -> opts.reportIdentical = true
                            'r' -> opts.recursive = true
                            'i' -> ignoreCase = true
                            'w' -> ignoreAllWs = true
                            'b' -> ignoreWsChange = true
                            'B' -> ignoreBlank = true
                            else -> return usage(ctx, "invalid option -- '$c'")
                        }
                    }
                }
            }
            i++
        }

        opts.ignore =
            IgnoreOptions(
                ignoreCase = ignoreCase,
                ignoreAllWhitespace = ignoreAllWs,
                ignoreWhitespaceChange = ignoreWsChange,
                ignoreBlankLines = ignoreBlank,
            )

        if (operands.size != 2) {
            return usage(ctx, "missing operand after '${operands.lastOrNull() ?: "diff"}'")
        }
        val p1 = operands[0]
        val p2 = operands[1]

        val fs = ctx.process.fs
        val abs1 = if (p1 == "-") null else Paths.resolve(ctx.process.cwd, p1)
        val abs2 = if (p2 == "-") null else Paths.resolve(ctx.process.cwd, p2)

        val dir1 = abs1 != null && fs.exists(abs1) && fs.isDirectory(abs1)
        val dir2 = abs2 != null && fs.exists(abs2) && fs.isDirectory(abs2)

        return if (dir1 && dir2) {
            diffDirs(p1, p2, abs1, abs2, opts, ctx)
        } else if (dir1 || dir2) {
            // One is a dir, the other a file: compare file against dir/basename.
            diffFileVsDir(p1, p2, abs1, abs2, dir1, opts, ctx)
        } else {
            diffFiles(p1, p2, abs1, abs2, opts, ctx)
        }
    }

    private suspend fun readText(
        label: String,
        abs: String?,
        ctx: CommandContext,
    ): String? =
        try {
            if (abs == null) {
                ctx.stdin.readAllBytes().decodeToString()
            } else {
                ctx.process.fs
                    .readBytes(abs)
                    .decodeToString()
            }
        } catch (_: FileNotFound) {
            ctx.stderr.writeUtf8("diff: $label: No such file or directory\n")
            null
        } catch (_: NotADirectory) {
            ctx.stderr.writeUtf8("diff: $label: Not a directory\n")
            null
        }

    private suspend fun diffFiles(
        label1: String,
        label2: String,
        abs1: String?,
        abs2: String?,
        opts: Opts,
        ctx: CommandContext,
    ): CommandResult {
        val text1 = readText(label1, abs1, ctx) ?: return CommandResult(exitCode = 2)
        val text2 = readText(label2, abs2, ctx) ?: return CommandResult(exitCode = 2)
        return emitDiff(label1, label2, text1, text2, opts, ctx)
    }

    private suspend fun emitDiff(
        label1: String,
        label2: String,
        text1: String,
        text2: String,
        opts: Opts,
        ctx: CommandContext,
    ): CommandResult {
        if (textsEqual(text1, text2, opts.ignore)) {
            if (opts.reportIdentical) {
                ctx.stdout.writeUtf8("Files $label1 and $label2 are identical\n")
            }
            return CommandResult(exitCode = 0)
        }
        if (opts.brief) {
            ctx.stdout.writeUtf8("Files $label1 and $label2 differ\n")
            return CommandResult(exitCode = 1)
        }

        val s1 = splitLines(text1)
        val s2 = splitLines(text2)
        val edits = lcsEditsWithOptions(s1.lines, s2.lines, opts.ignore)
        val out =
            when (opts.format) {
                Format.NORMAL -> {
                    renderNormal(edits)
                }

                Format.ED -> {
                    renderEd(edits)
                }

                Format.UNIFIED -> {
                    renderUnified(edits, label1, label2, opts.context, s1.hasTrailingNewline, s2.hasTrailingNewline)
                }

                Format.CONTEXT -> {
                    renderContext(edits, label1, label2, opts.context)
                }
            }
        if (out.isEmpty()) {
            // Folding (-B/-w/-i) reduced everything to keeps.
            if (opts.reportIdentical) ctx.stdout.writeUtf8("Files $label1 and $label2 are identical\n")
            return CommandResult(exitCode = 0)
        }
        ctx.stdout.writeUtf8(out)
        return CommandResult(exitCode = 1)
    }

    private suspend fun diffFileVsDir(
        label1: String,
        label2: String,
        abs1: String?,
        abs2: String?,
        firstIsDir: Boolean,
        opts: Opts,
        ctx: CommandContext,
    ): CommandResult {
        // GNU diff: `diff dir file` compares dir/<basename(file)> to file.
        return if (firstIsDir) {
            val base = Paths.basename(abs2 ?: label2)
            val joined = "$abs1/$base"
            val newLabel1 = "$label1/$base"
            diffFiles(newLabel1, label2, joined, abs2, opts, ctx)
        } else {
            val base = Paths.basename(abs1 ?: label1)
            val joined = "$abs2/$base"
            val newLabel2 = "$label2/$base"
            diffFiles(label1, newLabel2, abs1, joined, opts, ctx)
        }
    }

    private suspend fun diffDirs(
        label1: String,
        label2: String,
        abs1: String,
        abs2: String,
        opts: Opts,
        ctx: CommandContext,
    ): CommandResult {
        val fs = ctx.process.fs
        val names1 = fs.list(abs1).map { Paths.basename(it) }.toSet()
        val names2 = fs.list(abs2).map { Paths.basename(it) }.toSet()
        val all = (names1 + names2).sorted()
        var worst = 0
        for (name in all) {
            val in1 = name in names1
            val in2 = name in names2
            when {
                in1 && !in2 -> {
                    ctx.stdout.writeUtf8("Only in $label1: $name\n")
                    worst = maxOf(worst, 1)
                }

                !in1 && in2 -> {
                    ctx.stdout.writeUtf8("Only in $label2: $name\n")
                    worst = maxOf(worst, 1)
                }

                else -> {
                    val c1 = "$abs1/$name"
                    val c2 = "$abs2/$name"
                    val d1 = fs.isDirectory(c1)
                    val d2 = fs.isDirectory(c2)
                    when {
                        d1 && d2 -> {
                            if (opts.recursive) {
                                val rc = diffDirs("$label1/$name", "$label2/$name", c1, c2, opts, ctx)
                                worst = maxOf(worst, rc.exitCode)
                            } else {
                                ctx.stdout.writeUtf8("Common subdirectories: $c1 and $c2\n")
                            }
                        }

                        d1 != d2 -> {
                            val (fileSide, dirSide) =
                                if (d1) {
                                    ("$label2/$name") to ("$label1/$name")
                                } else {
                                    ("$label1/$name") to
                                        ("$label2/$name")
                                }
                            ctx.stdout.writeUtf8(
                                "File $fileSide is a regular file while file $dirSide is a directory\n",
                            )
                            worst = maxOf(worst, 1)
                        }

                        else -> {
                            ctx.stdout.writeUtf8("diff $label1/$name $label2/$name\n")
                            val rc = diffFiles("$label1/$name", "$label2/$name", c1, c2, opts, ctx)
                            worst = maxOf(worst, rc.exitCode)
                        }
                    }
                }
            }
        }
        return CommandResult(exitCode = worst)
    }

    private suspend fun usage(
        ctx: CommandContext,
        msg: String,
    ): CommandResult {
        ctx.stderr.writeUtf8("diff: $msg\n")
        return CommandResult(exitCode = 2)
    }

    private companion object {
        const val HELP: String =
            "Usage: diff [OPTION]... FILES\n" +
                "Compare FILES line by line.\n\n" +
                "  -u, --unified            output NUM (default 3) lines of unified context\n" +
                "  -U NUM                   output NUM lines of unified context\n" +
                "  -c, --context            output context-format diff\n" +
                "  -C NUM                   output NUM lines of context\n" +
                "  -e, --ed                 output an ed script\n" +
                "  -q, --brief              report only when files differ\n" +
                "  -s, --report-identical-files  report when two files are the same\n" +
                "  -r, --recursive          recursively compare any subdirectories found\n" +
                "  -i, --ignore-case        ignore case differences in file contents\n" +
                "  -w, --ignore-all-space   ignore all white space\n" +
                "  -b, --ignore-space-change  ignore changes in the amount of white space\n" +
                "  -B, --ignore-blank-lines ignore changes whose lines are all blank\n" +
                "      --help               display this help and exit\n\n" +
                "Exit status is 0 if inputs are the same, 1 if different, 2 if trouble.\n"
    }
}
