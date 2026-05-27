package com.accucodeai.kash.tools.id

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.user.SingleUserDatabase
import com.accucodeai.kash.api.user.UserDatabase
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private suspend fun runId(
    args: List<String>,
    userDb: UserDatabase = UserDatabase.Default,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            env = mutableMapOf(),
            cwd = "/",
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            userDb = userDb,
        )
    val res = IdCommand().run(args, ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class IdCommandTest {
    @Test fun defaultFormatForDefaultUser() =
        runTest {
            val (rc, out, _) = runId(emptyList())
            assertEquals(0, rc)
            assertEquals("uid=1000(user) gid=1000(user) groups=1000(user)\n", out)
        }

    @Test fun uShortFlag() =
        runTest {
            val (_, out, _) = runId(listOf("-u"))
            assertEquals("1000\n", out)
        }

    @Test fun unCombination() =
        runTest {
            val (_, out, _) = runId(listOf("-un"))
            assertEquals("user\n", out)
        }

    @Test fun gFlags() =
        runTest {
            assertEquals("1000\n", runId(listOf("-g")).second)
            assertEquals("user\n", runId(listOf("-gn")).second)
        }

    @Test fun groupsWithExtras() =
        runTest {
            val db =
                SingleUserDatabase(
                    name = "alice",
                    uid = 2000,
                    gid = 2000,
                    extraGroups = listOf(27 to "sudo", 100 to "users"),
                )
            assertEquals("2000 27 100\n", runId(listOf("-G"), db).second)
            assertEquals("alice sudo users\n", runId(listOf("-Gn"), db).second)
        }

    @Test fun rIsAcceptedAsNoOp() =
        runTest {
            val (rc, out, _) = runId(listOf("-r", "-u"))
            assertEquals(0, rc)
            assertEquals("1000\n", out)
        }

    @Test fun doubleDashEndsOptions() =
        runTest {
            val (rc, _, err) = runId(listOf("--", "-u"))
            // After --, "-u" becomes the user operand → unknown user.
            assertNotEquals(0, rc)
            assertTrue(err.contains("no such user"))
        }

    @Test fun unknownFlag() =
        runTest {
            val (rc, _, err) = runId(listOf("-x"))
            assertEquals(1, rc)
            assertTrue(err.contains("invalid option"))
        }

    @Test fun nWithoutModeIsError() =
        runTest {
            val (rc, _, err) = runId(listOf("-n"))
            assertEquals(1, rc)
            assertTrue(err.contains("default format"))
        }

    @Test fun conflictingModes() =
        runTest {
            val (rc, _, err) = runId(listOf("-u", "-g"))
            assertEquals(1, rc)
            assertTrue(err.contains("more than one"))
        }

    @Test fun operandLookupByName() =
        runTest {
            val db = SingleUserDatabase(name = "alice", uid = 2000)
            val (rc, out, _) = runId(listOf("-u", "alice"), db)
            assertEquals(0, rc)
            assertEquals("2000\n", out)
        }

    @Test fun operandLookupByUid() =
        runTest {
            val db = SingleUserDatabase(name = "alice", uid = 2000)
            val (rc, out, _) = runId(listOf("-un", "2000"), db)
            assertEquals(0, rc)
            assertEquals("alice\n", out)
        }

    @Test fun unknownUserOperand() =
        runTest {
            val (rc, _, err) = runId(listOf("ghost"))
            assertEquals(1, rc)
            assertTrue(err.contains("no such user"))
        }
}
