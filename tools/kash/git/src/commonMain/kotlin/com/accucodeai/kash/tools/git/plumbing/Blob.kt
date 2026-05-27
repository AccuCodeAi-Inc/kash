package com.accucodeai.kash.tools.git.plumbing

/**
 * A blob in git is just the file's bytes; the SHA comes from the framed
 * form `"blob <len>\0<bytes>"`. There is no payload-side encoder — the
 * payload *is* the file content. This file exists so callers have a
 * named entry point and can stop thinking about framing.
 */
public fun blobSha(content: ByteArray): String = objectSha(ObjectType.BLOB, content)
