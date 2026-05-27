package com.accucodeai.kash

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test fixture: a CommandSpec whose primary name is `primary` and that
 * declares `dummy` as a registry-level alias — the same mechanism
 * `tools/python3/.../Python3Command.kt:42` uses to register `python` as
 * an alias for `python3`.
 */
private object AliasedDummyCommand : Command, CommandSpec {
    override val name: String = "primary"
    override val kind: CommandKind = CommandKind.BUILTIN
    override val command: Command = this
    override val aliases: List<String> = listOf("dummy")

    // BASH_BUILTIN so `type dummy` resolves via the registry instead of
    // falling through to PATH — see [classify]'s registry-vs-PATH gate.
    override val tags: Set<com.accucodeai.kash.api.CommandTag> =
        setOf(com.accucodeai.kash.api.CommandTag.BASH_BUILTIN)

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult = CommandResult(0)
}

/**
 * Tests for the `alias` / `unalias` intrinsics and POSIX §2.3.1 alias
 * substitution. All test scripts must split definitions from uses across
 * [Kash.Session.exec] calls — POSIX/bash and kash all expand aliases at the
 * time the line is parsed, so `alias x=y; x` in a single parse leaves `x`
 * unexpanded (a famous bash gotcha that we preserve).
 */
class AliasIntrinsicTest {
    // Sessions used by the alias tests run interactive so the
    // `expand_aliases` shopt is ON by default — matches `bash -i`
    // semantics for `type`/`command -v` introspection. Bash gates alias
    // visibility on the shopt in non-interactive scripts (see
    // bash/type.tests).
    private suspend fun session(): Kash.Session = Kash(registry = standardRegistry()).newSession(interactive = true)

    // -------- alias listing / set / get --------

    @Test fun aliasListsDefinedEntries() =
        runTest {
            val s = session()
            s.exec("alias ll='ls -l'")
            val r = s.exec("alias")
            assertEquals("alias ll='ls -l'\n", r.stdout)
        }

    @Test fun aliasSingleNamePrintsOneEntry() =
        runTest {
            val s = session()
            s.exec("alias ll='ls -l'")
            s.exec("alias gg='git status'")
            val r = s.exec("alias gg")
            assertEquals("alias gg='git status'\n", r.stdout)
        }

    @Test fun aliasMissingNameExitsNonZero() =
        runTest {
            val s = session()
            val r = s.exec("alias bogus")
            assertTrue(r.exitCode != 0, "expected non-zero, got ${r.exitCode}")
            assertContains(r.stderr, "bogus")
        }

    @Test fun aliasListingEscapesSingleQuotesForRoundTrip() =
        runTest {
            val s = session()
            s.exec($$"alias tricky=\"echo it's me\"")
            val r = s.exec("alias tricky")
            // Embedded ' becomes '\'' so the output is re-input-safe.
            assertEquals("alias tricky='echo it'\\''s me'\n", r.stdout)
        }

    // -------- expansion --------

    @Test fun aliasExpandsAtNextParse() =
        runTest {
            val s = session()
            s.exec("alias greet='echo hi'")
            val r = s.exec("greet")
            assertEquals("hi\n", r.stdout)
        }

    @Test fun aliasExpansionPreservesAdditionalArgs() =
        runTest {
            val s = session()
            s.exec("alias say='echo'")
            val r = s.exec("say hello world")
            assertEquals("hello world\n", r.stdout)
        }

    @Test fun aliasBodyMayContainOperators() =
        runTest {
            val s = session()
            // Pipe operator inside the body must survive as a real pipeline.
            s.exec($$"alias yell='echo hi | cat'")
            val r = s.exec("yell")
            assertEquals("hi\n", r.stdout)
        }

    @Test fun aliasBodyMayContainAndOrChain() =
        runTest {
            val s = session()
            s.exec($$"alias seq='echo one && echo two'")
            val r = s.exec("seq")
            assertEquals("one\ntwo\n", r.stdout)
        }

    @Test fun aliasBodyMayContainRedirection() =
        runTest {
            val s = session()
            s.exec($$"alias toerr='echo oops 1>&2'")
            val r = s.exec("toerr")
            assertEquals("", r.stdout)
            assertEquals("oops\n", r.stderr)
        }

    @Test fun quotedNameSuppressesExpansion() =
        runTest {
            val s = session()
            s.exec("alias greet='echo hi'")
            // \greet bypasses the alias; the resulting `greet` command is
            // not a builtin so it should fail with command-not-found.
            val r = s.exec($$"\\greet")
            assertTrue(r.exitCode != 0)
        }

