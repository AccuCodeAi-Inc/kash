package com.accucodeai.kash.magic

/**
 * The signature table, checked top-to-bottom by [KMagic.detect]; first match
 * wins. Order matters only where signatures overlap — more specific (longer,
 * or container sub-type) patterns must precede generic ones.
 *
 * Coverage follows the Wikipedia "List of file signatures" plus the everyday
 * formats `file(1)` recognizes. Formats that share a leading magic, or that
 * carry an embedded version, use a [Signature.refine] pass (see [Refiners]).
 */
internal object Signatures {
    private fun sig(
        hex: String,
        description: String,
        mime: String,
        vararg ext: String,
        offset: Int = 0,
        refine: ((ByteArray) -> MagicMatch?)? = null,
        validate: ((ByteArray) -> Boolean)? = null,
    ): Signature {
        val match = MagicMatch(description, mime, ext.toList())
        // A validator is just a refine that returns the static match or rejects.
        val effectiveRefine = refine ?: validate?.let { v -> { buf: ByteArray -> if (v(buf)) match else null } }
        return Signature(offset, bytePattern(hex), match, effectiveRefine)
    }

    val all: List<Signature> =
        listOf(
            // ----- images -------------------------------------------------
            sig("89 50 4E 47 0D 0A 1A 0A", "PNG image data", "image/png", "png"),
            sig("FF D8 FF", "JPEG image data", "image/jpeg", "jpg", "jpeg", validate = Validators::jpeg),
            sig("47 49 46 38 37 61", "GIF image data, version 87a", "image/gif", "gif"),
            sig("47 49 46 38 39 61", "GIF image data, version 89a", "image/gif", "gif"),
            sig("49 49 2A 00", "TIFF image data, little-endian", "image/tiff", "tif", "tiff"),
            sig("4D 4D 00 2A", "TIFF image data, big-endian", "image/tiff", "tif", "tiff"),
            sig("42 4D", "PC bitmap", "image/bmp", "bmp", validate = Validators::bmp),
            sig(
                "00 00 01 00",
                "MS Windows icon resource",
                "image/vnd.microsoft.icon",
                "ico",
                validate = Validators::ico,
            ),
            sig("00 00 02 00", "MS Windows cursor resource", "image/x-win-bitmap", "cur", validate = Validators::ico),
            sig("38 42 50 53", "Adobe Photoshop image", "image/vnd.adobe.photoshop", "psd"),
            // JPEG 2000
            sig("00 00 00 0C 6A 50 20 20 0D 0A 87 0A", "JPEG 2000 image", "image/jp2", "jp2"),
            // ----- RIFF containers (bytes 4..7 = length, wildcarded) -------
            sig("52 49 46 46 ?? ?? ?? ?? 57 41 56 45", "RIFF (little-endian) data, WAVE audio", "audio/x-wav", "wav"),
            sig("52 49 46 46 ?? ?? ?? ?? 41 56 49 20", "RIFF (little-endian) data, AVI", "video/x-msvideo", "avi"),
            sig("52 49 46 46 ?? ?? ?? ?? 57 45 42 50", "RIFF (little-endian) data, Web/P image", "image/webp", "webp"),
            // ----- documents ----------------------------------------------
            sig("25 50 44 46 2D", "PDF document", "application/pdf", "pdf", refine = Refiners::pdf),
            sig("25 21 50 53", "PostScript document text", "application/postscript", "ps"),
            sig("7B 5C 72 74 66 31", "Rich Text Format data", "text/rtf", "rtf"),
            sig(
                "D0 CF 11 E0 A1 B1 1A E1",
                "Composite Document File V2 (Microsoft Office)",
                "application/x-ole-storage",
                "doc",
                "xls",
                "ppt",
                "msi",
            ),
            // ----- compression / archives ---------------------------------
            sig("1F 8B", "gzip compressed data", "application/gzip", "gz", validate = Validators::gzip),
            sig("42 5A 68", "bzip2 compressed data", "application/x-bzip2", "bz2"),
            sig("FD 37 7A 58 5A 00", "XZ compressed data", "application/x-xz", "xz"),
            sig("28 B5 2F FD", "Zstandard compressed data", "application/zstd", "zst"),
            sig("04 22 4D 18", "LZ4 compressed data", "application/x-lz4", "lz4"),
            sig("4C 5A 49 50", "lzip compressed data", "application/x-lzip", "lz"),
            sig("89 4C 5A 4F 00 0D 0A 1A 0A", "lzop compressed data", "application/x-lzop", "lzo"),
            sig("1F 9D", "compress'd data (LZW)", "application/x-compress", "Z"),
            sig(
                "50 4B 03 04",
                "Zip archive data",
                "application/zip",
                "zip",
                "jar",
                "docx",
                "xlsx",
                "pptx",
                "epub",
                validate = Validators::zip,
            ),
            sig("50 4B 05 06", "Zip archive data (empty)", "application/zip", "zip"),
            sig("50 4B 07 08", "Zip archive data (spanned)", "application/zip", "zip"),
            sig("37 7A BC AF 27 1C", "7-zip archive data", "application/x-7z-compressed", "7z"),
            sig("4D 53 43 46", "Microsoft Cabinet archive data", "application/vnd.ms-cab-compressed", "cab"),
            sig("52 61 72 21 1A 07 00", "RAR archive data, v1.5", "application/x-rar", "rar"),
            sig("52 61 72 21 1A 07 01 00", "RAR archive data, v5", "application/x-rar", "rar"),
            sig("21 3C 61 72 63 68 3E", "Unix archive", "application/x-archive", "ar", "a", "deb"),
            sig("75 73 74 61 72", "POSIX tar archive", "application/x-tar", "tar", offset = 257),
            sig("ED AB EE DB", "RPM package", "application/x-rpm", "rpm"),
            sig("78 01", "zlib compressed data (no compression)", "application/zlib", "zlib"),
            sig("78 9C", "zlib compressed data (default)", "application/zlib", "zlib"),
            sig("78 DA", "zlib compressed data (best compression)", "application/zlib", "zlib"),
            // ----- executables --------------------------------------------
            sig("7F 45 4C 46", "ELF", "application/x-executable", "elf", "so", "o", refine = Refiners::elf),
            sig("CA FE BA BE", "Mach-O universal binary", "application/x-mach-binary", refine = Refiners::cafebabe),
            sig("FE ED FA CE", "Mach-O 32-bit", "application/x-mach-binary"),
            sig("FE ED FA CF", "Mach-O 64-bit", "application/x-mach-binary"),
            sig("CE FA ED FE", "Mach-O 32-bit (reverse byte order)", "application/x-mach-binary"),
            sig("CF FA ED FE", "Mach-O 64-bit (reverse byte order)", "application/x-mach-binary"),
            sig(
                "4D 5A",
                "MS-DOS / PE executable",
                "application/x-dosexec",
                "exe",
                "dll",
                "sys",
                validate = Validators::pe,
            ),
            sig("00 61 73 6D", "WebAssembly binary module", "application/wasm", "wasm"),
            // ----- code / VM ----------------------------------------------
            sig("64 65 78 0A", "Dalvik dex file", "application/x-dex", "dex"),
            // ----- audio / video ------------------------------------------
            sig("4F 67 67 53", "Ogg data", "application/ogg", "ogg", "oga", "ogv"),
            sig("66 4C 61 43", "FLAC audio bitstream data", "audio/x-flac", "flac"),
            sig("49 44 33", "Audio file with ID3 version 2", "audio/mpeg", "mp3", validate = Validators::id3),
            sig("FF FB", "MPEG ADTS, layer III", "audio/mpeg", "mp3", validate = Validators::mp3),
            sig("FF F3", "MPEG ADTS, layer III", "audio/mpeg", "mp3", validate = Validators::mp3),
            sig("FF F2", "MPEG ADTS, layer III", "audio/mpeg", "mp3", validate = Validators::mp3),
            sig("4D 54 68 64", "Standard MIDI data", "audio/midi", "mid", "midi"),
            sig("1A 45 DF A3", "Matroska / WebM data (EBML)", "video/x-matroska", "mkv", "webm"),
            sig("46 4C 56", "Macromedia Flash Video", "video/x-flv", "flv"),
            sig("66 74 79 70", "ISO Media", "video/mp4", "mp4", offset = 4, refine = Refiners::ftyp),
            // ----- packet captures ----------------------------------------
            sig("D4 C3 B2 A1", "tcpdump capture file (little-endian)", "application/vnd.tcpdump.pcap", "pcap"),
            sig("A1 B2 C3 D4", "tcpdump capture file (big-endian)", "application/vnd.tcpdump.pcap", "pcap"),
            sig("0A 0D 0D 0A", "pcapng capture file", "application/x-pcapng", "pcapng"),
            // ----- databases ----------------------------------------------
            sig(
                "53 51 4C 69 74 65 20 66 6F 72 6D 61 74 20 33 00",
                "SQLite 3.x database",
                "application/vnd.sqlite3",
                "db",
                "sqlite",
            ),
            // ----- fonts --------------------------------------------------
            sig("4F 54 54 4F", "OpenType font data", "font/otf", "otf"),
            sig("00 01 00 00 00", "TrueType font data", "font/ttf", "ttf"),
            sig("74 74 63 66", "TrueType font collection data", "font/collection", "ttc"),
            sig("77 4F 46 46", "Web Open Font Format", "font/woff", "woff"),
            sig("77 4F 46 32", "Web Open Font Format 2", "font/woff2", "woff2"),
            // ----- disk / medical -----------------------------------------
            sig("44 49 43 4D", "DICOM medical imaging data", "application/dicom", "dcm", offset = 128),
            sig("63 6F 6E 65 63 74 69 78", "Microsoft Virtual PC disk image", "application/x-vhd", "vhd"),
            sig("76 68 64 78 66 69 6C 65", "Microsoft Hyper-V disk image", "application/x-vhdx", "vhdx"),
            sig("51 46 49 FB", "QEMU QCOW2 disk image", "application/x-qemu-disk", "qcow2"),
            // ----- firmware / embedded filesystems (carving targets) ------
            sig("68 73 71 73", "Squashfs filesystem, little endian", "application/octet-stream", "sqsh"),
            sig("73 71 73 68", "Squashfs filesystem, big endian", "application/octet-stream", "sqsh"),
            sig("27 05 19 56", "u-boot legacy uImage", "application/octet-stream", "uimage"),
            sig("D0 0D FE ED", "Flattened device tree blob (DTB)", "application/octet-stream", "dtb"),
            sig("45 3D CD 28", "cramfs filesystem, little endian", "application/octet-stream", "cramfs"),
            sig("2D 72 6F 6D 31 66 73 2D", "romfs filesystem", "application/octet-stream", "romfs"),
            sig("41 4E 44 52 4F 49 44 21", "Android bootimg", "application/octet-stream", "img"),
            sig("48 44 52 30", "TRX firmware header", "application/octet-stream", "trx"),
            sig("55 42 49 23", "UBI erase-count header", "application/octet-stream", "ubi"),
            sig("31 18 10 06", "UBIFS filesystem node", "application/octet-stream", "ubifs"),
            // Validator-gated firmware formats (weak/short magics — would be
            // pure noise without field checks, see Validators).
            sig(
                "85 19",
                "JFFS2 filesystem, little endian",
                "application/octet-stream",
                "jffs2",
                validate = Validators::jffs2Le,
            ),
            sig(
                "19 85",
                "JFFS2 filesystem, big endian",
                "application/octet-stream",
                "jffs2",
                validate = Validators::jffs2Be,
            ),
            sig(
                "53 EF",
                "Linux ext2/3/4 filesystem data",
                "application/octet-stream",
                "ext",
                offset = 1080,
                validate = Validators::ext,
            ),
            sig("5D", "LZMA compressed data", "application/x-lzma", "lzma", validate = Validators::lzma),
            // ----- text containers ----------------------------------------
            sig("EF BB BF", "UTF-8 Unicode (with BOM) text", "text/plain; charset=utf-8", "txt"),
            sig("FF FE 00 00", "Little-endian UTF-32 Unicode text", "text/plain; charset=utf-32le", "txt"),
            sig("00 00 FE FF", "Big-endian UTF-32 Unicode text", "text/plain; charset=utf-32be", "txt"),
            sig("FF FE", "Little-endian UTF-16 Unicode text", "text/plain; charset=utf-16le", "txt"),
            sig("FE FF", "Big-endian UTF-16 Unicode text", "text/plain; charset=utf-16be", "txt"),
            sig("3C 3F 78 6D 6C", "XML 1.0 document text", "text/xml", "xml"),
            sig("23 21", "script text executable", "text/x-script", refine = Refiners::shebang),
        )

