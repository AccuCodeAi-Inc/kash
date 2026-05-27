@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.zip

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.shared.fflate.bytesToJsString
import com.accucodeai.kash.shared.fflate.jsStringToBytes
import com.accucodeai.kash.shared.fflate.toU8
import com.accucodeai.kash.shared.fflate.u8ToJsString
import com.accucodeai.kash.shared.fflate.unzipSync
import com.accucodeai.kash.shared.fflate.zipSync
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

// ---------------------------------------------------------------------------
// Browser-side zip via `fflate` (MIT). Same buffer-then-process policy as
// the JVM side (`java.util.zip.ZipInputStream`): drain the source into one
// byte[], hand it to fflate.unzipSync, walk the resulting map. Writing is
// the inverse — accumulate entries in memory, call fflate.zipSync once on
// close.
//
// fflate is reached through ES-module imports (see FflateExternals.kt),
// NOT `require` — which is undefined in the browser and throws
// `require is not a function`. The js("…") helpers below only touch
// browser globals (Uint8Array, DataView, TextDecoder, Date, Object),
// which are safe; the unzipSync/zipSync calls happen in Kotlin against
// the imported externs.
//
// Bytes cross the JS boundary as strings of 0..255 code units, matching
// the gzip/tar codecs. Multi-entry archives use a tiny framing format:
//
//   [u32be name-len][name UTF-8][u32be mtime-seconds][u32be body-len][body]
//
// so one `js("…")` call suffices for the full archive. mtime is carried
// across explicitly because fflate's `unzipSync` strips it from its
// `{name: Uint8Array}` return — the JS helper parses the ZIP central
// directory and folds the per-entry DOS timestamp back in. mtime=0
// signals "unset" and is omitted from fflate's per-entry options on
// write, matching the JVM ZipWriter's behavior.
//
// TODO(streaming): when archives big enough to matter for browser memory
// show up (real user report or a tar/zip pipeline source), switch to
// fflate's `Unzip` + `UnzipInflate` streaming class. API shape: register
// `UnzipInflate`, `onfile(file)` fires per entry, `file.ondata(err, chunk,
// final)` emits chunked decompression output. Bridge to a Kotlin
// per-entry Channel<ByteArray> for `copyEntryTo`. Until then the
// buffer-once policy matches every other compression tool in kash.
// ---------------------------------------------------------------------------

private suspend fun drainSource(source: SuspendSource): ByteArray {
    val all = Buffer()
    while (true) {
        val n = source.readAtMostTo(all, 64 * 1024L)
        if (n == -1L) break
    }
    return all.readByteArray()
}

// Frame the unzipSync result. `a` is the raw archive Uint8Array (for the
// central-directory mtime scan); `obj` is fflate.unzipSync's
// { name: Uint8Array } result. No fflate call here — the unzipSync ran in
// Kotlin against the imported extern.
//
// fflate drops per-entry mtime, so we walk the ZIP central directory
// ourselves to rebuild a name→mtime map and merge it into the framed
// output. EOCD lookup scans back from end-of-file for PK\005\006 (max ZIP
// comment is 65535 bytes, so the EOCD sig must lie within the last
// ~64 KiB + 22 B). If we can't locate it, mtimes degrade to 0 — same
// fallback as a stripped/streamed archive that has no CD.
private fun frameUnzipResult(
    a: JsAny,
    obj: JsAny,
): JsString =
    js(
        "(function(a,obj){" +
            // ---- Parse central directory for mtimes ---------------
            "var mtimes={};" +
            "var dv=new DataView(a.buffer,a.byteOffset,a.byteLength);" +
            "var eocd=-1;" +
            "var maxComment=65535;" +
            "var scanStart=Math.max(0,a.length-22-maxComment);" +
            "for(var e=a.length-22;e>=scanStart;e--){" +
            "if(dv.getUint32(e,true)===0x06054b50){eocd=e;break;}" +
            "}" +
            "if(eocd>=0){" +
            "var cdOff=dv.getUint32(eocd+16,true);" +
            "var cdSize=dv.getUint32(eocd+12,true);" +
            "var cdEnd=cdOff+cdSize;" +
            "var dec0=new TextDecoder('utf-8');" +
            "var cp=cdOff;" +
            "while(cp<cdEnd-46&&dv.getUint32(cp,true)===0x02014b50){" +
            "var dosTime=dv.getUint16(cp+12,true);" +
            "var dosDate=dv.getUint16(cp+14,true);" +
            "var nlen=dv.getUint16(cp+28,true);" +
            "var xlen=dv.getUint16(cp+30,true);" +
            "var clen=dv.getUint16(cp+32,true);" +
            "var name=dec0.decode(a.subarray(cp+46,cp+46+nlen));" +
            "var year=((dosDate>>>9)&0x7f)+1980;" +
            "var month=(dosDate>>>5)&0xf;" +
            "var day=dosDate&0x1f;" +
            "var hour=(dosTime>>>11)&0x1f;" +
            "var minute=(dosTime>>>5)&0x3f;" +
            "var second=(dosTime&0x1f)*2;" +
            "var ep=(dosDate===0&&dosTime===0)?0:" +
            "Math.floor(Date.UTC(year,month-1,day,hour,minute,second)/1000);" +
            "if(!isFinite(ep)||ep<0)ep=0;" +
            "mtimes[name]=ep>>>0;" +
            "cp+=46+nlen+xlen+clen;" +
            "}" +
            "}" +
            // ---- Frame into Kotlin-side format --------------------
            "var enc=new TextEncoder();" +
            "var parts=[];" +
            "var total=0;" +
            "for(var k in obj){" +
            "if(!Object.prototype.hasOwnProperty.call(obj,k))continue;" +
            "var nb=enc.encode(k);" +
            "var bb=obj[k];" +
            "var mt=mtimes[k]||0;" +
            "parts.push(nb);parts.push(mt);parts.push(bb);" +
            "total+=12+nb.length+bb.length;" +
            "}" +
            "var out=new Uint8Array(total);" +
            "var p=0;" +
            "for(var j=0;j<parts.length;j+=3){" +
            "var nb=parts[j];var mt=parts[j+1];var bb=parts[j+2];" +
            "out[p++]=(nb.length>>>24)&0xff;out[p++]=(nb.length>>>16)&0xff;" +
            "out[p++]=(nb.length>>>8)&0xff;out[p++]=nb.length&0xff;" +
            "out.set(nb,p);p+=nb.length;" +
            "out[p++]=(mt>>>24)&0xff;out[p++]=(mt>>>16)&0xff;" +
            "out[p++]=(mt>>>8)&0xff;out[p++]=mt&0xff;" +
            "out[p++]=(bb.length>>>24)&0xff;out[p++]=(bb.length>>>16)&0xff;" +
            "out[p++]=(bb.length>>>8)&0xff;out[p++]=bb.length&0xff;" +
            "out.set(bb,p);p+=bb.length;" +
            "}" +
            "var r='';for(var q=0;q<out.length;q++)r+=String.fromCharCode(out[q]);" +
            "return r;" +
            "})(a,obj)",
    )

