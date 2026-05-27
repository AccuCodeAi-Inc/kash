package com.accucodeai.kash

import com.accucodeai.kash.api.user.SingleUserDatabase
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.snapshot.InterpreterSnapshot
import com.accucodeai.kash.snapshot.SnapshotJson
import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Integration tests for the [com.accucodeai.kash.api.user.UserDatabase]
 * wiring — env defaults, `~user` tilde expansion, subshell isolation,
 * snapshot round-trip.
 *
 * These tests live in `:core:commonTest` because they don't reach for the
 * `id`/`logname`/`whoami` *tools* (those are separate modules); they
 * exercise the *interpreter-level* hooks: env population, `~name`
 * expansion, and snapshot persistence. Tool-level end-to-end coverage
 * lives in `:kash-app:jvmTest:UserIdentityIntegrationTest`.
 */
class UserIdentityTest {
    @Test fun defaultEnvVarsCarryUserIdentity() =
        runTest {
            val s = Kash(registry = standardRegistry(), fs = InMemoryFs()).newSession()
            val r = s.exec("echo \$LOGNAME-\$USER-\$HOME")
            assertEquals("user-user-/home/user\n", r.stdout)
        }

    @Test fun customUserDatabaseDrivesEnvDefaults() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    fs = InMemoryFs(),
                    userDatabase = SingleUserDatabase(name = "alice", uid = 2000, home = "/home/alice"),
                ).newSession()
            val r = s.exec("echo \$LOGNAME-\$USER-\$HOME")
            assertEquals("alice-alice-/home/alice\n", r.stdout)
        }

    @Test fun explicitInitialEnvIsNotClobbered() =
        runTest {
            // Caller passes initialEnv → it wins, the userDatabase no longer
            // gets a chance to populate defaults.
            val s =
                Kash(
                    registry = standardRegistry(),
                    fs = InMemoryFs(),
                    userDatabase = SingleUserDatabase(name = "alice", home = "/home/alice"),
                    initialEnv = mapOf("LOGNAME" to "explicit", "HOME" to "/x", "PATH" to "/usr/bin:/bin"),
                    initialCwd = "/x",
                ).newSession()
            assertEquals("explicit\n", s.exec("echo \$LOGNAME").stdout)
            assertEquals("/x\n", s.exec("echo \$HOME").stdout)
        }

    @Test fun tildeUserExpandsKnownUser() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    fs = InMemoryFs(),
                    userDatabase = SingleUserDatabase(name = "alice", home = "/home/alice"),
                ).newSession()
            assertEquals("/home/alice\n", s.exec("echo ~alice").stdout)
        }

    @Test fun tildeUnknownUserStaysLiteral() =
        runTest {
            val s = Kash(registry = standardRegistry(), fs = InMemoryFs()).newSession()
            // `~bogus` is unknown → bash leaves it as the literal string.
            assertEquals("~bogus\n", s.exec("echo ~bogus").stdout)
        }

    @Test fun subshellLognameOverrideDoesNotLeak() =
        runTest {
            val s = Kash(registry = standardRegistry(), fs = InMemoryFs()).newSession()
            val r = s.exec("(export LOGNAME=other; echo in:\$LOGNAME); echo out:\$LOGNAME")
            assertEquals("in:other\nout:user\n", r.stdout)
        }

    @Test fun snapshotRoundTripPreservesEnvIdentity() =
        runTest {
            val db = SingleUserDatabase(name = "alice", uid = 2000, home = "/home/alice")
            val a = Kash(registry = standardRegistry(), fs = InMemoryFs(), userDatabase = db).newSession()
            a.exec("X=\$LOGNAME-\$USER")
            val json = SnapshotJson.encodeToString(a.snapshot())
            val restored = SnapshotJson.decodeFromString<InterpreterSnapshot>(json)
            val b = Kash.restoreSession(restored, registry = standardRegistry(), userDatabase = db)
            // Env round-trips through snapshot.env — no new snapshot field needed.
            assertEquals("alice-alice\n", b.exec("echo \$X").stdout)
            // ~alice still works after restore because userDatabase is re-injected.
            assertEquals("/home/alice\n", b.exec("echo ~alice").stdout)
        }

    @Test fun restoreWithDifferentUserDatabaseHonorsEnvButShiftsLookups() =
        runTest {
            // The snapshot pins LOGNAME=alice in env. Restoring with a *different*
            // user database leaves the env intact (it was serialized) but tilde
            // expansion of ~bob now succeeds (because the DB says so) and ~alice
            // no longer resolves (the DB no longer knows that name).
            val a =
                Kash(
                    registry = standardRegistry(),
                    fs = InMemoryFs(),
                    userDatabase = SingleUserDatabase(name = "alice"),
                ).newSession()
            val snap = a.snapshot()
            val b =
                Kash.restoreSession(
                    snap,
                    registry = standardRegistry(),
                    userDatabase = SingleUserDatabase(name = "bob", home = "/home/bob"),
                )
            assertEquals("alice\n", b.exec("echo \$LOGNAME").stdout) // from snapshot env
            assertEquals("/home/bob\n", b.exec("echo ~bob").stdout) // from new DB
            assertEquals("~alice\n", b.exec("echo ~alice").stdout) // unknown → literal
        }
}
