@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.shared.fflate

// Byte <-> JS marshaling shared by the fflate-backed codecs. Bytes cross
// the wasm/JS boundary as strings of 0..255 code units; the js("…")
// helpers only touch browser globals (Uint8Array), so they're safe in the
// browser — unlike `require`.

/** ByteArray -> JsString of [data].size code units, each in 0..255. */
public fun bytesToJsString(data: ByteArray): JsString {
    val sb = StringBuilder(data.size)
    for (b in data) sb.append((b.toInt() and 0xff).toChar())
    return sb.toString().toJsString()
}

/** Inverse of [bytesToJsString]: a 0..255 code-unit String back to bytes. */
public fun jsStringToBytes(s: String): ByteArray {
    val out = ByteArray(s.length)
    for (i in s.indices) out[i] = (s[i].code and 0xff).toByte()
    return out
}

/** 0..255 code-unit JsString -> Uint8Array (as JsAny). */
public fun toU8(s: JsString): JsAny =
    js(
        "(function(s){var a=new Uint8Array(s.length);" +
            "for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i)&0xff;return a;})(s)",
    )

/** Uint8Array (as JsAny) -> 0..255 code-unit JsString. */
public fun u8ToJsString(a: JsAny): JsString =
    js(
        "(function(a){var r='';for(var j=0;j<a.length;j++)r+=String.fromCharCode(a[j]);" +
            "return r;})(a)",
    )
