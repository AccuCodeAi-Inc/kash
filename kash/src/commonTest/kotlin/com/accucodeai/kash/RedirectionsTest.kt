package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedirectionsTest {
    @Test fun outputToFile() =
        runTest {
            val k = Kash()
            val r = k.exec("echo hello > /tmp/out.txt")
            assertEquals("", r.stdout)
            assertEquals("hello\n", k.fs.readBytes("/tmp/out.txt").decodeToString())
        }

    @Test fun appendToFile() =
        runTest {
            val k = Kash()
            k.exec("echo first > /tmp/log.txt")
            k.exec("echo second >> /tmp/log.txt")
            assertEquals("first\nsecond\n", k.fs.readBytes("/tmp/log.txt").decodeToString())
        }

    @Test fun inputFromFile() =
        runTest {
            val k = Kash()
            k.fs.writeBytes("/tmp/in.txt", "from-file\n".encodeToByteArray())
            val r = k.exec("cat < /tmp/in.txt")
            assertEquals("from-file\n", r.stdout)
        }

    @Test fun stderrRedirect() =
        runTest {
            val k = Kash()
            val r = k.exec("cat /missing 2> /tmp/err.log")
            assertEquals("", r.stderr)
            assertTrue(
                k.fs
                    .readBytes("/tmp/err.log")
                    .decodeToString()
                    .contains("No such file"),
            )
        }

    @Test fun mergeStderrIntoStdout() =
        runTest {
            val r = Kash().exec("cat /missing 2>&1")
            assertTrue(r.stdout.contains("No such file"))
            assertEquals("", r.stderr)
        }

    @Test fun ampersandRedirectBoth() =
        runTest {
            val k = Kash()
            k.exec("cat /missing &> /tmp/both.log")
            val content = k.fs.readBytes("/tmp/both.log").decodeToString()
            assertTrue(content.contains("No such file"))
        }

    @Test fun hereStringFeedsStdin() =
        runTest {
            val r = Kash().exec("cat <<< hello")
            assertEquals("hello\n", r.stdout)
        }

    @Test fun heredocFeedsStdin() =
        runTest {
            val script =
                """
                cat <<EOF
                line1
                line2
                EOF
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("line1\nline2\n", r.stdout)
        }

    @Test fun heredocExpandsVariables() =
        runTest {
            val script =
                $$"""
            NAME=alice
            cat <<EOF
            hi $NAME
            EOF
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("hi alice\n", r.stdout)
        }

    @Test fun quotedHeredocDoesNotExpand() =
        runTest {
            val script =
                $$"""
            NAME=alice
            cat <<'EOF'
            hi $NAME
            EOF
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals($$"hi $NAME\n", r.stdout)
        }

    @Test fun heredocStripTabs() =
        runTest {
            val script = "cat <<-EOF\n\thello\n\tworld\n\tEOF\n"
            val r = Kash().exec(script)
            assertEquals("hello\nworld\n", r.stdout)
        }

    @Test fun heredocBackslashNewlineInDelimiterContinues() =
        runTest {
            // `<< EO\` <nl> `F` — bash joins the delimiter to `EOF`.
            val script = "cat << EO\\\nF\nhi\nEOF\n"
            val r = Kash().exec(script)
            assertEquals("hi\n", r.stdout)
        }

    @Test fun heredocBackslashNewlineInBodyContinues() =
        runTest {
            // `next\` <nl> `EOF` joins to `nextEOF` (does NOT terminate); next bare `EOF` does.
            val script = "cat <<EOF\nnext\\\nEOF\nEOF\n"
            val r = Kash().exec(script)
            assertEquals("nextEOF\n", r.stdout)
        }

    @Test fun heredocBodyContinuationProducesDelimiter() =
        runTest {
            // Body line `EO\` <nl> `F` joins to `EOF` which matches the delimiter and terminates.
            val script = "cat <<EOF\nhi\nEO\\\nF\nafter\n"
            // After the heredoc terminates, `after` runs as a normal command (and errors silently).
            val r = Kash().exec(script)
            assertEquals("hi\n", r.stdout)
        }

    @Test fun quotedHeredocPreservesBackslashNewline() =
        runTest {
            // With `<< 'EOF'`, backslash-newline in body is literal — no splicing.
            val script = "cat << 'EOF'\nhi\\\nthere\nEOF\n"
            val r = Kash().exec(script)
            assertEquals("hi\\\nthere\n", r.stdout)
        }

    @Test fun heredocEofGracefullyTerminates() =
        runTest {
            // `<<''` is an empty quoted delimiter; with no closing line,
            // EOF terminates the heredoc.
            val script = "cat <<''\nhi\nthere\n"
            val r = Kash().exec(script)
            assertEquals("hi\nthere\n", r.stdout)
        }

    @Test fun pipelineThroughRedirectedCommand() =
        runTest {
            val k = Kash()
            k.exec("echo data > /tmp/x.txt")
            val r = k.exec("cat < /tmp/x.txt | cat")
            assertEquals("data\n", r.stdout)
        }
}
