package com.accucodeai.kash.tools.git.plumbing

/**
 * In-memory representation of a working tree: full path (no leading `/`)
 * to file content + executable bit. Symlinks and gitlinks are not in
 * scope for the synthetic-seed path; they can be added later.
 */
public data class FlatFile(
    val bytes: ByteArray,
    val executable: Boolean = false,
)

public typealias FlatTree = Map<String, FlatFile>

/**
 * Write every blob + every directory tree object that [flat] implies,
 * returning the root tree sha. Idempotent — if the same blob/tree
 * already exists in [store], we don't double-write.
 *
 * Paths are split on `/` to build the hierarchy; intermediate directory
 * objects are created automatically.
 */
public suspend fun writeFlatTree(
    store: ObjectStore,
    flat: FlatTree,
): String {
    // Root entry: path = "" means the directory we're computing right now.
    // Build a nested map keyed by directory.
    return writeDir(store, "", flat)
}

private suspend fun writeDir(
    store: ObjectStore,
    dirPrefix: String,
    flat: FlatTree,
): String {
    val direct = mutableMapOf<String, FlatFile>()
    val subdirEntries = mutableMapOf<String, MutableMap<String, FlatFile>>()
    val keyPrefix = if (dirPrefix.isEmpty()) "" else "$dirPrefix/"

    for ((path, file) in flat) {
        if (!path.startsWith(keyPrefix)) continue
        val rest = path.substring(keyPrefix.length)
        if (rest.isEmpty()) continue
        val slash = rest.indexOf('/')
        if (slash < 0) {
            direct[rest] = file
        } else {
            val sub = rest.substring(0, slash)
            val rel = rest.substring(slash + 1)
            subdirEntries.getOrPut(sub) { mutableMapOf() }["$keyPrefix$sub/$rel"] = file
        }
    }

    val entries = mutableListOf<TreeEntry>()
    for ((name, file) in direct) {
        val blobSha = store.write(ObjectType.BLOB, file.bytes)
        entries +=
            TreeEntry(
                mode = if (file.executable) FileMode.EXECUTABLE else FileMode.REGULAR,
                name = name,
                sha = blobSha,
            )
    }
    for ((sub, _) in subdirEntries) {
        val subSha = writeDir(store, if (dirPrefix.isEmpty()) sub else "$dirPrefix/$sub", flat)
        entries += TreeEntry(FileMode.TREE, sub, subSha)
    }
    return store.write(ObjectType.TREE, encodeTree(entries))
}
