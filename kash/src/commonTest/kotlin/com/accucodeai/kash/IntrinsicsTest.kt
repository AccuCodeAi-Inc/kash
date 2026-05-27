package com.accucodeai.kash

import com.accucodeai.kash.conformance.RechoCommand
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals

class IntrinsicsTest {
    private suspend fun out(script: String): String = Kash().exec(script).stdout

    private suspend fun result(script: String) = Kash().exec(script)

    /** Recho is a bash-test-corpus helper that lives in the conformance
     *  test sources; register it explicitly for the few tests that need
     *  its `argv[N] = <...>` rendering. */
    private suspend fun recho(script: String): String =
        Kash(customCommands = listOf(RechoCommand())).exec(script).stdout

    // -------- : (colon) --------

    @Test fun colonIsNoOpExitsZero() =
        runTest {
            val r = result(":")
            assertEquals(0, r.exitCode)
            assertEquals("", r.stdout)
            assertEquals("", r.stderr)
        }

    // -------- set -- --------

    @Test fun setDoubleDashSetsPositional() =
        runTest {
            assertEquals("3: a|b|c\n", out("set -- a b c\necho \"\$#: \$1|\$2|\$3\""))
        }

    @Test fun setDoubleDashEmptyClears() =
        runTest {
            assertEquals("0\n", out("set -- a b\nset --\necho \"\$#\""))
        }

    @Test fun setMinusOIsAccepted() =
        runTest {
            // Option flags get silently accepted and don't pollute positional.
            assertEquals("0\n", out("set -o posix\necho \"\$#\""))
        }

    // -------- shift --------

    @Test fun shiftDefaultsToOne() =
        runTest {
            assertEquals("b\n", out("set -- a b\nshift\necho \"\$1\""))
        }

    @Test fun shiftN() =
        runTest {
            assertEquals("c\n", out("set -- a b c\nshift 2\necho \"\$1\""))
        }

    @Test fun shiftBeyondReturnsError() =
        runTest {
            assertEquals(1, result("set -- a\nshift 5").exitCode)
        }

    // -------- unset --------

    @Test fun unsetRemovesVar() =
        runTest {
            assertEquals("\n", out("X=hello\nunset X\necho \"\$X\""))
        }

    @Test fun unsetFunctionRemovesFunction() =
        runTest {
            // After `unset -f f`, calling f should fail with 127.
            val r = result("f() { echo hi; }\nunset -f f\nf")
            assertEquals(127, r.exitCode)
        }

    // -------- export --------

    @Test fun exportNameEqValueAssigns() =
        runTest {
            assertEquals("v\n", out("export X=v\necho \"\$X\""))
        }

    @Test fun exportMultiple() =
        runTest {
            assertEquals("a b\n", out("export X=a Y=b\necho \"\$X \$Y\""))
        }

    // -------- eval --------

    @Test fun evalRunsConcatenatedScript() =
        runTest {
            assertEquals("hello\n", out("eval echo hello"))
        }

    @Test fun evalSeesCurrentEnv() =
        runTest {
            assertEquals("v\n", out("X=v\neval echo \\\"\\\$X\\\""))
        }

    @Test fun evalMultipleStatements() =
        runTest {
            assertEquals("a\nb\n", out("eval 'echo a; echo b'"))
        }

    // -------- return --------

    @Test fun returnExitsFunctionWithCode() =
        runTest {
            val r = result("f() { return 7; }\nf\necho \$?")
            assertEquals("7\n", r.stdout)
        }

    // -------- recho --------

    @Test fun rechoFormatsArgs() =
        runTest {
            assertEquals("argv[1] = <foo>\nargv[2] = <bar baz>\n", recho("recho foo \"bar baz\""))
        }

    @Test fun rechoRendersTabAsCaret() =
        runTest {
            // Tab (0x09) → ^I. We embed the literal tab as a Kotlin escape inside the script.
            assertEquals("argv[1] = <a^Ib>\n", recho("recho 'a\tb'"))
        }

    // -------- exec (redirection-only form) --------

    @Test fun execRedirectsStdoutForRestOfScript() =
        runTest {
            val k = Kash()
            val r = k.exec("exec >/tmp/out\necho first\necho second")
            assertEquals("", r.stdout)
            assertEquals(0, r.exitCode)
            val captured = k.fs.readBytes("/tmp/out").decodeToString()
            assertEquals("first\nsecond\n", captured)
        }

