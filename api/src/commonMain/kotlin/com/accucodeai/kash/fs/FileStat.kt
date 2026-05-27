package com.accucodeai.kash.fs

/**
 * File metadata returned by [FileSystem.stat]. Modeled on POSIX `stat(2)`
 * but slimmed to the fields `ls -l`, `test -r/-w/-x`, `find -printf`, and
 * `stat` actually consume.
 *
 * `mode` carries the Unix permission bits (lower 9 = rwxrwxrwx; bit 0o4000
 * = setuid, 0o2000 = setgid, 0o1000 = sticky). We **store** the bits but
 * don't yet enforce them on `source` / `sink` — that's a follow-up. Tools
 * that want the bits just for display (like `ls -l`) work today.
 *
 * `mtimeEpochSeconds` is "seconds since 1970-01-01 UTC". The default
 * [InMemoryFs] clock starts at 0 so tests are deterministic; production
 * embedders inject a real clock.
 */
public data class FileStat(
    public val path: String,
    public val type: FileType,
    public val size: Long,
    public val mtimeEpochSeconds: Long,
    public val mode: Int,
    public val nlink: Int = 1,
    public val ownerName: String = "user",
    public val groupName: String = "user",
    public val symlinkTarget: String? = null,
)

/**
 * File-type byte used by POSIX `ls -l` ([XCU §ls]).
 *
 * [XCU §ls]: https://pubs.opengroup.org/onlinepubs/9699919799/utilities/ls.html
 */
public enum class FileType {
    REGULAR,
    DIRECTORY,
    SYMLINK,
    FIFO,
    SOCKET,
    BLOCK,
    CHAR,
}
