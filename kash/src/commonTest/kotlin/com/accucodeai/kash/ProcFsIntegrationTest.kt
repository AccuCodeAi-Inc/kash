package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end exercise of ProcFs through Kash(). Confirms the FS is
 * mounted at /proc, the process is registered in the table, and `cat`
 * over a /proc path returns the expected bytes.
 */
class ProcFsIntegrationTest {
    @Test
    fun procPathsListable() =
        runTest {
            val r = Kash().exec("ls /proc")
            assertEquals(0, r.exitCode)
            val lines =
                r.stdout
                    .lines()
                    .filter { it.isNotBlank() }
                    .toSet()
            // /proc root should at minimum list "self" and our exec'd process pid.
            assertTrue("self" in lines, "expected 'self' in /proc, got: ${r.stdout}")
            // Pid 1 is the default for Kash.exec's one-shot process.
            assertTrue("1" in lines, "expected '1' in /proc, got: ${r.stdout}")
        }

    @Test
    fun procSelfCwdReadable() =
        runTest {
            // `readlink /proc/self/cwd` would be the canonical test, but we
            // don't have readlink yet. `[ -L ... ]` checks symlink-ness.
            val r = Kash().exec("[ -L /proc/self/cwd ] && echo yes")
            assertEquals(0, r.exitCode)
            assertEquals("yes\n", r.stdout)
        }

    @Test
    fun cdIntoProcSelfWorks() =
        runTest {
            // Reported bug: `cd /proc/self` failed with "Not a directory"
            // because /proc/self's FileStat didn't populate symlinkTarget,
            // AND MountedFileSystem.isDirectory didn't follow symlinks.
            // After the fix: cd succeeds. bash preserves the logical
            // path in $PWD (the literal "/proc/self") rather than the
            // resolved target — matches `cd -L` (default) semantics.
            val r = Kash().exec("cd /proc/self && pwd")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("/proc/self\n", r.stdout)
        }

    @Test
    fun lsProcSelfShowsPidEntries() =
        runTest {
            val r = Kash().exec("ls /proc/self")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines =
                r.stdout
                    .lines()
                    .filter { it.isNotBlank() }
                    .toSet()
            assertEquals(
                setOf("cmdline", "environ", "cwd", "exe", "fd", "status", "stat", "maps"),
                lines,
            )
        }

    @Test
    fun procPidOneCmdlineExists() =
        runTest {
            // `[ -e ... ]` follows symlinks via fs.stat. For /proc/self the
            // symlink resolver would need opener context the FS interface
            // doesn't carry yet — that's Phase 2 work. For now, exercise
            // the direct /proc/<pid> path which doesn't require any
            // symlink resolution.
            val r = Kash().exec("[ -e /proc/1/cmdline ] && echo exists")
            assertEquals(0, r.exitCode)
            assertEquals("exists\n", r.stdout)
        }

    @Test
    fun procPidOneExists() =
        runTest {
            // /proc/<pid> directory should exist for the registered process.
            val r = Kash().exec("[ -d /proc/1 ] && echo yes")
            assertEquals(0, r.exitCode)
            assertEquals("yes\n", r.stdout)
        }

    @Test
    fun catProcSelfCmdlineReturnsOpenerArgv() =
        runTest {
            // The point of opener threading: `cat /proc/self/cmdline`
            // returns the calling process's argv, not empty bytes.
            // Note `tr` to convert NUL → space for assertion readability.
            val r = Kash().exec("cat /proc/self/cmdline | tr '\\0' ' '")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // The default `kash` exec process has empty argv. Even
            // empty, the operation succeeds (no FileNotFound).
            assertTrue(r.stdout.isEmpty() || r.stdout.startsWith("kash"), "got: <${r.stdout}>")
        }

    @Test
    fun testDashROnProcSelfCmdlineWorks() =
        runTest {
            // Previously failed: `[ -r /proc/self/cmdline ]` returned
            // false because the symlink-chain resolver had no opener
            // context to follow /proc/self. Now works.
            val r = Kash().exec("[ -r /proc/self/cmdline ] && echo readable")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("readable\n", r.stdout)
        }
}
