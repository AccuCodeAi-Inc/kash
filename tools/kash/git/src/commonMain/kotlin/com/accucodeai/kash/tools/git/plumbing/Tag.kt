package com.accucodeai.kash.tools.git.plumbing

/**
 * Annotated tag object — what `git tag -a` writes to the loose store.
 *
 * Encoding:
 * ```
 * object <sha>\n
 * type <token>\n
 * tag <name>\n
 * tagger <name> <<email>> <unix-ts> <tz>\n
 * \n
 * <message>
 * ```
 *
 * The `tag <name>` line carries the tag's *display* name, which is
 * also what `refs/tags/<name>` is named. Real git treats the two as
 * independent fields, but they're conventionally always equal.
 */
public data class TagPayload(
    val targetSha: String,
    val targetType: ObjectType,
    val tagName: String,
    val tagger: PersonStamp,
    val message: String,
) {
    init {
        require(targetSha.length == 40) { "tag target sha must be 40 hex chars" }
    }
}

public fun encodeTag(t: TagPayload): ByteArray {
    val sb = StringBuilder()
    sb.append("object ").append(t.targetSha).append('\n')
    sb.append("type ").append(t.targetType.token).append('\n')
    sb.append("tag ").append(t.tagName).append('\n')
    sb.append("tagger ").append(t.tagger.encoded()).append('\n')
    sb.append('\n')
    sb.append(t.message)
    return sb.toString().encodeToByteArray()
}

public fun decodeTag(payload: ByteArray): TagPayload {
    val text = payload.decodeToString()
    val blank = text.indexOf("\n\n")
    require(blank >= 0) { "tag decode: missing blank line before message" }
    val header = text.substring(0, blank)
    val message = text.substring(blank + 2)

    var obj: String? = null
    var type: ObjectType? = null
    var name: String? = null
    var tagger: PersonStamp? = null
    for (line in header.split('\n')) {
        val sp = line.indexOf(' ')
        if (sp < 0) continue
        val key = line.substring(0, sp)
        val value = line.substring(sp + 1)
        when (key) {
            "object" -> obj = value
            "type" -> type = ObjectType.ofToken(value)
            "tag" -> name = value
            "tagger" -> tagger = parseTagger(value)
        }
    }
    return TagPayload(
        targetSha = requireNotNull(obj) { "tag decode: missing object" },
        targetType = requireNotNull(type) { "tag decode: missing type" },
        tagName = requireNotNull(name) { "tag decode: missing tag name" },
        tagger = requireNotNull(tagger) { "tag decode: missing tagger" },
        message = message,
    )
}

private fun parseTagger(s: String): PersonStamp {
    val ltGt = s.lastIndexOf('>')
    val ltLt = s.lastIndexOf('<', ltGt)
    require(ltLt > 0 && ltGt > ltLt) { "tagger: missing <email>" }
    val name = s.substring(0, ltLt - 1)
    val email = s.substring(ltLt + 1, ltGt)
    val rest = s.substring(ltGt + 2).split(' ')
    require(rest.size == 2) { "tagger: bad time/tz tail" }
    return PersonStamp(name, email, rest[0].toLong(), rest[1])
}