// Decode the framed buffer into an fflate zip-input object. `a` is the
// framed Uint8Array. Entries with mtime!=0 use fflate's [bytes, opts]
// tuple form to preserve the timestamp in the central directory. No
// fflate call — zipSync runs in Kotlin against the imported extern.
private fun buildZipInput(a: JsAny): JsAny =
    js(
        "(function(a){" +
            "var dv=new DataView(a.buffer,a.byteOffset,a.byteLength);" +
            "var obj={};" +
            "var p=0;" +
            "var dec=new TextDecoder('utf-8');" +
            "while(p<a.length){" +
            "var nl=dv.getUint32(p,false);p+=4;" +
            "var name=dec.decode(a.subarray(p,p+nl));p+=nl;" +
            "var mt=dv.getUint32(p,false);p+=4;" +
            "var bl=dv.getUint32(p,false);p+=4;" +
            "var body=new Uint8Array(bl);body.set(a.subarray(p,p+bl));p+=bl;" +
            "if(mt>0){obj[name]=[body,{mtime:new Date(mt*1000)}];}" +
            "else{obj[name]=body;}" +
            "}" +
            "return obj;" +
            "})(a)",
    )

private data class FramedEntry(
    val name: String,
    val mtimeEpochSeconds: Long,
    val body: ByteArray,
)

private fun frameEntries(entries: List<FramedEntry>): ByteArray {
    val nameBytes = entries.map { it.name.encodeToByteArray() }
    var size = 0
    for ((i, e) in entries.withIndex()) size += 12 + nameBytes[i].size + e.body.size
    val out = ByteArray(size)
    var i = 0
    for ((k, e) in entries.withIndex()) {
        val n = nameBytes[k]
        val mt = e.mtimeEpochSeconds.toInt()
        val b = e.body
        out[i++] = (n.size ushr 24).toByte()
        out[i++] = (n.size ushr 16).toByte()
        out[i++] = (n.size ushr 8).toByte()
        out[i++] = n.size.toByte()
        n.copyInto(out, i)
        i += n.size
        out[i++] = (mt ushr 24).toByte()
        out[i++] = (mt ushr 16).toByte()
        out[i++] = (mt ushr 8).toByte()
        out[i++] = mt.toByte()
        out[i++] = (b.size ushr 24).toByte()
        out[i++] = (b.size ushr 16).toByte()
        out[i++] = (b.size ushr 8).toByte()
        out[i++] = b.size.toByte()
        b.copyInto(out, i)
        i += b.size
    }
    return out
}

private fun unframeEntries(framed: ByteArray): List<FramedEntry> {
    val out = mutableListOf<FramedEntry>()
    var p = 0
    while (p < framed.size) {
        val nl = u32be(framed, p)
        p += 4
        val name = framed.copyOfRange(p, p + nl).decodeToString()
        p += nl
        val mt = u32be(framed, p).toLong() and 0xFFFFFFFFL
        p += 4
        val bl = u32be(framed, p)
        p += 4
        val body = framed.copyOfRange(p, p + bl)
        p += bl
        out += FramedEntry(name = name, mtimeEpochSeconds = mt, body = body)
    }
    return out
}

