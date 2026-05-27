package com.accucodeai.kash.tools.zip

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileSystem

/**
 * `zip` — create a ZIP archive.
 *
 * Synopsis: zip {-r|-q|-0..-9} ARCHIVE FILES...
 *
 * Special: `zip -` (no other args) is reserved for stdin->stdout streaming
 * in v2; v1 errors out with a clear message.
 *
 * Behavior:
 *  - First non-option argument is the archive path.
 *  - Subsequent arguments are entries: files (added directly) or, with
 *    `-r`, directories (walked recursively). Without `-r`, directory
 *    arguments are skipped with a warning to stderr.
 *  - Archive path of `-` writes to stdout (handy for piping into unzip).
 *  - Compression level defaults to 6; `-0` stores; `-1..-9` set deflate level.
 *  - Quiet (`-q`) suppresses the per-file "adding: foo" progress lines.
 */
public class ZipCommand :
    Command,
    CommandSpec {
    override val name: String = "zip"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.FS_WRITE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var recursive = false
        var quiet = false
        var level = 6
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
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-r" || a == "-R" -> {
                    recursive = true
                }

                a == "-q" || a == "--quiet" -> {
                    quiet = true
                }

                a.length == 2 && a[0] == '-' && a[1] in '0'..'9' -> {
                    level = a[1].digitToInt()
                }

                a == "-" -> {
                    operands += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("zip: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        if (operands.isEmpty()) {
            ctx.stderr.writeUtf8("zip: missing archive name\n")
            return CommandResult(exitCode = 2)
        }

        val archive = operands[0]
        val inputs = operands.drop(1)
        if (inputs.isEmpty()) {
            ctx.stderr.writeUtf8("zip: nothing to do (no files)\n")
            return CommandResult(exitCode = 2)
        }

        // Open destination sink: "-" means stdout; otherwise resolve through ctx.fs.
        val archiveSink =
            if (archive == "-") {
                ctx.stdout
            } else {
                val path = resolvePath(ctx.cwd, archive)
                try {
                    ctx.fs.sink(path, append = false)
                } catch (e: Exception) {
                    ctx.stderr.writeUtf8("zip: cannot open '$archive' for writing: ${e.message ?: "I/O error"}\n")
                    return CommandResult(exitCode = 1)
                }
            }

        val writer = ZipWriter(archiveSink, level)
        var hadError = false
        try {
            // Track names already added to avoid duplicate central directory
            // entries when callers list the same file twice or pass overlapping
            // dirs. POSIX zip would dedupe with a warning; we silently skip.
            val seen = mutableSetOf<String>()
            for (inp in inputs) {
                val res = addOperand(ctx.fs, ctx.cwd, inp, recursive, quiet, ctx, writer, seen)
                if (!res) hadError = true
            }
        } finally {
            try {
                writer.close()
            } catch (_: Throwable) {
                // errors surface elsewhere
            }
        }
        return CommandResult(exitCode = if (hadError) 1 else 0)
    }

    /** Add a single CLI operand (file or, with -r, directory) to the archive. */
    private suspend fun addOperand(
        fs: FileSystem,
        cwd: String,
        operand: String,
        recursive: Boolean,
        quiet: Boolean,
        ctx: CommandContext,
        writer: ZipWriter,
        seen: MutableSet<String>,
    ): Boolean {
        val abs = resolvePath(cwd, operand)
        if (!fs.exists(abs)) {
            ctx.stderr.writeUtf8("zip: $operand: No such file or directory\n")
            return false
        }
        // We store entries by the operand-as-given (POSIX zip's behavior:
        // archive paths mirror the literal CLI path). Strip a leading "./"
        // and trailing "/" to keep names tidy.
        val rel = canonicalEntryName(operand)
        if (fs.isDirectory(abs)) {
            if (!recursive) {
                ctx.stderr.writeUtf8("zip: $operand is a directory (use -r to add recursively)\n")
                return false
            }
            return addDirectoryRecursive(fs, abs, rel, quiet, ctx, writer, seen)
        }
        return addFile(fs, abs, rel, quiet, ctx, writer, seen)
    }

    private suspend fun addFile(
        fs: FileSystem,
        absPath: String,
        entryName: String,
        quiet: Boolean,
        ctx: CommandContext,
        writer: ZipWriter,
        seen: MutableSet<String>,
    ): Boolean {
        if (!seen.add(entryName)) return true
        val bytes: ByteArray =
            try {
                val src: SuspendSource = fs.source(absPath)
                try {
                    src.readAllBytes()
                } finally {
                    src.close()
                }
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("zip: $entryName: No such file or directory\n")
                return false
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("zip: $entryName: ${e.message ?: "I/O error"}\n")
                return false
            }
        val mtime = runCatching { fs.stat(absPath).mtimeEpochSeconds }.getOrNull()
        writer.putFileEntry(entryName, bytes, mtime)
        if (!quiet) ctx.stderr.writeUtf8("  adding: $entryName\n")
        return true
    }

    private suspend fun addDirectoryRecursive(
        fs: FileSystem,
        absDir: String,
        relDir: String,
        quiet: Boolean,
        ctx: CommandContext,
        writer: ZipWriter,
        seen: MutableSet<String>,
    ): Boolean {
        val dirEntryName = if (relDir.endsWith("/")) relDir else "$relDir/"
        if (seen.add(dirEntryName)) {
            val mtime = runCatching { fs.stat(absDir).mtimeEpochSeconds }.getOrNull()
            writer.putDirEntry(dirEntryName, mtime)
            if (!quiet) ctx.stderr.writeUtf8("  adding: $dirEntryName\n")
        }
        var ok = true
        val children: List<String> =
            try {
                fs.list(absDir).sorted()
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("zip: $relDir: ${e.message ?: "cannot list directory"}\n")
                return false
            }
        for (child in children) {
            val childAbs = "$absDir/$child"
            val childRel = "$relDir/$child".removePrefix("./")
            if (fs.isDirectory(childAbs)) {
                ok = addDirectoryRecursive(fs, childAbs, childRel, quiet, ctx, writer, seen) && ok
            } else {
                ok = addFile(fs, childAbs, childRel, quiet, ctx, writer, seen) && ok
            }
        }
        return ok
    }
}

internal fun resolvePath(
    cwd: String,
    path: String,
): String =
    if (path.startsWith("/")) {
        path
    } else if (cwd.endsWith("/")) {
        "$cwd$path"
    } else {
        "$cwd/$path"
    }

/** Normalize a CLI operand into a ZIP entry name. */
internal fun canonicalEntryName(operand: String): String {
    var s = operand
    while (s.startsWith("./")) s = s.removePrefix("./")
    if (s.startsWith("/")) s = s.removePrefix("/")
    while (s.endsWith("/") && s.length > 1) s = s.dropLast(1)
    return s
}
