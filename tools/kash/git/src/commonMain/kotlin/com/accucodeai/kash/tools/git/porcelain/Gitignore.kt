package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.fs.FileSystem

/**
 * Subset of the .gitignore pattern grammar that covers what real
 * scripts and `.gitignore` files actually use:
 *
 *  - blank lines and lines starting with `#` are ignored
 *  - `!` at the start negates a match (re-includes a previously ignored path)
 *  - trailing `/` makes the pattern directory-only
 *  - leading `/` anchors the pattern to the directory containing this
 *    .gitignore; without it, the pattern is matched at any depth below
 *  - `*` matches any run of non-`/` chars; `?` matches one; `**` matches
 *    across `/` boundaries
 *  - a pattern with an internal `/` (other than a leading/trailing one)
 *    is treated as anchored to this .gitignore's directory
 *
 * Order: later patterns win. A negation only re-includes if no later
 * non-negated pattern re-excludes.
 */
internal class GitignorePattern(
    val raw: String,
    val negate: Boolean,
    val dirOnly: Boolean,
    val anchored: Boolean,
    private val regex: Regex,
) {
    /** Test the candidate `relativeToPatternDir` path. */
    fun matches(relPath: String): Boolean = regex.matches(relPath)

    companion object {
        fun parse(line: String): GitignorePattern? {
            val t = line.trimEnd().trimEnd('\r')
            if (t.isEmpty() || t.startsWith("#")) return null
            var s = t
            var negate = false
            if (s.startsWith("!")) {
                negate = true
                s = s.substring(1)
            }
            val dirOnly = s.endsWith("/")
            if (dirOnly) s = s.dropLast(1)
            val anchored = s.startsWith("/") || (s.contains('/') && !s.endsWith("/"))
            if (s.startsWith("/")) s = s.substring(1)
            return GitignorePattern(t, negate, dirOnly, anchored, globToRegex(s, anchored))
        }

        private fun globToRegex(
            glob: String,
            anchored: Boolean,
        ): Regex {
            val sb = StringBuilder()
            if (anchored) sb.append("^") else sb.append("(?:^|.*/)")
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        // Consume `**`. Common forms: `**/x`, `x/**`, `a/**/b`.
                        sb.append(".*")
                        i += 2
                        if (i < glob.length && glob[i] == '/') i++ // absorb trailing /
                    }

                    c == '*' -> {
                        sb.append("[^/]*")
                        i++
                    }

                    c == '?' -> {
                        sb.append("[^/]")
                        i++
                    }

                    c in ".+(){}|^$\\" -> {
                        sb.append('\\').append(c)
                        i++
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            sb.append("$")
            return Regex(sb.toString())
        }
    }
}

/**
 * Layered matcher: each layer is the patterns from a particular
 * directory's `.gitignore` (plus `.git/info/exclude`). Layers are pushed
 * as the walker descends and popped on its way back up — matches
 * candidate paths from the deepest layer outward, with the standard
 * "last matching pattern wins, negation re-includes" semantics.
 */
internal class GitignoreMatcher {
    private data class Layer(
        val dir: String,
        val patterns: List<GitignorePattern>,
    )

    private val stack: ArrayDeque<Layer> = ArrayDeque()

    fun push(
        dir: String,
        patterns: List<GitignorePattern>,
    ) {
        stack.addLast(Layer(dir, patterns))
    }

    fun pop() {
        stack.removeLast()
    }

    /**
     * Is [relPath] (relative to the work tree, no leading slash) ignored?
     * [isDir] gates `dirOnly` patterns. Walks layers deepest-first; the
     * **last** matching pattern wins per real-git semantics. (We scan
     * deepest-to-shallowest, but within each layer iterate forward so
     * later patterns in the same file override earlier ones.)
     */
    fun isIgnored(
        relPath: String,
        isDir: Boolean,
    ): Boolean {
        // Build the candidate path relative to each layer's dir.
        var ignored = false
        for (layer in stack) {
            val candidate =
                if (layer.dir.isEmpty()) {
                    relPath
                } else if (relPath.startsWith("${layer.dir}/")) {
                    relPath.removePrefix("${layer.dir}/")
                } else if (relPath == layer.dir) {
                    ""
                } else {
                    continue
                }
            for (p in layer.patterns) {
                if (p.dirOnly && !isDir) continue
                if (p.matches(candidate)) {
                    ignored = !p.negate
                }
            }
        }
        return ignored
    }
}

/**
 * Read patterns from a `.gitignore`-style file. Returns an empty list
 * if the file doesn't exist.
 */
internal suspend fun readIgnoreFile(
    fs: FileSystem,
    path: String,
): List<GitignorePattern> {
    if (!fs.exists(path)) return emptyList()
    val text = fs.readBytes(path).decodeToString()
    val out = mutableListOf<GitignorePattern>()
    for (line in text.lineSequence()) {
        GitignorePattern.parse(line)?.let { out += it }
    }
    return out
}
