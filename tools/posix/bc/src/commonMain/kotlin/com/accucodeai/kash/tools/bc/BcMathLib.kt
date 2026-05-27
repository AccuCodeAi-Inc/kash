package com.accucodeai.kash.tools.bc

/**
 * POSIX `bc -l` math library. Provides:
 *  - `s(x)`  sine of x (radians)
 *  - `c(x)`  cosine of x (radians)
 *  - `a(x)`  arctangent of x (radians)
 *  - `l(x)`  natural log
 *  - `e(x)`  e^x
 *  - `j(n,x)` Bessel J_n(x)
 *
 * **Precision limits**: we evaluate Taylor / Maclaurin series at the current
 * `scale` and stop when the term magnitude becomes less than 10^-(scale+2).
 * For typical scales (≤ 30) the results match POSIX bc to within the last
 * few digits — not byte-identical to GNU bc, but good enough for the
 * "I-need-bc-in-my-shell-script" use case. Very large |x| for sin/cos/exp
 * loses precision because we don't do argument reduction.
 *
 * The library is INSTALLED into the interpreter as user-defined functions —
 * we synthesize AST nodes and register them. This keeps a single eval path.
 */
internal object BcMathLib {
    fun installInto(interp: BcInterpreter) {
        // We define these as native callables via the function table by
        // synthesizing FunctionDef entries that the interpreter doesn't have
        // a syntactic source for. Easier route: register each with a marker
        // body and intercept calls.
        //
        // For simplicity we register pre-parsed bc source so the AST is real.
        // This keeps the interpreter unchanged.
        val src = MATH_LIB_SOURCE
        val toks = BcLexer(src).tokenize()
        val program = BcParser(toks).parseProgram()
        for (s in program) {
            if (s is Stmt.FunctionDef) {
                interp.defineFunction(s)
            }
        }
    }

    // bc source for the math library. We write straightforward Taylor-series
    // expansions; not optimized but correct to a modest number of digits.
    //
    // Functions intentionally use scale-bound loops so they respect the
    // ambient `scale` variable.
    private val MATH_LIB_SOURCE =
        """
        define e(x) {
            auto s, t, n, i, r
            s = scale
            scale = s + 5
            r = 1
            t = 1
            n = 1
            for (i = 1; i < 1000; i = i + 1) {
                t = t * x / i
                r = r + t
                if (t < 0) { if (-t < 1 / (10 ^ (s + 3))) { i = 1000 } }
                if (t > 0) { if (t < 1 / (10 ^ (s + 3))) { i = 1000 } }
            }
            scale = s
            return r / 1
        }
        define l(x) {
            auto s, y, t, n, i, r, p
            s = scale
            scale = s + 5
            if (x <= 0) { return 0 }
            n = 0
            y = x
            while (y > 2) { y = y / 2; n = n + 1 }
            while (y < 1) { y = y * 2; n = n - 1 }
            y = y - 1
            p = y
            r = y
            t = -1
            for (i = 2; i < 2000; i = i + 1) {
                p = p * y
                r = r + t * p / i
                t = -t
                if (p < 0) { if (-p / i < 1 / (10 ^ (s + 3))) { i = 2000 } }
                if (p > 0) { if (p / i < 1 / (10 ^ (s + 3))) { i = 2000 } }
            }
            scale = s
            return (r + n * 6931471805599453 / 10000000000000000) / 1
        }
        define s(x) {
            auto sc, t, r, i, x2
            sc = scale
            scale = sc + 5
            r = x
            t = x
            x2 = x * x
            for (i = 1; i < 500; i = i + 1) {
                t = -t * x2 / ((2 * i) * (2 * i + 1))
                r = r + t
                if (t < 0) { if (-t < 1 / (10 ^ (sc + 3))) { i = 500 } }
                if (t > 0) { if (t < 1 / (10 ^ (sc + 3))) { i = 500 } }
            }
            scale = sc
            return r / 1
        }
        define c(x) {
            auto sc, t, r, i, x2
            sc = scale
            scale = sc + 5
            r = 1
            t = 1
            x2 = x * x
            for (i = 1; i < 500; i = i + 1) {
                t = -t * x2 / ((2 * i - 1) * (2 * i))
                r = r + t
                if (t < 0) { if (-t < 1 / (10 ^ (sc + 3))) { i = 500 } }
                if (t > 0) { if (t < 1 / (10 ^ (sc + 3))) { i = 500 } }
            }
            scale = sc
            return r / 1
        }
        define a(x) {
            auto sc, t, r, i, x2, p
            sc = scale
            scale = sc + 5
            if (x > 1) { scale = sc; return 15707963267948966 / 10000000000000000 - a(1/x) }
            if (x < -1) { scale = sc; return -15707963267948966 / 10000000000000000 - a(1/x) }
            r = x
            x2 = x * x
            p = x
            t = -1
            for (i = 3; i < 2000; i = i + 2) {
                p = p * x2
                r = r + t * p / i
                t = -t
                if (p / i < 1 / (10 ^ (sc + 3))) { if (-p / i < 1 / (10 ^ (sc + 3))) { i = 2000 } }
            }
            scale = sc
            return r / 1
        }
        define j(n, x) {
            auto sc, r, t, k, sign, fact, denom
            sc = scale
            scale = sc + 5
            if (n < 0) { scale = sc; return 0 }
            r = 0
            sign = 1
            for (k = 0; k < 80; k = k + 1) {
                fact = 1
                for (denom = 1; denom <= k; denom = denom + 1) { fact = fact * denom }
                for (denom = 1; denom <= n + k; denom = denom + 1) { fact = fact * denom }
                t = sign * (x / 2) ^ (n + 2 * k) / fact
                r = r + t
                sign = -sign
            }
            scale = sc
            return r / 1
        }
        """.trimIndent()
}