    /**
     * For each possible leading byte (0..255), the signatures (in table order)
     * that could match a buffer starting with that byte. A signature is a
     * candidate when it does not anchor at offset 0, or its first pattern byte
     * is a wildcard, or its first pattern byte equals the leading byte.
     *
     * This is the cheap alternative to a trie: it cuts [KMagic.detect] from a
     * full table scan to a short per-byte list while preserving first-match
     * ordering, and it sidesteps the per-offset / wildcard branching a trie
     * would force.
     */
    private val byFirstByte: Array<List<Signature>> =
        Array(256) { first ->
            all.filter { s ->
                s.offset != 0 || s.pattern.isEmpty() || s.pattern[0] < 0 || s.pattern[0] == first
            }
        }

    fun candidatesFor(firstByte: Int): List<Signature> = byFirstByte[firstByte and 0xff]

    /**
     * Index for [KMagic.scan]: keyed by each signature's first concrete magic
     * byte (the byte at its internal [Signature.offset]). When sliding over a
     * blob, the byte at absolute position `q` is a candidate magic-start, so we
     * look up signatures whose lead byte equals it and back-compute the
     * embedded item's start as `q - offset`.
     *
     * Signatures whose pattern leads with a wildcard ([Signature.leadByte] < 0)
     * cannot be indexed this way; [wildcardLead] holds them for a per-position
     * fallback. (Currently empty — all signatures lead with a concrete byte.)
     */
    private val byLeadByte: Array<List<Signature>> =
        Array(256) { b -> all.filter { it.leadByte == b } }

    val wildcardLead: List<Signature> = all.filter { it.leadByte < 0 }

    fun scanCandidatesFor(magicByte: Int): List<Signature> = byLeadByte[magicByte and 0xff]

    /** Largest internal offset of any signature — the backward reach a scan needs. */
    val maxOffset: Int = all.maxOf { it.offset }

    /** Longest pattern — the forward reach needed to test a match. */
    val maxPatternLen: Int = all.maxOf { it.pattern.size }
}
