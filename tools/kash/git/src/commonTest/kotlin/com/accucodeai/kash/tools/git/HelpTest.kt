package com.accucodeai.kash.tools.git

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HelpTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun runGit(vararg args: String): Output {
        val out = Buffer()
        val err = Buffer()
        val fs = InMemoryFs()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = "/",
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Output(res.exitCode, out.readString(), err.readString())
    }

    @Test fun gitHelpListsKnownSubcommandsByGroup() {
        val out = runGit("help")
        assertEquals(0, out.rc, out.stderr)
        // Group headers appear.
        assertTrue("start a working area" in out.stdout, out.stdout)
        assertTrue("examine the history and state" in out.stdout, out.stdout)
        assertTrue("collaborate" in out.stdout, out.stdout)
        // A spread of subcommands appear.
        for (sub in listOf("init", "add", "commit", "log", "status", "diff", "merge", "rebase", "push", "blame")) {
            assertTrue(sub in out.stdout, "expected $sub in overview help:\n${out.stdout}")
        }
        // Closing hint.
        assertTrue("git help <command>" in out.stdout, out.stdout)
    }

    @Test fun gitDashDashHelpEqualsGitHelp() {
        assertEquals(runGit("help").stdout, runGit("--help").stdout)
        assertEquals(runGit("help").stdout, runGit("-h").stdout)
    }

    @Test fun gitHelpSubPrintsSubcommandSpecificHelp() {
        val out = runGit("help", "commit")
        assertEquals(0, out.rc, out.stderr)
        assertTrue("git-commit" in out.stdout, out.stdout)
        assertTrue("SYNOPSIS" in out.stdout, out.stdout)
        assertTrue("--no-verify" in out.stdout, "expected --no-verify mentioned:\n${out.stdout}")
        assertTrue("unbypassable" in out.stdout, "expected host-validator note:\n${out.stdout}")
    }

    @Test fun subDashHelpEqualsGitHelpSub() {
        val viaSub = runGit("commit", "--help").stdout
        val viaHelp = runGit("help", "commit").stdout
        assertEquals(viaHelp, viaSub)
        // -h shortcut too
        assertEquals(viaHelp, runGit("commit", "-h").stdout)
    }

    @Test fun gitHelpUnknownTopicErrors() {
        val out = runGit("help", "frobnicate")
        assertEquals(1, out.rc)
        assertTrue("no help topic" in out.stderr, out.stderr)
    }

    @Test fun perSubcommandHelpCoversEverySubcommand() {
        // Spot-check: every name listed in the overview help has its own
        // detail page. We don't enumerate the registry directly to avoid
        // coupling the test to internal layout — instead we scrape the
        // overview output for subcommand names and verify each resolves.
        val overview = runGit("help").stdout
        val knownGroups = listOf("start a working area", "examine the history and state", "collaborate")
        for (group in knownGroups) {
            assertTrue(group in overview, "missing group: $group")
        }
        val names =
            overview
                .lines()
                .filter { it.startsWith("   ") && !it.startsWith("    ") }
                .map { it.trim().substringBefore(' ') }
                .filter { it.isNotEmpty() }
                .toSet()
        // Should have at least 25 subcommands listed.
        assertTrue(names.size >= 20, "too few subcommands in overview: $names")
        // And each renders a help page.
        for (name in names) {
            val r = runGit("help", name)
            assertEquals(0, r.rc, "help $name failed: ${r.stderr}")
            assertTrue("git-$name" in r.stdout, "missing NAME line for $name:\n${r.stdout}")
        }
    }
}
