package com.accucodeai.kash.api.io

import kotlin.coroutines.CoroutineContext

/**
 * Dispatcher used by the OS-boundary adapters in [SuspendIo] to offload
 * blocking RawSource/RawSink calls. On JVM this is `Dispatchers.IO` — an
 * elastic thread pool sized for blocking work. On wasmJs there is no
 * thread pool to back it (the runtime is single-threaded), and there is
 * no blocking I/O to offload anyway — every `RawSource`/`RawSink` on
 * wasmJs is already non-blocking — so the actual is
 * `EmptyCoroutineContext` (a no-op `withContext` switch).
 */
internal expect val ioDispatcher: CoroutineContext
