package com.accucodeai.kash.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KashJsonTest {
    @Test fun parse_primitives() {
        assertEquals(jsonNumber(42L), KashJson.parse("42"))
        assertEquals(jsonString("hi"), KashJson.parse(""""hi""""))
        assertEquals(jsonBool(true), KashJson.parse("true"))
        assertEquals(jsonNull(), KashJson.parse("null"))
    }

    @Test fun parse_object_and_array() {
        val v = KashJson.parse("""{"a":[1,2,3],"b":{"c":true}}""")
        assertEquals("object", v.typeName())
    }

    @Test fun encode_compact_and_pretty() {
        val v = KashJson.parse("""{"a":1}""")
        assertEquals("""{"a":1}""", KashJson.encode(v))
        assertTrue("{\n" in KashJson.encode(v, pretty = true))
    }

    @Test fun parse_stream_splits_values() {
        val s = """1 "two" {"x":3} [4,5]"""
        val items = KashJson.parseStream(s).toList()
        assertEquals(4, items.size)
        assertEquals(jsonNumber(1L), items[0])
        assertEquals(jsonString("two"), items[1])
        assertEquals("object", items[2].typeName())
        assertEquals("array", items[3].typeName())
    }

    @Test fun roundtrip_nested() {
        val src = """{"a":[1,2.5,null,true,"x"],"b":{}}"""
        val v = KashJson.parse(src)
        assertEquals(src, KashJson.encode(v))
    }

    @Test fun truthiness() {
        assertTrue(!jsonNull().isTruthy())
        assertTrue(!jsonBool(false).isTruthy())
        assertTrue(jsonBool(true).isTruthy())
        assertTrue(jsonNumber(0L).isTruthy()) // jq: numbers are truthy
        assertTrue(jsonString("").isTruthy()) // jq: even empty string
    }

    @Test fun typeName_distinguishes_number_and_string() {
        assertEquals("number", jsonNumber(1L).typeName())
        assertEquals("string", jsonString("1").typeName())
        assertEquals("boolean", jsonBool(false).typeName())
    }
}
