package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltinsClusterTest {
    private suspend fun out(
        script: String,
        stdin: String = "",
    ): String = Kash().exec(script, ExecOptions(stdin = stdin)).stdout

    private suspend fun result(
        script: String,
        stdin: String = "",
    ) = Kash().exec(script, ExecOptions(stdin = stdin))

    // -------- declare / typeset --------

    @Test fun declareAssignsValue() =
        runTest {
            assertEquals("v\n", out($$"declare X=v\necho \"$X\""))
        }

    @Test fun declareUppercases() =
        runTest {
            assertEquals("HELLO\n", out($$"declare -u X=hello\necho \"$X\""))
        }

    @Test fun declareLowercases() =
        runTest {
            assertEquals("hello\n", out($$"declare -l X=Hello\necho \"$X\""))
        }

    @Test fun typesetIsAliasForDeclare() =
        runTest {
            assertEquals("V\n", out($$"typeset -u X=v\necho \"$X\""))
        }

    @Test fun readonlyPreventsReassignment() =
        runTest {
            val r = result($$"readonly X=1\ndeclare X=2\necho \"$X\"")
            // The declare emits an error; X stays 1.
            assertEquals("1", r.stdout.trim())
        }

    // -------- local + function scope --------

    @Test fun localShadowsGlobal() =
        runTest {
            val script =
                $$"""
                X=outer
                f() { local X=inner; echo "in: $X"; }
                f
                echo "after: $X"
                """.trimIndent()
            assertEquals("in: inner\nafter: outer\n", out(script))
        }

    @Test fun localUnsetsAfterFunctionExit() =
        runTest {
            val script =
                $$"""
                f() { local Y=hello; echo "in: $Y"; }
                f
                echo "after: '$Y'"
                """.trimIndent()
            assertEquals("in: hello\nafter: ''\n", out(script))
        }

    @Test fun localOutsideFunctionFails() =
        runTest {
            val r = result("local X=1")
            assertEquals(1, r.exitCode)
        }

    // -------- read --------

    @Test fun readAssignsLineToVar() =
        runTest {
            assertEquals("hello world\n", out($$"read line\necho \"$line\"", stdin = "hello world\nnext\n"))
        }

    @Test fun readMultipleVarsSplitsOnIfs() =
        runTest {
            assertEquals("a|b c d\n", out($$"read a b\necho \"$a|$b\"", stdin = "a b c d\n"))
        }

    @Test fun readDefaultsToReply() =
        runTest {
            assertEquals("hi\n", out($$"read\necho \"$REPLY\"", stdin = "hi\n"))
        }

    @Test fun readReturnsOneOnEof() =
        runTest {
            assertEquals(1, result("read line", stdin = "").exitCode)
        }

    @Test fun readDashRDisablesEscapes() =
        runTest {
            // Without -r: `a\\b` becomes `ab`. With -r: stays `a\b`.
            assertEquals("a\\b\n", out($$"read -r line\necho \"$line\"", stdin = "a\\b\n"))
        }

    @Test fun readRejectsUnknownShortFlag() =
        runTest {
            val r = result("read -X foo", stdin = "")
            assertEquals(2, r.exitCode)
            assertTrue("invalid option" in r.stderr, r.stderr)
        }

    @Test fun readDashNStopsAtCharCount() =
        runTest {
            // Reads exactly 3 chars then returns; remaining bytes left on stdin.
            assertEquals("abc\n", out($$"read -n 3 v\necho \"$v\"", stdin = "abcdef\n"))
        }

    @Test fun readDashNStopsAtDelimEarly() =
        runTest {
            assertEquals("a\n", out($$"read -n 5 v\necho \"$v\"", stdin = "a\nbcd\n"))
        }

    @Test fun readDashTTimeoutReturnsGreaterThan128() =
        runTest {
            // No input ever arrives; timeout fires immediately.
            val r = result("read -t 0 line", stdin = "")
            assertTrue(r.exitCode > 128, "expected >128, got ${r.exitCode}")
        }

    // -------- type --------

    @Test fun typeKeyword() =
        runTest {
            assertEquals("if is a shell keyword\n", out("type if"))
        }

    @Test fun typeBuiltin() =
        runTest {
            assertEquals("echo is a shell builtin\n", out("type echo"))
        }

    @Test fun typeFunction() =
        runTest {
            // Real bash prints the header line AND the re-parseable
            // function body. Verified against bash 3.2:
            //   $ bash -c 'f() { :; }; type f'
            //   f is a function
            //   f ()
            //   {
            //       :
            //   }
            val r = out("f() { :; }\ntype f")
            assertTrue(r.startsWith("f is a function\n"), "expected header, got: $r")
            assertTrue(r.contains("f ()"), "expected re-parseable body, got: $r")
        }

    @Test fun typeBriefForms() =
        runTest {
            assertEquals("keyword\nbuiltin\n", out("type -t if\ntype -t echo"))
        }

    @Test fun typeNotFoundExitsOne() =
        runTest {
            val r = result("type nosuchthing")
            assertEquals(1, r.exitCode)
        }

    // -------- command --------

    @Test fun commandBypassesFunction() =
        runTest {
            // Define a function shadowing echo; `command echo` should still call the builtin.
            assertEquals("real\n", out("echo() { printf shadowed; }\ncommand echo real"))
        }

    @Test fun commandDashVPrintsName() =
        runTest {
            assertEquals("echo\n", out("command -v echo"))
        }

    // -------- printf extensions --------

    @Test fun printfWidth() =
        runTest {
            assertEquals("  abc", out("printf '%5s' abc"))
        }

    @Test fun printfLeftJustify() =
        runTest {
            assertEquals("abc  |", out("printf '%-5s|' abc"))
        }

    @Test fun printfPrecisionString() =
        runTest {
            assertEquals("abc", out("printf '%.3s' abcdef"))
        }

    @Test fun printfZeroPadInt() =
        runTest {
            assertEquals("00042", out("printf '%05d' 42"))
        }

    @Test fun printfHex() =
        runTest {
            assertEquals("ff", out("printf '%x' 255"))
            assertEquals("FF", out("printf '%X' 255"))
        }

    @Test fun printfQuoteSimple() =
        runTest {
            assertEquals("foo", out("printf '%q' foo"))
        }

    @Test fun printfQuoteWithSpace() =
        runTest {
            // Bash uses backslash-escape per shell-special char, not single-quote form.
            assertEquals("foo\\ bar", out("printf '%q' 'foo bar'"))
        }

    @Test fun printfQuoteEmbeddedQuote() =
        runTest {
            assertEquals("a\\'b", out("printf '%q' \"a'b\""))
        }

    @Test fun printfBInterpretsEscapes() =
        runTest {
            assertEquals("a\tb\n", out("printf '%b\\n' 'a\\tb'"))
        }

    @Test fun printfNoConsumerFormatDoesNotLoop() =
        runTest {
            // `printf "\n" 4.4 BSD`. Format has no `%` specifier, so the
            // recycle-format-until-args-consumed loop must terminate after
            // a single pass (zero args consumed) rather than spinning.
            assertEquals("\n", out("printf '\\n' 4.4 BSD"))
        }

    @Test fun printfUnknownConvDoesNotLoop() =
        runTest {
            // `printf "%y" 0`. `%y` is unrecognized; we print a stderr
            // warning and produce no stdout, and crucially the format-recycle
            // loop must not spin forever when args remain.
            val r = Kash().exec("printf '%y' 0")
            assertEquals("", r.stdout)
        }
}
