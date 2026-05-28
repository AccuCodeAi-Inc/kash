package com.accucodeai.kash

public data class ExecOptions(
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val stdin: String = "",
    /** Start with empty env instead of merging with the shell's env. */
    val replaceEnv: Boolean = false,
    /** Route stderr writes into stdout so they appear interleaved, in write
     *  order — like running the script with `2>&1` from a shell. */
    val mergeStderr: Boolean = false,
    /** Script name used for `$0` and the `<scriptname>: line <N>:` prefix
     *  on diagnostics. Defaults to `kash`. Conformance runners that invoke
     *  a real file pass the path here so bash-style error output matches. */
    val scriptName: String = "kash",
    /**
     * Capture the files this exec reads/mutates into [com.accucodeai.kash.api.ExecResult.touched].
     * Off by default: when false and nobody is subscribed to the machine's
     * file-access stream, the recording layer skips all work and allocates
     * no `FileAccess` objects. Turn on per call (or use the `traceAccess`
     * convenience overload of `exec`) when you want the touched-file list.
     */
    val traceAccess: Boolean = false,
)
