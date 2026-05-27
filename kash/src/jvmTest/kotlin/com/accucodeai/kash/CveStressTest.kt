package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Stress: pass a CVE-2014-6277 sized BASH_FUNC value through. We now
 * parse it on sub-shell entry; the parse must either succeed fast or
 * fail fast — no OOM no quadratic blowup.
 */
class CveStressTest {
    @Test fun hugeBashFuncEnvVarParseIsBounded() =
        runTest {
            val a1000 = "A".repeat(100_000)
            val t0 = System.currentTimeMillis()
            val r =
                Kash().exec(
                    "sh -c foo 2>/dev/null",
                    ExecOptions(
                        env = mapOf("BASH_FUNC_foo%%" to "() { 000(){>0;}&000(){ 0;}<<$a1000 0"),
                        replaceEnv = false,
                    ),
                )
            val ms = System.currentTimeMillis() - t0
            println("CVE_STRESS: stdout=${r.stdout.take(40)} stderr=${r.stderr.take(40)} exit=${r.exitCode} ms=$ms")
            // Sanity: we expect parse to fail (malformed body) and foo to be
            // unresolved — the assertion here is just that we finish in
            // bounded time (anything under 5s is fine; bash blowups manifest
            // as 30s+ or OOM).
            check(ms < 5_000) { "parse took too long: ${ms}ms" }
        }
}