private fun u32be(
    a: ByteArray,
    off: Int,
): Int =
    ((a[off].toInt() and 0xff) shl 24) or
        ((a[off + 1].toInt() and 0xff) shl 16) or
        ((a[off + 2].toInt() and 0xff) shl 8) or
        (a[off + 3].toInt() and 0xff)

// ---------------------------------------------------------------------------
// ZipReader
// ---------------------------------------------------------------------------

public actual class ZipReader actual constructor(
    private val source: SuspendSource,
) {
    private var loaded: Boolean = false
    private var entries: List<FramedEntry> = emptyList()
    private var index: Int = -1

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val bytes = drainSource(source)
        if (bytes.isEmpty()) {
            entries = emptyList()
            return
        }
        val rawU8 = toU8(bytesToJsString(bytes))
        val obj = unzipSync(rawU8)
        val framedJs = frameUnzipResult(rawU8, obj).toString()
        entries = unframeEntries(jsStringToBytes(framedJs))
    }

    public actual suspend fun readNextEntry(): ZipEntryInfo? {
        ensureLoaded()
        index += 1
        if (index >= entries.size) return null
        val e = entries[index]
        val isDir = e.name.endsWith("/")
        return ZipEntryInfo(
            name = e.name,
            isDirectory = isDir,
            uncompressedSize = if (isDir) 0L else e.body.size.toLong(),
            mtimeEpochSeconds = e.mtimeEpochSeconds,
        )
    }

    public actual suspend fun entryBytes(): ByteArray {
        if (index < 0 || index >= entries.size) return ByteArray(0)
        return entries[index].body
    }

    public actual suspend fun copyEntryTo(sink: SuspendSink) {
        if (index < 0 || index >= entries.size) return
        val body = entries[index].body
        if (body.isNotEmpty()) sink.writeBytes(body)
    }

    public actual suspend fun skipEntry() {
        // In-memory model — nothing to discard; cursor advances on readNextEntry.
    }

    public actual fun close() {
        // Nothing held — entries are already in-memory.
    }
}

// ---------------------------------------------------------------------------
// ZipWriter
// ---------------------------------------------------------------------------

public actual class ZipWriter actual constructor(
    private val sink: SuspendSink,
    @Suppress("UNUSED_PARAMETER") level: Int,
) {
    // `level` is accepted for parity with the JVM ZipWriter but fflate's
    // sync API takes a level per-entry via the AsyncZippable [u8, opts]
    // tuple form; v1 lets fflate pick its default. Audit / wire up if a
    // future caller specifically needs STORED or a custom level on wasm.

    private val entries: MutableList<FramedEntry> = mutableListOf()
    private var openName: String? = null
    private var openMtime: Long = 0L
    private var openBuffer: Buffer = Buffer()
    private var closed: Boolean = false

    public actual suspend fun putDirEntry(
        name: String,
        mtimeEpochSeconds: Long?,
    ) {
        flushOpenEntry()
        val n = if (name.endsWith("/")) name else "$name/"
        entries += FramedEntry(name = n, mtimeEpochSeconds = mtimeEpochSeconds ?: 0L, body = ByteArray(0))
    }

    public actual suspend fun putFileEntry(
        name: String,
        bytes: ByteArray,
        mtimeEpochSeconds: Long?,
    ) {
        flushOpenEntry()
        entries += FramedEntry(name = name, mtimeEpochSeconds = mtimeEpochSeconds ?: 0L, body = bytes)
    }

    public actual suspend fun openFileEntry(
        name: String,
        mtimeEpochSeconds: Long?,
    ): SuspendSink {
        flushOpenEntry()
        openName = name
        openMtime = mtimeEpochSeconds ?: 0L
        openBuffer = Buffer()
        return EntrySink()
    }

    public actual suspend fun closeEntry() {
        flushOpenEntry()
    }

    public actual suspend fun close() {
        if (closed) return
        closed = true
        flushOpenEntry()
        if (entries.isEmpty()) {
            sink.flush()
            return
        }
        val framed = frameEntries(entries)
        val zipInput = buildZipInput(toU8(bytesToJsString(framed)))
        val archiveJs = u8ToJsString(zipSync(zipInput)).toString()
        val archive = jsStringToBytes(archiveJs)
        if (archive.isNotEmpty()) {
            val buf = Buffer()
            buf.write(archive)
            sink.write(buf, buf.size)
        }
        sink.flush()
    }

    private fun flushOpenEntry() {
        val name = openName ?: return
        val body = openBuffer.readByteArray()
        entries += FramedEntry(name = name, mtimeEpochSeconds = openMtime, body = body)
        openName = null
        openMtime = 0L
        openBuffer = Buffer()
    }

    private inner class EntrySink : SuspendSink {
        override suspend fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            var remaining = byteCount
            while (remaining > 0) {
                val n = source.readAtMostTo(openBuffer, remaining)
                if (n == -1L) break
                remaining -= n
            }
        }

        override suspend fun flush() {
            // Buffered until closeEntry; no per-write flush.
        }

        override fun close() {
            // ZipWriter owns the entry lifecycle.
        }
    }
}
