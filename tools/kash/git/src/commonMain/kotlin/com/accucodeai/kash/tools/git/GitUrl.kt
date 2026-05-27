package com.accucodeai.kash.tools.git

/**
 * Canonicalize a clone URL so it points at the smart-HTTP endpoint.
 *
 * Users routinely paste GitHub-style web URLs (PR pages, file viewers,
 * tree URLs) into `git clone` — real git rejects those, but a
 * shell-for-LLMs shouldn't punish "I copied this from my browser tab".
 *
 * Rules:
 *  - Strip GitHub-style web fragments (`/pull/N`, `/tree/...`,
 *    `/blob/...`, `/commit/...`, `/issues/N`, `/wiki/...`). Only
 *    applied for known forge hosts; self-hosted servers may legitimately
 *    have repo paths containing `/tree/` etc.
 *  - Strip trailing slash.
 *  - For known forge hosts without `.git`, append `.git`.
 *
 * Shared across host driver implementations:
 *  - `HttpGitHostAdapter` (wasm + JVM, smart-HTTP path) — calls it
 *    inside `SmartHttpFetch.fetch()`.
 *  - `JGitHostAdapter` (JVM, JGit Transport path) — calls it before
 *    handing the URL to `Transport.open(...)`.
 *
 * Hosts not in [KNOWN_FORGE_HOSTS] pass through unchanged.
 */
public fun canonicalizeGitUrl(url: String): String {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    val host =
        if (schemeEnd < 0) {
            // SCP-style or unparseable — pass through.
            return trimmed.trimEnd('/').let { if (looksKnownForgeWithoutGit(it)) "$it.git" else it }
        } else {
            val rest = trimmed.substring(schemeEnd + 3)
            val pathStart = rest.indexOf('/')
            val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
            authority.substringAfter('@', authority).substringBefore(':').lowercase()
        }
    val isKnownForge = isKnownForgeHost(host)

    var u = trimmed
    if (isKnownForge) {
        for (seg in WEB_FRAGMENT_SEGMENTS) {
            val at = u.indexOf(seg)
            if (at >= 0) {
                u = u.substring(0, at)
                break
            }
        }
    }
    u = u.trimEnd('/')
    if (u.endsWith(".git")) return u
    return if (isKnownForge) "$u.git" else u
}

private val WEB_FRAGMENT_SEGMENTS =
    listOf("/pull/", "/tree/", "/blob/", "/commit/", "/issues/", "/wiki/", "/-/merge_requests/")

/** Hosts whose smart-HTTP endpoints conventionally live at `<path>.git/`. */
private val KNOWN_FORGE_HOSTS =
    setOf(
        "github.com",
        "gitlab.com",
        "bitbucket.org",
        "codeberg.org",
    )

private fun isKnownForgeHost(host: String): Boolean = host in KNOWN_FORGE_HOSTS || host.endsWith(".sr.ht")

/**
 * Tool-module-friendly alias for the API-level [GitFetchScope]
 * [com.accucodeai.kash.api.git.GitFetchScope]. Same data class —
 * just a shorter name when imported from `:tools:kash:git`-scoped
 * code. Both host adapters consume this type:
 *  - `HttpGitHostAdapter` translates it into smart-HTTP `deepen <N>`
 *    and `filter <spec>` pkt-lines.
 *  - `JGitHostAdapter` translates it into `Transport.setDepth(int)`
 *    and `Transport.setFilterSpec(FilterSpec.…)` calls.
 */
public typealias FetchScope = com.accucodeai.kash.api.git.GitFetchScope

private fun looksKnownForgeWithoutGit(url: String): Boolean {
    // Bare SCP-style fallback: `git@github.com:user/repo` → assume
    // user knows what they're doing; only add .git if obviously
    // missing AND the host is recognized.
    val at = url.indexOf('@')
    val colon = if (at >= 0) url.indexOf(':', at) else -1
    if (at < 0 || colon < 0) return false
    val host = url.substring(at + 1, colon).lowercase()
    if (!isKnownForgeHost(host)) return false
    return !url.endsWith(".git")
}
