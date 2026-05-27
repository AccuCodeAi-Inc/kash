package com.accucodeai.kash.api.terminal

/**
 * Tab-completion engine consulted by [LineEditor] when the user presses TAB.
 *
 * The interface lives in `:api` next to [LineEditor] so the editor in `:corevm`
 * can consume completions without depending on the shell interpreter.
 * The concrete shell-aware implementation
 * (`com.accucodeai.kash.completion.ShellCompleter`) is in `:tools:kash:shell`.
 *
 * Behavior mirrors bash/readline:
 *  - Unique match → insert the unambiguous remainder + [Candidate.trailing]
 *    (space for files/commands, `/` for directories, `""` for partial).
 *  - Ambiguous match with a non-trivial longest common prefix → insert the
 *    LCP only; second TAB on the same state lists candidates above the prompt.
 *  - No match → bell (no buffer mutation).
 *
 * Pure-data callback: implementations MUST NOT mutate shell state. Failure
 * modes (broken FS, missing dirs) should be caught internally and surfaced as
 * "no candidates".
 */
public fun interface Completer {
    public suspend fun complete(
        line: String,
        cursor: Int,
    ): CompletionResult
}

/**
 * One completion suggestion.
 *
 * @property text the full candidate string (NOT just the suffix being added).
 *   The editor replaces `line[replaceStart..replaceEnd)` with `text + trailing`.
 * @property trailing what to append after [text] when this candidate is the
 *   unique match. `" "` (default) → command/regular file; `"/"` → directory
 *   (no trailing space — user is likely about to drill in); `""` → partial
 *   match (e.g. ambiguous prefix where the LCP itself isn't a real name).
 */
public data class Candidate(
    val text: String,
    val trailing: String = " ",
)

/**
 * Outcome of one [Completer.complete] call. [candidates] is sorted, deduped,
 * and prefix-filtered against `line[replaceStart..replaceEnd)`. [commonPrefix]
 * is the longest common prefix of every candidate's [Candidate.text] (or the
 * lone candidate's text if `candidates.size == 1`). Empty when [candidates]
 * is empty.
 */
public data class CompletionResult(
    val replaceStart: Int,
    val replaceEnd: Int,
    val candidates: List<Candidate>,
    val commonPrefix: String,
) {
    public companion object {
        public val Empty: CompletionResult =
            CompletionResult(replaceStart = 0, replaceEnd = 0, candidates = emptyList(), commonPrefix = "")

        /** Build a result with [commonPrefix] computed from [candidates]. */
        public fun of(
            replaceStart: Int,
            replaceEnd: Int,
            candidates: List<Candidate>,
        ): CompletionResult {
            if (candidates.isEmpty()) {
                return CompletionResult(replaceStart, replaceEnd, emptyList(), "")
            }
            val texts = candidates.map { it.text }
            return CompletionResult(replaceStart, replaceEnd, candidates, longestCommonPrefix(texts))
        }

        internal fun longestCommonPrefix(strings: List<String>): String {
            if (strings.isEmpty()) return ""
            var prefix = strings[0]
            for (i in 1 until strings.size) {
                val s = strings[i]
                var n = 0
                val limit = minOf(prefix.length, s.length)
                while (n < limit && prefix[n] == s[n]) n++
                prefix = prefix.substring(0, n)
                if (prefix.isEmpty()) return ""
            }
            return prefix
        }
    }
}
