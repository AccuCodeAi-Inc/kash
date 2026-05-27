package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end: dropping an ELF/Mach-O/PE binary into the VFS and trying to
 * exec it produces a friendly diagnostic instead of dropping into the
 * shell-script parser. Backed by the binfmt handler chain on the VM.
 */
class BinfmtNativeRejectIntegrationTest {
    @Test fun elfBinaryGetsFriendlyRejection() =
        runTest {
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/work")
            k.fs.writeBytes("/work/fake.elf", byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0, 1, 2, 3))
            val r = k.exec("/work/fake.elf")
            assertEquals(126, r.exitCode, "stderr=${r.stderr}")
            assertTrue(
                "kash is a VM and does not execute native code" in r.stderr,
                "expected native-reject diagnostic, got: ${r.stderr}",
            )
            assertTrue("ELF" in r.stderr, "expected ELF label, got: ${r.stderr}")
        }

    @Test fun machOBinaryRejected() =
        runTest {
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/work")
            k.fs.writeBytes(
                "/work/fake.macho",
                byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()),
            )
            val r = k.exec("/work/fake.macho")
            assertEquals(126, r.exitCode)
            assertTrue("Mach-O" in r.stderr, r.stderr)
        }

    @Test fun peBinaryRejected() =
        runTest {
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/work")
            k.fs.writeBytes("/work/fake.exe", byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00))
            val r = k.exec("/work/fake.exe")
            assertEquals(126, r.exitCode)
            assertTrue("PE/COFF" in r.stderr, r.stderr)
        }

    @Test fun shebanglessShellScriptStillRuns() =
        runTest {
            // Regression guard: no-shebang text falls through to the
            // shell-script fallback, exactly as before the chain was wired.
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/work")
            k.fs.writeBytes("/work/script", "echo from-shebangless\n".encodeToByteArray())
            val r = k.exec("/work/script")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("from-shebangless\n", r.stdout)
        }
}
