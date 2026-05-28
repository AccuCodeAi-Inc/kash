package com.accucodeai.kash.tools.python3.pyodide.worker

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drift guard for the FS-bridge wire protocol.
 *
 * The protocol constants (control-SAB slot indices, op codes, the WASI
 * status table, the stat-blob layout, and open(2) flags) are declared twice:
 * once in [SabFsProtocol] (Kotlin, the source of truth) and once as plain
 * `const`s in `pyodide-worker.js` — the JS side can't import Kotlin, so the
 * duplication is unavoidable. Five "keep in sync" comments are the only thing
 * stopping a silent edit on one side from producing a wrong-errno or
 * misread-stat bug that would otherwise surface only in the slow, real-Pyodide
 * e2e suite (and even there only on the affected op).
 *
 * This test parses the `const NAME = VALUE;` lines out of `pyodide-worker.js`
 * and asserts each one equals the corresponding [SabFsProtocol] value. Because
 * the *expected* side reads straight from the Kotlin constants, drift on
 * EITHER side trips it — change a value in Kotlin and the JS no longer matches;
 * change it in JS and it no longer matches Kotlin.
 *
 * Lives in jvmTest (not wasmJsTest) because the JVM can read the worker script
 * off disk directly; a browser test would have to fetch + parse it over HTTP.
 */
class SabFsProtocolParityTest {
    @Test fun jsConstantsMatchKotlinProtocol() {
        val js = parseJsConstants(workerScriptText())

        // JS const name -> Kotlin source-of-truth value.
        val expected: Map<String, Int> =
            buildMap {
                put("FS_SLOT_REQ_SEQ", SabFsProtocol.SLOT_REQ_SEQ)
                put("FS_SLOT_RESP_SEQ", SabFsProtocol.SLOT_RESP_SEQ)
                put("FS_SLOT_OP", SabFsProtocol.SLOT_OP)
                put("FS_SLOT_STATUS", SabFsProtocol.SLOT_STATUS)
                put("FS_SLOT_PAYLOAD_LEN", SabFsProtocol.SLOT_PAYLOAD_LEN)
                put("FS_SLOT_ARG0", SabFsProtocol.SLOT_ARG0)
                put("FS_SLOT_ARG1", SabFsProtocol.SLOT_ARG1)
                put("FS_SLOT_ARG2", SabFsProtocol.SLOT_ARG2)
                put("FS_SLOT_ARG3", SabFsProtocol.SLOT_ARG3)

                put("OP_NOP", SabFsProtocol.Op.NOP)
                put("OP_STAT", SabFsProtocol.Op.STAT)
                put("OP_LIST", SabFsProtocol.Op.LIST)
                put("OP_OPEN", SabFsProtocol.Op.OPEN)
                put("OP_READ", SabFsProtocol.Op.READ)
                put("OP_WRITE", SabFsProtocol.Op.WRITE)
                put("OP_CLOSE", SabFsProtocol.Op.CLOSE)
                put("OP_MKDIR", SabFsProtocol.Op.MKDIR)
                put("OP_RMDIR", SabFsProtocol.Op.RMDIR)
                put("OP_UNLINK", SabFsProtocol.Op.UNLINK)
                put("OP_RENAME", SabFsProtocol.Op.RENAME)

                put("STATUS_OK", SabFsProtocol.Status.OK)
                put("STATUS_EACCES", SabFsProtocol.Status.EACCES)
                put("STATUS_EBADF", SabFsProtocol.Status.EBADF)
                put("STATUS_EEXIST", SabFsProtocol.Status.EEXIST)
                put("STATUS_EINVAL", SabFsProtocol.Status.EINVAL)
                put("STATUS_EIO", SabFsProtocol.Status.EIO)
                put("STATUS_EISDIR", SabFsProtocol.Status.EISDIR)
                put("STATUS_ENOENT", SabFsProtocol.Status.ENOENT)
                put("STATUS_ENOSPC", SabFsProtocol.Status.ENOSPC)
                put("STATUS_ENOSYS", SabFsProtocol.Status.ENOSYS)
                put("STATUS_ENOTDIR", SabFsProtocol.Status.ENOTDIR)

                put("STAT_OFF_SIZE", SabFsProtocol.Stat.OFF_SIZE)
                put("STAT_OFF_MTIME", SabFsProtocol.Stat.OFF_MTIME)
                put("STAT_OFF_MODE", SabFsProtocol.Stat.OFF_MODE)
                put("STAT_OFF_TYPE", SabFsProtocol.Stat.OFF_TYPE)
                put("STAT_SIZE", SabFsProtocol.Stat.SIZE)

                put("TYPE_REGULAR", SabFsProtocol.Type.REGULAR)
                put("TYPE_DIRECTORY", SabFsProtocol.Type.DIRECTORY)

                put("O_RDONLY", SabFsProtocol.Open.O_RDONLY)
                put("O_WRONLY", SabFsProtocol.Open.O_WRONLY)
                put("O_RDWR", SabFsProtocol.Open.O_RDWR)
                put("O_CREAT", SabFsProtocol.Open.O_CREAT)
                put("O_TRUNC", SabFsProtocol.Open.O_TRUNC)
                put("O_APPEND", SabFsProtocol.Open.O_APPEND)
            }

        for ((name, kotlinValue) in expected) {
            assertTrue(
                js.containsKey(name),
                "pyodide-worker.js is missing `const $name` — Kotlin SabFsProtocol expects it = $kotlinValue",
            )
            assertEquals(
                kotlinValue,
                js[name],
                "Protocol drift: pyodide-worker.js `$name` = ${js[name]} but Kotlin SabFsProtocol = $kotlinValue",
            )
        }
    }

    private fun parseJsConstants(text: String): Map<String, Int> {
        // Matches e.g. `const OP_STAT = 1;` and `const O_CREAT = 0x40;`,
        // including negative decimals (`const STATUS_ENOENT = -44;`).
        val re = Regex("""const\s+([A-Z][A-Z0-9_]*)\s*=\s*(-?(?:0[xX][0-9a-fA-F]+|\d+))\s*;""")
        val out = HashMap<String, Int>()
        for (m in re.findAll(text)) {
            val name = m.groupValues[1]
            val raw = m.groupValues[2]
            val value =
                if (raw.startsWith("-0x") || raw.startsWith("-0X")) {
                    -raw.substring(3).toInt(16)
                } else if (raw.startsWith("0x") || raw.startsWith("0X")) {
                    raw.substring(2).toInt(16)
                } else {
                    raw.toInt()
                }
            out[name] = value
        }
        return out
    }

    private fun workerScriptText(): String {
        // Gradle runs the test with the module dir as the working directory.
        // Locate the worker script relative to it, walking up a few parents
        // as a fallback so the test isn't brittle to how it's launched.
        val rel = "src/wasmJsMain/resources/pyodide-worker.js"
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, rel)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "tools/kash/python3-pyodide/$rel")
            if (nested.isFile) return nested.readText()
            dir = dir?.parentFile
        }
        fail("could not locate pyodide-worker.js (looked for `$rel` from ${System.getProperty("user.dir")})")
    }
}
