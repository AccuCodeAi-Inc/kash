package com.accucodeai.kash.tools.diff

import com.accucodeai.kash.diff.Edit

/**
 * LCS edit script comparing lines by [opts]-derived keys but carrying the
 * *original* line text in the resulting [Edit]s. When [opts] is inactive
 * this is identical to plain `lcsEdits`. The `-B` (ignore blank lines)
 * rule is applied as a post-pass: a change block that only adds or only
 * deletes blank lines is downgraded to keeps.
 */
internal fun lcsEditsWithOptions(
    old: List<String>,
    new: List<String>,
    opts: IgnoreOptions,
): List<Edit> {
    val oldKeys = old.map { opts.key(it) }
    val newKeys = new.map { opts.key(it) }

    val n = old.size
    val m = new.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] =
                if (oldKeys[i] == newKeys[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
        }
    }
    val out = mutableListOf<Edit>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            oldKeys[i] == newKeys[j] -> {
                // Keys match; emit the OLD text as the kept line (diff shows
                // context from the first file).
                out += Edit.Keep(old[i])
                i++
                j++
            }

            lcs[i + 1][j] >= lcs[i][j + 1] -> {
                out += Edit.Delete(old[i])
                i++
            }

            else -> {
                out += Edit.Insert(new[j])
                j++
            }
        }
    }
    while (i < n) {
        out += Edit.Delete(old[i])
        i++
    }
    while (j < m) {
        out += Edit.Insert(new[j])
        j++
    }

    return if (opts.ignoreBlankLines) suppressBlankOnlyChanges(out) else out
}

/**
 * `-B`: turn a delete/insert into a keep when the line is blank, provided
 * the surrounding change run consists only of blank-line edits on that
 * side. We keep it conservative: any non-blank edit anywhere in a run
 * keeps the whole run intact (matching the common GNU behaviour for
 * blank-only differences).
 */
private fun suppressBlankOnlyChanges(edits: List<Edit>): List<Edit> {
    val out = mutableListOf<Edit>()
    var i = 0
    while (i < edits.size) {
        if (edits[i] is Edit.Keep) {
            out += edits[i]
            i++
            continue
        }
        // Gather the contiguous change run.
        val run = mutableListOf<Edit>()
        while (i < edits.size && edits[i] !is Edit.Keep) {
            run += edits[i]
            i++
        }
        val allBlank = run.all { it.text.isBlank() }
        if (allBlank) {
            // Suppress: a blank insert vanishes; a blank delete also vanishes.
            // Keeps preserve the old file's blank lines so context lines up.
            for (e in run) {
                if (e is Edit.Delete) out += Edit.Keep(e.text)
            }
        } else {
            out += run
        }
    }
    return out
}
