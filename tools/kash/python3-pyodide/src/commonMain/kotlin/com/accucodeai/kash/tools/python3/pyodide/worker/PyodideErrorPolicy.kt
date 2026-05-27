package com.accucodeai.kash.tools.python3.pyodide.worker

/**
 * Pure policy for turning a Pyodide run's `errorMessage` into the text (if
 * any) that belongs on kash's stderr. Kept in commonMain so JVM tests can
 * pin the behavior without a browser worker — same rationale as
 * [StdinRingMath].
 *
 * Why this exists: `pyodide.runPythonAsync` *raises* an uncaught Python
 * exception to the JS catch rather than echoing the traceback through the
 * `setStderr` callback. The worker captures that into `run-result.errorMessage`
 * — and if the main thread doesn't write it out, the traceback vanishes and
 * the user sees a bare non-zero exit with no diagnostics. Routing both the
 * worker path and the in-process fallback through here closes that gap and
 * keeps the suppression rules in one place.
 */
public object PyodideErrorPolicy {
    private const val SYSTEM_EXIT_MARKER = "SystemExit: "

    /**
     * Pyodide formats a `SystemExit` traceback as `SystemExit: N`. Parse the
     * integer so an explicit `sys.exit(N)` maps to kash exit code N instead of
     * the generic uncaught-exception code 1. Returns null when [msg] is null or
     * is not a `SystemExit` carrying an integer code (e.g. a `ValueError`, or
     * `sys.exit("some string")`).
     */
    public fun extractSystemExitCode(msg: String?): Int? {
        if (msg == null) return null
        val i = msg.lastIndexOf(SYSTEM_EXIT_MARKER)
        if (i < 0) return null
        val rest = msg.substring(i + SYSTEM_EXIT_MARKER.length).trimEnd()
        return rest.toIntOrNull()
    }

    /**
     * The stderr text to emit for a run's [errorMessage], or null to emit
     * nothing. Mirrors CPython's behavior:
     *  - no error / blank message → null
     *  - a clean `sys.exit(N)` (integer N) → null; CPython prints nothing and
     *    just exits N, so we mustn't dump a `SystemExit: N` line as noise
     *  - anything else (real tracebacks, `sys.exit("msg")`) → the message with
     *    a single trailing newline
     */
    public fun stderrTextForError(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return null
        if (extractSystemExitCode(errorMessage) != null) return null
        return errorMessage.trimEnd() + "\n"
    }
}
