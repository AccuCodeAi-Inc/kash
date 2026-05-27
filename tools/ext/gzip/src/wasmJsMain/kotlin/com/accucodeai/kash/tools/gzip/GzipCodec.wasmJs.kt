@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.gzip

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.shared.fflate.bytesToJsString
import com.accucodeai.kash.shared.fflate.gunzipSync
import com.accucodeai.kash.shared.fflate.gzipSync
import com.accucodeai.kash.shared.fflate.jsStringToBytes
import com.accucodeai.kash.shared.fflate.toU8
import com.accucodeai.kash.shared.fflate.u8ToJsString

// Browser-side gzip via `fflate` (MIT). The library + byte marshaling come
// from :shared:fflate (ES-module imports, NOT `require`). Only the gzip
// options builder is local here.

// Build fflate's gzip options object. `mtime` accepts a JS Date; gzip's
// MTIME field is uint32 seconds, so we hand it `new Date(seconds*1000)`.
// `filename` is set only when provided.
private fun gzipOpts(
    level: Int,
    filename: JsString?,
    mtimeSec: Int,
): JsAny =
    js(
        "(function(l,n,t){var o={level:l,mtime:new Date(t*1000)};" +
            "if(n!=null)o.filename=n;return o;})(level,filename,mtimeSec)",
    )

public actual suspend fun gzipCompress(
    source: SuspendSource,
    sink: SuspendSink,
    level: Int,
    storeName: String?,
    storeMtime: Long,
) {
    val input = source.readAllBytes()
    val u8 = toU8(bytesToJsString(input))
    val opts = gzipOpts(level, storeName?.toJsString(), storeMtime.toInt())
    val out = gzipSync(u8, opts)
    sink.writeBytes(jsStringToBytes(u8ToJsString(out).toString()))
}

public actual suspend fun gzipDecompress(
    source: SuspendSource,
    sink: SuspendSink,
) {
    val input = source.readAllBytes()
    val out = gunzipSync(toU8(bytesToJsString(input)))
    sink.writeBytes(jsStringToBytes(u8ToJsString(out).toString()))
}

public actual suspend fun gzipTest(source: SuspendSource) {
    val input = source.readAllBytes()
    gunzipSync(toU8(bytesToJsString(input))) // throws on invalid input
}
