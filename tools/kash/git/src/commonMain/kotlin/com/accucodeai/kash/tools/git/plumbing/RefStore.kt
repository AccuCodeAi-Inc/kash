package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.fs.FileSystem

/**
 * Reader/writer for `.git/HEAD` and the `.git/refs/` tree. Loose refs
 * only — packed-refs is read-aware ([resolve] consults it) but writes
 * always land as loose ref files, matching real-git's lazy behavior.
 * `git pack-refs` is a later operation that will rewrite the packed
 * file.
 */
public class RefStore(
    public val layout: RepoLayout,
    public val fs: FileSystem,
) {
    /**
     * Logical HEAD. Either:
     *  - [Symbolic] — `HEAD` is `"ref: <target>\n"`. [target] is e.g.
     *    `"refs/heads/main"`. Calling [resolveHead] follows it.
     *  - [Detached] — `HEAD` is a 40-hex sha + LF. Points straight at a
     *    commit; no implicit branch.
     */
    public sealed interface Head {
        public data class Symbolic(
            val target: String,
        ) : Head

        public data class Detached(
            val sha: String,
        ) : Head
    }

    public suspend fun readHead(): Head? {
        if (!fs.exists(layout.headFile)) return null
        val s = fs.readBytes(layout.headFile).decodeToString().trimEnd('\n', '\r')
        return if (s.startsWith("ref: ")) Head.Symbolic(s.substring(5)) else Head.Detached(s)
    }

    public suspend fun writeHeadSymbolic(target: String) {
        require(target.startsWith("refs/")) { "symbolic HEAD target must start with 'refs/': '$target'" }
        fs.mkdirs(layout.gitDir)
        fs.writeBytes(layout.headFile, "ref: $target\n".encodeToByteArray())
    }

    public suspend fun writeHeadDetached(sha: String) {
        require(sha.length == 40) { "HEAD sha must be 40 hex chars" }
        fs.mkdirs(layout.gitDir)
        fs.writeBytes(layout.headFile, "$sha\n".encodeToByteArray())
    }

    /** Read the sha a loose ref file points at; null if the file doesn't exist. */
    public suspend fun readLooseRef(ref: String): String? {
        val path = layout.refFile(ref)
        if (!fs.exists(path)) return null
        return fs.readBytes(path).decodeToString().trimEnd('\n', '\r')
    }

    public suspend fun writeRef(
        ref: String,
        sha: String,
    ) {
        require(sha.length == 40) { "ref sha must be 40 hex chars" }
        val path = layout.refFile(ref)
        fs.mkdirs(path.substringBeforeLast('/'))
        fs.writeBytes(path, "$sha\n".encodeToByteArray())
    }

    /**
     * Resolve [ref] (loose, then packed-refs) to a commit sha. Returns
     * null if the ref doesn't exist locally. Does NOT follow symbolic
     * HEAD — call [resolveHead] for that.
     */
    public suspend fun resolve(ref: String): String? {
        readLooseRef(ref)?.let { return it }
        return readPackedRef(ref)
    }

    /** Resolve HEAD all the way to a commit sha. */
    public suspend fun resolveHead(): String? =
        when (val h = readHead()) {
            null -> null
            is Head.Detached -> h.sha
            is Head.Symbolic -> resolve(h.target)
        }

    /**
     * Parse `packed-refs` and return the sha for [ref], or null. We
     * don't cache — the file is small in v1 (no `pack-refs` writes yet).
     * Ignores peeled annotated-tag lines (`^<sha>`).
     */
    public suspend fun readPackedRef(ref: String): String? {
        if (!fs.exists(layout.packedRefsFile)) return null
        val text = fs.readBytes(layout.packedRefsFile).decodeToString()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("^")) continue
            val sp = line.indexOf(' ')
            if (sp != 40) continue
            val sha = line.substring(0, sp)
            val name = line.substring(sp + 1)
            if (name == ref) return sha
        }
        return null
    }

    public suspend fun listRefs(prefix: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val root = "${layout.gitDir}/$prefix"
        if (fs.exists(root) && fs.isDirectory(root)) {
            walk(root, prefix, out)
        }
        if (fs.exists(layout.packedRefsFile)) {
            val text = fs.readBytes(layout.packedRefsFile).decodeToString()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("^")) continue
                val sp = line.indexOf(' ')
                if (sp != 40) continue
                val name = line.substring(sp + 1)
                if (name.startsWith(prefix)) {
                    val sha = line.substring(0, sp)
                    // Loose wins; only add if not already present.
                    if (out.none { it.first == name }) out += name to sha
                }
            }
        }
        return out
    }

    private suspend fun walk(
        dir: String,
        prefix: String,
        out: MutableList<Pair<String, String>>,
    ) {
        for (name in fs.list(dir)) {
            val full = "$dir/$name"
            if (fs.isDirectory(full)) {
                walk(full, "$prefix/$name", out)
            } else {
                val sha = fs.readBytes(full).decodeToString().trimEnd('\n', '\r')
                if (sha.length == 40) out += "$prefix/$name" to sha
            }
        }
    }
}
