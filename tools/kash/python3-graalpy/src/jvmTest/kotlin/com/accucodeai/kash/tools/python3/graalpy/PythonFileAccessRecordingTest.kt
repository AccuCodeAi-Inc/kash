package com.accucodeai.kash.tools.python3.graalpy

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.fs.AccessKind
import com.accucodeai.kash.fs.FileAccess
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.python3.Python3Command
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.readString
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The headline guarantee: file access from *inside* GraalPy's `open()` is
 * recorded on the machine's [com.accucodeai.kash.api.KashMachine.fileAccess]
 * bus, because [Python3Command] hands the engine `ctx.process.fs` — the
 * opener-bound recording facade — and [KashPolyglotFileSystem] proxies every
 * Python file op back into it.
 *
 * (The ENGINE_CACHE-noise filtering is covered by `RecordingFileSystemTest`;
 * here we use a plain [InMemoryFs] to match the engine's path expectations.)
 *
 * GraalPy spins up a real interpreter (seconds of first-context cost), so
 * this suite is intentionally tiny.
 */
class PythonFileAccessRecordingTest {
    @Test
    fun pythonOpenWriteAndReadIsRecorded() =
        runBlocking {
            val fs = InMemoryFs()
            val err = Buffer()
            val ctx = bareCommandContext(fs = fs, cwd = "/home/user", stderr = err.asSuspendSink())
            val bus = ctx.process.machine.fileAccess

            val seen = CopyOnWriteArrayList<FileAccess>()
            coroutineScope {
                val subscribed = CompletableDeferred<Unit>()
                val job =
                    launch(Dispatchers.Default) {
                        bus.events.onSubscription { subscribed.complete(Unit) }.collect { seen.add(it) }
                    }
                subscribed.await()

                val rc =
                    Python3Command(GraalPyEngine())
                        .run(
                            listOf(
                                "-c",
                                // `with` so the channel flushes to kash FS before exit.
                                "with open('/home/user/out.txt','w') as f:\n" +
                                    "    f.write('hello')\n" +
                                    "with open('/home/user/out.txt') as f:\n" +
                                    "    print(f.read())\n",
                            ),
                            ctx,
                        ).exitCode
                assertEquals(0, rc, "python failed; stderr: ${err.readString()}")

                withTimeoutOrNull(3_000.milliseconds) {
                    var last = -1
                    while (seen.size != last) {
                        last = seen.size
                        delay(40.milliseconds)
                    }
                }
                job.cancelAndJoin()
            }

            val out = seen.filter { it.path == "/home/user/out.txt" }
            assertTrue(
                out.any { it.kind == AccessKind.CREATE || it.kind == AccessKind.WRITE },
                "expected Python write of out.txt to be recorded; got $seen",
            )
            assertTrue(
                out.any { it.kind == AccessKind.READ },
                "expected Python read of out.txt to be recorded; got $seen",
            )
        }
}
