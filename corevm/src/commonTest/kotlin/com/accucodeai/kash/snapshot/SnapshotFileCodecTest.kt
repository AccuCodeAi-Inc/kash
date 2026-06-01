package com.accucodeai.kash.snapshot

import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.fs.FsLabel
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.fs.Mount
import com.accucodeai.kash.fs.MountedFileSystem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [SnapshotCodec] envelope is the single on-disk/on-storage form for
 * every host (JVM file store, browser localStorage, download/upload).
 * These tests pin the envelope round-trip for both payload kinds, the
 * format-tag sanity check, and — critically — that a payload encoded by
 * "one host" decodes identically (the JVM ⇄ web interop guarantee).
 */
class SnapshotFileCodecTest {
    private fun bootMachine(): KashMachine {
        val inMem = InMemoryFs()
        val mounted = MountedFileSystem(listOf(Mount("/", inMem, FsLabel.USER, readOnly = false)))
        return KashMachine(fs = mounted, registry = CommandRegistry(emptyList()))
    }

    @Test
    fun fullEnvelopeRoundTrips() {
        val machine = bootMachine()
        machine.ensureInit()
        machine.snapshotSlots[2] = buildJsonObject { put("hello", JsonPrimitive("world")) }

        val payload = SnapshotPayload.Full(machine.snapshot())
        val text = SnapshotCodec.encodeToFile("my-session", payload)
        val imported = SnapshotCodec.decodeFromFile(text)

        assertNotNull(imported)
        assertEquals("my-session", imported.name)
        val full = imported.payload as SnapshotPayload.Full
        assertEquals(payload.snapshot, full.snapshot)
    }

    @Test
    fun fsOnlyEnvelopeRoundTrips() {
        val machine = bootMachine()
        machine.ensureInit()
        machine.fs.mkdirs("/work")
        machine.fs.sink("/work/marker", append = false, mode = 0b110_100_100).close()

        val fsSnap = (machine.fs as MountedFileSystem).snapshot()
        val payload = SnapshotPayload.FsOnly(fsSnap)
        val text = SnapshotCodec.encodeToFile("fs", payload)
        val imported = SnapshotCodec.decodeFromFile(text)

        assertNotNull(imported)
        val fsOnly = imported.payload as SnapshotPayload.FsOnly
        assertEquals(fsSnap, fsOnly.snapshot)
    }

    @Test
    fun envelopeIsSelfDescribing() {
        val machine = bootMachine()
        machine.ensureInit()
        val text = SnapshotCodec.encodeToFile("x", SnapshotPayload.Full(machine.snapshot()))
        // format + version tags are always present, even though SnapshotJson
        // omits other defaults — they're what makes the file recognizable.
        assertTrue(text.contains("\"${SnapshotFile.FILE_FORMAT}\""))
        assertTrue(text.contains("\"kind\""))
    }

    @Test
    fun rejectsNonKashJson() {
        assertNull(SnapshotCodec.decodeFromFile("""{"format":"not-kash","kind":"FULL"}"""))
        assertNull(SnapshotCodec.decodeFromFile("not json at all"))
        // Right format tag but the payload field for the declared kind is absent.
        assertNull(SnapshotCodec.decodeFromFile("""{"format":"${SnapshotFile.FILE_FORMAT}","kind":"FULL"}"""))
    }

    @Test
    fun crossHostInteropIsByteIdentical() {
        // Whatever the JVM store writes, the web store must read and vice
        // versa: both go through SnapshotCodec, so encoding the same payload
        // twice is identical and decodes back to an equal payload.
        val machine = bootMachine()
        machine.ensureInit()
        val payload = SnapshotPayload.Full(machine.snapshot())

        val a = SnapshotCodec.encodeToFile("snapshot", payload)
        val b = SnapshotCodec.encodeToFile("snapshot", payload)
        assertEquals(a, b)

        val decoded = SnapshotCodec.decodeFromFile(a)?.payload as? SnapshotPayload.Full
        assertNotNull(decoded)
        assertEquals(payload.snapshot, decoded.snapshot)
    }
}
