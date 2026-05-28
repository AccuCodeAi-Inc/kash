package com.accucodeai.kash

import com.accucodeai.kash.fs.AccessKind
import com.accucodeai.kash.fs.reads
import com.accucodeai.kash.fs.writes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ergonomic "yolo" surface: `kash.exec(script, traceAccess = true)` and
 * read [com.accucodeai.kash.api.ExecResult.touched]. Capture is synchronous
 * (the bus observer runs inline on each access), so by the time `exec`
 * returns the list is complete — no polling.
 */
class FileAccessTrackingTest {
    @Test
    fun redirectionWriteAndCommandReadShowUpInTouched() =
        runBlocking {
            val r = Kash().exec("echo hi > /a.txt; cat /a.txt", traceAccess = true)
            assertEquals(0, r.exitCode, r.stderr)
            assertTrue("/a.txt" in r.touched.writes(), "expected /a.txt write; got ${r.touched}")
            assertTrue("/a.txt" in r.touched.reads(), "expected /a.txt read; got ${r.touched}")
        }

    @Test
    fun createThenDeleteAreBothRecorded() =
        runBlocking {
            val r = Kash().exec("printf x > /b.txt; rm /b.txt", traceAccess = true)
            assertEquals(0, r.exitCode, r.stderr)
            val kinds = r.touched.filter { it.path == "/b.txt" }.map { it.kind }
            assertTrue(AccessKind.CREATE in kinds, "expected CREATE; got ${r.touched}")
            assertTrue(AccessKind.DELETE in kinds, "expected DELETE; got ${r.touched}")
        }

    @Test
    fun toolReadCarriesANonZeroScope() =
        runBlocking {
            // grep is a TOOL — forks and gets its own scope id.
            val r = Kash().exec("printf 'a\\nfoo\\n' > /c.txt; grep foo /c.txt", traceAccess = true)
            assertEquals(0, r.exitCode, r.stderr)
            val read = r.touched.firstOrNull { it.path == "/c.txt" && it.kind == AccessKind.READ }
            assertTrue(read != null, "expected a READ of /c.txt; got ${r.touched}")
            assertTrue(read.scopeId != 0L, "tool read should carry a non-zero scope; got $read")
        }

    @Test
    fun probesDoNotShowAsReads() =
        runBlocking {
            val r = Kash().exec("printf x > /d.txt; [ -e /d.txt ] && ls / > /dev/null", traceAccess = true)
            assertEquals(0, r.exitCode, r.stderr)
            assertTrue(
                "/d.txt" !in r.touched.reads(),
                "stat/test/-e must not record a READ; got ${r.touched}",
            )
        }

    @Test
    fun tracingOffYieldsEmptyTouched() =
        runBlocking {
            // Default (traceAccess = false): no capture, no allocation.
            val r = Kash().exec("echo hi > /e.txt; cat /e.txt")
            assertEquals(0, r.exitCode, r.stderr)
            assertEquals(emptyList(), r.touched, "touched must be empty when tracing is off")
        }

    @Test
    fun executingAScriptFileRecordsReadingIt() =
        runBlocking {
            // Regression guard for the binfmt hole: the shell-script fallback
            // used to read the script body via raw machine.fs (invisible).
            // It now reads through the caller's facade, so running a script
            // file shows up as a READ of that file.
            val kash = Kash()
            kash.exec("printf 'echo from-script\\n' > /s.sh; chmod +x /s.sh")
            val r = kash.exec("/s.sh", traceAccess = true)
            assertEquals(0, r.exitCode, r.stderr)
            assertTrue("from-script" in r.stdout, "script should run; stdout=${r.stdout}")
            assertTrue(
                "/s.sh" in r.touched.reads(),
                "executing /s.sh must record reading it; got ${r.touched}",
            )
        }
}
