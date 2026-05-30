package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.shared.regex.LinearRegex
import com.accucodeai.kash.shared.regex.breToEre
import com.accucodeai.kash.shared.regex.escapeLiteral
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git grep` — search the contents of tracked files for lines matching
 * one or more patterns.
 *
 * Sources (mutually exclusive):
 *  - default: the **working-tree** content of tracked paths (files that
 *    no longer exist on disk are silently skipped, matching real git);
 *  - `--cached`: the **index** blob content;
 *  - `<tree-ish>` (one positional rev, e.g. `HEAD`, a branch, a sha):
 *    that tree's blob content. Output lines gain a `<rev>:` prefix.
 *
 * Flags implemented:
 *  - `-n` / `--line-number`             prefix matches with 1-based line no.
 *  - `-i` / `--ignore-case`             case-insensitive match
 *  - `-l` / `--files-with-matches`      print only matching file names
 *  - `-L` / `--files-without-match`     print only non-matching file names
 *  - `-c` / `--count`                   print `<path>:<count>` (count > 0 only)
 *  - `-w` / `--word-regexp`             match only at word boundaries
 *  - `-v` / `--invert-match`            select non-matching lines
 *  - `-h`                               suppress the file-name prefix
 *  - `-E` / `--extended-regexp`         POSIX ERE (passed through to RE2)
 *  - `-G` / `--basic-regexp`            POSIX BRE (the default; translated to ERE)
 *  - `-F` / `--fixed-strings`           literal substring match
 *  - `-e <pattern>`                     add a pattern (repeatable; OR semantics)
 *  - `--and` / `--or`                   accepted as a no-op separator between
 *                                       `-e` patterns (we always OR them; the
 *                                       single-pattern `--and` form is the
 *                                       common case and behaves identically)
 *  - `--`                               end of options; the rest are pathspecs
 *
 * Exit code: 0 when at least one line matched (or, for `-L`, at least one
 * file printed), 1 when nothing matched, like `grep(1)`.
 *
 * ### Regex flavor
 * Matching goes through `:shared:regex`'s `LinearRegex` — the same RE2-backed,
 * linear-time engine kash's `grep`/`sed` use (never `java.util.regex`, which
 * is ReDoS-prone). Real git's default engine is POSIX **basic** regular
 * expressions (BRE) with the common GNU extensions (`\+`, `\?`, `\|`, `\(\)`,
 * `\<`/`\>`); the shared `breToEre` translator maps that to ERE/RE2 (in BRE
 * `+ ? { } | ( )` are literal unless backslash-escaped — the inverse of ERE).
 * `-E` passes through unchanged; `-F` is escaped as a literal. Remaining gaps
 * vs. glibc: POSIX collating/equivalence classes inside `[. .]` / `[= =]` are
 * not translated, and backreferences are not supported by RE2.
 */
public fun gitGrepSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "grep"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }

            val opts = GrepOptions()
            val patterns = mutableListOf<String>()
            val positionals = mutableListOf<String>()
            var sawDoubleDash = false

            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (sawDoubleDash) {
                    positionals += a
                    i++
                    continue
                }
                when {
                    a == "--" -> {
                        sawDoubleDash = true
                    }

                    a == "-e" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("fatal: switch 'e' requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        patterns += args[i + 1]
                        i++
                    }

                    a.startsWith("-e") && a.length > 2 -> {
                        patterns += a.substring(2)
                    }

                    a == "--and" || a == "--or" -> {}

                    // OR is implicit; no-op separator
                    a == "-n" || a == "--line-number" -> {
                        opts.lineNumber = true
                    }

                    a == "-i" || a == "--ignore-case" -> {
                        opts.ignoreCase = true
                    }

                    a == "-l" || a == "--files-with-matches" -> {
                        opts.filesWithMatches = true
                    }

                    a == "-L" || a == "--files-without-match" -> {
                        opts.filesWithoutMatch = true
                    }

                    a == "-c" || a == "--count" -> {
                        opts.count = true
                    }

                    a == "-w" || a == "--word-regexp" -> {
                        opts.wordRegexp = true
                    }

                    a == "-v" || a == "--invert-match" -> {
                        opts.invert = true
                    }

                    a == "-h" -> {
                        opts.noFilename = true
                    }

                    a == "-E" || a == "--extended-regexp" -> {
                        opts.mode = GrepMode.ERE
                    }

                    a == "-F" || a == "--fixed-strings" -> {
                        opts.mode = GrepMode.FIXED
                    }

                    a == "-G" || a == "--basic-regexp" -> {
                        opts.mode = GrepMode.BRE
                    }

                    a == "--cached" -> {
                        opts.cached = true
                    }

                    a.startsWith("-") && a != "-" -> {
                        // Bundled short flags, e.g. `-ni`, `-il`.
                        if (!a.startsWith("--") && a.length > 1 &&
                            a.drop(1).all { it in "nilLcwvhEFG" }
                        ) {
                            for (c in a.drop(1)) {
                                when (c) {
                                    'n' -> opts.lineNumber = true
                                    'i' -> opts.ignoreCase = true
                                    'l' -> opts.filesWithMatches = true
                                    'L' -> opts.filesWithoutMatch = true
                                    'c' -> opts.count = true
                                    'w' -> opts.wordRegexp = true
                                    'v' -> opts.invert = true
                                    'h' -> opts.noFilename = true
                                    'E' -> opts.mode = GrepMode.ERE
                                    'F' -> opts.mode = GrepMode.FIXED
                                    'G' -> opts.mode = GrepMode.BRE
                                }
                            }
                        } else {
                            ctx.stderr.writeUtf8("error: unknown switch `$a'\n")
                            return CommandResult(exitCode = 129)
                        }
                    }

                    else -> {
                        positionals += a
                    }
                }
                i++
            }

            // If no -e was given, the first bare positional is the pattern.
            if (patterns.isEmpty()) {
                if (positionals.isEmpty()) {
                    ctx.stderr.writeUtf8("fatal: no pattern given\n")
                    return CommandResult(exitCode = 129)
                }
                patterns += positionals.removeAt(0)
            }

            // Separate a single leading tree-ish from the remaining
            // pathspecs. Real git allows multiple <tree-ish>; we support
            // one (the common case) and treat the rest as pathspecs.
            var treeish: String? = null
            val pathspecs = mutableListOf<String>()
            if (!opts.cached) {
                for (p in positionals) {
                    if (treeish == null && resolveRev(repo, p) != null && pathLooksLikeRev(repo, p)) {
                        treeish = p
                    } else {
                        pathspecs += p
                    }
                }
            } else {
                pathspecs += positionals
            }

            val matchers = patterns.map { raw -> buildRegex(raw, opts) }

            // Collect (displayPath, bytes) sources to search.
            val sources: List<Pair<String, ByteArray>> =
                when {
                    treeish != null -> collectTreeSources(repo, treeish, pathspecs)
                    opts.cached -> collectCachedSources(repo, pathspecs)
                    else -> collectWorktreeSources(repo, pathspecs)
                }

            val revPrefix = if (treeish != null) "$treeish:" else ""
            val out = StringBuilder()
            var anyOutput = false

            for ((path, bytes) in sources) {
                val isBinary = bytes.contains(0.toByte())
                val text = bytes.decodeToString()
                // Split into lines preserving git's "last line need not end
                // with \n" semantics. A trailing newline does not create an
                // extra empty line.
                val lines = splitLines(text)

                val matchingLineIndices = mutableListOf<Int>()
                for ((idx, line) in lines.withIndex()) {
                    val matched = matchers.any { it.containsMatch(line) }
                    if (matched != opts.invert) matchingLineIndices += idx
                }

                val hasMatch = matchingLineIndices.isNotEmpty()

                when {
                    opts.filesWithoutMatch -> {
                        if (!hasMatch) {
                            out.append(revPrefix).append(path).append('\n')
                            anyOutput = true
                        }
                    }

                    opts.filesWithMatches -> {
                        if (hasMatch) {
                            out.append(revPrefix).append(path).append('\n')
                            anyOutput = true
                        }
                    }

                    opts.count -> {
                        if (matchingLineIndices.isNotEmpty()) {
                            out
                                .append(revPrefix)
                                .append(path)
                                .append(':')
                                .append(matchingLineIndices.size)
                                .append('\n')
                            anyOutput = true
                        }
                    }

                    isBinary && !opts.invert -> {
                        if (hasMatch) {
                            // Real git prints this to stdout.
                            out
                                .append("Binary file ")
                                .append(revPrefix)
                                .append(path)
                                .append(" matches\n")
                            anyOutput = true
                        }
                    }

                    else -> {
                        for (idx in matchingLineIndices) {
                            if (!opts.noFilename) {
                                out.append(revPrefix).append(path).append(':')
                            }
                            if (opts.lineNumber) {
                                out.append(idx + 1).append(':')
                            }
                            out.append(lines[idx]).append('\n')
                            anyOutput = true
                        }
                    }
                }
            }

            ctx.stdout.writeUtf8(out.toString())
            return CommandResult(exitCode = if (anyOutput) 0 else 1)
        }
    }

