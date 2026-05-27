package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.api.git.GitObjectResolver
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.hash.sha1Hex

/**
 * Loose-object reader/writer. Backed by the VFS at [layout]'s
 * `.git/objects/` tree. Writes are content-addressed: the sha comes
 * from the framed bytes, so callers can't accidentally overwrite an
 * object with different content under the same name (we error if a
 * conflicting object already exists).
 *
 * If [resolver] is non-null, [read] falls back to it on local miss —
 * the bytes returned are verified to match the requested sha and
 * cached as a loose object before being returned. This is the path
 * the GitLab-API-backed `OnDemand` seed uses to fetch objects past
 * the horizon.
 *
 * Pack support is not in this layer — when we add it, callers gain
 * `readFromPack` semantics here without changing the [read] surface.
 */
public class ObjectStore(
    public val layout: RepoLayout,
    public val fs: FileSystem,
    public val resolver: GitObjectResolver? = null,
) {
    /** True iff the framed object is materialized locally (loose form). */
    public fun hasLocal(sha: String): Boolean = fs.exists(layout.looseObjectPath(sha))

    /**
     * Fetch the **uncompressed framed object** for [sha]. Local first;
     * on miss, [resolver] is consulted (and the result cached). Throws
     * [FileNotFound] if neither source has it.
     */
    public suspend fun read(sha: String): ByteArray {
        if (hasLocal(sha)) {
            return zlibInflate(fs.readBytes(layout.looseObjectPath(sha)))
        }
        val resolved = resolver?.resolve(sha) ?: throw FileNotFound("git object $sha")
        // Defense-in-depth: the host could be wrong about what it's
        // handing us. Verify before caching.
        val actual = sha1Hex(resolved)
        require(actual == sha) {
            "git object resolver returned mismatched sha: asked $sha, got $actual"
        }
        writeFramed(resolved) // caches locally
        return resolved
    }

    /**
     * Write [framed] (uncompressed) as a loose object. Returns the sha
     * git assigns it. If a loose object already exists at this sha:
     *  - identical bytes → no-op (idempotent)
     *  - different bytes → throws (would be a SHA-1 collision, real bug)
     */
    public suspend fun writeFramed(framed: ByteArray): String {
        val sha = sha1Hex(framed)
        val path = layout.looseObjectPath(sha)
        if (fs.exists(path)) {
            val existing = zlibInflate(fs.readBytes(path))
            require(existing.contentEquals(framed)) {
                "object $sha already exists with different content (SHA-1 collision?)"
            }
            return sha
        }
        fs.mkdirs(path.substringBeforeLast('/'))
        fs.writeBytes(path, zlibDeflate(framed))
        return sha
    }

    /** Convenience: frame a payload and write it. */
    public suspend fun write(
        type: ObjectType,
        payload: ByteArray,
    ): String = writeFramed(framedObject(type, payload))

    /** Parse [read] result into type + payload. */
    public suspend fun readParsed(sha: String): ParsedObject = parseFramedObject(read(sha))

    /** Read a blob's raw bytes. Throws if [sha] isn't a blob. */
    public suspend fun readBlob(sha: String): ByteArray {
        val obj = readParsed(sha)
        require(obj.type == ObjectType.BLOB) { "$sha is ${obj.type.token}, not blob" }
        return obj.payload
    }

    /** Read and decode a tree object. */
    public suspend fun readTree(sha: String): List<TreeEntry> {
        val obj = readParsed(sha)
        require(obj.type == ObjectType.TREE) { "$sha is ${obj.type.token}, not tree" }
        return decodeTree(obj.payload)
    }

    /** Read and decode a commit object. */
    public suspend fun readCommit(sha: String): CommitPayload {
        val obj = readParsed(sha)
        require(obj.type == ObjectType.COMMIT) { "$sha is ${obj.type.token}, not commit" }
        return decodeCommit(obj.payload)
    }
}
