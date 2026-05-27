package com.accucodeai.kash.tools.join

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OutputSpecTest {
    @Test fun `single zero parses as JoinKey`() {
        val r = parseOutputSpec("0")
        assertEquals(listOf(OutputField.JoinKey), r)
    }

    @Test fun `M dot N parses as FileField`() {
        assertEquals(listOf(OutputField.FileField(1, 3)), parseOutputSpec("1.3"))
        assertEquals(listOf(OutputField.FileField(2, 1)), parseOutputSpec("2.1"))
    }

    @Test fun `comma separated tokens`() {
        val r = parseOutputSpec("1.1,2.2,0")
        assertEquals(
            listOf(
                OutputField.FileField(1, 1),
                OutputField.FileField(2, 2),
                OutputField.JoinKey,
            ),
            r,
        )
    }

    @Test fun `whitespace separated tokens`() {
        val r = parseOutputSpec("1.1 2.2  0")
        assertEquals(3, r!!.size)
    }

    @Test fun `auto returns null`() {
        assertNull(parseOutputSpec("auto"))
    }

    @Test fun `rejects bad file number`() {
        assertFailsWith<IllegalArgumentException> { parseOutputSpec("3.1") }
    }

    @Test fun `rejects missing dot`() {
        assertFailsWith<IllegalArgumentException> { parseOutputSpec("1") }
    }

    @Test fun `rejects empty`() {
        assertFailsWith<IllegalArgumentException> { parseOutputSpec("") }
    }

    @Test fun `rejects zero field`() {
        assertFailsWith<IllegalArgumentException> { parseOutputSpec("1.0") }
    }
}
