package com.accucodeai.kash.tools.seq

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

private suspend fun runSeq(vararg args: String): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            env = mutableMapOf(),
            cwd = "/",
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = SeqCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class SeqCommandTest {
    @Test fun seqN_emitsOneThroughN() =
        runTest {
            val (rc, out, _) = runSeq("5")
            assertEquals(0, rc)
            assertEquals("1\n2\n3\n4\n5\n", out)
        }

    @Test fun seqAB_simpleRange() =
        runTest {
            val (rc, out, _) = runSeq("3", "6")
            assertEquals(0, rc)
            assertEquals("3\n4\n5\n6\n", out)
        }

    @Test fun seqASB_positiveStep() =
        runTest {
            val (_, out, _) = runSeq("1", "2", "9")
            assertEquals("1\n3\n5\n7\n9\n", out)
        }

    @Test fun seqASB_doesNotOvershoot() =
        runTest {
            val (_, out, _) = runSeq("1", "3", "10")
            assertEquals("1\n4\n7\n10\n", out)
        }

    @Test fun seqASB_negativeStep() =
        runTest {
            val (_, out, _) = runSeq("5", "-1", "1")
            assertEquals("5\n4\n3\n2\n1\n", out)
        }

    @Test fun seqASB_floatStep() =
        runTest {
            val (_, out, _) = runSeq("0", "0.5", "2")
            assertEquals("0.0\n0.5\n1.0\n1.5\n2.0\n", out)
        }

    @Test fun sepFlag_setsSeparator() =
        runTest {
            val (_, out, _) = runSeq("-s", ",", "1", "4")
            assertEquals("1,2,3,4\n", out)
        }

    @Test fun sepFlag_attached() =
        runTest {
            val (_, out, _) = runSeq("-s|", "1", "3")
            assertEquals("1|2|3\n", out)
        }

    @Test fun equalWidth_padsZeros() =
        runTest {
            val (_, out, _) = runSeq("-w", "8", "10")
            assertEquals("08\n09\n10\n", out)
        }

    @Test fun equalWidth_withFloats() =
        runTest {
            val (_, out, _) = runSeq("-w", "0", "0.5", "2")
            // widest endpoint is "2.0" / "0.0" → width 3
            assertEquals("0.0\n0.5\n1.0\n1.5\n2.0\n", out)
        }

    @Test fun emptySeq_whenDirectionWrong_noOutput() =
        runTest {
            val (rc, out, _) = runSeq("5", "1")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun zeroStep_errors() =
        runTest {
            val (rc, _, err) = runSeq("1", "0", "5")
            assertEquals(1, rc)
            assertEquals(true, err.contains("zero increment"))
        }

    @Test fun badNumber_errors() =
        runTest {
            val (rc, _, err) = runSeq("abc")
            assertEquals(1, rc)
            assertEquals(true, err.contains("invalid"))
        }

    @Test fun tooManyArgs_errors() =
        runTest {
            val (rc, _, _) = runSeq("1", "2", "3", "4")
            assertEquals(2, rc)
        }

    @Test fun noArgs_errors() =
        runTest {
            val (rc, _, _) = runSeq()
            assertEquals(2, rc)
        }

    @Test fun negativeFirst_singleArgIsAllowed() =
        runTest {
            // `seq -3` would be ambiguous with options, but `seq -- -3` should not be needed
            // because `-3` parses as a number. However a single negative LAST < 1 yields empty
            // (since FIRST defaults to 1 and step to 1).
            val (rc, out, _) = runSeq("-3")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun doubleDash_endOfOpts() =
        runTest {
            val (_, out, _) = runSeq("--", "1", "3")
            assertEquals("1\n2\n3\n", out)
        }
}
