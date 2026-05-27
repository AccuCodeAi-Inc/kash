package com.accucodeai.kash.tools.jq

internal data class JqCorpusCase(
    val source: String, // origin file or "inline"
    val lineNumber: Int, // 1-based line of the filter
    val filter: String,
    val input: String,
    val expected: List<String>,
    val expectError: Boolean,
)

internal object JqCorpusParser {
    /**
     * Parse jq's tests/jq.test format:
     *
     *   # comment lines
     *   <blank lines>
     *   filter
     *   input-json
     *   expected-json-line-1
     *   expected-json-line-2
     *   <blank>
     *
     * A case beginning with `%%FAIL` or where the filter is preceded by a
     * line of `%%FAIL` is an error-case.
     */
    fun parse(
        source: String,
        text: String,
    ): List<JqCorpusCase> {
        val lines = text.split('\n')
        val cases = mutableListOf<JqCorpusCase>()
        var i = 0
        while (i < lines.size) {
            // skip blanks + comments
            while (i < lines.size && (lines[i].isBlank() || lines[i].trimStart().startsWith("#"))) i++
            if (i >= lines.size) break

            var expectError = false
            if (lines[i].trim() == "%%FAIL" || lines[i].startsWith("%%FAIL")) {
                expectError = true
                i++
                // skip optional comment line(s) that often follow %%FAIL
                while (i < lines.size && lines[i].trimStart().startsWith("#")) i++
            }
            if (i >= lines.size) break

            val filterLine = i + 1
            val filter = lines[i]
            i++
            if (i >= lines.size) break
            val input = lines[i]
            i++
            val expected = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() && !lines[i].trimStart().startsWith("#")) {
                expected += lines[i]
                i++
            }
            cases += JqCorpusCase(source, filterLine, filter, input, expected, expectError)
        }
        return cases
    }
}
