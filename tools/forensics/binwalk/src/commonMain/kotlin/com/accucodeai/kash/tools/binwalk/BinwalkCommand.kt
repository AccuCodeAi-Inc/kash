package com.accucodeai.kash.tools.binwalk

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
import com.accucodeai.kash.magic.Carving
import com.accucodeai.kash.magic.KMagic
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

private fun resolvePath(
    cwd: String,
    path: String,
): String = if (path.startsWith("/")) path else "$cwd/$path"

private val HELP =
    """
    Usage: binwalk [OPTIONS] FILE...

    Scan a file for embedded file signatures at any offset (firmware / blob
    carving), built on kash's KMagic signature engine.

      -e, --extract        carve each detected item to <file>.extracted/
      -E, --entropy        print a per-block Shannon entropy map instead of a
                           signature scan (flags likely compressed/encrypted)
      -y, --include GLOB   only show matches whose description contains GLOB
                           (case-insensitive substring)
      -x, --exclude GLOB   hide matches whose description contains GLOB
          --include-text   also report text/BOM/script signatures
                           (suppressed by default as carving noise)
      -h, --help           this help and exit

    Default output is the classic three-column signature table:
      DECIMAL   HEXADECIMAL   DESCRIPTION

    With -e, lengths are exact for formats KMagic can measure (PNG, BMP);
    otherwise each item is carved from its offset up to the next detected
    item (or end of file).
    """.trimIndent() + "\n"

private const val ENTROPY_BLOCK = 1024

