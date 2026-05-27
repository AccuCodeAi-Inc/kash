package com.accucodeai.kash

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BashCommandTest {
    @Test fun bashCommandReflectsSourceText() =
        runTest {
            val fs = InMemoryFs()
            val kash =
                Kash(
                    fs = fs,
                    initialCwd = "/",
                    parentContext = coroutineContext,
                )
            val r =
                kash.exec(
                    "echo \$BASH_COMMAND",
                    ExecOptions(
                        env = mapOf("PATH" to "/usr/bin"),
                        cwd = "/",
                        replaceEnv = true,
                        mergeStderr = true,
                    ),
                )
            assertEquals("echo \$BASH_COMMAND\n", r.stdout)
        }

    @Test fun bashCommandReflectsCurrentNotPrevious() =
        runTest {
            val fs = InMemoryFs()
            val kash =
                Kash(
                    fs = fs,
                    initialCwd = "/",
                    parentContext = coroutineContext,
                )
            // Two commands on separate lines: the second prints the
            // freshly-set value (line-based src capture), confirming
            // BASH_COMMAND tracks the current command — not the previous.
            val r =
                kash.exec(
                    "echo hello\necho \$BASH_COMMAND",
                    ExecOptions(
                        env = mapOf("PATH" to "/usr/bin"),
                        cwd = "/",
                        replaceEnv = true,
                        mergeStderr = true,
                    ),
                )
            assertEquals("hello\necho \$BASH_COMMAND\n", r.stdout)
        }
}
