package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `/proc/self/fd/` and `/dev/fd/` introspection covers four intertwined
 * shapes:
 *
 *  1. Per-command file redirects on fd 0/1/2 (`cmd > file`, `cmd < file`)
 *     must stamp the file path on the OFD that lands in `fdTable[N]`, so
 *     `readlink /proc/self/fd/N` reports the file — not `pipe:[N]` from
 *     whatever stream was inherited.
 *
 *  2. Anonymous pipes (`<(…)`, `>(…)`, coproc) get a process-unique
 *     [AsyncPipe.inode] shared by both ends. `pipe:[<inode>]` is stable
 *     across fd dups and distinct across separate pipes — matches Linux's
 *     `proc_inum` behavior.
 *
 *  4. `/dev/fd/N` is a magic symlink — `readlink` resolves it (not EINVAL).
 *     Same target rules as `/proc/self/fd/N`.
 *
 * (Issue 3 from the original report — `while read; done < <(cmd)` empty
 *  output — was a state-snapshot lock artifact, not a real bug; see the
 *  end-to-end ProcessSubstitutionTest for the procsub path coverage.)
 */
class FdMetadataTest {
    private suspend fun out(script: String): String = Kash().exec(script).stdout

    // ---------------- Issue 1: per-command fd<3 file redirect ----------------

    @Test fun stdoutRedirToFile_setsFdPath() =
        runTest {
            // `cmd > file` must update fd 1's OFD.path so /proc/self/fd/1
            // points to the file, not the inherited pipe.
            val r =
                Kash().exec(
                    """
                    readlink /proc/self/fd/1 > /tmp/fdmeta_a.txt
                    cat /tmp/fdmeta_a.txt
                    """.trimIndent(),
                )
            assertEquals("/tmp/fdmeta_a.txt\n", r.stdout)
        }

    @Test fun stdinRedirFromFile_setsFdPath() =
        runTest {
            val r =
                Kash().exec(
                    """
                    printf hi > /tmp/fdmeta_b.txt
                    readlink /proc/self/fd/0 < /tmp/fdmeta_b.txt
                    """.trimIndent(),
                )
            assertEquals("/tmp/fdmeta_b.txt\n", r.stdout)
        }

    @Test fun stderrRedirToFile_setsFdPath() =
        runTest {
            val r =
                Kash().exec(
                    """
                    readlink /proc/self/fd/2 2> /tmp/fdmeta_c.txt
                    cat /tmp/fdmeta_c.txt
                    """.trimIndent(),
                )
            assertEquals("/tmp/fdmeta_c.txt\n", r.stdout)
        }

    @Test fun appendRedir_setsFdPath() =
        runTest {
            val r =
                Kash().exec(
                    """
                    readlink /proc/self/fd/1 >> /tmp/fdmeta_d.txt
                    cat /tmp/fdmeta_d.txt
                    """.trimIndent(),
                )
            assertEquals("/tmp/fdmeta_d.txt\n", r.stdout)
        }

    @Test fun highFdRedir_setsFdPath() =
        // Regression guard for the fd≥3 path that DOES already use installFd.
        runTest {
            val r =
                Kash().exec(
                    """
                    printf hi > /tmp/fdmeta_e.txt
                    exec 7</tmp/fdmeta_e.txt
                    readlink /proc/self/fd/7
                    """.trimIndent(),
                )
            assertEquals("/tmp/fdmeta_e.txt\n", r.stdout)
        }

    // ---------------- Issue 2: stable unique pipe inodes ----------------

    @Test fun differentPipesHaveDifferentInodes() =
        runTest {
            // Two unrelated procsubs → two AsyncPipes → distinct inodes.
            val r =
                Kash().exec(
                    """
                    A=<(true)
                    B=<(true)
                    readlink ${'$'}A
                    readlink ${'$'}B
                    """.trimIndent(),
                )
            val lines = r.stdout.lines().filter { it.startsWith("pipe:") }
            assertEquals(2, lines.size, "expected 2 pipe targets, got: ${r.stdout}")
            assertNotEquals(lines[0], lines[1], "expected distinct pipe inodes")
        }

    @Test fun pipeInodeShape() =
        // `pipe:[<digits>]` shape matches Linux.
        runTest {
            val r = Kash().exec("A=<(true); readlink \$A")
            assertTrue(
                r.stdout.trim().matches(Regex("pipe:\\[\\d+]")),
                "expected pipe:[<digits>], got: ${r.stdout}",
            )
        }

    @Test fun pipeInodeIsLargeAndNonObvious() =
        // Seeded with wall-clock-epoch, so the inode never lands on a small
        // collision-prone integer like `pipe:[2]` (which would suggest a
        // generic per-process counter to anyone introspecting). The first
        // pipe in a fresh process should already be in the millions-or-more
        // range, matching what a kernel-side inode would look like.
        runTest {
            val r = Kash().exec("A=<(true); readlink \$A")
            val inode =
                r.stdout
                    .trim()
                    .removePrefix("pipe:[")
                    .removeSuffix("]")
                    .toLong()
            assertTrue(inode > 1_000_000, "expected kernel-style inode > 1e6, got: $inode")
        }

    // ---------------- Issue 4: /dev/fd/N readlink ----------------

    @Test fun readlinkDevFdResolvesForFileRedir() =
        runTest {
            val r =
                Kash().exec(
                    """
                    printf x > /tmp/fdmeta_g.txt
                    exec 7</tmp/fdmeta_g.txt
                    readlink /dev/fd/7
                    """.trimIndent(),
                )
            assertEquals("/tmp/fdmeta_g.txt\n", r.stdout)
        }

    @Test fun readlinkDevFdResolvesForProcsub() =
        runTest {
            val r = Kash().exec("X=<(echo hi); readlink \$X")
            assertTrue(
                r.stdout.trim().startsWith("pipe:["),
                "expected pipe:[…], got: ${r.stdout}",
            )
        }

    @Test fun devFdIsClassifiedAsSymlink() =
        runTest {
            // `[ -L /dev/fd/N ]` is the canonical script-level "is it a
            // magic symlink" check. Should be true now, false before.
            val r =
                Kash().exec(
                    """
                    printf x > /tmp/fdmeta_h.txt
                    exec 7</tmp/fdmeta_h.txt
                    [ -L /dev/fd/7 ] && echo yes
                    """.trimIndent(),
                )
            assertEquals("yes\n", r.stdout)
        }

    @Test fun openingDevFdStillWorks() =
        // Regression guard: making /dev/fd/N a symlink must NOT break open()
        // through it. The synthetic pipe:[N] target should NOT be followed.
        runTest {
            val r = Kash().exec("cat <(echo open-still-works)")
            assertEquals("open-still-works\n", r.stdout)
        }
}
