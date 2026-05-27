package com.accucodeai.kash.shared.regex

/**
 * Linear-time regex abstraction.
 *
 * Backed by a guaranteed-linear-time engine per target. The JVM `actual` uses
 * RE2/J — `java.util.regex` is unsafe under adversarial input (catastrophic
 * backtracking / ReDoS), so we explicitly avoid it.
 *
 * Flags follow jq's letter set: `i` (case-insensitive), `m` (multiline `^`/`$`),
 * `s` (dotall — `.` matches newline), `x` (extended whitespace ignored — not
 * supported by every engine; throws if used), `n` (no implicit captures —
 * accepted but currently a no-op). `g` is interpreted at the call site of
 * `match`/`scan`, not at compile time.
 */
public expect class LinearRegex public constructor(
    pattern: String,
    flags: String,
) {
    /** True iff [s] contains at least one match. */
    public fun containsMatch(s: String): Boolean

    /** All matches in [s]. Lazy; iterating is O(|s|) total. */
    public fun findAll(s: String): Sequence<RegexMatch>

    /** First match in [s], or null. */
    public fun findFirst(s: String): RegexMatch?
}

public data class RegexMatch(
    /** Char offset of the match within the input. */
    val offset: Int,
    /** Char length of the match. */
    val length: Int,
    /** Matched substring. */
    val text: String,
    /** Capture groups in order, including unnamed. Group 0 (the full match) is excluded. */
    val captures: List<RegexCapture>,
)

public data class RegexCapture(
    val offset: Int,
    val length: Int,
    /** Null if the capture group was optional and did not participate in the match. */
    val text: String?,
    /** Null for unnamed groups. */
    val name: String?,
)
