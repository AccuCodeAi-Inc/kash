package com.accucodeai.kash.magic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun bytes(hex: String): ByteArray {
    val toks = hex.trim().split(Regex("\\s+"))
    return ByteArray(toks.size) { toks[it].toInt(16).toByte() }
}

class KMagicTest {
    @Test
    fun png() {
        val m = KMagic.detect(bytes("89 50 4E 47 0D 0A 1A 0A 00 00"))
        assertEquals("PNG image data", m?.description)
        assertEquals("image/png", m?.mime)
        assertEquals(listOf("png"), m?.extensions)
    }

    @Test
    fun jpeg() {
        assertEquals("JPEG image data", KMagic.detect(bytes("FF D8 FF E0 00 10"))?.description)
    }

    @Test
    fun gif87vs89() {
        assertEquals("GIF image data, version 87a", KMagic.detect(bytes("47 49 46 38 37 61"))?.description)
        assertEquals("GIF image data, version 89a", KMagic.detect(bytes("47 49 46 38 39 61"))?.description)
    }

    @Test
    fun riffContainersDisambiguate() {
        assertEquals(
            "RIFF (little-endian) data, WAVE audio",
            KMagic.detect(bytes("52 49 46 46 24 08 00 00 57 41 56 45"))?.description,
        )
        assertEquals(
            "RIFF (little-endian) data, Web/P image",
            KMagic.detect(bytes("52 49 46 46 FF FF FF FF 57 45 42 50"))?.description,
        )
        assertEquals(
            "RIFF (little-endian) data, AVI",
            KMagic.detect(bytes("52 49 46 46 00 00 00 00 41 56 49 20"))?.description,
        )
    }

    @Test
    fun pdfVersionExtraction() {
        assertEquals("PDF document, version 1.7", KMagic.detect(bytes("25 50 44 46 2D 31 2E 37 0A"))?.description)
    }

    @Test
    fun elfClassEndiannessType() {
        // 64-bit (2), LSB (1), e_type=2 (executable) at offset 16 LE.
        val elf = bytes("7F 45 4C 46 02 01 01 00 00 00 00 00 00 00 00 00 02 00 3E 00")
        assertEquals("ELF 64-bit LSB executable", KMagic.detect(elf)?.description)
    }

    @Test
    fun elfSharedObject() {
        val elf = bytes("7F 45 4C 46 02 01 01 00 00 00 00 00 00 00 00 00 03 00 3E 00")
        assertEquals("ELF 64-bit LSB shared object", KMagic.detect(elf)?.description)
    }

    @Test
    fun cafebabeJavaVsMachO() {
        // Java class: major version 52 (Java 8) at bytes 6..7.
        val java = bytes("CA FE BA BE 00 00 00 34 00")
        assertEquals("compiled Java class data, version 52.0", KMagic.detect(java)?.description)
        // Mach-O fat: arch count (small, out of the 45..127 version range) at 6..7.
        val fat = bytes("CA FE BA BE 00 00 00 02")
        assertEquals("Mach-O universal binary", KMagic.detect(fat)?.description)
    }

    @Test
    fun tarAtOffset257() {
        val buf = ByteArray(300)
        // "ustar" at offset 257
        "ustar".encodeToByteArray().copyInto(buf, 257)
        assertEquals("POSIX tar archive", KMagic.detect(buf)?.description)
    }

    @Test
    fun dicomAtOffset128() {
        val buf = ByteArray(140)
        "DICM".encodeToByteArray().copyInto(buf, 128)
        // Offset-0 bytes are all zero → no offset-0 signature; DICOM is in the
        // "non-zero offset" candidate set regardless of first byte.
        assertEquals("DICOM medical imaging data", KMagic.detect(buf)?.description)
    }

    @Test
    fun ftypBrands() {
        assertEquals(
            "ISO Media, HEIF image",
            KMagic.detect(bytes("00 00 00 18 66 74 79 70 68 65 69 63"))?.description,
        )
        assertTrue(
            KMagic.detect(bytes("00 00 00 18 66 74 79 70 69 73 6F 6D"))!!.description.startsWith("ISO Media"),
        )
    }

    @Test
    fun shebangInterpreters() {
        assertEquals(
            "Bourne-Again shell script, ASCII text executable",
            KMagic.detect("#!/bin/bash\necho hi\n".encodeToByteArray())?.description,
        )
        assertEquals(
            "Python script, ASCII text executable",
            KMagic.detect("#!/usr/bin/python3\n".encodeToByteArray())?.description,
        )
        assertEquals(
            "a python3 script",
            KMagic.detect("#!/usr/bin/env python3\n".encodeToByteArray())?.description,
        )
    }

