package com.accucodeai.kash.tools.git.http

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire-format tests for `--depth=N` (shallow) and `--filter=<spec>`
 * (partial clone) request bodies, plus the parsing of the
 * `shallow`/`unshallow` response section. We don't drive a real HTTP
 * round-trip here — that's the host-pair test's job — but every byte
 * sent/received must match what `git http-backend` and clients like
 * `git fetch --depth=1` actually use.
 */
class ShallowAndFilterTest {
    @Test fun fullScopeOmitsShallowAndFilterAdvertisements() {
        val body = buildUploadPackBody(listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).decodeToString()
        // Don't allow `shallow` as a cap echo (spec: server-only). Look
        // for it as a word-bounded token rather than substring — the
        // body always contains `side-band-64k` and `no-progress`.
        assertFalse(
            body.contains(" shallow ") || body.contains(" shallow\n"),
            "full scope must not echo 'shallow' cap: <$body>",
        )
        assertFalse(body.contains("deepen"), "full scope must not send deepen line: <$body>")
        assertFalse(
            body.contains(" filter ") || body.contains("filter blob") || body.contains("filter tree"),
            "full scope must not advertise filter: <$body>",
        )
        // Sanity: the new always-on caps must be there.
        assertContains(body, "side-band-64k", message = "missing side-band-64k cap: <$body>")
        assertContains(body, "no-progress", message = "missing no-progress cap: <$body>")
    }

    @Test fun shallowScopeSendsDeepenLineWithoutEchoingShallowCap() {
        val scope = FetchScope(depth = 1)
        val body = buildUploadPackBody(listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), scope).decodeToString()
        // Spec: `shallow` is a SERVER-advertised cap. Client signals
        // shallow by sending `deepen N`, not by echoing the cap.
        assertFalse(
            body.contains(" shallow ") || body.contains(" shallow\n"),
            "must not echo 'shallow' capability — it's server-advertised only: <$body>",
        )
        assertContains(body, "deepen 1\n", message = "missing deepen pkt-line: <$body>")
        assertFalse(body.contains("filter"), "filter must not appear when scope.filter == null: <$body>")
    }

    @Test fun filterScopeAddsCapabilityAndFilterLine() {
        val scope = FetchScope(filter = "blob:none")
        val body = buildUploadPackBody(listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), scope).decodeToString()
        assertContains(body, " filter ", message = "missing filter capability: <$body>")
        assertContains(body, "filter blob:none\n", message = "missing filter pkt-line: <$body>")
        assertFalse(body.contains("shallow"), "shallow must not appear when depth == null: <$body>")
    }

    @Test fun shallowAndFilterCanCombine() {
        val scope = FetchScope(depth = 50, filter = "blob:limit=1m")
        val body = buildUploadPackBody(listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), scope).decodeToString()
        assertContains(body, " filter ")
        assertContains(body, "deepen 50\n")
        assertContains(body, "filter blob:limit=1m\n")
        // Confirm no spurious `shallow` cap echo.
        assertFalse(body.contains(" shallow "), "must not echo shallow cap: <$body>")
    }

    @Test fun negativeDepthIsRejected() {
        assertFails {
            buildUploadPackBody(
                listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                FetchScope(depth = 0),
            )
        }
    }

    @Test fun extractParsesShallowUnshallowBeforeNAK() {
        // Synthesize an upload-pack response with one shallow + one
        // unshallow line, a flush, NAK, then a minimal (empty) PACK.
        val responseBody =
            PktLine.encodeText("shallow aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n") +
                PktLine.encodeText("unshallow bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n") +
                PktLine.flush +
                PktLine.encodeText("NAK\n") +
                // Just enough bytes that the slice exists; PackfileReader
                // would reject these, but the extractor only slices.
                "PACK".encodeToByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0)
        val (shallow, unshallow, pack) =
            extractPackfileFromBody(responseBody, shallowExpected = true, sideband = false)
        assertEquals(setOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), shallow)
        assertEquals(setOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), unshallow)
        assertTrue(pack.size >= 4 && pack[0] == 'P'.code.toByte())
    }

    @Test fun extractSurfacesServerERRLineVerbatim() {
        val body = PktLine.encodeText("ERR upload-pack: not our ref deadbeefdeadbeefdeadbeefdeadbeefdeadbeef\n")
        val ex =
            kotlin
                .runCatching { extractPackfileFromBody(body, shallowExpected = false, sideband = false) }
                .exceptionOrNull()
        kotlin.test.assertNotNull(ex)
        assertContains(ex.message ?: "", "server reported error")
        assertContains(ex.message ?: "", "not our ref")
    }

    @Test fun extractAcceptsAckVerdict() {
        // Some servers emit `ACK 0000…` even when we sent no haves.
        val body =
            PktLine.encodeText("ACK 0000000000000000000000000000000000000000\n") +
                "PACK".encodeToByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0)
        val (shallow, unshallow, pack) = extractPackfileFromBody(body, shallowExpected = false, sideband = false)
        assertTrue(shallow.isEmpty())
        assertTrue(unshallow.isEmpty())
        assertTrue(pack.size >= 4 && pack[0] == 'P'.code.toByte())
    }

    @Test fun extractWithoutShallowExpectedStillFindsNAKAndPack() {
        val body =
            PktLine.encodeText("NAK\n") +
                "PACK".encodeToByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0)
        val (shallow, unshallow, pack) = extractPackfileFromBody(body, shallowExpected = false, sideband = false)
        assertTrue(shallow.isEmpty())
        assertTrue(unshallow.isEmpty())
        assertTrue(pack.size >= 4 && pack[0] == 'P'.code.toByte())
    }
}
