package com.accucodeai.kash.tools.python3.graalpy

import com.accucodeai.kash.fs.InMemoryFs
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KashPolyglotFileSystemTest {
    @Test
    fun `sibling of passthrough dir is not host passthrough`(
        @TempDir tmp: Path,
    ) {
        // Real-disk layout:
        //   <tmp>/cache/             ← legitimate passthrough region
        //   <tmp>/cache_evil/secret  ← attacker target (sibling, shares string prefix)
        val cache = tmp.resolve("cache").also { Files.createDirectories(it) }
        val evilDir = tmp.resolve("cache_evil").also { Files.createDirectories(it) }
        val evilFile = evilDir.resolve("secret").also { Files.writeString(it, "host-secret") }
        assertTrue(Files.exists(evilFile), "test fixture sanity")

        val polyglotFs =
            KashPolyglotFileSystem(
                fs = InMemoryFs(),
                initialCwd = "/",
                hostPassthroughPrefixes = listOf(cache.toString()),
            )

        // Sandboxed code attempts to read the host-side sibling. The path
        // string starts with the same characters as the passthrough dir, but
        // crosses a directory boundary — must route to the in-memory FS and
        // raise NoSuchFile (instead of leaking host content).
        assertFailsWith<NoSuchFileException> {
            polyglotFs.newByteChannel(
                Paths.get(evilFile.toString()),
                setOf(StandardOpenOption.READ),
            )
        }
    }

    @Test
    fun `sibling write is blocked`(
        @TempDir tmp: Path,
    ) {
        val cache = tmp.resolve("graalpy").also { Files.createDirectories(it) }
        val polyglotFs =
            KashPolyglotFileSystem(
                fs = InMemoryFs(),
                initialCwd = "/",
                hostPassthroughPrefixes = listOf(cache.toString()),
            )
        // graalpy_evil/payload — attempt to write a sibling-confusion path.
        val attacker = tmp.resolve("graalpy_evil_payload")
        // Routes to InMemoryFs's sink, which auto-creates parents, succeeds in
        // the virtual FS, and never touches host disk. Verify by asserting
        // the host file was not created.
        polyglotFs
            .newByteChannel(
                Paths.get(attacker.toString()),
                setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE),
            ).use { ch ->
                ch.write(java.nio.ByteBuffer.wrap("pwned".toByteArray()))
            }
        val leaked = if (Files.exists(attacker)) Files.readAllBytes(attacker).decodeToString() else null
        assertTrue(leaked == null, "host file must not exist; got: $leaked")
    }

    @Test
    fun `path equal to passthrough dir routes to host`(
        @TempDir tmp: Path,
    ) {
        val cache = tmp.resolve("cache").also { Files.createDirectories(it) }
        val polyglotFs =
            KashPolyglotFileSystem(
                fs = InMemoryFs(),
                initialCwd = "/",
                hostPassthroughPrefixes = listOf(cache.toString()),
            )
        // Exact-match: the passthrough directory itself. readAttributes should
        // go to host and succeed (it's a real directory).
        val attrs =
            polyglotFs.readAttributes(
                Paths.get(cache.toString()),
                "basic:*",
            )
        assertEquals(true, attrs["isDirectory"])
    }

    @Test
    fun `descendant of passthrough dir routes to host`(
        @TempDir tmp: Path,
    ) {
        val cache = tmp.resolve("cache").also { Files.createDirectories(it) }
        Files.writeString(cache.resolve("stdlib.bin"), "real-stdlib-content")
        val polyglotFs =
            KashPolyglotFileSystem(
                fs = InMemoryFs(),
                initialCwd = "/",
                hostPassthroughPrefixes = listOf(cache.toString()),
            )
        val ch =
            polyglotFs.newByteChannel(
                Paths.get(cache.resolve("stdlib.bin").toString()),
                setOf(StandardOpenOption.READ),
            )
        val buf = java.nio.ByteBuffer.allocate(64)
        val n = ch.read(buf)
        ch.close()
        val content = String(buf.array(), 0, n)
        assertEquals("real-stdlib-content", content)
    }

    // ---- Vuln #1: dotted-path escape ----

    @Test
    fun `dotted path escape via passthrough does not reach host`(
        @TempDir tmp: Path,
    ) {
        // Real-disk layout — attacker stash *outside* the passthrough region.
        val cache = tmp.resolve("cache").also { Files.createDirectories(it) }
        val outside = tmp.resolve("outside").also { Files.createDirectories(it) }
        val secret = outside.resolve("secret").also { Files.writeString(it, "host-secret") }
        assertTrue(Files.exists(secret), "test fixture sanity")

        val polyglotFs =
            KashPolyglotFileSystem(
                fs = InMemoryFs(),
                initialCwd = "/",
                hostPassthroughPrefixes = listOf(cache.toString()),
            )

        // Naive `..` traversal: path string starts with the cache prefix but
        // resolves outside via `..`. Pre-fix, the kernel would resolve the
        // dots and `Files.newByteChannel` would open `<tmp>/outside/secret`.
        val escapePath = "$cache/../outside/secret"
        assertFailsWith<NoSuchFileException> {
            polyglotFs.newByteChannel(
                Paths.get(escapePath),
                setOf(StandardOpenOption.READ),
            )
        }
        // Confirm the secret on disk is untouched (we didn't accidentally
        // open it).
        assertEquals("host-secret", Files.readString(secret))
    }

    @Test
    fun `dotted-path write does not write to host`(
        @TempDir tmp: Path,
    ) {
        val cache = tmp.resolve("cache").also { Files.createDirectories(it) }
        val target = tmp.resolve("outside_payload")
        val polyglotFs =
            KashPolyglotFileSystem(
                fs = InMemoryFs(),
                initialCwd = "/",
                hostPassthroughPrefixes = listOf(cache.toString()),
            )
        val escapePath = "$cache/../outside_payload"
        // Should NOT touch the host. After normalization the path resolves
        // outside the passthrough region; routes to in-memory FS.
        polyglotFs
            .newByteChannel(
                Paths.get(escapePath),
                setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE),
            ).use { ch ->
                ch.write(java.nio.ByteBuffer.wrap("pwned".toByteArray()))
            }
        assertTrue(!Files.exists(target), "host file must not exist after escape attempt")
    }

    // ---- Vuln #2: symlink trapdoor inside cache region ----

    @Test
    fun `symlink in cache region pointing outside is rejected`(
        @TempDir tmp: Path,
    ) {
        val cache = tmp.resolve("cache").also { Files.createDirectories(it) }
        val outside = tmp.resolve("outside").also { Files.createDirectories(it) }
        val secret = outside.resolve("secret").also { Files.writeString(it, "host-secret") }

        // Plant a trapdoor symlink inside the cache region pointing outside.
        val trapdoor = cache.resolve("trapdoor")
        try {
            Files.createSymbolicLink(trapdoor, secret)
        } catch (_: UnsupportedOperationException) {
            return // host FS doesn't support symlinks — skip
        } catch (_: java.io.IOException) {
            return // privilege issue (Windows non-admin) — skip
        }

        val polyglotFs =
            KashPolyglotFileSystem(
                fs = InMemoryFs(),
                initialCwd = "/",
                hostPassthroughPrefixes = listOf(cache.toString()),
            )
        // Accessing the symlink (which is inside the passthrough region by
        // path) must NOT follow the link to the outside target. The exact
        // exception depends on the platform — on macOS the canonicalized
        // passthrough prefix routes the symlink path to the in-memory FS
        // (NoSuchFile); on Linux without symlinked /tmp it goes through the
        // explicit rejectSymlinkEscape path (SecurityException). Either way
        // the read of the host secret fails.
        val ex =
            assertFailsWith<Exception> {
                polyglotFs.newByteChannel(
                    Paths.get(trapdoor.toString()),
                    setOf(StandardOpenOption.READ),
                )
            }
        assertTrue(
            ex is SecurityException || ex is NoSuchFileException,
            "expected SecurityException or NoSuchFileException; got: ${ex::class.simpleName}: ${ex.message}",
        )
        // Sanity: secret on disk is unchanged.
        assertEquals("host-secret", Files.readString(secret))
    }

    @Test
    fun `trailing slash on passthrough dir does not change semantics`(
        @TempDir tmp: Path,
    ) {
        val cache = tmp.resolve("cache").also { Files.createDirectories(it) }
        Files.writeString(cache.resolve("x"), "x-content")
        // Same dir, but constructed with a trailing slash.
        val polyglotFs =
            KashPolyglotFileSystem(
                fs = InMemoryFs(),
                initialCwd = "/",
                hostPassthroughPrefixes = listOf("$cache/"),
            )
        val ch =
            polyglotFs.newByteChannel(
                Paths.get(cache.resolve("x").toString()),
                setOf(StandardOpenOption.READ),
            )
        val buf = java.nio.ByteBuffer.allocate(16)
        val n = ch.read(buf)
        ch.close()
        assertEquals("x-content", String(buf.array(), 0, n))
    }
}
