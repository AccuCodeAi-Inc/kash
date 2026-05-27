package com.accucodeai.kash.tools.getopt

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun runGetopt(vararg args: String): Triple<Int, String, String> {
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
    val res = GetoptCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class GetoptCommandTest {
    // ---- Short options ----
    @Test fun simpleShortFlags() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "abc", "--", "-a", "-b", "-c")
            assertEquals(0, rc)
            assertEquals("'-a' '-b' '-c' --\n", out)
        }

    @Test fun bundledShort() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "abc", "--", "-abc")
            assertEquals(0, rc)
            assertEquals("'-a' '-b' '-c' --\n", out)
        }

    @Test fun shortWithRequiredArgInline() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "f:", "--", "-fbar")
            assertEquals(0, rc)
            assertEquals("'-f' 'bar' --\n", out)
        }

    @Test fun shortWithRequiredArgSeparate() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "f:", "--", "-f", "bar")
            assertEquals(0, rc)
            assertEquals("'-f' 'bar' --\n", out)
        }

    @Test fun shortOptionalArgPresent() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "f::", "--", "-fbar")
            assertEquals(0, rc)
            assertEquals("'-f' 'bar' --\n", out)
        }

    @Test fun shortOptionalArgAbsent() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "f::", "--", "-f")
            assertEquals(0, rc)
            assertEquals("'-f' '' --\n", out)
        }

    @Test fun bundledMixedWithArg() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "abf:", "--", "-abfvalue")
            assertEquals(0, rc)
            assertEquals("'-a' '-b' '-f' 'value' --\n", out)
        }

    // ---- Long options ----
    @Test fun longNoArg() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "", "-l", "foo", "--", "--foo")
            assertEquals(0, rc)
            assertEquals("'--foo' --\n", out)
        }

    @Test fun longWithArgInline() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "", "-l", "foo:", "--", "--foo=bar")
            assertEquals(0, rc)
            assertEquals("'--foo' 'bar' --\n", out)
        }

    @Test fun longWithArgSeparate() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "", "-l", "foo:", "--", "--foo", "bar")
            assertEquals(0, rc)
            assertEquals("'--foo' 'bar' --\n", out)
        }

    @Test fun longOptionalInline() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "", "-l", "foo::", "--", "--foo=bar")
            assertEquals(0, rc)
            assertEquals("'--foo' 'bar' --\n", out)
        }

    @Test fun longOptionalAbsent() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "", "-l", "foo::", "--", "--foo")
            assertEquals(0, rc)
            assertEquals("'--foo' '' --\n", out)
        }

    @Test fun longPrefixAbbreviation() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "", "-l", "foobar", "--", "--foo")
            assertEquals(0, rc)
            assertEquals("'--foobar' --\n", out)
        }

    @Test fun longAmbiguousPrefix() =
        runTest {
            val (rc, _, err) = runGetopt("-o", "", "-l", "foobar,foobaz", "--", "--foo")
            assertEquals(1, rc)
            assertTrue(err.contains("ambiguous"))
        }

    @Test fun longUnknownErrors() =
        runTest {
            val (rc, _, err) = runGetopt("-o", "", "-l", "foo", "--", "--bar")
            assertEquals(1, rc)
            assertTrue(err.contains("unrecognized"))
        }

    @Test fun longRequiredMissingErrors() =
        runTest {
            val (rc, _, err) = runGetopt("-o", "", "-l", "foo:", "--", "--foo")
            assertEquals(1, rc)
            assertTrue(err.contains("requires an argument"))
        }

    // ---- Operands ----
    @Test fun operandsAfterOptions() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "a", "--", "-a", "opname1", "opname2")
            assertEquals(0, rc)
            assertEquals("'-a' -- 'opname1' 'opname2'\n", out)
        }

    @Test fun operandsBeforeOptionsPermuted() =
        runTest {
            // Default mode permutes operands to the end.
            val (rc, out, _) = runGetopt("-o", "a", "--", "file", "-a", "more")
            assertEquals(0, rc)
            assertEquals("'-a' -- 'file' 'more'\n", out)
        }

    @Test fun plusModifierStopsAtFirstNonOption() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "+a", "--", "-a", "file", "-b")
            assertEquals(0, rc)
            assertEquals("'-a' -- 'file' '-b'\n", out)
        }

    @Test fun doubleDashEndsParsing() =
        runTest {
            val (rc, out, _) = runGetopt("-o", "a", "--", "-a", "--", "-b")
            assertEquals(0, rc)
            assertEquals("'-a' -- '-b'\n", out)
        }

    // ---- Errors / flags ----
    @Test fun unknownShortOptionError() =
        runTest {
            val (rc, _, err) = runGetopt("-o", "a", "--", "-z")
            assertEquals(1, rc)
            assertTrue(err.contains("invalid option"))
        }

    @Test fun unknownShortQuietSuppressesError() =
        runTest {
            val (rc, _, err) = runGetopt("-q", "-o", "a", "--", "-z")
            assertEquals(1, rc)
            assertEquals("", err)
        }

    @Test fun quietOutputSuppressesNormalizedLine() =
        runTest {
            val (rc, out, _) = runGetopt("-Q", "-o", "a", "--", "-a", "file")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun testFlagExits4() =
        runTest {
            val (rc, _, _) = runGetopt("-T")
            assertEquals(4, rc)
        }

    @Test fun nameUsedInErrors() =
        runTest {
            val (rc, _, err) = runGetopt("-n", "myprog", "-o", "a", "--", "-z")
            assertEquals(1, rc)
            assertTrue(err.startsWith("myprog:"))
        }

    @Test fun quotingEscapesSingleQuote() =
        runTest {
            // arg containing a single quote should become 'foo'\''bar'
            val (rc, out, _) = runGetopt("-o", "f:", "--", "-f", "foo'bar")
            assertEquals(0, rc)
            assertEquals("'-f' 'foo'\\''bar' --\n", out)
        }

    @Test fun unquotedFlagSkipsQuoting() =
        runTest {
            val (rc, out, _) = runGetopt("-u", "-o", "a", "--", "-a", "file")
            assertEquals(0, rc)
            assertEquals("-a -- file\n", out)
        }

    @Test fun alternativeSingleDashLong() =
        runTest {
            val (rc, out, _) = runGetopt("-a", "-o", "", "-l", "foo", "--", "-foo")
            assertEquals(0, rc)
            assertEquals("'--foo' --\n", out)
        }

    @Test fun missingOptstringErrors() =
        runTest {
            val (rc, _, err) = runGetopt()
            assertEquals(2, rc)
            assertTrue(err.contains("missing optstring"))
        }

    @Test fun shellFlavorBashIsDefault() =
        runTest {
            val (rc, out, _) = runGetopt("-s", "sh", "-o", "a", "--", "-a")
            assertEquals(0, rc)
            assertEquals("'-a' --\n", out)
        }

    @Test fun compatibilityModeFirstArgIsSpec() =
        runTest {
            // No -o: first positional is the short option string.
            val (rc, out, _) = runGetopt("ab", "-a", "-b")
            assertEquals(0, rc)
            assertEquals("'-a' '-b' --\n", out)
        }

    // ---- Recipe-level realistic uses ----
    @Test fun recipe_typicalShortAndLongMix() =
        runTest {
            val (rc, out, _) =
                runGetopt(
                    "-o",
                    "ho:v",
                    "-l",
                    "help,output:,verbose",
                    "--",
                    "-v",
                    "--output=/tmp/x",
                    "input.txt",
                )
            assertEquals(0, rc)
            assertEquals("'-v' '--output' '/tmp/x' -- 'input.txt'\n", out)
        }

    @Test fun recipe_evalSetForm() =
        runTest {
            // Mirrors `eval set -- "$(getopt -o ab: -- "$@")"` workflow.
            val (rc, out, _) = runGetopt("-o", "ab:", "--", "-a", "-b", "value", "operand")
            assertEquals(0, rc)
            assertEquals("'-a' '-b' 'value' -- 'operand'\n", out)
        }
}
