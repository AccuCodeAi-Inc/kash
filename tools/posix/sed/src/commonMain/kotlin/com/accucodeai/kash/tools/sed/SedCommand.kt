package com.accucodeai.kash.tools.sed

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.ShellInvocation
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.readUtf8DelimitedOrNull
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths
import kotlinx.io.Buffer
import kotlinx.io.readString

/**
 * `sed` as a kash [Command]. Implements the high-leverage POSIX/GNU subset —
 * see [SedScript] for the supported syntax (substitution, addresses with
 * ranges, `d`/`p`/`n`/`N`/`=`/`q`/`a`/`i`/`c`/`y`/`r`/`w`/`l`, hold-space,
 * labels with `b`/`t`/`T`, `{ ... }` blocks).
 *
 * CLI: `-n`, `-e SCRIPT` (repeatable), `-f SCRIPT_FILE` (repeatable), `-i`
 * (in-place; suffix is parsed but no backup is written), `-s` (treat each
 * input file as a separate stream — line numbers, hold, and range state
 * reset between files), `-z` (records terminated by NUL instead of newline),
 * `-E`/`-r` (no-op — regex engine already speaks ERE-like), `--`, plus a
 * positional script before file arguments. `q [N]` / `Q [N]` propagate the
 * exit code.
 */
