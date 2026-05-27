package com.accucodeai.kash.tools.awk

import com.accucodeai.kash.tools.awk.ast.AwkProgram
import com.accucodeai.kash.tools.awk.eval.AwkInterpreter
import com.accucodeai.kash.tools.awk.parser.parseAwkProgram
import kotlinx.coroutines.flow.Flow

/**
 * Line-by-line reader for `getline < file` or `cmd | getline`. The awk
 * engine itself doesn't touch the filesystem or spawn processes —
 * callers (the `awk` shell command, or test harnesses) supply one of
 * these per opened path. `readLine` returns null at EOF; the engine
 * takes care of `close()` at end of run.
 */
public interface AwkLineReader {
    public suspend fun readLine(): String?

    public suspend fun close() {
        // default: nothing to release
    }
}

/**
 * One named input file (or stdin) for the multi-file `run` overload.
 * The empty string is the conventional name for stdin / no-file-args
 * input — bwk awk reports `FILENAME` as empty in that case.
 */
public class AwkInputFile(
    public val name: String,
    public val records: Sequence<String>,
)

/** Mode hint passed to the output opener. */
public enum class AwkOutputMode {
    /** `print > "file"` — open for writing, truncate on first open. */
    Truncate,

    /** `print >> "file"` — open for writing, append. */
    Append,

    /** `print | "cmd"` — pipe stdout to a spawned command. */
    Pipe,
}

/**
 * Sink for a `print` / `printf` redirection. The engine keeps the
 * writer open across statements that target the same destination, and
 * calls [close] when the awk run finishes (or when the script calls
 * `close(target)` explicitly).
 */
public interface AwkOutputWriter {
    public suspend fun write(text: String)

    public suspend fun close() {
        // default: nothing to release
    }
}

/**
 * Public entry point for the awk engine.
 *
 * Shape mirrors `Jq` deliberately: `Awk.compile(source)` returns an
 * [AwkProgramHandle] which can then be run over a record stream.
 */
public object Awk {
    /** Parse an awk source program. Throws [AwkParseError] on syntax errors. */
    public fun compile(source: String): AwkProgramHandle = AwkProgramHandle(parseAwkProgram(source))
}

/**
 * Compiled awk program. [run] returns a [Flow] of output lines (each
 * already terminated by the active `ORS`); callers stream those onward
 * line-by-line. The flow is suspending end-to-end — `getline < file`,
 * `print > file`, `print | cmd`, `cmd | getline`, and `system()` can
 * all drive real I/O through suspending openers without `runBlocking`.
 */
public class AwkProgramHandle internal constructor(
    internal val ast: AwkProgram,
) {
    /**
     * Single-stream entry point. Records share one anonymous "file"
     * (empty FILENAME, FNR == NR). For multi-file semantics with
     * per-file FNR reset and FILENAME tracking, use the [AwkInputFile]
     * overload below.
     */
    public fun run(
        input: Sequence<String>,
        opts: AwkOptions = AwkOptions(),
        fileOpener: (suspend (String) -> AwkLineReader?)? = null,
        outputOpener: (suspend (String, AwkOutputMode) -> AwkOutputWriter?)? = null,
        cmdOpener: (suspend (String) -> AwkLineReader?)? = null,
        systemHook: (suspend (String) -> Int)? = null,
    ): Flow<String> =
        runFiles(sequenceOf(AwkInputFile("", input)), opts, fileOpener, outputOpener, cmdOpener, systemHook)

    /**
     * Multi-file entry point. Each [AwkInputFile] is a named record
     * stream; FNR resets and FILENAME updates at each file boundary,
     * matching POSIX awk semantics for `awk … f1 f2 f3`. Bare `getline`
     * (no source) transparently crosses file boundaries when the
     * current file is exhausted. `nextfile` skips the remainder of the
     * current file and resumes at the next.
     */
    public fun runFiles(
        files: Sequence<AwkInputFile>,
        opts: AwkOptions = AwkOptions(),
        fileOpener: (suspend (String) -> AwkLineReader?)? = null,
        outputOpener: (suspend (String, AwkOutputMode) -> AwkOutputWriter?)? = null,
        cmdOpener: (suspend (String) -> AwkLineReader?)? = null,
        systemHook: (suspend (String) -> Int)? = null,
    ): Flow<String> {
        val interp =
            AwkInterpreter(
                ast,
                opts,
                fileOpener = fileOpener,
                outputOpener = outputOpener,
                cmdOpener = cmdOpener,
                systemHook = systemHook,
            )
        return interp.runFiles(files)
    }
}
