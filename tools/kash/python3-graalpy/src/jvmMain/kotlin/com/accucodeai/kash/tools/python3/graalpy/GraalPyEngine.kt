package com.accucodeai.kash.tools.python3.graalpy

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.FsLabel
import com.accucodeai.kash.fs.HostFs
import com.accucodeai.kash.fs.Mount
import com.accucodeai.kash.fs.MountedFileSystem
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.tools.python3.PythonEngine
import com.accucodeai.kash.tools.python3.PythonSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import java.io.OutputStream
import kotlin.time.Duration.Companion.milliseconds
import com.accucodeai.kash.api.sandbox.SandboxPolicy as KashSandboxPolicy
import org.graalvm.polyglot.SandboxPolicy as GraalSandboxPolicy

/**
 * GraalPy-backed [PythonEngine].
 *
 * Sandbox posture (intentional default-deny — every opt-in is named):
 * - `allowHostAccess(HostAccess.NONE)` — Python cannot reach Java classes,
 *   reflection, or polyglot bindings.
 * - `allowNativeAccess(false)` — no C extensions; CPython-compatible stdlib
 *   only. C extensions would punch a hole straight through every other limit.
 * - `allowCreateProcess(false)` — no `subprocess`, no `os.system`.
 * - `allowCreateThread(false)` — Python is single-threaded inside the context.
 * - `allowEnvironmentAccess(false)` — `os.environ` is empty; we pass kash's
 *   env explicitly via `os.environ.update(...)` after eval if needed.
 * - **IO scope:** Python sees a [KashPolyglotFileSystem] wrapping a per-
 *   invocation [MountedFileSystem] that composes the caller's `ctx.process.fs` at
 *   `/` (USER) with a host-backed [HostFs] at `/.cache/graalpy/<version>`
 *   (ENGINE_CACHE). The polyglot [IOAccess] is `.fileSystem(adapter).build()`
 *   with no `allowHostFileAccess` — so the only paths Python can reach are
 *   those visible through our adapter. `open('/etc/passwd')` returns
 *   NoSuchFile because `/etc` isn't in any mount.
 *
 *   GraalPy's own stdlib bootstrap goes through the same adapter, hitting
 *   the ENGINE_CACHE mount that backs onto real disk at
 *   `~/.kash/graalpy/<version>/`. The mount is labeled so a future snapshot
 *   layer can skip-and-regenerate it. We also pass `polyglot.engine.userResourceCache`
 *   pointing at the same host directory so any extraction GraalPy does
 *   directly via `java.nio.file` (bypassing the polyglot FS) lands in the
 *   same place.
 *
 * Timeout: a wall-clock budget enforced via coroutine cancellation.
 * `context.close(true)` on the cancellation path tears down the running
 * eval. Exit `124` on timeout matches GNU `timeout(1)`.
 *
 * Threading: each invocation gets its own `Context`. We do NOT reuse a
 * pre-warmed Context — that would allow program N+1 to observe N's globals.
 * Construction is cheap relative to the rest of an interpreter startup.
 */
public class GraalPyEngine : PythonEngine {
    override val name: String = "GraalPy"

