package com.accucodeai.kash.tools.chmod

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChmodCommandTest {
    private fun ctx(fs: InMemoryFs): Triple<CommandContext, Buffer, Buffer> {
        val out = Buffer()
        val err = Buffer()
        val c =
            bareCommandContext(
                fs,
                mutableMapOf(),
                "/work",
                Buffer().asSuspendSource(),
                out.asSuspendSink(),
                err.asSuspendSink(),
            )
        return Triple(c, out, err)
    }

    // ---- computeMode unit tests -------------------------------------------

    @Test fun octalReplacesEntireMode() {
        assertEquals(0b110_100_100, computeMode(0b111_111_111, "644"))
        assertEquals(0b111_101_101, computeMode(0, "755"))
        assertEquals(0b100_111_101_101, computeMode(0, "4755")) // setuid + 755
        assertEquals(0b001_111_111_111, computeMode(0, "1777")) // sticky + 777
    }

    @Test fun octalLeadingZeroAllowed() {
        assertEquals(0b110_100_100, computeMode(0, "0644"))
    }

    @Test fun symbolicAddUserExec() {
        // 644 → u+x → 744
        assertEquals(0b111_100_100, computeMode(0b110_100_100, "u+x"))
    }

    @Test fun symbolicRemoveOtherWrite() {
        // 666 → o-w → 664
        assertEquals(0b110_110_100, computeMode(0b110_110_110, "o-w"))
    }

    @Test fun symbolicAssignAll() {
        // anything → a=r → 444
        assertEquals(0b100_100_100, computeMode(0b111_111_111, "a=r"))
    }

    @Test fun symbolicBareOpMeansAll() {
        // 0 → +x → a+x → 111
        assertEquals(0b001_001_001, computeMode(0, "+x"))
    }

    @Test fun symbolicCommaSeparatedChained() {
        // 0 → u=rwx,go=rx → 755
        assertEquals(0b111_101_101, computeMode(0, "u=rwx,go=rx"))
    }

    @Test fun symbolicSetuidWithUser() {
        // 755 → u+s → 4755
        assertEquals(0b100_111_101_101, computeMode(0b111_101_101, "u+s"))
    }

    @Test fun symbolicSetgidWithGroup() {
        // 755 → g+s → 2755
        assertEquals(0b010_111_101_101, computeMode(0b111_101_101, "g+s"))
    }

    @Test fun symbolicStickyBit() {
        // 755 → +t → 1755
        assertEquals(0b001_111_101_101, computeMode(0b111_101_101, "+t"))
    }

    @Test fun symbolicMultipleClauses() {
        // 0 → u=rwx → 700 → g+r → 740 → o+x → 741
        assertEquals(0b111_100_001, computeMode(0, "u=rwx,g+r,o+x"))
    }

    @Test fun symbolicAssignClearsOldBitsInWhoSlots() {
        // 777 → g=r → 747 (group goes to read-only, user and other untouched)
        assertEquals(0b111_100_111, computeMode(0b111_111_111, "g=r"))
    }

    @Test fun emptyModeThrows() {
        try {
            computeMode(0, "")
            error("should have thrown")
        } catch (_: ModeParseError) {
        }
    }

    @Test fun unknownPermCharThrows() {
        try {
            computeMode(0, "u+z")
            error("should have thrown")
        } catch (_: ModeParseError) {
        }
    }

    // ---- end-to-end via the command ---------------------------------------

    @Test fun octalChmodWritesNewMode() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/file", "x".encodeToByteArray())
            val (c, _, _) = ctx(fs)
            val r = ChmodCommand().run(listOf("755", "file"), c)
            assertEquals(0, r.exitCode)
            assertEquals(0b111_101_101, fs.stat("/work/file").mode)
        }

    @Test fun symbolicAddExec() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/script", "#!/bin/sh".encodeToByteArray(), mode = 0b110_100_100) // 644
            val (c, _, _) = ctx(fs)
            val r = ChmodCommand().run(listOf("+x", "script"), c)
            assertEquals(0, r.exitCode)
            // 644 → a+x → 755
            assertEquals(0b111_101_101, fs.stat("/work/script").mode)
        }

    @Test fun multiplePathsAllUpdated() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a", "1".encodeToByteArray())
            fs.writeBytes("/work/b", "2".encodeToByteArray())
            val (c, _, _) = ctx(fs)
            val r = ChmodCommand().run(listOf("600", "a", "b"), c)
            assertEquals(0, r.exitCode)
            assertEquals(0b110_000_000, fs.stat("/work/a").mode)
            assertEquals(0b110_000_000, fs.stat("/work/b").mode)
        }

    @Test fun missingPathReportsErrorButContinues() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/exists", "x".encodeToByteArray())
            val (c, _, err) = ctx(fs)
            val r = ChmodCommand().run(listOf("755", "nope", "exists"), c)
            assertEquals(1, r.exitCode)
            assertTrue("No such file" in err.readString())
            // Existing file still got the new mode.
            assertEquals(0b111_101_101, fs.stat("/work/exists").mode)
        }

    @Test fun missingOperandFailsExitTwo() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, err) = ctx(fs)
            val r = ChmodCommand().run(emptyList(), c)
            assertEquals(2, r.exitCode)
            assertTrue("missing operand" in err.readString())
        }

    @Test fun missingPathOperandFailsExitTwo() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, err) = ctx(fs)
            val r = ChmodCommand().run(listOf("755"), c)
            assertEquals(2, r.exitCode)
            assertTrue("missing operand" in err.readString())
        }

    @Test fun invalidModeFailsExitTwo() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/f", "x".encodeToByteArray())
            val (c, _, err) = ctx(fs)
            val r = ChmodCommand().run(listOf("u+q", "f"), c)
            assertEquals(2, r.exitCode)
            assertTrue("invalid mode" in err.readString())
        }

    @Test fun recursiveWalksDirectory() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/top.txt", "t".encodeToByteArray())
            fs.mkdirs("/work/sub")
            fs.writeBytes("/work/sub/inner.txt", "i".encodeToByteArray())
            val (c, _, _) = ctx(fs)
            val r = ChmodCommand().run(listOf("-R", "700", "/work"), c)
            assertEquals(0, r.exitCode)
            assertEquals(0b111_000_000, fs.stat("/work").mode)
            assertEquals(0b111_000_000, fs.stat("/work/top.txt").mode)
            assertEquals(0b111_000_000, fs.stat("/work/sub").mode)
            assertEquals(0b111_000_000, fs.stat("/work/sub/inner.txt").mode)
        }

    @Test fun nonRecursiveLeavesChildrenAlone() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/top", "x".encodeToByteArray(), mode = 0b110_100_100) // 644
            fs.mkdirs("/work/sub")
            fs.writeBytes("/work/sub/inner", "y".encodeToByteArray(), mode = 0b110_100_100) // 644
            val (c, _, _) = ctx(fs)
            ChmodCommand().run(listOf("700", "/work"), c)
            assertEquals(0b111_000_000, fs.stat("/work").mode)
            // Children untouched.
            assertEquals(0b110_100_100, fs.stat("/work/top").mode)
            assertEquals(0b110_100_100, fs.stat("/work/sub/inner").mode)
        }

    @Test fun doubleDashTerminatesOptions() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/-R", "x".encodeToByteArray()) // name literally `-R`
            val (c, _, _) = ctx(fs)
            // Without `--`, `-R` would be the recursive flag. With `--`, it's the path.
            val r = ChmodCommand().run(listOf("755", "--", "-R"), c)
            assertEquals(0, r.exitCode)
            assertEquals(0b111_101_101, fs.stat("/work/-R").mode)
        }
}
