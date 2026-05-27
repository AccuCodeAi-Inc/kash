package com.accucodeai.kash.tools.dd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DdOperandsTest {
    @Test fun `bs sets ibs and obs`() {
        val o = parseDdOperands(listOf("bs=4096"))
        assertEquals(4096L, o.ibs)
        assertEquals(4096L, o.obs)
    }

    @Test fun `if and of`() {
        val o = parseDdOperands(listOf("if=/in", "of=/out"))
        assertEquals("/in", o.input)
        assertEquals("/out", o.output)
    }

    @Test fun `k suffix is 1024`() {
        assertEquals(1024L, parseSize("1k"))
        assertEquals(1024L, parseSize("1K"))
    }

    @Test fun `kB suffix is 1000`() {
        assertEquals(1000L, parseSize("1kB"))
    }

    @Test fun `M is mebibyte`() {
        assertEquals(1024L * 1024L, parseSize("1M"))
    }

    @Test fun `MB is megabyte`() {
        assertEquals(1000L * 1000L, parseSize("1MB"))
    }

    @Test fun `G suffix`() {
        assertEquals(1024L * 1024L * 1024L, parseSize("1G"))
    }

    @Test fun `c w b suffixes`() {
        assertEquals(1L, parseSize("1c"))
        assertEquals(2L, parseSize("1w"))
        assertEquals(512L, parseSize("1b"))
    }

    @Test fun `xN multiplies`() {
        assertEquals(1024L, parseSize("2x512"))
        assertEquals(6L, parseSize("2x3"))
        assertEquals(24L, parseSize("2x3x4"))
    }

    @Test fun `hex and octal numbers`() {
        assertEquals(255L, parseSize("0xff"))
        assertEquals(8L, parseSize("010"))
    }

    @Test fun `count parses`() {
        val o = parseDdOperands(listOf("count=10"))
        assertEquals(10L, o.count)
    }

    @Test fun `skip and seek parse`() {
        val o = parseDdOperands(listOf("skip=2", "seek=3"))
        assertEquals(2L, o.skip)
        assertEquals(3L, o.seek)
    }

    @Test fun `conv with multiple flags`() {
        val o = parseDdOperands(listOf("conv=ucase,swab"))
        assertTrue(DdConvFlag.UCASE in o.conv)
        assertTrue(DdConvFlag.SWAB in o.conv)
    }

    @Test fun `conv lcase and ucase together rejected`() {
        assertFailsWith<DdOperandException> {
            parseDdOperands(listOf("conv=lcase,ucase"))
        }
    }

    @Test fun `conv block and unblock together rejected`() {
        assertFailsWith<DdOperandException> {
            parseDdOperands(listOf("conv=block,unblock", "cbs=8"))
        }
    }

    @Test fun `unknown operand errors`() {
        assertFailsWith<DdOperandException> { parseDdOperands(listOf("bogus=1")) }
    }

    @Test fun `argument without equals errors`() {
        assertFailsWith<DdOperandException> { parseDdOperands(listOf("garbage")) }
    }

    @Test fun `bs zero rejected`() {
        assertFailsWith<DdOperandException> { parseDdOperands(listOf("bs=0")) }
    }

    @Test fun `negative count rejected`() {
        // "-1" parses as numeric -1 then requireNonNegative rejects.
        // parseSize doesn't currently accept negatives; treat both as errors.
        assertFailsWith<DdOperandException> { parseDdOperands(listOf("count=-1")) }
    }

    @Test fun `invalid suffix errors`() {
        assertFailsWith<DdOperandException> { parseDdOperands(listOf("bs=4Q")) }
    }

    @Test fun `iflag and oflag accept known values`() {
        val o = parseDdOperands(listOf("iflag=fullblock,sync", "oflag=append"))
        assertTrue(DdIoFlag.FULLBLOCK in o.iflag)
        assertTrue(DdIoFlag.SYNC in o.iflag)
        assertTrue(DdIoFlag.APPEND in o.oflag)
    }

    @Test fun `unknown iflag errors`() {
        assertFailsWith<DdOperandException> { parseDdOperands(listOf("iflag=bogus")) }
    }

    @Test fun `status values parse`() {
        assertEquals(DdStatus.NONE, parseDdOperands(listOf("status=none")).status)
        assertEquals(DdStatus.NOXFER, parseDdOperands(listOf("status=noxfer")).status)
        assertEquals(DdStatus.PROGRESS, parseDdOperands(listOf("status=progress")).status)
    }

    @Test fun `status invalid errors`() {
        assertFailsWith<DdOperandException> { parseDdOperands(listOf("status=loud")) }
    }

    @Test fun `defaults are POSIX`() {
        val o = parseDdOperands(emptyList())
        assertEquals(512L, o.ibs)
        assertEquals(512L, o.obs)
        assertEquals(0L, o.skip)
        assertEquals(0L, o.seek)
        assertEquals(0L, o.cbs)
        assertEquals(null, o.count)
        assertEquals(DdStatus.DEFAULT, o.status)
    }

    @Test fun `conv list with empty entries tolerated`() {
        val o = parseDdOperands(listOf("conv=ucase,,swab"))
        assertTrue(DdConvFlag.UCASE in o.conv)
        assertTrue(DdConvFlag.SWAB in o.conv)
    }
}
