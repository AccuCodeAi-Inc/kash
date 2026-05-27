package com.accucodeai.kash.tools.getopt

// Pure argv normalizer used by the `getopt(1)` utility. Parses the user's
// argv against a short/long spec and emits the canonical "flag value flag
// value -- operands" stream that scripts then `eval` after shell-quoting.
//
// This is the standalone util-linux-style getopt — distinct from the POSIX
// `getopts` shell builtin.

/** Argument requirement on a single option entry. */
public enum class ArgKind { NONE, REQUIRED, OPTIONAL }

/** Parsed entry from a short-option spec letter or a long-option name. */
public data class OptSpec(
    /** Either a 1-char string for short opts or the long name. */
    val name: String,
    val isLong: Boolean,
    val argKind: ArgKind,
)

/** A consumed option or operand emitted by the parser. */
public sealed interface ParsedItem {
    public data class Option(
        val spec: OptSpec,
        val value: String?,
    ) : ParsedItem

    public data class Operand(
        val value: String,
    ) : ParsedItem
}

public class GetoptParseException(
    public val rc: Int,
    message: String,
) : RuntimeException(message)

/**
 * Parse a short-option spec a la `abc:d::` into a list of [OptSpec]s.
 * Leading '+' / '-' modifiers are accepted and stripped (we always run in
 * a POSIX-permuted-style — `+` would mean stop at first non-option, which
 * we honor; '-' means treat operands as args of '\1', not yet supported).
 */
public fun parseShortSpec(spec: String): Pair<List<OptSpec>, ShortMode> {
    var s = spec
    var mode = ShortMode.PERMUTE
    if (s.startsWith("+")) {
        mode = ShortMode.REQUIRE_ORDER
        s = s.substring(1)
    } else if (s.startsWith("-")) {
        mode = ShortMode.RETURN_IN_ORDER
        s = s.substring(1)
    }
    val out = mutableListOf<OptSpec>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        var argKind = ArgKind.NONE
        var j = i + 1
        if (j < s.length && s[j] == ':') {
            argKind = ArgKind.REQUIRED
            j++
            if (j < s.length && s[j] == ':') {
                argKind = ArgKind.OPTIONAL
                j++
            }
        }
        out += OptSpec(c.toString(), isLong = false, argKind = argKind)
        i = j
    }
    return out to mode
}

public enum class ShortMode { PERMUTE, REQUIRE_ORDER, RETURN_IN_ORDER }

/**
 * Parse a long-option spec (a comma-separated list). Each entry is a name
 * optionally followed by `:` (required arg) or `::` (optional arg).
 *
 * Multiple `-l` arguments are joined with commas before being passed in.
 */
public fun parseLongSpec(specs: List<String>): List<OptSpec> {
    val joined = specs.joinToString(",")
    if (joined.isBlank()) return emptyList()
    val out = mutableListOf<OptSpec>()
    for (raw in joined.split(",")) {
        val entry = raw.trim()
        if (entry.isEmpty()) continue
        val argKind: ArgKind
        val name: String
        when {
            entry.endsWith("::") -> {
                argKind = ArgKind.OPTIONAL
                name = entry.dropLast(2)
            }

            entry.endsWith(":") -> {
                argKind = ArgKind.REQUIRED
                name = entry.dropLast(1)
            }

            else -> {
                argKind = ArgKind.NONE
                name = entry
            }
        }
        if (name.isEmpty()) continue
        out += OptSpec(name, isLong = true, argKind = argKind)
    }
    return out
}

/** Find a short option spec by single letter; returns null if unknown. */
private fun findShort(
    specs: List<OptSpec>,
    letter: Char,
): OptSpec? = specs.firstOrNull { !it.isLong && it.name == letter.toString() }

/**
 * Resolve a long option name, allowing unique prefix abbreviation (like
 * GNU getopt). Returns either the exact match, the unique prefix, or null.
 * Throws on ambiguous prefix.
 */
private fun findLong(
    specs: List<OptSpec>,
    name: String,
): OptSpec? {
    val longs = specs.filter { it.isLong }
    val exact = longs.firstOrNull { it.name == name }
    if (exact != null) return exact
    val prefixed = longs.filter { it.name.startsWith(name) }
    if (prefixed.size == 1) return prefixed.single()
    if (prefixed.size > 1) {
        throw GetoptParseException(
            1,
            "option '--$name' is ambiguous; possibilities: " +
                prefixed.joinToString(" ") { "'--${it.name}'" },
        )
    }
    return null
}

/**
 * Parse [argv] according to [shorts] + [longs]. Returns the consumed
 * options/operands in order, or throws on parse error.
 *
 * Behavior follows util-linux enhanced mode:
 *  - long options are matched on `--name` or `--name=value`
 *  - long options with `:` consume the next arg if no `=` form is used
 *  - long options with `::` only accept an inline `=value`
 *  - short options bundle: `-abc` ≡ `-a -b -c` (provided none take an arg
 *    in the middle of the bundle — once we hit one with an arg, the rest
 *    of the bundle is consumed as its value)
 *  - `--` ends parsing; rest are operands
 *  - if [alternative], long options also accepted with a single dash
 *  - if [stopAtNonOption] (`+` modifier), the first non-option arg ends
 *    parsing and the rest are operands
 */
