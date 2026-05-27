package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the new `mount` builtin (introspection) and `hash` intrinsic
 * (POSIX [XCU §hash](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/hash.html))
 * end-to-end through [Kash.exec].
 */
class MountAndHashTest {
    // -------- mount --------

    @Test fun mount_lists_default_mounts() =
        runTest {
            val r = Kash().exec("mount")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // Default bootstrap mounts: /, /usr/bin, /bin
            assertTrue(r.stdout.contains("/usr/bin"), "got: ${r.stdout}")
            assertTrue(r.stdout.contains("/bin"), "got: ${r.stdout}")
            assertTrue(r.stdout.contains("system-bin"), "got: ${r.stdout}")
            assertTrue(r.stdout.contains("user"), "got: ${r.stdout}")
            assertTrue(r.stdout.contains("(ro,"), "RO marker missing: ${r.stdout}")
            assertTrue(r.stdout.contains("(rw,"), "RW marker missing: ${r.stdout}")
        }

    @Test fun mount_dash_t_filters_by_label() =
        runTest {
            val r = Kash().exec("mount -t SYSTEM_BIN")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // /usr/bin and /bin both labeled SYSTEM_BIN.
            assertTrue(r.stdout.contains("/usr/bin"))
            assertTrue(r.stdout.contains("/bin"))
            // User-labeled mount NOT in output.
            assertTrue(!r.stdout.contains(" user "), "filter leaked: ${r.stdout}")
        }

    @Test fun mount_dash_t_unknown_label_errors() =
        runTest {
            val r = Kash().exec("mount -t NOPE")
            assertEquals(2, r.exitCode)
            assertTrue(r.stderr.contains("unknown label"))
        }

    @Test fun mount_help_flag() =
        runTest {
            val r = Kash().exec("mount --help")
            assertEquals(0, r.exitCode)
            assertTrue(r.stdout.contains("Usage: mount"))
        }

    // -------- hash --------

    @Test fun hash_empty_after_creation() =
        runTest {
            val r = Kash().exec("hash")
            assertEquals(0, r.exitCode)
            assertEquals("hash: hash table empty\n", r.stdout)
        }

    @Test fun hash_does_not_track_builtins() =
        runTest {
            // bash convention (and ours): `hash` tracks only TOOLs / scripts —
            // built-ins like echo are reached without a PATH walk worth caching.
            val r = Kash().exec("echo seed >/dev/null; hash")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("hash: hash table empty\n", r.stdout)
        }

    @Test fun hash_dash_r_clears_cache() =
        runTest {
            val r =
                Kash().exec(
                    """
                    echo seed >/dev/null
                    hash -r
                    hash
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("hash: hash table empty\n", r.stdout)
        }

    @Test fun hash_explicit_name_resolves_into_cache() =
        runTest {
            val r =
                Kash().exec(
                    """
                    hash -r
                    hash echo
                    hash
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertTrue(r.stdout.contains("echo"), "echo missing: ${r.stdout}")
        }

    @Test fun hash_unknown_name_exits_one() =
        runTest {
            val r = Kash().exec($$"hash no-such-tool 2>/dev/null; echo rc=$?")
            assertTrue(r.stdout.trim().endsWith("rc=1"), "got: ${r.stdout}")
        }

    @Test fun hash_path_qualified_argument_rejected() =
        runTest {
            val r = Kash().exec($$"hash /usr/bin/echo 2>&1; echo rc=$?")
            assertTrue(r.stdout.contains("hash a name"), "missing hint: ${r.stdout}")
        }
}
