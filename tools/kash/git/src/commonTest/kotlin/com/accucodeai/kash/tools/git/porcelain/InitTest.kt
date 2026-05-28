package com.accucodeai.kash.tools.git.porcelain

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
import kotlin.test.assertTrue

class InitTest {
    private suspend fun runGit(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Triple<Int, String, String> {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = GitCommand().run(args.toList(), ctx)
        return Triple(res.exitCode, out.readString(), err.readString())
    }

    @Test fun initInCurrentDirectory() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home/user/proj")
            val (rc, out, _) = runGit(fs, "/home/user/proj", "init")
            assertEquals(0, rc)
            assertEquals("Initialized empty Git repository in /home/user/proj/.git/\n", out)
            assertTrue(fs.exists("/home/user/proj/.git/HEAD"))
            assertTrue(fs.exists("/home/user/proj/.git/config"))
            assertTrue(fs.exists("/home/user/proj/.git/objects"))
            assertTrue(fs.exists("/home/user/proj/.git/refs/heads"))
            assertTrue(fs.isDirectory("/home/user/proj/.git/hooks"))

            val head = fs.readBytes("/home/user/proj/.git/HEAD").decodeToString()
            assertEquals("ref: refs/heads/main\n", head)
        }

    @Test fun initCustomBranch() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home/user/proj")
            val (rc, _, _) = runGit(fs, "/home/user/proj", "init", "-b", "trunk")
            assertEquals(0, rc)
            val head = fs.readBytes("/home/user/proj/.git/HEAD").decodeToString()
            assertEquals("ref: refs/heads/trunk\n", head)
        }

    @Test fun initPositionalDir() =
        runTest {
            val fs = InMemoryFs()
            val (rc, out, _) = runGit(fs, "/home/user", "init", "newrepo")
            assertEquals(0, rc)
            assertEquals("Initialized empty Git repository in /home/user/newrepo/.git/\n", out)
            assertTrue(fs.exists("/home/user/newrepo/.git/HEAD"))
        }

    @Test fun reinitPrintsReinitialized() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home/user/proj")
            runGit(fs, "/home/user/proj", "init")
            val (rc, out, _) = runGit(fs, "/home/user/proj", "init")
            assertEquals(0, rc)
            assertEquals("Reinitialized existing Git repository in /home/user/proj/.git/\n", out)
        }

    @Test fun reinitWarnsAboutBranch() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home/user/proj")
            runGit(fs, "/home/user/proj", "init")
            val (_, _, err) = runGit(fs, "/home/user/proj", "init", "-b", "trunk")
            assertEquals("warning: re-init: ignored --initial-branch=trunk\n", err)
            // HEAD unchanged
            val head = fs.readBytes("/home/user/proj/.git/HEAD").decodeToString()
            assertEquals("ref: refs/heads/main\n", head)
        }

    @Test fun quietSuppressesStdout() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home/user/proj")
            val (rc, out, _) = runGit(fs, "/home/user/proj", "init", "-q")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun globalDashCRedirectsCwd() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home/user/elsewhere")
            val (rc, out, _) = runGit(fs, "/", "-C", "/home/user/elsewhere", "init")
            assertEquals(0, rc)
            assertEquals("Initialized empty Git repository in /home/user/elsewhere/.git/\n", out)
        }

    @Test fun unknownSubcommandIsAnError() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runGit(fs, "/", "frobnicate")
            assertEquals(1, rc)
            assertTrue(err.contains("frobnicate"), "stderr: $err")
        }
}
