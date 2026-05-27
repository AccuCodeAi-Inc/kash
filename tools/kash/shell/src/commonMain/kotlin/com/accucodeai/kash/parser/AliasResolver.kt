package com.accucodeai.kash.parser

/**
 * Read-only view of the interpreter's runtime alias table, passed into the
 * parser so the lexer-stage alias-substitution pass can run without taking
 * a dependency on the interpreter package.
 *
 * POSIX §2.3.1 alias substitution is implemented in [Lexer] using a
 * character-level input stack (bash `alias-input-stack push`/`alias-input-stack pop` mechanics
 * — see `Lexer.Segment`). When a command-name WordTok resolves to an
 * alias, the body is pushed as a new input segment so subsequent reads
 * draw from it; quote contexts that don't close before body-EOF transparently
 * resume in the outer source.
 */
public fun interface AliasResolver {
    public fun lookup(name: String): String?

    public companion object {
        public val Empty: AliasResolver = AliasResolver { null }
    }
}

/**
 * Bash-permissive alias name: non-empty, no whitespace, no shell
 * metacharacters from the set `/ = $ ` " ' \ | & ;  < > { } [ ] # * ?`.
 * Reserved-word names are *accepted* (matching bash's default mode); they
 * are simply unreachable at command-name position because the lexer emits
 * them as `Token.Keyword` rather than `Token.WordTok`, so the substitution
 * pass never sees them there.
 *
 * Shared between the parse-time substitution pass and the runtime
 * `alias`/`BASH_ALIASES[...]=...` writers; do not fork.
 */
internal fun isValidAliasName(name: String): Boolean {
    if (name.isEmpty()) return false
    for (c in name) {
        if (c.isWhitespace()) return false
        when (c) {
            '/', '=', '$', '`', '"', '\'', '\\',
            '|', '&', ';', '(', ')', '<', '>',
            '{', '}', '[', ']', '#', '*', '?',
            -> return false
        }
    }
    return true
}

/**
 * Shell identifier: leading letter or `_`, followed by letters/digits/`_`.
 * Used for variable names, function names, and for-loop variables. Stricter
 * than [isValidAliasName], which allows digits and dashes anywhere.
 */
internal fun isShellIdentifier(name: String): Boolean {
    if (name.isEmpty()) return false
    val first = name[0]
    if (!first.isLetter() && first != '_') return false
    for (i in 1 until name.length) {
        val c = name[i]
        if (!c.isLetterOrDigit() && c != '_') return false
    }
    return true
}
