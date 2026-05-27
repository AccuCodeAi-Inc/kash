package com.accucodeai.kash.tools.patch

/** Detected patch format for one file's set of hunks. */
internal enum class PatchFormat { UNIFIED, CONTEXT, NORMAL }

/**
 * One hunk reduced to the only thing the applier needs: the exact lines
 * expected in the original ([oldLines]) and the lines they become
 * ([newLines]). [oldStart] is the 1-based line where the old block begins
 * (0 for a pure append at top of file); used to locate the hunk.
 */
internal class Hunk(
    val oldStart: Int,
    val oldLines: List<String>,
    val newLines: List<String>,
)

/**
 * All hunks targeting one file, plus the old/new path names parsed from
 * the header (used for `-p` stripping when no explicit operand is given).
 */
internal class FilePatch(
    val oldName: String?,
    val newName: String?,
    val format: PatchFormat,
    val hunks: List<Hunk>,
)

/** Outcome of applying all hunks of one file. */
internal class ApplyResult(
    val lines: List<String>,
    val failed: List<Int>, // 1-based hunk indices that did not apply
    val hadTrailingNewline: Boolean,
)
