package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.KashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@name` format strings (`@text`, `@json`, `@base64`/`@base64d`, `@uri`,
 * `@csv`, `@tsv`, `@sh`, `@html`) as filters, against real jq semantics.
 */
class JqFormatStringsTest {
    /** Raw string result (the unquoted content of a single string output). */
    private fun runRaw(
        filter: String,
        input: String,
    ): String =
        Jq
            .run(filter, KashJson.parse(input))
            .toList()
            .single()
            .let { Jq.format(it, raw = true) }

    @Test fun text_stringifies_scalars_and_structures() {
        assertEquals("123", runRaw("@text", "123"))
        assertEquals("hi", runRaw("@text", "\"hi\""))
        assertEquals("[1,2]", runRaw("@text", "[1,2]"))
    }

    @Test fun json_emits_compact_json() {
        assertEquals("""{"a":1}""", runRaw("@json", """{"a":1}"""))
        assertEquals("\"x\"", runRaw("@json", "\"x\""))
    }

    @Test fun base64_encodes_utf8() {
        assertEquals("aGVsbG8=", runRaw("@base64", "\"hello\""))
    }

    @Test fun base64_decode_roundtrips() {
        assertEquals("hello", runRaw("@base64 | @base64d", "\"hello\""))
        assertEquals("a", runRaw("@base64d", "\"YQ==\""))
    }

    @Test fun base64_unicode_roundtrips() {
        assertEquals("héllo→", runRaw("@base64 | @base64d", "\"héllo→\""))
    }

    @Test fun uri_percent_encodes_reserved() {
        assertEquals("a%20b%2Fc%3F", runRaw("@uri", "\"a b/c?\""))
        // Unreserved chars pass through untouched.
        assertEquals("Az0-_.~", runRaw("@uri", "\"Az0-_.~\""))
    }

    @Test fun csv_quotes_strings_and_doubles_quotes() {
        // 1 , "x,y" , "he said ""hi""" , true , <empty for null>
        val expected = "1,\"x,y\",\"he said \"\"hi\"\"\",true,"
        assertEquals(expected, runRaw("@csv", """[1,"x,y","he said \"hi\"",true,null]"""))
    }

    @Test fun csv_rejects_non_array() {
        val ex = runCatching { runRaw("@csv", "\"nope\"") }.exceptionOrNull()
        assertTrue(ex is JqRuntimeError, "expected JqRuntimeError, got $ex")
    }

    @Test fun tsv_tab_separates_and_escapes() {
        assertEquals("a\tb", runRaw("@tsv", """["a","b"]"""))
        // A literal tab inside a field is escaped to backslash-t.
        assertEquals("x\\ty", runRaw("@tsv", "[\"x\\ty\"]"))
    }

    @Test fun sh_quotes_array_elements() {
        // 'a b' , 'c'\''d' , 42
        val expected = "'a b' 'c'\\''d' 42"
        assertEquals(expected, runRaw("@sh", """["a b","c'd",42]"""))
    }

    @Test fun sh_rejects_object() {
        val ex = runCatching { runRaw("@sh", """[{"a":1}]""") }.exceptionOrNull()
        assertTrue(ex is JqRuntimeError, "expected JqRuntimeError, got $ex")
    }

    @Test fun html_escapes_markup() {
        assertEquals("&lt;a&gt;&amp;&apos;&quot;", runRaw("@html", "\"<a>&'\\\"\""))
    }

    @Test fun unknown_format_is_a_runtime_error() {
        val ex = runCatching { runRaw("@nope", "1") }.exceptionOrNull()
        assertTrue(ex is JqRuntimeError, "expected JqRuntimeError, got $ex")
    }
}