    @Test fun trailingBlankChainsExpansion() =
        runTest {
            val s = session()
            // Trailing space in the `sudo` body makes the NEXT word from
            // the surrounding stream (`ll`) also alias-eligible. With ll
            // expanding to a plain literal `HI`, the resulting command is
            // `echo SUDO HI`.
            s.exec("alias sudo='echo SUDO '")
            s.exec("alias ll='HI'")
            val r = s.exec("sudo ll")
            assertEquals("SUDO HI\n", r.stdout)
        }

    @Test fun recursionGuardPreventsInfiniteLoop() =
        runTest {
            val s = session()
            // Self-referencing alias must not loop.
            s.exec("alias ls='echo aliased-ls'")
            val r = s.exec("ls")
            // After one expansion, `ls` in the body cannot re-expand.
            assertEquals("aliased-ls\n", r.stdout)
        }

    @Test fun reservedWordAliasIsUnreachable() =
        runTest {
            val s = session()
            // bash default mode lets you define `alias if=...`; the lexer
            // emits `if` as Token.Keyword in command position so the alias
            // is unreachable. We accept the definition (matching bash) but
            // the keyword still parses as control flow.
            val r1 = s.exec("alias if='echo nope'")
            assertEquals(0, r1.exitCode)
            val r2 = s.exec("if true; then echo ran; fi")
            assertEquals("ran\n", r2.stdout)
        }

    // -------- unalias --------

    @Test fun unaliasRemovesEntry() =
        runTest {
            val s = session()
            s.exec("alias ll='ls -l'")
            val r1 = s.exec("unalias ll")
            assertEquals(0, r1.exitCode)
            val r2 = s.exec("alias ll")
            assertTrue(r2.exitCode != 0)
        }

    @Test fun unaliasMissingNameExitsNonZero() =
        runTest {
            val s = session()
            val r = s.exec("unalias nope")
            assertTrue(r.exitCode != 0)
        }

    @Test fun unaliasAllClearsTable() =
        runTest {
            val s = session()
            s.exec("alias a='echo 1'")
            s.exec("alias b='echo 2'")
            s.exec("unalias -a")
            val r = s.exec("alias")
            assertEquals("", r.stdout)
        }

    // -------- type / command --------

    @Test fun typeReportsRuntimeAlias() =
        runTest {
            val s = session()
            s.exec("alias ll='ls -l'")
            val r = s.exec("type ll")
            assertContains(r.stdout, "aliased")
            assertContains(r.stdout, "ls -l")
        }

