package com.accucodeai.kash.parser

import com.accucodeai.kash.ast.Connector
import com.accucodeai.kash.ast.SimpleCommand
import com.accucodeai.kash.ast.WordPart
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParserTest {
    private fun parse(s: String) = Parser(s).parseScript()

    @Test
    fun parsesSingleCommandWithArgs() =
        runTest {
            val s = parse("echo a b c")
            assertEquals(1, s.statements.size)
            val cmd = s.statements[0].pipelines[0].commands[0] as SimpleCommand
            assertEquals("echo", (cmd.name!!.parts[0] as WordPart.Literal).value)
            assertEquals(3, cmd.args.size)
        }

    @Test
    fun parsesPipeline() =
        runTest {
            val s = parse("a | b | c")
            val p = s.statements[0].pipelines[0]
            assertEquals(3, p.commands.size)
        }

    @Test
    fun parsesAndOrChain() =
        runTest {
            val s = parse("a && b || c")
            val stmt = s.statements[0]
            assertEquals(3, stmt.pipelines.size)
            assertEquals(listOf(Connector.AND, Connector.OR), stmt.operators)
        }

    @Test
    fun semicolonSeparatesStatements() =
        runTest {
            val s = parse("a ; b ; c")
            assertEquals(3, s.statements.size)
        }

    @Test
    fun newlineSeparatesStatements() =
        runTest {
            val s = parse("a\nb\nc")
            assertEquals(3, s.statements.size)
        }

    @Test
    fun commentsAreSkipped() =
        runTest {
            val s = parse("# just a comment\necho hi # trailing")
            assertEquals(1, s.statements.size)
        }

    @Test
    fun singleQuotedPreservesSpacesAndSpecials() =
        runTest {
            val s = parse("echo 'a | b && c'")
            val cmd = s.statements[0].pipelines[0].commands[0] as SimpleCommand
            assertEquals(1, cmd.args.size)
            val part = cmd.args[0].parts[0] as WordPart.SingleQuoted
            assertEquals("a | b && c", part.value)
        }

    @Test
    fun doubleQuotedIsParsedAsSinglePart() =
        runTest {
            val s = parse("""echo "hello world"""")
            val cmd = s.statements[0].pipelines[0].commands[0] as SimpleCommand
            assertTrue(cmd.args[0].parts.single() is WordPart.DoubleQuoted)
        }

    @Test
    fun backslashEscapesNextChar() =
        runTest {
            val s = parse("""echo foo\ bar""")
            val cmd = s.statements[0].pipelines[0].commands[0] as SimpleCommand
            // foo + \space + bar → one word
            assertEquals(1, cmd.args.size)
        }

    @Test
    fun emptyScriptParses() =
        runTest {
            val s = parse("")
            assertEquals(0, s.statements.size)
        }

    @Test
    fun unterminatedSingleQuoteFails() =
        runTest {
            // Parser now throws ParseException (was IllegalStateException
            // pre-recovery-rewrite). Both are runtime exceptions; the
            // important contract is that parsing the malformed source
            // fails noisily rather than producing garbage AST.
            assertFailsWith<com.accucodeai.kash.parser.ParseException> { parse("echo 'oops") }
        }

    @Test
    fun backgroundOperatorIsRecognized() =
        runTest {
            val s = parse("sleep 1 &")
            assertTrue(s.statements[0].background)
        }
}
