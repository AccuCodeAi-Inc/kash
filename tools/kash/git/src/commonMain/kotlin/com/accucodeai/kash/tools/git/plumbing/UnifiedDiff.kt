package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.diff.renderUnifiedDiff

/**
 * Line-oriented unified diff. Thin wrapper over
 * [com.accucodeai.kash.diff.renderUnifiedDiff] in `:shared:difflib`,
 * preserving git's historical hunk-header style (`@@ -1,1 +1,2 @@` — the
 * `,1` is always written, never collapsed to `@@ -1 @@`).
 */
public fun unifiedDiff(
    oldBytes: ByteArray,
    newBytes: ByteArray,
    oldLabel: String,
    newLabel: String,
    contextLines: Int = 3,
): String =
    renderUnifiedDiff(
        oldText = oldBytes.decodeToString(),
        newText = newBytes.decodeToString(),
        oldLabel = oldLabel,
        newLabel = newLabel,
        contextLines = contextLines,
        collapseUnitRanges = false,
    )
