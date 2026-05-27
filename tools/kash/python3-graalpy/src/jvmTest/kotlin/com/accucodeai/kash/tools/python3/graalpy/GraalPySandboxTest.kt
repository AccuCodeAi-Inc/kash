package com.accucodeai.kash.tools.python3.graalpy

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.api.sandbox.SandboxPolicy
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.python3.Python3Command
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [GraalPyEngine]'s [SandboxPolicy] handling. Two paths matter:
 *
 *  - TRUSTED + CONSTRAINED: existing hardened config should still succeed.
 *    CONSTRAINED layers `Context.Builder.sandbox(CONSTRAINED)` on top —
 *    Truffle enforces policy compatibility at build time. If it errors, our
 *    config has drifted away from CONSTRAINED's requirements and we want
 *    that surfaced as a test failure, not silently swallowed.
 *  - UNTRUSTED on a non-Oracle GraalVM: the engine MUST refuse to start with
 *    exit 126 and a clear stderr message — per session-owner directive,
 *    explicit failure beats silent downgrade.
 *
 * The CI runtime is GraalVM Community, so UNTRUSTED's happy path can't be
 * tested here. If someone runs the suite on Oracle GraalVM, the
 * refuse-to-start test will fail (UNTRUSTED *would* succeed). That's
 * expected — flip the assertion when CI gains an Oracle runner.
 */
class GraalPySandboxTest {
    private fun runPython(
        sandbox: SandboxPolicy,
        vararg args: String,
        networkPolicy: NetworkPolicy = NetworkPolicy.None,
    ): Triple<Int, String, String> {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = InMemoryFs(),
                env = mutableMapOf(),
                cwd = "/home/user",
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
                sandbox = sandbox,
                networkPolicy = networkPolicy,
            )
        val rc = runBlocking { Python3Command(GraalPyEngine()).run(args.toList(), ctx).exitCode }
        return Triple(rc, out.readString(), err.readString())
    }

    @Test fun `trusted runs python normally`() =
        runBlocking {
            val (rc, out, err) = runPython(SandboxPolicy.TRUSTED, "-c", "print('ok')")
            assertEquals(0, rc, "stderr was: $err")
            assertEquals("ok\n", out)
        }

    @Test fun `constrained still runs python with hardened policy`() =
        runBlocking {
            // Our default config already satisfies CONSTRAINED's requirements
            // (HostAccess.NONE, custom IOAccess FS, no native, no proc, no
            // env). If a future config change breaks this, Truffle will throw
            // at build() and this assertion will surface it.
            val (rc, out, err) = runPython(SandboxPolicy.CONSTRAINED, "-c", "print(2+2)")
            assertEquals(0, rc, "stderr was: $err")
            assertEquals("4\n", out)
        }

    @Test fun `denyall network policy refuses to start under trusted`() =
        runBlocking {
            // NetworkPolicy enforcement inside Python requires
            // SandboxPolicy.UNTRUSTED (the only tier where Truffle's
            // sandbox validation denies host sockets at the polyglot
            // layer). Any softer enforcement would be a misleading
            // best-effort wrapper — see docs/SECURITY.md — so we refuse
            // to start with a clear diagnostic instead.
            val (rc, out, err) =
                runPython(
                    SandboxPolicy.TRUSTED,
                    "-c",
                    "print('should not run')",
                    networkPolicy = NetworkPolicy.DenyAll,
                )
            assertEquals(126, rc, "expected refuse-to-start (126); out=$out err=$err")
            assertTrue(
                err.contains("NetworkPolicy other than None requires sandbox=UNTRUSTED"),
                "expected refuse-to-start diagnostic, got stderr: $err",
            )
            assertEquals("", out, "no python output should have been produced")
        }

    @Test fun `none network policy still runs under trusted`() =
        runBlocking {
            // Sanity: the gate must NOT fire when NetworkPolicy is None.
            val (rc, out, err) =
                runPython(
                    SandboxPolicy.TRUSTED,
                    "-c",
                    "print('hello')",
                    networkPolicy = NetworkPolicy.None,
                )
            assertEquals(0, rc, "stderr was: $err")
            assertEquals("hello\n", out)
        }

    @Test fun `untrusted refuses to start on community graalvm`() =
        runBlocking {
            val (rc, out, err) = runPython(SandboxPolicy.UNTRUSTED, "-c", "print('should not run')")
            // Either we're on community (rc=126, refuse-to-start), or on
            // Oracle GraalVM (rc=0). Skip cleanly on Oracle.
            if (rc == 0) {
                // Running on Oracle GraalVM — flip the test once CI has one.
                return@runBlocking
            }
            assertEquals(126, rc, "expected refuse-to-start (126), got $rc; out=$out err=$err")
            assertTrue(
                err.contains("UNTRUSTED requires Oracle GraalVM"),
                "expected refuse-to-start diagnostic, got stderr: $err",
            )
            assertEquals("", out, "no python output should have been produced")
        }
}
