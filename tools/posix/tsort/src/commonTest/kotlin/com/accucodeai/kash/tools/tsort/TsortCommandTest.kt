package com.accucodeai.kash.tools.tsort

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun runTsort(
    input: String = "",
    vararg args: String,
    fs: InMemoryFs = InMemoryFs(),
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val stdin = Buffer().apply { writeUtf8(input) }
    val ctx =
        bareCommandContext(
            fs = fs,
            stdin = stdin.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val rc = TsortCommand().run(args.toList(), ctx)
    return Triple(rc.exitCode, out.readString(), err.readString())
}

class TsortCommandTest {
    // ---- pure helper tests ----

    @Test fun helper_emptyInput() {
        val r = topologicalSort(emptyList(), emptyList())
        assertEquals(emptyList(), r.order)
        assertTrue(r.cycle.isEmpty())
    }

    @Test fun helper_singletonNoEdges() {
        val r = topologicalSort(listOf("A"), emptyList())
        assertEquals(listOf("A"), r.order)
    }

    @Test fun helper_linearChain() {
        val r = topologicalSort(listOf("A", "B", "C", "D"), listOf("A" to "B", "B" to "C", "C" to "D"))
        assertEquals(listOf("A", "B", "C", "D"), r.order)
        assertTrue(r.cycle.isEmpty())
    }

    @Test fun helper_diamond() {
        val r = topologicalSort(listOf("A", "B", "C", "D"), listOf("A" to "B", "A" to "C", "B" to "D", "C" to "D"))
        // A first, D last; B/C in stable insertion order.
        assertEquals(listOf("A", "B", "C", "D"), r.order)
    }

    @Test fun helper_cycleBroken() {
        val r = topologicalSort(listOf("A", "B"), listOf("A" to "B", "B" to "A"))
        // Both nodes present; cycle non-empty.
        assertEquals(setOf("A", "B"), r.order.toSet())
        assertTrue(r.cycle.isNotEmpty())
    }

    @Test fun helper_disconnected() {
        val r =
            topologicalSort(
                listOf("A", "B", "C", "D"),
                listOf("A" to "B", "C" to "D"),
            )
        // Topo order valid; A before B, C before D.
        assertTrue(r.order.indexOf("A") < r.order.indexOf("B"))
        assertTrue(r.order.indexOf("C") < r.order.indexOf("D"))
    }

    // ---- command-level tests ----

    @Test fun cli_linearChain() =
        runTest {
            val (rc, out, _) = runTsort("A B\nB C\nC D\n")
            assertEquals(0, rc)
            assertEquals("A\nB\nC\nD\n", out)
        }

    @Test fun cli_diamond() =
        runTest {
            val (rc, out, _) = runTsort("A B\nA C\nB D\nC D\n")
            assertEquals(0, rc)
            assertEquals("A\nB\nC\nD\n", out)
        }

    @Test fun cli_multipleTokensPerLine() =
        runTest {
            val (rc, out, _) = runTsort("A B B C C D\n")
            assertEquals(0, rc)
            assertEquals("A\nB\nC\nD\n", out)
        }

    @Test fun cli_selfEdge_emittedOnce() =
        runTest {
            val (rc, out, _) = runTsort("A A\n")
            assertEquals(0, rc)
            assertEquals("A\n", out)
        }

    @Test fun cli_cycle_warnsAndExits1() =
        runTest {
            val (rc, out, err) = runTsort("A B\nB A\n")
            assertEquals(1, rc)
            assertContains(err, "loop")
            // Both nodes appear in output.
            assertTrue(out.contains("A\n"))
            assertTrue(out.contains("B\n"))
        }

    @Test fun cli_disconnected() =
        runTest {
            val (rc, out, _) = runTsort("A B\nC D\n")
            assertEquals(0, rc)
            val lines = out.trim().split('\n')
            assertEquals(setOf("A", "B", "C", "D"), lines.toSet())
            assertTrue(lines.indexOf("A") < lines.indexOf("B"))
            assertTrue(lines.indexOf("C") < lines.indexOf("D"))
        }

    @Test fun cli_oddTokens_error() =
        runTest {
            val (rc, _, err) = runTsort("A B C\n")
            assertEquals(1, rc)
            assertContains(err, "odd")
        }

    @Test fun cli_emptyInput() =
        runTest {
            val (rc, out, _) = runTsort("")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun cli_tabSeparated() =
        runTest {
            val (rc, out, _) = runTsort("A\tB\nB\tC\n")
            assertEquals(0, rc)
            assertEquals("A\nB\nC\n", out)
        }

    @Test fun cli_stableTieBreak() =
        runTest {
            // Three roots, all in-degree zero. Insertion order is X, Y, Z.
            val (rc, out, _) = runTsort("X T\nY T\nZ T\n")
            assertEquals(0, rc)
            assertEquals("X\nY\nZ\nT\n", out)
        }

    @Test fun cli_readsFromFile() =
        runTest {
            val fs = InMemoryFs()
            val sink = fs.sink("/edges.txt", append = false, mode = 0b110_100_100)
            val buf = Buffer().apply { writeUtf8("A B\nB C\n") }
            sink.write(buf, buf.size)
            sink.flush()
            sink.close()
            val (rc, out, _) = runTsort(args = arrayOf("/edges.txt"), fs = fs)
            assertEquals(0, rc)
            assertEquals("A\nB\nC\n", out)
        }

    @Test fun cli_missingFile_errors() =
        runTest {
            val (rc, _, err) = runTsort(args = arrayOf("/no/such/file"))
            assertEquals(1, rc)
            assertContains(err, "No such file")
        }

    @Test fun cli_dashMeansStdin() =
        runTest {
            val (rc, out, _) = runTsort("A B\n", "-")
            assertEquals(0, rc)
            assertEquals("A\nB\n", out)
        }

    @Test fun cli_unknownFlag_errors() =
        runTest {
            val (rc, _, err) = runTsort(args = arrayOf("--nope"))
            assertEquals(2, rc)
            assertContains(err, "unrecognized")
        }

    @Test fun cli_extraOperand_errors() =
        runTest {
            val (rc, _, err) = runTsort(args = arrayOf("a", "b"))
            assertEquals(2, rc)
            assertContains(err, "extra operand")
        }

    @Test fun cli_help() =
        runTest {
            val (rc, out, _) = runTsort(args = arrayOf("--help"))
            assertEquals(0, rc)
            assertContains(out, "Usage")
        }
}
