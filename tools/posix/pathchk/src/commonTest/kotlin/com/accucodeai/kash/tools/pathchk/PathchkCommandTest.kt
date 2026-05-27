package com.accucodeai.kash.tools.pathchk

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

private suspend fun runPathchk(vararg args: String): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val rc = PathchkCommand().run(args.toList(), ctx)
    return Triple(rc.exitCode, out.readString(), err.readString())
}

class PathchkCommandTest {
    private val cmd = PathchkCommand()

    // ---- direct validate() unit tests ----

    @Test fun validate_plainPathOk() {
        assertNull(cmd.validate("/etc/passwd", portable = false, stricter = false))
    }

    @Test fun validate_emptyPath() {
        assertContains(cmd.validate("", false, false)!!, "empty")
    }

    @Test fun validate_tooLong() {
        val p = "/" + "a".repeat(5000)
        assertContains(cmd.validate(p, false, false)!!, "too long")
    }

    @Test fun validate_componentTooLong() {
        val p = "/foo/" + "a".repeat(300)
        assertContains(cmd.validate(p, false, false)!!, "component too long")
    }

    @Test fun validate_portableRejectsSpace() {
        assertContains(cmd.validate("foo bar", portable = true, stricter = false)!!, "non-portable")
    }

    @Test fun validate_portableAccepts() {
        assertNull(cmd.validate("foo.bar-baz", portable = true, stricter = false))
    }

    @Test fun validate_portablePosixLengthLimit() {
        // POSIX_NAME_MAX = 14
        assertContains(cmd.validate("a".repeat(15), portable = true, stricter = false)!!, "too long")
    }

    @Test fun validate_stricter_emptyComponent() {
        assertContains(cmd.validate("path//empty", false, stricter = true)!!, "empty component")
    }

    @Test fun validate_stricter_dashLeading() {
        assertContains(cmd.validate("-bad", false, stricter = true)!!, "'-'")
    }

    @Test fun validate_absolutePath_leadingEmptyOk() {
        assertNull(cmd.validate("/etc/passwd", false, stricter = true))
    }

    @Test fun validate_trailingSlashOk() {
        assertNull(cmd.validate("/etc/", false, stricter = true))
    }

    // ---- CLI tests ----

    @Test fun cli_validPath_exit0() =
        runTest {
            val (rc, _, _) = runPathchk("/etc/passwd")
            assertEquals(0, rc)
        }

    @Test fun cli_emptyPath_exit1() =
        runTest {
            val (rc, _, err) = runPathchk("")
            assertEquals(1, rc)
            assertContains(err, "empty")
        }

    @Test fun cli_componentTooLong_exit1() =
        runTest {
            val (rc, _, _) = runPathchk("/a/" + "x".repeat(300))
            assertEquals(1, rc)
        }

    @Test fun cli_dashP_portableSpaceRejected() =
        runTest {
            val (rc, _, _) = runPathchk("-p", "foo bar")
            assertEquals(1, rc)
        }

    @Test fun cli_dashP_portableOk() =
        runTest {
            val (rc, _, _) = runPathchk("-p", "foo.bar")
            assertEquals(0, rc)
        }

    @Test fun cli_dashP_strict_leadingDashRejected() =
        runTest {
            val (rc, _, _) = runPathchk("-P", "--", "-startsWithDash")
            assertEquals(1, rc)
        }

    @Test fun cli_dashP_strict_emptyComponent() =
        runTest {
            val (rc, _, _) = runPathchk("-P", "path//empty")
            assertEquals(1, rc)
        }

    @Test fun cli_multiplePathsAllChecked() =
        runTest {
            val (rc, _, err) = runPathchk("/ok", "", "/also/ok")
            assertEquals(1, rc)
            assertContains(err, "''")
        }

    @Test fun cli_missingOperand_usageError() =
        runTest {
            val (rc, _, _) = runPathchk()
            assertEquals(2, rc)
        }

    @Test fun cli_help() =
        runTest {
            val (rc, out, _) = runPathchk("--help")
            assertEquals(0, rc)
            assertContains(out, "pathchk")
        }
}
