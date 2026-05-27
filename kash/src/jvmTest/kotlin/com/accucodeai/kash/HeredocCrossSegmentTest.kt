package com.accucodeai.kash

import com.accucodeai.kash.api.ExecResult
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-segment heredoc body consumption.
 *
 * Two pathological recovery cases — bash's lexer handles both,
 * kash needed lexer changes (heredoc body reading must walk across
 * alias-body and cmdsub-close segment boundaries):
 *
 *  1. **heredoc-from-alias-body crosses into outer source**
 *     (heredoc10.sub `headplus` alias) — alias body opens a heredoc
 *     with delim `EOF`, body starts inside alias body but the
 *     terminator line comes from the SURROUNDING source after the
 *     alias was substituted. Without cross-segment reading the
 *     heredoc terminates at alias-body-EOF (warning).
 *
 *  2. **unterminated heredoc inside `$(...)` pulls body from
 *     outer source** (heredoc7.sub `echo $(cat << EOF)\nfoo\nbar\nEOF\nafter`) —
 *     the cmdsub's `cat << EOF` registers a heredoc but the cmdsub
 *     `)` closes before the body is consumed. Bash's recovery:
 *     emit a `command substitution: N unterminated here-document`
 *     warning, pull the body from outer source past `)`, append
 *     it to the cmdsub's captured text so the recursive parse
 *     sees a well-formed heredoc.
 *
 * Reference: POSIX §2.7.4 (Here-Document) describes body collection
 * but doesn't specify recovery. Bash's behavior is the de-facto
 * convention shells follow.
 */
class HeredocCrossSegmentTest {
    private suspend fun run(
        script: String,
        env: Map<String, String> = mapOf("PATH" to "/usr/bin"),
    ): ExecResult {
        val fs = InMemoryFs()
        val kash =
            Kash(
                fs = fs,
                initialCwd = "/",
                parentContext = kotlin.coroutines.coroutineContext,
            )
        return kash.exec(
            script,
            ExecOptions(
                env = env,
                cwd = "/",
                replaceEnv = true,
                mergeStderr = false,
                scriptName = "./test.sh",
            ),
        )
    }

    @Test fun aliasBodyHeredocContinuesIntoOuterSource() =
        runTest {
            // Mirror of heredoc10.sub `headplus` case: alias defines
            // `cat <<EOF\nhello`, then is invoked; the surrounding
            // source provides the rest of the body (`world`) plus the
            // terminator (`EOF`).
            val script =
                """
                shopt -s expand_aliases
                alias 'headplus=cat <<EOF
                hello'
                headplus
                world
                EOF
                """.trimIndent()
            val r = run(script)
            assertTrue(
                r.stderr.isEmpty(),
                "expected no diagnostic; got stderr=`${r.stderr}`",
            )
            // Body is `hello\nworld\n` (alias's partial line completes
            // from the outer source on the next line).
            assertEquals("hello\nworld\n", r.stdout)
        }

    @Test fun unterminatedCmdsubHeredocPullsBodyFromOuter() =
        runTest {
            // Mirror of heredoc7.sub line 17: `echo $(cat << EOF)`
            // closes the cmdsub with `EOF` heredoc unfulfilled. Bash
            // pulls `foo\nbar\nEOF\n` from the outer source after `)`
            // as the heredoc body.
            val script =
                """
                echo ${'$'}(cat << EOF)
                foo
                bar
                EOF
                echo done
                """.trimIndent()
            val r = run(script)
            // `cat` outputs `foo\nbar`; echo's word-split joins with a
            // space, then `done` on the next line.
            assertEquals("foo bar\ndone\n", r.stdout)
            // Bash emits the recovery warning to stderr; kash should
            // too. Don't pin the exact wording — gate on the warning
            // shape.
            assertTrue(
                r.stderr.contains("unterminated here-document"),
                "expected recovery warning; got stderr=`${r.stderr}`",
            )
        }
}
