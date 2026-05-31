package com.accucodeai.kash.fs

/**
 * POSIX path helpers. Path strings are normalized to use `/` and are
 * absolute when stored in [InMemoryFs].
 */
public object Paths {
    public fun normalize(path: String): String {
        if (path.isEmpty()) return "/"
        // Fast path: most paths reaching here are already canonical (absolute,
        // no `.`/`..` segments, no `//`, no trailing slash). A single scan
        // detects that and returns the input unchanged — no split/join
        // allocation. This is hot: the mount router + inner FS each normalize
        // the same path, so a single op can hit this 3-5×.
        if (isCanonical(path)) return path
        val parts = mutableListOf<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * True iff [path] is already in the form [normalize] would produce:
     * absolute, no empty/`.`/`..` segments, no trailing slash (except the
     * bare root `/`). Cheap single-pass scan with zero allocation.
     */
    private fun isCanonical(path: String): Boolean {
        val len = path.length
        if (path[0] != '/') return false
        if (len == 1) return true // exactly "/"
        if (path[len - 1] == '/') return false // trailing slash
        var i = 0
        while (i < len) {
            if (path[i] == '/') {
                // A '/' is never the last char here (trailing slash rejected),
                // so i+1 is in bounds — it starts the next segment.
                val next = path[i + 1]
                if (next == '/') return false // empty segment "//"
                if (next == '.') {
                    val j = i + 2
                    // "." segment: '.' then '/' or end-of-string.
                    if (j == len || path[j] == '/') return false
                    // ".." segment: '.' '.' then '/' or end-of-string.
                    if (path[j] == '.' && (j + 1 == len || path[j + 1] == '/')) return false
                }
            }
            i++
        }
        return true
    }

    public fun resolve(
        cwd: String,
        path: String,
    ): String = if (path.startsWith("/")) normalize(path) else normalize("$cwd/$path")

    public fun parent(path: String): String {
        val n = normalize(path)
        if (n == "/") return "/"
        val idx = n.lastIndexOf('/')
        return if (idx <= 0) "/" else n.substring(0, idx)
    }

    public fun basename(path: String): String {
        val n = normalize(path)
        if (n == "/") return "/"
        return n.substringAfterLast('/')
    }

    /**
     * Did the raw, un-normalized [path] end with a `/` (other than being
     * exactly `/`)? POSIX path resolution treats a trailing slash as
     * "this must be a directory" — `open("/foo/", O_RDONLY)` on a regular
     * file returns `ENOTDIR`. [normalize] strips trailing slashes, so
     * callers that need this signal must capture it before normalizing.
     */
    public fun endsWithSlash(path: String): Boolean = path.length > 1 && path.endsWith('/')
}
