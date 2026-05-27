package com.accucodeai.kash.tools.logname

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

private suspend fun runLogname(
    args: List<String>,
    userDb: UserDatabase = UserDatabase.Default,
    env: MutableMap<String, String> = mutableMapOf(),
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            env = env,
            cwd = "/",
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            userDb = userDb,
        )
    val res = LognameCommand().run(args, ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class LognameCommandTest {
    @Test fun defaultPrintsUser() =
        runTest {
            val (rc, out, _) = runLogname(emptyList())
            assertEquals(0, rc)
            assertEquals("user\n", out)
        }

    /** POSIX RATIONALE: `logname` must source from the system, not `$LOGNAME`. */
    @Test fun ignoresLognameEnvVar() =
        runTest {
            val (_, out, _) =
                runLogname(
                    args = emptyList(),
                    userDb = SingleUserDatabase(name = "alice"),
                    env = mutableMapOf("LOGNAME" to "bob"),
                )
            assertEquals("alice\n", out)
        }

    @Test fun anyArgIsAnError() =
        runTest {
            val (rc, _, err) = runLogname(listOf("anything"))
            assertEquals(1, rc)
            assertTrue(err.contains("extra operand"))
        }
}
