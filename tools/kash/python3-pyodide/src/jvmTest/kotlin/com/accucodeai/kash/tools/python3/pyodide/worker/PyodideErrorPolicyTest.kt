package com.accucodeai.kash.tools.python3.pyodide.worker

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the error-surfacing contract that the Pyodide worker path relies on,
 * without a browser. The regression this guards: a worker `run-result`
 * carries the uncaught Python traceback in `errorMessage`, and the main
 * thread used to drop it — so a throwing program produced a bare non-zero
 * exit with no diagnostics. [PyodideErrorPolicy.stderrTextForError] is what
 * the client now feeds to stderr.
 */
class PyodideErrorPolicyTest {
    // ----- the headline case: a throwing program's traceback reaches stderr -----

    @Test fun throwingProgramTracebackIsSurfaced() {
        // What Pyodide puts in run-result.errorMessage for an uncaught
        // `bytes.fromhex("abc")` (odd-length hex), trimmed to its tail.
        val traceback =
            "Traceback (most recent call last):\n" +
                "  File \"<exec>\", line 3, in <module>\n" +
                "ValueError: non-hexadecimal number found in fromhex() arg at position 3"

        val text = PyodideErrorPolicy.stderrTextForError(traceback)

        assertNotNull(text, "a real traceback must be surfaced to stderr, not dropped")
        assertTrue(text.contains("ValueError"), "the exception type must survive")
        assertTrue(text.endsWith("\n"), "stderr text ends with exactly one newline")
    }

    @Test fun bareValueErrorMessageIsSurfaced() {
        val text = PyodideErrorPolicy.stderrTextForError("ValueError: bad input")
        assertEquals("ValueError: bad input\n", text)
    }

    // ----- suppression: a clean sys.exit(N) is not noise on stderr -----

    @Test fun cleanIntegerSystemExitIsSuppressed() {
        // CPython prints nothing for `sys.exit(0)` / `sys.exit(3)`; we mustn't
        // dump a `SystemExit: N` line.
        assertNull(PyodideErrorPolicy.stderrTextForError("SystemExit: 0"))
        assertNull(PyodideErrorPolicy.stderrTextForError("SystemExit: 3"))
        assertNull(
            PyodideErrorPolicy.stderrTextForError(
                "Traceback (most recent call last):\n  ...\nSystemExit: 7",
            ),
        )
    }

    @Test fun stringSystemExitIsSurfaced() {
        // `sys.exit("boom")` carries a non-integer code — CPython prints it.
        val text = PyodideErrorPolicy.stderrTextForError("SystemExit: boom")
        assertEquals("SystemExit: boom\n", text)
    }

    // ----- no-error / empty cases -----

    @Test fun nullOrBlankYieldsNothing() {
        assertNull(PyodideErrorPolicy.stderrTextForError(null))
        assertNull(PyodideErrorPolicy.stderrTextForError(""))
        assertNull(PyodideErrorPolicy.stderrTextForError("   \n  "))
    }

    @Test fun trailingWhitespaceIsNormalizedToSingleNewline() {
        assertEquals(
            "ValueError: x\n",
            PyodideErrorPolicy.stderrTextForError("ValueError: x\n\n  "),
        )
    }

    // ----- extractSystemExitCode parsing (drives both exit code + suppression) -----

    @Test fun extractsIntegerExitCode() {
        assertEquals(0, PyodideErrorPolicy.extractSystemExitCode("SystemExit: 0"))
        assertEquals(3, PyodideErrorPolicy.extractSystemExitCode("SystemExit: 3"))
    }

    @Test fun extractsFromTailOfMultilineTraceback() {
        val msg = "Traceback (most recent call last):\n  ...\nSystemExit: 42"
        assertEquals(42, PyodideErrorPolicy.extractSystemExitCode(msg))
    }

    @Test fun extractReturnsNullForNonSystemExit() {
        assertNull(PyodideErrorPolicy.extractSystemExitCode(null))
        assertNull(PyodideErrorPolicy.extractSystemExitCode("ValueError: nope"))
        // SystemExit with a non-integer payload is not an integer exit code.
        assertNull(PyodideErrorPolicy.extractSystemExitCode("SystemExit: boom"))
    }
}
