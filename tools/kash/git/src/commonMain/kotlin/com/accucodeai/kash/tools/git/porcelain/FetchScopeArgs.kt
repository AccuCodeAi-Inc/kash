package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.git.GitFetchScope

/**
 * Parse real-git's `--depth=N` / `--depth N` and `--filter=<spec>` /
 * `--filter <spec>` from a flat arg list (matching how `git clone` and
 * `git fetch` accept them). Returns:
 *  - the constructed [GitFetchScope]
 *  - the input args with the recognized flags + their values removed
 *    so the caller can pull positional remote/branch arguments out of
 *    the remainder.
 *
 * Throws [IllegalArgumentException] on a malformed value (e.g.
 * `--depth=foo`, missing value after `--depth`, depth <= 0).
 *
 * v1 supports `--depth=N` (and `-d`); `--shallow-since=<date>` /
 * `--shallow-exclude=<rev>` / `--unshallow` / `--update-shallow` are
 * NOT wired — neither host adapter exposes them and the LLM-driven
 * use case rarely needs them.
 */
internal fun parseFetchScopeArgs(args: List<String>): Pair<GitFetchScope, List<String>> {
    var depth: Int? = null
    var filter: String? = null
    val out = ArrayList<String>(args.size)
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a.startsWith("--depth=") -> {
                val v = a.removePrefix("--depth=")
                depth = parseDepthValue(v)
                i++
            }

            a == "--depth" || a == "-d" -> {
                require(i + 1 < args.size) { "option '$a' requires a value" }
                depth = parseDepthValue(args[i + 1])
                i += 2
            }

            a.startsWith("--filter=") -> {
                filter =
                    a.removePrefix("--filter=").also {
                        require(it.isNotBlank()) { "option '--filter' requires a non-blank spec" }
                    }
                i++
            }

            a == "--filter" -> {
                require(i + 1 < args.size) { "option '--filter' requires a value" }
                filter =
                    args[i + 1].also {
                        require(it.isNotBlank()) { "option '--filter' requires a non-blank spec" }
                    }
                i += 2
            }

            else -> {
                out += a
                i++
            }
        }
    }
    return GitFetchScope(depth = depth, filter = filter) to out
}

private fun parseDepthValue(s: String): Int {
    val n =
        s.toIntOrNull()
            ?: throw IllegalArgumentException("option '--depth' requires a positive integer (got '$s')")
    require(n > 0) { "option '--depth' must be > 0 (got $n)" }
    return n
}
