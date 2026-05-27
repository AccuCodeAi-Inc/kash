package com.accucodeai.kash.api.util

/**
 * Split `$PATH` per POSIX
 * [XBD §8.3](https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap08.html#tag_08_03):
 * colon-separated; empty element = current working directory; a trailing
 * colon yields a trailing empty element. When PATH is unset the
 * implementation-defined default applies — kash uses `/usr/bin:/bin` plus
 * the current working directory, so a stripped env doesn't make every
 * utility unreachable AND `type -p NAME` (with NAME present only in cwd)
 * still resolves consistently with the empty-PATH case.
 */
public fun splitPath(pathEnv: String?): List<String> {
    if (pathEnv == null) return listOf("/usr/bin", "/bin", "")
    if (pathEnv.isEmpty()) return listOf("")
    return pathEnv.split(':')
}
