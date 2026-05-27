package com.accucodeai.kash.api.signal

import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context element giving a running command access to its job's
 * inbound signal stream. Tools that want to react to kash signals
 * (`tail -f` exiting on INT, a long-running compute responding to USR1)
 * read it like:
 *
 * ```kotlin
 * val sigs = currentCoroutineContext()[JobSignalContext]?.signals
 * while (isActive) {
 *     sigs?.tryReceive()?.getOrNull()?.let { handleSignal(it); break }
 *     // … one iteration of work …
 * }
 * ```
 *
 * Tools that don't read it are unaffected — INT/TERM still cancel via
 * `Job.cancel()`, so any tool that hits a suspending call (`delay`,
 * `Channel.receive`, suspending reads) cooperates automatically.
 */
public class JobSignalContext(
    public val signals: ReceiveChannel<KashSignal>,
) : AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<JobSignalContext>
}