    @Test fun execAppendsStderrToFile() =
        runTest {
            val k = Kash()
            val r = k.exec("exec 2>>/tmp/err\necho oops 1>&2\necho more 1>&2")
            assertEquals("", r.stderr)
            assertEquals(0, r.exitCode)
            assertEquals("oops\nmore\n", k.fs.readBytes("/tmp/err").decodeToString())
        }

    @Test fun execMergesErrIntoOut() =
        runTest {
            val r = result("exec 2>&1\necho out\necho err 1>&2")
            assertEquals("out\nerr\n", r.stdout)
            assertEquals("", r.stderr)
        }

    @Test fun execWithCommandWordReturns127() =
        runTest {
            val r = result("exec /no/such/prog\necho 'still here'")
            assertEquals(127, r.exitCode)
            assertEquals("", r.stdout)
        }

    // -------- times --------

    @Test fun timesEmitsTwoLinesInExpectedFormat() =
        runTest {
            val r = result("times")
            assertEquals(0, r.exitCode)
            val lines = r.stdout.split("\n")
            // two lines + trailing empty after the final \n
            assertEquals(3, lines.size)
            assertEquals("", lines[2])
            val pattern = Regex("""^\d+m\d+\.\d{3}s \d+m\d+\.\d{3}s$""")
            assertEquals(true, pattern.matches(lines[0]), "shell line: ${lines[0]}")
            assertEquals(true, pattern.matches(lines[1]), "child line: ${lines[1]}")
            // Children are always zero in kash (no child accounting).
            assertEquals("0m0.000s 0m0.000s", lines[1])
        }

    // -------- umask --------

    @Test fun umaskBarePrintsOctal() =
        runTest {
            assertEquals("0022\n", out("umask"))
        }

    @Test fun umaskDashSPrintsSymbolic() =
        runTest {
            assertEquals("u=rwx,g=rx,o=rx\n", out("umask -S"))
        }

    @Test fun umaskSetOctalThenRead() =
        runTest {
            assertEquals("0077\n", out("umask 077\numask"))
        }

    @Test fun umaskSetSymbolicThenRead() =
        runTest {
            // u=rwx,g=,o= → ~mask permissions u=rwx g=000 o=000 → mask 0077
            assertEquals("0077\n", out("umask u=rwx,g=,o=\numask"))
        }

    @Test fun umaskAffectsRedirectionMode() =
        runTest {
            val k = Kash()
            k.exec("umask 077\necho hi >/tmp/f")
            val mode = k.fs.stat("/tmp/f").mode and 0xFFF
            // 0666 & ~077 = 0600
            assertEquals(0b110_000_000, mode)
        }

    // Note: `touch`/`mkdir` umask integration tests live in
    // :kash-app:jvmTest UmaskIntegrationTest — those tools aren't
    // registered in the bare `Kash()` core registry. The
    // `umaskAffectsRedirectionMode()` test above already covers the
    // interpreter side of the plumbing.

    // -------- fd ≥ 3 redirection via process.fdTable --------

    @Test fun execOpensFd3AndWritesViaDup() =
        runTest {
            val k = Kash()
            val r = k.exec("exec 3>/tmp/fd3.out\necho hi >&3\nexec 3>&-\n")
            assertEquals(0, r.exitCode)
            // `exec 3>FILE` should have persisted the fd; `>&3` writes
            // through it; `exec 3>&-` closes the OFD and flushes.
            val src = k.fs.source("/tmp/fd3.out")
            val bb = Buffer()
            try {
                src.readAtMostTo(bb, Long.MAX_VALUE)
            } finally {
                src.close()
            }
            val bytes = bb.readByteArray()
            assertEquals("hi\n", bytes.decodeToString())
        }

    @Test fun dupToUnopenedFdFails() =
        runTest {
            val r = result("echo hi >&5\n")
            // Bash: "5: Bad file descriptor"; we surface the same shape
            // (writes to stderr; non-zero exit from the redirection).
            assertEquals(1, r.exitCode)
        }

    @Test fun execFd3ReadInputThroughDup() =
        runTest {
            val k = Kash()
            // Seed a file, then read it through fd 3 via DUP_IN.
            k.exec("echo seed >/tmp/fd3.in")
            val r = k.exec("exec 3</tmp/fd3.in\ncat <&3\nexec 3<&-\n")
            assertEquals(0, r.exitCode)
            assertEquals("seed\n", r.stdout)
        }
}
