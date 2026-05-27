package com.accucodeai.kash.commands

// ---------------------------------------------------------------------------
// Shared helpers for read. (echo's unescape, printf's formatters, and
// test's evaluator moved with their commands when those migrated to
// :tools:posix. Only read-related text helpers remain here — read itself
// runs as an intrinsic (`interpreter/IntrinsicsRead.kt`) so it can
// mutate env and indexedArrays directly; these helpers stay here for
// historical organization and to keep the read intrinsic's import
// surface small.)

/** Backslash escapes when not in `read -r` mode: `\<char>` → `<char>`. */
internal fun processReadBackslashes(s: String): String {
    if ('\\' !in s) return s
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            sb.append(s[i + 1])
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

/**
 * IFS-splitting used by `read`. Mirrors bash's
 * `word scanner` / `word-list builder` which
 * implements POSIX §2.6.5: IFS-whitespace coalesces, IFS-nonwhitespace
 * is an explicit delimiter, and the ws+nonws+ws combination forms a
 * single delimiter. The first [maxVars-1] fields are split normally;
 * the final field absorbs the remainder with trailing IFS-whitespace
 * stripped (so `IFS=": " read x y <<< "a :"` yields x="a", y="").
 */
internal fun splitForRead(
    line: String,
    ifs: String,
    maxVars: Int,
): List<String> {
    val ifsSet = ifs.toSet()
    val isWs: (Char) -> Boolean = { it in ifsSet && (it == ' ' || it == '\t' || it == '\n') }
    val isSepAt: (Int) -> Boolean = { idx -> idx < line.length && line[idx] in ifsSet }
    val isWsSepAt: (Int) -> Boolean = { idx -> isSepAt(idx) && isWs(line[idx]) }

    /**
     * Extract one field at [start], returning (field, newPos). Mirrors
     * bash's word scanner: captures chars up
     * to the next IFS sep, advances past it + any IFS-ws, and (if the
     * first sep was IFS-ws and the next char is IFS-nonws) absorbs
     * that too with its trailing ws.
     */
    fun getWord(start: Int): Pair<String, Int> {
        var p = start
        val sb = StringBuilder()
        while (p < line.length && !isSepAt(p)) {
            sb.append(line[p])
            p++
        }
        if (p >= line.length) return sb.toString() to p
        val firstSepWasWs = isWs(line[p])
        p++
        while (p < line.length && isWsSepAt(p)) p++
        if (firstSepWasWs && isSepAt(p) && !isWs(line[p])) {
            p++
            while (p < line.length && isWsSepAt(p)) p++
        }
        return sb.toString() to p
    }

    fun stripTrailingWs(s: String): String {
        var end = s.length
        while (end > 0 && isWs(s[end - 1])) end--
        return s.substring(0, end)
    }

    var p = 0
    // Strip leading IFS-whitespace.
    while (p < line.length && isWsSepAt(p)) p++

    val out = mutableListOf<String>()
    // Extract all but the last field with the normal one-word-at-a-time loop.
    while (p < line.length && out.size < maxVars - 1) {
        val (word, next) = getWord(p)
        out += word
        p = next
    }
    // Bash's two-stage last-field rule for `read`:
    //   - If remaining input is empty, bind "".
    //   - Else: save t1 = remaining input; extract one word (advances p).
    //     If after that p == end-of-input: bind the extracted word
    //     (the trailing-delim case: `a:` → y="b" not "b:").
    //     Else: bind t1 with trailing IFS-whitespace stripped — keeps
    //     internal delimiters as-is (`a:b:c:` → y="b:c:" not "b:c").
    if (out.size < maxVars) {
        if (p >= line.length) {
            out += ""
        } else {
            val t1 = line.substring(p)
            val (word, next) = getWord(p)
            out += if (next >= line.length) word else stripTrailingWs(t1)
        }
    }
    return out
}
