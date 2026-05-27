package com.accucodeai.kash.conformance

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.util.matchGlob

/**
 * `strmatch` — bash test-suite helper. Source ships as a loadable builtin
 * (`enable -f strmatch.so strmatch`) at `external/bash/examples/loadables/`.
 * Wraps `strmatch(pattern, string, flags)` with FNM_PATHNAME | FNM_PERIOD |
 * FNM_EXTMATCH so glob-bracket.tests can compare bash's bracket-expression
 * handling against a known reference.
 *
 * Invoked as `strmatch STRING PATTERN` (note arg order: string first, then
 * pattern — matches the C builtin's `(word_list*)` order, which extracts
 * `str = list->word`, then `pat = list->next->word`). Exit 0 on match,
 * 1 on no match, 2 on usage error.
 *
 * FNM_PATHNAME — `*`, `?`, and `[...]` don't span literal `/`. kash's
 * matchGlob doesn't model PATHNAME natively; we approximate by splitting
 * pattern and value on `/` and matching segment-by-segment. Mirrors bash's
 * `match_pattern_char` PATHNAME handling for the test corpus.
 */
public class StrmatchCommand :
    Command,
    CommandSpec {
    override val name: String = "strmatch"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        if (args.size < 2) return CommandResult(exitCode = 2)
        val str = args[0]
        val pat = args[1]
        return CommandResult(exitCode = if (fnmatchPathname(pat, str)) 0 else 1)
    }
}

/**
 * `fnmatch` — companion to [StrmatchCommand]. The test compiles a tiny C
 * program calling `fnmatch(pattern, string, FNM_PATHNAME | FNM_PERIOD |
 * FNM_EXTMATCH)` and invokes it via `$WORK_DIR/fnmatch`. We register a
 * kash command of the same name and arrange (in the conformance harness)
 * for `$WORK_DIR/fnmatch` to dispatch to it.
 */
public class FnmatchCommand :
    Command,
    CommandSpec {
    override val name: String = "fnmatch"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        if (args.size < 2) return CommandResult(exitCode = 2)
        val str = args[0]
        val pat = args[1]
        return CommandResult(exitCode = if (fnmatchPathname(pat, str)) 0 else 1)
    }
}

/**
 * Glob match honoring FNM_PATHNAME: `*` / `?` / `[...]` never consume `/`.
 * Implemented by splitting both pattern and value on `/` and matching each
 * segment with [matchGlob] — segment counts must agree, and each segment
 * must independently match. This is the standard fnmatch(3) PATHNAME
 * semantics that the bash test corpus checks bracket-expression behavior
 * against.
 */
internal fun fnmatchPathname(
    pattern: String,
    value: String,
): Boolean {
    // Bash FNM_PATHNAME quirk: a bracket expression `[…]` containing `/`
    // can't actually match (FNM_PATHNAME forbids brackets from spanning
    // `/`), so bash falls back to treating the whole bracket as literal
    // characters. `ab[/]ef` matches `ab[/]ef` (literally `[`/`/`/`]`),
    // but does NOT match `ab/ef` (literal `[` doesn't appear). Same for
    // longer bracket sub-syntax like `[c/d]`, `[[=/=]]`, `[[:alpha:]/]`
    // — anything with an unescaped `/` between `[` and `]`.
    val literalized = escapeBracketsContainingSlash(pattern)
    val patSegs = splitOnUnescapedSlash(literalized)
    // If the literalized pattern has no unescaped `/` left (e.g. the only
    // slashes were inside a bracket that got promoted to literal chars),
    // skip segment-split and match the pattern against the whole value.
    // Otherwise FNM_PATHNAME semantics require segment-by-segment match.
    if (patSegs.size == 1) {
        return matchGlob(literalized, value)
    }
    val valSegs = value.split('/')
    if (patSegs.size != valSegs.size) return false
    for (i in patSegs.indices) {
        if (!matchGlob(patSegs[i], valSegs[i])) return false
    }
    return true
}

/**
 * Walk [pattern]; for each balanced `[…]` whose body contains an
 * unescaped `/`, rewrite the entire bracket (including `[` and `]`)
 * as escape-prefixed literal characters. The output keeps non-bracket
 * regions unchanged. Unmatched `[` (no closing `]`) is left alone —
 * bash treats those as literal already via matchGlob's lenient fallback.
 */
private fun escapeBracketsContainingSlash(pattern: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c == '\\' && i + 1 < pattern.length) {
            sb.append(c)
            sb.append(pattern[i + 1])
            i += 2
            continue
        }
        if (c != '[') {
            sb.append(c)
            i++
            continue
        }
        // Scan for the closing `]`. Allow leading `!`/`^` (negation) and
        // a literal `]` as the first body char per POSIX bracket syntax.
        var j = i + 1
        if (j < pattern.length && (pattern[j] == '!' || pattern[j] == '^')) j++
        if (j < pattern.length && pattern[j] == ']') j++
        while (j < pattern.length && pattern[j] != ']') {
            if (pattern[j] == '\\' && j + 1 < pattern.length) j += 2 else j++
        }
        if (j >= pattern.length) {
            // Unmatched `[` — emit as-is and let matchGlob handle it.
            sb.append(c)
            i++
            continue
        }
        val content = pattern.substring(i, j + 1)
        if (hasUnescapedSlash(content)) {
            // Bash strmatch under FNM_PATHNAME, on encountering a
            // bracket whose body contains a `/`: rejects the bracket
            // parse, treats the opening `[` as a literal character,
            // and CONTINUES parsing the rest of the body as new
            // pattern. So `ab[/[abc]]ef` becomes `ab` + literal `[` +
            // `/` + bracket `[abc]` + literal `]ef`, which matches
            // `ab[/c]ef` (test #34). Implementation: emit `\[` for the
            // opening, then the remaining content (up to and including
            // the closing `]`) unchanged so the body re-parses.
            // Recurse on the body + closing `]` so nested bracket-
            // with-slash also backtracks the same way. Without the
            // recursion, `ab[[=/=]]ef` (#28) leaves an inner `[=/=]`
            // whose body re-parsed as a char class fails to match the
            // next char in the value.
            sb.append('\\').append('[')
            sb.append(escapeBracketsContainingSlash(content.substring(1)))
        } else {
            sb.append(content)
        }
        i = j + 1
    }
    return sb.toString()
}

/**
 * True iff [content] has any `/` — escaped or not. Bash's FNM_PATHNAME
 * treats `\/` inside a bracket as a literal `/`, which makes the bracket
 * unmatchable (PATHNAME forbids `/`), triggering the literal-fallback
 * rule. Test #39's `[b\/c]` exercises this.
 */
private fun hasUnescapedSlash(content: String): Boolean = '/' in content

/**
 * Split on `/` but treat `\/` and `/`-inside-bracket-`[...]` as part of the
 * same segment. Without this, `a[b\/c]` (the test's #39 case) would split
 * into segments `a[b\` and `c]`, neither of which is a valid pattern.
 */
private fun splitOnUnescapedSlash(p: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    var inBracket = false
    while (i < p.length) {
        val c = p[i]
        when {
            c == '\\' && i + 1 < p.length -> {
                sb.append(c)
                sb.append(p[i + 1])
                i += 2
            }

            c == '[' && !inBracket -> {
                inBracket = true
                sb.append(c)
                i++
            }

            c == ']' && inBracket -> {
                inBracket = false
                sb.append(c)
                i++
            }

            c == '/' && !inBracket -> {
                out += sb.toString()
                sb.clear()
                i++
            }

            else -> {
                sb.append(c)
                i++
            }
        }
    }
    out += sb.toString()
    return out
}
