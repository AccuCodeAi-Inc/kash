package com.accucodeai.kash.tools.tail

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FsEvent
import com.accucodeai.kash.fs.Paths
import kotlinx.coroutines.flow.takeWhile
import kotlinx.io.readByteArray

/**
 * `tail` — print the last part of files.
 *
 * Flags:
 * - `-n N` / `-nN` / `--lines=N`        — print last N lines (default 10).
 * - `-n +N` / `-n+N`                    — start at line N (1-indexed) to end.
 * - `-c N` / `-c +N` / `--bytes=N`      — byte forms analogous to `-n`.
 * - `-q` / `--quiet`                    — never print headers.
 * - `-v` / `--verbose`                  — always print headers.
 * - Obsolete: `tail -5` ≡ `-n 5`; `tail +5` ≡ `-n +5`.
 * - `--` ends options.
 * - `-f` / `--follow` — after emitting the initial tail, stream
 *   appended bytes until cancelled or the file is removed. Implemented on
 *   top of [com.accucodeai.kash.fs.FileSystem.watch], which uses an event
 *   bus on InMemoryFs and `java.nio.file.WatchService` on HostFs.
 *
 * With ≥2 file operands, each block is preceded by a `==> NAME <==` header and
 * separated from the next by a blank line.
 */
public class TailCommand :
    Command,
    CommandSpec {
    override val name: String = "tail"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private sealed interface Mode {
        data class LastLines(
            val n: Int,
        ) : Mode

        data class FromLine(
            val n: Int,
        ) : Mode

        data class LastBytes(
            val n: Int,
        ) : Mode

        data class FromByte(
            val n: Int,
        ) : Mode
    }

    private data class Parsed(
        val mode: Mode,
        val files: List<String>,
        val headers: HeaderMode,
        val follow: Boolean = false,
    )

    private enum class HeaderMode { AUTO, QUIET, VERBOSE }

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val parsed = parseArgs(args, ctx)
        if (parsed.exitCode != null) return CommandResult(exitCode = parsed.exitCode)
        val p = parsed.parsed!!

        val files = if (p.files.isEmpty()) listOf("-") else p.files
        val showHeaders =
            when (p.headers) {
                HeaderMode.QUIET -> false
                HeaderMode.VERBOSE -> true
                HeaderMode.AUTO -> files.size >= 2
            }

        var anyError = false
        var firstEmitted = false
        for (file in files) {
            val isStdin = file == "-"
            val abs = if (isStdin) file else Paths.resolve(ctx.process.cwd, file)
            if (!isStdin && !ctx.process.fs.exists(abs)) {
                ctx.stderr.writeUtf8("tail: cannot open '$file' for reading: No such file or directory\n")
                anyError = true
                continue
            }
            if (showHeaders) {
                if (firstEmitted) ctx.stdout.writeUtf8("\n")
                val label = if (isStdin) "standard input" else file
                ctx.stdout.writeUtf8("==> $label <==\n")
            }
            val source: SuspendSource = if (isStdin) ctx.stdin else ctx.process.fs.source(abs)
            try {
                emit(source, p.mode, ctx)
            } finally {
                if (!isStdin) source.close()
            }
            firstEmitted = true
        }
        // `-f`: after emitting the initial tail of each file, follow the
        // *last* file operand for appended bytes. (bash interleaves all
        // operands; we keep it simple — most scripts `tail -f one.log`.)
        // Cancellation (e.g. INT delivered to the foreground statement)
        // breaks the collect loop naturally.
        if (p.follow) {
            val target = files.lastOrNull { it != "-" && ctx.process.fs.exists(Paths.resolve(ctx.process.cwd, it)) }
            if (target != null) followFile(Paths.resolve(ctx.process.cwd, target), ctx)
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    /**
     * Stream new bytes from [file] as they appear, until the watcher is
     * cancelled or the file is deleted. Re-reads from the saved offset on
     * every Modified event — works on both InMemoryFs (event-bus) and
     * HostFs (WatchService) without per-platform code in this tool.
     */
    private suspend fun followFile(
        file: String,
        ctx: CommandContext,
    ) {
        var offset =
            ctx.process.fs
                .readBytes(file)
                .size
        // `takeWhile { it !is Deleted }` stops the collect when the file is
        // removed (bash with default flags exits in that case). Coroutine
        // cancellation (e.g. INT delivered to the foreground statement)
        // propagates out of `collect` naturally.
        ctx.process.fs
            .watch(file)
            .takeWhile { it !is FsEvent.Deleted }
            .collect { ev ->
                when (ev) {
                    is FsEvent.Modified, is FsEvent.Created -> {
                        val bytes =
                            try {
                                ctx.process.fs.readBytes(file)
                            } catch (_: Throwable) {
                                return@collect
                            }
                        if (bytes.size < offset) {
                            // Truncation — bash prints "tail: file truncated"
                            // and resumes from 0.
                            ctx.stderr.writeUtf8("tail: $file: file truncated\n")
                            offset = 0
                        }
                        if (bytes.size > offset) {
                            ctx.stdout.writeBytes(bytes.copyOfRange(offset, bytes.size))
                            offset = bytes.size
                        }
                    }

                    is FsEvent.Deleted -> { /* unreachable — takeWhile filtered */ }
                }
            }
    }

    private suspend fun emit(
        source: SuspendSource,
        mode: Mode,
        ctx: CommandContext,
    ) {
        when (mode) {
            is Mode.LastLines -> emitLastLines(source, mode.n, ctx)
            is Mode.FromLine -> emitFromLine(source, mode.n, ctx)
            is Mode.LastBytes -> emitLastBytes(source, mode.n, ctx)
            is Mode.FromByte -> emitFromByte(source, mode.n, ctx)
        }
    }

    private suspend fun emitLastLines(
        source: SuspendSource,
        n: Int,
        ctx: CommandContext,
    ) {
        if (n <= 0) {
            // Still drain so stdin is consumed.
            while (source.readUtf8LineOrNull() != null) { /* drain */ }
            return
        }
        val ring = ArrayDeque<String>(n)
        while (true) {
            val line = source.readUtf8LineOrNull() ?: break
            if (ring.size == n) ring.removeFirst()
            ring.addLast(line)
        }
        for (line in ring) ctx.stdout.writeLine(line)
    }

    private suspend fun emitFromLine(
        source: SuspendSource,
        startLine: Int,
        ctx: CommandContext,
    ) {
        // 1-indexed; `+N` means starting at line N.
        val skip = (startLine - 1).coerceAtLeast(0)
        var skipped = 0
        while (skipped < skip) {
            source.readUtf8LineOrNull() ?: return
            skipped++
        }
        while (true) {
            val line = source.readUtf8LineOrNull() ?: break
            ctx.stdout.writeLine(line)
        }
    }

    private suspend fun emitLastBytes(
        source: SuspendSource,
        n: Int,
        ctx: CommandContext,
    ) {
        if (n <= 0) return
        // Sliding window: keep at most N bytes in memory. On an infinite
        // source (`tail -c 16 /dev/urandom`) this never returns (there's
        // no end to print "from") — but memory stays bounded at N bytes
        // instead of OOMing the way readAllBytes() did.
        val window = kotlinx.io.Buffer()
        val chunk = kotlinx.io.Buffer()
        while (true) {
            val read = source.readAtMostTo(chunk, READ_CHUNK)
            if (read == -1L) break
            window.transferFrom(chunk)
            val excess = window.size - n.toLong()
            if (excess > 0) window.skip(excess)
        }
        if (window.size > 0L) ctx.stdout.writeBytes(window.readByteArray())
    }

    private suspend fun emitFromByte(
        source: SuspendSource,
        startByte: Int,
        ctx: CommandContext,
    ) {
        // Stream: skip the first (startByte - 1) bytes, then forward the
        // rest in chunks. Memory bounded at READ_CHUNK regardless of
        // input length; on an infinite source this faithfully streams
        // forever instead of trying to buffer it all up front.
        val skip = (startByte - 1L).coerceAtLeast(0L)
        val chunk = kotlinx.io.Buffer()
        var skipped = 0L
        while (skipped < skip) {
            val want = (skip - skipped).coerceAtMost(READ_CHUNK)
            val n = source.readAtMostTo(chunk, want)
            if (n == -1L) return
            chunk.clear()
            skipped += n
        }
        while (true) {
            val n = source.readAtMostTo(chunk, READ_CHUNK)
            if (n == -1L) break
            if (chunk.size == 0L) continue
            ctx.stdout.writeBytes(chunk.readByteArray())
        }
    }

    private companion object {
        const val READ_CHUNK: Long = 8 * 1024L
    }

    private data class ParseResult(
        val parsed: Parsed?,
        val exitCode: Int? = null,
    )

    private suspend fun parseArgs(
        args: List<String>,
        ctx: CommandContext,
    ): ParseResult {
        var mode: Mode = Mode.LastLines(10)
        var headers = HeaderMode.AUTO
        var follow = false
        val files = mutableListOf<String>()
        var endOfOpts = false
        var i = 0

        // POSIX-obsolete leading numeric: `tail -5 file` or `tail +5 file`.
        if (args.isNotEmpty()) {
            val a = args[0]
            if (a.length >= 2 && a[0] == '-' && a[1].isDigit()) {
                val num = a.substring(1).toIntOrNull()
                if (num != null) {
                    mode = Mode.LastLines(num)
                    i = 1
                }
            } else if (a.length >= 2 && a[0] == '+' && a[1].isDigit()) {
                val num = a.substring(1).toIntOrNull()
                if (num != null) {
                    mode = Mode.FromLine(num)
                    i = 1
                }
            }
        }

        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                files += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-" -> {
                    files += a
                }

                a == "-f" || a == "--follow" || a.startsWith("--follow=") -> {
                    follow = true
                }

                a == "-q" || a == "--quiet" || a == "--silent" -> {
                    headers = HeaderMode.QUIET
                }

                a == "-v" || a == "--verbose" -> {
                    headers = HeaderMode.VERBOSE
                }

                a == "-n" || a == "--lines" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("tail: option requires an argument -- 'n'\n")
                        return ParseResult(parsed = null, exitCode = 1)
                    }
                    mode = parseLinesArg(args[++i], ctx) ?: return ParseResult(parsed = null, exitCode = 1)
                }

                a.startsWith("--lines=") -> {
                    mode = parseLinesArg(a.substring("--lines=".length), ctx)
                        ?: return ParseResult(parsed = null, exitCode = 1)
                }

                a.startsWith("-n") && a.length > 2 -> {
                    mode = parseLinesArg(a.substring(2), ctx) ?: return ParseResult(parsed = null, exitCode = 1)
                }

                a == "-c" || a == "--bytes" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("tail: option requires an argument -- 'c'\n")
                        return ParseResult(parsed = null, exitCode = 1)
                    }
                    mode = parseBytesArg(args[++i], ctx) ?: return ParseResult(parsed = null, exitCode = 1)
                }

                a.startsWith("--bytes=") -> {
                    mode = parseBytesArg(a.substring("--bytes=".length), ctx)
                        ?: return ParseResult(parsed = null, exitCode = 1)
                }

                a.startsWith("-c") && a.length > 2 -> {
                    mode = parseBytesArg(a.substring(2), ctx) ?: return ParseResult(parsed = null, exitCode = 1)
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("tail: unrecognized option: $a\n")
                    return ParseResult(parsed = null, exitCode = 1)
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("tail: invalid option -- '${a.substring(1)}'\n")
                    return ParseResult(parsed = null, exitCode = 1)
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        return ParseResult(Parsed(mode = mode, files = files, headers = headers, follow = follow))
    }

    private suspend fun parseLinesArg(
        v: String,
        ctx: CommandContext,
    ): Mode? {
        if (v.isEmpty()) {
            ctx.stderr.writeUtf8("tail: invalid number of lines: ''\n")
            return null
        }
        return if (v.startsWith("+")) {
            val n = v.substring(1).toIntOrNull()
            if (n == null) {
                ctx.stderr.writeUtf8("tail: invalid number of lines: '$v'\n")
                null
            } else {
                Mode.FromLine(n)
            }
        } else {
            val n = v.toIntOrNull()
            if (n == null) {
                ctx.stderr.writeUtf8("tail: invalid number of lines: '$v'\n")
                null
            } else {
                Mode.LastLines(n)
            }
        }
    }

    private suspend fun parseBytesArg(
        v: String,
        ctx: CommandContext,
    ): Mode? {
        if (v.isEmpty()) {
            ctx.stderr.writeUtf8("tail: invalid number of bytes: ''\n")
            return null
        }
        return if (v.startsWith("+")) {
            val n = v.substring(1).toIntOrNull()
            if (n == null) {
                ctx.stderr.writeUtf8("tail: invalid number of bytes: '$v'\n")
                null
            } else {
                Mode.FromByte(n)
            }
        } else {
            val n = v.toIntOrNull()
            if (n == null) {
                ctx.stderr.writeUtf8("tail: invalid number of bytes: '$v'\n")
                null
            } else {
                Mode.LastBytes(n)
            }
        }
    }
}
