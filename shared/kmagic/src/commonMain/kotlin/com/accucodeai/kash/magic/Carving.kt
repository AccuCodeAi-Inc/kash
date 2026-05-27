package com.accucodeai.kash.magic

/**
 * One signature hit found by [KMagic.scan] at an arbitrary position in a blob.
 *
 * @property offset absolute byte offset where the embedded item starts (i.e.
 *   where its magic would sit if the item began here — already corrected for
 *   the signature's internal offset).
 * @property match the identified type.
 */
public data class Carving(
    val offset: Long,
    val match: MagicMatch,
)
