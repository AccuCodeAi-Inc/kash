package com.accucodeai.kash.tools.realpath

import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.NotASymlink
import com.accucodeai.kash.fs.Paths

internal enum class CanonMode { EXISTING, DEFAULT, MISSING }

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
                // DEFAULT is strict: any missing component (including the final
                // one) is an error. Scripts that do `target=$(realpath "$x") ||
                // die` rely on this. To allow missing components, pass `-m`.
                // GNU realpath's default permits a missing final component;
                // kash deliberately deviates here for script safety.
                CanonMode.EXISTING, CanonMode.DEFAULT -> {
                    return Result.failure(FileNotFound(candidate))
                }

                CanonMode.MISSING -> {
                    resolved.append('/').append(seg)
                    continue
                }
            }
        }

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
        for (i in targetSegments.indices.reversed()) {
            pending.addFirst(targetSegments[i])
        }
    }

    return Result.success(if (resolved.isEmpty()) "/" else resolved.toString())
}

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

internal fun normalizeAbs(path: String): String = Paths.normalize(path)

private const val SYMLOOP_MAX = 40
