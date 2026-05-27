package com.accucodeai.kash.tools.file

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.magic.KMagic
import com.accucodeai.kash.magic.MagicMatch
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

private fun resolvePath(
    cwd: String,
    path: String,
): String = if (path.startsWith("/")) path else "$cwd/$path"

private val HELP =
    """
    Usage: file [-bhiL] [--mime] [file ...]

      -b, --brief          do not prepend filenames to output
      -i, --mime           print MIME type strings instead of descriptions
      -h, --no-dereference don't follow symlinks (report the link itself)
      -L, --dereference    follow symlinks (default)
      --help               this help and exit

    With no file operands, or '-', read from standard input. File contents are
    identified from a bounded prefix (the first ${KMagic.PEEK_BYTES} bytes); a
    huge file is never read in full just to name its type.
    """.trimIndent() + "\n"

private const val STDIN_NAME = "/dev/stdin"

public class FileCommand :
    Command,
    CommandSpec {
    override val name: String = "file"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var brief = false
        var mime = false
        var follow = true
        val operands = mutableListOf<String>()

        var i = 0
        var endOfOpts = false
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                operands += a
                i++
                continue
            }
            when (a) {
                "--" -> {
                    endOfOpts = true
                }

                "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                "-b", "--brief" -> {
                    brief = true
                }

                "-i", "--mime", "--mime-type" -> {
                    mime = true
                }

                "-h", "--no-dereference" -> {
                    follow = false
                }

                "-L", "--dereference" -> {
                    follow = true
                }

                "-" -> {
                    operands += a
                }

                else -> {
                    if (a.startsWith("-") && a.length > 1) {
                        // Allow stacked short flags like -bi.
                        if (a.all { it == '-' || it in "bihL" }) {
                            for (c in a.substring(1)) {
                                when (c) {
                                    'b' -> brief = true
                                    'i' -> mime = true
                                    'h' -> follow = false
                                    'L' -> follow = true
                                }
                            }
                        } else {
                            ctx.stderr.writeUtf8("file: invalid option -- '$a'\n")
                            return CommandResult(exitCode = 1)
                        }
                    } else {
                        operands += a
                    }
                }
            }
            i++
        }

        val targets = if (operands.isEmpty()) listOf("-") else operands
        val labelWidth = if (brief) 0 else targets.maxOf { displayName(it).length } + 2

        var anyError = false
        for (target in targets) {
            val result = classify(target, follow, ctx)
            if (result == null) {
                anyError = true
                continue
            }
            val text = if (mime) result.mime else result.description
            if (brief) {
                ctx.stdout.writeUtf8(text + "\n")
            } else {
                val label = "${displayName(target)}:"
                ctx.stdout.writeUtf8(label.padEnd(labelWidth) + text + "\n")
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private fun displayName(target: String): String = if (target == "-") STDIN_NAME else target

    /** Returns the match, or null after writing an error (missing file). */
    private suspend fun classify(
        target: String,
        follow: Boolean,
        ctx: CommandContext,
    ): MagicMatch? {
        if (target == "-") {
            val prefix = readPrefix(ctx, null)
            return KMagic.identify(prefix)
        }
        val path = resolvePath(ctx.process.cwd, target)
        val stat: FileStat =
            try {
                if (follow) ctx.process.fs.stat(path) else ctx.process.fs.statLink(path)
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("file: cannot open `$target' (No such file or directory)\n")
                return null
            }

        specialType(stat)?.let { return it }

        // Regular file: zero-length is "empty"; otherwise identify from prefix.
        if (stat.size == 0L) return MagicMatch("empty", "inode/x-empty")
        val prefix =
            try {
                readPrefix(ctx, path)
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("file: cannot open `$target' (No such file or directory)\n")
                return null
            }
        return KMagic.identify(prefix)
    }

    private fun specialType(stat: FileStat): MagicMatch? =
        when (stat.type) {
            FileType.DIRECTORY -> {
                val sticky = (stat.mode and 0b1_000_000_000) != 0
                val desc = if (sticky) "sticky, directory" else "directory"
                MagicMatch(desc, "inode/directory")
            }

            FileType.FIFO -> {
                MagicMatch("fifo (named pipe)", "inode/fifo")
            }

            FileType.SOCKET -> {
                MagicMatch("socket", "inode/socket")
            }

            FileType.BLOCK -> {
                MagicMatch("block special", "inode/blockdevice")
            }

            FileType.CHAR -> {
                MagicMatch("character special", "inode/chardevice")
            }

            FileType.SYMLINK -> {
                val tgt = stat.symlinkTarget
                val desc = if (tgt != null) "symbolic link to $tgt" else "symbolic link"
                MagicMatch(desc, "inode/symlink")
            }

            FileType.REGULAR -> {
                null
            }
        }

    /**
     * Read at most [KMagic.PEEK_BYTES] bytes. For a path, opens the source and
     * stops as soon as the cap is reached — a large file is never fully read.
     */
    private suspend fun readPrefix(
        ctx: CommandContext,
        path: String?,
    ): ByteArray {
        val src = if (path == null) ctx.stdin else ctx.process.fs.source(path)
        try {
            val buf = Buffer()
            var total = 0L
            while (total < KMagic.PEEK_BYTES) {
                val n = src.readAtMostTo(buf, KMagic.PEEK_BYTES.toLong() - total)
                if (n == -1L) break
                total += n
            }
            return buf.readByteArray()
        } finally {
            if (path != null) src.close()
        }
    }
}
