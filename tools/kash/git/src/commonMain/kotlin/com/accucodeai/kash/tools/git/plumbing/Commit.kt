package com.accucodeai.kash.tools.git.plumbing

/**
 * A commit object's logical contents — everything we need to serialize
 * to the byte form git hashes. Encoding and decoding round-trip; tests
 * verify the byte form against `git cat-file commit`.
 *
 * Encoding:
 * ```
 * tree <sha>\n
 * (parent <sha>\n)*
 * author <name> <<email>> <unix-ts> <tz>\n
 * committer <name> <<email>> <unix-ts> <tz>\n
 * \n
 * <message>
 * ```
 *
 * Real git auto-appends a trailing LF if the message lacks one; we
 * preserve [message] verbatim so callers stay in control. The
 * `commit -m` builder layer (when we have one) is where the LF-append
 * convention belongs.
 */
public data class CommitPayload(
    val tree: String,
    val parents: List<String>,
    val author: PersonStamp,
    val committer: PersonStamp,
    val message: String,
    /**
     * Headers between `committer` and the blank line — `gpgsig`,
     * `mergetag`, `encoding`, etc. Each is `(name, multilineValue)`;
     * encoding handles the continuation-line indent. Empty by default.
     */
    val extraHeaders: List<Pair<String, String>> = emptyList(),
) {
    init {
        require(tree.length == 40) { "commit tree sha must be 40 hex chars" }
        parents.forEach { require(it.length == 40) { "commit parent sha must be 40 hex chars" } }
    }
}

public data class PersonStamp(
    val name: String,
    val email: String,
    /** Seconds since epoch. */
    val whenSeconds: Long,
    /**
     * Timezone offset in the `±HHMM` form git writes (e.g. `"+0000"`,
     * `"-0500"`). We keep it as a string because git doesn't store the
     * minutes-vs-hours boundary as a number — it stores exactly what
     * the local TZ produced.
     */
    val tz: String,
) {
    init {
        require(!name.contains('<') && !name.contains('>') && !name.contains('\n')) {
            "person name may not contain '<', '>', or newline"
        }
        require(!email.contains('<') && !email.contains('>') && !email.contains('\n')) {
            "person email may not contain '<', '>', or newline"
        }
        require(tz.length == 5 && (tz[0] == '+' || tz[0] == '-')) { "tz must be ±HHMM, got '$tz'" }
    }

    public fun encoded(): String = "$name <$email> $whenSeconds $tz"
}

public fun encodeCommit(c: CommitPayload): ByteArray {
    val sb = StringBuilder()
    sb.append("tree ").append(c.tree).append('\n')
    for (p in c.parents) sb.append("parent ").append(p).append('\n')
    sb.append("author ").append(c.author.encoded()).append('\n')
    sb.append("committer ").append(c.committer.encoded()).append('\n')
    for ((name, value) in c.extraHeaders) {
        sb.append(name).append(' ')
        // Continuation lines: any LF in the value is followed by a single
        // SPACE on the next line, matching git's `gpgsig`/`mergetag` form.
        sb.append(value.replace("\n", "\n "))
        sb.append('\n')
    }
    sb.append('\n')
    sb.append(c.message)
    return sb.toString().encodeToByteArray()
}

public fun commitSha(c: CommitPayload): String = objectSha(ObjectType.COMMIT, encodeCommit(c))

public fun decodeCommit(payload: ByteArray): CommitPayload {
    val text = payload.decodeToString()
    val blank = text.indexOf("\n\n")
    require(blank >= 0) { "commit decode: missing blank line before message" }
    val headerBlock = text.substring(0, blank)
    val message = text.substring(blank + 2)

    var tree: String? = null
    val parents = mutableListOf<String>()
    var author: PersonStamp? = null
    var committer: PersonStamp? = null
    val extras = mutableListOf<Pair<String, String>>()

    // Walk header lines; a leading space continues the previous header's value.
    val lines = headerBlock.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        require(line.isNotEmpty() && line[0] != ' ') { "commit decode: stray continuation" }
        val sp = line.indexOf(' ')
        require(sp > 0) { "commit decode: header missing value" }
        val name = line.substring(0, sp)
        val first = line.substring(sp + 1)
        val value = StringBuilder(first)
        var j = i + 1
        while (j < lines.size && lines[j].startsWith(" ")) {
            value.append('\n').append(lines[j].substring(1))
            j++
        }
        when (name) {
            "tree" -> tree = value.toString()
            "parent" -> parents += value.toString()
            "author" -> author = parsePerson(value.toString())
            "committer" -> committer = parsePerson(value.toString())
            else -> extras += name to value.toString()
        }
        i = j
    }
    requireNotNull(tree) { "commit decode: missing tree header" }
    requireNotNull(author) { "commit decode: missing author header" }
    requireNotNull(committer) { "commit decode: missing committer header" }
    return CommitPayload(tree, parents, author, committer, message, extras)
}

private fun parsePerson(s: String): PersonStamp {
    val ltGt = s.lastIndexOf('>')
    val ltLt = s.lastIndexOf('<', ltGt)
    require(ltLt > 0 && ltGt > ltLt) { "person stamp: missing <email>" }
    val name = s.substring(0, ltLt - 1)
    val email = s.substring(ltLt + 1, ltGt)
    val rest = s.substring(ltGt + 2).split(' ')
    require(rest.size == 2) { "person stamp: bad time/tz tail '$s'" }
    return PersonStamp(name, email, rest[0].toLong(), rest[1])
}
