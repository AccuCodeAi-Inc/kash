package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.diff.lcsMatches as sharedLcsMatches

/**
 * Compute LCS alignment between two line lists. Thin wrapper over
 * [com.accucodeai.kash.diff.lcsMatches] in `:shared:difflib`; kept here
 * so `git blame`'s import path stays stable.
 */
public fun lcsMatches(
    left: List<String>,
    right: List<String>,
): List<Pair<Int, Int>> = sharedLcsMatches(left, right)
