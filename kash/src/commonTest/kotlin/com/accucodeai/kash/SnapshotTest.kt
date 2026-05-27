package com.accucodeai.kash

import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.snapshot.InterpreterSnapshot
import com.accucodeai.kash.snapshot.SnapshotJson
import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotTest {
    @Test fun envFunctionAndCwdRoundTrip() =
        runTest {
            val a = Kash(registry = standardRegistry(), fs = InMemoryFs()).newSession()
            a.exec("FOO=bar")
            a.exec($$"greet() { echo \"hi $FOO\"; }")
            a.exec("cd /tmp")

            val json = SnapshotJson.encodeToString(a.snapshot())
            val restored = SnapshotJson.decodeFromString<InterpreterSnapshot>(json)
            val b = Kash.restoreSession(restored, registry = standardRegistry())

            assertEquals("/tmp", b.cwd)
            assertEquals("bar", b.env["FOO"])
            assertEquals("hi bar\n", b.exec("greet").stdout)
        }

    @Test fun inMemoryFilesystemRoundTrip() =
        runTest {
            val a = Kash(registry = standardRegistry(), fs = InMemoryFs()).newSession()
            a.exec("echo hello > /tmp/note")
            a.exec("echo more >> /tmp/note")

            val json = SnapshotJson.encodeToString(a.snapshot())
            val restored = SnapshotJson.decodeFromString<InterpreterSnapshot>(json)
            val b = Kash.restoreSession(restored, registry = standardRegistry())

            assertEquals("hello\nmore\n", b.exec("cat /tmp/note").stdout)
            // Pre-existing seeded dir survives the round-trip too.
            assertEquals(0, b.exec("test -d /tmp").exitCode)
        }
}
