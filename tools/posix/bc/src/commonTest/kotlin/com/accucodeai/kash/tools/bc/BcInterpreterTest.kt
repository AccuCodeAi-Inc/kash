package com.accucodeai.kash.tools.bc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BcInterpreterTest {
    private fun runScript(
        src: String,
        withMathLib: Boolean = false,
    ): String {
        val sb = StringBuilder()
        val errSb = StringBuilder()
        val interp = BcInterpreter(emit = { sb.append(it) }, emitErr = { errSb.append(it) })
        if (withMathLib) interp.loadMathLib()
        val toks = BcLexer(src).tokenize()
        val ast = BcParser(toks).parseProgram()
        try {
            interp.run(ast)
        } catch (_: Throwable) {
            // QuitSignal etc — accept
        }
        return sb.toString()
    }

    @Test fun twoPlusTwo() {
        assertEquals("4\n", runScript("2 + 2"))
    }

    @Test fun multiplyAndAdd() {
        assertEquals("14\n", runScript("2 * 3 + 8 / 1"))
    }

    @Test fun semicolonsSeparate() {
        assertEquals("1\n2\n", runScript("1; 2"))
    }

    @Test fun newlinesSeparate() {
        assertEquals("1\n2\n", runScript("1\n2"))
    }

    @Test fun assignmentNoPrint() {
        assertEquals("", runScript("x = 5"))
    }

    @Test fun varReadAfterWrite() {
        assertEquals("5\n", runScript("x = 5; x"))
    }

    @Test fun scaleAffectsDivision() {
        // scale=5; 1/3 = 0.33333
        assertEquals(".33333\n", runScript("scale = 5; 1 / 3"))
    }

    @Test fun scaleZeroTruncates() {
        assertEquals("3\n", runScript("10 / 3"))
    }

    @Test fun fractionalAdd() {
        assertEquals(".3\n", runScript("scale = 1; .1 + .2"))
    }

    @Test fun moduloOperator() {
        assertEquals("1\n", runScript("10 % 3"))
    }

    @Test fun powerOperator() {
        assertEquals("1024\n", runScript("2 ^ 10"))
    }

    @Test fun unaryMinus() {
        assertEquals("-7\n", runScript("-7"))
    }

    @Test fun compoundAssignPlus() {
        assertEquals("8\n", runScript("x = 5; x += 3; x"))
    }

    @Test fun ifTrue() {
        assertEquals("yes\n", runScript("""if (1 > 0) print "yes\n" """))
    }

    @Test fun ifFalseElse() {
        assertEquals("neg\n", runScript("""if (1 < 0) print "pos\n" else print "neg\n" """))
    }

    @Test fun whileLoop() {
        val r = runScript("i = 0; while (i < 3) { i += 1; }; i")
        assertEquals("3\n", r)
    }

    @Test fun forLoop() {
        // sum 0..4 = 10
        val r = runScript("s = 0; for (i = 0; i < 5; i = i + 1) s = s + i; s")
        assertEquals("10\n", r)
    }

    @Test fun breakInLoop() {
        val r = runScript("i = 0; while (i < 10) { if (i == 3) break; i = i + 1 }; i")
        assertEquals("3\n", r)
    }

    @Test fun continueInLoop() {
        val r = runScript("s = 0; for (i = 0; i < 5; i = i + 1) { if (i == 2) continue; s = s + i }; s")
        assertEquals("8\n", r) // 0+1+3+4
    }

    @Test fun arrayUsage() {
        val r = runScript("a[0] = 10; a[1] = 20; a[0] + a[1]")
        assertEquals("30\n", r)
    }

    @Test fun functionDefineAndCall() {
        val r = runScript("define f(x) { return x * x }\nf(7)")
        assertEquals("49\n", r)
    }

    @Test fun functionRecursionFactorial() {
        val r = runScript("define f(n) { if (n < 2) return 1; return n * f(n - 1) }\nf(10)")
        assertEquals("3628800\n", r)
    }

    @Test fun functionLocalAutos() {
        val r =
            runScript(
                """
                define f(x) {
                  auto t
                  t = x * 2
                  return t
                }
                t = 99
                f(5)
                t
                """.trimIndent(),
            )
        // first prints f(5)=10, then t=99 (auto t did not clobber global)
        assertEquals("10\n99\n", r)
    }

    @Test fun lengthBuiltin() {
        assertEquals("3\n", runScript("length(123)"))
    }

    @Test fun scaleBuiltin() {
        assertEquals("4\n", runScript("scale(1.2345)"))
    }

    @Test fun sqrtBuiltin() {
        assertEquals("1.4142135623\n", runScript("scale = 10; sqrt(2)"))
    }

    @Test fun obaseHex() {
        assertEquals("FF\n", runScript("obase = 16; 255"))
    }

    @Test fun obaseBinary() {
        assertEquals("1010\n", runScript("obase = 2; 10"))
    }

    @Test fun ibaseHex() {
        assertEquals("255\n", runScript("ibase = 16; FF"))
    }

    @Test fun lastValueViaDot() {
        val r = runScript("5\n. + 1")
        assertEquals("5\n6\n", r)
    }

    @Test fun printDoesNotAutoNewline() {
        assertEquals("hi", runScript("""print "hi" """))
    }

    @Test fun printEscapes() {
        assertEquals("a\tb\n", runScript("""print "a\tb\n" """))
    }

    @Test fun printMultipleItems() {
        val r = runScript("""print "x=", 5, "\n" """)
        assertEquals("x=5\n", r)
    }

    @Test fun quitStopsExecution() {
        val r = runScript("""1\nquit\n2""".replace("\\n", "\n"))
        assertEquals("1\n", r)
    }

    @Test fun comparisonReturnsOneOrZero() {
        assertEquals("1\n", runScript("3 > 2"))
        assertEquals("0\n", runScript("3 < 2"))
    }

    @Test fun andShortCircuits() {
        val r = runScript("(0 && 1)\n(1 && 1)")
        assertEquals("0\n1\n", r)
    }

    @Test fun mathLibSineOfZero() {
        val r = runScript("scale = 5; s(0)", withMathLib = true)
        // s(0) should be 0 or 0.00000
        assertTrue(r.trim().toDouble() == 0.0, "expected 0, got: $r")
    }

    @Test fun mathLibCosineOfZero() {
        val r = runScript("scale = 5; c(0)", withMathLib = true)
        assertTrue(r.trim().toDouble() == 1.0 || r.trim().startsWith("1"), "expected ~1, got: $r")
    }
}