public fun parseArgv(
    argv: List<String>,
    shorts: List<OptSpec>,
    longs: List<OptSpec>,
    alternative: Boolean = false,
    stopAtNonOption: Boolean = false,
): List<ParsedItem> {
    val all = shorts + longs
    val out = mutableListOf<ParsedItem>()
    val operands = mutableListOf<String>()
    var i = 0
    var endOfOpts = false
    while (i < argv.size) {
        val a = argv[i]
        if (endOfOpts) {
            operands += a
            i++
            continue
        }
        if (a == "--") {
            endOfOpts = true
            i++
            continue
        }
        // Long option: --name or --name=value
        if (a.startsWith("--") && a.length > 2) {
            val body = a.substring(2)
            val eq = body.indexOf('=')
            val name = if (eq >= 0) body.substring(0, eq) else body
            val inline = if (eq >= 0) body.substring(eq + 1) else null
            val spec =
                findLong(all, name)
                    ?: throw GetoptParseException(1, "unrecognized option '--$name'")
            i = consumeLong(spec, inline, argv, i, out)
            continue
        }
        // Short option(s): -abc
        if (a.startsWith("-") && a.length > 1 && a != "-") {
            // Alternative: a single-dash form may also be a long option.
            if (alternative) {
                val body = a.substring(1)
                val eq = body.indexOf('=')
                val name = if (eq >= 0) body.substring(0, eq) else body
                val inline = if (eq >= 0) body.substring(eq + 1) else null
                val longSpec = findLong(all, name)
                if (longSpec != null) {
                    i = consumeLong(longSpec, inline, argv, i, out)
                    continue
                }
                // Also accept single-char short option starting with `-X`
                // (fall through to short handling below).
            }
            i = consumeShortBundle(a, argv, i, all, shorts, out)
            continue
        }
        // Operand.
        if (stopAtNonOption) {
            operands += a
            i++
            // Everything else becomes an operand too.
            endOfOpts = true
            continue
        }
        operands += a
        i++
    }
    for (op in operands) out += ParsedItem.Operand(op)
    return out
}

private fun consumeLong(
    spec: OptSpec,
    inline: String?,
    argv: List<String>,
    i: Int,
    out: MutableList<ParsedItem>,
): Int {
    when (spec.argKind) {
        ArgKind.NONE -> {
            if (inline != null) {
                throw GetoptParseException(1, "option '--${spec.name}' doesn't allow an argument")
            }
            out += ParsedItem.Option(spec, null)
            return i + 1
        }

        ArgKind.REQUIRED -> {
            if (inline != null) {
                out += ParsedItem.Option(spec, inline)
                return i + 1
            }
            if (i + 1 >= argv.size) {
                throw GetoptParseException(1, "option '--${spec.name}' requires an argument")
            }
            out += ParsedItem.Option(spec, argv[i + 1])
            return i + 2
        }

        ArgKind.OPTIONAL -> {
            // Long optional: only takes value via `=`, never the next arg.
            out += ParsedItem.Option(spec, inline)
            return i + 1
        }
    }
}

private fun consumeShortBundle(
    token: String,
    argv: List<String>,
    i: Int,
    allSpecs: List<OptSpec>,
    shorts: List<OptSpec>,
    out: MutableList<ParsedItem>,
): Int {
    var p = 1
    while (p < token.length) {
        val c = token[p]
        val spec =
            findShort(shorts, c)
                ?: throw GetoptParseException(1, "invalid option -- '$c'")
        when (spec.argKind) {
            ArgKind.NONE -> {
                out += ParsedItem.Option(spec, null)
                p++
            }

            ArgKind.REQUIRED -> {
                // Rest of token is the value; otherwise next argv.
                if (p + 1 < token.length) {
                    out += ParsedItem.Option(spec, token.substring(p + 1))
                    return i + 1
                }
                if (i + 1 >= argv.size) {
                    throw GetoptParseException(1, "option requires an argument -- '$c'")
                }
                out += ParsedItem.Option(spec, argv[i + 1])
                return i + 2
            }

            ArgKind.OPTIONAL -> {
                if (p + 1 < token.length) {
                    out += ParsedItem.Option(spec, token.substring(p + 1))
                    return i + 1
                }
                out += ParsedItem.Option(spec, null)
                p++
            }
        }
    }
    return i + 1
}

/** Quote [s] for sh/bash: single-quote enclosure with `'\''` escaping. */
public fun shQuote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('\'')
    for (c in s) {
        if (c == '\'') {
            sb.append("'\\''")
        } else {
            sb.append(c)
        }
    }
    sb.append('\'')
    return sb.toString()
}

/** Quote [s] for csh/tcsh: '!' has special meaning so escape it. */
public fun cshQuote(s: String): String {
    val sb = StringBuilder()
    sb.append('\'')
    for (c in s) {
        when (c) {
            '\'' -> {
                sb.append("'\\''")
            }

            '!' -> {
                sb.append("\\!")
            }

            '\n' -> {
                // csh can't quote a newline inside single quotes — close,
                // backslash-newline, reopen.
                sb.append("'\\\n'")
            }

            else -> {
                sb.append(c)
            }
        }
    }
    sb.append('\'')
    return sb.toString()
}

/** Render [items] as a single line of shell-quoted tokens. */
public fun renderOutput(
    items: List<ParsedItem>,
    quote: (String) -> String,
    unquoted: Boolean,
): String {
    val sb = StringBuilder()
    val tokens = mutableListOf<String>()
    val operands = mutableListOf<String>()
    for (it in items) {
        when (it) {
            is ParsedItem.Option -> {
                tokens += if (it.spec.isLong) "--${it.spec.name}" else "-${it.spec.name}"
                if (it.spec.argKind != ArgKind.NONE) {
                    tokens += it.value ?: ""
                }
            }

            is ParsedItem.Operand -> {
                operands += it.value
            }
        }
    }

    fun emit(s: String) {
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(if (unquoted) s else quote(s))
    }
    for (t in tokens) emit(t)
    if (sb.isNotEmpty()) sb.append(' ')
    sb.append("--")
    for (op in operands) emit(op)
    return sb.toString()
}
