package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.ShellRunner
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BisectTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun run(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
        shellRunner: ShellRunner? = null,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf("GIT_AUTHOR_DATE" to "1700000000 +0000"),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
                shellRunner = shellRunner,
            )
        val res = GitCommand().run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    /**
     * Build a 7-commit linear history c0..c6 where the file content flips
     * from "good" to "bad" at c4. Returns the shas oldest-first (so
     * shas[0]==c0, shas[6]==c6, and the first bad commit is shas[4]).
     */
    private suspend fun buildLinear(fs: InMemoryFs): List<String> {
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        val shas = mutableListOf<String>()
        for (i in 0..6) {
            // Unique per commit (so each `git commit` actually advances HEAD)
            // while still encoding the good→bad flip at c4.
            val content = "rev $i ${if (i < 4) "good" else "bad"}\n"
            fs.writeBytes("/r/file", content.encodeToByteArray())
            run(fs, "/r", "add", "file")
            run(fs, "/r", "commit", "-m", "c$i")
            shas += run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
        }
        return shas
    }

    private val firstBadRegex = Regex("([0-9a-f]{40}) is the first bad commit")

    @Test
    fun bisectManualFindsFirstBad() =
        runTest {
            val fs = InMemoryFs()
            val shas = buildLinear(fs)

            val start = run(fs, "/r", "bisect", "start", shas[6], shas[0])
            assertEquals(0, start.rc)
            assertTrue(start.stdout.contains("Bisecting:"), "start should pick a midpoint: ${start.stdout}")

            var firstBad: String? = null
            var guard = 0
            while (guard++ < 25 && firstBad == null) {
                val content = fs.readBytes("/r/file").decodeToString()
                val res =
                    if (content.contains("good")) {
                        run(fs, "/r", "bisect", "good")
                    } else {
                        run(fs, "/r", "bisect", "bad")
                    }
                firstBad = firstBadRegex.find(res.stdout)?.groupValues?.get(1)
            }
            assertEquals(shas[4], firstBad, "bisect should pin c4 as the first bad commit")

            // Session stays live until reset.
            assertTrue(fs.exists("/r/.git/BISECT_START"))
            val reset = run(fs, "/r", "bisect", "reset")
            assertEquals(0, reset.rc)
            assertFalse(fs.exists("/r/.git/BISECT_START"))
            assertFalse(fs.exists("/r/.git/refs/bisect"))
        }

    @Test
    fun bisectRunAutomatesSearch() =
        runTest {
            val fs = InMemoryFs()
            val shas = buildLinear(fs)

            // Fake test command: passes (exit 0) iff the worktree file is "good".
            val tester =
                ShellRunner { _ ->
                    if (fs.readBytes("/r/file").decodeToString().contains("good")) 0 else 1
                }

            run(fs, "/r", "bisect", "start", shas[6], shas[0])
            val res = run(fs, "/r", "bisect", "run", "test-script", shellRunner = tester)
            assertEquals(0, res.rc)
            assertTrue(
                res.stdout.contains("${shas[4]} is the first bad commit"),
                "run should converge on c4: ${res.stdout}",
            )
            assertTrue(res.stdout.contains("bisect run success"), res.stdout)
        }

    @Test
    fun bisectResetReturnsToOriginalBranch() =
        runTest {
            val fs = InMemoryFs()
            val shas = buildLinear(fs)
            val origHead = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
            val origBranch = run(fs, "/r", "rev-parse", "--abbrev-ref", "HEAD").stdout.trim()

            run(fs, "/r", "bisect", "start", shas[6], shas[0])
            val mid = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
            assertTrue(mid != origHead, "bisect should have moved HEAD off the tip")

            run(fs, "/r", "bisect", "reset")
            assertEquals(origHead, run(fs, "/r", "rev-parse", "HEAD").stdout.trim())
            assertEquals(origBranch, run(fs, "/r", "rev-parse", "--abbrev-ref", "HEAD").stdout.trim())
            // Worktree restored to the tip (c6).
            assertEquals("rev 6 bad\n", fs.readBytes("/r/file").decodeToString())
        }

    @Test
    fun bisectSkipAvoidsCommit() =
        runTest {
            val fs = InMemoryFs()
            val shas = buildLinear(fs)

            run(fs, "/r", "bisect", "start", shas[6], shas[0])
            val mid = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
            val res = run(fs, "/r", "bisect", "skip")
            assertEquals(0, res.rc)
            val afterSkip = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
            assertTrue(afterSkip != mid, "skip should pick a different commit to test")
            assertTrue(fs.exists("/r/.git/refs/bisect/skip-$mid"))
        }

    @Test
    fun bisectReplayReproducesSession() =
        runTest {
            val fs = InMemoryFs()
            val shas = buildLinear(fs)

            // Drive a full manual session.
            run(fs, "/r", "bisect", "start", shas[6], shas[0])
            var guard = 0
            while (guard++ < 25) {
                val content = fs.readBytes("/r/file").decodeToString()
                val res =
                    if (content.contains("good")) {
                        run(fs, "/r", "bisect", "good")
                    } else {
                        run(fs, "/r", "bisect", "bad")
                    }
                if (firstBadRegex.containsMatchIn(res.stdout)) break
            }
            // Capture the replayable log and save it to a file.
            val log = run(fs, "/r", "bisect", "log").stdout
            assertTrue(log.contains("git bisect start"), log)
            fs.writeBytes("/r/session.log", log.encodeToByteArray())

            run(fs, "/r", "bisect", "reset")

            val replay = run(fs, "/r", "bisect", "replay", "/r/session.log")
            assertEquals(0, replay.rc, replay.stderr)
            assertTrue(
                replay.stdout.contains("${shas[4]} is the first bad commit"),
                "replay should reach the same first bad commit: ${replay.stdout}",
            )
        }

    @Test
    fun bisectCustomTerms() =
        runTest {
            val fs = InMemoryFs()
            val shas = buildLinear(fs)

            run(
                fs,
                "/r",
                "bisect",
                "start",
                "--term-old=working",
                "--term-new=broken",
                shas[6],
                shas[0],
            )
            val terms = run(fs, "/r", "bisect", "terms")
            assertTrue(terms.stdout.contains("working for the old state"), terms.stdout)
            assertTrue(terms.stdout.contains("broken for the new state"), terms.stdout)

            // The custom term words drive the marks.
            var firstBad: String? = null
            var guard = 0
            while (guard++ < 25 && firstBad == null) {
                val content = fs.readBytes("/r/file").decodeToString()
                val res =
                    if (content.contains("good")) {
                        run(fs, "/r", "bisect", "working")
                    } else {
                        run(fs, "/r", "bisect", "broken")
                    }
                firstBad = Regex("([0-9a-f]{40}) is the first broken commit").find(res.stdout)?.groupValues?.get(1)
            }
            assertEquals(shas[4], firstBad)
        }

    @Test
    fun bisectViewListsSuspects() =
        runTest {
            val fs = InMemoryFs()
            val shas = buildLinear(fs)
            run(fs, "/r", "bisect", "start", shas[6], shas[0])
            val view = run(fs, "/r", "bisect", "view")
            assertEquals(0, view.rc)
            // The good commit (c0) is excluded; the bad tip (c6) is a suspect.
            assertTrue(view.stdout.contains(shas[6].substring(0, 7)), view.stdout)
            assertFalse(view.stdout.contains(shas[0].substring(0, 7)), view.stdout)
        }
}
