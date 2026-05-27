package com.accucodeai.kash.api.binfmt

import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinfmtTest {
    /** Captures stderr writes for assertion. */
    private class CaptureSink : SuspendSink {
        private val bytes = ArrayList<Byte>()

        override suspend fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            repeat(byteCount.toInt()) { bytes.add(source.readByte()) }
        }

        override suspend fun flush() {}

        override fun close() {}

        fun text(): String = bytes.toByteArray().decodeToString()
    }

    private val emptySource: SuspendSource =
        object : SuspendSource {
            override suspend fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long = -1L

            override fun close() {}
        }

    private val discardSink: SuspendSink =
        object : SuspendSink {
            override suspend fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                source.skip(byteCount)
            }

            override suspend fun flush() {}

            override fun close() {}
        }

    private fun boot(): Triple<KashMachine, KashProcess, InMemoryFs> {
        val fs = InMemoryFs()
        val machine = KashMachine(fs = fs)
        val init = machine.ensureInit()
        val shell =
            init.fork().apply {
                commandName = "kash"
                argv = listOf("kash")
            }
        return Triple(machine, shell, fs)
    }

    private suspend fun runExec(
        machine: KashMachine,
        parent: KashProcess,
        path: String,
        argv: List<String> = listOf(path),
        stderr: SuspendSink,
    ): Int {
        val head =
            try {
                val all = machine.fs.readBytes(path)
                if (all.size <= 128) all else all.copyOfRange(0, 128)
            } catch (_: Throwable) {
                ByteArray(0)
            }
        return machine.execFile(
            ExecRequest(
                path = path,
                argv = argv,
                env = emptyMap(),
                inlineEnv = emptyMap(),
                stdin = emptySource,
                stdout = discardSink,
                stderr = stderr,
                parent = parent,
                machine = machine,
                headPeek = head,
            ),
        )
    }

    @Test fun rejectsElfWithFriendlyMessage() =
        runTest {
            val (machine, shell, fs) = boot()
            fs.writeBytes("/work/fake.elf", byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0, 1, 2, 3))
            val err = CaptureSink()
            val rc = runExec(machine, shell, "/work/fake.elf", stderr = err)
            assertEquals(126, rc)
            assertTrue("ELF (Linux/BSD)" in err.text(), "expected ELF label, got: ${err.text()}")
            assertTrue("kash is a VM and does not execute native code" in err.text())
        }

    @Test fun rejectsMachO64() =
        runTest {
            val (machine, shell, fs) = boot()
            fs.writeBytes("/work/macho", byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()))
            val err = CaptureSink()
            assertEquals(126, runExec(machine, shell, "/work/macho", stderr = err))
            assertTrue("Mach-O" in err.text())
        }

    @Test fun rejectsPE() =
        runTest {
            val (machine, shell, fs) = boot()
            fs.writeBytes("/work/win.exe", byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00))
            val err = CaptureSink()
            assertEquals(126, runExec(machine, shell, "/work/win.exe", stderr = err))
            assertTrue("PE/COFF" in err.text())
        }

    @Test fun shebangReexecLoopsUntilDepthLimit() =
        runTest {
            // Opt the script handler into the chain (it's not auto-registered
            // because the shell uses basename + utility-runner dispatch instead
            // of strict Linux reexec). Build a self-recursive a→b→a chain.
            val (machine, shell, fs) = boot()
            machine.binfmt.register(BinfmtScript())
            fs.writeBytes("/work/a", "#!/work/b\n".encodeToByteArray())
            fs.writeBytes("/work/b", "#!/work/a\n".encodeToByteArray())
            val err = CaptureSink()
            val rc = runExec(machine, shell, "/work/a", stderr = err)
            assertEquals(126, rc)
            assertTrue("too many levels of binfmt recursion" in err.text(), err.text())
        }

    @Test fun shebangTerminatesAtElfReject() =
        runTest {
            // /work/script's shebang points at /work/bin, which is an ELF binary.
            // After reexec, the ELF reject handler claims it and stops the chain.
            val (machine, shell, fs) = boot()
            machine.binfmt.register(BinfmtScript())
            fs.writeBytes("/work/bin", byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0))
            fs.writeBytes("/work/script", "#!/work/bin\necho hi\n".encodeToByteArray())
            val err = CaptureSink()
            val rc = runExec(machine, shell, "/work/script", stderr = err)
            assertEquals(126, rc)
            assertTrue("ELF" in err.text(), err.text())
            assertTrue("/work/bin" in err.text(), err.text())
        }

    @Test fun unclaimedFileFallsThroughToEnoexecWhenNoRunnerSupplied() =
        runTest {
            // Plain text with no shebang and no native magic. The
            // shell-convention handler is auto-registered but NotMine's when
            // [ExecRequest.shellRunner] is null — so a headless caller that
            // hands no runner gets the defensive ENOEXEC diagnostic, while a
            // shell caller (with a runner) would run the file as a script.
            val (machine, shell, fs) = boot()
            fs.writeBytes("/work/plain", "hello world\n".encodeToByteArray())
            val err = CaptureSink()
            val rc = runExec(machine, shell, "/work/plain", stderr = err)
            assertEquals(126, rc)
            assertTrue("ENOEXEC" in err.text(), err.text())
        }

    @Test fun customHandlerWinsOverFallthrough() =
        runTest {
            val (machine, shell, fs) = boot()
            fs.writeBytes("/work/x.kweird", "magic-marker\n".encodeToByteArray())
            machine.binfmt.register(
                object : BinfmtHandler {
                    override val name = "kweird"
                    override val priority = 50

                    override suspend fun tryExec(req: ExecRequest): ExecOutcome =
                        if (req.path.endsWith(".kweird")) ExecOutcome.Ran(7) else ExecOutcome.NotMine
                },
            )
            val err = CaptureSink()
            val rc = runExec(machine, shell, "/work/x.kweird", stderr = err)
            assertEquals(7, rc)
            assertEquals("", err.text())
            machine.binfmt.unregister("kweird")
        }

    @Test fun handlersListedInPriorityOrder() {
        val (machine, _, _) = boot()
        // Default chain: native-reject (30), shell-convention (1000).
        // Hosts that want Linux-strict reexec opt in to BinfmtScript.
        assertEquals(
            listOf("native-reject", "shell-script"),
            machine.binfmt.handlers().map { it.name },
        )
        machine.binfmt.register(BinfmtScript())
        assertEquals(
            listOf("script", "native-reject", "shell-script"),
            machine.binfmt.handlers().map { it.name },
        )
    }
}
