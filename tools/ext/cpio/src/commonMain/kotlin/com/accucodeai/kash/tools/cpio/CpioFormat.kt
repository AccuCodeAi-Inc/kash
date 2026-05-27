package com.accucodeai.kash.tools.cpio

/**
 * On-disk cpio archive formats this tool understands.
 *
 * Both are header-then-name-then-data, repeated; the archive ends with a
 * sentinel entry named "TRAILER!!!" with nlink=1, size=0.
 *
 * newc (SVR4, "-H newc", magic 070701): all numeric fields are 8 hex ASCII
 * digits; name and data are each padded with NULs to a 4-byte boundary
 * counted from the START of the archive. The header itself is 110 bytes
 * (already a multiple of 2 but not 4 — name padding compensates).
 *
 * odc (POSIX old portable, "-H odc", magic 070707): all numeric fields are
 * 6 octal ASCII digits except filesize/devmajor-style which are 11. No
 * padding. Name is just the NUL-terminated bytes.
 */
public enum class CpioFormat(
    public val magic: String,
) {
    NEWC("070701"),
    ODC("070707"),
    ;

    public companion object {
        public fun parse(name: String): CpioFormat? =
            when (name) {
                "newc" -> NEWC
                "odc" -> ODC
                else -> null
            }
    }
}

/** Trailer entry name marking end-of-archive. */
public const val CPIO_TRAILER: String = "TRAILER!!!"

/** A single archive entry's metadata (no payload). */
public data class CpioHeader(
    val name: String,
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val nlink: Int,
    val mtime: Long,
    val size: Long,
    val ino: Long = 0L,
    val devMajor: Int = 0,
    val devMinor: Int = 0,
    val rDevMajor: Int = 0,
    val rDevMinor: Int = 0,
) {
    val isTrailer: Boolean get() = name == CPIO_TRAILER
    val isDir: Boolean get() = (mode and 0xF000) == 0x4000
    val isRegular: Boolean get() = (mode and 0xF000) == 0x8000 || (mode and 0xF000) == 0
    val isSymlink: Boolean get() = (mode and 0xF000) == 0xA000
}
