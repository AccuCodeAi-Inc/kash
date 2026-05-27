package com.accucodeai.kash.fs

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import kotlinx.coroutines.test.runTest
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSpec(
    override val name: String,
    override val kind: CommandKind = CommandKind.BUILTIN,
) : CommandSpec,
    Command {
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult = CommandResult()
}

class ToolsFsTest {
    private fun fs(vararg specs: CommandSpec): ToolsFs {
        val map = specs.associateBy { it.name }
        return ToolsFs(lookup = { map[it] }, names = { map.keys })
    }

    @Test fun root_is_directory() =
        runTest {
            val f = fs(FakeSpec("grep"))
            assertTrue(f.exists("/"))
            assertTrue(f.isDirectory("/"))
        }

    @Test fun lookup_returns_spec() =
        runTest {
            val f = fs(FakeSpec("grep"))
            assertTrue(f.exists("/grep"))
            assertFalse(f.isDirectory("/grep"))
            assertEquals("grep", f.commandSpec("/grep")?.name)
        }

    @Test fun missing_name_does_not_exist_and_has_no_spec() =
        runTest {
            val f = fs(FakeSpec("grep"))
            assertFalse(f.exists("/nope"))
            assertNull(f.commandSpec("/nope"))
        }

    @Test fun list_returns_sorted_names() =
        runTest {
            val f = fs(FakeSpec("sed"), FakeSpec("grep"), FakeSpec("awk"))
            assertEquals(listOf("awk", "grep", "sed"), f.list("/"))
        }

    @Test fun list_on_non_root_throws() =
        runTest {
            val f = fs(FakeSpec("grep"))
            assertFails { f.list("/grep") }
        }

    @Test fun source_returns_empty_body() =
        runTest {
            val f = fs(FakeSpec("grep"))
            val src = f.source("/grep")
            val sink = kotlinx.io.Buffer()
            while (true) {
                val n = src.readAtMostTo(sink, 1024L)
                if (n == -1L) break
            }
            assertEquals("", sink.readString())
        }

    @Test fun source_throws_for_missing() =
        runTest {
            val f = fs(FakeSpec("grep"))
            assertFails { f.source("/nope") }
        }

    @Test fun stat_reports_regular_file_mode_0o755() =
        runTest {
            val f = fs(FakeSpec("grep"))
            val s = f.stat("/grep")
            assertEquals(FileType.REGULAR, s.type)
            assertEquals(0b111_101_101, s.mode)
            assertEquals(0L, s.size)
        }

    @Test fun stat_reports_directory_for_root() =
        runTest {
            val f = fs(FakeSpec("grep"))
            assertEquals(FileType.DIRECTORY, f.stat("/").type)
        }

    @Test fun writes_are_rejected() =
        runTest {
            val f = fs(FakeSpec("grep"))
            assertFails { f.sink("/grep", append = false) }
            assertFails { f.remove("/grep") }
            assertFails { f.mkdirs("/new") }
            assertFails { f.chmod("/grep", 0) }
            assertFails { f.setMtime("/grep", 0L) }
        }

    @Test fun multi_segment_paths_are_rejected_as_command_spec() =
        runTest {
            // /sub/foo isn't a flat ToolsFs entry; commandSpec must return null
            // (defense in depth — otherwise a name like "/sub/foo" registered in
            // the underlying lookup would shadow an unrelated FS path).
            val f =
                ToolsFs(lookup = {
                    "sub/foo"
                        .takeIf { _ ->
                            it == "sub/foo"
                        }?.let { FakeSpec(it) }
                }, names = { emptySet() })
            assertNull(f.commandSpec("/sub/foo"))
        }

    @Test fun lookup_is_called_lazily() =
        runTest {
            var calls = 0
            val f =
                ToolsFs(
                    lookup = {
                        calls++
                        if (it == "grep") FakeSpec("grep") else null
                    },
                    names = { setOf("grep") },
                )
            assertEquals(0, calls)
            assertNotNull(f.commandSpec("/grep"))
            assertTrue(calls > 0)
        }
}
