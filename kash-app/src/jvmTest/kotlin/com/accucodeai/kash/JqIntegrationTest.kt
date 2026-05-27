package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * First end-to-end proof that `jq` is reachable from a kash script via the
 * Koin-composed [com.accucodeai.kash.api.CommandRegistry]. Lives in
 * `:kash-app` because that's where the Koin graph is composed — `:core`
 * never sees `:tools:jq` directly.
 */
class JqIntegrationTest {
    @Test fun typeReportsJqAsBuiltin() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("type jq")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertTrue(r.stdout.contains("jq"), "stdout=${r.stdout}")
        }
    }

    @Test fun jqEvaluatesArithFromNullInput() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("jq -n -r '(1+1|tostring)'")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("2\n", r.stdout)
        }
    }

    @Test fun jqPipesFromEcho() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """echo '{"x":42}' | jq -r '.x'""",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("42\n", r.stdout)
        }
    }
}
