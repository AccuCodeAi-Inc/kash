package com.accucodeai.kash.fs

// Path-hygiene helpers for importing externally-named files (drag-and-drop,
// uploads) into a kash FileSystem. Lives here rather than in the web UI
// because it's pure filesystem-path logic with no UI dependency, and so it
// can be unit-tested against InMemoryFs on the fast JVM test target.

/**
 * Normalize a host filename for use inside a kash fs. Produces a
 * shell-clean name: only `[A-Za-z0-9._-]` survive — spaces and shell
 * metacharacters collapse to `_`. This matters because an imported path
 * is often pasted onto the command line; `ctf (1).zip` would force the
 * caller to quote, while `ctf_1.zip` is a bare word.
 *
 * Also strips path separators (so a drop of `../sneaky.txt` can't escape
 * its parent directory), collapses runs of `_`, trims leading dots, and
 * falls back to `"drop"` for empty / all-punctuation names.
 */
public fun sanitizeDropName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned =
        buildString {
            for (ch in base) {
                val keep = ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_'
                append(if (keep) ch else '_')
            }
        }.replace(Regex("_+"), "_") // collapse runs from consecutive metachars
            .replace(Regex("_*\\._*"), ".") // drop underscores hugging a dot: "ctf_1_.zip" -> "ctf_1.zip"
            .replace(Regex("\\.+"), ".") // collapse any dot runs the above could expose
            .trim('_')
            .trim('.')
    return cleaned.ifEmpty { "drop" }
}

/**
 * Pick an unused path under [dir] for [name], appending `_1`, `_2` …
 * before the extension on collision. Caller has already sanitized the
 * name via [sanitizeDropName]. The suffix is space/paren-free so the
 * resulting path is a bare shell word — no quoting needed when it's
 * pasted onto the command line.
 */
public fun uniqueDropPath(
    fs: FileSystem,
    dir: String,
    name: String,
): String {
    val base = if (dir.endsWith("/")) dir else "$dir/"
    val direct = "$base$name"
    if (!fs.exists(direct)) return direct
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (true) {
        val candidate = "$base${stem}_$i$ext"
        if (!fs.exists(candidate)) return candidate
        i++
    }
}
