package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Process substitution (bash §3.5.6): `<(...)` and `>(...)` runs the inner
 * script asynchronously, wires its stdio to an in-memory pipe, and expands
 * to a `/dev/fd/N` path that the consuming/producing command opens.
 *
 * The bash conformance corpus `external/bash/tests/procsub.tests` exercises
 * deeper edge cases (date interop, stress fd reuse, `read -u N` from
 * fd-redirected procsubs); these tests pin the spec-aligned core behaviors.
 */
class ProcessSubstitutionTest {
    private suspend fun out(script: String): String = Kash().exec(script).stdout

    @Test fun inputProcSubBasic() = runTest { assertEquals("hello\n", out("cat <(echo hello)")) }

    @Test fun inputProcSubMultiple() = runTest { assertEquals("a\nb\n", out("cat <(echo a) <(echo b)")) }

    @Test fun outputProcSubBasic() =
        // `echo` writes to procsub's path, inner `cat` reads and stores to a
        // file the outer shell then prints. File-based instead of inner-cat
        // → parent stdout to dodge a known race where the parent shell can
        // return before the async inner cat flushes through — same flakiness
        // bash itself can exhibit. Loop-poll the file (with a bounded retry)
        // because `wait` doesn't currently track procsub jobs. Proper
        // sync-on-command-end is future work.
        runTest {
            val r =
                Kash().exec(
                    "echo hello > >(cat > /tmp/procsub_out)\n" +
                        "for i in 1 2 3 4 5 6 7 8 9 10; do\n" +
                        "  [ -s /tmp/procsub_out ] && break\n" +
                        "  sleep 0.05\n" +
                        "done\n" +
                        "cat /tmp/procsub_out",
                )
            assertEquals("hello\n", r.stdout)
        }

    @Test fun procSubWithStdinRedir() =
        // procsub as the target of `<` — `wc -l` reads from the fd path.
        // kash's wc -l formats with leading whitespace (POSIX-allowed); trim
        // to the count only.
        runTest { assertEquals("3", out("wc -l < <(printf 'a\\nb\\nc\\n')").trim()) }

    @Test fun procSubInsideEval() =
        // `eval cat <(echo …)` — procsub is expanded after eval re-parses.
        runTest { assertEquals("evaltest\n", out("eval cat <(echo evaltest)")) }

    @Test fun procSubInsideFunction() =
        // Function takes procsub path as $1, opens it.
        runTest { assertEquals("fn\n", out("f() { cat \"\$1\"; }\nf <(echo fn)")) }

    @Test fun procSubPathShape() =
        // The expanded path is `/dev/fd/<N>` — exact fd number is
        // implementation-defined; just assert the shape.
        runTest {
            val s = out("echo <(true)").trimEnd('\n')
            assertTrue(s.matches(Regex("/dev/fd/\\d+")), "expected /dev/fd/N path, got: $s")
        }

    @Test fun procSubMultiStmtInnerBody() =
        // Multi-statement procsub body — both echos drain through the pipe.
        runTest { assertEquals("a\nb\n", out("cat <(echo a; echo b)")) }

    @Test fun procSubInsideQuotedArg() =
        // `"<(...)"` is literal — quoted, no expansion.
        runTest { assertEquals("<(echo no)\n", out("echo \"<(echo no)\"")) }

    @Test fun procSubInheritsExportedEnv() = runTest { assertEquals("42\n", out("export X=42\ncat <(echo \$X)")) }

    @Test fun nestedProcSub() = runTest { assertEquals("nested\n", out("cat <(cat <(echo nested))")) }

    @Test fun procSubInPipeline() =
        // `cat <(...) | wc -l` — procsub inside one stage of a pipeline.
        runTest { assertEquals("2", out("cat <(printf 'a\\nb\\n') | wc -l").trim()) }

    @Test fun procSubAssignedToVariable() =
        // `f=<(...)`; `cat "$f"` opens the recorded fd path. The fd outlives
        // the assignment statement (it's installed in the parent's fdTable).
        runTest { assertEquals("z\n", out("f=<(echo z); cat \"\$f\"")) }

    @Test fun outputProcSubReceivesMultipleWrites() =
        // Multiple writes to a `>(cat)` accumulate; cat echoes the lot. Same
        // race as [outputProcSubBasic]: file-based + poll to dodge it.
        runTest {
            val r =
                Kash().exec(
                    "{ echo one; echo two; echo three; } > >(cat > /tmp/procsub_multi)\n" +
                        "for i in 1 2 3 4 5 6 7 8 9 10; do\n" +
                        "  [ \"\$(wc -l < /tmp/procsub_multi 2>/dev/null)\" -ge 3 ] && break\n" +
                        "  sleep 0.05\n" +
                        "done\n" +
                        "cat /tmp/procsub_multi",
                )
            assertEquals("one\ntwo\nthree\n", r.stdout)
        }

    @Test fun inputProcSubWithReadBuiltin() =
        // Direct stdin via `<` works (different code path from `read -u N`).
        runTest { assertEquals("[hi]\n", out("read x < <(echo hi); echo \"[\$x]\"")) }
}
