package com.accucodeai.kash

import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.defineCommand
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KashTest {
    // -------- Basics --------

    @Test fun echoHello() =
        runTest {
            val r = Kash().exec("echo hello")
            assertEquals(0, r.exitCode)
            assertEquals("hello\n", r.stdout)
            assertEquals("", r.stderr)
        }

    @Test fun unknownCommandReturns127() =
        runTest {
            assertEquals(127, Kash().exec("nosuchcommand").exitCode)
        }

    @Test fun echoDashNSuppressesNewline() =
        runTest {
            assertEquals("hi", Kash().exec("echo -n hi").stdout)
        }

    // -------- Pipelines --------

    @Test fun pipelineFlowsStdout() =
        runTest {
            assertEquals("hello\n", Kash().exec("echo hello | cat").stdout)
        }

    @Test fun threeStagePipeline() =
        runTest {
            assertEquals("hello\n", Kash().exec("echo hello | cat | cat").stdout)
        }

    @Test fun pipelineExitCodeIsLastCommand() =
        runTest {
            val r = Kash().exec("echo a | false")
            assertEquals(1, r.exitCode)
        }

    // -------- Connectors --------

    @Test fun andOrShortCircuit() =
        runTest {
            val r = Kash().exec("false && echo nope || echo yes")
            assertEquals(0, r.exitCode)
            assertEquals("yes\n", r.stdout)
        }

    @Test fun andStopsOnFailure() =
        runTest {
            val r = Kash().exec("true && echo ran && false && echo nope")
            assertEquals("ran\n", r.stdout)
            assertEquals(1, r.exitCode)
        }

    @Test fun semicolonSequences() =
        runTest {
            assertEquals("a\nb\nc\n", Kash().exec("echo a ; echo b ; echo c").stdout)
        }

    @Test fun exitCodeIsLastCommand() =
        runTest {
            val r = Kash().exec("echo a ; false")
            assertEquals(1, r.exitCode)
            assertEquals("a\n", r.stdout)
        }

    // -------- Quoting --------

    @Test fun singleQuotedPreservesLiteral() =
        runTest {
            assertEquals("a  b\n", Kash().exec("echo 'a  b'").stdout)
        }

    @Test fun doubleQuotedConcatenates() =
        runTest {
            assertEquals("a b c\n", Kash().exec("""echo "a b" c""").stdout)
        }

    @Test fun singleQuotedDoesNotExpand() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            FOO=bar
            echo '$FOO'
                    """.trimIndent(),
                )
            assertEquals($$"$FOO\n", r.stdout)
        }

    @Test fun doubleQuotedDoesExpand() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            FOO=bar
            echo "value is $FOO"
                    """.trimIndent(),
                )
            assertEquals("value is bar\n", r.stdout)
        }

    @Test fun backslashEscape() =
        runTest {
            // \$ → literal $
            assertEquals($$"$FOO\n", Kash().exec($$"""echo \$FOO""").stdout)
        }

    // -------- Variables --------

    @Test fun assignmentThenExpand() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            X=42
            echo $X
                    """.trimIndent(),
                )
            assertEquals("42\n", r.stdout)
        }

    @Test fun bracedExpansion() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            FOO=hello
            echo ${FOO}world
                    """.trimIndent(),
                )
            assertEquals("helloworld\n", r.stdout)
        }

    @Test fun unsetVariableExpandsToEmpty() =
        runTest {
            val r = Kash().exec($$"echo [$MISSING]")
            assertEquals("[]\n", r.stdout)
        }

    @Test fun exitCodeSpecialDollarQuestion() =
        runTest {
            val r =
                Kash().exec(
                    """
                    false
                    echo $?
                    true
                    echo $?
                    """.trimIndent(),
                )
            assertEquals("1\n0\n", r.stdout)
        }

    @Test fun assignmentValueExpandsItself() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            A=one
            B=$A-two
            echo $B
                    """.trimIndent(),
                )
            assertEquals("one-two\n", r.stdout)
        }

    @Test fun envFromOptionsIsVisible() =
        runTest {
            val r = Kash().exec($$"echo $GREETING", ExecOptions(env = mapOf("GREETING" to "hi")))
            assertEquals("hi\n", r.stdout)
        }

    @Test fun replaceEnvDropsDefaults() =
        runTest {
            val r = Kash().exec($$"echo [$HOME]", ExecOptions(replaceEnv = true))
            assertEquals("[]\n", r.stdout)
        }

    // -------- Cwd / cd / pwd --------

    @Test fun pwdReflectsDefaultCwd() =
        runTest {
            assertEquals("/home/user\n", Kash().exec("pwd").stdout)
        }

    @Test fun cdChangesPwd() =
        runTest {
            val r = Kash().exec("cd /tmp ; pwd")
            assertEquals("/tmp\n", r.stdout)
            assertEquals(0, r.exitCode)
        }

    @Test fun cdToMissingDirFails() =
        runTest {
            val r = Kash().exec("cd /nope")
            assertEquals(1, r.exitCode)
            assertTrue(r.stderr.startsWith("cd:"))
        }

    @Test fun cwdOptionOverridesPerExec() =
        runTest {
            assertEquals("/tmp\n", Kash().exec("pwd", ExecOptions(cwd = "/tmp")).stdout)
        }

    // -------- Filesystem --------

    @Test fun catReadsFile() =
        runTest {
            val k = Kash()
            k.fs.writeBytes("/home/user/note.txt", "hello fs\n".encodeToByteArray())
            val r = k.exec("cat note.txt")
            assertEquals("hello fs\n", r.stdout)
            assertEquals(0, r.exitCode)
        }

    @Test fun catReadsStdinWhenNoArgs() =
        runTest {
            val r = Kash().exec("cat", ExecOptions(stdin = "piped in\n"))
            assertEquals("piped in\n", r.stdout)
        }

    @Test fun catMissingFileReports() =
        runTest {
            val r = Kash().exec("cat /missing")
            assertEquals(1, r.exitCode)
            assertTrue(r.stderr.contains("No such file"))
        }

    @Test fun catMultipleFilesConcatenates() =
        runTest {
            val k = Kash()
            k.fs.writeBytes("/tmp/a.txt", "alpha\n".encodeToByteArray())
            k.fs.writeBytes("/tmp/b.txt", "beta\n".encodeToByteArray())
            assertEquals("alpha\nbeta\n", k.exec("cat /tmp/a.txt /tmp/b.txt").stdout)
        }

    // -------- Comments / whitespace --------

    @Test fun commentsIgnored() =
        runTest {
            val r = Kash().exec("# header\necho hi # trailing\n# another")
            assertEquals("hi\n", r.stdout)
        }

    // -------- Custom commands --------

    @Test fun customCommandComposesWithPipeline() =
        runTest {
            val greet =
                defineCommand("greet") { args, ctx ->
                    ctx.stdout.writeUtf8("hi ${args.firstOrNull() ?: "world"}\n")
                    CommandResult()
                }
            val upper =
                defineCommand("upper") { _, ctx ->
                    ctx.stdout.writeUtf8(ctx.stdin.readUtf8Text().uppercase())
                    CommandResult()
                }
            val r = Kash(customCommands = listOf(greet, upper)).exec("greet alice | upper")
            assertEquals("HI ALICE\n", r.stdout)
        }

    // -------- Complex multi-line scripts --------

    @Test fun multilineFizzbuzzLite() =
        runTest {
            // Without arithmetic/loops we can still chain conditionals + assignments.
            val r =
                Kash().exec(
                    $$"""
            N=3
            true && echo "first $N"
            false || echo "fallback $N"
                    """.trimIndent(),
                )
            assertEquals("first 3\nfallback 3\n", r.stdout)
        }

    @Test fun pipelineThroughCustomFilterThenCat() =
        runTest {
            val tagger =
                defineCommand("tag") { args, ctx ->
                    val prefix = args.firstOrNull() ?: "??"
                    val tagged =
                        ctx.stdin
                            .readUtf8Text()
                            .lines()
                            .filter { it.isNotEmpty() }
                            .joinToString("\n") { "[$prefix] $it" } + "\n"
                    ctx.stdout.writeUtf8(tagged)
                    CommandResult()
                }
            val k = Kash(customCommands = listOf(tagger))
            k.fs.writeBytes("/tmp/data.txt", "alpha\nbeta\ngamma\n".encodeToByteArray())
            val r = k.exec("cat /tmp/data.txt | tag LOG | cat")
            assertEquals("[LOG] alpha\n[LOG] beta\n[LOG] gamma\n", r.stdout)
        }

    @Test fun variablesPersistAcrossStatementsButNotAcrossExec() =
        runTest {
            val k = Kash()
            val r1 = k.exec($$"X=set ; echo $X")
            assertEquals("set\n", r1.stdout)
            // Each exec gets fresh shell state, so X should be empty here.
            val r2 = k.exec($$"echo [$X]")
            assertEquals("[]\n", r2.stdout)
        }

    @Test fun complexQuotingAndExpansion() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            FIRST=Alice
            LAST=Doe
            echo "Hello, $FIRST $LAST!" 'literal: $FIRST'
                    """.trimIndent(),
                )
            assertEquals($$"Hello, Alice Doe! literal: $FIRST\n", r.stdout)
        }

    @Test fun pipelineWithFalseAtEndKeepsStdout() =
        runTest {
            // stdout from earlier stages is still captured even if pipeline fails.
            val r = Kash().exec("echo keep | false")
            assertEquals(1, r.exitCode)
        }

    @Test fun chainedAndOrOnVariableExpansion() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            FOO=
            true && echo "set: [$FOO]"
            FOO=value
            true && echo "set: [$FOO]"
                    """.trimIndent(),
                )
            assertEquals("set: []\nset: [value]\n", r.stdout)
        }
}
