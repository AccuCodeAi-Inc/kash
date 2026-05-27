package com.accucodeai.kash.tools.patch

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths

/**
 * POSIX `patch [OPTIONS] [ORIGFILE]`. Reads a patch from stdin or `-i
 * FILE`, autodetects unified/context/normal hunks, and applies them with
 * exact-context matching.
 *
 * Flags: `-pN`/`--strip=N`, `-R`/`--reverse`, `--dry-run`, `-i FILE`,
 * `-o FILE`, `--help`.
 *
 * Exit codes: 0 all hunks applied, 1 one or more hunks failed, 2 trouble
 * (unreadable patch/target, malformed patch).
 *
 * Fuzz/offset search beyond exact context is a documented TODO in
 * [applyHunks]; mismatching hunks report `Hunk #N FAILED`.
 */
public class PatchCommand :
    Command,
    CommandSpec {
    override val name: String = "patch"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE, CommandTag.FS_WRITE)
    override val command: Command get() = this

    private class Opts(
        var strip: Int = -1, // -1 => "basename" default per POSIX when unset
        var reverse: Boolean = false,
        var dryRun: Boolean = false,
        var inputFile: String? = null,
        var outputFile: String? = null,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = Opts()
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

                a == "-R" || a == "--reverse" -> {
                    opts.reverse = true
                }

                a == "--dry-run" -> {
                    opts.dryRun = true
                }

                a == "-i" || a == "--input" -> {
                    opts.inputFile = args.getOrNull(++i) ?: return trouble(ctx, "option '-i' requires an argument")
                }

                a.startsWith("--input=") -> {
                    opts.inputFile = a.substringAfter('=')
                }

                a == "-o" || a == "--output" -> {
                    opts.outputFile = args.getOrNull(++i) ?: return trouble(ctx, "option '-o' requires an argument")
                }

                a.startsWith("--output=") -> {
                    opts.outputFile = a.substringAfter('=')
                }

                a == "-p" -> {
                    val n = args.getOrNull(++i) ?: return trouble(ctx, "option '-p' requires an argument")
                    opts.strip = n.toIntOrNull() ?: return trouble(ctx, "invalid strip count '$n'")
                }

                a.startsWith("-p") && a.length > 2 -> {
                    opts.strip =
                        a.substring(2).toIntOrNull() ?: return trouble(ctx, "invalid strip count '${a.substring(2)}'")
                }

                a.startsWith("--strip=") -> {
                    opts.strip = a.substringAfter('=').toIntOrNull() ?: return trouble(ctx, "invalid strip count")
                }

                a.startsWith("--") -> {
                    return trouble(ctx, "unrecognized option '$a'")
                }

                else -> {
                    for (c in a.drop(1)) {
                        when (c) {
                            'R' -> opts.reverse = true
                            else -> return trouble(ctx, "invalid option -- '$c'")
                        }
                    }
                }
            }
            i++
        }

        val patchText =
            try {
                if (opts.inputFile != null) {
                    ctx.process.fs
                        .readBytes(Paths.resolve(ctx.process.cwd, opts.inputFile!!))
                        .decodeToString()
                } else {
                    ctx.stdin.readAllBytes().decodeToString()
                }
            } catch (_: FileNotFound) {
                return trouble(ctx, "${opts.inputFile}: No such file or directory")
            }

        val files =
            try {
                parsePatch(patchText)
            } catch (e: PatchParseException) {
                return trouble(ctx, e.message ?: "malformed patch")
            }
        if (files.isEmpty() || files.all { it.hunks.isEmpty() }) {
            ctx.stderr.writeUtf8("patch: **** Only garbage was found in the patch input.\n")
            return CommandResult(exitCode = 2)
        }

        val explicitTarget = operands.firstOrNull()
        var anyFailed = false

        for (fp in files) {
            val target =
                explicitTarget
                    ?: chooseTarget(fp, opts.strip)
                    ?: run {
                        ctx.stderr.writeUtf8("patch: **** can't determine which file to patch\n")
                        return CommandResult(exitCode = 2)
                    }
            val absTarget = Paths.resolve(ctx.process.cwd, target)

            val (originalText, hadTrailingNl) =
                try {
                    val bytes = ctx.process.fs.readBytes(absTarget)
                    val text = bytes.decodeToString()
                    text to (text.isEmpty() || text.endsWith('\n'))
                } catch (_: FileNotFound) {
                    // New-file patch (old side empty) is allowed; otherwise trouble.
                    "" to true
                }

            val originalLines = if (originalText.isEmpty()) emptyList() else splitKeep(originalText)
            val result = applyHunks(originalLines, fp.hunks, opts.reverse, hadTrailingNl)

            for (h in result.failed) {
                ctx.stderr.writeUtf8("Hunk #$h FAILED at ${fp.hunks[h - 1].oldStart}.\n")
                anyFailed = true
            }

            val outText =
                result.lines.joinToString("\n").let {
                    if (result.hadTrailingNewline &&
                        result.lines.isNotEmpty()
                    ) {
                        "$it\n"
                    } else {
                        it
                    }
                }
            val outPath = if (opts.outputFile != null) Paths.resolve(ctx.process.cwd, opts.outputFile!!) else absTarget

            if (!opts.dryRun) {
                val sink = ctx.process.fs.sink(outPath)
                sink.writeBytes(outText.encodeToByteArray())
                sink.close()
            }
        }

        return CommandResult(exitCode = if (anyFailed) 1 else 0)
    }

    /** Apply `-pN` stripping to the patch's path names to pick a target. */
    private fun chooseTarget(
        fp: FilePatch,
        strip: Int,
    ): String? {
        val name = fp.newName ?: fp.oldName ?: return null
        if (name == "/dev/null") return fp.oldName?.let { strip(it, strip) }
        return strip(name, strip)
    }

    private fun strip(
        path: String,
        n: Int,
    ): String {
        if (n < 0) return Paths.basename(path) // default: basename
        val parts = path.split('/').filter { it.isNotEmpty() }
        return if (n >= parts.size) parts.lastOrNull() ?: path else parts.drop(n).joinToString("/")
    }

    /** Split into lines without the synthetic trailing empty element. */
    private fun splitKeep(text: String): List<String> {
        val hasTrailing = text.endsWith('\n')
        val body = if (hasTrailing) text.dropLast(1) else text
        return body.split('\n')
    }

    private suspend fun trouble(
        ctx: CommandContext,
        msg: String,
    ): CommandResult {
        ctx.stderr.writeUtf8("patch: **** $msg\n")
        return CommandResult(exitCode = 2)
    }

    private companion object {
        const val HELP: String =
            "Usage: patch [OPTION]... [ORIGFILE]\n" +
                "Apply a diff file to an original.\n\n" +
                "  -pN, --strip=N       strip N leading path components from file names\n" +
                "  -R, --reverse        assume patches were swapped, undo them\n" +
                "  -i FILE, --input=FILE  read patch from FILE instead of stdin\n" +
                "  -o FILE, --output=FILE  write output to FILE instead of patching in place\n" +
                "      --dry-run        print results without modifying any files\n" +
                "      --help           display this help and exit\n\n" +
                "Exit status is 0 if all hunks applied, 1 if some failed, 2 on trouble.\n"
    }
}
