package com.accucodeai.kash.fs

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.ProcessState
import com.accucodeai.kash.api.installFd
import com.accucodeai.kash.api.io.asSuspendSource
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ProcFs Phase 1: /proc/self, /proc/<pid>/{cmdline,environ,cwd,exe}.
 * Mirrors Linux's per-process resolution model (`current` decides what
 * `/proc/self` points at; `/proc/<pid>` requires the pid to exist).
 */
class ProcFsTest {
    private fun bootMachine(): KashMachine = KashMachine(fs = InMemoryFs())

    private fun bootProcess(
        machine: KashMachine,
        pid: Int,
        argv: List<String> = emptyList(),
        env: MutableMap<String, String> = mutableMapOf(),
        cwd: String = "/",
        commandName: String = "",
    ): KashProcess {
        val p = KashProcess(machine = machine, pid = pid, cwd = cwd, env = env)
        p.argv = argv
        p.commandName = commandName
        machine.processTable[pid] = p
        return p
    }

    @Test fun emptyMachine_listsOnlySelf() =
        runTest {
            val fs = ProcFs(processes = { emptyMap() })
            assertEquals(listOf("cpuinfo", "loadavg", "meminfo", "self", "stat", "uptime"), fs.list("/"))
            assertTrue(fs.exists("/"))
            assertTrue(fs.exists("/self"))
        }

