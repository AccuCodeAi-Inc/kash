package com.accucodeai.kash.tools.pax

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileType
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * `pax` — Portable Archive Interchange utility.
 *
 * Modes:
 * - (no mode)   list archive read from stdin / `-f FILE`.
 * - `-r`        read (extract).
 * - `-w`        write (create archive from FILE operands).
 * - `-r -w`     copy mode — not implemented in v1.
 *
 * Flags:
 * - `-f FILE`   archive file (default stdin for read/list, stdout for write).
 *               `-f -` is explicit stdin/stdout.
 * - `-v`        verbose (list mode: long format; write: list each entry).
 * - `-x FMT`    archive format. Only `ustar` and `pax` supported.
 * - `-s SUBST`  rename substitution — DEFERRED in v1 (flag accepted with
 *               a stderr warning). Implement when needed.
 *
 * Format: USTAR with pax extended headers (typeflag 'x') used automatically
 * for filenames > 256 bytes (or > 100 with no usable prefix split).
 */
public class PaxCommand :
    Command,
    CommandSpec {
    override val name: String = "pax"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts =
            try {
                parseArgs(args)
            } catch (e: UsageError) {
                ctx.stderr.writeUtf8("pax: ${e.message}\n")
                return CommandResult(exitCode = 2)
            }

        if (opts.unsupportedSubst) {
            ctx.stderr.writeUtf8("pax: -s substitution not yet implemented in v1\n")
        }

        return when {
            opts.read && opts.write -> {
                ctx.stderr.writeUtf8("pax: copy mode (-r -w together) is not supported in v1\n")
                CommandResult(exitCode = 2)
            }

            opts.write -> {
                doWrite(opts, ctx)
            }

            opts.read -> {
                doRead(opts, ctx, listOnly = false)
            }

            else -> {
                doRead(opts, ctx, listOnly = true)
            }
        }
    }

    private suspend fun doWrite(
        opts: Options,
        ctx: CommandContext,
    ): CommandResult {
        if (opts.operands.isEmpty()) {
            ctx.stderr.writeUtf8("pax: no file operands\n")
            return CommandResult(exitCode = 1)
        }

        val out: SuspendSink =
            if (opts.archive == null || opts.archive == "-") {
                ctx.stdout
            } else {
                val path = resolvePath(ctx.cwd, opts.archive)
                ctx.fs.sink(path, append = false)
            }
        var status = 0
        try {
            for (operand in opts.operands) {
                val path = resolvePath(ctx.cwd, operand)
                try {
                    writeEntry(path, operand, out, ctx, opts)
                } catch (e: FileNotFound) {
                    ctx.stderr.writeUtf8("pax: $operand: No such file or directory\n")
                    status = 1
                } catch (e: Throwable) {
                    ctx.stderr.writeUtf8("pax: $operand: ${e.message ?: e::class.simpleName}\n")
                    status = 1
                }
            }
            // End-of-archive: two zero blocks.
            val zero = ByteArray(UstarFormat.BLOCK)
            out.writeBytes(zero)
            out.writeBytes(zero)
            out.flush()
        } finally {
            if (out !== ctx.stdout) out.close()
        }
        return CommandResult(exitCode = status)
    }

    /** Walk [path] (recursively if dir) and write entries with archive name [archName]. */
    private suspend fun writeEntry(
        path: String,
        archName: String,
        out: SuspendSink,
        ctx: CommandContext,
        opts: Options,
    ) {
        val stat = ctx.fs.stat(path)
        when (stat.type) {
            FileType.DIRECTORY -> {
                // Emit dir header (entry name with trailing '/')
                val dirName = if (archName.endsWith("/")) archName else "$archName/"
                writeOneEntry(out, dirName, stat.mode, stat.mtimeEpochSeconds, 0L, UstarFormat.TYPE_DIR, ctx)
                if (opts.verbose) ctx.stderr.writeLine(dirName)
                // Recurse children
                val children = ctx.fs.list(path).sorted()
                for (child in children) {
                    val childPath = if (path.endsWith("/")) "$path$child" else "$path/$child"
                    val childArch = "$dirName$child".trimEnd('/').let { it } // dirName ends with /
                    val childArchName = "$dirName$child"
                    writeEntry(childPath, childArchName, out, ctx, opts)
                }
            }

            FileType.REGULAR -> {
                val src = ctx.fs.source(path)
                try {
                    // Need the file size — read into a buffer (streaming write
                    // still applies for the body, but we need size in header
                    // before payload). Stream-read and accumulate to a kotlinx
                    // Buffer so we don't double-allocate as a ByteArray for
                    // small files; large files still allocate proportional to
                    // size, which is unavoidable for ustar.
                    val body = Buffer()
                    while (true) {
                        val n = src.readAtMostTo(body, 64 * 1024L)
                        if (n == -1L) break
                    }
                    val size = body.size
                    writeOneEntry(
                        out,
                        archName,
                        stat.mode,
                        stat.mtimeEpochSeconds,
                        size,
                        UstarFormat.TYPE_REGULAR,
                        ctx,
                    )
                    if (opts.verbose) ctx.stderr.writeLine(archName)
                    // Body
                    out.write(body, size)
                    // Pad to 512
                    val pad = ((-size) and 511L).toInt()
                    if (pad > 0) out.writeBytes(ByteArray(pad))
                    out.flush()
                } finally {
                    src.close()
                }
            }

            else -> {
                ctx.stderr.writeUtf8("pax: $archName: unsupported file type ${stat.type}\n")
            }
        }
    }

    private suspend fun writeOneEntry(
        out: SuspendSink,
        name: String,
        mode: Int,
        mtime: Long,
        size: Long,
        typeflag: Byte,
        ctx: CommandContext,
    ) {
        if (!UstarFormat.fitsUstar(name)) {
            // Emit pax extended header carrying the long path.
            val records = mapOf("path" to name)
            val body = UstarFormat.encodePaxRecords(records)
            // Truncated name for the ext-header itself (POSIX: "%d/PaxHeaders/%s")
            val shortName = "PaxHeaders/" + name.takeLast(80)
            val extHdr =
                UstarFormat.buildHeader(
                    name = shortName,
                    mode = 0b110_100_100,
                    uid = 0,
                    gid = 0,
                    size = body.size.toLong(),
                    mtime = mtime,
                    typeflag = UstarFormat.TYPE_PAX_EXT,
                )
            out.writeBytes(extHdr)
            out.writeBytes(body)
            val pad = ((-body.size.toLong()) and 511L).toInt()
            if (pad > 0) out.writeBytes(ByteArray(pad))
        }
        // Now write the real header. The name field in the ustar header is
        // truncated when pax-ext is used (readers prefer the pax 'path' record).
        val ustarName =
            if (UstarFormat.fitsUstar(name)) name else name.takeLast(100)
        val hdr =
            UstarFormat.buildHeader(
                name = ustarName,
                mode = mode and 0xfff,
                uid = 0,
                gid = 0,
                size = size,
                mtime = mtime,
                typeflag = typeflag,
            )
        out.writeBytes(hdr)
    }

    private suspend fun doRead(
        opts: Options,
        ctx: CommandContext,
        listOnly: Boolean,
    ): CommandResult {
        val input: SuspendSource =
            if (opts.archive == null || opts.archive == "-") {
                ctx.stdin
            } else {
                val path = resolvePath(ctx.cwd, opts.archive)
                try {
                    ctx.fs.source(path)
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("pax: ${opts.archive}: No such file or directory\n")
                    return CommandResult(exitCode = 1)
                }
            }
        var status = 0
        try {
            var pendingPaxPath: String? = null
            var pendingLongName: String? = null
            while (true) {
                val block = readFullBlock(input) ?: break
                if (UstarFormat.isZeroBlock(block)) {
                    // POSIX: two consecutive zero blocks signal end. Be lenient:
                    // accept a single zero block too.
                    val maybeSecond = readFullBlock(input)
                    if (maybeSecond == null || UstarFormat.isZeroBlock(maybeSecond)) break
                    // First was zero but second isn't — treat second as a header.
                    val h2 = UstarFormat.parseHeader(maybeSecond) ?: continue
                    handleHeader(
                        h2,
                        input,
                        ctx,
                        opts,
                        listOnly,
                        pendingPaxPath,
                        pendingLongName,
                    ) { newPax, newLong, exit ->
                        pendingPaxPath = newPax
                        pendingLongName = newLong
                        if (exit != 0) status = exit
                    }
                    continue
                }
                val hdr = UstarFormat.parseHeader(block)
                if (hdr == null) {
                    ctx.stderr.writeUtf8("pax: corrupt archive (bad checksum)\n")
                    status = 1
                    break
                }
                handleHeader(
                    hdr,
                    input,
                    ctx,
                    opts,
                    listOnly,
                    pendingPaxPath,
                    pendingLongName,
                ) { newPax, newLong, exit ->
                    pendingPaxPath = newPax
                    pendingLongName = newLong
                    if (exit != 0) status = exit
                }
            }
        } finally {
            if (input !== ctx.stdin) input.close()
        }
        return CommandResult(exitCode = status)
    }

    private suspend fun handleHeader(
        hdr: UstarFormat.Header,
        input: SuspendSource,
        ctx: CommandContext,
        opts: Options,
        listOnly: Boolean,
        pendingPaxPath: String?,
        pendingLongName: String?,
        update: (String?, String?, Int) -> Unit,
    ) {
        if (hdr.isPaxExt) {
            val body = readBody(input, hdr.size)
            val records = UstarFormat.decodePaxRecords(body)
            update(records["path"] ?: pendingPaxPath, pendingLongName, 0)
            return
        }
        if (hdr.isGnuLongName) {
            val body = readBody(input, hdr.size)
            // Strip trailing NULs.
            var end = body.size
            while (end > 0 && body[end - 1] == 0.toByte()) end--
            val longName = body.copyOf(end).decodeToString()
            update(pendingPaxPath, longName, 0)
            return
        }

        val effectiveName = pendingPaxPath ?: pendingLongName ?: hdr.name
        // Clear pending state.
        update(null, null, 0)

        if (listOnly) {
            if (opts.verbose) {
                ctx.stdout.writeLine(formatVerbose(hdr, effectiveName))
            } else {
                ctx.stdout.writeLine(effectiveName)
            }
            // Skip body.
            skipBytes(input, hdr.size)
            skipBytes(input, paddingFor(hdr.size).toLong())
            return
        }

        // Extract mode
        try {
            when {
                hdr.isDir -> {
                    val name = effectiveName.trimEnd('/')
                    if (name.isNotEmpty()) {
                        val target = resolvePath(ctx.cwd, name)
                        ctx.fs.mkdirs(target, hdr.mode.takeIf { it != 0 } ?: 0b111_101_101)
                    }
                    if (opts.verbose) ctx.stderr.writeLine(effectiveName)
                    // Dirs have size 0 normally; still drain.
                    skipBytes(input, hdr.size)
                    skipBytes(input, paddingFor(hdr.size).toLong())
                }

                hdr.isRegular -> {
                    val target = resolvePath(ctx.cwd, effectiveName)
                    // Ensure parent dir exists.
                    val parent = target.substringBeforeLast('/', "")
                    if (parent.isNotEmpty() && parent != "/") {
                        try {
                            ctx.fs.mkdirs(parent)
                        } catch (_: Throwable) {
                            // ignore
                        }
                    }
                    val sink =
                        ctx.fs.sink(
                            target,
                            append = false,
                            mode = if (hdr.mode != 0) hdr.mode else 0b110_100_100,
                        )
                    try {
                        var remaining = hdr.size
                        val buf = Buffer()
                        while (remaining > 0) {
                            val want = minOf(remaining, 64 * 1024L)
                            val n = input.readAtMostTo(buf, want)
                            if (n == -1L) throw RuntimeException("unexpected EOF inside body of $effectiveName")
                            sink.write(buf, buf.size)
                            remaining -= n
                        }
                        sink.flush()
                    } finally {
                        sink.close()
                    }
                    skipBytes(input, paddingFor(hdr.size).toLong())
                    if (opts.verbose) ctx.stderr.writeLine(effectiveName)
                }

                else -> {
                    // Unsupported type — skip body.
                    ctx.stderr.writeUtf8(
                        "pax: $effectiveName: unsupported entry type (typeflag=${hdr.typeflag.toInt() and 0xff})\n",
                    )
                    skipBytes(input, hdr.size)
                    skipBytes(input, paddingFor(hdr.size).toLong())
                    update(null, null, 1)
                }
            }
        } catch (e: Throwable) {
            ctx.stderr.writeUtf8("pax: $effectiveName: ${e.message ?: e::class.simpleName}\n")
            update(null, null, 1)
        }
    }

    private fun formatVerbose(
        hdr: UstarFormat.Header,
        name: String,
    ): String {
        // Simple `tar tv`-style line: typechar, mode, size, mtime, name.
        val typeChar =
            when {
                hdr.isDir -> 'd'
                hdr.isRegular -> '-'
                hdr.typeflag == UstarFormat.TYPE_SYMLINK -> 'l'
                else -> '?'
            }
        val modeStr = modeString(hdr.mode)
        return "$typeChar$modeStr ${hdr.uid}/${hdr.gid} ${hdr.size} ${hdr.mtime} $name"
    }

    private fun modeString(mode: Int): String {
        val sb = StringBuilder()
        for (shift in intArrayOf(6, 3, 0)) {
            val bits = (mode shr shift) and 0b111
            sb.append(if ((bits and 4) != 0) 'r' else '-')
            sb.append(if ((bits and 2) != 0) 'w' else '-')
            sb.append(if ((bits and 1) != 0) 'x' else '-')
        }
        return sb.toString()
    }

    private suspend fun readBody(
        input: SuspendSource,
        size: Long,
    ): ByteArray {
        val out = ByteArray(size.toInt())
        var got = 0
        val buf = Buffer()
        while (got < size.toInt()) {
            val want = minOf(size - got, 64 * 1024L)
            val n = input.readAtMostTo(buf, want)
            if (n == -1L) throw RuntimeException("unexpected EOF reading body")
            val read = buf.readByteArray()
            for (i in read.indices) out[got + i] = read[i]
            got += read.size
        }
        skipBytes(input, paddingFor(size).toLong())
        return out
    }

    private suspend fun readFullBlock(input: SuspendSource): ByteArray? {
        val buf = Buffer()
        var got = 0
        while (got < UstarFormat.BLOCK) {
            val n = input.readAtMostTo(buf, (UstarFormat.BLOCK - got).toLong())
            if (n == -1L) {
                if (got == 0) return null
                // Partial block at EOF — treat as truncation.
                return null
            }
            got += n.toInt()
        }
        return buf.readByteArray()
    }

    private suspend fun skipBytes(
        input: SuspendSource,
        n: Long,
    ) {
        if (n <= 0) return
        var remaining = n
        val buf = Buffer()
        while (remaining > 0) {
            val want = minOf(remaining, 64 * 1024L)
            val r = input.readAtMostTo(buf, want)
            if (r == -1L) return
            buf.clear()
            remaining -= r
        }
    }

    private fun paddingFor(size: Long): Int = ((-size) and 511L).toInt()

    private fun resolvePath(
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

    // -- option parsing --

    private class UsageError(
        message: String,
    ) : RuntimeException(message)

    internal data class Options(
        val read: Boolean = false,
        val write: Boolean = false,
        val verbose: Boolean = false,
        val archive: String? = null,
        val format: String = "ustar",
        val operands: List<String> = emptyList(),
        val unsupportedSubst: Boolean = false,
    )

    internal fun parseArgs(args: List<String>): Options {
        var read = false
        var write = false
        var verbose = false
        var archive: String? = null
        var format = "ustar"
        var unsupportedSubst = false
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

                a == "-r" -> {
                    read = true
                }

                a == "-w" -> {
                    write = true
                }

                a == "-v" -> {
                    verbose = true
                }

                a == "-f" -> {
                    if (i + 1 >= args.size) throw UsageError("option requires an argument -- f")
                    archive = args[i + 1]
                    i++
                }

                a.startsWith("-f") && a.length > 2 -> {
                    archive = a.substring(2)
                }

                a == "-x" -> {
                    if (i + 1 >= args.size) throw UsageError("option requires an argument -- x")
                    format = args[i + 1]
                    i++
                }

                a.startsWith("-x") && a.length > 2 -> {
                    format = a.substring(2)
                }

                a == "-s" -> {
                    if (i + 1 >= args.size) throw UsageError("option requires an argument -- s")
                    // accept but defer
                    i++
                    unsupportedSubst = true
                }

                a.startsWith("-s") && a.length > 2 -> {
                    unsupportedSubst = true
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Allow bundled flags like -rv, -wv, -vf FILE.
                    if (!tryBundled(a) { c ->
                            when (c) {
                                'r' -> read = true
                                'w' -> write = true
                                'v' -> verbose = true
                                else -> return@tryBundled false
                            }
                            true
                        }
                    ) {
                        throw UsageError("unknown option: $a")
                    }
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        if (format != "ustar" && format != "pax") {
            throw UsageError("unsupported format: $format (only ustar / pax)")
        }

        return Options(
            read = read,
            write = write,
            verbose = verbose,
            archive = archive,
            format = format,
            operands = operands.toList(),
            unsupportedSubst = unsupportedSubst,
        )
    }

    private inline fun tryBundled(
        a: String,
        consume: (Char) -> Boolean,
    ): Boolean {
        // a starts with '-' and has length > 1; each subsequent char must consume.
        for (j in 1 until a.length) {
            if (!consume(a[j])) return false
        }
        return true
    }
}