    @Test fun typeReportsToolDeclaredAlias() =
        runTest {
            // Register a custom tool that exposes a primary name plus a
            // registry-level alias — same mechanism that python3's CommandSpec
            // uses to make `python` resolve to `python3`. `type` should
            // surface the alias.
            val s =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(AliasedDummyCommand),
                ).newSession(interactive = true)
            val r = s.exec("type dummy")
            assertContains(r.stdout, "dummy")
            assertContains(r.stdout, "alias")
            assertContains(r.stdout, "primary")
        }

    @Test fun commandVPrintsAliasDefinition() =
        runTest {
            val s = session()
            s.exec("alias ll='ls -l'")
            val r = s.exec("command -v ll")
            assertEquals("alias ll='ls -l'\n", r.stdout)
        }

    // -------- bash-conformance corner cases --------

    @Test fun shoptExpandAliasesIsAcceptedAsNoop() =
        runTest {
            val s = session()
            val r = s.exec("shopt -s expand_aliases")
            assertEquals(0, r.exitCode)
            assertEquals("", r.stderr)
        }

    @Test fun commandSuppressesAliasOfNextWord() =
        runTest {
            val s = session()
            // bash: `command true` invokes the true builtin even if `true`
            // is aliased to something else.
            s.exec($$"alias true='echo bad'")
            val r = s.exec("command true && echo ok")
            // If alias suppression works, `true` succeeds and we see "ok".
            assertEquals("ok\n", r.stdout)
        }

    @Test fun aliasBodyStartingWithHashCommentsRestOfLine() =
        runTest {
            val s = session()
            s.exec("alias comment=#")
            val r = s.exec("comment this is all comment\necho after")
            // The first line should produce nothing; only `echo after` runs.
            assertEquals("after\n", r.stdout)
        }

    @Test fun multilineAliasBody() =
        runTest {
            val s = session()
            // Embedded newlines in the body should be re-lexed as statement
            // separators when spliced into the surrounding stream.
            s.exec("alias mt='echo a\necho b'")
            val r = s.exec("mt")
            assertEquals("a\nb\n", r.stdout)
        }

    @Test fun bashAliasesIndexedWriteSetsAlias() =
        runTest {
            val s = session()
            s.exec($$"BASH_ALIASES[gg]='echo via-array'")
            val r = s.exec("gg")
            assertEquals("via-array\n", r.stdout)
        }

    @Test fun bashAliasesInvalidNameWarnsButDoesntSet() =
        runTest {
            val s = session()
            val r1 = s.exec($$"BASH_ALIASES['bad/name']='nope'")
            assertContains(r1.stderr, "invalid alias name")
            val r2 = s.exec($$"alias 'bad/name'")
            assertTrue(r2.exitCode != 0)
        }

    @Test fun bashAliasTestsSnippet() =
        runTest {
            // Reproduces the failure mode seen running external/bash/tests/alias.tests
            // — the whole script fails to lex with "Unterminated single-quoted string".
            val script =
                $$"""
                shopt -s expand_aliases
                unalias -a
                alias
                echo alias: $?
                alias foo=bar
                unalias foo
                alias
                echo alias: $?
                unalias qfoo qbar qbaz quux 2>/dev/null
                alias qfoo=qbar
                alias qbar=qbaz
                alias qbaz=quux
                alias quux=qfoo
                qfoo
                unalias qfoo qbar qbaz quux
                unalias -a
                unalias foo
                alias foo='echo '
                alias bar=baz
                alias baz=quux
                foo bar
                unalias foo bar baz
                alias foo='a=() b=""
                for i in 1; do echo hi; done'
                foo
                unalias foo
                alias L='m=("x")'
                L
                alias '\$'=xx
                BASH_ALIASES['\$']=xx
                """.trimIndent()
            // Just confirm it doesn't throw at lex time.
            Kash().exec(script, ExecOptions(scriptName = "./alias.tests", mergeStderr = true))
        }

    @Test fun multilineAliasBodyArrayAssignment() =
        runTest {
            val s = session()
            // bash alias.tests line 56-57: multi-line body containing array
            // assignment + for loop. Body's `'` spans two source lines.
            s.exec(
                """
                alias foo='a=() b=""
                for i in 1; do echo hi; done'
                """.trimIndent(),
            )
            val r = s.exec("foo")
            assertContains(r.stdout, "hi")
        }

    @Test fun scriptNameErrorPrefix() =
        runTest {
            // Conformance runner passes a real scriptName; verify the
            // bash-format prefix appears on `command not found` diagnostics.
            val r =
                Kash().exec(
                    "nosuchcommand\n",
                    ExecOptions(scriptName = "./demo.sh"),
                )
            assertContains(r.stderr, "./demo.sh: line ")
            assertContains(r.stderr, "nosuchcommand: command not found")
        }

    // -------- POSIX/bash gap fixes (G1, G3, G4, G6, G7, G2) --------

    @Test fun caseInPositionDoesntExpandAlias() =
        runTest {
            // G7: alias-expanding the case pattern would change `foo)` into
            // the alias body and break the match. Bash test alias3.sub
            // expects this case to silently match.
            val r =
                Kash().exec(
                    """
                    alias foo='oneword'
                    foo_word='foo'
                    case "${'$'}foo_word" in
                        foo) echo matched ;;
                        *) echo bad ;;
                    esac
                    """.trimIndent(),
                )
            assertEquals("matched\n", r.stdout)
        }

    @Test fun forInWordListDoesntExpandAlias() =
        runTest {
            // G7: same fix applies to `for x in word-list`.
            val r =
                Kash().exec(
                    """
                    alias bad='echo SHOULD_NOT_EXPAND'
                    for x in bad good; do echo "${'$'}x"; done
                    """.trimIndent(),
                )
            assertEquals("bad\ngood\n", r.stdout)
        }

    @Test fun redirectionBeforeCommandPreservesAliasEligibility() =
        runTest {
            // G4: a redirection prefix shouldn't consume command-name position.
            // `touch` is a tools-subproject command not on core's test
            // classpath, so we seed the redir target directly via the FS API.
            val s = session()
            s.fs.writeBytes("/tmp/srcfile", ByteArray(0))
            s.exec("alias e=echo")
            val r = s.exec("< /tmp/srcfile e ok")
            assertEquals("ok\n", r.stdout)
        }

    @Test fun redirectionBeforeBareAssignmentParsesAsAssignment() =
        runTest {
            // G1+G4: `< file x=value` should set x without invoking a command.
            val s = session()
            s.fs.writeBytes("/tmp/srcfile2", ByteArray(0))
            val r = s.exec("< /tmp/srcfile2 x=value")
            assertEquals(0, r.exitCode)
            val r2 = s.exec($$"echo $x")
            assertEquals("value\n", r2.stdout)
        }

    @Test fun chainedInlineAssignmentAfterArray() =
        runTest {
            // G1: `a=() b=hi` — the b= after a=() must parse as an
            // AssignmentTok, not as a command word.
            val r =
                Kash().exec(
                    "a=() b=hi\necho \$b\n",
                )
            assertEquals("hi\n", r.stdout)
            assertEquals("", r.stderr)
        }

    @Test fun bashAliasesQuotedSubscriptUnquotes() =
        runTest {
            // G3: subscript lexer now honors single quotes — `'\$'` (literal
            // backslash + dollar) resolves to the 2-char string `\$`. Build
            // the input via raw + template so `\` survives Kotlin escape
            // processing.
            val s = session()
            val r = s.exec("""BASH_ALIASES['\${'$'}']=x""")
            assertContains(r.stderr, """`\${'$'}': invalid alias name""")
        }

    @Test fun sourceIntrinsicReadsAndRunsFile() =
        runTest {
            // G6: . / source reads a script and runs it in the current shell.
            // Seed the file via the FS API so we don't depend on `cat`/`mkdir`
            // being present on the core test classpath.
            val s = session()
            val script =
                """
                FOO=fromfile
                echo ${'$'}FOO
                """.trimIndent()
            s.fs.writeBytes("/sourced.sh", script.encodeToByteArray())
            val r = s.exec(". /sourced.sh")
            assertContains(r.stdout, "fromfile")
            // The variable assignment persists into a subsequent exec because
            // `.` runs in the current shell, not a subshell.
            val r2 = s.exec($$"echo \"after: $FOO\"")
            assertEquals("after: fromfile\n", r2.stdout)
        }

    @Test fun sourceMissingFileExits127() =
        runTest {
            val s = session()
            val r = s.exec(". /no/such/path/script.sh")
            assertEquals(127, r.exitCode)
            assertContains(r.stderr, "No such file")
        }

    @Test fun declarePIndexedArray() =
        runTest {
            // G2: declare -p prints arrays in bash format. We split the
            // assignment from `declare -p` because our parser only accepts
            // `NAME=(…)` as a top-level inline-env prefix, not as an arg
            // to the declare builtin.
            val r =
                Kash().exec(
                    "a=(x y)\ndeclare -p a\n",
                )
            assertEquals("declare -a a=([0]=\"x\" [1]=\"y\")\n", r.stdout)
        }

    @Test fun declarePScalar() =
        runTest {
            val r =
                Kash().exec(
                    "FOO=hi\ndeclare -p FOO\n",
                )
            assertContains(r.stdout, "declare -- FOO=\"hi\"")
        }

    @Test fun declarePMissingNameExitsNonZero() =
        runTest {
            val r = Kash().exec("declare -p NOSUCH\n")
            assertTrue(r.exitCode != 0)
            assertContains(r.stderr, "NOSUCH: not found")
        }

    // -------- G8: quote-spanning alias bodies --------

    @Test fun g8QuoteSpanningSingleQuote() =
        runTest {
            // The body's open `'` pairs with the `'` in the surrounding
            // source. Without character-level substitution this fails as
            // `foo: command not found`.
            val r =
                Kash().exec(
                    $$"""
                    alias foo="echo 'Error:"
                    foo bar'
                    """.trimIndent(),
                )
            assertEquals("Error: bar\n", r.stdout)
        }

    @Test fun g8QuoteSpanningDoubleQuote() =
        runTest {
            val s = session()
            s.exec("alias nullalias=''")
            s.exec("alias foo='echo '")
            s.exec($$"""alias bar="echo 'whoops: """")
            val r = s.exec($$"""bar nullalias'""")
            assertContains(r.stdout, "whoops:  nullalias")
        }

    @Test fun g8RecursionGuardAcrossSegments() =
        runTest {
            // POSIX rule 5 across multiple nested pushes. Cycle terminates
            // when the original alias is encountered as an active frame.
            val s = session()
            s.exec("alias qfoo=qbar")
            s.exec("alias qbar=qbaz")
            s.exec("alias qbaz=quux")
            s.exec("alias quux=qfoo")
            val r = s.exec("qfoo")
            // qfoo→qbar→qbaz→quux→qfoo; qfoo's recursion guard stops the
            // cycle so we end up trying to execute literal `qfoo`.
            assertContains(r.stderr, "qfoo: command not found")
        }

    @Test fun g8ChainAcrossSegmentBoundary() =
        runTest {
            // Body ending in blank arms the chain for the *next* outer-source
            // word, which itself substitutes (transitively).
            val s = session()
            s.exec("alias foo='echo '") // trailing blank
            s.exec("alias bar=baz")
            s.exec("alias baz=quux")
            val r = s.exec("foo bar")
            // foo expands to `echo `, chain arms; `bar` becomes `baz` becomes
            // `quux`. Final command: `echo quux`.
            assertEquals("quux\n", r.stdout)
        }

    @Test fun g8MultilineBodyExecutesInOuterContext() =
        runTest {
            // Body has `\n` inside; tokens produced from the body must form
            // a valid statement sequence in the outer parse.
            val s = session()
            s.exec(
                $$"""
                alias xx='echo line1
                echo line2'
                """.trimIndent(),
            )
            val r = s.exec("xx")
            assertEquals("line1\nline2\n", r.stdout)
        }
}