public class SedCommand :
    Command,
    CommandSpec {
    override val name: String = "sed"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var suppressDefault = false
        var inPlace = false
        var inPlaceBackupSuffix: String? = null
        var separate = false
        var nulMode = false
        var ereMode = false
        val scripts = mutableListOf<String>()
        val files = mutableListOf<String>()
        var positionalScript: String? = null

        var i = 0
        var doubleDash = false
        while (i < args.size) {
            val a = args[i]
            if (doubleDash) {
                if (positionalScript == null && scripts.isEmpty()) {
                    positionalScript = a
                } else {
                    files.add(a)
                }
                i++
                continue
            }
            when {
                a == "--" -> {
                    doubleDash = true
                    i++
                }

                a == "-n" || a == "--quiet" || a == "--silent" -> {
                    suppressDefault = true
                    i++
                }

                a == "-s" || a == "--separate" -> {
                    separate = true
                    i++
                }

                a == "-z" || a == "--null-data" -> {
                    nulMode = true
                    i++
                }

                a == "-e" || a == "--expression" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("sed: option requires an argument: -e\n")
                        return CommandResult(exitCode = 2)
                    }
                    scripts.add(args[i + 1])
                    i += 2
                }

                a.startsWith("-e") && a.length > 2 -> {
                    scripts.add(a.substring(2))
                    i++
                }

                a == "-i" -> {
                    inPlace = true
                    i++
                }

                a.startsWith("-i") && a.length > 2 -> {
                    // GNU `-iSUFFIX`. If a suffix is given (non-empty), we
                    // copy the original to `<file><suffix>` before overwriting.
                    inPlace = true
                    inPlaceBackupSuffix = a.substring(2)
                    i++
                }

                a == "-f" || a == "--file" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("sed: option requires an argument: -f\n")
                        return CommandResult(exitCode = 2)
                    }
                    val path = args[i + 1]
                    val abs = Paths.resolve(ctx.process.cwd, path)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("sed: $path: No such file or directory\n")
                        return CommandResult(exitCode = 2)
                    }
                    scripts.add(
                        ctx.process.fs
                            .readBytes(abs)
                            .decodeToString(),
                    )
                    i += 2
                }

                a.startsWith("-f") && a.length > 2 -> {
                    val path = a.substring(2)
                    val abs = Paths.resolve(ctx.process.cwd, path)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("sed: $path: No such file or directory\n")
                        return CommandResult(exitCode = 2)
                    }
                    scripts.add(
                        ctx.process.fs
                            .readBytes(abs)
                            .decodeToString(),
                    )
                    i++
                }

                a == "-E" || a == "-r" || a == "--regexp-extended" -> {
                    ereMode = true
                    i++
                }

                a.startsWith("-") && a.length > 1 && a != "-" -> {
                    ctx.stderr.writeUtf8("sed: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    if (positionalScript == null && scripts.isEmpty()) {
                        positionalScript = a
                    } else {
                        files.add(a)
                    }
                    i++
                }
            }
        }

        if (positionalScript != null) scripts.add(0, positionalScript)
        if (scripts.isEmpty()) {
            ctx.stderr.writeUtf8("sed: no script given\n")
            return CommandResult(exitCode = 2)
        }

        val script =
            try {
                SedScriptParser.parse(scripts.joinToString("\n"), breMode = !ereMode)
            } catch (e: SedScriptError) {
                ctx.stderr.writeUtf8("sed: ${e.message}\n")
                return CommandResult(exitCode = 1)
            }

        val recordDelim: Byte = if (nulMode) 0.toByte() else '\n'.code.toByte()
        val emitToStdout: suspend (String) -> Unit = { s ->
            ctx.stdout.writeUtf8(s)
            ctx.stdout.writeBytes(byteArrayOf(recordDelim))
        }

        // Stdin → stdout.
        if (files.isEmpty()) {
            val io = makeFsIo(ctx, recordDelim)
            val engine = SedEngine(script, suppressDefault, io)
            val drained = mutableListOf<String>()
            while (true) {
                val line = ctx.stdin.readUtf8DelimitedOrNull(recordDelim) ?: break
                drained += line
            }
            val lines = drained.iterator()
            val result =
                try {
                    engine.run(lines, emitToStdout)
                } catch (e: SedScriptError) {
                    ctx.stderr.writeUtf8("sed: ${e.message}\n")
                    return CommandResult(exitCode = 1)
                } finally {
                    io.close()
                }
            return CommandResult(exitCode = result.exitCode)
        }

        // In-place edit. Each file is its own stream (regardless of -s).
        if (inPlace) {
            var lastExit = 0
            for (file in files) {
                val fileAbs = Paths.resolve(ctx.process.cwd, file)
                if (!ctx.process.fs.exists(fileAbs)) {
                    ctx.stderr.writeUtf8("sed: $file: No such file or directory\n")
                    return CommandResult(exitCode = 2)
                }
                val originalBytes = ctx.process.fs.readBytes(fileAbs)
                inPlaceBackupSuffix?.takeIf { it.isNotEmpty() }?.let { suffix ->
                    ctx.process.fs.writeBytes(fileAbs + suffix, originalBytes)
                }
                val original = decode(originalBytes)
                val out = StringBuilder()
                val records = splitRecords(original, recordDelim.toInt().toChar()).iterator()
                val io = makeFsIo(ctx, recordDelim)
                val engine = SedEngine(script, suppressDefault, io)
                val result =
                    try {
                        engine.run(records) {
                            out.append(it)
                            out.append(recordDelim.toInt().toChar())
                        }
                    } finally {
                        io.close()
                    }
                lastExit = result.exitCode
                val finalText =
                    if (!original.endsWith(recordDelim.toInt().toChar()) && out.isNotEmpty()) {
                        out.deleteAt(out.length - 1)
                        out.toString()
                    } else {
                        out.toString()
                    }
                ctx.process.fs.writeBytes(fileAbs, encode(finalText))
            }
            return CommandResult(exitCode = lastExit)
        }

        // Stdout mode, multiple files. With -s, each file is its own stream;
        // otherwise concatenate (POSIX default).
        if (separate) {
            var lastExit = 0
            for (file in files) {
                val fileAbs = Paths.resolve(ctx.process.cwd, file)
                if (!ctx.process.fs.exists(fileAbs)) {
                    ctx.stderr.writeUtf8("sed: $file: No such file or directory\n")
                    return CommandResult(exitCode = 2)
                }
                val records =
                    splitRecords(
                        decode(ctx.process.fs.readBytes(fileAbs)),
                        recordDelim.toInt().toChar(),
                    ).iterator()
                val io = makeFsIo(ctx, recordDelim)
                val engine = SedEngine(script, suppressDefault, io)
                val result =
                    try {
                        engine.run(records, emitToStdout)
                    } finally {
                        io.close()
                    }
                lastExit = result.exitCode
            }
            return CommandResult(exitCode = lastExit)
        }

        val allRecords = mutableListOf<String>()
        for (file in files) {
            val fileAbs = Paths.resolve(ctx.process.cwd, file)
            if (!ctx.process.fs.exists(fileAbs)) {
                ctx.stderr.writeUtf8("sed: $file: No such file or directory\n")
                return CommandResult(exitCode = 2)
            }
            allRecords.addAll(splitRecords(decode(ctx.process.fs.readBytes(fileAbs)), recordDelim.toInt().toChar()))
        }
        val io = makeFsIo(ctx, recordDelim)
        val engine = SedEngine(script, suppressDefault, io)
        val result =
            try {
                engine.run(allRecords.iterator(), emitToStdout)
            } finally {
                io.close()
            }
        return CommandResult(exitCode = result.exitCode)
    }

    /**
     * Build a [SedIo] that reads via [fs] for `r` and writes via [fs] for `w`.
     * Write sinks are opened lazily (truncate on first use) and cached for the
     * life of the IO bridge, so a single run that writes many records to the
     * same `w` target opens one sink, not one per cycle.
     */
    private suspend fun makeFsIo(
        ctx: CommandContext,
        recordDelim: Byte,
    ): SedIo =
        object : SedIo {
            private val sinks = mutableMapOf<String, SuspendSink>()

            override suspend fun readForR(path: String): String? {
                val abs = Paths.resolve(ctx.process.cwd, path)
                return if (ctx.process.fs.exists(abs)) {
                    ctx.process.fs
                        .readBytes(abs)
                        .decodeToString()
                } else {
                    null
                }
            }

            override suspend fun writeForW(
                path: String,
                line: String,
            ) {
                val abs = Paths.resolve(ctx.process.cwd, path)
                val s = sinks.getOrPut(abs) { ctx.process.fs.sink(abs, append = false) }
                s.writeUtf8(line)
                s.writeBytes(byteArrayOf(recordDelim))
            }

            override suspend fun execForS(commandLine: String): String? {
                val runner = ctx.shellRunner ?: return null
                val buf = Buffer()
                runner.run(
                    ShellInvocation(
                        script = commandLine,
                        stdout = buf.asSuspendSink(),
                        // stderr = null passes through to ctx.stderr per ShellRunner contract
                    ),
                )
                val text = buf.readString()
                return if (text.endsWith("\n")) text.substring(0, text.length - 1) else text
            }

            override fun close() {
                for (s in sinks.values) {
                    runCatching { s.close() }
                }
                sinks.clear()
            }
        }

    private fun splitRecords(
        text: String,
        delim: Char,
    ): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c == delim) {
                out.add(sb.toString())
                sb.clear()
            } else {
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun decode(bytes: ByteArray): String = bytes.decodeToString()

    private fun encode(s: String): ByteArray = s.encodeToByteArray()
}
