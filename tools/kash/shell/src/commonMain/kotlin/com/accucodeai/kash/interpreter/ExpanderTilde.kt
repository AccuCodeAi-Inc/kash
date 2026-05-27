package com.accucodeai.kash.interpreter

import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart

// Tilde-prefix expansion (~/, ~+, ~-, ~user) extracted from Expander.

/**
 * Tilde-prefix expansion. Operates on the unquoted leading literal of the word
 * (and, in assignment context, on literals immediately after a `:`). A quoted
 * `~` is left alone. Recognized prefixes: `~`, `~/...`, `~+`, `~-`, `~name`.
 */
internal fun Expander.applyTilde(
    word: Word,
    assignmentContext: Boolean,
    argShape: Boolean = false,
): Word {
    if (word.parts.isEmpty()) return word
    val first = word.parts[0]
    if (first !is WordPart.Literal || !first.value.startsWith("~")) {
        if (!assignmentContext) return word
        return rewriteAssignmentTildes(word, argShape = argShape)
    }
    // A tilde-prefix is recognized only when it's bounded by a `/` or by the
    // end of the word's literal head — once an expansion (or escape) follows
    // the `~` without an intervening slash, bash treats the tilde as
    // literal. So `~/foo` and `~user/foo` and `~user` all expand, but
    // `~$USER` and `~\chet/bar` do not.
    val firstSlash = first.value.indexOf('/')
    if (firstSlash < 0 && word.parts.size > 1) {
        val next = word.parts[1]
        val continuesLiteral =
            next is WordPart.Literal || next is WordPart.SingleQuoted ||
                next is WordPart.DoubleQuoted || next is WordPart.Escaped ||
                next is WordPart.ParameterExpansion || next is WordPart.CommandSubstitution ||
                next is WordPart.ArithmeticExpansion
        if (continuesLiteral) {
            // Tilde-prefix is interrupted by either a quoted segment or a
            // dynamic expansion; leave the leading `~` literal.
            return if (assignmentContext) rewriteAssignmentTildes(word, argShape = argShape) else word
        }
    }
    val replaced =
        expandTildePrefix(first.value)
            ?: return if (assignmentContext) rewriteAssignmentTildes(word, argShape = argShape) else word
    val newParts = mutableListOf<WordPart>(WordPart.Literal(replaced))
    newParts.addAll(word.parts.drop(1))
    val out = Word(newParts, word.line)
    return if (assignmentContext) rewriteAssignmentTildes(out, argShape = argShape) else out
}

/**
 * In assignment context, bash extends tilde expansion past the leading
 * position: a tilde-prefix following an unquoted `:` is expanded. In
 * *arg-shape* context (a command argument whose leading literal is
 * `name=...`), tilde-after the assignment-marker `=` is *also* expanded
 * — so `echo FOO=~/x` prints `FOO=/home/user/x`. The `=` rule fires only
 * for the FIRST `=` of an arg-shape word; subsequent or non-arg-shape
 * `=`s remain literal.
 */
