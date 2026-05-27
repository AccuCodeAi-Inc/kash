package com.accucodeai.kash.magic

/**
 * Second-pass refiners for signatures that carry an embedded version or that
 * share a leading magic with another format. Each takes the full prefix and
 * returns a more specific [MagicMatch], or null to reject the match (so
 * [KMagic.detect] falls through to the next signature).
 */
internal object Refiners {
    /** `%PDF-1.4` → capture the version digits. */
    fun pdf(buf: ByteArray): MagicMatch {
        val ver = StringBuilder()
        var i = 5
        while (i < buf.size && i < 12) {
            val c = (buf[i].toInt() and 0xff).toChar()
            if (c.isDigit() || c == '.') ver.append(c) else break
            i++
        }
        val v = if (ver.isNotEmpty()) ", version $ver" else ""
        return MagicMatch("PDF document$v", "application/pdf", listOf("pdf"))
    }

    /** ELF header: class (32/64), data (LSB/MSB), e_type. */
    fun elf(buf: ByteArray): MagicMatch {
        if (buf.size < 18) return MagicMatch("ELF", "application/x-executable", listOf("elf"))
        val cls = if ((buf[4].toInt() and 0xff) == 2) "64-bit" else "32-bit"
        val msb = (buf[5].toInt() and 0xff) == 2
        val data = if (msb) "MSB" else "LSB"
        // e_type is a 2-byte value at offset 16 in the declared endianness.
        val b16 = buf[16].toInt() and 0xff
        val b17 = buf[17].toInt() and 0xff
        val eType = if (msb) (b16 shl 8) or b17 else (b17 shl 8) or b16
        val typeName =
            when (eType) {
                1 -> "relocatable"
                2 -> "executable"
                3 -> "shared object"
                4 -> "core file"
                else -> "unknown type"
            }
        return MagicMatch("ELF $cls $data $typeName", "application/x-executable", listOf("elf", "so", "o"))
    }

    /**
     * `CA FE BA BE` is shared by Mach-O universal ("fat") binaries and Java
     * `.class` files. Java's next 4 bytes are minor||major version; a sane
     * major (45..127, i.e. JDK 1.1..) means it's a class file. Mach-O fat
     * binaries instead store an architecture count there, typically small but
     * not in that range for real files — we treat a plausible major version as
     * the discriminator, matching what `file` does in practice.
     */
    fun cafebabe(buf: ByteArray): MagicMatch {
        if (buf.size >= 8) {
            val major = ((buf[6].toInt() and 0xff) shl 8) or (buf[7].toInt() and 0xff)
            if (major in 45..127) {
                return MagicMatch(
                    "compiled Java class data, version $major.0",
                    "application/x-java-applet",
                    listOf("class"),
                )
            }
        }
        return MagicMatch("Mach-O universal binary", "application/x-mach-binary", emptyList())
    }

    /** ISO Base Media `ftyp` box at offset 4; brand at offset 8 picks the type. */
    fun ftyp(buf: ByteArray): MagicMatch {
        if (buf.size < 12) return MagicMatch("ISO Media", "video/mp4", listOf("mp4"))
        val brand =
            buildString {
                for (i in 8 until 12) append((buf[i].toInt() and 0xff).toChar())
            }
        return when (brand.trimEnd()) {
            "3gp4", "3gp5", "3g2a" -> MagicMatch("ISO Media, MPEG v4 / 3GPP", "video/3gpp", listOf("3gp"))
            "qt" -> MagicMatch("ISO Media, Apple QuickTime movie", "video/quicktime", listOf("mov"))
            "M4A", "M4A " -> MagicMatch("ISO Media, Apple iTunes ALAC/AAC-LC (.M4A) Audio", "audio/mp4", listOf("m4a"))
            "M4V", "M4V " -> MagicMatch("ISO Media, Apple iTunes Video (.M4V)", "video/x-m4v", listOf("m4v"))
            "heic", "heix", "mif1" -> MagicMatch("ISO Media, HEIF image", "image/heif", listOf("heic"))
            "avif" -> MagicMatch("ISO Media, AVIF image", "image/avif", listOf("avif"))
            else -> MagicMatch("ISO Media, MP4 v1 (brand: $brand)", "video/mp4", listOf("mp4"))
        }
    }

    /** `#!` line → name the interpreter. */
    fun shebang(buf: ByteArray): MagicMatch {
        var end = 2
        while (end < buf.size && buf[end].toInt() != '\n'.code) end++
        val line =
            buildString {
                for (i in 2 until end) append((buf[i].toInt() and 0xff).toChar())
            }.trim()
        val first = line.substringBefore(' ').ifEmpty { line }
        val interp = first.substringAfterLast('/')
        val (desc, mime, ext) =
            when (interp) {
                "bash" -> {
                    Triple("Bourne-Again shell script", "text/x-shellscript", "sh")
                }

                "sh" -> {
                    Triple("POSIX shell script", "text/x-shellscript", "sh")
                }

                "zsh" -> {
                    Triple("Zsh shell script", "text/x-shellscript", "zsh")
                }

                "fish" -> {
                    Triple("fish shell script", "text/x-shellscript", "fish")
                }

                "python", "python2", "python3" -> {
                    Triple("Python script", "text/x-python", "py")
                }

                "perl" -> {
                    Triple("Perl script", "text/x-perl", "pl")
                }

                "ruby" -> {
                    Triple("Ruby script", "text/x-ruby", "rb")
                }

                "node", "nodejs" -> {
                    Triple("Node.js script", "application/javascript", "js")
                }

                "awk", "gawk", "nawk" -> {
                    Triple("awk script", "text/x-awk", "awk")
                }

                "env" -> {
                    // `#!/usr/bin/env python` — the real interpreter is the next token.
                    val real =
                        line
                            .substringAfter(' ', "")
                            .trim()
                            .substringBefore(' ')
                            .substringAfterLast('/')
                    return MagicMatch(
                        if (real.isEmpty()) "a /usr/bin/env script" else "a $real script",
                        "text/x-script",
                        listOf(),
                    )
                }

                else -> {
                    Triple(if (interp.isEmpty()) "script" else "$interp script", "text/x-script", "")
                }
            }
        val exts = if (ext.isEmpty()) emptyList() else listOf(ext)
        return MagicMatch("$desc, ASCII text executable", mime, exts)
    }
}
