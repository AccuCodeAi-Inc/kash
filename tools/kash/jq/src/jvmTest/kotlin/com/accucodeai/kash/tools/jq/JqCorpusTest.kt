package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.KashJson
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFails

class JqCorpusTest {
    @TestFactory
    fun handwrittenCorpus(): List<DynamicTest> {
        val text =
            requireNotNull(javaClass.getResourceAsStream("/jq/corpus/basics.test")) {
                "missing /jq/corpus/basics.test"
            }.bufferedReader().readText()
        return JqCorpusParser.parse("basics.test", text).map { c ->
            DynamicTest.dynamicTest("L${c.lineNumber}: ${c.filter.take(60)}") {
                runCase(c)
            }
        }
    }

    private fun runCase(c: JqCorpusCase) {
        val input = KashJson.parse(c.input)
        if (c.expectError) {
            assertFails { Jq.run(c.filter, input).toList() }
            return
        }
        val actual = Jq.run(c.filter, input).toList().map { KashJson.encode(it) }
        val expected = c.expected.map { KashJson.encode(KashJson.parse(it)) }
        assertEquals(expected, actual, "filter `${c.filter}` on input `${c.input}` (${c.source}:${c.lineNumber})")
    }
}
