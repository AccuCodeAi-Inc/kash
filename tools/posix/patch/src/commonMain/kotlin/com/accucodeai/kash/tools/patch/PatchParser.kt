package com.accucodeai.kash.tools.patch

internal class PatchParseException(
    message: String,
) : RuntimeException(message)

/**
 * Hand-written, line-oriented patch parser. Autodetects unified
 * (`@@ -a,b +c,d @@`), context (`*** / ---` with `***************`
 * separators) and normal (`1c1` / `2d1` / `0a1`) hunks, grouping them by
 * target file. Garbage between files (e.g. `diff` command echoes) is
 * skipped until the next recognizable header.
 */
internal fun parsePatch(text: String): List<FilePatch> {
    val lines = text.split('\n')
    // Drop a single trailing empty element produced by a final newline so we
    // don't treat it as a spurious blank patch line.
    val body = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
    val parser = Cursor(body)
    val out = mutableListOf<FilePatch>()
    while (!parser.atEnd()) {
        val fp = parseOneFile(parser) ?: break
        out += fp
    }
    return out
}

private class Cursor(
    val lines: List<String>,
) {
    var pos = 0

    fun atEnd() = pos >= lines.size

    fun peek(): String? = lines.getOrNull(pos)

    fun next(): String = lines[pos++]
}

private fun parseOneFile(c: Cursor): FilePatch? {
    var oldName: String? = null
    var newName: String? = null

    // Skip leading noise; capture ---/+++ or ***/--- header names if present.
    while (!c.atEnd()) {
        val line = c.peek()!!
        when {
            line.startsWith("--- ") -> {
                oldName = headerPath(line.substring(4))
                c.next()
                val plus = c.peek()
                if (plus != null && plus.startsWith("+++ ")) {
                    newName = headerPath(plus.substring(4))
                    c.next()
                    return parseUnifiedFile(c, oldName, newName)
                }
                // `--- ` followed by non-`+++ ` -> context format second half.
            }

            line.startsWith("*** ") -> {
                oldName = headerPath(line.substring(4))
                c.next()
                val dash = c.peek()
                if (dash != null && dash.startsWith("--- ")) {
                    newName = headerPath(dash.substring(4))
                    c.next()
                }
                return parseContextFile(c, oldName, newName)
            }

            line.startsWith("@@ ") -> {
                return parseUnifiedFile(c, oldName, newName)
            }

            line.startsWith("***************") -> {
                return parseContextFile(c, oldName, newName)
            }

            isNormalCommand(line) -> {
                return parseNormalFile(c)
            }

            else -> {
                c.next()
            } // skip noise
        }
    }
    return null
}

private fun headerPath(rest: String): String {
    // Strip a trailing timestamp (tab-separated) and surrounding whitespace.
    val tab = rest.indexOf('\t')
    val raw = if (tab >= 0) rest.substring(0, tab) else rest
    return raw.trim()
}

// ---- Unified ---------------------------------------------------------------

private val unifiedHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")

private fun parseUnifiedFile(
    c: Cursor,
    oldName: String?,
    newName: String?,
): FilePatch {
    val hunks = mutableListOf<Hunk>()
    while (!c.atEnd()) {
        val line = c.peek()!!
        if (!line.startsWith("@@ ")) break
        val m = unifiedHeader.matchEntire(line) ?: throw PatchParseException("malformed hunk header: $line")
        c.next()
        val oldStart = m.groupValues[1].toInt()
        val oldCount = m.groupValues[2].ifEmpty { "1" }.toInt()
        val newCount = m.groupValues[4].ifEmpty { "1" }.toInt()

        val oldLines = mutableListOf<String>()
        val newLines = mutableListOf<String>()
        var oTaken = 0
        var nTaken = 0
        while ((oTaken < oldCount || nTaken < newCount) && !c.atEnd()) {
            val raw = c.peek()!!
            if (raw.startsWith("@@ ")) break
            c.next()
            if (raw == "\\ No newline at end of file" || raw.startsWith("\\ No newline")) continue
            val tag = if (raw.isEmpty()) ' ' else raw[0]
            val content = if (raw.isEmpty()) "" else raw.substring(1)
            when (tag) {
                ' ' -> {
                    oldLines += content
                    newLines += content
                    oTaken++
                    nTaken++
                }

                '-' -> {
                    oldLines += content
                    oTaken++
                }

                '+' -> {
                    newLines += content
                    nTaken++
                }

                else -> {
                    break
                }
            }
        }
        hunks += Hunk(oldStart, oldLines, newLines)
    }
    return FilePatch(oldName, newName, PatchFormat.UNIFIED, hunks)
}