    override suspend fun execute(
        source: PythonSource,
        scriptArgs: List<String>,
        fs: FileSystem,
        env: Map<String, String>,
        cwd: String,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
        timeoutMillis: Long,
        sandbox: KashSandboxPolicy,
        networkPolicy: com.accucodeai.kash.api.sandbox.NetworkPolicy,
    ): Int {
        // NetworkPolicy inside Python: enforced only at SandboxPolicy.UNTRUSTED,
        // where Truffle's sandbox validation denies host sockets at the
        // polyglot layer. Weaker sandbox tiers cannot enforce a policy
        // boundary against guest Python code that imports `socket` directly,
        // so rather than ship a softer "best effort" wrapper that misleads
        // callers, we refuse to start. See docs/SECURITY.md.
        if (networkPolicy !== com.accucodeai.kash.api.sandbox.NetworkPolicy.None &&
            sandbox != KashSandboxPolicy.UNTRUSTED
        ) {
            stderr.writeBytes(
                (
                    "python3: NetworkPolicy other than None requires sandbox=UNTRUSTED; " +
                        "got sandbox=$sandbox. See docs/SECURITY.md.\n"
                ).toByteArray(),
            )
            return 126
        }
        if (sandbox == KashSandboxPolicy.SAFE) {
            stderr.writeBytes(
                "python3: disabled under sandbox=SAFE (no host-FS access permitted)\n".toByteArray(),
            )
            return 126
        }
        val program: String =
            when (source) {
                is PythonSource.Code -> {
                    source.code
                }

                is PythonSource.Module -> {
                    "import runpy; runpy.run_module(${quote(source.module)}, run_name='__main__')"
                }

                is PythonSource.File -> {
                    val abs = Paths.resolve(cwd, source.path)
                    if (!fs.exists(abs)) {
                        stderr.writeBytes(
                            "python3: can't open file '${source.path}': No such file or directory\n".toByteArray(),
                        )
                        return 2
                    }
                    fs.source(abs).readUtf8Text()
                }

                PythonSource.Stdin -> {
                    stdin.readUtf8Text()
                }
            }

        val sysArgvScript: String =
            when (source) {
                is PythonSource.Code -> "-c"
                is PythonSource.Module -> "-m"
                is PythonSource.File -> source.path
                PythonSource.Stdin -> ""
            }

        // Adapt kash sinks to JDK OutputStream — GraalPy writes through these for
        // sys.stdout / sys.stderr. Per-write flush keeps streaming snappy and
        // makes broken-pipe surface promptly.
        val outAdapter = SinkOutputStream(stdout)
        val errAdapter = SinkOutputStream(stderr)

        // Pin Truffle's InternalResource cache to a kash-owned location BEFORE any
        // engine work so we know exactly which host prefix to expose as
        // passthrough. System property is the documented way; setting it once is
        // idempotent across invocations.
        val cacheHostDir = engineCacheHostPath()
        ensureUserResourceCacheConfigured(cacheHostDir)
        val cacheKashPath = CACHE_MOUNT_POINT
        val composedKashFs =
            if (fs is MountedFileSystem &&
                fs.mounts().any { it.label == FsLabel.ENGINE_CACHE && it.mountPoint == cacheKashPath }
            ) {
                fs
            } else {
                MountedFileSystem(
                    listOf(
                        Mount("/", fs, FsLabel.USER),
                        Mount(cacheKashPath, HostFs(cacheHostDir), FsLabel.ENGINE_CACHE),
                    ),
                )
            }
        // The polyglot FS routes:
        // - paths starting with the real host cache dir → host passthrough (so
        //   Truffle's stdlib extraction and reads work)
        // - everything else → kash FS (Python's user-level `open()` writes /
        //   reads land in kash's virtual filesystem)
        val polyglotFs =
            KashPolyglotFileSystem(
                fs = composedKashFs,
                initialCwd = cwd,
                hostPassthroughPrefixes = listOf(cacheHostDir),
            )

        return withContext(Dispatchers.IO) {
            val builder =
                Context
                    .newBuilder("python")
                    .allowHostAccess(hostAccessFor(sandbox))
                    .allowNativeAccess(false)
                    .allowCreateProcess(false)
                    .allowCreateThread(false)
                    .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                    .allowIO(IOAccess.newBuilder().fileSystem(polyglotFs).build())
                    .`in`(SuspendSourceInputStream(stdin))
                    .out(outAdapter)
                    .err(errAdapter)
                    .applyKashSandbox(sandbox)
                    .also {
                        // python.PosixModuleBackend = "java" is a TRUSTED-only
                        // option per Truffle's sandbox validation; leave it
                        // unset under stricter policies so GraalPy picks a
                        // policy-compatible default.
                        if (sandbox == KashSandboxPolicy.TRUSTED) {
                            it.option("python.PosixModuleBackend", "java")
                        }
                    }
            val ctx =
                try {
                    builder.build()
                } catch (e: IllegalArgumentException) {
                    if (sandbox == KashSandboxPolicy.UNTRUSTED && looksLikeUnsupportedSandbox(e)) {
                        errAdapter.write(
                            (
                                "python3: sandbox=UNTRUSTED requires Oracle GraalVM polyglot at runtime; " +
                                    "the current GraalVM Community runtime cannot enforce it. " +
                                    "Install Oracle GraalVM or use sandbox=CONSTRAINED.\n"
                            ).toByteArray(),
                        )
                        errAdapter.flush()
                        return@withContext 126
                    }
                    throw e
                }

            try {
                withTimeout(timeoutMillis.milliseconds) {
                    runProgram(ctx, program, sysArgvScript, scriptArgs)
                }
            } catch (e: TimeoutCancellationException) {
                // close(cancelIfExecuting=true) interrupts the eval thread.
                try {
                    ctx.close(true)
                } catch (_: Throwable) {
                    // ignore — context is going away anyway
                }
                errAdapter.write("python3: execution timed out after ${timeoutMillis}ms\n".toByteArray())
                124
            } catch (e: PolyglotException) {
                if (e.isExit) e.exitStatus else handleUncaught(e, errAdapter)
            } catch (e: Throwable) {
                // Surface initialization / unexpected errors instead of swallowing.
                errAdapter.write("python3: ${e::class.simpleName}: ${e.message}\n".toByteArray())
                errAdapter.flush()
                1
            } finally {
                try {
                    ctx.close(true)
                } catch (_: Throwable) {
                    // ignore — multiple close() calls are allowed but throw is harmless
                }
            }
        }
    }

