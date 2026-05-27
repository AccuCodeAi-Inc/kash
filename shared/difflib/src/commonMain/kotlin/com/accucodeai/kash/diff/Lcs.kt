package com.accucodeai.kash.diff

/**
 * One step in an LCS-based edit script. [Keep] lines appear in both
 * inputs, [Delete] lines only in the old, [Insert] lines only in the new.
 */
public sealed class Edit {
    public abstract val text: String

    public class Keep(
        override val text: String,
    ) : Edit()

    public class Delete(
        override val text: String,
    ) : Edit()

    public class Insert(
        override val text: String,
    ) : Edit()
}

/**
 * Compute an LCS-based edit script aligning [old] to [new].
 *
 * Not a Myers-optimal implementation — a straightforward O(N*M) dynamic
 * program. Fine for the file sizes a shell session realistically diffs;
 * the algorithm upgrade can come later behind the same surface.
 */
public fun lcsEdits(
    old: List<String>,
    new: List<String>,
): List<Edit> {
    val n = old.size
    val m = new.size
    // DP: lcs[i][j] = LCS length of old[i..] vs new[j..]
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] =
                if (old[i] == new[j]) {
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
            old[i] == new[j] -> {
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
    return out
}

/**
 * Compute LCS alignment between two line lists. Returns `(leftIdx,
 * rightIdx)` pairs where `left[leftIdx] == right[rightIdx]`, in
 * left-then-right increasing order.
 */
public fun lcsMatches(
    left: List<String>,
    right: List<String>,
): List<Pair<Int, Int>> {
    val n = left.size
    val m = right.size
    if (n == 0 || m == 0) return emptyList()
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] =
                if (left[i] == right[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
        }
    }
    val out = mutableListOf<Pair<Int, Int>>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            left[i] == right[j] -> {
                out += i to j
                i++
                j++
            }

            lcs[i + 1][j] >= lcs[i][j + 1] -> {
                i++
            }

            else -> {
                j++
            }
        }
    }
    return out
}
