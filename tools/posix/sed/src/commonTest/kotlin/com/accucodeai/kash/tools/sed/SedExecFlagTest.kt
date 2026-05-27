package com.accucodeai.kash.tools.sed

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Engine-level tests for the GNU `e` flag on `s` substitutions. These bypass
 * `SedCommand`/the FS bridge and inject a fake [SedIo.execForS] so the test
 * exercises the engine's exec-flag handling without bringing in `:core`.
 */
class SedExecFlagTest {
    private class FakeExecIo(
        private val exec: suspend (String) -> String?,
    ) : SedIo {
        override suspend fun readForR(path: String): String? = null

        override suspend fun writeForW(
            path: String,
            line: String,
        ) = Unit

        override suspend fun execForS(commandLine: String): String? = exec(commandLine)

        override fun close() = Unit
    }

    private suspend fun runWith(
        script: String,
        input: List<String>,
        suppressDefault: Boolean = false,
        exec: suspend (String) -> String?,
    ): Pair<List<String>, SedRunResult> {
        val parsed = SedScriptParser.parse(script)
        val out = mutableListOf<String>()
        val result = SedEngine(parsed, suppressDefault, FakeExecIo(exec)).run(input.iterator()) { out.add(it) }
        return out to result
    }

    @Test fun e_flag_replaces_pattern_with_exec_output() =
        runTest {
            // Mock "shell" turns the post-substitution pattern into upper-case.
            val (out, rc) = runWith("s/foo/bar/e", listOf("foo")) { it.uppercase() }
            assertEquals(0, rc.exitCode)
            assertEquals(listOf("BAR"), out)
        }

    @Test fun e_flag_strips_one_trailing_newline() =
        runTest {
            // execForS returns text without the trailing newline by contract
            // (the SedIo doc says callers strip one). The engine should not
            // re-strip — verify it accepts the raw return.
            val (out, _) = runWith("s/foo/bar/e", listOf("foo")) { "RESULT" }
            assertEquals(listOf("RESULT"), out)
        }

    @Test fun e_flag_does_not_fire_when_substitution_does_not_match() =
        runTest {
            var called = false
            val (out, _) =
                runWith("s/zzz/x/e", listOf("hello")) {
                    called = true
                    "should not happen"
                }
            assertEquals(false, called)
            assertEquals(listOf("hello"), out)
        }

    @Test fun e_flag_null_runner_aborts_with_exit_2() =
        runTest {
            val (_, rc) = runWith("s/foo/bar/e", listOf("foo")) { null }
            assertEquals(2, rc.exitCode)
        }

    @Test fun e_and_w_flags_are_mutually_exclusive() =
        runTest {
            // `ew PATH`: `e` set first, then `w` should reject. (The reverse
            // form `we...` doesn't apply — `w` greedily consumes the rest of
            // the line as the filename, so "e" would become part of the path.)
            kotlin.test.assertFailsWith<SedScriptError> {
                SedScriptParser.parse("s/x/y/ew /tmp/out")
            }
        }
}
