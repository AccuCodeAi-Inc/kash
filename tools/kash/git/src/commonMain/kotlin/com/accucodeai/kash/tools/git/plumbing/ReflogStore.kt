package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.fs.FileSystem

/**
 * On-disk reflog at `.git/logs/<ref>` plus `.git/logs/HEAD`. Each line:
 *
 * ```
 * <40-old> <40-new> <name> <email> <unix-ts> <tz>\t<message>\n
 * ```
 *
 * Creation events use 40 zeros for `<old>`. Real git also writes a
 * reflog for HEAD whenever the *currently checked-out branch's* tip
 * moves (because HEAD symbolically follows it). We mirror that: any
 * write to `refs/heads/<X>` *also* appends to logs/HEAD when HEAD
 * points symbolically at `<X>`.
 *
 * Reads are line-by-line and tolerant — malformed lines are skipped
 * silently rather than failing the read.
 */
public class ReflogStore(
    public val layout: RepoLayout,
    public val fs: FileSystem,
) {
    public data class Entry(
        val oldSha: String,
        val newSha: String,
        val name: String,
        val email: String,
        val whenSeconds: Long,
        val tz: String,
        val message: String,
    )

    /**
     * Append one entry to the log file for [refName]. If [alsoHead] is
     * true and `refName` differs from `"HEAD"`, also writes to
     * `logs/HEAD` (the typical "commit on the current branch" path).
     */
    public suspend fun append(
        refName: String,
        oldSha: String,
        newSha: String,
        name: String,
        email: String,
        whenSeconds: Long,
        tz: String,
        message: String,
        alsoHead: Boolean,
    ) {
        val line =
            buildString {
                append(oldSha.padStart(40, '0'))
                append(' ')
                append(newSha.padStart(40, '0'))
                append(' ')
                append(name)
                append(' ')
                append('<').append(email).append('>')
                append(' ')
                append(whenSeconds)
                append(' ')
                append(tz)
                append('\t')
                append(message.trimEnd('\n'))
                append('\n')
            }
        appendOne(logPath(refName), line)
        if (alsoHead && refName != "HEAD") appendOne(logPath("HEAD"), line)
    }

    /** Read entries for [refName] in chronological (oldest-first) order. */
    public suspend fun read(refName: String): List<Entry> {
        val path = logPath(refName)
        if (!fs.exists(path)) return emptyList()
        val text = fs.readBytes(path).decodeToString()
        val out = mutableListOf<Entry>()
        for (raw in text.lineSequence()) {
            if (raw.isEmpty()) continue
            val tab = raw.indexOf('\t')
            if (tab < 0) continue
            val head = raw.substring(0, tab)
            val msg = raw.substring(tab + 1)
            val parts = head.split(' ')
            if (parts.size < 6) continue
            val oldSha = parts[0]
            val newSha = parts[1]
            // Email is bracketed: walk back from the end to find <…>.
            val rest = head.substring(82) // 40 + 1 + 40 + 1 = 82
            val emailEnd = rest.lastIndexOf('>')
            val emailStart = rest.lastIndexOf('<')
            if (emailEnd < 0 || emailStart < 0 || emailStart > emailEnd) continue
            val name = rest.substring(0, emailStart).trim()
            val email = rest.substring(emailStart + 1, emailEnd)
            val tail = rest.substring(emailEnd + 2).split(' ')
            if (tail.size < 2) continue
            val whenSec = tail[0].toLongOrNull() ?: continue
            val tz = tail[1]
            out += Entry(oldSha, newSha, name, email, whenSec, tz, msg)
        }
        return out
    }

    private fun logPath(refName: String): String =
        if (refName == "HEAD") "${layout.logsDir}/HEAD" else "${layout.logsDir}/$refName"

    private suspend fun appendOne(
        path: String,
        line: String,
    ) {
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        if (!fs.exists(parent)) fs.mkdirs(parent)
        val existing = if (fs.exists(path)) fs.readBytes(path) else ByteArray(0)
        val out = ByteArray(existing.size + line.length)
        existing.copyInto(out)
        line.encodeToByteArray().copyInto(out, existing.size)
        fs.writeBytes(path, out)
    }
}
