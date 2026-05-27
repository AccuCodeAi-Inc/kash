package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.RefStore

/**
 * Tiny shim used by every porcelain ref-mover so the reflog stays in
 * sync without each call site duplicating the dispatch logic. Picks
 * the right log file(s) based on `refName`:
 *
 *  - `"HEAD"` → logs/HEAD only
 *  - `refs/heads/<X>` → logs/refs/heads/<X> + logs/HEAD if HEAD
 *    currently follows `<X>` symbolically (the "commit on checked-out
 *    branch" path that real git treats as a HEAD reflog event too)
 *  - any other ref (tags, remotes, stash) → just that ref's log
 *
 * Author name/email defaults — if a GitIdentity is available it's
 * used; otherwise the placeholder values match what real git inserts
 * before the user has set user.email.
 */
internal suspend fun recordReflog(
    repo: GitRepo,
    refName: String,
    oldSha: String?,
    newSha: String,
    identity: GitIdentity?,
    whenSeconds: Long,
    tz: String,
    message: String,
) {
    val alsoHead =
        when {
            refName == "HEAD" -> {
                false
            }

            !refName.startsWith("refs/heads/") -> {
                false
            }

            else -> {
                val head = repo.refs.readHead()
                head is RefStore.Head.Symbolic && head.target == refName
            }
        }
    repo.reflog.append(
        refName = refName,
        oldSha = oldSha ?: "0".repeat(40),
        newSha = newSha,
        name = identity?.name ?: "kash",
        email = identity?.email ?: "kash@localhost",
        whenSeconds = whenSeconds,
        tz = tz,
        message = message,
        alsoHead = alsoHead,
    )
}
