package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.lcsMatches
import kotlinx.datetime.format.parse
import kotlinx.datetime.toLocalDateTime

/**
 * `git blame <path>` — for each line of the file at HEAD (or the
 * given revspec), find the commit that last touched it. Walks
 * first-parent history; for each commit that modified the blob we
 * align lines via LCS and propagate attribution forward, so a line
 * "carried through" several commits is attributed to the one that
 * actually changed it.
 *
 * Output format (human-readable, matches real git's default closely):
 *
 *   <8-char sha> (<author> <YYYY-MM-DD HH:MM:SS> <±tz> <lineno>) <line>
 *
 * v1 ignores `-p` / `--porcelain`, `-L` (line-range), `-C` (cross-file
 * copy detection). They're real-git ergonomics; the underlying walk
 * already supports the data they need.
 */
public fun gitBlameSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "blame"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }

            var revspec: String? = null
            var path: String? = null
            for (a in args) {
                when {
                    a == "--" -> {}

                    a.startsWith("-") -> {
                        // accept-and-ignore for v1
                    }

                    revspec == null && a.matches(Regex("[0-9a-fA-F]{4,40}|HEAD|HEAD[~^].*|refs/.*")) -> {
                        revspec = a
                    }

                    path == null -> {
                        path = a
                    }

                    else -> {
                        ctx.stderr.writeUtf8("git blame: too many positional args\n")
                        return CommandResult(exitCode = 129)
                    }
                }
            }
            if (path == null) {
                ctx.stderr.writeUtf8("git blame: pathspec required\n")
                return CommandResult(exitCode = 129)
            }
            val startSha =
                resolveRev(repo, revspec ?: "HEAD") ?: run {
                    ctx.stderr.writeUtf8("fatal: bad revision '${revspec ?: "HEAD"}'\n")
                    return CommandResult(exitCode = 128)
                }

            // Step 1: collect the chain of commits (first-parent) where
            // the file's blob differs from its parent's. Oldest first.
            if (blobShaAt(repo, startSha, path) == null) {
                ctx.stderr.writeUtf8("fatal: no such path $path in HEAD\n")
                return CommandResult(exitCode = 128)
            }
            val touching = mutableListOf<String>()
            var c: String? = startSha
            while (c != null) {
                val commit = repo.objects.readCommit(c)
                val curBlob = blobShaAt(repo, c, path)
                val parent = commit.parents.firstOrNull()
                val parentBlob = if (parent != null) blobShaAt(repo, parent, path) else null
                if (curBlob != null && curBlob != parentBlob) touching += c
                if (curBlob == null) break
                c = parent
            }
            touching.reverse() // oldest first

            if (touching.isEmpty()) {
                ctx.stderr.writeUtf8("fatal: no such path $path in HEAD\n")
                return CommandResult(exitCode = 128)
            }

            // Step 2: walk forward, propagating attribution.
            var liveLines = linesAt(repo, touching[0], path)
            var liveAttr = MutableList(liveLines.size) { touching[0] }

            for (i in 1 until touching.size) {
                val next = touching[i]
                val nextLines = linesAt(repo, next, path)
                val matches = lcsMatches(liveLines, nextLines)
                val newAttr = MutableList(nextLines.size) { next }
                for ((leftIdx, rightIdx) in matches) {
                    newAttr[rightIdx] = liveAttr[leftIdx]
                }
                liveLines = nextLines
                liveAttr = newAttr
            }

            // Step 3: emit.
            val cache = mutableMapOf<String, AuthorStamp>()
            for ((idx, line) in liveLines.withIndex()) {
                val sha = liveAttr[idx]
                val stamp =
                    cache.getOrPut(sha) {
                        val cm = repo.objects.readCommit(sha)
                        AuthorStamp(cm.author.name, cm.author.whenSeconds, cm.author.tz)
                    }
                val date = epochToIso(stamp.whenSeconds, stamp.tz)
                val lineno = idx + 1
                ctx.stdout.writeUtf8(
                    "${sha.substring(0, 8)} (${stamp.name.padEnd(10).take(20)} $date ${stamp.tz} " +
                        "${lineno.toString().padStart(3)}) $line\n",
                )
            }
            return CommandResult(exitCode = 0)
        }
    }

private class AuthorStamp(
    val name: String,
    val whenSeconds: Long,
    val tz: String,
)

/** Resolve `path` to a blob sha at `commit`. Null when the file isn't in the tree. */
private suspend fun blobShaAt(
    repo: GitRepo,
    commitSha: String,
    path: String,
): String? {
    val rootTree = repo.objects.readCommit(commitSha).tree
    var curTree = rootTree
    val parts = path.split('/')
    for ((i, part) in parts.withIndex()) {
        val entries = repo.objects.readTree(curTree)
        val match = entries.firstOrNull { it.name == part } ?: return null
        if (i == parts.lastIndex) {
            return if (match.mode == FileMode.TREE) null else match.sha
        }
        if (match.mode != FileMode.TREE) return null
        curTree = match.sha
    }
    return null
}

/** Read the lines of `path` at `commit` (split on LF, no trailing-newline preservation). */
private suspend fun linesAt(
    repo: GitRepo,
    commitSha: String,
    path: String,
): List<String> {
    val sha = blobShaAt(repo, commitSha, path) ?: return emptyList()
    val bytes = repo.objects.readBlob(sha)
    val text = bytes.decodeToString()
    if (text.isEmpty()) return emptyList()
    val body = if (text.endsWith("\n")) text.substring(0, text.length - 1) else text
    return body.split('\n')
}

/**
 * Format `epoch + tz-string` as `YYYY-MM-DD HH:MM:SS` in the commit's
 * stored tz. Built on kotlinx-datetime — same `Instant.toLocalDateTime`
 * path that `git log`, `date`, and `ls -l` all use.
 */
private fun epochToIso(
    epoch: Long,
    tz: String,
): String {
    val offset =
        runCatching {
            kotlinx.datetime.UtcOffset.Formats.FOUR_DIGITS
                .parse(tz)
        }.getOrDefault(kotlinx.datetime.UtcOffset.ZERO)
    val ldt =
        kotlin.time.Instant
            .fromEpochSeconds(epoch)
            .toLocalDateTime(kotlinx.datetime.FixedOffsetTimeZone(offset))
    val y = ldt.year.toString().padStart(4, '0')
    val mo = (ldt.month.ordinal + 1).toString().padStart(2, '0')
    val d = ldt.day.toString().padStart(2, '0')
    val h = ldt.hour.toString().padStart(2, '0')
    val mi = ldt.minute.toString().padStart(2, '0')
    val s = ldt.second.toString().padStart(2, '0')
    return "$y-$mo-$d $h:$mi:$s"
}
