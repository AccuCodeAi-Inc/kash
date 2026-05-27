package com.accucodeai.kash.tools.awk

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.ShellInvocation
import com.accucodeai.kash.api.io.AsyncPipe
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * `awk` command.
 *
 * Argument grammar (POSIX subset, Slice 1):
 *
 *     awk [-F sep] [-v var=val]... ('program' | -f file) [file…]
 *
 *  - `-F sep` sets the initial value of `FS`.
 *  - `-v var=val` may repeat; assigns to `var` before `BEGIN` runs.
 *  - The program source is either the first positional arg, or read from
 *    a file via `-f file`.
 *  - File args after the program are concatenated as input. A bare `-`
 *    is read from stdin. With no files, stdin is the input.
 *  - `-f file` reads the program source from a file. Repeating `-f` (or
 *    mixing with `-e`-style positional source) concatenates the scripts
 *    with a newline separator.
 *  - Multi-file input gives per-file `FNR` / `FILENAME` semantics, and
 *    `nextfile` skips to the next file.
 *  - `print > "f"`, `print >> "f"`, and `getline … < "f"` are wired
 *    through `ctx.process.fs`. `print | "cmd"` and `cmd | getline`
 *    still need shell process-spawn — see STATUS.md.
 */
public class AwkCommand :
    Command,
    CommandSpec {
    override val name: String = "awk"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var fs: String? = null
        val pre = LinkedHashMap<String, String>()
        val sourceFiles = mutableListOf<String>()
        var programSource: String? = null
        val inputFiles = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-F" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("awk: -F requires an argument\n")
                        return CommandResult(exitCode = 2)
                    }
                    fs = args[i + 1]
                    i += 2
                }

                a.startsWith("-F") && a.length > 2 -> {
                    fs = a.substring(2)
                    i++
                }

                a == "-v" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("awk: -v requires an argument\n")
                        return CommandResult(exitCode = 2)
                    }
                    parseAssignment(args[i + 1], pre, ctx)?.let { return it }
                    i += 2
                }

                a.startsWith("-v") && a.length > 2 -> {
                    parseAssignment(a.substring(2), pre, ctx)?.let { return it }
                    i++
                }

                a == "-f" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("awk: -f requires an argument\n")
                        return CommandResult(exitCode = 2)
                    }
                    sourceFiles += args[i + 1]
                    i += 2
                }

                a.startsWith("-f") && a.length > 2 -> {
                    sourceFiles += a.substring(2)
                    i++
                }

                a == "--" -> {
                    // Positional args follow. The next one is the program
                    // source if we don't have one yet, otherwise inputs.
                    i++
                    while (i < args.size) {
                        if (programSource == null && sourceFiles.isEmpty()) {
                            programSource = args[i]
                        } else {
                            inputFiles += args[i]
                        }
                        i++
                    }
                }

                a == "-" -> {
                    // Bare `-` is always an input file (stdin), never a program.
                    inputFiles += a
                    i++
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("awk: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                programSource == null && sourceFiles.isEmpty() -> {
                    programSource = a
                    i++
                }

                else -> {
                    inputFiles += a
                    i++
                }
            }
        }

        val sourceParts = mutableListOf<String>()
        if (programSource != null) sourceParts += programSource
        for (path in sourceFiles) {
            val abs = Paths.resolve(ctx.process.cwd, path)
            if (!ctx.process.fs.exists(abs)) {
                ctx.stderr.writeUtf8("awk: $path: No such file or directory\n")
                return CommandResult(exitCode = 2)
            }
            sourceParts +=
                ctx.process.fs
                    .readBytes(abs)
                    .decodeToString()
        }
        if (sourceParts.isEmpty()) {
            ctx.stderr.writeUtf8("awk: usage: awk [-F sep] [-v var=val]... ('program' | -f file) [file…]\n")
            return CommandResult(exitCode = 2)
        }
        val source = sourceParts.joinToString("\n")

        val program =
            try {
                Awk.compile(source)
            } catch (e: AwkParseError) {
                ctx.stderr.writeUtf8("awk: ${e.message}\n")
                return CommandResult(exitCode = 2)
            }

        val opts =
            AwkOptions(
                fieldSeparator = fs,
                preAssignments = pre,
                environ = ctx.process.env.toMap(),
            )
        // Build per-file inputs so the engine sees one AwkInputFile per
        // argument — that's what gives `FNR` / `FILENAME` correct
        // per-file semantics. With no file args, we read stdin once and
        // present it as a single anonymous file (empty FILENAME).
        val effectiveFiles = if (inputFiles.isEmpty()) listOf("") else inputFiles
        val awkFiles = mutableListOf<AwkInputFile>()
        for (file in effectiveFiles) {
            val name: String
            val text: String
            when (file) {
                "" -> {
                    name = ""
                    text = ctx.stdin.readUtf8Text()
                }

                "-" -> {
                    name = "-"
                    text = ctx.stdin.readUtf8Text()
                }

                else -> {
                    name = file
                    val abs = Paths.resolve(ctx.process.cwd, file)
                    text =
                        try {
                            ctx.process.fs
                                .readBytes(abs)
                                .decodeToString()
                        } catch (_: FileNotFound) {
                            ctx.stderr.writeUtf8("awk: $file: No such file or directory\n")
                            return CommandResult(exitCode = 2)
                        }
                }
            }
            val records =
                if (text.isEmpty()) {
                    emptySequence()
                } else {
                    text.removeSuffix("\n").splitToSequence('\n')
                }
            awkFiles += AwkInputFile(name, records)
        }

        // Suspending openers drive real I/O directly: getline < file goes
        // through ctx.process.fs.source; print > / >> file goes through
        // ctx.process.fs.sink; print | cmd and cmd | getline spawn the
        // command via ctx.shellRunner with an AsyncPipe carrying records
        // either direction. The engine calls close() on each writer/reader
        // at end-of-run (or when awk's close() builtin fires) — that's
        // where we join the spawned child jobs.
        val runner = ctx.shellRunner
        return coroutineScope {
            val pipeJobs = mutableListOf<Job>()

            val fileOpener: suspend (String) -> AwkLineReader? = { path ->
                val abs = Paths.resolve(ctx.process.cwd, path)
                if (!ctx.process.fs.exists(abs)) {
                    null
                } else {
                    val src = ctx.process.fs.source(abs)
                    object : AwkLineReader {
                        override suspend fun readLine(): String? = src.readUtf8LineOrNull()

                        override suspend fun close() {
                            src.close()
                        }
                    }
                }
            }

            val outputOpener: suspend (String, AwkOutputMode) -> AwkOutputWriter? = { path, mode ->
                when (mode) {
                    AwkOutputMode.Truncate, AwkOutputMode.Append -> {
                        val abs = Paths.resolve(ctx.process.cwd, path)
                        val sink = ctx.process.fs.sink(abs, append = (mode == AwkOutputMode.Append))
                        fileSinkWriter(sink)
                    }

                    AwkOutputMode.Pipe -> {
                        if (runner == null) {
                            null
                        } else {
                            val pipe = AsyncPipe()
                            val job =
                                launch {
                                    runner.run(
                                        ShellInvocation(
                                            script = path,
                                            stdout = ctx.stdout,
                                            stdin = pipe.source,
                                        ),
                                    )
                                }
                            pipeJobs += job
                            object : AwkOutputWriter {
                                override suspend fun write(text: String) {
                                    pipe.sink.writeUtf8(text)
                                }

                                override suspend fun close() {
                                    pipe.sink.close()
                                    job.join()
                                }
                            }
                        }
                    }
                }
            }

            val cmdOpener: suspend (String) -> AwkLineReader? = { cmd ->
                if (runner == null) {
                    null
                } else {
                    val pipe = AsyncPipe()
                    val job =
                        launch {
                            runner.run(
                                ShellInvocation(
                                    script = cmd,
                                    stdout = pipe.sink,
                                ),
                            )
                            pipe.sink.close()
                        }
                    pipeJobs += job
                    object : AwkLineReader {
                        override suspend fun readLine(): String? = pipe.source.readUtf8LineOrNull()

                        override suspend fun close() {
                            pipe.source.close()
                            job.join()
                        }
                    }
                }
            }

            val systemHook: suspend (String) -> Int = { cmd ->
                if (runner == null) {
                    ctx.stderr.writeUtf8("awk: system(): no shell runner available\n")
                    1
                } else {
                    runner.run(ShellInvocation(script = cmd, stdout = ctx.stdout))
                }
            }

            try {
                program
                    .runFiles(
                        awkFiles.asSequence(),
                        opts,
                        fileOpener = fileOpener,
                        outputOpener = outputOpener,
                        cmdOpener = cmdOpener,
                        systemHook = systemHook,
                    ).collect { line ->
                        ctx.stdout.writeUtf8(line)
                    }
            } catch (e: AwkRuntimeError) {
                ctx.stderr.writeUtf8("awk: ${e.message}\n")
                return@coroutineScope CommandResult(exitCode = 2)
            }
            // Pipe-writer close() already joined those jobs; any cmd|getline
            // jobs not explicitly closed by the engine are joined here.
            pipeJobs.forEach { it.join() }
            CommandResult()
        }
    }

    /** Wrap a SuspendSink as an AwkOutputWriter — used by `print > / >> file`. */
    private fun fileSinkWriter(sink: SuspendSink): AwkOutputWriter =
        object : AwkOutputWriter {
            override suspend fun write(text: String) {
                sink.writeUtf8(text)
            }

            override suspend fun close() {
                sink.close()
            }
        }

    private suspend fun parseAssignment(
        arg: String,
        out: MutableMap<String, String>,
        ctx: CommandContext,
    ): CommandResult? {
        val eq = arg.indexOf('=')
        if (eq <= 0) {
            ctx.stderr.writeUtf8("awk: bad -v assignment: $arg\n")
            return CommandResult(exitCode = 2)
        }
        out[arg.substring(0, eq)] = arg.substring(eq + 1)
        return null
    }
}
