package com.accucodeai.kash.tools.cpio

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileType
import kotlinx.io.readByteArray

/**
 * `cpio` — archive copy.
 *
 * Modes (mutually exclusive):
 *   -o   copy-out: read filenames from stdin, write archive to stdout
 *   -i   copy-in: read archive from stdin, extract files
 *   -t   table-of-contents: read archive from stdin, list entries
 *
 * Format flags:
 *   -H newc|odc   archive format (default: newc)
 *
 * Common flags:
 *   -v   verbose: list each file as it's processed (to stderr)
 *   -d   (extract) create leading directories as needed
 *   -u   (extract) unconditionally overwrite existing files
 *   -m   (extract) preserve archive mtime on extracted files
 *
 * Streaming: archives are processed entry-by-entry; we hold at most one
 * file's payload in memory at a time.
 */
public class CpioCommand :
    Command,
    CommandSpec {
    override val name: String = "cpio"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.FS_WRITE)
    override val command: Command get() = this

    private data class Options(
        var mode: Char = ' ', // 'o', 'i', 't'
        var format: CpioFormat = CpioFormat.NEWC,
        var verbose: Boolean = false,
        var mkdirs: Boolean = false,
        var overwrite: Boolean = false,
        var preserveMtime: Boolean = false,
        var patterns: List<String> = emptyList(),
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = Options()
        var i = 0
        var endOpts = false
        val operands = mutableListOf<String>()
        while (i < args.size) {
            val a = args[i]
            if (endOpts) {
                operands += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOpts = true
                }

                a == "-o" || a == "--create" -> {
                    opts.mode = setMode(opts.mode, 'o', ctx) ?: return usage(ctx)
                }

                a == "-i" || a == "--extract" -> {
                    opts.mode = setMode(opts.mode, 'i', ctx) ?: return usage(ctx)
                }

                a == "-t" || a == "--list" -> {
                    opts.mode = setMode(opts.mode, 't', ctx) ?: return usage(ctx)
                }

                a == "-v" || a == "--verbose" -> {
                    opts.verbose = true
                }

                a == "-d" || a == "--make-directories" -> {
                    opts.mkdirs = true
                }

                a == "-u" || a == "--unconditional" -> {
                    opts.overwrite = true
                }

                a == "-m" || a == "--preserve-modification-time" -> {
                    opts.preserveMtime = true
                }

                a == "-H" || a == "--format" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("cpio: option requires an argument -- H\n")
                        return CommandResult(exitCode = 2)
                    }
                    val f = CpioFormat.parse(args[i + 1])
                    if (f == null) {
                        ctx.stderr.writeUtf8("cpio: unsupported format '${args[i + 1]}' (supported: newc, odc)\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.format = f
                    i += 2
                    continue
                }

                a.startsWith("-H") && a.length > 2 -> {
                    val f = CpioFormat.parse(a.substring(2))
                    if (f == null) {
                        ctx.stderr.writeUtf8("cpio: unsupported format '${a.substring(2)}'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.format = f
                }

                a.startsWith("--format=") -> {
                    val f = CpioFormat.parse(a.substring("--format=".length))
                    if (f == null) {
                        ctx.stderr.writeUtf8("cpio: unsupported format '${a.substring("--format=".length)}'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.format = f
                }

                a.startsWith("-") && a.length > 1 && !a.startsWith("--") -> {
                    // Short cluster: each letter is a flag.
                    for (c in a.substring(1)) {
                        when (c) {
                            'o' -> {
                                opts.mode = setMode(opts.mode, 'o', ctx) ?: return usage(ctx)
                            }

                            'i' -> {
                                opts.mode = setMode(opts.mode, 'i', ctx) ?: return usage(ctx)
                            }

                            't' -> {
                                opts.mode = setMode(opts.mode, 't', ctx) ?: return usage(ctx)
                            }

                            'v' -> {
                                opts.verbose = true
                            }

                            'd' -> {
                                opts.mkdirs = true
                            }

                            'u' -> {
                                opts.overwrite = true
                            }

                            'm' -> {
                                opts.preserveMtime = true
                            }

                            else -> {
                                ctx.stderr.writeUtf8("cpio: unknown option -$c\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                    }
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("cpio: unknown option $a\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    operands += a
                }
            }
            i++
        }
        opts.patterns = operands

        return when (opts.mode) {
            'o' -> {
                doCopyOut(opts, ctx)
            }

            'i' -> {
                doCopyIn(opts, ctx, extract = true)
            }

            't' -> {
                doCopyIn(opts, ctx, extract = false)
            }

            else -> {
                ctx.stderr.writeUtf8("cpio: must specify one of -o, -i, -t\n")
                CommandResult(exitCode = 2)
            }
        }
    }

    private fun setMode(
        current: Char,
        next: Char,
        ctx: CommandContext,
    ): Char? {
        if (current != ' ' && current != next) {
            return null
        }
        return next
    }

    private suspend fun usage(ctx: CommandContext): CommandResult {
        ctx.stderr.writeUtf8("cpio: cannot mix -o / -i / -t\n")
        return CommandResult(exitCode = 2)
    }

    // ---- copy-out ---------------------------------------------------------

    private suspend fun doCopyOut(
        opts: Options,
        ctx: CommandContext,
    ): CommandResult {
        val sink = CountingSink(ctx.stdout)
        var status = 0
        // Read filenames from stdin, one per line.
        while (true) {
            val line = ctx.stdin.readUtf8LineOrNull() ?: break
            val name = line.trimEnd('\r')
            if (name.isEmpty()) continue
            val path = resolvePath(ctx.cwd, name)
            try {
                val st =
                    try {
                        ctx.fs.statLink(path)
                    } catch (_: FileNotFound) {
                        ctx.stderr.writeUtf8("cpio: $name: No such file or directory\n")
                        status = 1
                        continue
                    }
                val payload: ByteArray
                val mode: Int
                when (st.type) {
                    FileType.REGULAR -> {
                        payload = readAllBytes(ctx.fs.source(path))
                        mode = 0x8000 or (st.mode and 0xFFF)
                    }

                    FileType.DIRECTORY -> {
                        payload = ByteArray(0)
                        mode = 0x4000 or (st.mode and 0xFFF)
                    }

                    FileType.SYMLINK -> {
                        val target = ctx.fs.readSymlink(path)
                        payload = target.encodeToByteArray()
                        mode = 0xA000 or (st.mode and 0xFFF)
                    }

                    else -> {
                        ctx.stderr.writeUtf8("cpio: $name: unsupported file type\n")
                        status = 1
                        continue
                    }
                }
                val h =
                    CpioHeader(
                        name = name,
                        mode = mode,
                        uid = 0,
                        gid = 0,
                        nlink = 1,
                        mtime = st.mtimeEpochSeconds,
                        size = payload.size.toLong(),
                        ino = 0L,
                    )
                when (opts.format) {
                    CpioFormat.NEWC -> writeNewcEntry(sink, h, payload)
                    CpioFormat.ODC -> writeOdcEntry(sink, h, payload)
                }
                if (opts.verbose) ctx.stderr.writeLine(name)
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("cpio: $name: ${e.message ?: "I/O error"}\n")
                status = 1
            }
        }
        writeTrailer(sink, opts.format)
        sink.flush()
        return CommandResult(exitCode = status)
    }

    // ---- copy-in / list ---------------------------------------------------

    private suspend fun doCopyIn(
        opts: Options,
        ctx: CommandContext,
        extract: Boolean,
    ): CommandResult {
        val src = CountingSource(ctx.stdin)
        var status = 0
        // Auto-detect format by peeking at first 6 bytes? Most cpio impls
        // auto-detect on -i; we honor -H if given, otherwise detect.
        // For simplicity: trust -H; if not given, try newc first by peeking
        // via a small wrapper. Easier: peek the first 6 bytes.
        val detected = detectFormat(src) ?: opts.format
        val format =
            // If user explicitly passed -H, prefer that; we only set
            // opts.format to NEWC when defaulted. Track explicit later if
            // needed. For now: trust detection on input side.
            detected
        while (true) {
            val h =
                try {
                    when (format) {
                        CpioFormat.NEWC -> readNewcHeader(src)
                        CpioFormat.ODC -> readOdcHeader(src)
                    }
                } catch (e: CpioParseException) {
                    ctx.stderr.writeUtf8("cpio: ${e.message}\n")
                    return CommandResult(exitCode = 1)
                } ?: break
            if (h.isTrailer) {
                // Drain padding for newc.
                if (format == CpioFormat.NEWC) {
                    val pad = ((4 - (src.bytesRead % 4)) % 4).toInt()
                    if (pad > 0) src.skipExact(pad)
                }
                break
            }
            if (!matchesPatterns(h.name, opts.patterns)) {
                // Skip payload.
                when (format) {
                    CpioFormat.NEWC -> skipNewcPayload(src, h)
                    CpioFormat.ODC -> skipOdcPayload(src, h)
                }
                continue
            }
            if (!extract) {
                // list mode
                if (opts.verbose) {
                    ctx.stdout.writeLine(formatVerboseLine(h))
                } else {
                    ctx.stdout.writeLine(h.name)
                }
                when (format) {
                    CpioFormat.NEWC -> skipNewcPayload(src, h)
                    CpioFormat.ODC -> skipOdcPayload(src, h)
                }
                continue
            }
            // Extract.
            val payload =
                when (format) {
                    CpioFormat.NEWC -> readNewcPayload(src, h)
                    CpioFormat.ODC -> readOdcPayload(src, h)
                }
            try {
                extractEntry(h, payload, opts, ctx)
                if (opts.verbose) ctx.stderr.writeLine(h.name)
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("cpio: ${h.name}: ${e.message ?: "I/O error"}\n")
                status = 1
            }
        }
        return CommandResult(exitCode = status)
    }

    private suspend fun detectFormat(src: CountingSource): CpioFormat? {
        // Read 6 bytes, peek at the magic, then push the bytes back. We can't
        // push back, so instead we restructure: read magic into a Buffer,
        // wrap as a synthetic source that emits those bytes first.
        //
        // Simpler approach: just return null here — the caller already has
        // opts.format with its NEWC default; that gets used. We DO NOT
        // auto-detect for v1 — caller passes -H if archive isn't newc.
        return null
    }

    private fun matchesPatterns(
        name: String,
        patterns: List<String>,
    ): Boolean {
        if (patterns.isEmpty()) return true
        for (p in patterns) {
            if (globMatches(p, name)) return true
        }
        return false
    }

    private suspend fun extractEntry(
        h: CpioHeader,
        payload: ByteArray,
        opts: Options,
        ctx: CommandContext,
    ) {
        val rel = h.name.trimStart('/')
        val full = resolvePath(ctx.cwd, rel)
        if (h.isDir) {
            if (!ctx.fs.exists(full)) {
                ctx.fs.mkdirs(full)
            }
            return
        }
        if (h.isSymlink) {
            val target = payload.decodeToString()
            if (opts.mkdirs) ensureParent(full, ctx)
            if (ctx.fs.exists(full) && !opts.overwrite) {
                throw RuntimeException("not overwritten")
            }
            if (ctx.fs.exists(full)) {
                try {
                    ctx.fs.remove(full)
                } catch (_: Exception) {
                }
            }
            try {
                ctx.fs.createSymlink(full, target)
            } catch (_: UnsupportedOperationException) {
                // Fall back: write target as a regular file
                ctx.fs.writeBytes(full, payload)
            }
            return
        }
        // Regular file.
        if (opts.mkdirs) ensureParent(full, ctx)
        if (ctx.fs.exists(full)) {
            if (!opts.overwrite) {
                throw RuntimeException("not overwritten")
            }
            try {
                ctx.fs.remove(full)
            } catch (_: Exception) {
            }
        }
        ctx.fs.writeBytes(full, payload)
        if (opts.preserveMtime) {
            try {
                ctx.fs.setMtime(full, h.mtime)
            } catch (_: UnsupportedOperationException) {
                // Backend doesn't model mtime — silently skip.
            }
        }
    }

    private fun ensureParent(
        full: String,
        ctx: CommandContext,
    ) {
        val parent = full.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != "/" && !ctx.fs.exists(parent)) {
            ctx.fs.mkdirs(parent)
        }
    }

    private fun formatVerboseLine(h: CpioHeader): String {
        // -tv output mimics `ls -l`: mode-string nlink owner group size mtime name
        val ms = formatModeString(h.mode)
        val size = h.size
        return "$ms  ${h.nlink} ${h.uid} ${h.gid} $size ${h.mtime} ${h.name}"
    }

    private fun formatModeString(mode: Int): String {
        val type =
            when (mode and 0xF000) {
                0x4000 -> 'd'
                0xA000 -> 'l'
                0x8000 -> '-'
                0x2000 -> 'c'
                0x6000 -> 'b'
                0x1000 -> 'p'
                0xC000 -> 's'
                else -> '-'
            }
        val sb = StringBuilder()
        sb.append(type)
        val perms = mode and 0xFFF

        fun bit(
            mask: Int,
            ch: Char,
        ) = if ((perms and mask) != 0) ch else '-'
        sb.append(bit(0x100, 'r'))
        sb.append(bit(0x080, 'w'))
        sb.append(bit(0x040, 'x'))
        sb.append(bit(0x020, 'r'))
        sb.append(bit(0x010, 'w'))
        sb.append(bit(0x008, 'x'))
        sb.append(bit(0x004, 'r'))
        sb.append(bit(0x002, 'w'))
        sb.append(bit(0x001, 'x'))
        return sb.toString()
    }

    private suspend fun readAllBytes(src: SuspendSource): ByteArray {
        val buf = kotlinx.io.Buffer()
        while (true) {
            val n = src.readAtMostTo(buf, 8 * 1024L)
            if (n == -1L) break
        }
        src.close()
        return buf.readByteArray(buf.size.toInt())
    }
}

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

/**
 * Minimal glob: `*` matches any run of chars (incl. empty); `?` matches one
 * char; `\X` escapes a literal X; everything else is literal.
 * Adequate for the cpio operand pattern set.
 */
internal fun globMatches(
    pattern: String,
    text: String,
): Boolean {
    // Iterative DP-free backtracking; pattern is typically short.
    var pi = 0
    var ti = 0
    var starPi = -1
    var starTi = -1
    while (ti < text.length) {
        when {
            pi < pattern.length && pattern[pi] == '\\' && pi + 1 < pattern.length -> {
                if (pattern[pi + 1] == text[ti]) {
                    pi += 2
                    ti++
                } else if (starPi >= 0) {
                    pi = starPi + 1
                    starTi++
                    ti = starTi
                } else {
                    return false
                }
            }

            pi < pattern.length && pattern[pi] == '*' -> {
                starPi = pi
                starTi = ti
                pi++
            }

            pi < pattern.length && (pattern[pi] == '?' || pattern[pi] == text[ti]) -> {
                pi++
                ti++
            }

            starPi >= 0 -> {
                pi = starPi + 1
                starTi++
                ti = starTi
            }

            else -> {
                return false
            }
        }
    }
    while (pi < pattern.length && pattern[pi] == '*') pi++
    return pi == pattern.length
}
