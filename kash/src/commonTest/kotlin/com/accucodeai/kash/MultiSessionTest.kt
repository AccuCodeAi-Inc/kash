package com.accucodeai.kash

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "one VM, many sessions" contract: sessions on a single [Kash] share
 * the filesystem, registry, and process table but get isolated shell state
 * (env, vars, cwd, functions, aliases). Lifted from Lua's
 * `lua_newstate` / `lua_newthread` split — see the corresponding plan in
 * `.claude/plans/use-ur-plan-tool-concurrent-taco.md`.
 */
class MultiSessionTest {
    @Test fun twoSessionsHaveDistinctPids() =
        runTest {
            val kash = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            try {
                val s1 = kash.newSession()
                val s2 = kash.newSession()
                try {
                    assertNotEquals(s1.process.pid, s2.process.pid)
                    // setsid: every session is its own session leader.
                    assertEquals(s1.process.pid, s1.process.sid)
                    assertEquals(s2.process.pid, s2.process.sid)
                    assertEquals(s1.process.pid, s1.process.pgid)
                    assertEquals(s2.process.pid, s2.process.pgid)
                } finally {
                    s1.close()
                    s2.close()
                }
            } finally {
                kash.close()
            }
        }

    @Test fun filesystemIsSharedAcrossSessions() =
        runTest {
            val kash = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            try {
                val a = kash.newSession()
                val b = kash.newSession()
                try {
                    assertEquals(0, a.exec("echo hello > /tmp/shared").exitCode)
                    val r = b.exec("cat /tmp/shared")
                    assertEquals("hello\n", r.stdout)
                } finally {
                    a.close()
                    b.close()
                }
            } finally {
                kash.close()
            }
        }

    @Test fun envIsIsolatedAcrossSessions() =
        runTest {
            val kash = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            try {
                val a = kash.newSession()
                val b = kash.newSession()
                try {
                    a.exec("X=tab-a")
                    b.exec("X=tab-b")
                    assertEquals("tab-a\n", a.exec("echo \$X").stdout)
                    assertEquals("tab-b\n", b.exec("echo \$X").stdout)
                } finally {
                    a.close()
                    b.close()
                }
            } finally {
                kash.close()
            }
        }

    @Test fun cwdIsIsolatedAcrossSessions() =
        runTest {
            val kash = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            try {
                val a = kash.newSession()
                val b = kash.newSession()
                try {
                    a.exec("cd /tmp")
                    assertEquals("/tmp\n", a.exec("pwd").stdout)
                    // B's cwd is unaffected — still the VM-level initialCwd.
                    assertEquals("${kash.initialCwd}\n", b.exec("pwd").stdout)
                } finally {
                    a.close()
                    b.close()
                }
            } finally {
                kash.close()
            }
        }

    @Test fun functionsAreIsolatedAcrossSessions() =
        runTest {
            val kash = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            try {
                val a = kash.newSession()
                val b = kash.newSession()
                try {
                    a.exec("greet() { echo hi-from-a; }")
                    assertEquals("hi-from-a\n", a.exec("greet").stdout)
                    // B doesn't see A's function; "command not found"-style
                    // exit (non-zero). Exact stderr text is engine-specific
                    // so we only assert the non-zero exit.
                    val br = b.exec("greet")
                    assertTrue(br.exitCode != 0, "b should not see a's function")
                } finally {
                    a.close()
                    b.close()
                }
            } finally {
                kash.close()
            }
        }

    @Test fun sessionsAppearInEachOthersProcessTable() =
        runTest {
            val kash = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            try {
                val a = kash.newSession()
                val b = kash.newSession()
                try {
                    // Same machine ↔ same process table: both pids are visible.
                    assertTrue(a.process.pid in kash.machine.processTable)
                    assertTrue(b.process.pid in kash.machine.processTable)
                    // Each session leader is registered in `sessions[pid]` so
                    // /dev/tty resolves and signal routing works.
                    assertTrue(a.process.pid in kash.machine.sessions)
                    assertTrue(b.process.pid in kash.machine.sessions)
                } finally {
                    a.close()
                    b.close()
                }
            } finally {
                kash.close()
            }
        }

    @Test fun closingSessionUnregistersIt() =
        runTest {
            val kash = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            try {
                val s = kash.newSession()
                val pid = s.process.pid
                assertTrue(pid in kash.machine.processTable)
                s.close()
                assertNull(kash.machine.processTable[pid], "session pid should be removed on close")
                assertNull(kash.machine.sessions[pid], "session entry should be removed on close")
            } finally {
                kash.close()
            }
        }

    @Test fun execOneShotDoesNotPolluteAStatefulSession() =
        runTest {
            // The `Kash.exec` convenience opens a fresh session per call. Its
            // shell state must not leak into independent stateful sessions on
            // the same VM.
            val kash = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            try {
                val s = kash.newSession()
                try {
                    s.exec("X=stateful")
                    // A one-shot exec sees the VM's initialEnv (no X), runs,
                    // and is discarded. It must not see or touch s.X.
                    val r = kash.exec("echo \$X")
                    assertEquals("\n", r.stdout)
                    // And vice versa: the stateful session's X is intact.
                    assertEquals("stateful\n", s.exec("echo \$X").stdout)
                } finally {
                    s.close()
                }
            } finally {
                kash.close()
            }
        }

    @Test fun snapshotRoundTripsPerSession() =
        runTest {
            // Each Kash.Session.snapshot() captures THIS session's state.
            // The VM-level FS is captured into the snapshot's `fs` field, so
            // a single-session round-trip via `Kash.restoreSession` works the
            // same as the legacy KashSession.restore did.
            val kash = Kash(registry = standardRegistry(), fs = InMemoryFs(), parentContext = coroutineContext)
            val captured =
                try {
                    val a = kash.newSession()
                    try {
                        a.exec("FOO=multi")
                        a.exec("greet() { echo \"hi \$FOO\"; }")
                        a.exec("cd /tmp")
                        a.snapshot()
                    } finally {
                        a.close()
                    }
                } finally {
                    kash.close()
                }
            val restored = Kash.restoreSession(captured, registry = standardRegistry())
            try {
                assertEquals("/tmp", restored.cwd)
                assertEquals("multi", restored.env["FOO"])
                assertEquals("hi multi\n", restored.exec("greet").stdout)
            } finally {
                restored.close()
            }
        }
}
