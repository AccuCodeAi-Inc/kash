package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.decodeTag
import com.accucodeai.kash.tools.git.plumbing.parseFramedObject
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for `git tag` message cleanup and tag-object payloads —
 * no real-git dependency (runs on every platform's commonTest).
 */
class TagMessageCleanupTest {
    @Test fun cleanupStripsCommentsBlanksAndTrailingWhitespace() {
        // Leading-space preserved; trailing whitespace stripped; comments
        // dropped; leading/trailing blanks removed; internal runs collapsed.
        val raw = "\n\n  keep lead\ntrail   \n#comment\nnot # inline\n\n\n  \nend\n"
        assertEquals("  keep lead\ntrail\nnot # inline\n\nend\n", cleanupMessage(raw))
    }

    @Test fun cleanupAllCommentsYieldsEmpty() {
        assertEquals("", cleanupMessage("# just a comment\n#another\n"))
    }

    @Test fun cleanupCollapsesInternalBlankRuns() {
        assertEquals("a\n\nb\n", cleanupMessage("a\n\n\n\nb\n"))
    }

    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun run(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env =
                    mutableMapOf(
                        "GIT_AUTHOR_DATE" to "1700000000 +0000",
                        "GIT_COMMITTER_DATE" to "1700000000 +0000",
                        "GIT_COMMITTER_NAME" to "C",
                        "GIT_COMMITTER_EMAIL" to "c@example.com",
                    ),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = GitCommand().run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    @Test fun annotatedTagUsesCommitterIdentityAndDate() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/a", "x\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "c")
            run(fs, "/r", "tag", "-a", "v1", "-m", "rel")

            val repo =
                com.accucodeai.kash.tools.git.GitRepo
                    .open(fs, "/r")
            val refSha = repo.refs.resolve("refs/tags/v1")!!
            val parsed = parseFramedObject(repo.objects.read(refSha))
            assertEquals(ObjectType.TAG, parsed.type)
            val tag = decodeTag(parsed.payload)
            assertEquals("v1", tag.tagName)
            assertEquals("C", tag.tagger.name)
            assertEquals("c@example.com", tag.tagger.email)
            assertEquals(1700000000L, tag.tagger.whenSeconds)
            assertEquals("+0000", tag.tagger.tz)
            assertEquals("rel\n", tag.message)
        }

    @Test fun mAndFTogetherIsFatal() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/a", "x\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "c")
            fs.writeBytes("/r/msg", "hi\n".encodeToByteArray())
            val res = run(fs, "/r", "tag", "-a", "v1", "-m", "m", "-F", "msg")
            assertEquals(128, res.rc)
            assertTrue("cannot be used together" in res.stderr, res.stderr)
        }
}
