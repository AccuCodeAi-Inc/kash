package com.accucodeai.kash.conformance

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Audit gate over `bash-tests-normalize.txt`.
 *
 * The normalize file is dangerous — a regex applied to BOTH expected and
 * actual can silently mask real behavior divergence. OVERFITPASS (P2,
 * `bash-tests-normalize.txt`) requires that every rule be explicitly
 * categorized so a reviewer can tell at a glance whether a rule is
 * canonicalizing a format difference or papering over a semantic gap.
 *
 * Each non-comment, non-blank rule line must be in a **block** (run of
 * consecutive non-blank lines) that contains a `# CATEGORY: <tag>`
 * annotation. A blank line ends the block — so a new rule appended
 * after a blank line must come with its own CATEGORY annotation, not
 * inherit the previous block's.
 *
 * Allowed categories:
 *
 *  - `format-only`     wording differs, semantics match
 *                      (e.g. arith-error message phrasing)
 *  - `quirk-accepted`  kash deliberately doesn't match a bash quirk
 *                      (e.g. xcase helper, byte-vs-codepoint policy,
 *                      RANDOM LCRNG seeding)
 *  - `semantic-mask`   masks a real behavior divergence; should be
 *                      tracked as a bug, not merged as-is going forward
 *  - `utility`         operates only on already-tokenized values (dedup
 *                      collapse, run-of-token coalesce)
 *
 * Adding a new rule? Add a `# CATEGORY: <tag>` line above it (or above
 * its leading explanatory comment block). If you can't pick a category,
 * the rule probably shouldn't merge.
 */
class NormalizeRuleAuditTest {
    private val allowedCategories =
        setOf("format-only", "quirk-accepted", "semantic-mask", "utility")

    @Test
    fun everyRuleHasACategory() {
        val stream =
            javaClass.classLoader.getResourceAsStream("bash-tests-normalize.txt")
                ?: fail("bash-tests-normalize.txt not found on test classpath")
        val lines = stream.bufferedReader().readLines()
        // Partition into blocks separated by blank lines; within each
        // block, require at least one `# CATEGORY:` annotation and apply
        // it to every rule in that block.
        val violations = mutableListOf<String>()
        var anyCategory = false
        var blockCategory: String? = null
        val blockRules = mutableListOf<Pair<Int, String>>() // lineNo, testName

        fun flushBlock() {
            if (blockRules.isNotEmpty() && blockCategory == null) {
                for ((ln, testName) in blockRules) {
                    violations.add(
                        "line $ln: rule for `$testName` is in a block with no " +
                            "`# CATEGORY: <tag>` annotation",
                    )
                }
            }
            blockCategory = null
            blockRules.clear()
        }

        for ((index, raw) in lines.withIndex()) {
            val lineNo = index + 1
            val line = raw.trimEnd('\n', '\r')

            if (line.isEmpty()) {
                flushBlock()
                continue
            }
            if (line.startsWith("# CATEGORY:")) {
                val tag = line.removePrefix("# CATEGORY:").trim()
                if (tag !in allowedCategories) {
                    violations.add(
                        "line $lineNo: unknown category `$tag` " +
                            "(allowed: ${allowedCategories.sorted().joinToString()})",
                    )
                } else {
                    blockCategory = tag
                    anyCategory = true
                }
                continue
            }
            if (line.startsWith("#")) continue

            // Rule line.
            val testName = line.substringBefore('\t', missingDelimiterValue = "")
            blockRules.add(lineNo to testName)
        }
        flushBlock()

        // Stat the file so changes to it re-run the gate.
        if (violations.isNotEmpty()) {
            fail(
                "bash-tests-normalize.txt has ${violations.size} uncategorized rule(s):\n" +
                    violations.joinToString("\n") { "  $it" } +
                    "\n\nFix: add `# CATEGORY: <tag>` above each rule (or rule block). " +
                    "See NormalizeRuleAuditTest KDoc for category meanings.",
            )
        }
        assertTrue(anyCategory, "expected at least one CATEGORY annotation in normalize.txt")
    }
}
