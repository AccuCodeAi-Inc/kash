package com.accucodeai.kash.tools.readlink

import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.NotASymlink
import com.accucodeai.kash.fs.Paths

/**
 * Canonicalization existence policy.
 *
 *  - [EXISTING]: every component (including the final) must exist.
 *  - [DEFAULT]: every component except possibly the last must exist.
 *    (matches `readlink -f` / `realpath` default.)
 *  - [MISSING]: no component need exist; missing components are kept verbatim.
 */
internal enum class CanonMode { EXISTING, DEFAULT, MISSING }

/**
 * Canonicalize [path] into an absolute, slash-normalized form.
 *
 *  - Relative inputs are resolved against [cwd].
 *  - `.` / `..` / repeated `/` are collapsed.
 *  - When [followSymlinks] is true, symlinks at any component (subject to
 *    [SYMLOOP_MAX]) are expanded.
 *  - Existence is enforced per [mode]. If a required component is missing,
 *    returns `Result.failure(FileNotFound(componentPath))`.
 *
 * Returns the canonical absolute path on success.
 */
internal fun canonicalize(
    fs: FileSystem,
    path: String,
    cwd: String,
    mode: CanonMode,
    followSymlinks: Boolean,
): Result<String> {
    if (path.isEmpty()) {
        return Result.failure(FileNotFound(""))
    }

    val abs = if (path.startsWith("/")) path else "$cwd/$path"
    // Split into raw segments — we walk component by component so we can
    // intercept symlinks BEFORE the trailing path is normalized away.
    val pending = ArrayDeque<String>()
    abs.split('/').forEach { if (it.isNotEmpty()) pending.addLast(it) }

    val resolved = StringBuilder()
    var symlinkHops = 0

    while (pending.isNotEmpty()) {
        val seg = pending.removeFirst()
        when (seg) {
            "." -> {
                continue
            }

            ".." -> {
                if (resolved.isNotEmpty()) {
                    val lastSlash = resolved.lastIndexOf('/')
                    resolved.setLength(if (lastSlash < 0) 0 else lastSlash)
                }
                continue
            }
        }

        val candidate = resolved.toString() + "/" + seg
        val isLast = pending.isEmpty()

        // Test for existence. We need this for both error policy AND
        // symlink-detection. Use statLink (no-follow) so dangling symlinks
        // are still seen as existing entries.
        val exists =
            try {
                fs.statLink(candidate)
                true
            } catch (_: FileNotFound) {
                false
            } catch (_: Exception) {
                false
            }

        if (!exists) {
            when (mode) {
                CanonMode.EXISTING -> {
                    return Result.failure(FileNotFound(candidate))
                }

                CanonMode.DEFAULT -> {
                    // Last component may be missing; intermediate may not.
                    if (!isLast) return Result.failure(FileNotFound(candidate))
                    resolved.append('/').append(seg)
                    // Remaining pending segments (none here, but be safe) just append.
                    while (pending.isNotEmpty()) {
                        val s = pending.removeFirst()
                        when (s) {
                            "." -> {
                                continue
                            }

                            ".." -> {
                                val lastSlash = resolved.lastIndexOf('/')
                                resolved.setLength(if (lastSlash < 0) 0 else lastSlash)
                            }

                            else -> {
                                resolved.append('/').append(s)
                            }
                        }
                    }
                    return Result.success(if (resolved.isEmpty()) "/" else resolved.toString())
                }

                CanonMode.MISSING -> {
                    resolved.append('/').append(seg)
                    continue
                }
            }
        }

        // Component exists. Is it a symlink we should follow?
        val isSymlink =
            try {
                fs.readSymlink(candidate)
                true
            } catch (_: NotASymlink) {
                false
            } catch (_: Exception) {
                false
            }

        if (!isSymlink || !followSymlinks) {
            resolved.append('/').append(seg)
            continue
        }

        // Follow the symlink: prepend its target (split into segments) to
        // the pending queue. Absolute targets restart from "/".
        symlinkHops++
        if (symlinkHops > SYMLOOP_MAX) {
            return Result.failure(RuntimeException("Too many levels of symbolic links: $candidate"))
        }
        val target =
            try {
                fs.readSymlink(candidate)
            } catch (e: Exception) {
                return Result.failure(e)
            }

        val targetSegments = target.split('/').filter { it.isNotEmpty() }
        if (target.startsWith("/")) {
            resolved.setLength(0)
        }
        // Prepend target segments to pending (in order).
        for (i in targetSegments.indices.reversed()) {
            pending.addFirst(targetSegments[i])
        }
    }

    return Result.success(if (resolved.isEmpty()) "/" else resolved.toString())
}

/**
 * Make [path] relative to [base]. Both must be absolute, normalized.
 * Returns null if [path] is not under [base] AND the caller asked for
 * "only-if-under" semantics (handled by caller, not here).
 */
internal fun makeRelative(
    path: String,
    base: String,
): String {
    if (path == base) return "."
    val pSegs = path.trim('/').split('/').filter { it.isNotEmpty() }
    val bSegs = base.trim('/').split('/').filter { it.isNotEmpty() }
    var common = 0
    while (common < pSegs.size && common < bSegs.size && pSegs[common] == bSegs[common]) common++
    val ups = bSegs.size - common
    val downs = pSegs.drop(common)
    val parts = mutableListOf<String>()
    repeat(ups) { parts += ".." }
    parts += downs
    return if (parts.isEmpty()) "." else parts.joinToString("/")
}

internal fun isUnder(
    path: String,
    base: String,
): Boolean {
    if (path == base) return true
    val b = if (base.endsWith("/")) base else "$base/"
    return path.startsWith(b)
}

/** Normalize an already-absolute path (no symlink following). */
internal fun normalizeAbs(path: String): String = Paths.normalize(path)

private const val SYMLOOP_MAX = 40