    @Test
    fun gzipBzip2Xz() {
        assertEquals("gzip compressed data", KMagic.detect(bytes("1F 8B 08"))?.description)
        assertEquals("bzip2 compressed data", KMagic.detect(bytes("42 5A 68 39"))?.description)
        assertEquals("XZ compressed data", KMagic.detect(bytes("FD 37 7A 58 5A 00"))?.description)
    }

    @Test
    fun zipFamily() {
        assertEquals("Zip archive data", KMagic.detect(bytes("50 4B 03 04"))?.description)
        assertEquals("Zip archive data (empty)", KMagic.detect(bytes("50 4B 05 06"))?.description)
    }

    @Test
    fun tier1FirmwareAndForensicsMagics() {
        assertEquals("lzop compressed data", KMagic.detect(bytes("89 4C 5A 4F 00 0D 0A 1A 0A"))?.description)
        assertEquals("Microsoft Cabinet archive data", KMagic.detect(bytes("4D 53 43 46 00 00"))?.description)
        assertEquals("QEMU QCOW2 disk image", KMagic.detect(bytes("51 46 49 FB 00 00 00 03"))?.description)
        assertEquals("UBI erase-count header", KMagic.detect(bytes("55 42 49 23 01 00"))?.description)
        assertEquals("UBIFS filesystem node", KMagic.detect(bytes("31 18 10 06 00 00"))?.description)
        assertEquals(
            "tcpdump capture file (little-endian)",
            KMagic.detect(bytes("D4 C3 B2 A1 02 00 04 00"))?.description,
        )
        assertEquals("tcpdump capture file (big-endian)", KMagic.detect(bytes("A1 B2 C3 D4 00 02 00 04"))?.description)
        assertEquals("pcapng capture file", KMagic.detect(bytes("0A 0D 0D 0A 1C 00 00 00"))?.description)
    }

    @Test
    fun lzopNotConfusedWithPng() {
        // Both lead with 0x89; second byte disambiguates.
        assertEquals("PNG image data", KMagic.detect(bytes("89 50 4E 47 0D 0A 1A 0A"))?.description)
        assertEquals("lzop compressed data", KMagic.detect(bytes("89 4C 5A 4F 00 0D 0A 1A 0A"))?.description)
    }

    @Test
    fun unknownReturnsNull() {
        assertNull(KMagic.detect(bytes("DE AD BE EF C0 DE")))
    }

    @Test
    fun textClassification() {
        assertEquals("ASCII text", KMagic.classifyText("hello world\n".encodeToByteArray()).description)
        assertEquals(
            "ASCII text, with CRLF line terminators",
            KMagic.classifyText("a\r\nb\r\n".encodeToByteArray()).description,
        )
        assertEquals("UTF-8 Unicode text", KMagic.classifyText("héllo wörld".encodeToByteArray()).description)
        assertEquals("data", KMagic.classifyText(bytes("00 01 02 03 7F 00")).description)
    }

    @Test
    fun identifyEmptyAndFallback() {
        assertEquals("empty", KMagic.identify(ByteArray(0)).description)
        assertEquals("PNG image data", KMagic.identify(bytes("89 50 4E 47 0D 0A 1A 0A")).description)
        assertEquals("ASCII text", KMagic.identify("plain\n".encodeToByteArray()).description)
        assertEquals("data", KMagic.identify(bytes("00 FF 00 FF 00 FF 01 02")).description)
    }

    @Test
    fun firstByteIndexPreservesTableOrder() {
        // Both empty-zip (50 4b 05 06) and zip (50 4b 03 04) start with 0x50.
        // The candidate list for 0x50 must keep them in table order so the
        // right one wins.
        assertEquals("Zip archive data", KMagic.detect(bytes("50 4B 03 04"))?.description)
        assertEquals("Zip archive data (spanned)", KMagic.detect(bytes("50 4B 07 08"))?.description)
    }

    @Test
    fun scanFindsEmbeddedSignaturesAtAnyOffset() {
        // [junk][PNG@8][junk][gzip@40]
        val png = bytes("89 50 4E 47 0D 0A 1A 0A")
        val gz = bytes("1F 8B 08")
        val blob = ByteArray(64)
        png.copyInto(blob, 8)
        gz.copyInto(blob, 40)
        val hits = KMagic.scan(blob)
        val byOff = hits.associate { it.offset to it.match.description }
        assertEquals("PNG image data", byOff[8L])
        assertEquals("gzip compressed data", byOff[40L])
    }

