package com.accucodeai.kash.fs

/**
 * POSIX path helpers. Path strings are normalized to use `/` and are
 * absolute when stored in [InMemoryFs].
 */
public object Paths {
    public fun normalize(path: String): String {
        if (path.isEmpty()) return "/"
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
