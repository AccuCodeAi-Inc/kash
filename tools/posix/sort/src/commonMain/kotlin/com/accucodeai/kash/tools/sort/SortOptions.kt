package com.accucodeai.kash.tools.sort

/**
 * Parsed `sort` options. POSIX subset:
 *   -n  numeric sort (leading optional whitespace, optional sign, digits, optional fractional part)
 *   -r  reverse comparison
 *   -u  unique — drop later duplicates that compare equal under the configured keys
 *   -t SEP  field separator (default: runs of blanks, with leading blanks part of the field)
 *   -k POS[,POS]  key spec; POS = F[.C], 1-based; ",POS" gives the end field/char (inclusive)
 *
 * Multiple `-k` specs are honored in order (first-listed is primary).
 */
public data class SortOptions(
    val numeric: Boolean = false,
    val reverse: Boolean = false,
    val unique: Boolean = false,
    /** null => default whitespace field model. */
    val separator: Char? = null,
    val keys: List<KeySpec> = emptyList(),
)

/**
 * A key range. Fields and chars are 1-based. `endField == null` means "to end of line".
 * When `endField != null` and `endChar == null`, the end is the last character of `endField`.
 */
public data class KeySpec(
    val startField: Int,
    val startChar: Int = 1,
    val endField: Int? = null,
    val endChar: Int? = null,
)

public class SortOptionError(
    message: String,
) : RuntimeException(message)

internal object SortOptionParser {
    /** Returns (options, positional file args). */
    fun parse(args: List<String>): Pair<SortOptions, List<String>> {
        var numeric = false
        var reverse = false
        var unique = false
        var separator: Char? = null
        val keys = mutableListOf<KeySpec>()
        val files = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--" -> {
                    for (j in (i + 1) until args.size) files += args[j]
                    return SortOptions(numeric, reverse, unique, separator, keys) to files
                }

                a == "-" -> {
                    files += a
                }

                a == "-n" -> {
                    numeric = true
                }

                a == "-r" -> {
                    reverse = true
                }

                a == "-u" -> {
                    unique = true
                }

                a == "-k" -> {
                    if (i + 1 >= args.size) throw SortOptionError("sort: option requires an argument -- k")
                    keys += parseKeySpec(args[++i])
                }

                a.startsWith("-k") && a.length > 2 -> {
                    keys += parseKeySpec(a.substring(2))
                }

                a == "-t" -> {
                    if (i + 1 >= args.size) throw SortOptionError("sort: option requires an argument -- t")
                    val s = args[++i]
                    if (s.length != 1) throw SortOptionError("sort: -t argument must be a single character")
                    separator = s[0]
                }

                a.startsWith("-t") && a.length > 2 -> {
                    val s = a.substring(2)
                    if (s.length != 1) throw SortOptionError("sort: -t argument must be a single character")
                    separator = s[0]
                }

                // Compact flag bundles like -nr, -ru, -nru, -nrk2
                a.startsWith("-") && a.length > 1 && a[1] != '-' -> {
                    var j = 1
                    while (j < a.length) {
                        when (a[j]) {
                            'n' -> {
                                numeric = true
                            }

                            'r' -> {
                                reverse = true
                            }

                            'u' -> {
                                unique = true
                            }

                            'k' -> {
                                val rest = a.substring(j + 1)
                                if (rest.isNotEmpty()) {
                                    keys += parseKeySpec(rest)
                                    j = a.length
                                } else {
                                    if (i + 1 >=
                                        args.size
                                    ) {
                                        throw SortOptionError("sort: option requires an argument -- k")
                                    }
                                    keys += parseKeySpec(args[++i])
                                    j = a.length
                                }
                            }

                            't' -> {
                                val rest = a.substring(j + 1)
                                val sep: String =
                                    if (rest.isNotEmpty()) {
                                        rest
                                    } else {
                                        if (i + 1 >=
                                            args.size
                                        ) {
                                            throw SortOptionError("sort: option requires an argument -- t")
                                        }
                                        args[++i]
                                    }
                                if (sep.length !=
                                    1
                                ) {
                                    throw SortOptionError("sort: -t argument must be a single character")
                                }
                                separator = sep[0]
                                j = a.length
                            }

                            else -> {
                                throw SortOptionError("sort: invalid option -- '${a[j]}'")
                            }
                        }
                        j++
                    }
                }

                else -> {
                    files += a
                }
            }
            i++
        }
        return SortOptions(numeric, reverse, unique, separator, keys) to files
    }

    private fun parseKeySpec(s: String): KeySpec {
        val parts = s.split(",", limit = 2)
        val startPos = parsePos(parts[0])
        // Start char defaults to 1 when no `.C` given.
        val sc = startPos.charPos ?: 1
        if (parts.size == 1) return KeySpec(startPos.field, sc)
        val endPos = parsePos(parts[1])
        // End char: explicit when given, else null = "to end of end-field".
        return KeySpec(startPos.field, sc, endPos.field, endPos.charPos)
    }

    private data class Pos(
        val field: Int,
        val charPos: Int?,
    )

    private fun parsePos(s: String): Pos {
        val dot = s.indexOf('.')
        if (dot < 0) {
            val f = s.toIntOrNull() ?: throw SortOptionError("sort: bad field spec: $s")
            if (f < 1) throw SortOptionError("sort: field number must be >= 1: $s")
            return Pos(f, null)
        }
        val f =
            s.substring(0, dot).toIntOrNull()
                ?: throw SortOptionError("sort: bad field spec: $s")
        val c =
            s.substring(dot + 1).toIntOrNull()
                ?: throw SortOptionError("sort: bad char spec: $s")
        if (f < 1 || c < 1) throw SortOptionError("sort: field/char numbers must be >= 1: $s")
        return Pos(f, c)
    }
}
