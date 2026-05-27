package com.accucodeai.kash.tools.whoami

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
import kotlin.test.assertTrue

private suspend fun runWhoami(
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
    val res = WhoamiCommand().run(args, ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class WhoamiCommandTest {
    @Test fun defaultPrintsUser() =
        runTest {
            val (rc, out, _) = runWhoami(emptyList())
            assertEquals(0, rc)
            assertEquals("user\n", out)
        }

    @Test fun customDatabase() =
        runTest {
            val (_, out, _) = runWhoami(emptyList(), SingleUserDatabase(name = "alice"))
            assertEquals("alice\n", out)
        }

    @Test fun argIsError() =
        runTest {
            val (rc, _, err) = runWhoami(listOf("anything"))
            assertEquals(1, rc)
            assertTrue(err.contains("extra operand"))
        }
}
