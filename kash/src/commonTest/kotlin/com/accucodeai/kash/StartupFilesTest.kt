package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for bash-parity startup-file sourcing. See StartupFiles.kt in
 * tools/kash/shell for the behavior matrix; this test exercises it
 * through the `kash` command's `-c`/`-l`/`--rcfile`/`--noprofile`/
 * `--posix` argv surface, the same way bash itself would.
 *
 * The host's default user database hands every fresh [Kash] a
 * `$HOME = /home/user` — tests write the relevant profile / rc files
 * under that path before invoking the shell.
 */
class StartupFilesTest {
    // ---- login-shell profile cascade ----

    @Test fun loginShellSourcesKashProfile() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/home/user")
            kash.fs.writeBytes(
                "/home/user/.kash_profile",
                "echo from-kash_profile\n".encodeToByteArray(),
            )
            val r = kash.exec("kash -l -c 'echo body'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("from-kash_profile\nbody\n", r.stdout)
        }

    @Test fun loginShellSourcesEtcProfileBeforePersonal() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/etc")
            kash.fs.writeBytes("/etc/profile", "echo etc\n".encodeToByteArray())
            kash.fs.mkdirs("/home/user")
            kash.fs.writeBytes(
                "/home/user/.kash_profile",
                "echo personal\n".encodeToByteArray(),
            )
            val r = kash.exec("kash -l -c 'echo body'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("etc\npersonal\nbody\n", r.stdout)
        }

    @Test fun loginShellPrefersKashProfileOverKashLoginOverDotProfile() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/home/user")
            kash.fs.writeBytes("/home/user/.kash_profile", "echo P\n".encodeToByteArray())
            kash.fs.writeBytes("/home/user/.kash_login", "echo L\n".encodeToByteArray())
            kash.fs.writeBytes("/home/user/.profile", "echo D\n".encodeToByteArray())
            val r = kash.exec("kash -l -c 'true'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            // Only the first present is read — P short-circuits L and D.
            assertEquals("P\n", r.stdout)
        }

    @Test fun loginShellFallsBackToKashLoginWhenProfileAbsent() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/home/user")
            kash.fs.writeBytes("/home/user/.kash_login", "echo L\n".encodeToByteArray())
            kash.fs.writeBytes("/home/user/.profile", "echo D\n".encodeToByteArray())
            val r = kash.exec("kash -l -c 'true'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("L\n", r.stdout)
        }

    @Test fun loginShellFallsBackToDotProfileWhenKashFilesAbsent() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/home/user")
            kash.fs.writeBytes("/home/user/.profile", "echo D\n".encodeToByteArray())
            val r = kash.exec("kash -l -c 'true'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("D\n", r.stdout)
        }

    @Test fun noProfileSuppressesLoginCascade() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/home/user")
            kash.fs.writeBytes(
                "/home/user/.kash_profile",
                "echo SHOULD-NOT-FIRE\n".encodeToByteArray(),
            )
            kash.fs.mkdirs("/etc")
            kash.fs.writeBytes(
                "/etc/profile",
                "echo SHOULD-NOT-FIRE-EITHER\n".encodeToByteArray(),
            )
            val r = kash.exec("kash -l --noprofile -c 'echo body'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("body\n", r.stdout)
            assertFalse("SHOULD-NOT-FIRE" in r.stdout)
        }

    @Test fun nonLoginShellSkipsProfileEvenIfPresent() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/home/user")
            kash.fs.writeBytes(
                "/home/user/.kash_profile",
                "echo SHOULD-NOT-FIRE\n".encodeToByteArray(),
            )
            val r = kash.exec("kash -c 'echo body'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("body\n", r.stdout)
        }

    // ---- BASH_ENV / KASH_ENV (non-interactive) ----

    @Test fun bashEnvSourcedForDashC() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/tmp")
            kash.fs.writeBytes("/tmp/env.sh", "echo env-fired\n".encodeToByteArray())
            val r = kash.exec("BASH_ENV=/tmp/env.sh kash -c 'echo body'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("env-fired\nbody\n", r.stdout)
        }

    @Test fun kashEnvWinsOverBashEnv() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/tmp")
            kash.fs.writeBytes("/tmp/k.sh", "echo kash-env\n".encodeToByteArray())
            kash.fs.writeBytes("/tmp/b.sh", "echo bash-env\n".encodeToByteArray())
            val r = kash.exec("KASH_ENV=/tmp/k.sh BASH_ENV=/tmp/b.sh kash -c 'true'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("kash-env\n", r.stdout)
        }

    @Test fun bashEnvHonorsTildeExpansion() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/home/user")
            kash.fs.writeBytes(
                "/home/user/myenv.sh",
                "echo tilde-env\n".encodeToByteArray(),
            )
            val r = kash.exec("BASH_ENV='~/myenv.sh' kash -c 'true'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("tilde-env\n", r.stdout)
        }

    @Test fun missingBashEnvIsSilentlyIgnored() =
        runTest {
            val kash = Kash()
            val r = kash.exec("BASH_ENV=/no/such/file kash -c 'echo body'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("body\n", r.stdout)
            // Missing files are silent — no diagnostic on stderr.
            assertFalse("No such" in r.stderr, "stderr leaked: ${r.stderr}")
        }

    // ---- --rcfile / --init-file ----

    @Test fun rcFileOverrideAcceptedAsFlag() =
        runTest {
            // We can't easily drive the interactive REPL from a plain
            // exec, but we CAN confirm parseArgs accepts --rcfile FILE
            // and doesn't treat it as a script-file argument.
            val r = Kash().exec("kash --rcfile /tmp/x -c 'echo ok'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("ok\n", r.stdout)
        }

    @Test fun rcFileMissingArgErrors() =
        runTest {
            val r = Kash().exec("kash --rcfile")
            assertEquals(2, r.exitCode)
            assertContains(r.stderr, "requires an argument")
        }

    // ---- error policy: exists but broken vs missing ----

    @Test fun brokenProfileEmitsDiagnosticButContinues() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/home/user")
            kash.fs.writeBytes(
                "/home/user/.kash_profile",
                "if true\n".encodeToByteArray(), // unterminated `if`
            )
            val r = kash.exec("kash -l -c 'echo body'")
            assertEquals(0, r.exitCode, "body still runs; exit=${r.exitCode} stderr=${r.stderr}")
            // Diagnostic surfaces with the file path; body still executes.
            assertContains(r.stderr, "/home/user/.kash_profile")
            assertEquals("body\n", r.stdout)
        }

    @Test fun missingProfileIsSilent() =
        runTest {
            val kash = Kash()
            val r = kash.exec("kash -l -c 'echo body'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("body\n", r.stdout)
            // No /etc/profile, no ~/.kash_profile, no ~/.kash_login, no
            // ~/.profile → completely silent.
            assertTrue(r.stderr.isEmpty(), "stderr leaked: ${r.stderr}")
        }

    // ---- env mutations from startup files cross into the body ----

    @Test fun profileExportsVisibleInBody() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/home/user")
            kash.fs.writeBytes(
                "/home/user/.kash_profile",
                "export GREETING=hi\n".encodeToByteArray(),
            )
            val r = kash.exec("kash -l -c 'echo \$GREETING'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("hi\n", r.stdout)
        }

    @Test fun bashEnvSetsVarsVisibleInBody() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/tmp")
            kash.fs.writeBytes("/tmp/env.sh", "FROM_ENV=yes\n".encodeToByteArray())
            val r = kash.exec("BASH_ENV=/tmp/env.sh kash -c 'echo \$FROM_ENV'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("yes\n", r.stdout)
        }
}
