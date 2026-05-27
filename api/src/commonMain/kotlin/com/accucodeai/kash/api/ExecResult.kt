package com.accucodeai.kash.api

/**
 * Result of running a script through one of the embedding entry points
 * (`Kash.exec`, `Kash.Session.exec`, conformance runners).
 *
 * Script-level analog of [CommandResult] — captures buffered stdout and
 * stderr plus the final exit code. Lives in `:api` so both the embedding
 * facade (`:kash`) and the shell engine (`:tools:kash:shell`) can return
 * it without crossing module boundaries.
 *
 * For streaming I/O — where stdout/stderr go straight to caller-provided
 * sinks instead of being buffered — see `Kash.exec` with stream sinks or
 * `Kash.Session.execStreaming`, which return just the exit code.
 */
public data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)