public class BinwalkCommand :
    Command,
    CommandSpec {
    override val name: String = "binwalk"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    /** Streaming window size; small values in tests force chunk-boundary cases. */
    internal var chunkSize: Int = 1 shl 20

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var extract = false
        var entropy = false
        var includeText = false
        val includes = mutableListOf<String>()
        val excludes = mutableListOf<String>()
        val files = mutableListOf<String>()

        var i = 0
        var endOfOpts = false
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                files += a
                i++
                continue
            }
            when (a) {
                "--" -> {
                    endOfOpts = true
                }

                "-h", "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                "-e", "--extract", "--dd" -> {
                    extract = true
                }

                "-E", "--entropy" -> {
                    entropy = true
                }

                "--include-text" -> {
                    includeText = true
                }

                "-y", "--include" -> {
                    val v = args.getOrNull(i + 1)
                    if (v == null) {
                        ctx.stderr.writeUtf8("binwalk: $a requires an argument\n")
                        return CommandResult(exitCode = 1)
                    }
                    includes += v.lowercase()
                    i += 2
                    continue
                }

                "-x", "--exclude" -> {
                    val v = args.getOrNull(i + 1)
                    if (v == null) {
                        ctx.stderr.writeUtf8("binwalk: $a requires an argument\n")
                        return CommandResult(exitCode = 1)
                    }
                    excludes += v.lowercase()
                    i += 2
                    continue
                }

                "-" -> {
                    files += a
                }

                else -> {
                    if (a.startsWith("-") && a.length > 1) {
                        ctx.stderr.writeUtf8("binwalk: unknown option: $a\n")
                        return CommandResult(exitCode = 1)
                    }
                    files += a
                }
            }
            i++
        }

        if (files.isEmpty()) files += "-"

        var anyError = false
        var first = true
        for (target in files) {
            if (entropy) {
                if (!first) ctx.stdout.writeUtf8("\n")
                first = false
                if (!entropyReport(target, ctx)) anyError = true
                continue
            }
            // Extraction needs the bytes, so it reads the file in; the
            // report-only path streams in bounded windows (see scanSource).
            val blob: ByteArray? =
                if (extract) {
                    readBlob(target, ctx) ?: run {
                        anyError = true
                        continue
                    }
                } else {
                    null
                }
            val rawHits: List<Carving>? =
                if (blob != null) {
                    KMagic.scan(blob)
                } else {
                    scanSource(target, ctx) ?: run {
                        anyError = true
                        continue
                    }
                }

            val hits =
                rawHits!!
                    .filter { includeText || !it.match.mime.startsWith("text/") }
                    .filter { c -> includes.isEmpty() || includes.any { it in c.match.description.lowercase() } }
                    .filter { c -> excludes.none { it in c.match.description.lowercase() } }

            if (!first) ctx.stdout.writeUtf8("\n")
            first = false
            ctx.stdout.writeUtf8("${displayName(target)}\n")
            writeTable(hits, ctx)

            if (blob != null) extractAll(target, blob, hits, ctx)
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private fun displayName(target: String): String = if (target == "-") "(stdin)" else target

    /**
     * Single-pass streaming scan: reads [chunkSize]-byte windows, scanning each
     * window's owned (non-overlap) magic-position range and keeping only the
     * trailing [KMagic.scanLookBehind] + [KMagic.scanLookAhead] bytes as context
     * for the next window. Memory stays O(chunk) regardless of file size.
     *
     * Returns absolute-offset hits, or null after reporting a missing file.
     */
    private suspend fun scanSource(
        target: String,
        ctx: CommandContext,
    ): List<Carving>? {
        val src =
            if (target == "-") {
                ctx.stdin
            } else {
                try {
                    ctx.process.fs.source(resolvePath(ctx.process.cwd, target))
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("binwalk: cannot open '$target' (No such file or directory)\n")
                    return null
                }
            }
        try {
            val lookAhead = KMagic.scanLookAhead
            val lookBehind = KMagic.scanLookBehind
            val all = ArrayList<Carving>()
            var win = ByteArray(0)
            var winBase = 0L
            var ownedNext = 0
            val sink = Buffer()
            var eof = false
            while (!eof) {
                var got = 0L
                while (got < chunkSize) {
                    val n = src.readAtMostTo(sink, chunkSize.toLong() - got)
                    if (n == -1L) {
                        eof = true
                        break
                    }
                    got += n
                }
                val data = sink.readByteArray()
                win = if (win.isEmpty()) data else win + data
                val scanEnd = if (eof) win.size else maxOf(0, win.size - lookAhead)
                if (scanEnd > ownedNext) {
                    for (h in KMagic.scanRange(win, ownedNext, scanEnd)) {
                        all += Carving(winBase + h.offset, h.match)
                    }
                    ownedNext = scanEnd
                }
                if (!eof) {
                    val dropTo = maxOf(0, scanEnd - lookBehind)
                    if (dropTo > 0) {
                        win = win.copyOfRange(dropTo, win.size)
                        winBase += dropTo
                        ownedNext -= dropTo
                    }
                }
            }
            all.sortBy { it.offset }
            return all
        } finally {
            if (target != "-") src.close()
        }
    }

    private suspend fun readBlob(
        target: String,
        ctx: CommandContext,
    ): ByteArray? {
        if (target == "-") return ctx.stdin.readAllBytes()
        val path = resolvePath(ctx.process.cwd, target)
        return try {
            val src = ctx.process.fs.source(path)
            try {
                src.readAllBytes()
            } finally {
                src.close()
            }
        } catch (_: FileNotFound) {
            ctx.stderr.writeUtf8("binwalk: cannot open '$target' (No such file or directory)\n")
            null
        }
    }

    private suspend fun writeTable(
        hits: List<Carving>,
        ctx: CommandContext,
    ) {
        ctx.stdout.writeUtf8("DECIMAL       HEXADECIMAL     DESCRIPTION\n")
        ctx.stdout.writeUtf8("-".repeat(72) + "\n")
        for (h in hits) {
            val dec = h.offset.toString().padEnd(14)
            val hex = ("0x" + h.offset.toString(16).uppercase()).padEnd(16)
            ctx.stdout.writeUtf8("$dec$hex${h.match.description}\n")
        }
    }

    private suspend fun extractAll(
        target: String,
        blob: ByteArray,
        hits: List<Carving>,
        ctx: CommandContext,
    ) {
        if (hits.isEmpty()) return
        val baseName = if (target == "-") "stdin" else target.substringAfterLast('/')
        val dir = resolvePath(ctx.process.cwd, "$baseName.extracted")
        ctx.process.fs.mkdirs(dir)
        for ((idx, h) in hits.withIndex()) {
            val start = h.offset.toInt()
            // Exact length when KMagic can measure the format; else carve to the
            // next detected item (or EOF).
            val measured = KMagic.measure(blob, start, h.match)?.toInt()
            val end =
                when {
                    measured != null -> minOf(start + measured, blob.size)
                    idx + 1 < hits.size -> hits[idx + 1].offset.toInt()
                    else -> blob.size
                }
            if (end <= start) continue
            val ext =
                h.match.extensions
                    .firstOrNull()
                    ?.let { ".$it" } ?: ""
            val name =
                h.offset
                    .toString(16)
                    .uppercase()
                    .padStart(8, '0') + ext
            val sink = ctx.process.fs.sink("$dir/$name", append = false, mode = 0b110_100_100)
            try {
                sink.writeBytes(blob.copyOfRange(start, end))
                sink.flush()
            } finally {
                sink.close()
            }
        }
        ctx.stdout.writeUtf8("\n${hits.size} item(s) extracted to $baseName.extracted/\n")
    }

    /**
     * Streaming Shannon-entropy map: one reading per [ENTROPY_BLOCK]-byte block,
     * normalized 0.0–1.0, flagging high-entropy (likely compressed/encrypted)
     * regions. Never holds more than one block in memory.
     */
    private suspend fun entropyReport(
        target: String,
        ctx: CommandContext,
    ): Boolean {
        val src =
            if (target == "-") {
                ctx.stdin
            } else {
                try {
                    ctx.process.fs.source(resolvePath(ctx.process.cwd, target))
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("binwalk: cannot open '$target' (No such file or directory)\n")
                    return false
                }
            }
        try {
            ctx.stdout.writeUtf8("${displayName(target)}\n")
            ctx.stdout.writeUtf8("DECIMAL       HEXADECIMAL     ENTROPY\n")
            ctx.stdout.writeUtf8("-".repeat(72) + "\n")
            val sink = Buffer()
            var offset = 0L
            var eof = false
            while (!eof) {
                var got = 0L
                while (got < ENTROPY_BLOCK) {
                    val n = src.readAtMostTo(sink, ENTROPY_BLOCK.toLong() - got)
                    if (n == -1L) {
                        eof = true
                        break
                    }
                    got += n
                }
                if (got == 0L) break
                val block = sink.readByteArray()
                val e = shannonEntropy(block, block.size)
                val dec = offset.toString().padEnd(14)
                val hex = ("0x" + offset.toString(16).uppercase()).padEnd(16)
                val flag = if (e >= HIGH_ENTROPY) "  (high — possible compression/encryption)" else ""
                ctx.stdout.writeUtf8("$dec$hex${formatEntropy(e)}$flag\n")
                offset += block.size
            }
            return true
        } finally {
            if (target != "-") src.close()
        }
    }

    private fun formatEntropy(e: Double): String {
        // Six decimal places without relying on String.format (not in commonMain).
        val scaled = (e * 1_000_000).toLong()
        val whole = scaled / 1_000_000
        val frac = (scaled % 1_000_000).toString().padStart(6, '0')
        return "$whole.$frac"
    }
}
