package com.accucodeai.kash.tools.python3.graalpy

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.python3.Python3Command
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reproduces the failure observed in an AI-agent session:
 *
 *   write_file(data.json, "{ ... complex JSON ... }")
 *   write_file(update_json.py, "import json\nwith open('data.json','r') as f: ...")
 *   shell_exec("python3 update_json.py")
 *
 * GraalPy aborts with:
 *
 *   java.lang.NullPointerException: Cannot invoke
 *     "java.lang.Integer.intValue()" because the return value of
 *     "com.oracle.truffle.api.TruffleFile$Attributes.get(...)" is null
 *
 * Root cause: `KashPolyglotFileSystem.readAttributes` returns only the
 * `BasicFileAttributeView` keys (`isRegularFile`, `size`, mtime, etc.).
 * Under sandbox=TRUSTED we set `python.PosixModuleBackend = "java"`, which
 * makes GraalPy's `open()` read POSIX-shaped attributes (mode/uid/gid)
 * through `TruffleFile.Attributes`. Missing keys come back as `null`,
 * Truffle unboxes them to `int`, and the engine NPEs before any user code
 * runs. Existing tests pass because they only WRITE through Python — read
 * of an existing kash-FS file via `open(..., 'r')` is the uncovered path.
 *
 * The agent's `awk` retry worked because awk runs as a kash-native command
 * and never touches the Truffle FS adapter.
 */
class GraalPyAgentRepro {
    @Test fun `read-then-overwrite-same-file via python3 script`() =
        runBlocking {
            val fs = InMemoryFs()
            val cwd = "/home/user"
            // Mirror the agent's write_file outputs.
            fs.sink("$cwd/data.json", append = false).also { s ->
                try {
                    s.writeUtf8(
                        """
                        {"company":{"projects":[{"name":"Project Alpha"},{"name":"Project Beta"}]}}
                        """.trimIndent() + "\n",
                    )
                } finally {
                    s.close()
                }
            }
            fs.sink("$cwd/update_json.py", append = false).also { s ->
                try {
                    s.writeUtf8(
                        """
                        import json
                        with open('data.json', 'r') as f:
                            data = json.load(f)
                        for project in data['company']['projects']:
                            if project['name'] == 'Project Beta':
                                project['name'] = 'Project Gamma'
                        with open('data.json', 'w') as f:
                            json.dump(data, f, indent=2)
                        print("Successfully updated Project Beta to Project Gamma.")
                        """.trimIndent() + "\n",
                    )
                } finally {
                    s.close()
                }
            }

            val out = Buffer()
            val err = Buffer()
            val stdin = Buffer()
            val ctx =
                bareCommandContext(
                    fs = fs,
                    env = mutableMapOf(),
                    cwd = cwd,
                    stdin = stdin.asSuspendSource(),
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                )
            val rc =
                Python3Command(GraalPyEngine())
                    .run(listOf("update_json.py"), ctx)
                    .exitCode
            val stdoutStr = out.readString()
            val stderrStr = err.readString()

            assertEquals(
                0,
                rc,
                "expected exit 0; stdout=<$stdoutStr> stderr=<$stderrStr>",
            )
            assertEquals(
                "Successfully updated Project Beta to Project Gamma.\n",
                stdoutStr,
            )
            // And the file in the kash virtual FS should reflect the change.
            val after = fs.readBytes("$cwd/data.json").decodeToString()
            kotlin.test.assertTrue(
                after.contains("Project Gamma") && !after.contains("Project Beta"),
                "data.json should have Beta -> Gamma. after=<$after>",
            )
        }
}
