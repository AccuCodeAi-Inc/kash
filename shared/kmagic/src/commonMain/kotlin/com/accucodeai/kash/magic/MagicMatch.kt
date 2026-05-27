package com.accucodeai.kash.magic

/**
 * The result of identifying a byte buffer.
 *
 * @property description human-readable type, in the style of `file(1)`
 *   (e.g. `"PNG image data"`).
 * @property mime the IANA media type (e.g. `"image/png"`).
 * @property extensions common filename extensions for the format, most
 *   typical first, without a leading dot (e.g. `["jpg", "jpeg"]`).
 */
public data class MagicMatch(
    val description: String,
    val mime: String,
    val extensions: List<String> = emptyList(),
)