    @Test
    fun scanReportsSortedOffsets() {
        val blob = ByteArray(300)
        // tar magic "ustar" lands at base+257; place so the item starts at 0.
        "ustar".encodeToByteArray().copyInto(blob, 257)
        // a valid gzip (deflate, no flags) at offset 100
        bytes("1F 8B 08 00").copyInto(blob, 100)
        val hits = KMagic.scan(blob)
        // tar item starts at 0 (257 - 257), gzip at 100 → sorted ascending.
        assertEquals(listOf(0L, 100L), hits.map { it.offset })
        assertEquals("POSIX tar archive", hits[0].match.description)
    }

    @Test
    fun scanRefinesEmbeddedElf() {
        val blob = ByteArray(64)
        bytes("7F 45 4C 46 02 01 01 00 00 00 00 00 00 00 00 00 02 00 3E 00").copyInto(blob, 16)
        // Byte-scanning yields coincidental hits too (e.g. a cursor magic
        // inside the ELF e_type field), so assert the ELF hit is present rather
        // than an exact count.
        val hits = KMagic.scan(blob)
        val elf = hits.singleOrNull { it.match.description.startsWith("ELF") }
        assertEquals(16L, elf?.offset)
        assertEquals("ELF 64-bit LSB executable", elf?.match?.description)
    }

    @Test
    fun scanEmptyBlob() {
        assertTrue(KMagic.scan(ByteArray(0)).isEmpty())
    }

    // ----- validators -------------------------------------------------------

    @Test
    fun gzipValidatorRejectsBadMethod() {
        // method byte 0x00 (not deflate) → not gzip; flags-reserved set → reject.
        assertNull(KMagic.detect(bytes("1F 8B 00 00 00 00")))
        assertEquals("gzip compressed data", KMagic.detect(bytes("1F 8B 08 00 00 00"))?.description)
        // reserved flag bits set → reject
        assertNull(KMagic.detect(bytes("1F 8B 08 E0 00 00")))
    }

    @Test
    fun zipValidatorChecksMethod() {
        // method 8 (deflate) ok; method 7 (unused) rejected.
        val ok = bytes("50 4B 03 04 14 00 00 00 08 00 00 00")
        assertEquals("Zip archive data", KMagic.detect(ok)?.description)
        val bad = bytes("50 4B 03 04 14 00 00 00 07 00 00 00")
        assertNull(KMagic.detect(bad))
        // too short to validate → accepted (benefit of the doubt)
        assertEquals("Zip archive data", KMagic.detect(bytes("50 4B 03 04"))?.description)
    }

    @Test
    fun jffs2NodeValidation() {
        // LE magic 85 19, nodetype 0xE002 (inode) → valid
        assertEquals("JFFS2 filesystem, little endian", KMagic.detect(bytes("85 19 02 E0"))?.description)
        // bogus nodetype → reject
        assertNull(KMagic.detect(bytes("85 19 AB CD")))
        // BE magic 19 85, nodetype 0x2003 (cleanmarker)
        assertEquals("JFFS2 filesystem, big endian", KMagic.detect(bytes("19 85 20 03"))?.description)
    }

    @Test
    fun lzmaPropsAndDict() {
        // props 0x5D, dict 0x00010000 (LE 00 00 01 00) → valid
        assertEquals("LZMA compressed data", KMagic.detect(bytes("5D 00 00 01 00 00 00 00 00"))?.description)
        // valid props but junk dict size → reject
        assertNull(KMagic.detect(bytes("5D 11 22 33 44 00 00 00 00")))
    }

    @Test
    fun extSuperblockValidation() {
        val blob = ByteArray(1200)
        // s_magic 0xEF53 (LE 53 EF) at offset 1080
        bytes("53 EF").copyInto(blob, 1080)
        // superblock at 1024: log_block_size(=+24)=2, state(+58)=1, errors(+60)=1, rev(+76)=1
        blob[1024 + 24] = 2
        blob[1024 + 58] = 1
        blob[1024 + 60] = 1
        blob[1024 + 76] = 1
        assertEquals("Linux ext2/3/4 filesystem data", KMagic.detect(blob)?.description)
        // break the block-size field → reject
        blob[1024 + 24] = 99
        assertNull(KMagic.detect(blob))
    }

