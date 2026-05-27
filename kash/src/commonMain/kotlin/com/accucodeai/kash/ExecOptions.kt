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
)
