package com.accucodeai.kash.tools.dd

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths
import kotlinx.io.readByteArray

/**
 * `dd` — copy and convert a byte stream.
 *
 * Arguments are `name=value` pairs, not POSIX short flags. See [DdOperands]
 * for the recognized names. POSIX core operands (if, of, ibs, obs, bs, cbs,
 * skip, seek, count, conv=lcase/ucase/swab/block/unblock/sync/noerror/notrunc)
 * are supported. GNU extensions iflag/oflag/status are supported with the
 * subset of values most commonly used in shell scripts.
 *
 * Not implemented in v1: `conv=ascii`/`conv=ebcdic` translation tables,
 * `status=progress` mid-copy reporting (treated as default), `iflag=count_bytes`
 * / `skip_bytes` / `seek_bytes` (would change skip/count/seek units).
 */
public class DdCommand :
    Command,
    CommandSpec {
    override val name: String = "dd"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        // Meta args first.
        for (a in args) {
            if (a == "--help") {
                ctx.stdout.writeUtf8(HELP_TEXT)
                return CommandResult(exitCode = 0)
            }
            if (a == "--version") {
                ctx.stdout.writeUtf8("dd (kash)\n")
                return CommandResult(exitCode = 0)
            }
        }

        val ops: DdOperands =
            try {
                parseDdOperands(args)
            } catch (e: DdOperandException) {
                val m = e.message.orEmpty()
                if (m.startsWith("__meta__:")) {
                    // Already handled above; defensive.
                    return CommandResult(exitCode = 0)
                }
                ctx.stderr.writeUtf8("dd: $m\n")
                return CommandResult(exitCode = 2)
            }

        if (DdConvFlag.ASCII in ops.conv || DdConvFlag.EBCDIC in ops.conv) {
            ctx.stderr.writeUtf8("dd: conv=ascii/ebcdic translation tables not implemented\n")
            return CommandResult(exitCode = 1)
        }
        if ((DdConvFlag.BLOCK in ops.conv || DdConvFlag.UNBLOCK in ops.conv) && ops.cbs <= 0) {
            ctx.stderr.writeUtf8("dd: conv=block/unblock requires cbs=N\n")
            return CommandResult(exitCode = 2)
        }

        // --- Read input ---
        // Compute an upper bound so we don't try to drain an infinite source
        // (`dd if=/dev/urandom count=4`). When `count` is set, we need exactly
        // skip*ibs + count*ibs bytes; otherwise we have to read everything
        // (POSIX behavior on a finite source — unbounded sources will run
        // forever).
        val skipBytes = ops.skip * ops.ibs
        val wantBytes: Long? =
            ops.count?.let { skipBytes + it * ops.ibs }
        val rawInput: ByteArray =
            try {
                if (ops.input != null) {
                    val p = Paths.resolve(ctx.process.cwd, ops.input)
                    if (wantBytes != null) {
                        readBoundedFromFs(ctx, p, wantBytes)
                    } else {
                        ctx.process.fs.readBytes(p)
                    }
                } else {
                    if (wantBytes != null) {
                        readBoundedFromSource(ctx.stdin, wantBytes)
                    } else {
                        ctx.stdin.readAllBytes()
                    }
                }
            } catch (t: Throwable) {
                if (DdConvFlag.NOERROR !in ops.conv) {
                    ctx.stderr.writeUtf8("dd: ${ops.input ?: "stdin"}: ${t.message ?: "read error"}\n")
                    return CommandResult(exitCode = 1)
                } else {
                    ByteArray(0)
                }
            }

        // --- Apply skip (in units of ibs) ---
        val afterSkip: ByteArray =
            if (skipBytes >= rawInput.size.toLong()) {
                ByteArray(0)
            } else {
                rawInput.copyOfRange(skipBytes.toInt(), rawInput.size)
            }

        // --- Read in ibs-sized blocks (count limit if set) ---
        val ibs = ops.ibs.toInt()
        val cbs = ops.cbs.toInt()
        var recordsInFull = 0L
        var recordsInPartial = 0L
        var totalIn = 0L
        var truncated = 0L

        // Accumulate the conversion output into a contiguous buffer; we then
        // chunk that into obs blocks for writing.
        val converted = ArrayList<Byte>(afterSkip.size)
        val truncCounter = IntArray(1)

        var pos = 0
        var blocksRead = 0L
        while (pos < afterSkip.size) {
            if (ops.count != null && blocksRead >= ops.count) break
            val end = (pos + ibs).coerceAtMost(afterSkip.size)
            val blockLen = end - pos
            val block = afterSkip.copyOfRange(pos, end)
            if (blockLen == ibs) recordsInFull++ else recordsInPartial++
            totalIn += blockLen
            pos = end
            blocksRead++

            // --- Apply conversions, in POSIX order ---
            var cur: ByteArray = block

            if (DdConvFlag.SWAB in ops.conv) cur = DdConv.swab(cur)

            // block/unblock convert per-block, then disable sync padding.
            val doBlock = DdConvFlag.BLOCK in ops.conv
            val doUnblock = DdConvFlag.UNBLOCK in ops.conv
            if (doBlock) {
                cur = DdConv.block(cur, cbs, truncCounter)
            } else if (doUnblock) {
                cur = DdConv.unblock(cur, cbs)
            }

            if (DdConvFlag.LCASE in ops.conv) cur = DdConv.lcase(cur)
            if (DdConvFlag.UCASE in ops.conv) cur = DdConv.ucase(cur)

            if (DdConvFlag.SYNC in ops.conv && !doBlock && !doUnblock) {
                cur = DdConv.sync(cur, ibs)
            }

            for (b in cur) converted += b
        }
        truncated = truncCounter[0].toLong()

        // --- Write output: chunk converted bytes into obs blocks ---
        val obs = ops.obs.toInt()
        val totalOut = converted.size.toLong()
        val recordsOutFull = totalOut / obs
        val recordsOutPartial = if (totalOut % obs != 0L) 1L else 0L

        val outBytes = converted.toByteArray()

        // Apply seek + write/truncate behavior to the output file or stdout.
        try {
            writeOutput(ctx, ops, outBytes)
        } catch (t: Throwable) {
            ctx.stderr.writeUtf8("dd: ${ops.output ?: "stdout"}: ${t.message ?: "write error"}\n")
            return CommandResult(exitCode = 1)
        }

        // --- Summary on stderr ---
        if (ops.status != DdStatus.NONE) {
            ctx.stderr.writeUtf8("$recordsInFull+$recordsInPartial records in\n")
            ctx.stderr.writeUtf8("$recordsOutFull+$recordsOutPartial records out\n")
            if (ops.status != DdStatus.NOXFER) {
                ctx.stderr.writeUtf8("$totalOut bytes (${humanBytes(totalOut)}) copied\n")
            }
            if (DdConvFlag.BLOCK in ops.conv && truncated > 0) {
                ctx.stderr.writeUtf8("$truncated truncated record${if (truncated == 1L) "" else "s"}\n")
            }
        }

        return CommandResult(exitCode = 0)
    }

    private suspend fun writeOutput(
        ctx: CommandContext,
        ops: DdOperands,
        outBytes: ByteArray,
    ) {
        val seekBytes = (ops.seek * ops.obs).toInt()
        val notrunc = DdConvFlag.NOTRUNC in ops.conv
        val append = DdIoFlag.APPEND in ops.oflag

        if (ops.output != null) {
            val path = Paths.resolve(ctx.process.cwd, ops.output)
            val fs = ctx.process.fs

            if (append) {
                // append doesn't honor seek (POSIX/GNU semantics: seek ignored or stacked after EOF).
                val payload =
                    if (seekBytes > 0) ByteArray(seekBytes) + outBytes else outBytes
                fs.appendBytes(path, payload)
                return
            }

            // Build final bytes: seek pads from beginning. With notrunc, preserve
            // existing tail beyond the written region; without notrunc, truncate
            // at end of written region.
            val existing: ByteArray =
                if (notrunc && fs.exists(path)) {
                    try {
                        fs.readBytes(path)
                    } catch (_: Throwable) {
                        ByteArray(0)
                    }
                } else {
                    ByteArray(0)
                }

            // Compose buffer: zeros up to seekBytes, then outBytes, then any
            // trailing portion of `existing` past seekBytes+outBytes.
            val endOfWrite = seekBytes + outBytes.size
            val finalLen =
                if (notrunc) maxOf(endOfWrite, existing.size) else endOfWrite

            val finalBuf = ByteArray(finalLen)
            // Preserve pre-seek bytes from existing when notrunc.
            if (notrunc && existing.isNotEmpty()) {
                val pre = minOf(existing.size, seekBytes)
                existing.copyInto(finalBuf, 0, 0, pre)
                // Preserve tail past write region.
                if (existing.size > endOfWrite) {
                    existing.copyInto(finalBuf, endOfWrite, endOfWrite, existing.size)
                }
            }
            outBytes.copyInto(finalBuf, seekBytes)
            fs.writeBytes(path, finalBuf)
        } else {
            // stdout: seek prepends NULs; append/notrunc don't apply.
            if (seekBytes > 0) ctx.stdout.writeBytes(ByteArray(seekBytes))
            ctx.stdout.writeBytes(outBytes)
        }
    }

    private fun humanBytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var v = n.toDouble() / 1024.0
        var u = 0
        while (v >= 1024.0 && u < units.size - 1) {
            v /= 1024.0
            u++
        }
        // one decimal place without locale formatting issues
        val tenths = (v * 10.0).toLong()
        val whole = tenths / 10
        val frac = tenths % 10
        return "$whole.$frac ${units[u]}"
    }

    /** Read at most [limit] bytes from [source] into a ByteArray. */
    private suspend fun readBoundedFromSource(
        source: com.accucodeai.kash.api.io.SuspendSource,
        limit: Long,
    ): ByteArray {
        val buf = kotlinx.io.Buffer()
        var remaining = limit
        while (remaining > 0L) {
            val want = remaining.coerceAtMost(READ_CHUNK)
            val n = source.readAtMostTo(buf, want)
            if (n == -1L) break
            if (n == 0L) continue
            remaining -= n
        }
        return buf.readByteArray()
    }

    /** Read at most [limit] bytes from [path] in the VFS. */
    private suspend fun readBoundedFromFs(
        ctx: CommandContext,
        path: String,
        limit: Long,
    ): ByteArray {
        val src = ctx.process.fs.source(path)
        try {
            return readBoundedFromSource(src, limit)
        } finally {
            try {
                src.close()
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        const val HELP_TEXT: String =
            "usage: dd [if=FILE] [of=FILE] [bs=N] [ibs=N] [obs=N] [cbs=N]\n" +
                "          [skip=N] [seek=N] [count=N] [conv=LIST] [iflag=LIST]\n" +
                "          [oflag=LIST] [status=none|noxfer|progress]\n"
        const val READ_CHUNK: Long = 8 * 1024L
    }
}