    @Test fun list_returnsLivePidsPlusSelf() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1)
            bootProcess(m, pid = 7)
            bootProcess(m, pid = 42)
            val fs = ProcFs(processes = { m.processTable })
            assertEquals(
                listOf("1", "42", "7", "cpuinfo", "loadavg", "meminfo", "self", "stat", "uptime"),
                fs.list("/"),
            )
        }

    @Test fun pidDir_listsCurrentEntries() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1)
            val fs = ProcFs(processes = { m.processTable })
            val entries = fs.list("/1")
            assertEquals(
                setOf("cmdline", "environ", "cwd", "exe", "fd", "status", "stat", "maps"),
                entries.toSet(),
            )
        }

    @Test fun rootDir_listsMachineWideEntries() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1)
            val fs = ProcFs(processes = { m.processTable })
            val entries = fs.list("/").toSet()
            assertTrue("meminfo" in entries)
            assertTrue("cpuinfo" in entries)
            assertTrue("self" in entries)
            assertTrue("1" in entries)
        }

    @Test fun cmdline_isNulSeparatedWithTrailingNul() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1, argv = listOf("kash", "-c", "echo hi"))
            val fs = ProcFs(processes = { m.processTable })
            val bytes = fs.readBytes("/1/cmdline")
            // Linux convention: argv joined by NUL, trailing NUL.
            val expected = "kash\u0000-c\u0000echo hi\u0000".encodeToByteArray()
            assertEquals(expected.toList(), bytes.toList())
        }

    @Test fun cmdline_emptyArgv_emptyBytes() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1, argv = emptyList())
            val fs = ProcFs(processes = { m.processTable })
            // Kernel threads have empty /proc/<pid>/cmdline; we match that.
            assertEquals(0, fs.readBytes("/1/cmdline").size)
        }

    @Test fun environ_isNulSeparatedKeyEqValue() =
        runTest {
            val m = bootMachine()
            bootProcess(
                m,
                pid = 1,
                env = linkedMapOf("USER" to "alice", "HOME" to "/home/alice"),
            )
            val fs = ProcFs(processes = { m.processTable })
            val bytes = fs.readBytes("/1/environ").decodeToString()
            assertEquals("USER=alice\u0000HOME=/home/alice\u0000", bytes)
        }

    @Test fun cwd_isSymlinkToProcessCwd() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 5, cwd = "/home/alice/work")
            val fs = ProcFs(processes = { m.processTable })
            assertEquals("/home/alice/work", fs.readSymlink("/5/cwd"))
        }

    @Test fun exe_unregisteredCommand_returnsBareName() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1, commandName = "missing-from-registry")
            val fs = ProcFs(processes = { m.processTable })
            assertEquals("missing-from-registry", fs.readSymlink("/1/exe"))
        }

    @Test fun unknownPid_returnsFileNotFound() =
        runTest {
            val fs = ProcFs(processes = { emptyMap() })
            assertTrue(!fs.exists("/9999"))
            assertFailsWith<FileNotFound> { fs.list("/9999") }
            assertFailsWith<FileNotFound> { fs.stat("/9999/cmdline") }
        }

    @Test fun openHandle_proc_pid_cmdline_returnsOfdWithCorrectPath() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1, argv = listOf("kash"))
            val fs = ProcFs(processes = { m.processTable })
            val ofd = fs.openHandle("/1/cmdline", AccessMode.RDONLY)
            assertNotNull(ofd)
            assertEquals("/proc/1/cmdline", ofd.path)
            val data =
                ofd.source!!.let { src ->
                    val b = Buffer()
                    while (src.readAtMostTo(b, 4096) != -1L) Unit
                    b.readByteArray()
                }
            assertEquals("kash\u0000", data.decodeToString())
        }

    @Test fun openHandle_proc_self_resolvesToOpenerPid() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1, argv = listOf("init"))
            bootProcess(m, pid = 42, argv = listOf("kash", "-c", "echo hi"))
            val procA = m.processTable[1]
            val procB = m.processTable[42]
            val fs = ProcFs(processes = { m.processTable })

            val ofdA = fs.openHandle("/self/cmdline", AccessMode.RDONLY, opener = procA)!!
            val ofdB = fs.openHandle("/self/cmdline", AccessMode.RDONLY, opener = procB)!!

            assertEquals("/proc/1/cmdline", ofdA.path)
            assertEquals("/proc/42/cmdline", ofdB.path)
        }

    @Test fun openHandle_proc_self_withNullOpener_fallsBackToEmptyContent() =
        runTest {
            // After the opener-threading refactor, openHandle without an
            // opener for /proc/self/X returns an OFD with empty content
            // rather than null — the symlink-chain resolver still has a
            // path to walk and `[ -e ... ]` works without process context.
            val m = bootMachine()
            bootProcess(m, pid = 1)
            val fs = ProcFs(processes = { m.processTable })
            val ofd = fs.openHandle("/self/cmdline", AccessMode.RDONLY, opener = null)
            assertNotNull(ofd)
            // Path reported is /proc/self (no opener to substitute).
            assertEquals("/proc/self/cmdline", ofd.path)
            val bytes =
                ofd.source!!.let { src ->
                    val b = Buffer()
                    while (src.readAtMostTo(b, 4096) != -1L) Unit
                    b.readByteArray()
                }
            assertEquals(0, bytes.size)
        }

    @Test fun openHandle_writeAccessRejected() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1, argv = listOf("x"))
            val fs = ProcFs(processes = { m.processTable })
            assertNull(fs.openHandle("/1/cmdline", AccessMode.WRONLY))
            assertNull(fs.openHandle("/1/cmdline", AccessMode.RDWR))
        }

    @Test fun emptyProcessTable_lookupsReportEmpty() =
        runTest {
            // Headless mount: every lookup degrades gracefully, no NPE.
            val fs = ProcFs(processes = { emptyMap() })
            assertEquals(listOf("cpuinfo", "loadavg", "meminfo", "self", "stat", "uptime"), fs.list("/"))
            assertTrue(!fs.exists("/1"))
            assertFailsWith<FileNotFound> { fs.stat("/1") }
        }

    @Test fun mountedAtSlashProc_routesCorrectly() =
        runTest {
            val machine = KashMachine(fs = InMemoryFs())
            bootProcess(machine, pid = 1, argv = listOf("init"))
            val mountedFs =
                installSystemBin(
                    machine.fs,
                    registry = { machine.registry },
                    processes = { machine.processTable },
                )
            assertTrue(mountedFs.exists("/proc"))
            assertTrue(mountedFs.exists("/proc/1"))
            assertTrue(mountedFs.exists("/proc/1/cmdline"))
            val bytes = mountedFs.readBytes("/proc/1/cmdline")
            assertEquals("init\u0000", bytes.decodeToString())
        }

    // ---- /proc/<pid>/fd/N ----

    private fun ofdRead(path: String? = "/some/file"): OpenFileDescription =
        OpenFileDescription(
            source = Buffer().asSuspendSource(),
            accessMode = AccessMode.RDONLY,
            path = path,
            owning = false,
        )

    @Test fun fdDir_listsOpenSlots() =
        runTest {
            val m = bootMachine()
            val p = bootProcess(m, pid = 1)
            p.installFd(0, ofdRead())
            p.installFd(2, ofdRead())
            p.installFd(7, ofdRead())
            val fs = ProcFs(processes = { m.processTable })
            assertEquals(listOf("0", "2", "7"), fs.list("/1/fd"))
        }

    @Test fun fdEntry_readlinkReturnsUnderlyingPath() =
        runTest {
            val m = bootMachine()
            val p = bootProcess(m, pid = 1)
            p.installFd(3, ofdRead(path = "/home/alice/data.txt"))
            val fs = ProcFs(processes = { m.processTable })
            assertEquals("/home/alice/data.txt", fs.readSymlink("/1/fd/3"))
        }

    @Test fun fdEntry_readlinkFallsBackToAnonInodePlaceholder() =
        runTest {
            // OFD with no path, not a tty, and no pipeInode (a capture-buffer
            // / synthetic OFD): Linux emits `anon_inode:[…]` for fds backed
            // by anonymous inodes that aren't pipes. Real pipes set
            // [OpenFileDescription.pipeInode] and get `pipe:[<inode>]` (see
            // [fdEntry_readlinkPipeOfd_returnsPipeInode]).
            val m = bootMachine()
            val p = bootProcess(m, pid = 1)
            p.installFd(4, ofdRead(path = null))
            val fs = ProcFs(processes = { m.processTable })
            assertEquals("anon_inode:[fd-4]", fs.readSymlink("/1/fd/4"))
        }

    @Test fun fdEntry_readlinkTtyOfd_returnsDevTty() =
        runTest {
            val m = bootMachine()
            val p = bootProcess(m, pid = 1)
            // Build an OFD that claims to be a tty but has no recorded path.
            p.installFd(
                0,
                OpenFileDescription(
                    source = Buffer().asSuspendSource(),
                    accessMode = AccessMode.RDONLY,
                    path = null,
                    isTty = true,
                    owning = false,
                ),
            )
            val fs = ProcFs(processes = { m.processTable })
            assertEquals("/dev/tty", fs.readSymlink("/1/fd/0"))
        }

    @Test fun openHandle_procFd_dupsUnderlyingOfd() =
        runTest {
            val m = bootMachine()
            val p = bootProcess(m, pid = 1)
            val orig = ofdRead()
            p.installFd(3, orig)
            val fs = ProcFs(processes = { m.processTable })
            val ofd = fs.openHandle("/1/fd/3", AccessMode.RDONLY)
            assertNotNull(ofd)
            assertTrue(ofd !== orig)
            assertEquals("/proc/1/fd/3", ofd.path)
            assertSame(orig.source, ofd.source)
            assertEquals(false, ofd.owning)
        }

    @Test fun openHandle_procSelfFd_resolvesToOpenerTable() =
        runTest {
            val m = bootMachine()
            val a = bootProcess(m, pid = 1)
            val b = bootProcess(m, pid = 42)
            val sa = ofdRead(path = "/A")
            val sb = ofdRead(path = "/B")
            a.installFd(3, sa)
            b.installFd(3, sb)
            val fs = ProcFs(processes = { m.processTable })

            val ofdA = fs.openHandle("/self/fd/3", AccessMode.RDONLY, opener = a)!!
            val ofdB = fs.openHandle("/self/fd/3", AccessMode.RDONLY, opener = b)!!
            assertEquals("/proc/1/fd/3", ofdA.path)
            assertEquals("/proc/42/fd/3", ofdB.path)
            assertSame(sa.source, ofdA.source)
            assertSame(sb.source, ofdB.source)
        }

    @Test fun fdEntry_unopenedSlot_returnsFileNotFound() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1)
            val fs = ProcFs(processes = { m.processTable })
            assertFailsWith<FileNotFound> { fs.stat("/1/fd/99") }
            assertNull(fs.openHandle("/1/fd/99", AccessMode.RDONLY))
        }

    @Test fun procFs_doesNotDependOnMachine_atConstruction() =
        runTest {
            // Architectural assertion: ProcFs's only ctor dependency is the
            // process-table supplier. Should be possible to construct without
            // any KashMachine in sight (a custom embedder might build its own
            // process-tracking layer). Compiles + runs ⇒ contract holds.
            val customTable: MutableMap<Int, KashProcess> = mutableMapOf()
            val fs = ProcFs(processes = { customTable })
            // Empty table: machine-wide entries + "self" appear.
            assertEquals(listOf("cpuinfo", "loadavg", "meminfo", "self", "stat", "uptime"), fs.list("/"))
        }

    // ---- Phase 3: status / stat / maps / meminfo / cpuinfo ----

    private suspend fun statusBody(
        m: KashMachine,
        pid: Int,
        configure: KashProcess.() -> Unit = {},
    ): String {
        val p = bootProcess(m, pid = pid)
        p.configure()
        val fs = ProcFs(processes = { m.processTable })
        return fs.readBytes("/$pid/status").decodeToString()
    }

    @Test fun status_keyValueShape() =
        runTest {
            val body = statusBody(bootMachine(), 1)
            val labelLine = Regex("^[A-Za-z_][A-Za-z_0-9()]*:\\s")
            body.lineSequence().filter { it.isNotEmpty() }.forEach {
                assertTrue(labelLine.containsMatchIn(it), "non-label line: $it")
            }
            // Spot-check the mandatory labels.
            for (label in listOf("Name:", "Pid:", "PPid:", "State:", "Uid:", "Gid:")) {
                assertTrue(body.contains(label), "missing label: $label")
            }
        }

    @Test fun status_stateLabel_mapsProcessState() =
        runTest {
            assertTrue(
                statusBody(bootMachine(), 1) { state = ProcessState.RUNNING }
                    .contains("State:\tR (running)"),
            )
            assertTrue(
                statusBody(bootMachine(), 1) { state = ProcessState.STOPPED }
                    .contains("State:\tT (stopped)"),
            )
            assertTrue(
                statusBody(bootMachine(), 1) { state = ProcessState.ZOMBIE }
                    .contains("State:\tZ (zombie)"),
            )
        }

    @Test fun status_uidLine_hasFourTabSeparatedInts() =
        runTest {
            val body = statusBody(bootMachine(), 1)
            val uidLine = body.lines().first { it.startsWith("Uid:") }
            val parts = uidLine.split('\t')
            // "Uid:" + 4 numeric fields.
            assertEquals(5, parts.size, "got: $parts")
            for (p in parts.drop(1)) assertNotNull(p.toIntOrNull(), "non-numeric: $p")
        }

    @Test fun status_sigmask_isExactly16HexCharsAndReflectsBits() =
        runTest {
            val body =
                statusBody(bootMachine(), 1) {
                    signalMask.add(com.accucodeai.kash.api.signal.SigTerm) // signum 15
                }
            val sigBlk = body.lines().first { it.startsWith("SigBlk:") }.removePrefix("SigBlk:\t")
            assertEquals(16, sigBlk.length, "got: '$sigBlk'")
            val mask = sigBlk.toULong(16).toLong()
            assertEquals(1L shl (15 - 1), mask, "bit 14 should be set")
            // Untouched masks are zero.
            assertTrue(body.contains("SigIgn:\t0000000000000000"))
            assertTrue(body.contains("SigCgt:\t0000000000000000"))
        }

    private suspend fun statBody(
        m: KashMachine,
        pid: Int,
        configure: KashProcess.() -> Unit = {},
    ): String {
        val p = bootProcess(m, pid = pid)
        p.configure()
        val fs = ProcFs(processes = { m.processTable })
        return fs.readBytes("/$pid/stat").decodeToString()
    }

    @Test fun stat_lastCloseParen_splitsCommFromRest() =
        runTest {
            val body =
                statBody(bootMachine(), 7) {
                    commandName = "weird (name)"
                }
            // Procps idiom: find LAST ')', everything after is the rest.
            val lastParen = body.lastIndexOf(')')
            val commWithParens = body.substring(0, lastParen + 1)
            assertTrue(commWithParens.endsWith("(weird (name))"), "got: $commWithParens")
            val rest = body.substring(lastParen + 2).trimEnd().split(' ')
            // 50 fields after `state` since pid + comm consume positions 1-2.
            assertTrue(rest.size >= 50, "got ${rest.size}: $rest")
        }

    @Test fun stat_fieldCount_isAtLeast52() =
        runTest {
            val body = statBody(bootMachine(), 1)
            val lastParen = body.lastIndexOf(')')
            val tail = body.substring(lastParen + 1).trim().split(Regex("\\s+"))
            // pid (1) + (comm) (2) + tail = total fields.
            assertTrue(2 + tail.size >= 52, "expected ≥52 fields, got ${2 + tail.size}")
        }

    @Test fun stat_positionalFields() =
        runTest {
            val m = bootMachine()
            val p = bootProcess(m, pid = 42)
            p.ppid = 7
            p.pgid = 100
            p.sid = 200
            p.state = ProcessState.RUNNING
            val body = ProcFs(processes = { m.processTable }).readBytes("/42/stat").decodeToString()
            val lastParen = body.lastIndexOf(')')
            // Split fields 1-2 from the rest.
            val head = body.substring(0, lastParen + 1)
            val tail = body.substring(lastParen + 2).trimEnd().split(' ')
            assertEquals("42", head.substringBefore(' '))
            // tail[0] is field 3, tail[1] = field 4, etc.
            assertEquals("R", tail[0])
            assertEquals("7", tail[1]) // ppid
            assertEquals("100", tail[2]) // pgrp
            assertEquals("200", tail[3]) // session
            // processor = field 38 → tail index 35.
            assertEquals("0", tail[35])
        }

    @Test fun maps_heapLineMatchesKernelShape() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1)
            val body =
                ProcFs(processes = { m.processTable })
                    .readBytes("/1/maps")
                    .decodeToString()
                    .trimEnd('\n')
            val re = Regex("^[0-9a-f]+-[0-9a-f]+ [rwxsp-]{4} [0-9a-f]+ \\d+:\\d+ \\d+\\s+\\[heap\\]$")
            assertTrue(re.matches(body), "got: '$body'")
        }

    @Test fun meminfo_lineFormatMatchesKernelString() =
        runTest {
            val fs = ProcFs(processes = { emptyMap() })
            val body = fs.readBytes("/meminfo").decodeToString()
            // Every non-blank line: <label>: <num> kB, with lowercase k, capital B.
            val re = Regex("^[A-Za-z_]+(?:\\(\\w+\\))?:\\s+\\d+ kB$")
            val lines = body.lines().filter { it.isNotEmpty() }
            assertTrue(lines.isNotEmpty())
            for (l in lines) assertTrue(re.matches(l), "bad meminfo line: '$l'")
            assertTrue(body.startsWith("MemTotal:"))
        }

    @Test fun meminfo_awkSecondColumn_isNumeric() =
        runTest {
            val fs = ProcFs(processes = { emptyMap() })
            val body = fs.readBytes("/meminfo").decodeToString()
            body.lines().filter { it.isNotEmpty() }.forEach { line ->
                val cols = line.split(Regex("\\s+"))
                assertNotNull(cols[1].toLongOrNull(), "field 2 not numeric in: $line")
                assertEquals("kB", cols[2])
            }
        }

    @Test fun cpuinfo_grepCountProcessor_isOne() =
        runTest {
            val fs = ProcFs(processes = { emptyMap() })
            val body = fs.readBytes("/cpuinfo").decodeToString()
            val count = body.lines().count { it.startsWith("processor") }
            assertEquals(1, count)
        }

    @Test fun cpuinfo_blankLineAfterBlock() =
        runTest {
            val fs = ProcFs(processes = { emptyMap() })
            val body = fs.readBytes("/cpuinfo").decodeToString()
            assertTrue(body.endsWith("\n\n"), "expected trailing blank line")
        }

    @Test fun procSelf_status_resolvesToOpenerPid() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1, commandName = "init")
            val opener = bootProcess(m, pid = 99, commandName = "kash")
            val fs = ProcFs(processes = { m.processTable })
            val ofd = fs.openHandle("/self/status", AccessMode.RDONLY, opener = opener)!!
            assertEquals("/proc/99/status", ofd.path)
            val body =
                ofd.source!!.let { src ->
                    val b = Buffer()
                    while (src.readAtMostTo(b, 4096) != -1L) Unit
                    b.readByteArray().decodeToString()
                }
            assertTrue(body.contains("Name:\tkash"))
            assertTrue(body.contains("Pid:\t99"))
        }

    // ---- /proc/stat, /proc/uptime, /proc/loadavg ----

    @Test fun procStat_hasCpuLineAndBtime() =
        runTest {
            val m = KashMachine(fs = InMemoryFs(), bootEpochSeconds = 1_700_000_000L)
            bootProcess(m, pid = 1)
            val body = ProcFs(processes = { m.processTable }).readBytes("/stat").decodeToString()
            assertTrue(body.startsWith("cpu  0 0 0 0 0 0 0 0 0 0\n"), "got: $body")
            assertTrue(body.contains("cpu0 0 0 0 0 0 0 0 0 0 0\n"))
            assertTrue(Regex("(?m)^btime 1700000000$").containsMatchIn(body))
            assertTrue(Regex("(?m)^procs_blocked 0$").containsMatchIn(body))
        }

    @Test fun procStat_processes_reflectsNextPid() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1)
            bootProcess(m, pid = 2)
            // nextPid is 1 by default — bootProcess inserts but does not bump.
            // Test the formatter contract: processes = nextPid - 1.
            m.nextPid = 5
            val body = ProcFs(processes = { m.processTable }).readBytes("/stat").decodeToString()
            assertTrue(Regex("(?m)^processes 4$").containsMatchIn(body), "got: $body")
        }

    @Test fun procStat_procsRunning_countsRunningOnly() =
        runTest {
            val m = bootMachine()
            val a = bootProcess(m, pid = 1)
            val b = bootProcess(m, pid = 2)
            val c = bootProcess(m, pid = 3)
            a.state = ProcessState.RUNNING
            b.state = ProcessState.ZOMBIE
            c.state = ProcessState.STOPPED
            val body = ProcFs(processes = { m.processTable }).readBytes("/stat").decodeToString()
            assertTrue(Regex("(?m)^procs_running 1$").containsMatchIn(body), "got: $body")
        }

    @Test fun uptime_format_twoFloatsWithCentiseconds() =
        runTest {
            val fs = ProcFs(processes = { emptyMap() })
            val body = fs.readBytes("/uptime").decodeToString().trimEnd('\n')
            assertTrue(Regex("^\\d+\\.\\d{2} \\d+\\.\\d{2}$").matches(body), "got: '$body'")
        }

    @Test fun uptime_value_reflectsClockDelta() =
        runTest {
            val m =
                KashMachine(
                    fs = InMemoryFs(),
                    bootEpochSeconds = 1000L,
                    nowEpochSeconds = { 1050L },
                )
            bootProcess(m, pid = 1)
            val body = ProcFs(processes = { m.processTable }).readBytes("/uptime").decodeToString().trimEnd('\n')
            assertEquals("50.00 50.00", body)
        }

    @Test fun loadavg_format_matchesKernelShape() =
        runTest {
            val m = bootMachine()
            bootProcess(m, pid = 1)
            val body =
                ProcFs(processes = { m.processTable }).readBytes("/loadavg").decodeToString().trimEnd('\n')
            assertTrue(
                Regex("^0\\.00 0\\.00 0\\.00 \\d+/\\d+ \\d+$").matches(body),
                "got: '$body'",
            )
        }

    @Test fun loadavg_runningTotal_reflectsProcessTable() =
        runTest {
            val m = bootMachine()
            val a = bootProcess(m, pid = 1)
            val b = bootProcess(m, pid = 2)
            val c = bootProcess(m, pid = 3)
            a.state = ProcessState.RUNNING
            b.state = ProcessState.STOPPED
            c.state = ProcessState.ZOMBIE
            m.nextPid = 4
            val body =
                ProcFs(processes = { m.processTable }).readBytes("/loadavg").decodeToString().trimEnd('\n')
            val fields = body.split(' ')
            assertEquals("1/3", fields[3])
            assertEquals("3", fields[4]) // last-pid = nextPid - 1
        }

    @Test fun source_procPidFdN_dupsUnderlyingSource() =
        runTest {
            // `cat /proc/<pid>/fd/N` goes through fs.source, not openHandle.
            // This is what was broken — the FdEntry branch only existed in
            // openHandle.
            val m = bootMachine()
            val p = bootProcess(m, pid = 1)
            val orig =
                OpenFileDescription(
                    source = Buffer().apply { write("hello".encodeToByteArray()) }.asSuspendSource(),
                    accessMode = AccessMode.RDONLY,
                    path = "/somefile",
                    owning = false,
                )
            p.installFd(3, orig)
            val src = ProcFs(processes = { m.processTable }).source("/1/fd/3")
            val buf = Buffer()
            while (src.readAtMostTo(buf, 4096) != -1L) Unit
            assertEquals("hello", buf.readByteArray().decodeToString())
        }

    @Test fun source_procSelfFdN_resolvesAgainstOpener() =
        runTest {
            // `cat /proc/self/fd/N` from within the child must consult
            // the child's fd table (not the parent shell's). Mirror the
            // openHandle test on the source path.
            val m = bootMachine()
            val a = bootProcess(m, pid = 1)
            val b = bootProcess(m, pid = 42)
            val sa =
                OpenFileDescription(
                    source = Buffer().apply { write("A".encodeToByteArray()) }.asSuspendSource(),
                    accessMode = AccessMode.RDONLY,
                    path = "/A",
                    owning = false,
                )
            val sb =
                OpenFileDescription(
                    source = Buffer().apply { write("B".encodeToByteArray()) }.asSuspendSource(),
                    accessMode = AccessMode.RDONLY,
                    path = "/B",
                    owning = false,
                )
            a.installFd(3, sa)
            b.installFd(3, sb)
            val fs = ProcFs(processes = { m.processTable })

            val srcA = fs.source("/self/fd/3", opener = a)
            val srcB = fs.source("/self/fd/3", opener = b)
            val bufA = Buffer().also { srcA.readAtMostTo(it, 16) }
            val bufB = Buffer().also { srcB.readAtMostTo(it, 16) }
            assertEquals("A", bufA.readByteArray().decodeToString())
            assertEquals("B", bufB.readByteArray().decodeToString())
        }
}
