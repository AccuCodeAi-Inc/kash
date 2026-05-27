package com.accucodeai.kash

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.installStdio
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.fs.installSystemBin
import com.accucodeai.kash.test.FakeTerminalControl
import com.accucodeai.kash.tools.kash.KashShellCommand
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance tests for `KashShellCommand` — the shell modeled as a tool
 * registered at `/usr/bin/kash`. The whole point of step 1 of the
 * "kash-as-Command" inversion is that typing `kash …` at the prompt
 * dispatches just like any other tool, going through `machine.spawn`.
 *
 * Step 2 adds the interactive REPL branch — see `interactiveRepl…` tests.
 */
class KashShellCommandTest {
    // ---- -c / stdin / argv parsing ----

    @Test fun dashCRunsInlineScript() =
        runTest {
            val r = Kash().exec("kash -c 'echo hi'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("hi\n", r.stdout)
        }

    @Test fun dashCExitStatusPropagates() =
        runTest {
            val r = Kash().exec("kash -c 'exit 42'")
            assertEquals(42, r.exitCode)
        }

    @Test fun recursiveKashWorks() =
        runTest {
            val r = Kash().exec("kash -c \"kash -c 'echo nested'\"")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("nested\n", r.stdout)
        }

    @Test fun dashCInheritsEnvFromParent() =
        runTest {
            // Parent sets X in its env (export), child kash reads it. Confirms
            // env crosses the spawn boundary like POSIX execve(envp).
            val r = Kash().exec("export X=parent_value; kash -c 'echo \$X'")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("parent_value\n", r.stdout)
        }

    @Test fun dashCWithMissingArgumentErrors() =
        runTest {
            val r = Kash().exec("kash -c")
            assertEquals(2, r.exitCode)
        }

    @Test fun dashCDollarZeroAndPositionalParams() =
        runTest {
            // bash: `kash -c 'CMD' name arg1 arg2` makes $0=name, $1=arg1, $2=arg2.
            val r = Kash().exec("kash -c 'echo \$0 \$1 \$2' myname foo bar")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("myname foo bar\n", r.stdout)
        }

    // ---- script-file mode ----

    @Test fun scriptFileRuns() =
        runTest {
            val kash = Kash()
            kash.fs.writeBytes("/work/hello.sh", "echo file-hello\n".encodeToByteArray())
            val r = kash.exec("kash /work/hello.sh")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("file-hello\n", r.stdout)
        }

    @Test fun scriptFilePositionalArgs() =
        runTest {
            val kash = Kash()
            kash.fs.writeBytes(
                "/work/echo-args.sh",
                "echo \$0 then \$1 \$2 \$3\n".encodeToByteArray(),
            )
            val r = kash.exec("kash /work/echo-args.sh one two three")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("/work/echo-args.sh then one two three\n", r.stdout)
        }

    @Test fun scriptFileMissingReturns127() =
        runTest {
            val r = Kash().exec("kash /no/such/script.sh")
            assertEquals(127, r.exitCode)
            assertContains(r.stderr, "No such file or directory")
        }

    @Test fun scriptFileRelativePathResolvesAgainstCwd() =
        runTest {
            val kash = Kash(initialCwd = "/work")
            kash.fs.mkdirs("/work")
            kash.fs.writeBytes("/work/hi.sh", "echo from-relative\n".encodeToByteArray())
            val r = kash.exec("kash hi.sh")
            assertEquals(0, r.exitCode, "exit=${r.exitCode} stderr=${r.stderr}")
            assertEquals("from-relative\n", r.stdout)
        }

    @Test fun scriptFileExitCodePropagates() =
        runTest {
            val kash = Kash()
            kash.fs.writeBytes("/work/fail.sh", "exit 7\n".encodeToByteArray())
            val r = kash.exec("kash /work/fail.sh")
            assertEquals(7, r.exitCode)
        }

    @Test fun scriptFileDirectoryReturns126() =
        runTest {
            val kash = Kash()
            kash.fs.mkdirs("/work/somedir")
            val r = kash.exec("kash /work/somedir")
            assertEquals(126, r.exitCode)
            assertContains(r.stderr, "is a directory")
        }

    // ---- interactive REPL ----

    @Test fun interactiveReplExecutesTypedLineAndExits() =
        runTest {
            val out = Buffer()
            val (shell, ctx) =
                setupInteractive(out) { term ->
                    term.pushChars("echo from-repl")
                    term.pushKey(Key.Named.ENTER)
                    term.pushChars("exit")
                    term.pushKey(Key.Named.ENTER)
                }
            val r = shell.run(emptyList(), ctx)
            assertEquals(0, r.exitCode)
            assertContains(out.readString(), "from-repl")
        }

    @Test fun interactiveReplEofExitsCleanly() =
        runTest {
            val out = Buffer()
            val (shell, ctx) =
                setupInteractive(out) { term ->
                    term.pushKey(Key.Ctrl('D')) // EOF at empty prompt
                }
            val r = shell.run(emptyList(), ctx)
            assertEquals(0, r.exitCode)
        }

    @Test fun interactiveReplCwdPersistsAcrossLines() =
        runTest {
            val out = Buffer()
            val (shell, ctx) =
                setupInteractive(out) { term ->
                    term.pushChars("cd /tmp")
                    term.pushKey(Key.Named.ENTER)
                    term.pushChars("pwd")
                    term.pushKey(Key.Named.ENTER)
                    term.pushChars("exit")
                    term.pushKey(Key.Named.ENTER)
                }
            ctx.process.machine.fs
                .mkdirs("/tmp")
            val r = shell.run(emptyList(), ctx)
            assertEquals(0, r.exitCode)
            assertContains(out.readString(), "/tmp")
        }

    @Test fun interactiveReplMultilineContinuation() =
        runTest {
            val out = Buffer()
            val (shell, ctx) =
                setupInteractive(out) { term ->
                    // `if true` is incomplete; press Enter, then completion
                    // line submits the assembled statement.
                    term.pushChars("if true")
                    term.pushKey(Key.Named.ENTER)
                    term.pushChars("then echo cont-works; fi")
                    term.pushKey(Key.Named.ENTER)
                    term.pushChars("exit")
                    term.pushKey(Key.Named.ENTER)
                }
            val r = shell.run(emptyList(), ctx)
            assertEquals(0, r.exitCode)
            assertContains(out.readString(), "cont-works")
        }

    @Test fun interactiveReplNestedKashInheritsTerminal() =
        runTest {
            // Regression: typing `kash` at an interactive prompt used to
            // crash the nested invocation with "no terminal available for
            // interactive mode" because [KashShellCommand.runReplLoop] never
            // forwarded [ctx.process.fdTable[0].ofd.terminalControl] into
            // [interp.runStreaming], so the spawned child's fd 0 OFD was
            // built with terminalControl=null. Same mechanism breaks `nano`
            // and any tool that flips raw mode.
            val out = Buffer()
            val err = Buffer()
            val (shell, ctx) =
                setupInteractive(out, err) { term ->
                    term.pushChars("kash")
                    term.pushKey(Key.Named.ENTER) // outer dispatches inner
                    term.pushChars("exit")
                    term.pushKey(Key.Named.ENTER) // inner REPL exits
                    term.pushChars("exit")
                    term.pushKey(Key.Named.ENTER) // outer REPL exits
                }
            shell.run(emptyList(), ctx)
            val errStr = err.readString()
            assertTrue(
                "no terminal available" !in errStr,
                "nested kash must inherit the terminal handle; stderr was: $errStr",
            )
        }

    @Test fun interactiveReplCtrlCDiscardsAndKeepsLooping() =
        runTest {
            val out = Buffer()
            val (shell, ctx) =
                setupInteractive(out) { term ->
                    term.pushChars("garbage")
                    term.pushKey(Key.Ctrl('C'))
                    term.pushChars("echo recovered")
                    term.pushKey(Key.Named.ENTER)
                    term.pushChars("exit")
                    term.pushKey(Key.Named.ENTER)
                }
            val r = shell.run(emptyList(), ctx)
            assertEquals(0, r.exitCode)
            val s = out.readString()
            assertContains(s, "recovered")
            assertTrue("garbage" !in s, "Ctrl-C should discard partial input; output was: $s")
        }

    /**
     * Build a self-contained machine + process + KashShellCommand wired to
     * a [FakeTerminalControl] preloaded by [setupKeys]. Stdout flows into
     * [out]; stdin/stderr go to throwaway Buffers. The shell command's
     * registry includes itself (so recursive `kash` inside the REPL would
     * also work).
     */
    private suspend fun setupInteractive(
        out: Buffer,
        err: Buffer = Buffer(),
        setupKeys: suspend (FakeTerminalControl) -> Unit,
    ): Pair<KashShellCommand, CommandContext> {
        val term = FakeTerminalControl()
        setupKeys(term)

        // KashShellCommand is now a @Single CommandSpec — defaultCommandSpecs()
        // already includes it (BuiltinsModule scans `commands.*`). Pull our
        // local handle out of the registry by name.
        val registry = CommandRegistry(defaultCommandSpecs())
        val shell = registry["kash"]!!.command as KashShellCommand

        val fs =
            installSystemBin(
                com.accucodeai.kash.fs
                    .InMemoryFs(),
                registry = { registry },
            )
        val machine = KashMachine(fs = fs, registry = registry)
        val process = machine.ensureInit()
        // tty=true on all three so the interactive branch triggers.
        process.installStdio(
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            stdinIsTty = true,
            stdoutIsTty = true,
            stderrIsTty = true,
            terminalControl = term,
        )
        val ctx = CommandContext(process = process, isInteractive = true)
        return shell to ctx
    }
}