internal fun Expander.rewriteAssignmentTildes(
    word: Word,
    argShape: Boolean = false,
): Word {
    val newParts = mutableListOf<WordPart>()
    var argShapeEqOpen = argShape
    // Bash also expands `~` after a `[N]=` bracketed-label in array
    // literals (`a=([0]=~/foo)`). Detect the pattern in the leading
    // literal so the `=~` rule fires there too.
    val firstLit = word.parts.firstOrNull() as? WordPart.Literal
    if (firstLit != null && firstLit.value.startsWith("[")) {
        val close = firstLit.value.indexOf("]=")
        if (close > 0) argShapeEqOpen = true
    }
    for (part in word.parts) {
        if (part !is WordPart.Literal ||
            (
                !part.value.contains(":~") &&
                    !(argShapeEqOpen && part.value.contains("=~")) &&
                    !part.value.startsWith("[~")
            )
        ) {
            newParts += part
            continue
        }
        val text = part.value
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            sb.append(ch)
            val eqBoundary = argShapeEqOpen && ch == '='
            if (eqBoundary) argShapeEqOpen = false
            // Leading tilde-prefix inside an assignment array-element label
            // subscript: `assoc=([~/key]=v)` expands the key's `~` exactly
            // like a word's tilde-prefix, but only when `~` directly follows
            // the label-opening `[`. Bounded by `/`, `:`, or the closing `]`.
            if (ch == '[' && i == 0 && i + 1 < text.length && text[i + 1] == '~') {
                var j = i + 1
                while (j < text.length && text[j] != '/' && text[j] != ':' && text[j] != ']') j++
                val prefix = text.substring(i + 1, j)
                val expanded = expandTildePrefix(prefix)
                sb.append(expanded ?: prefix)
                i = j
                continue
            }
            if ((ch == ':' || eqBoundary) && i + 1 < text.length && text[i + 1] == '~') {
                var j = i + 1
                while (j < text.length && text[j] != ':') j++
                val prefix = text.substring(i + 1, j)
                val expanded = expandTildePrefix(prefix)
                if (expanded != null) sb.append(expanded) else sb.append(prefix)
                i = j
                continue
            }
            i++
        }
        newParts += WordPart.Literal(sb.toString())
    }
    return Word(newParts, word.line)
}

/**
 * Given a string that begins with `~`, return its expansion if the tilde-prefix
 * (the part up to the first `/`) is recognized, plus the rest unchanged. Returns
 * null if the prefix is unknown (so caller leaves the literal alone — bash behavior).
 */
internal fun Expander.expandTildePrefix(s: String): String? {
    if (!s.startsWith("~")) return null
    // Tilde-prefix is delimited by the first `/` or `:`. The `:` boundary
    // covers `~:rest` (assignment-context paths like `PATH=~:$PATH`) so the
    // leading `~` expands even without a slash before the colon.
    val boundary =
        s
            .withIndex()
            .drop(1)
            .firstOrNull { (_, c) -> c == '/' || c == ':' }
            ?.index ?: -1
    val prefix = if (boundary < 0) s else s.substring(0, boundary)
    val rest = if (boundary < 0) "" else s.substring(boundary)

    // Dirstack indices: `~N` and `~+N` from the top (0 = current cwd),
    // `~-N` from the bottom. Only honored when the index is within
    // range AND `pushd` has actually populated the stack — otherwise
    // bash leaves the literal alone (so `~1` stays `~1` in a vanilla
    // shell with no pushes). DIRSTACK[0] is always present (= cwd), so
    // the check is "is the requested index reachable".
    val dirstack = indexedArrays["DIRSTACK"]
    if (dirstack != null) {
        val (sign, digits) =
            when {
                prefix.startsWith("~+") && prefix.length > 2 -> '+' to prefix.substring(2)
                prefix.startsWith("~-") && prefix.length > 2 -> '-' to prefix.substring(2)
                prefix.length > 1 && prefix.substring(1).all { it.isDigit() } -> '+' to prefix.substring(1)
                else -> null to null
            }
        if (digits != null && digits.all { it.isDigit() }) {
            val n = digits.toInt()
            // dstack2 only treats the prefix as a stack reference when
            // we've actually pushed something (size > 1). `~1` with no
            // pushd stays literal.
            if (dirstack.size > 1) {
                val idx = if (sign == '-') dirstack.size - 1 - n else n
                val hit = dirstack[idx]
                if (hit != null) return hit + rest
            }
        }
    }

    // `~0` / `~+0` / `~-0` with no populated dirstack: bash falls back to
    // PWD (the top of an empty dirstack is the cwd). The dirstack branch
    // above only fires when `pushd` has actually populated the stack;
    // here we cover the empty-stack fallthrough.
    val home: String? =
        when (prefix) {
            "~" -> env["HOME"]

            "~+" -> env["PWD"]

            "~-" -> env["OLDPWD"]

            "~0", "~+0", "~-0" -> env["PWD"]

            // `~name`: passwd-style lookup. Unknown user → null so the
            // caller leaves the literal alone (bash behavior).
            else -> userDb.lookup(prefix.substring(1))?.home
        }
    return if (home == null) null else home + rest
}
