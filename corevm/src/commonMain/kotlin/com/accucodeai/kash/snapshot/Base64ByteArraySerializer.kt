package com.accucodeai.kash.snapshot

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Encodes a [ByteArray] as a Base64-encoded JSON string.
 *
 * The default `kotlinx-serialization-json` serializer renders `ByteArray`
 * as a JSON array of unsigned-byte ints (`[72, 105]`) — verbose (≈4× the
 * compact form), painful to eyeball, and slower to encode/decode. Base64
 * gets us ~4/3× the raw bytes with no character-escape overhead, and the
 * encoding is single-line and human-recognizable. Round-trip is exact.
 *
 * Applied via `@Serializable(with = Base64ByteArraySerializer::class)` on
 * file-content fields in the snapshot data classes — see
 * [com.accucodeai.kash.fs.FileEntry.bytes].
 */
@OptIn(ExperimentalEncodingApi::class)
public object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ByteArray,
    ) {
        encoder.encodeString(Base64.encode(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray = Base64.decode(decoder.decodeString())
}
