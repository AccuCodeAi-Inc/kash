@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.git.plumbing

// Browser-side zlib via `pako` (MIT + Zlib licensed, see NOTICE). pako is
// the canonical JS port of zlib used across the npm ecosystem — small,
// fast, and browser-clean.
//
// pako is reached through ES-module imports (see PakoExternals.kt), NOT
// `require` — which is undefined in the browser and throws
// `require is not a function`. Bytes shuttle across the JS boundary as
// strings of code units (each byte → one `String.fromCharCode(b & 0xff)`);
// small browser-global js("…") helpers convert to/from Uint8Array.

private fun bytesToJsString(data: ByteArray): JsString {
    val sb = StringBuilder(data.size)
    for (b in data) sb.append((b.toInt() and 0xff).toChar())
    return sb.toString().toJsString()
}

private fun bytesToJsStringRange(
    data: ByteArray,
    offset: Int,
): JsString {
    val sb = StringBuilder(data.size - offset)
    for (i in offset until data.size) sb.append((data[i].toInt() and 0xff).toChar())
    return sb.toString().toJsString()
}

private fun jsStringToBytes(s: String): ByteArray {
    val out = ByteArray(s.length)
    for (i in s.indices) out[i] = (s[i].code and 0xff).toByte()
    return out
}

// 0..255 code-unit JsString <-> Uint8Array. Browser-global, no `require`.
private fun toU8(s: JsString): JsAny =
    js(
        "(function(s){var a=new Uint8Array(s.length);" +
            "for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i)&0xff;return a;})(s)",
    )

private fun u8ToJsString(a: JsAny): JsString =
    js(
        "(function(a){var r='';for(var j=0;j<a.length;j++)r+=String.fromCharCode(a[j]);" +
            "return r;})(a)",
    )

private fun deflateOpts(level: Int): JsAny = js("({ level: level })")

// Pull `strm.total_in` (bytes consumed) off a pako Inflate's strm object.
private fun strmTotalIn(strm: JsAny): Int = js("strm.total_in")

public actual fun zlibDeflate(
    data: ByteArray,
    level: Int,
): ByteArray {
    val out = deflate(toU8(bytesToJsString(data)), deflateOpts(level))
    return jsStringToBytes(u8ToJsString(out).toString())
}

public actual fun zlibInflate(data: ByteArray): ByteArray {
    val out = inflate(toU8(bytesToJsString(data)))
    return jsStringToBytes(u8ToJsString(out).toString())
}

public actual fun zlibInflateChunk(
    data: ByteArray,
    offset: Int,
): InflatedChunk {
    val inf = Inflate()
    inf.push(toU8(bytesToJsStringRange(data, offset)), false)
    if (inf.err != 0) throw IllegalStateException("pako inflate err=${inf.err}")
    val bytes = jsStringToBytes(u8ToJsString(inf.result).toString())
    val consumed = strmTotalIn(inf.strm)
    return InflatedChunk(bytes, consumed)
}