private enum class GrepMode { BRE, ERE, FIXED }

private class GrepOptions {
    var lineNumber = false
    var ignoreCase = false
    var filesWithMatches = false
    var filesWithoutMatch = false
    var count = false
    var wordRegexp = false
    var invert = false
    var noFilename = false
    var cached = false
    var mode = GrepMode.BRE
}

/**
 * A positional looks like a rev iff resolving it succeeds AND it is not
 * obviously a path. We keep this conservative: only treat it as a tree-ish
 * when [resolveRev] resolves it. Real git is more nuanced (a name that is
 * both a ref and a path is ambiguous and errors); for kash's purposes,
 * preferring the rev interpretation when it resolves matches the common
 * `git grep <pat> HEAD` and `git grep <pat> <branch>` flows.
 */
private suspend fun pathLooksLikeRev(
    repo: GitRepo,
    spec: String,
): Boolean {
    // A spec that contains a slash and exists as a tracked path is far
    // more likely a pathspec than a rev.
    if (spec.contains('/')) {
        val tracked = repo.readIndex().entries.any { it.path == spec || it.path.startsWith("$spec/") }
        if (tracked) return false
    }
    return true
}

private suspend fun collectWorktreeSources(
    repo: GitRepo,
    pathspecs: List<String>,
): List<Pair<String, ByteArray>> {
    val fs = repo.fs
    val out = mutableListOf<Pair<String, ByteArray>>()
    val paths =
        repo
            .readIndex()
            .entries
            .filter { it.stage == 0 }
            .map { it.path }
            .distinct()
            .sorted()
    for (path in paths) {
        if (!matchesGrepPath(path, pathspecs)) continue
        val abs = repo.layout.workPath(path)
        if (!fs.exists(abs) || fs.isDirectory(abs)) continue // deleted/typechange → skip
        out += path to fs.readBytes(abs)
    }
    return out
}

