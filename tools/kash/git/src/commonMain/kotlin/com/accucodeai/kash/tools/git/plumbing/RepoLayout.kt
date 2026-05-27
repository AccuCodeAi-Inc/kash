package com.accucodeai.kash.tools.git.plumbing

/**
 * Pure path math for a repo at [workTree]. No FS access here — this is
 * a value object that knows where things live so [ObjectStore],
 * [RefStore], and the seed materializer don't repeat string-joining.
 *
 * Bare repos (where the worktree *is* `.git`) are not supported in v1;
 * we always have a separate `<workTree>/.git/` directory.
 */
public class RepoLayout(
    public val workTree: String,
) {
    init {
        require(workTree.startsWith("/")) { "workTree must be absolute, got '$workTree'" }
        require(!workTree.endsWith("/") || workTree == "/") { "workTree must not have trailing slash" }
    }

    public val gitDir: String = if (workTree == "/") "/.git" else "$workTree/.git"
    public val objectsDir: String = "$gitDir/objects"
    public val refsDir: String = "$gitDir/refs"
    public val headsDir: String = "$refsDir/heads"
    public val tagsDir: String = "$refsDir/tags"
    public val remotesDir: String = "$refsDir/remotes"
    public val infoDir: String = "$gitDir/info"
    public val hooksDir: String = "$gitDir/hooks"
    public val logsDir: String = "$gitDir/logs"
    public val packDir: String = "$objectsDir/pack"
    public val headFile: String = "$gitDir/HEAD"
    public val configFile: String = "$gitDir/config"
    public val indexFile: String = "$gitDir/index"
    public val packedRefsFile: String = "$gitDir/packed-refs"
    public val descriptionFile: String = "$gitDir/description"
    public val excludeFile: String = "$infoDir/exclude"

    /** Loose-object path: `.git/objects/aa/bbbb…` (first 2 hex chars, then 38). */
    public fun looseObjectPath(sha: String): String {
        require(sha.length == 40) { "sha must be 40 hex chars" }
        return "$objectsDir/${sha.substring(0, 2)}/${sha.substring(2)}"
    }

    /** Ref file under `.git/`: refs/heads/main → `<gitDir>/refs/heads/main`. */
    public fun refFile(ref: String): String {
        require(ref.startsWith("refs/") || ref == "HEAD") { "ref must start with 'refs/' or be HEAD: '$ref'" }
        return if (ref == "HEAD") headFile else "$gitDir/$ref"
    }

    /** Working-tree path for [relPath]. Joins with [workTree] (no leading slash). */
    public fun workPath(relPath: String): String {
        require(!relPath.startsWith("/")) { "rel path must not start with '/' : '$relPath'" }
        return if (workTree == "/") "/$relPath" else "$workTree/$relPath"
    }
}
