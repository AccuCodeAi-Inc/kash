package com.accucodeai.kash

import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.app.KashSnapshotStore
import com.accucodeai.kash.fs.FsLabel
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.fs.Mount
import com.accucodeai.kash.fs.MountedFileSystem
import com.accucodeai.kash.snapshot.Kind
import com.accucodeai.kash.snapshot.SnapshotFile
import com.accucodeai.kash.snapshot.SnapshotPayload
import com.accucodeai.kash.snapshot.snapshot
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JVM file store persists the same self-describing
 * [SnapshotFile] envelope as the web store, so `.kash/state.json` is
 * directly portable across hosts.
 */
class KashSnapshotStoreTest {
    private val tmpDir = Files.createTempDirectory("kash-snap-test")

    @AfterTest
    fun cleanup() {
        Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun bootMachine(): KashMachine {
        val mounted = MountedFileSystem(listOf(Mount("/", InMemoryFs(), FsLabel.USER, readOnly = false)))
        return KashMachine(fs = mounted, registry = CommandRegistry(emptyList()))
    }

    @Test
    fun saveThenLoadRoundTripsAsFullEnvelope() {
        val machine = bootMachine()
        machine.ensureInit()
        machine.fs.mkdirs("/work")
        machine.fs.sink("/work/marker", append = false, mode = 0b110_100_100).close()

        val store = KashSnapshotStore(tmpDir.resolve(".kash/state.json"))
        val saved = machine.snapshot()
        store.save(saved)

        val loaded = store.loadOrNull()
        assertTrue(loaded is SnapshotPayload.Full)
        assertEquals(saved, loaded.snapshot)
    }

    @Test
    fun onDiskFileIsTheSelfDescribingEnvelope() {
        val machine = bootMachine()
        machine.ensureInit()

        val path = tmpDir.resolve(".kash/state.json")
        KashSnapshotStore(path).save(machine.snapshot())

        val text = Files.readString(path)
        assertTrue(text.contains("\"${SnapshotFile.FILE_FORMAT}\""), "expected format tag in $text")
        assertTrue(text.contains("\"${Kind.FULL.name}\""))
    }

    @Test
    fun loadOfMissingFileIsNull() {
        val store = KashSnapshotStore(tmpDir.resolve(".kash/absent.json"))
        assertNull(store.loadOrNull())
    }
}
