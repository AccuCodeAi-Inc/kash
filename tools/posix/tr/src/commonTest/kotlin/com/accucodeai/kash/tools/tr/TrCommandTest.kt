package com.accucodeai.kash.tools.tr

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun runTr(
    input: String,
    vararg args: String,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val stdin = Buffer().also { it.writeString(input) }
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            env = mutableMapOf(),
            cwd = "/",
            stdin = stdin.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = TrCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class TrCommandTest {
    @Test fun `translate simple range lower to upper`() =
        runTest {
            val (rc, out, _) = runTr("hello world\n", "a-z", "A-Z")
            assertEquals(0, rc)
            assertEquals("HELLO WORLD\n", out)
        }

    @Test fun `translate using lower upper classes`() =
        runTest {
            val (_, out, _) = runTr("Mixed CASE 42\n", "[:lower:]", "[:upper:]")
            assertEquals("MIXED CASE 42\n", out)
        }

    @Test fun `rot13 style range`() =
        runTest {
            val (_, out, _) =
                runTr(
                    "abcxyz\n",
                    "a-zA-Z",
                    "n-za-mN-ZA-M",
                )
            assertEquals("nopklm\n", out)
        }

    @Test fun `delete vowels`() =
        runTest {
            val (rc, out, _) = runTr("hello world\n", "-d", "aeiou")
            assertEquals(0, rc)
            assertEquals("hll wrld\n", out)
        }

    @Test fun `squeeze repeated spaces`() =
        runTest {
            val (_, out, _) = runTr("a    b   c\n", "-s", " ")
            assertEquals("a b c\n", out)
        }

    @Test fun `translate plus squeeze on set2`() =
        runTest {
            val (_, out, _) = runTr("aaabbb\n", "-s", "a-z", "A-Z")
            assertEquals("AB\n", out)
        }

    @Test fun `complement with delete keeps alpha`() =
        runTest {
            val (rc, out, _) = runTr("Hello, World! 123\n", "-cd", "[:alpha:]")
            assertEquals(0, rc)
            assertEquals("HelloWorld", out)
        }

    @Test fun `truncate set1 to set2 length`() =
        runTest {
            // Without -t, "abcde" → "XY" would map c,d,e to last of SET2 (Y).
            // With -t, only "a"→"X","b"→"Y", c/d/e unchanged.
            val (_, out, _) = runTr("abcde", "-t", "abcde", "XY")
            assertEquals("XYcde", out)
        }

    @Test fun `no truncate maps extras to last of set2`() =
        runTest {
            val (_, out, _) = runTr("abcde", "abcde", "XY")
            assertEquals("XYYYY", out)
        }

    @Test fun `octal escape parses to ascii A`() =
        runTest {
            val (_, out, _) = runTr("A", "\\101", "Z")
            assertEquals("Z", out)
        }

    @Test fun `escape newline and tab`() =
        runTest {
            val (_, out, _) = runTr("a\nb\tc\n", "\\n\\t", "  ")
            assertEquals("a b c ", out)
        }

    @Test fun `repeat construct fills N copies`() =
        runTest {
            // SET1=abc, SET2=[x*3] → xxx → a→x, b→x, c→x
            val (_, out, _) = runTr("abc", "abc", "[x*3]")
            assertEquals("xxx", out)
        }

    @Test fun `fill construct expands set2 to set1 length`() =
        runTest {
            val (_, out, _) = runTr("abcde", "abcde", "[x*]")
            assertEquals("xxxxx", out)
        }

    @Test fun `mismatched lengths repeat last of set2 by default`() =
        runTest {
            val (_, out, _) = runTr("abcd", "abcd", "XY")
            assertEquals("XYYY", out)
        }

    @Test fun `empty input produces empty output`() =
        runTest {
            val (rc, out, _) = runTr("", "a-z", "A-Z")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun `delete with extra operand is error`() =
        runTest {
            val (rc, _, err) = runTr("foo", "-d", "a", "b")
            assertEquals(2, rc)
            assertTrue(err.isNotEmpty())
        }

    @Test fun `no args is usage error`() =
        runTest {
            val (rc, _, err) = runTr("foo")
            assertEquals(2, rc)
            assertTrue(err.contains("missing operand"))
        }

    @Test fun `translate digit range`() =
        runTest {
            val (_, out, _) = runTr("abc123\n", "0-9", "X")
            assertEquals("abcXXX\n", out)
        }

    @Test fun `complement delete digits`() =
        runTest {
            val (_, out, _) = runTr("phone: 555-1212\n", "-cd", "0-9")
            assertEquals("5551212", out)
        }

    @Test fun `alnum class delete keeps non-alnum`() =
        runTest {
            val (_, out, _) = runTr("a1!b2@", "-d", "[:alnum:]")
            assertEquals("!@", out)
        }

    @Test fun `digit class translate`() =
        runTest {
            val (_, out, _) = runTr("a1b2c3", "[:digit:]", "#")
            assertEquals("a#b#c#", out)
        }

    @Test fun `space class squeezes whitespace runs`() =
        runTest {
            val (_, out, _) = runTr("a \t \n b", "-s", "[:space:]")
            // Each maximal run of any single space-class char collapses to one of that char.
            // "a", " ", "\t", " ", "\n", " ", "b" — already each run length 1; passes through.
            // So this just tests no crash + identity for distinct chars.
            assertEquals("a \t \n b", out)
        }

    @Test fun `cntrl class delete strips control chars`() =
        runTest {
            val (_, out, _) = runTr("abc", "-d", "[:cntrl:]")
            assertEquals("abc", out)
        }

    @Test fun `upper class translate to lower`() =
        runTest {
            val (_, out, _) = runTr("ABC xyz", "[:upper:]", "[:lower:]")
            assertEquals("abc xyz", out)
        }

    @Test fun `xdigit class keep only hex chars`() =
        runTest {
            val (_, out, _) = runTr("0xCAFE-zzz", "-cd", "[:xdigit:]")
            assertEquals("0CAFE", out)
        }

    @Test fun `equivalence class accepted as single char`() =
        runTest {
            val (rc, out, _) = runTr("aXa", "[=a=]", "Z")
            assertEquals(0, rc)
            assertEquals("ZXZ", out)
        }

    @Test fun `delete and squeeze combined`() =
        runTest {
            // -d SET1 deletes a's; -s SET2 squeezes runs of b's
            val (rc, out, _) = runTr("aabbbcca", "-ds", "a", "b")
            assertEquals(0, rc)
            assertEquals("bcc", out)
        }
}