// ---- Context ---------------------------------------------------------------

private val contextOldRange = Regex("""^\*\*\* (\d+)(?:,(\d+))? \*\*\*\*.*$""")
private val contextNewRange = Regex("""^--- (\d+)(?:,(\d+))? ----.*$""")

private fun parseContextFile(
    c: Cursor,
    oldName: String?,
    newName: String?,
): FilePatch {
    val hunks = mutableListOf<Hunk>()
    while (!c.atEnd()) {
        if (c.peek() != "***************") break
        c.next()
        val oldHeader = c.peek() ?: break
        val om =
            contextOldRange.matchEntire(oldHeader) ?: throw PatchParseException("malformed context hunk: $oldHeader")
        c.next()
        val oldStart = om.groupValues[1].toInt()

        // Old section: lines until the `--- c,d ----` header.
        val oldSection = mutableListOf<Pair<Char, String>>()
        while (!c.atEnd() && contextNewRange.matchEntire(c.peek()!!) == null) {
            val raw = c.next()
            if (raw.startsWith("\\ No newline")) continue
            val tag = if (raw.length >= 2) raw[0] else ' '
            val content = if (raw.length >= 2) raw.substring(2) else ""
            oldSection += tag to content
        }
        if (c.atEnd()) throw PatchParseException("context hunk missing new section")
        c.next() // consume `--- c,d ----`
        val newSection = mutableListOf<Pair<Char, String>>()
        while (!c.atEnd() && c.peek() != "***************") {
            val nxt = c.peek()!!
            // A new file header / EOF terminates.
            if (nxt.startsWith("*** ") || nxt.startsWith("--- ") || nxt.startsWith("@@ ")) break
            val raw = c.next()
            if (raw.startsWith("\\ No newline")) continue
            val tag = if (raw.length >= 2) raw[0] else ' '
            val content = if (raw.length >= 2) raw.substring(2) else ""
            newSection += tag to content
        }

        val oldLines = oldSection.filter { it.first == ' ' || it.first == '-' || it.first == '!' }.map { it.second }
        val newLines = newSection.filter { it.first == ' ' || it.first == '+' || it.first == '!' }.map { it.second }
        hunks += Hunk(oldStart, oldLines, newLines)
    }
    return FilePatch(oldName, newName, PatchFormat.CONTEXT, hunks)
}

// ---- Normal ----------------------------------------------------------------

private val normalCommand = Regex("""^(\d+)(?:,(\d+))?([acd])(\d+)(?:,(\d+))?$""")

private fun isNormalCommand(line: String) = normalCommand.matchEntire(line) != null

private fun parseNormalFile(c: Cursor): FilePatch {
    val hunks = mutableListOf<Hunk>()
    while (!c.atEnd()) {
        val line = c.peek()!!
        val m = normalCommand.matchEntire(line) ?: break
        c.next()
        val oldStart = m.groupValues[1].toInt()
        val cmd = m.groupValues[3][0]

        val oldLines = mutableListOf<String>()
        val newLines = mutableListOf<String>()
        if (cmd == 'd' || cmd == 'c') {
            while (!c.atEnd() && c.peek()!!.startsWith("< ")) {
                oldLines += c.next().substring(2)
            }
        }
        if (cmd == 'c') {
            // separator line `---`
            if (!c.atEnd() && c.peek() == "---") c.next()
        }
        if (cmd == 'a' || cmd == 'c') {
            while (!c.atEnd() && c.peek()!!.startsWith("> ")) {
                newLines += c.next().substring(2)
            }
        }
        // For `a`, anchor is after line oldStart; for d/c it's at oldStart.
        val anchor = if (cmd == 'a') oldStart + 1 else oldStart
        hunks += Hunk(anchor, oldLines, newLines)
    }
    return FilePatch(null, null, PatchFormat.NORMAL, hunks)
}
