package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end proof that the new POSIX consumers reach the interpreter via
 * [com.accucodeai.kash.api.ShellRunner] / [com.accucodeai.kash.api.UtilityRunner]:
 *
 * - `sh -c '…'` parses and runs a sub-script in a subshell.
 * - `xargs UTIL …` dispatches to a registered utility per batch.
 * - `find … -exec UTIL {} ;` dispatches per match.
 * - `find … -exec sh -c '…' \;` composes — find calls UtilityRunner for `sh`,
 *   which itself calls ShellRunner. Round-trip through both hooks.
 */
class XargsFindShIntegrationTest {
    @Test fun shDashC_throughInterpreter() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "sh -c 'echo hello from sh'",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("hello from sh\n", r.stdout)
        }
    }

    @Test fun xargsEcho_pipesTokensInOneBatch() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "echo 'a b c' | xargs echo prefix:",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("prefix: a b c\n", r.stdout)
        }
    }

    @Test fun xargsDashN_splitsBatchesPerInvocation() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "echo 'a b c d' | xargs -n 2 echo",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // Two batches → two echo calls → two output lines.
            assertEquals("a b\nc d\n", r.stdout)
        }
    }

    @Test fun findDashName_listsMatches() {
        runTest {
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/work")
            k.fs.writeBytes("/work/keep.txt", ByteArray(0))
            k.fs.writeBytes("/work/skip.log", ByteArray(0))
            val r = k.exec("find /work -type f -name '*.txt'")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("/work/keep.txt\n", r.stdout)
        }
    }

    @Test fun findExec_perMatchInvokesEcho() {
        runTest {
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/data")
            k.fs.writeBytes("/data/a", ByteArray(0))
            k.fs.writeBytes("/data/b", ByteArray(0))
            val r =
                k.exec(
                    "find /data -type f -exec echo found: '{}' ';'",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // Two matches, two echo calls.
            val lines =
                r.stdout
                    .trimEnd()
                    .lines()
                    .sorted()
            assertEquals(listOf("found: /data/a", "found: /data/b"), lines)
        }
    }

    @Test fun findExec_plusBatchesIntoOneCall() {
        runTest {
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/d")
            k.fs.writeBytes("/d/x", ByteArray(0))
            k.fs.writeBytes("/d/y", ByteArray(0))
            val r = k.exec("find /d -type f -exec echo '{}' +")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // One echo with both paths → one line.
            val lines = r.stdout.trimEnd().lines()
            assertEquals(1, lines.size)
            val parts = lines[0].split(" ").sorted()
            assertEquals(listOf("/d/x", "/d/y"), parts)
        }
    }

    @Test fun findExec_shDashC_composesBothHooks() {
        runTest {
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/d")
            k.fs.writeBytes("/d/file", ByteArray(0))
            // find dispatches `sh -c "echo found"` per match. find uses
            // UtilityRunner to invoke sh; sh uses ShellRunner to parse and run.
            val r = k.exec("find /d -type f -exec sh -c 'echo found' ';'")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("found\n", r.stdout)
        }
    }

    @Test fun findExec_shDashC_perMatch_receivesPathAsDollar1() {
        runTest {
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/d")
            k.fs.writeBytes("/d/alpha", ByteArray(0))
            k.fs.writeBytes("/d/beta", ByteArray(0))
            // POSIX form: `sh -c SCRIPT NAME ARG…`. The `_` becomes $0 inside
            // the sub-script; `{}` is the match path that becomes $1.
            val r = k.exec($$"find /d -type f -exec sh -c 'echo got=$1' _ '{}' ';'")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines =
                r.stdout
                    .trimEnd()
                    .lines()
                    .sorted()
            assertEquals(listOf("got=/d/alpha", "got=/d/beta"), lines)
        }
    }

    @Test fun xargsDashI_shDashC_receivesPathAsDollar1() {
        runTest {
            // xargs -I {} sh -c '... $1 ...' _ {}
            // Previously $1 was empty / inherited; with positional-args
            // plumbing it now actually carries the substituted path.
            val r =
                Kash(registry = standardRegistry()).exec(
                    $$"printf 'one\ntwo\n' | xargs -I {} sh -c 'echo got=$1' _ {}",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines =
                r.stdout
                    .trimEnd()
                    .lines()
                    .sorted()
            assertEquals(listOf("got=one", "got=two"), lines)
        }
    }

    @Test fun findExec_shDashC_plus_iteratesDollarAt() {
        runTest {
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/d")
            k.fs.writeBytes("/d/one", ByteArray(0))
            k.fs.writeBytes("/d/two", ByteArray(0))
            // `+` form: find aggregates all paths into one `sh -c` call.
            // Sub-script iterates over "$@" which is now actually plumbed.
            val r =
                k.exec(
                    $$"find /d -type f -exec sh -c 'for p in \"$@\"; do echo p=$p; done' _ '{}' +",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines =
                r.stdout
                    .trimEnd()
                    .lines()
                    .sorted()
            assertEquals(listOf("p=/d/one", "p=/d/two"), lines)
        }
    }
}
