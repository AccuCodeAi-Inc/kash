package com.accucodeai.kash

import com.accucodeai.kash.api.user.SingleUserDatabase
import com.accucodeai.kash.app.standardRegistry
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.snapshot.MachineSnapshot
import com.accucodeai.kash.snapshot.SnapshotJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage of POSIX user identity — exercises the `id`,
 * `logname`, and `whoami` *tools* dispatched through the full Koin-composed
 * registry. Lives in `:kash-app` because that's where the tool modules are
 * actually wired in; `:core` tests can only exercise the interpreter-level
 * hooks (env, tilde) since they don't depend on `:tools:*`.
 */
class UserIdentityIntegrationTest {
    private fun session(
        name: String = "user",
        uid: Int = 1000,
        gid: Int = 1000,
        home: String = "/home/user",
        extraGroups: List<Pair<Int, String>> = emptyList(),
    ): Kash.Session =
        Kash(
            fs = InMemoryFs(),
            registry = standardRegistry(),
            userDatabase =
                SingleUserDatabase(
                    name = name,
                    uid = uid,
                    gid = gid,
                    home = home,
                    extraGroups = extraGroups,
                ),
        ).newSession()

    @Test fun idDefaultFormat() =
        runTest {
            val r = session().exec("id")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("uid=1000(user) gid=1000(user) groups=1000(user)\n", r.stdout)
        }

    @Test fun idUnSelectsName() =
        runTest {
            val s = session(name = "alice", uid = 2000)
            assertEquals("alice\n", s.exec("id -un").stdout)
            assertEquals("2000\n", s.exec("id -u").stdout)
        }

    @Test fun idAllGroupsHonorsExtras() =
        runTest {
            val s = session(name = "alice", gid = 2000, extraGroups = listOf(27 to "sudo"))
            assertEquals("alice sudo\n", s.exec("id -Gn").stdout)
        }

    @Test fun lognameSourcesFromUserDb() =
        runTest {
            val s = session(name = "alice")
            assertEquals("alice\n", s.exec("logname").stdout)
        }

    /** POSIX RATIONALE: logname must NOT trust `$LOGNAME`. */
    @Test fun lognameIgnoresEnvOverride() =
        runTest {
            val s = session(name = "alice")
            val r = s.exec("LOGNAME=bob logname")
            assertEquals("alice\n", r.stdout)
        }

    @Test fun whoamiMatchesIdUn() =
        runTest {
            val s = session(name = "alice")
            assertEquals("alice\n", s.exec("whoami").stdout)
            assertEquals(s.exec("id -un").stdout, s.exec("whoami").stdout)
        }

    @Test fun envLognameUserHomePopulated() =
        runTest {
            val s = session(name = "alice", home = "/home/alice")
            val r = s.exec("echo \$LOGNAME-\$USER-\$HOME")
            assertEquals("alice-alice-/home/alice\n", r.stdout)
        }

    @Test fun tildeAliceExpands() =
        runTest {
            val s = session(name = "alice", home = "/home/alice")
            assertEquals("/home/alice\n", s.exec("echo ~alice").stdout)
        }

    @Test fun snapshotPreservesIdentityViaEnvAndRestoreInjection() =
        runTest {
            val db = SingleUserDatabase(name = "alice", uid = 2000, home = "/home/alice")
            val a =
                Kash(
                    fs = InMemoryFs(),
                    registry = standardRegistry(),
                    userDatabase = db,
                ).newSession()
            a.exec("X=\$LOGNAME-\$(id -un)")
            val snap =
                SnapshotJson.decodeFromString(
                    MachineSnapshot.serializer(),
                    SnapshotJson.encodeToString(MachineSnapshot.serializer(), a.machineSnapshot()),
                )
            val b =
                Kash.restoreMachineSession(
                    snap,
                    registry = standardRegistry(),
                    userDatabase = db,
                )
            assertEquals("alice-alice\n", b.exec("echo \$X").stdout)
            assertEquals("/home/alice\n", b.exec("echo ~alice").stdout)
            assertEquals("2000\n", b.exec("id -u").stdout)
        }

    @Test fun subshellIsolatesLognameOverride() =
        runTest {
            val s = session(name = "alice")
            val r = s.exec("(export LOGNAME=other; echo in:\$LOGNAME); echo out:\$LOGNAME")
            assertEquals("in:other\nout:alice\n", r.stdout)
            // The tool still sources from the DB, even inside the subshell.
            val r2 = s.exec("(export LOGNAME=other; logname)")
            assertEquals("alice\n", r2.stdout)
        }

    @Test fun backgroundJobSeesSameUserDb() =
        runTest {
            // parentContext = coroutineContext makes the session's background
            // scope a sibling of this test body — auto-cancelled when the
            // test ends, no manual close() required.
            val s =
                Kash(
                    fs = InMemoryFs(),
                    registry = standardRegistry(),
                    userDatabase = SingleUserDatabase(name = "alice"),
                    parentContext = coroutineContext,
                ).newSession()
            val r = s.exec("id -un & wait")
            assertTrue(r.stdout.contains("alice"), "stdout=${r.stdout}")
        }
}
