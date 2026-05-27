package com.accucodeai.kash.tools.grep

/**
 * Parsed view of the `GREP_COLORS` environment variable. Format is a
 * colon-separated list of `key=value` pairs where value is a raw SGR
 * sequence like `"01;31"`. See GNU grep's manual entry for `GREP_COLORS`
 * (we implement the subset the engine actually uses).
 *
 *  - `ms` — match in a *selected* line (the matched substring)
 *  - `mc` — match in a *context* line (with `-B`/`-A`/`-C`; defaults to `ms`)
 *  - `fn` — filename prefix
 *  - `ln` — line number prefix
 *  - `se` — separators (`:` / `-` between columns)
 *
 * Keys not in this list are silently ignored — that's GNU's behavior too,
 * so a `GREP_COLORS` that mentions `sl` or `bn` won't error here.
 *
 * Values are stored as raw strings (the SGR digits) and consumed verbatim
 * by [com.accucodeai.kash.api.ansi.AnsiStyler.style]; an empty value
 * disables coloring for that role.
 *
 * Defaults match GNU grep when `GREP_COLORS` is unset.
 */
internal data class GrepColors(
    val ms: String = "01;31",
    val mc: String = "01;31",
    val fn: String = "35",
    val ln: String = "32",
    val se: String = "36",
) {
    companion object {
        val DEFAULT: GrepColors = GrepColors()

        /**
         * Parse the value of `$GREP_COLORS`. Null or empty input returns
         * [DEFAULT]. Malformed entries (no `=`, or values containing
         * non-digit/non-`;` chars) are skipped — we deliberately don't
         * throw, because the env var is a user-set string that may carry
         * vendor-specific keys we don't model.
         */
        fun parse(raw: String?): GrepColors {
            if (raw.isNullOrEmpty()) return DEFAULT
            var ms = DEFAULT.ms
            var mc = DEFAULT.mc
            var fn = DEFAULT.fn
            var ln = DEFAULT.ln
            var se = DEFAULT.se
            // GNU's quirk: `mc` defaults to whatever `ms` was set to in this
            // string, not the global default. Track whether `mc` was set
            // explicitly so a trailing `ms=…` doesn't override it.
            var mcExplicit = false
            for (part in raw.split(':')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val key = part.substring(0, eq)
                val value = part.substring(eq + 1)
                if (!isValidSgr(value)) continue
                when (key) {
                    "ms" -> {
                        ms = value
                        if (!mcExplicit) mc = value
                    }

                    "mc" -> {
                        mc = value
                        mcExplicit = true
                    }

                    "fn" -> {
                        fn = value
                    }

                    "ln" -> {
                        ln = value
                    }

                    "se" -> {
                        se = value
                    }

                    else -> {
                        Unit
                    } // ignore unknown keys
                }
            }
            return GrepColors(ms = ms, mc = mc, fn = fn, ln = ln, se = se)
        }

        /** True iff [s] is empty or only digits + `;` — i.e. a valid SGR body. */
        private fun isValidSgr(s: String): Boolean {
            if (s.isEmpty()) return true
            for (c in s) {
                if (c != ';' && !c.isDigit()) return false
            }
            return true
        }
    }
}