private suspend fun collectCachedSources(
    repo: GitRepo,
    pathspecs: List<String>,
): List<Pair<String, ByteArray>> {
    val out = mutableListOf<Pair<String, ByteArray>>()
    val entries =
        repo
            .readIndex()
            .entries
            .filter { it.stage == 0 }
            .sortedBy { it.path }
    for (e in entries) {
        if (!matchesGrepPath(e.path, pathspecs)) continue
        if (e.mode == FileMode.GITLINK) continue
        out += e.path to repo.objects.readBlob(e.sha)
    }
    return out
}

private suspend fun collectTreeSources(
    repo: GitRepo,
    treeish: String,
    pathspecs: List<String>,
): List<Pair<String, ByteArray>> {
    val sha = resolveRev(repo, treeish) ?: return emptyList()
    val treeSha =
        try {
            repo.objects.readCommit(sha).tree
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            sha // already a tree
        }
    val flat = flattenTree(repo, treeSha)
    val out = mutableListOf<Pair<String, ByteArray>>()
    for (path in flat.keys.sorted()) {
        if (!matchesGrepPath(path, pathspecs)) continue
        out += path to flat.getValue(path).bytes
    }
    return out
}

/** Pathspec narrowing: exact match or directory-prefix, like git grep. */
private fun matchesGrepPath(
    path: String,
    pathspecs: List<String>,
): Boolean {
    if (pathspecs.isEmpty()) return true
    for (raw in pathspecs) {
        val p = raw.trimEnd('/')
        if (p.isEmpty()) return true
        if (path == p) return true
        if (path.startsWith("$p/")) return true
    }
    return false
}

/**
 * Split [text] into lines for matching. A single trailing newline does
 * not produce a trailing empty line; an empty file yields no lines.
 * Both `\n` and `\r\n` terminate a line (the `\r` is dropped from the
 * matched text, matching real git which strips the trailing CR).
 */
private fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val raw = text.split('\n')
    // If the text ends with '\n', `split` produces a trailing "" we drop.
    val trimmed = if (text.endsWith('\n')) raw.dropLast(1) else raw
    return trimmed.map { if (it.endsWith('\r')) it.dropLast(1) else it }
}

/**
 * Compile one pattern under [opts] into a [LinearRegex] (the shared RE2-backed
 * engine — same matcher kash's `grep`/`sed` use). FIXED escapes the pattern as
 * a literal, BRE is translated to ERE via the shared [breToEre], ERE passes
 * through. `-w` wraps the pattern in word boundaries; `-i` sets the `i` flag.
 * A malformed pattern falls back to a literal match rather than throwing.
 */
private fun buildRegex(
    raw: String,
    opts: GrepOptions,
): LinearRegex {
    val core =
        when (opts.mode) {
            GrepMode.FIXED -> escapeLiteral(raw)
            GrepMode.ERE -> raw
            GrepMode.BRE -> breToEre(raw)
        }
    val pattern = if (opts.wordRegexp) "(?:\\b(?:$core)\\b)" else core
    val flags = if (opts.ignoreCase) "i" else ""
    return try {
        LinearRegex(pattern, flags)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        // Last-ditch: treat as literal so a malformed pattern still produces
        // deterministic behavior rather than crashing.
        LinearRegex(escapeLiteral(raw), flags)
    }
}
