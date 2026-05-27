package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end proof that the `umask` builtin reaches the file-creating tools
 * (touch / mkdir / cp) through `ctx.process.umask`. These tools live in
 * `:tools:posix:*` so a bare `Kash()` core registry doesn't carry them —
 * the test composes the full app registry.
 */
class UmaskIntegrationTest {
    @Test fun umaskAffectsTouchMode() {
        runTest {
            val k = Kash(registry = standardRegistry())
            val r = k.exec("umask 027\ntouch /tmp/t")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val mode = k.fs.stat("/tmp/t").mode and 0xFFF
            // 0666 & ~027 = 0640
            assertEquals(0b110_100_000, mode)
        }
    }

    @Test fun umaskAffectsMkdirMode() {
        runTest {
            val k = Kash(registry = standardRegistry())
            val r = k.exec("umask 027\nmkdir /tmp/d")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val mode = k.fs.stat("/tmp/d").mode and 0xFFF
            // 0777 & ~027 = 0750
            assertEquals(0b111_101_000, mode)
        }
    }

    @Test fun mkdirMinusMOverridesUmask() {
        runTest {
            val k = Kash(registry = standardRegistry())
            val r = k.exec("umask 077\nmkdir -m 0755 /tmp/d2")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val mode = k.fs.stat("/tmp/d2").mode and 0xFFF
            assertEquals(0b111_101_101, mode)
        }
    }

    @Test fun teeHonorsUmaskOnNewFile() {
        runTest {
            val k = Kash(registry = standardRegistry())
            val r = k.exec("umask 077\necho hi | tee /tmp/teefile >/dev/null")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val mode = k.fs.stat("/tmp/teefile").mode and 0xFFF
            assertEquals(0b110_000_000, mode)
        }
    }

    @Test fun cpWithoutPreservePassesThroughUmask() {
        runTest {
            val k = Kash(registry = standardRegistry())
            // source has mode 0644 (default for touch under default umask 0022).
            // After cp with umask 077, dest is min(src, ~umask) = 0600.
            val r =
                k.exec(
                    """
                    touch /tmp/src
                    umask 077
                    cp /tmp/src /tmp/dst
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val mode = k.fs.stat("/tmp/dst").mode and 0xFFF
            assertEquals(0b110_000_000, mode)
        }
    }
}
