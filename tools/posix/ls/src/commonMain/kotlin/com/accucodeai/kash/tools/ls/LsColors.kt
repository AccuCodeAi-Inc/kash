package com.accucodeai.kash.tools.ls

import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileType

/**
 * Parsed view of the `LS_COLORS` environment variable as produced by
 * `dircolors`. The format is colon-separated `key=sgr` pairs where:
 *
 *  - **Type keys** map a file kind to an SGR string:
 *    `di` (directory), `ln` (symlink), `ex` (executable), `fi` (regular
 *    file), `so` (socket), `pi` (fifo), `bd` (block device), `cd` (char
 *    device), `or` (orphan symlink), `mi` (missing target), `no` (normal),
 *    `tw`/`ow`/`st`/`su`/`sg` (sticky/other-writable/setuid/setgid combos).
 *
 *  - **Extension globs** look like `*.tar=01;31` and apply to regular
 *    files whose name ends in `.tar` (case-sensitive — GNU's default).
 *
 * We model the type keys we can actually compute from [FileStat] (kash
 * doesn't snapshot setuid/sticky-other-writable as distinct render
 * categories — those fall back to the matching simple type). Extension
 * globs use a flat last-match-wins scan.
 *
 * Empty values are allowed (and meaningful — they disable coloring for
 * that role); unknown keys are silently ignored.
 */
internal data class LsColors(
    val di: String = "01;34",
    val ln: String = "01;36",
    val ex: String = "01;32",
    val fi: String = "",
    val so: String = "01;35",
    val pi: String = "33",
    val bd: String = "01;33",
    val cd: String = "01;33",
    val or: String = "",
    val mi: String = "",
    val no: String = "",
    /**
     * Extension → SGR. Insertion order preserved; last entry wins on
     * duplicates (we resolve that at parse time by overwriting).
     * Lookup key is the lowercase extension *including* the leading dot
     * (e.g. `.tar`), stored as the suffix matched against [FileStat.path].
     */
    val extensions: Map<String, String> = emptyMap(),
) {
    /**
     * Pick the SGR sequence for [e]. Extension globs win over the
     * type-key fallback for regular files; other types ignore extensions.
     * Returns an empty string when no color applies — callers should
     * treat that as "render bare."
     */
    fun colorFor(e: FileStat): String {
        if (e.type == FileType.REGULAR) {
            // Extension match is case-insensitive in practice on macOS but
            // GNU's default is case-sensitive. We follow GNU.
            val name = e.path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            if (dot >= 0) {
                val ext = name.substring(dot)
                val viaExt = extensions[ext]
                if (viaExt != null) return viaExt
            }
            // Executable bit on regular files overrides plain `fi`.
            return if (e.mode and 0b001_001_001 != 0) ex else fi
        }
        return when (e.type) {
            FileType.DIRECTORY -> di
            FileType.SYMLINK -> ln
            FileType.FIFO -> pi
            FileType.SOCKET -> so
            FileType.BLOCK -> bd
            FileType.CHAR -> cd
            FileType.REGULAR -> "" // unreachable, handled above
        }
    }

    companion object {
        val DEFAULT: LsColors = LsColors()

        /**
         * Parse `$LS_COLORS`. Null/empty → [DEFAULT]. Malformed entries
         * (no `=`, non-SGR value characters) are skipped silently. Order
         * matters for extension entries only on a per-extension basis —
         * last definition wins.
         */
        fun parse(raw: String?): LsColors {
            if (raw.isNullOrEmpty()) return DEFAULT
            var di = DEFAULT.di
            var ln = DEFAULT.ln
            var ex = DEFAULT.ex
            var fi = DEFAULT.fi
            var so = DEFAULT.so
            var pi = DEFAULT.pi
            var bd = DEFAULT.bd
            var cd = DEFAULT.cd
            var or = DEFAULT.or
            var mi = DEFAULT.mi
            var no = DEFAULT.no
            val extensions = mutableMapOf<String, String>()
            for (part in raw.split(':')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val key = part.substring(0, eq)
                val value = part.substring(eq + 1)
                if (!isValidSgr(value)) continue
                if (key.startsWith("*.")) {
                    // GNU permits `*.ext` (the dot included after the star).
                    extensions[key.substring(1)] = value
                    continue
                }
                if (key.startsWith("*")) {
                    // `*name` (no dot) matches an exact suffix — store as-is
                    // so the lookup can fall back to whole-name match too.
                    extensions[key.substring(1)] = value
                    continue
                }
                when (key) {
                    "di" -> di = value

                    "ln" -> ln = value

                    "ex" -> ex = value

                    "fi" -> fi = value

                    "so" -> so = value

                    "pi" -> pi = value

                    "bd" -> bd = value

                    "cd" -> cd = value

                    "or" -> or = value

                    "mi" -> mi = value

                    "no" -> no = value

                    // Permission combos collapse to their base type — kash
                    // doesn't render them distinctly. Future work if needed.
                    else -> Unit
                }
            }
            return LsColors(
                di = di,
                ln = ln,
                ex = ex,
                fi = fi,
                so = so,
                pi = pi,
                bd = bd,
                cd = cd,
                or = or,
                mi = mi,
                no = no,
                extensions = extensions,
            )
        }

        private fun isValidSgr(s: String): Boolean {
            if (s.isEmpty()) return true
            for (c in s) {
                if (c != ';' && !c.isDigit()) return false
            }
            return true
        }
    }
}
