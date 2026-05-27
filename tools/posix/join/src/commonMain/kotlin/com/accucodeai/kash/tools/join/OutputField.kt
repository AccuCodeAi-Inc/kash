package com.accucodeai.kash.tools.join

/**
 * One element of the `-o` output list. Either the join key (`0`) or a
 * (fileNumber, fieldNumber) pair (M.N).
 */
public sealed interface OutputField {
    public data object JoinKey : OutputField

    public data class FileField(
        val fileNo: Int,
        val fieldNo: Int,
    ) : OutputField
}

/**
 * Parse the value of `-o LIST`. POSIX accepts a single argument containing
 * comma- or whitespace-separated tokens; GNU also accepts the literal token
 * `auto`. We accept comma-separated, plus a few whitespace-separated tokens
 * within the same string.
 *
 * Tokens are:
 *  - `0`        → the join key
 *  - `M.N`      → field N of file M (1 ≤ M ≤ 2, N ≥ 1)
 *  - `auto`     → return null (signals caller to use auto-format)
 *
 * Throws [IllegalArgumentException] for malformed tokens.
 */
public fun parseOutputSpec(s: String): List<OutputField>? {
    val trimmed = s.trim()
    if (trimmed == "auto") return null
    val tokens =
        trimmed
            .split(',', ' ', '\t')
            .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) throw IllegalArgumentException("empty -o spec")
    return tokens.map { tok ->
        if (tok == "0") {
            OutputField.JoinKey
        } else {
            val dot = tok.indexOf('.')
            if (dot <= 0 || dot == tok.length - 1) {
                throw IllegalArgumentException("invalid output field: $tok")
            }
            val fileStr = tok.substring(0, dot)
            val fieldStr = tok.substring(dot + 1)
            val fileNo = fileStr.toIntOrNull() ?: throw IllegalArgumentException("invalid file number in: $tok")
            val fieldNo = fieldStr.toIntOrNull() ?: throw IllegalArgumentException("invalid field number in: $tok")
            if (fileNo != 1 && fileNo != 2) throw IllegalArgumentException("file number must be 1 or 2: $tok")
            if (fieldNo < 1) throw IllegalArgumentException("field number must be ≥ 1: $tok")
            OutputField.FileField(fileNo, fieldNo)
        }
    }
}