    /**
     * Drive an interactive Python REPL hooked straight to the host terminal:
     * System.in for keystrokes, System.out for results, System.err for
     * tracebacks. Bypasses the kash interpreter's stdio buffering — without
     * that bypass, output would accumulate in a kotlinx.io.Buffer and only
     * surface when the REPL exited, defeating the point of a REPL.
     *
     * Reuses the same sandbox posture and polyglot FS adapter as [execute] so
     * file ops still land in kash's virtual filesystem and the GraalPy stdlib
     * cache still works.
     *
     * Blocks until the user exits (Ctrl-D / `exit()` / `sys.exit(...)`).
     * Returns the exit code: 0 on clean exit, the SystemExit code if the user
     * called `sys.exit(N)`, 1 on initialization failure.
     *
     * Caller responsibility: only invoke this from a context where the JVM
     * actually owns the terminal (e.g. `./gradlew runJvm --console=plain -q`).
     * In a non-interactive shell where stdin is a pipe or a heredoc body, use
     * [execute] with [PythonSource.Stdin] instead.
     */
    override suspend fun runInteractiveRepl(
        fs: FileSystem,
        cwd: String,
        env: Map<String, String>,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int = runInteractiveReplBlocking(fs, cwd, stdin, stdout, stderr)

    /**
     * Synchronous core of [runInteractiveRepl]. Bridges the calling tool's
     * SuspendSource/SuspendSink into the Java InputStream/OutputStream
     * surfaces Truffle's polyglot Context requires. Bytes flow through the
     * caller-provided streams (i.e. through the fd-0 OFD source that the
     * kash terminal byte pump owns), NOT through host System.in/out/err —
     * that's how the REPL avoids racing with the line editor for stdin
     * bytes and how shell redirections like `python3 > out.txt` still work.
     */
    public fun runInteractiveReplBlocking(
        fs: FileSystem,
        cwd: String,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int {
        val cacheHostDir = engineCacheHostPath()
        ensureUserResourceCacheConfigured(cacheHostDir)
        val composedKashFs =
            if (fs is MountedFileSystem &&
                fs.mounts().any { it.label == FsLabel.ENGINE_CACHE && it.mountPoint == CACHE_MOUNT_POINT }
            ) {
                fs
            } else {
                MountedFileSystem(
                    listOf(
                        Mount("/", fs, FsLabel.USER),
                        Mount(CACHE_MOUNT_POINT, HostFs(cacheHostDir), FsLabel.ENGINE_CACHE),
                    ),
                )
            }
        val polyglotFs =
            KashPolyglotFileSystem(
                fs = composedKashFs,
                initialCwd = cwd,
                hostPassthroughPrefixes = listOf(cacheHostDir),
            )

        // Tell GraalPy the standard streams are a TTY. Without this,
        // EmulatedPosixSupport.isatty() returns false for stdin/stdout/stderr
        // (it just reads `python.TerminalIsInteractive`), and Python's IO
        // stack picks block-buffered mode for sys.stdout. The visible symptom
        // is that `print('hello')` at the REPL prompt produces no output
        // until the buffer fills or the interpreter shuts down — because
        // `code.interact()`'s input() doesn't flush a non-tty stdout before
        // reading the next line. Flipping this option makes line-buffering
        // kick in, so print() flushes on its trailing `\n` and output appears
        // immediately. The option is marked EXPERT in GraalPy, hence
        // allowExperimentalOptions(true).
        val termCols =
            runCatching { System.getenv("COLUMNS")?.toInt() }.getOrNull() ?: 80
        val termRows =
            runCatching { System.getenv("LINES")?.toInt() }.getOrNull() ?: 24
        val ctx =
            Context
                .newBuilder("python")
                .allowHostAccess(HostAccess.NONE)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .allowCreateThread(false)
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                .allowIO(IOAccess.newBuilder().fileSystem(polyglotFs).build())
                .allowExperimentalOptions(true)
                .option("python.PosixModuleBackend", "java")
                .option("python.TerminalIsInteractive", "true")
                .option("python.TerminalWidth", termCols.toString())
                .option("python.TerminalHeight", termRows.toString())
                .`in`(SuspendSourceInputStream(stdin))
                .out(SuspendSinkOutputStream(stdout))
                .err(SuspendSinkOutputStream(stderr))
                .build()

        return try {
            // Drive Python's stdlib `code.interact()` — it owns the prompt
            // loop, multi-line continuation handling, and exception display,
            // all things we'd otherwise re-implement in Kotlin badly.
            //
            // sys.stdout.reconfigure(line_buffering=True, write_through=True)
            // forces flush-on-newline regardless of what Python's IO stack
            // picked when sys.stdout was constructed — belt-and-suspenders
            // alongside python.TerminalIsInteractive. write_through bypasses
            // the TextIOWrapper buffer entirely, so each Python-side write
            // surfaces immediately through our SuspendSinkOutputStream.
            val replSrc =
                Source
                    .newBuilder(
                        "python",
                        "import code, sys\n" +
                            "try:\n" +
                            "    sys.stdout.reconfigure(line_buffering=True, write_through=True)\n" +
                            "    sys.stderr.reconfigure(line_buffering=True, write_through=True)\n" +
                            "except Exception:\n" +
                            "    pass\n" +
                            "sys.ps1 = '>>> '\n" +
                            "sys.ps2 = '... '\n" +
                            "code.interact(banner='Python REPL via GraalPy (Ctrl-D to exit, back to kash)', exitmsg='')\n",
                        "<kash-python-repl>",
                    ).build()
            ctx.eval(replSrc)
            0
        } catch (e: PolyglotException) {
            if (e.isExit) e.exitStatus else 1
        } catch (e: Throwable) {
            System.err.println("python3: ${e::class.simpleName}: ${e.message}")
            1
        } finally {
            try {
                ctx.close(true)
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun runProgram(
        ctx: Context,
        program: String,
        sysArgvScript: String,
        scriptArgs: List<String>,
    ): Int {
        // Set sys.argv before user code runs. GraalPy exposes sys via the
        // python language bindings; the simplest way is to splice a prelude.
        val prelude = buildSysArgvPrelude(sysArgvScript, scriptArgs)
        val full = prelude + "\n" + program
        val src =
            Source
                .newBuilder("python", full, "<kash>")
                .build()
        ctx.eval(src)
        return 0
    }

    private fun buildSysArgvPrelude(
        scriptName: String,
        args: List<String>,
    ): String {
        val parts = mutableListOf<String>()
        parts += quote(scriptName)
        args.forEach { parts += quote(it) }
        return "import sys\nsys.argv = [${parts.joinToString(", ")}]"
    }

    private fun handleUncaught(
        e: PolyglotException,
        err: OutputStream,
    ): Int {
        // GraalPy normally streams the traceback through sys.stderr (our
        // errAdapter) before raising. If we got here without one written, fall
        // back to the polyglot message — better than swallowing.
        val msg = e.message
        if (msg != null && !msg.isBlank()) {
            err.write((msg + "\n").toByteArray())
            err.flush()
        }
        return 1
    }

    /**
     * Real-disk path where Truffle's InternalResource cache lives. We pin it to
     * a kash-owned location so we know which host prefix to expose as
     * passthrough through [KashPolyglotFileSystem]. Truffle extracts GraalPy's
     * stdlib here on first run, then reads through the polyglot FS on every
     * subsequent open.
     */
    private fun engineCacheHostPath(): String {
        val home = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir") ?: "/tmp"
        val dir = "$home/.kash/graalpy"
        // HostFs.init will create the directory if missing.
        return dir
    }

    /**
     * Set `polyglot.engine.userResourceCache` exactly once per JVM. Done via
     * system property because Truffle reads it eagerly during engine init and
     * the per-builder option isn't exposed on `Context.Builder`. If the
     * property is already set (e.g. by another tool or the user), respect it.
     */
    private fun ensureUserResourceCacheConfigured(dir: String) {
        val key = "polyglot.engine.userResourceCache"
        val existing = System.getProperty(key)
        if (existing == null) {
            java.io.File(dir).mkdirs()
            System.setProperty(key, dir)
        }
    }

    public companion object {
        /**
         * Virtual mount point under which GraalPy's stdlib cache appears in
         * kash's [com.accucodeai.kash.fs.FileSystem]. Snapshot/restore should
         * skip mounts at this path with label
         * [com.accucodeai.kash.fs.FsLabel.ENGINE_CACHE].
         */
        public const val CACHE_MOUNT_POINT: String = "/.cache/graalpy"

        // Python-safe string literal: backslash-escape backslashes and double
        // quotes, then wrap. Sufficient for argv strings — they are not
        // expected to contain control characters that need \n / \t encoding.
        internal fun quote(s: String): String =
            "\"" +
                s
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"") +
                "\""
    }
}

/**
 * Apply a kash [KashSandboxPolicy] to this [Context.Builder]. For TRUSTED
 * we make no Truffle-level declaration — the existing `allowHostAccess
 * (NONE)` / no-native / no-proc setup is already strict, and adding
 * `.sandbox(TRUSTED)` is a no-op. CONSTRAINED and UNTRUSTED call through
 * to Truffle's [GraalSandboxPolicy] so policy-conflict misconfigurations
 * surface at build time.
 */
private fun Context.Builder.applyKashSandbox(p: KashSandboxPolicy): Context.Builder =
    when (p) {
        KashSandboxPolicy.TRUSTED -> this
        KashSandboxPolicy.CONSTRAINED -> this.sandbox(GraalSandboxPolicy.CONSTRAINED)
        KashSandboxPolicy.UNTRUSTED -> this.sandbox(GraalSandboxPolicy.UNTRUSTED)
        KashSandboxPolicy.SAFE -> error("SAFE policy must short-circuit before building a polyglot context")
    }

/**
 * The Truffle-level [HostAccess] compatible with each [KashSandboxPolicy].
 * GraalVM's [HostAccess.NONE] is a "no host method calls" config that
 * nevertheless permits some mutable target type mappings — CONSTRAINED and
 * UNTRUSTED reject those, so they need the matching presets that have
 * mutable mappings disabled.
 */
private fun hostAccessFor(p: KashSandboxPolicy): HostAccess =
    when (p) {
        KashSandboxPolicy.TRUSTED -> HostAccess.NONE
        KashSandboxPolicy.CONSTRAINED -> HostAccess.CONSTRAINED
        KashSandboxPolicy.UNTRUSTED -> HostAccess.UNTRUSTED
        KashSandboxPolicy.SAFE -> error("SAFE policy must short-circuit before building a polyglot context")
    }

/**
 * Whether [t] is the polyglot library's "this sandbox policy isn't supported
 * by the current runtime" signal. Used to distinguish missing-Oracle-GraalVM
 * (a session-config error we can recover from with exit 126) from misconfig
 * of our own builder (a programming error we should let propagate).
 */
internal fun looksLikeUnsupportedSandbox(t: Throwable): Boolean {
    val msg = t.message ?: return false
    // Polyglot emits messages like "The validation for the given sandbox
    // policy UNTRUSTED failed" and "isolated polyglot sandbox is required"
    // depending on which knob is missing. Both indicate the runtime can't
    // enforce UNTRUSTED.
    return ("sandbox" in msg.lowercase()) &&
        ("untrusted" in msg.lowercase() || "isolated" in msg.lowercase() || "policy" in msg.lowercase())
}

/** Adapt a kash [SuspendSink] to [OutputStream] for GraalPy's `Context.out`/`err`.
 *
 * External boundary: GraalPy's polyglot library exposes a synchronous
 * [OutputStream] API. We `runBlocking` here to bridge — the alternative is
 * not supporting GraalPy. Reads/writes from python land are infrequent and
 * line-buffered, so the cost is bounded. */
private class SinkOutputStream(
    private val sink: SuspendSink,
) : OutputStream() {
    private val staging = kotlinx.io.Buffer()

    override fun write(b: Int) {
        staging.writeByte(b.toByte())
        flushIfReady()
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        staging.write(b, off, off + len)
        flushIfReady()
    }

    override fun flush() {
        if (staging.size > 0) {
            kotlinx.coroutines.runBlocking {
                sink.write(staging, staging.size)
                sink.flush()
            }
        }
    }

    override fun close() {
        flush()
    }

    private fun flushIfReady() {
        // GraalPy writes are line-ish; flush each call to keep streaming.
        flush()
    }
}
