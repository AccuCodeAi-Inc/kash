package com.accucodeai.kash.tools.find

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.UtilityRunner
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * In-memory FS that models a small tree:
 *   /a/
 *     foo.txt
 *     bar.log
 *     sub/
 *       baz.txt
 *   /b.txt
 */
private class TreeFs(
    private val dirs: Set<String>,
    private val files: Map<String, ByteArray>,
    private val children: Map<String, List<String>>,
) : FileSystem {
    override fun exists(path: String): Boolean = path in dirs || path in files

    override fun isDirectory(path: String) = path in dirs

    override fun list(path: String): List<String> = children[path] ?: emptyList()

    override fun source(path: String): SuspendSource {
        val b = Buffer()
        b.write(files[path]!!)
        return b.asSuspendSource()
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = Buffer().asSuspendSink()

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {}

    override fun remove(path: String) {}

    override suspend fun readBytes(path: String) = files[path] ?: ByteArray(0)

    override suspend fun writeBytes(
        path: String,
        bytes: ByteArray,
        mode: Int,
    ) {}
}

private fun smallTree(): FileSystem =
    TreeFs(
        dirs = setOf("/a", "/a/sub"),
        files =
            mapOf(
                "/a/foo.txt" to ByteArray(0),
                "/a/bar.log" to ByteArray(0),
                "/a/sub/baz.txt" to ByteArray(0),
                "/b.txt" to ByteArray(0),
            ),
        children =
            mapOf(
                "/a" to listOf("foo.txt", "bar.log", "sub"),
                "/a/sub" to listOf("baz.txt"),
            ),
    )

class FindCommandTest {
    private class RecordingRunner : UtilityRunner {
        val calls = mutableListOf<Pair<String, List<String>>>()

        override suspend fun run(
            name: String,
            args: List<String>,
            stdin: SuspendSource,
            stdout: SuspendSink,
            stderr: SuspendSink,
        ): Int {
            calls.add(name to args)
            stdout.writeUtf8("ran ${args.joinToString(" ")}\n")
            return 0
        }
    }

    private fun ctx(
        fs: FileSystem,
        runner: UtilityRunner? = null,
    ): Triple<CommandContext, Buffer, Buffer> {
        val out = Buffer()
        val err = Buffer()
        return Triple(
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = "/",
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
                utilityRunner = runner,
            ),
            out,
            err,
        )
    }

    @Test fun default_action_is_print_walks_recursively() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            val rc = FindCommand().run(listOf("/a"), c)
            assertEquals(0, rc.exitCode)
            assertEquals(
                listOf("/a", "/a/foo.txt", "/a/bar.log", "/a/sub", "/a/sub/baz.txt"),
                out.readString().trimEnd().lines(),
            )
        }

    @Test fun name_filters_by_basename_glob() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(listOf("/a", "-name", "*.txt"), c)
            assertEquals(
                listOf("/a/foo.txt", "/a/sub/baz.txt"),
                out.readString().trimEnd().lines(),
            )
        }

    @Test fun type_f_picks_only_files() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(listOf("/a", "-type", "f"), c)
            assertEquals(
                listOf("/a/foo.txt", "/a/bar.log", "/a/sub/baz.txt"),
                out.readString().trimEnd().lines(),
            )
        }

    @Test fun type_d_picks_only_directories() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(listOf("/a", "-type", "d"), c)
            assertEquals(
                listOf("/a", "/a/sub"),
                out.readString().trimEnd().lines(),
            )
        }

    @Test fun maxdepth_limits_traversal() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(listOf("/a", "-maxdepth", "1"), c)
            assertEquals(
                listOf("/a", "/a/foo.txt", "/a/bar.log", "/a/sub"),
                out.readString().trimEnd().lines(),
            )
        }

    @Test fun mindepth_skips_shallow_matches() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(listOf("/a", "-mindepth", "1"), c)
            assertEquals(
                listOf("/a/foo.txt", "/a/bar.log", "/a/sub", "/a/sub/baz.txt"),
                out.readString().trimEnd().lines(),
            )
        }

    @Test fun exec_semi_invokes_utility_per_match() =
        runTest {
            val r = RecordingRunner()
            val (c, _, _) = ctx(smallTree(), r)
            FindCommand().run(listOf("/a", "-type", "f", "-exec", "echo", "{}", ";"), c)
            assertEquals(
                listOf(
                    "echo" to listOf("/a/foo.txt"),
                    "echo" to listOf("/a/bar.log"),
                    "echo" to listOf("/a/sub/baz.txt"),
                ),
                r.calls,
            )
        }

    @Test fun exec_plus_batches_into_one_call() =
        runTest {
            val r = RecordingRunner()
            val (c, _, _) = ctx(smallTree(), r)
            FindCommand().run(listOf("/a", "-type", "f", "-exec", "echo", "{}", "+"), c)
            assertEquals(1, r.calls.size)
            assertEquals(
                "echo" to listOf("/a/foo.txt", "/a/bar.log", "/a/sub/baz.txt"),
                r.calls[0],
            )
        }

    @Test fun missing_root_returns_nonzero() =
        runTest {
            val (c, _, err) = ctx(smallTree())
            val rc = FindCommand().run(listOf("/nope"), c)
            assertEquals(1, rc.exitCode)
            assertEquals(true, err.readString().contains("No such"))
        }

    @Test fun print0_writes_nul_separator() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(listOf("/a", "-type", "f", "-print0"), c)
            val bytes = out.readByteArray()
            // Each match terminates with a NUL, never a space.
            assertEquals(true, bytes.any { it == 0.toByte() })
            assertEquals(false, bytes.any { it == ' '.code.toByte() })
        }

    @Test fun iname_matches_case_insensitively() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(listOf("/a", "-iname", "*.TXT"), c)
            assertEquals(
                listOf("/a/foo.txt", "/a/sub/baz.txt"),
                out.readString().trimEnd().lines(),
            )
        }

    @Test fun or_unions_matches() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(listOf("/a", "-name", "*.log", "-o", "-name", "*.txt"), c)
            val got =
                out
                    .readString()
                    .trimEnd()
                    .lines()
                    .sorted()
            assertEquals(listOf("/a/bar.log", "/a/foo.txt", "/a/sub/baz.txt"), got)
        }

    @Test fun grouping_with_parens_alters_precedence() =
        runTest {
            // ( -name *.log -o -name *.txt ) -a -type f
            // Without grouping the -a would bind tighter and *.log would slip
            // through regardless of -type.
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(
                listOf("/a", "(", "-name", "*.log", "-o", "-name", "*.txt", ")", "-a", "-type", "f"),
                c,
            )
            val got =
                out
                    .readString()
                    .trimEnd()
                    .lines()
                    .sorted()
            assertEquals(listOf("/a/bar.log", "/a/foo.txt", "/a/sub/baz.txt"), got)
        }

    @Test fun not_negates_following_predicate() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(listOf("/a", "-type", "f", "!", "-name", "*.log"), c)
            val got =
                out
                    .readString()
                    .trimEnd()
                    .lines()
                    .sorted()
            assertEquals(listOf("/a/foo.txt", "/a/sub/baz.txt"), got)
        }

    @Test fun not_with_group() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(
                listOf("/a", "-type", "f", "-not", "(", "-name", "*.log", "-o", "-name", "baz.*", ")"),
                c,
            )
            assertEquals(listOf("/a/foo.txt"), out.readString().trimEnd().lines())
        }

    @Test fun unknown_primary_returns_2() =
        runTest {
            val (c, _, err) = ctx(smallTree())
            val rc = FindCommand().run(listOf("/a", "-bogus"), c)
            assertEquals(2, rc.exitCode)
            assertEquals(true, err.readString().contains("unknown primary"))
        }

    @Test fun unbalanced_group_returns_2() =
        runTest {
            val (c, _, err) = ctx(smallTree())
            val rc = FindCommand().run(listOf("/a", "(", "-name", "x"), c)
            assertEquals(2, rc.exitCode)
            assertEquals(true, err.readString().contains("missing ')'"))
        }

    @Test fun prune_skips_a_subtree() =
        runTest {
            val (c, out, _) = ctx(smallTree())
            // Match `sub` and prune it; the `-o -print` action prints the rest.
            FindCommand().run(
                listOf("/a", "-name", "sub", "-prune", "-o", "-print"),
                c,
            )
            val lines = out.readString().trimEnd().lines()
            // `/a/sub/baz.txt` must NOT appear — the subtree was pruned.
            assertEquals(false, "/a/sub/baz.txt" in lines)
            assertEquals(true, "/a/foo.txt" in lines)
            assertEquals(true, "/a/bar.log" in lines)
        }

    @Test fun empty_matches_empty_files() =
        runTest {
            // smallTree() has all files as ByteArray(0) — every file is empty.
            val (c, out, _) = ctx(smallTree())
            FindCommand().run(listOf("/a", "-type", "f", "-empty"), c)
            val lines =
                out
                    .readString()
                    .trimEnd()
                    .lines()
                    .sorted()
            assertEquals(listOf("/a/bar.log", "/a/foo.txt", "/a/sub/baz.txt"), lines)
        }

    @Test fun empty_matches_empty_directories() =
        runTest {
            // /a/sub has one entry — not empty. /a has entries — not empty.
            // Add a third dir with no children.
            val fs =
                TreeFs(
                    dirs = setOf("/a", "/a/sub", "/a/empty"),
                    files =
                        mapOf(
                            "/a/foo.txt" to ByteArray(0),
                            "/a/sub/baz.txt" to ByteArray(0),
                        ),
                    children =
                        mapOf(
                            "/a" to listOf("foo.txt", "sub", "empty"),
                            "/a/sub" to listOf("baz.txt"),
                            "/a/empty" to emptyList(),
                        ),
                )
            val (c, out, _) = ctx(fs)
            FindCommand().run(listOf("/a", "-type", "d", "-empty"), c)
            assertEquals("/a/empty", out.readString().trimEnd())
        }

    @Test fun size_matches_exact_byte_count_with_c_suffix() =
        runTest {
            val fs =
                TreeFs(
                    dirs = setOf("/a"),
                    files =
                        mapOf(
                            "/a/small" to ByteArray(3),
                            "/a/big" to ByteArray(7),
                        ),
                    children = mapOf("/a" to listOf("small", "big")),
                )
            val (c, out, _) = ctx(fs)
            FindCommand().run(listOf("/a", "-type", "f", "-size", "3c"), c)
            assertEquals("/a/small", out.readString().trimEnd())
        }

    @Test fun size_greater_than_with_plus_prefix() =
        runTest {
            val fs =
                TreeFs(
                    dirs = setOf("/a"),
                    files =
                        mapOf(
                            "/a/tiny" to ByteArray(1),
                            "/a/large" to ByteArray(100),
                        ),
                    children = mapOf("/a" to listOf("tiny", "large")),
                )
            val (c, out, _) = ctx(fs)
            FindCommand().run(listOf("/a", "-type", "f", "-size", "+10c"), c)
            assertEquals("/a/large", out.readString().trimEnd())
        }

    @Test fun size_bad_suffix_returns_2() =
        runTest {
            val (c, _, err) = ctx(smallTree())
            val rc = FindCommand().run(listOf("/a", "-size", "1Z"), c)
            assertEquals(2, rc.exitCode)
            assertEquals(true, err.readString().contains("-size"))
        }

    @Test fun mtime_parses_with_sign_prefix() =
        runTest {
            // Without clock injection we can't assert exact match, but the
            // expression should at least parse and run without error.
            val (c, _, err) = ctx(smallTree())
            val rc = FindCommand().run(listOf("/a", "-mtime", "+0"), c)
            assertEquals(0, rc.exitCode, "stderr=${err.readString()}")
        }

    @Test fun mtime_bad_argument_returns_2() =
        runTest {
            val (c, _, err) = ctx(smallTree())
            val rc = FindCommand().run(listOf("/a", "-mtime", "abc"), c)
            assertEquals(2, rc.exitCode)
            assertEquals(true, err.readString().contains("-mtime"))
        }
}