    @Test
    fun mp3FrameValidation() {
        // FF FB + b2=0x90: bitrate idx 9 (ok), sample-rate idx 0 (ok) → valid.
        assertEquals("MPEG ADTS, layer III", KMagic.detect(bytes("FF FB 90 00"))?.description)
        // bitrate index 15 (bad) → reject
        assertNull(KMagic.detect(bytes("FF FB F0 00")))
        // sample-rate index 3 (reserved) → reject
        assertNull(KMagic.detect(bytes("FF FB 0C 00")))
    }

    @Test
    fun peRequiresPeHeader() {
        // MZ with e_lfanew=0x40 pointing at "PE\0\0" → valid.
        val pe = ByteArray(0x48)
        bytes("4D 5A").copyInto(pe, 0)
        pe[0x3c] = 0x40 // e_lfanew = 0x40 (LE)
        bytes("50 45 00 00").copyInto(pe, 0x40)
        assertEquals("MS-DOS / PE executable", KMagic.detect(pe)?.description)
        // MZ with no PE header in range → reject
        val bare = ByteArray(0x48)
        bytes("4D 5A").copyInto(bare, 0)
        bare[0x3c] = 0x40
        assertNull(KMagic.detect(bare))
    }

    @Test
    fun bmpReservedFields() {
        // "BM", size 100 @2, reserved @6-9 = 0 → valid.
        val ok = ByteArray(40)
        bytes("42 4D").copyInto(ok, 0)
        ok[2] = 100
        assertEquals("PC bitmap", KMagic.detect(ok)?.description)
        // non-zero reserved field → reject
        val bad = ByteArray(40)
        bytes("42 4D").copyInto(bad, 0)
        bad[2] = 100
        bad[6] = 1
        assertNull(KMagic.detect(bad))
    }

    @Test
    fun icoCountValidation() {
        // count=1 @4, reserved @9=0 → valid icon.
        val ok = ByteArray(22)
        bytes("00 00 01 00").copyInto(ok, 0)
        ok[4] = 1
        assertEquals("MS Windows icon resource", KMagic.detect(ok)?.description)
        // count=0 (all-zero region) → reject — kills the coincidental-cursor noise.
        assertNull(KMagic.detect(ByteArray(22)))
    }

    @Test
    fun jpegMarkerByte() {
        assertEquals("JPEG image data", KMagic.detect(bytes("FF D8 FF E1 00 10"))?.description)
        // 4th byte 0x00 is not a marker → reject
        assertNull(KMagic.detect(bytes("FF D8 FF 00 00 10")))
    }

    @Test
    fun id3VersionByte() {
        assertEquals("Audio file with ID3 version 2", KMagic.detect(bytes("49 44 33 03 00"))?.description)
        assertNull(KMagic.detect(bytes("49 44 33 FF 00")))
    }

    // ----- measure (exact extraction length) --------------------------------

    @Test
    fun measurePngWalksToIend() {
        // sig(8) + IHDR chunk(len=13) + IEND chunk(len=0), with trailing junk.
        val png =
            bytes("89 50 4E 47 0D 0A 1A 0A") +
                bytes("00 00 00 0D") + "IHDR".encodeToByteArray() + ByteArray(13) + bytes("00 00 00 00") +
                bytes("00 00 00 00") + "IEND".encodeToByteArray() + bytes("AE 42 60 82")
        val blob = png + ByteArray(50) // trailing junk after the PNG
        val len = KMagic.measure(blob, 0, MagicMatch("PNG image data", "image/png", listOf("png")))
        assertEquals(png.size.toLong(), len)
    }

    @Test
    fun measureBmpUsesSizeField() {
        val blob = ByteArray(100)
        bytes("42 4D").copyInto(blob, 0) // "BM"
        // file size 64 at offset 2 (LE)
        blob[2] = 64
        val len = KMagic.measure(blob, 0, MagicMatch("PC bitmap", "image/bmp", listOf("bmp")))
        assertEquals(64L, len)
    }

    @Test
    fun measureUnknownReturnsNull() {
        assertNull(
            KMagic.measure(ByteArray(100), 0, MagicMatch("gzip compressed data", "application/gzip", listOf("gz"))),
        )
    }

    @Test
    fun peekBytesCoversDeepestSignature() {
        // The deepest signature is tar at offset 257; PEEK_BYTES must exceed it.
        assertTrue(KMagic.PEEK_BYTES > 257 + 8)
    }
}
