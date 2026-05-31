package com.accucodeai.kash.parser

import com.accucodeai.kash.api.ansi.Ansi

/**
 * Hand-rolled bash lexer.
 *
 * Scope: literals, single/double quotes, backslash escapes, parameter
 * expansion (`$NAME`, `${NAME}`, `$?`, `$1`…`$9`, `$@`, `$#`, `$*`),
 * `NAME=value` assignment tokens at command position, reserved words at
 * command position, control operators (`;`, `&`, `&&`, `||`, `|`), and
 * redirection operators (`<`, `>`, `>>`, `>|`, `<>`, `&>`, `&>>`, `<&`,
 * `>&`, `<<<`, `<<`, `<<-`) with optional leading FD digit. Heredoc bodies
 * are captured inline at the next newline.
 *
 * Not yet here: command substitution `$()`/backticks, arithmetic `$(())`.
 * (Brace expansion is handled at expansion time as a pre-pass over the
 * AST in [ExpanderBrace]; process substitution `<(...)`/`>(...)` is a
 * word-level chunk recognized by [readProcessSubstitution].)
 */
internal class Lexer(
    rootSource: String,
    private val aliasResolver: AliasResolver = AliasResolver.Empty,
    /**
     * POSIX mode (`sh -o posix`). When true, reserved words are never
     * alias-substituted, and `alias NAME=value` definitions where NAME is
     * a reserved word are silently dropped from the parse-time overlay.
     * Matches bash's `set -o posix` semantics for alias handling.
     */
    private val posixMode: Boolean = false,
    /**
     * Initial value of the source-line counter. Defaults to 1 for a fresh
     * top-level parse; deferred `$(...)`/backtick re-parses pass the
     * outer-source line where the `$(` / `` ` `` appeared so that every
     * token, statement, and runtime diagnostic produced from the body
     * carries the correct outer-source line — mirrors zsh's
     * `lineno_save` / `lineno_restore` around inner `parse_in_string`
     * calls. Without this, `command not found` / `LINENO` / arith errors
     * inside a multi-line cmdsub body would point at body-relative lines.
     */
    startLine: Int = 1,
    /**
     * Whether extglob pattern prefixes (`?(`, `*(`, `+(`, `@(`, `!(`)
     * are recognized as extglob at lex time. Bash gates these on
     * `shopt -s extglob` (default off in non-interactive bash); kash
     * defaults this to true to preserve its historical always-on
     * behavior. Wired through [com.accucodeai.kash.parser.StatementStream]
     * so a mid-script `shopt -s extglob` / `shopt -u extglob` toggle
     * affects subsequent statements' lex — same mechanism as
     * [posixMode] re-lex invalidation.
     */
    private val extglobEnabled: Boolean = true,
) {
    /**
     * Frame in the input stack. A fresh Lexer starts with a single root
     * [Segment] pointing at the script source. Alias substitution (G8) will
     * push the alias body as a new segment so the lexer's character cursor
     * reads body chars first, then transparently resumes in the outer
     * source when the body exhausts — that's how bash's
     * `alias foo="echo 'Error:"; foo bar'` works: the body's open `'`
     * stays unclosed at body-EOF, then pairs with the next `'` in the
     * surrounding source.
     *
     * Bash equivalent: `alias-segment frame` in `` (`alias-input-stack push` /
     * `alias-input-stack pop`); our `source`/`pos`/`expanding` fields map to bash's
     * `shell_input_line` / `shell_input_line_index` / alias pointer.
     */
    private data class Segment(
        val source: String,
        var pos: Int,
        /** Alias name being expanded by this segment (null for the
         *  root). POSIX rule 5's recursion guard checks active frames. */
        val expanding: String? = null,
        /** POSIX rule 4: if the alias body's source ends in whitespace,
         *  the *next* command word after the body is consumed must also
         *  be checked for alias expansion. We carry the arming on the
         *  segment so the auto-pop sets the flag at exactly the right
         *  moment (when body chars have been fully consumed). */
        val chainAfter: Boolean = false,
    )

    private val stack: ArrayDeque<Segment> = ArrayDeque()

    /**
     * Top-of-stack cache. Every char read in the hot lexer loop goes through
     * [source] and [pos]; routing through `stack.first()` on each access
     * burns two ArrayDeque-head reads per character. Cache the top frame in
     * a plain field and update it only at the two mutation sites
     * ([pushAliasBody], [popExhausted]) — the no-aliases common case (single
     * root frame) then never touches the deque after init.
     */
    private var top: Segment = Segment(rootSource, 0).also { stack.addFirst(it) }

    /** Convenience accessors so existing call sites keep using `source[pos]`
     *  and `pos++` patterns. They route to the top of the segment stack
     *  (via the cached [top]). Quote contexts that don't close before
     *  body-EOF transparently resume in the outer source: a `'` opened in
     *  body, body-EOF reached, [popExhausted] fires inside
     *  `readSingleQuoted`'s next loop iteration, next char read is from
     *  outer source and the closing `'` is paired.
     *
     *  Bash equivalent: `shell_getc` returning the next char after a
     *  `alias-input-stack pop`.
     */
    private val source: String get() = top.source
    private var pos: Int
        get() = top.pos
        set(value) {
            top.pos = value
        }

    /**
     * Pop any exhausted *non-root* segments. Sites that check
     * `pos < source.length` or read `source[pos]` must call this *first*
     * so the cursor transparently sees the outer segment when the top
     * body has been fully consumed.
     *
     * Critically, pop is *lazy* — not triggered by the pos setter.
     * Bash's POSIX rule 5 recursion guard via [isExpanding] consults the
     * active stack frames, and the just-produced word from the body's
     * last chars must still see its parent alias as active. If we
     * eagerly popped on the final `pos++` of a body, by the time the
     * substitution check on the produced word ran, the parent alias
     * would no longer be in the stack — letting cycles like
     * `qfoo→qbar→qbaz→quux→qfoo` re-fire forever.
     */
    private fun popExhausted() {
        while (stack.size > 1 && top.pos >= top.source.length) {
            val popped = stack.removeFirst()
            top = stack.first()
            if (popped.chainAfter) activeState?.chainNextWord = true
        }
    }

    /** True if any input remains (any non-root segment has chars OR the
     *  root segment has chars). Pops exhausted bodies as a side effect. */
    private fun hasMoreInput(): Boolean {
        popExhausted()
        return top.pos < top.source.length
    }

    private var line = startLine

    /**
     * Per-token root-source end positions, parallel to the token list
     * produced by [tokenize]. After emitting token i, `tokenEndsBuf[i]`
     * holds the root-source character index immediately AFTER the
     * last character that contributed to the token. Used by the
     * per-statement [com.accucodeai.kash.parser.StatementStream] V2 to
     * advance a source-character cursor after `parser.statement()`
     * consumes a statement's worth of tokens — letting the next
     * iteration re-lex `source.substring(cursor)` with the live POSIX
     * / extglob runtime state.
     *
     * For tokens emitted inside an alias body, the position is
     * `stack.last.pos` — the ROOT segment's current position, which
     * was advanced past the alias REFERENCE call site when
     * [pushAliasBody] ran. Every body-emitted token shares the same
     * root position (the position where the body was inserted), which
     * is monotonically ≤ where execution will resume in root source
     * after the body exhausts. That's the right value for cursor
     * advancement.
     *
     * [reorderHeredocBodies] keeps this list in lock-step with the
     * token list it reshapes.
     */
    private val tokenEndsBuf: MutableList<Int> = mutableListOf()

    /** Root-source position at the moment of token emission. */
    private fun rootSourceEnd(): Int = if (stack.size == 1) pos else stack.last().pos

    /** Append [tok] to [out] and record [rootSourceEnd] in [tokenEndsBuf]. */
    private fun emit(
        out: MutableList<Token>,
        tok: Token,
    ) {
        out += tok
        tokenEndsBuf += rootSourceEnd()
    }

    /**
     * Token-end positions for the most recent [tokenize] call. Indexed
     * parallel to the returned token list (post-reorder). Empty before
     * the first tokenize. Callers should consume immediately — the
     * buffer is reused on a re-tokenize.
     */
    internal fun tokenEndPositions(): IntArray = tokenEndsBuf.toIntArray()

    /** Source line at the end of the most recent [tokenize] — used by
     *  per-call lex callers ([com.accucodeai.kash.parser.parseOneStatement])
     *  to keep diagnostics' line numbers in sync across calls. */
    internal fun currentLine(): Int = line

    /**
     * Live [TokenizeState] for the current [tokenize] call. Set on entry,
     * cleared on exit. [popExhausted] writes through this to arm
     * `chainNextWord` when a trailing-blank alias body exhausts —
     * [popExhausted] runs deep inside reader helpers and would otherwise
     * need the state threaded through every call.
     */
    private var activeState: TokenizeState? = null

    /** Nesting depth of `"..."` contexts. While >0, `'` is a literal character (not a quote opener)
     * inside `${...}` arg parsing. */
    private var dqDepth = 0

    /**
     * Nesting depth inside `${...}` parameter-expansion bodies. Bash strips
     * the "literal `$'...'`" rule that normally applies inside double quotes
     * when we're inside a `${...}` body — e.g. `"${a/$'\001'/A}"` interprets
     * `$'\001'` as ANSI-C even though it's textually inside `"..."`.
     */
    private var braceDepth = 0

    /**
     * True while [lexDeferredOperandChunks] is driving the lexer over
     * previously-captured operand text. Allows sq across newlines and
     * other constructs the outer parser conservatively rejects because
     * we know the operand bounds are correct (the outer raw scanner
     * already paired them).
     */
    private var inDeferredOperandLex = false

    /**
     * True while reading the operand of a default-value parameter-expansion
     * operator (`-`, `:-`, `+`, `:+`, `=`, `:=`, `?`, `:?`).
     *
     * Bash treats `$'...'` ANSI-C quoting as **literal text** inside these
     * operands — `${x-$'\01'}` expands to the 6-char literal `$'\01'`, NOT
     * the byte 0x01. Pattern ops (`#`, `##`, `%`, `%%`, `/`, `//`, `^`, `,`,
     * etc.) DO recognize `$'...'`.
     *
     * Saved and restored across nested `${...}` so an inner pattern op
     * inside an outer default-value op sees its own state.
     */
    private var inDefaultValueOpOperand = false

    /** FIFO of heredocs awaiting body capture at the next newline. */
    private val pendingHeredocs = ArrayDeque<PendingHereDoc>()

    private data class PendingHereDoc(
        val delimiter: String,
        val stripTabs: Boolean,
        val quoted: Boolean,
        /** Source line where `<<DELIM` appeared — used to format the
         *  `here-document at line N delimited by end-of-file` warning. */
        val openedAtLine: Int,
    )

    /**
     * Diagnostic warnings collected during lexing. Currently used for the
     * `here-document at line N delimited by end-of-file (wanted \`DELIM')`
     * notice bash emits when a heredoc body is unexpectedly terminated by
     * end-of-input (real EOF for top-level scripts, end of command-substitution
     * content for embedded heredocs). The Parser exposes these so the
     * runtime can print them to stderr before executing the script.
     */
    internal data class LexerWarning(
        val line: Int,
        val message: String,
        val suppressLinePrefix: Boolean = false,
    )

    internal val warnings: MutableList<LexerWarning> = mutableListOf()

    /**
     * Parse-time alias overlay. The interpreter's runtime alias table is
     * read-only at parse time, but `alias name=value` / `unalias name` /
     * `BASH_ALIASES[name]=value` invocations observed earlier in the *same*
     * script must affect later command-name lookups in this script. We
     * record observations into [localAliases] / [localUnaliased] and the
     * effective resolver consults them first.
     *
     * This approximates bash's parse/execute interleaving for the common
     * case of top-level alias definitions (which is what bash test scripts
     * use). Defs inside `if`/`while` blocks would still parse-time-fire
     * even when execution skips the branch — accepted divergence.
     */
    private val localAliases = mutableMapOf<String, String>()
    private val localUnaliased = mutableSetOf<String>()

    private fun effectiveAlias(name: String): String? {
        if (name in localUnaliased) return null
        return localAliases[name] ?: aliasResolver.lookup(name)
    }

    /**
     * Push an alias body as a new segment on top of the input stack. The
     * lexer's next character read comes from `body`; when body exhausts,
     * the pos setter auto-pops and reading resumes in the outer segment
     * (bash `alias-input-stack push` / `alias-input-stack pop` mechanics).
     */
    private fun pushAliasBody(
        name: String,
        body: String,
    ) {
        val endsInBlank = body.isNotEmpty() && body.last().isWhitespace()
        val seg = Segment(body, 0, expanding = name, chainAfter = endsInBlank)
        stack.addFirst(seg)
        top = seg
    }

    /** True if any active segment is currently expanding [name] —
     *  POSIX rule 5's recursion guard. */
    private fun isExpanding(name: String): Boolean = stack.any { it.expanding == name }

    /** Bump [line] only when the consumed newline came from the root
     *  source. Newlines INSIDE an alias body are part of the substituted
     *  text but don't correspond to physical lines in the user's script —
     *  bash counts only root-source newlines for diagnostics. */
    private fun bumpLine() {
        if (stack.size == 1) line++
    }

    /** Returns the literal text of a single-chunk unquoted WordTok, or
     *  null if the word is quoted or has expansions. Only such words are
     *  alias-name candidates per POSIX §2.3.1. */
    private fun Token.WordTok.singleLiteralIfPlain(): String? {
        if (parts.size != 1) return null
        val p = parts[0]
        return if (p is WordChunk.Literal) p.value else null
    }

    /** Concatenate the literal-ish parts of a WordChunk list. Returns null
     *  if any part is dynamic (parameter expansion, command substitution),
     *  since we can't resolve those at parse time. */
    private fun literalOfChunks(chunks: List<WordChunk>): String? {
        val sb = StringBuilder()
        for (c in chunks) {
            when (c) {
                is WordChunk.Literal -> {
                    sb.append(c.value)
                }

                is WordChunk.SingleQuoted -> {
                    sb.append(c.value)
                }

                is WordChunk.Escaped -> {
                    sb.append(c.value)
                }

                is WordChunk.DoubleQuoted -> {
                    for (q in c.parts) {
                        when (q) {
                            is WordChunk.Literal -> sb.append(q.value)
                            is WordChunk.Escaped -> sb.append(q.value)
                            else -> return null
                        }
                    }
                }

                else -> {
                    return null
                }
            }
        }
        return sb.toString()
    }

    /** Peel a WordTok whose first literal part begins with `name=` into
     *  the (name, value) pair, concatenating remaining literal-ish parts
     *  as the value. */
    private fun extractAssignmentPair(tok: Token.WordTok): Pair<String, String>? {
        if (tok.parts.isEmpty()) return null
        // Concatenate the literal-ish text of all chunks so that
        // `'heredoc=cat <<EOF\nhello\nEOF'` (one SingleQuoted chunk) is
        // recognized as an alias def whose body contains newlines.
        val joined = literalOfChunks(tok.parts) ?: return null
        val eq = joined.indexOf('=')
        if (eq <= 0) return null
        return joined.substring(0, eq) to joined.substring(eq + 1)
    }

    /** Resolved alias name + body. Returned by [aliasMatchFor] when a
     *  WordTok in command-substitutable position names a defined alias. */
    private data class AliasMatch(
        val name: String,
        val body: String,
    )

    /** Look up [tok] as an alias name. Returns null when it isn't a plain
     *  single-literal word, when its name is already on the active
     *  expansion stack (POSIX rule 5 recursion guard), or when no alias is
     *  defined for it. Callers layer site-specific eligibility (cmd-pos,
     *  reserved-word block, etc.) around this. */
    private fun aliasMatchFor(tok: Token): AliasMatch? {
        if (tok !is Token.WordTok) return null
        val lit = tok.singleLiteralIfPlain() ?: return null
        if (isExpanding(lit)) return null
        val body = effectiveAlias(lit) ?: return null
        return AliasMatch(lit, body)
    }

    /**
     * POSIX §2.3.1 alias substitution at the primary command-name slot
     * (or chain-armed continuation after an alias body's trailing blank).
     * Returns true when the caller should `continue` the lexer loop:
     * either substitution fired (body pushed as a new input segment) OR
     * the body began with `#` and the rest of the source line was
     * swallowed as a comment.
     *
     * POSIX: alias defs "shall have no effect on the recognition of
     * reserved words" — at the primary cmd-pos we refuse to substitute
     * reserved-word names. Chain position is different: bash's chain
     * "continues until a word is found that is not a valid alias", so
     * `alias al=' '; alias for=echo; al for x in y` does substitute
     * `for→echo` in default mode. POSIX mode never substitutes reserved
     * words even in chain position.
     */
    private fun trySubstituteAtCommandStart(
        tok: Token,
        s: TokenizeState,
    ): Boolean {
        if (tok !is Token.WordTok) return false
        if (!(s.atCommandStart || s.chainNextWord)) return false
        // awaitingIn covers the word immediately after `for`/`case`/`select`
        // — a loop var or case-expression value, not a command name, so
        // POSIX alias substitution doesn't apply.
        if (s.suppressNextWordAlias || s.dataMode || s.awaitingIn) return false
        val m = aliasMatchFor(tok) ?: return false
        val blockedByReservedWord =
            (s.atCommandStart && !s.chainNextWord && m.name in RESERVED_WORDS) ||
                (posixMode && m.name in RESERVED_WORDS)
        if (blockedByReservedWord) return false
        if (m.body.trimStart().startsWith("#")) {
            // bash: an alias body starting with `#` turns into a comment
            // after expansion — consume the rest of the current source line.
            while (pos < source.length && source[pos] != '\n') pos++
            s.chainNextWord = false
            s.suppressNextWordAlias = false
            return true
        }
        pushAliasBody(m.name, m.body)
        // chainNextWord disarms when the eligible word is consumed; the
        // body's trailing-blank chainAfter (set in pushAliasBody) re-arms it
        // after body exhaustion. After the splice, the body's first word
        // stands in the command-name slot the original head occupied —
        // recursive expansion proceeds via the loop's natural re-entry, with
        // POSIX rule 5's guard via isExpanding.
        s.chainNextWord = false
        s.atCommandStart = true
        return true
    }

    fun tokenize(): List<Token> {
        val out = mutableListOf<Token>()
        val s = TokenizeState()
        activeState = s
        try {
            try {
                tokenizeLoop(out, s)
            } catch (e: ParseException) {
                // Structured lexer diagnostic (e.g. unterminated `$(...)`)
                // hit mid-stream. Bash's reader_loop discards the failing
                // command but keeps prior commands; mirror that by sealing
                // a LexError sentinel into the token stream so the
                // statement-streaming parser can run everything that
                // already lexed cleanly before reporting this error.
                emit(
                    out,
                    Token.LexError(
                        e.message ?: "lex error",
                        if (e.line > 0) e.line else line,
                        offendingToken = e.offendingToken,
                    ),
                )
            } catch (e: IllegalStateException) {
                // Unstructured `error("Unterminated ...")` site. Same
                // recovery shape — emit the sentinel and stop lexing.
                emit(out, Token.LexError(e.message ?: "lex error", line))
            }
        } finally {
            activeState = null
        }
        return reorderHeredocBodies(out)
    }

    /**
     * Deferred re-lex of a default-value-op operand. The raw text was
     * captured at outer-parse time by [extractBracedOperandTextRaw];
     * here we run the same chunk reader the eager path used, with
     * [dqDepth] pre-set so the operand's `'`/`\X` rules match the
     * originating context. Mirrors bash's
     * `default-value RHS expander` calling its
     * re-parse helpers on the captured text.
     *
     * Stop chars are empty: the raw text is bounded by EOF (the outer
     * scanner cut it at the matching `}`).
     */
    internal fun lexDeferredOperandChunks(
        initialDqDepth: Int,
        defaultValueOp: Boolean,
        inDefaultValueOp: Boolean = false,
    ): List<WordChunk> {
        dqDepth = initialDqDepth
        // braceDepth=1 mirrors "we're inside `${...}`" so that
        // `$'...'` recognition at lex-time uses the in-brace branch
        // ( convention via `brace-body state machine`). Without this,
        // `${mytab:-$'\t'}` re-lex would fall through `dqDepth > 0
        // && braceDepth == 0 -> null` and emit `$'\t'` as literal.
        braceDepth = 1
        // Bare default-value ops (`-`/`+`/`=`/`?`) keep `$'...'` as
        // a literal block; colon-prefixed ops recognize ANSI-C.
        inDefaultValueOpOperand = inDefaultValueOp
        inDeferredOperandLex = true
        return readWordChunksUntilDelimiter(emptySet(), defaultValueOp = defaultValueOp)
    }

    companion object {
        /**
         * Bash's per-simple-command heredoc cap (parse.h `HEREDOC_MAX` is
         * 16 in 5.2). Past this, the lexer emits "maximum here-document
         * count exceeded" — a CVE-2014-7186 mitigation that prevents
         * stack abuse from `cat <<A <<B <<C …` adversarial input.
         */
        internal const val HEREDOC_MAX: Int = 16

        /**
         * Convenience: build a transient Lexer over [rawText] and run
         * [lexDeferredOperandChunks]. Used by the expander for every
         * default-value-op `${X<op>...}` whose operand was deferred at
         * outer-parse time. The strict pass uses
         * `defaultValueOp = initialDqDepth > 0` (bash-5.3 / POSIX
         * semantics); the lenient fallback in the expander uses
         * `defaultValueOp = false` so `'` opens sq and `\X` keeps
         * its backslash for non-dq-special X — the bash-3.2 quirky
         * behavior that the older `braces.tests` fixtures depend on.
         */
        internal fun lexDeferredOperand(
            rawText: String,
            initialDqDepth: Int,
            defaultValueOp: Boolean = initialDqDepth > 0,
            inDefaultValueOp: Boolean = false,
        ): List<WordChunk> {
            val l = Lexer(rawText)
            return l.lexDeferredOperandChunks(initialDqDepth, defaultValueOp, inDefaultValueOp)
        }
    }

    private fun tokenizeLoop(
        out: MutableList<Token>,
        s: TokenizeState,
    ) {
        while (hasMoreInput()) {
            val c = source[pos]
            when {
                c == '\n' -> {
                    pos++
                    emit(out, Token.Newline(line))
                    bumpLine()
                    if (pendingHeredocs.isNotEmpty()) consumeHeredocBodies(out)
                    s.onStatementBoundary()
                }

                c.isWhitespace() -> {
                    pos++
                }

                // Backslash-newline between tokens is a POSIX line
                // continuation: silently consume both bytes (bumping the
                // line counter) so the next token is parsed as if it were
                // on the same logical line. Without this, `echo x | \\\n
                // (subshell)` lands `\\` in readWord and produces a stray
                // empty WordTok that confuses ANTLR's pipeline rule.
                c == '\\' && pos + 1 < source.length && source[pos + 1] == '\n' -> {
                    pos += 2
                    bumpLine()
                }

                c == '#' -> {
                    skipComment()
                }

                c == '{' && s.atCommandStart && nextIsSpaceOrEnd(pos + 1) -> {
                    pos++
                    emit(out, Token.Keyword("{", line))
                }

                c == '}' && s.atCommandStart -> {
                    pos++
                    emit(out, Token.Keyword("}", line))
                    s.atCommandStart = false
                }

                // Redirections must be checked before control operators so `&>` isn't eaten by `&`.
                isRedirStart() -> {
                    val redirTok = readRedirection()
                    emit(out, redirTok)
                    // For `<<` / `<<-`, the delimiter word was already
                    // consumed inline by [readHereDocDelimiter] and the
                    // body will be captured at the next newline. Don't
                    // arm pendingRedirTarget — there's no further target
                    // word to read, and leaving it armed would swallow
                    // the next statement's first word (e.g. an `if` /
                    // `while` keyword on the line after the heredoc).
                    if (redirTok.op != "<<" && redirTok.op != "<<-") {
                        s.pendingRedirTarget = true
                    }
                    // Don't flip atCommandStart: redirections are a prefix,
                    // not a command-name-consuming token. The target read in
                    // the next iteration is what (transparently) leaves the
                    // surrounding atCommandStart intact.
                }

                c == '(' && s.atCommandStart && pos + 1 < source.length && source[pos + 1] == '(' -> {
                    val tok = tryReadArithCommand()
                    if (tok != null) {
                        emit(out, tok)
                        s.atCommandStart = false
                    } else {
                        // `((` that can't validly close with `))` — bash falls back to nested subshells.
                        // Emit one `(` here; the next iteration re-encounters the second `(` as another subshell open.
                        pos++
                        emit(out, Token.Operator("(", line))
                    }
                }

                // Regex RHS of `[[ … =~ … ]]`: a `(` opens a regex group
                // (`(foo|bar)`), NOT a subshell. Dispatch to readWord so the
                // whole pattern up to whitespace/`]]` is one word.
                s.regexRhsArmed && c == '(' -> {
                    var tok: Token = readWordOrAssignment(atCommandStart = false, assignOkAsWord = false)
                    emit(out, tok)
                    if (tok is Token.WordTok && s.regexRhsArmed) s.regexRhsArmed = false
                }

                isOperatorStart(c) -> {
                    val opTok = readControlOperator()
                    emit(out, opTok)
                    s.onControlOperator()
                    if (opTok is Token.Operator) {
                        s.onCaseOperator(opTok.op)
                        if (opTok.op == ";") s.awaitingIn = false
                    }
                }

                else -> {
                    // A chain-armed word (after an alias body's trailing
                    // blank) occupies the command-name slot in bash's view,
                    // so reserved-word recognition (e.g. `for` in
                    // `alias al=' '; al for foo in v`) and assignment-prefix
                    // detection both apply. POSIX §2.3.1 rule 4 + bash
                    // expand_alias semantics.
                    var tok: Token =
                        readWordOrAssignment(
                            atCommandStart = s.atCommandStart || s.chainNextWord || s.inAssignmentBuiltin,
                            assignOkAsWord =
                                s.inAssignOkBuiltin && !s.atCommandStart && !s.chainNextWord && !s.inAssignmentBuiltin,
                        )
                    // Chain-armed alias check on a redirection target. Bash's
                    // chain operates on the raw input stream regardless of
                    // syntactic position, so `alias c='< '; alias file='/dev/
                    // null ;'; ... c file` substitutes `file` even though it
                    // sits in the redir-target slot. The substituted body's
                    // first word becomes the target. pendingRedirTarget stays
                    // armed so the NEXT WordTok (from the body) is the target.
                    if (s.pendingRedirTarget && s.chainNextWord) {
                        val m = aliasMatchFor(tok)
                        if (m != null && !m.body.trimStart().startsWith("#")) {
                            pushAliasBody(m.name, m.body)
                            s.chainNextWord = false
                            continue
                        }
                    }
                    if (s.pendingRedirTarget) {
                        // This word is the redirection target — it doesn't
                        // change cmd-name position (POSIX: a redirection's
                        // target isn't the simple command's name).
                        s.pendingRedirTarget = false
                        emit(out, tok)
                        continue
                    }
                    // `in` arriving as a WordTok inside a case/for/select
                    // header is the data-mode-arming keyword (it isn't at
                    // cmd-pos so the lexer emits it as WordTok). Promote
                    // first regardless of substitution eligibility — must
                    // also fire after `case "$x" in` where `"$x"` consumed
                    // command-name position.
                    if (s.awaitingIn && tok is Token.WordTok &&
                        tok.singleLiteralIfPlain() == "in"
                    ) {
                        emit(out, tok)
                        s.dataMode = true
                        s.awaitingIn = false
                        s.atCommandStart = false
                        s.suppressNextWordAlias = false
                        s.chainNextWord = false
                        continue
                    }
                    // POSIX §2.3.1 alias substitution: a WordTok in command-
                    // name position (or chain-armed) whose literal resolves
                    // to an alias triggers a body-segment push instead of
                    // emitting the word. See [trySubstituteAtCommandStart].
                    if (trySubstituteAtCommandStart(tok, s)) continue
                    // Reserved-word recognition: deferred from readWord-
                    // OrAssignment so it runs after alias substitution
                    // declined the word. Fires at either primary cmd-pos OR
                    // chain position — at chain position we only reach here
                    // if no alias matched, so the reserved word should win.
                    if (tok is Token.WordTok &&
                        (s.atCommandStart || s.chainNextWord) &&
                        tok.parts.size == 1
                    ) {
                        val single = tok.parts[0]
                        if (single is WordChunk.Literal && single.value in RESERVED_WORDS) {
                            tok = Token.Keyword(single.value, tok.line)
                        }
                    }
                    // Observe alias / unalias / BASH_ALIASES so subsequent
                    // command words in the same parse see the defs (bash
                    // parse/execute interleaving approximation).
                    if (s.inAliasArgs && tok is Token.WordTok) {
                        val pair = extractAssignmentPair(tok)
                        if (pair != null && isValidAliasName(pair.first) &&
                            !(posixMode && pair.first in RESERVED_WORDS)
                        ) {
                            localAliases[pair.first] = pair.second
                            localUnaliased.remove(pair.first)
                        }
                    } else if (s.inUnaliasArgs && tok is Token.WordTok) {
                        val literal = tok.singleLiteralIfPlain()
                        if (literal == "-a") {
                            localAliases.clear()
                        } else if (literal != null && !literal.startsWith("-")) {
                            localAliases.remove(literal)
                            localUnaliased += literal
                        }
                    } else if (tok is Token.IndexedAssignTok &&
                        tok.name == "BASH_ALIASES" &&
                        s.atCommandStart
                    ) {
                        val key = literalOfChunks(tok.subscript)
                        val value = literalOfChunks(tok.value)
                        if (key != null && value != null && isValidAliasName(key)) {
                            localAliases[key] = value
                            localUnaliased.remove(key)
                        }
                    }
                    // Decide next-word arming based on whether THIS token was at
                    // cmd-pos (read pre-update `atCommandStart`), then update
                    // state for the next iteration.
                    val literal = (tok as? Token.WordTok)?.singleLiteralIfPlain()
                    val wasAtCommandStart = s.atCommandStart
                    if (tok is Token.Keyword) s.onKeyword(tok.word)
                    // Track `[[ ... ]]` nesting so the rhs of `=~` can be lexed
                    // as a regex (balanced `(...)` joined into the word). `[[`
                    // only arrives as a Keyword (cmd-pos); the matching `]]`
                    // arrives as a WordTok because the operand before it left
                    // atCommandStart=false.
                    if (tok is Token.Keyword && tok.word == "[[") {
                        s.condDepth++
                        s.regexRhsArmed = false
                    } else if (literal == "]]" && s.condDepth > 0) {
                        s.condDepth--
                        s.regexRhsArmed = false
                    } else if (s.condDepth > 0 && literal == "=~") {
                        s.regexRhsArmed = true
                    } else if (tok is Token.WordTok && s.regexRhsArmed) {
                        // The armed word has been consumed (the next-word read
                        // already saw `regexRhsArmed` via [activeState] and
                        // applied regex-rhs lexing). Disarm so subsequent
                        // operands inside the same `[[ ]]` are normal words.
                        s.regexRhsArmed = false
                    }
                    // Inline-env prefix preserves command-name position for
                    // the *next* word: `FOO=1 BAR= cmd` is still a simple
                    // command with head `cmd`. Without this, `b=""` after
                    // `a=()` parses as a plain word and executes as a
                    // (failed) command.
                    // `time` arms: a following `-p` / `--` WordTok is a
                    // time-option, not the command word. Keep cmd-start
                    // through the option-run so the next `time` or the
                    // body command stays in keyword-promotion position.
                    val isTimeOptWord =
                        s.inTimeOpts &&
                            tok is Token.WordTok &&
                            (literal == "-p" || literal == "--")
                    s.atCommandStart =
                        tok is Token.AssignmentTok ||
                        tok is Token.ArrayAssignTok ||
                        tok is Token.IndexedAssignTok ||
                        (tok is Token.Keyword && tok.word in KEYWORDS_KEEPING_CMD_START) ||
                        isTimeOptWord
                    s.inTimeOpts =
                        (tok is Token.Keyword && tok.word == "time") || isTimeOptWord
                    if (tok is Token.WordTok) s.chainNextWord = false
                    // bash: `command name args…` suppresses alias expansion
                    // of `name` (next WordTok). `alias` / `unalias` arm the
                    // operand-observation overlays. Assignment-builtin arming
                    // — after `declare`/`typeset`/`local`/`readonly`/`export`
                    // (or `command declare`, …) — promotes operand slots to
                    // `NAME=value` / `NAME=(...)` assignment tokens. POSIX
                    // §2.9.1.4.
                    s.suppressNextWordAlias = wasAtCommandStart && literal == "command"
                    if (wasAtCommandStart && literal == "alias") s.inAliasArgs = true
                    if (wasAtCommandStart && literal == "unalias") s.inUnaliasArgs = true
                    if (wasAtCommandStart && literal in ASSIGNMENT_BUILTIN_NAMES) s.inAssignmentBuiltin = true
                    if (wasAtCommandStart && literal in ASSIGN_OK_BUILTIN_NAMES) s.inAssignOkBuiltin = true
                    emit(out, tok)
                }
            }
        }
        emit(out, Token.Eof(line))
    }

    /**
     * Statement-machine state for [tokenize]: every flag that the main loop
     * mutates as tokens flow past lives here. The instance is local to one
     * `tokenize()` call and reached from [popExhausted] via [activeState].
     */
    private class TokenizeState {
        var atCommandStart = true

        /**
         * POSIX §2.3.1 rule 4 — when an alias body ends in whitespace, the
         * next *command word* (the one that arrives after the body is fully
         * consumed) is also checked for alias substitution, recursively.
         * Set by the segment auto-pop when a chained body finishes;
         * disarmed once a word actually consumes it (whether substituted or
         * not), and on newline / operator boundaries.
         */
        var chainNextWord: Boolean = false

        /** Set after a [Token.RedirOpTok] so the *target* word (the next non-redir,
         *  non-newline token) doesn't consume cmd-name position. Bash treats
         *  redirections as a prefix — `< /dev/null foo bar` keeps `foo` at
         *  command-name position, so `foo` is alias-eligible. */
        var pendingRedirTarget = false

        /** POSIX: words following `for X in`, `case X in`, `select X in` are
         *  *data* (value list / patterns), not commands. They must NOT be
         *  alias-expanded — otherwise `case x in foo)` matches against the
         *  alias body rather than `foo`. */
        var dataMode = false
        var caseDepth = 0

        /** After Keyword(`case`/`for`/`select`) we're waiting to see `in`,
         *  which arrives as a WordTok (lexer only emits Keyword at cmd-pos).
         *  The flag promotes WordTok("in") to data-mode-arming behavior. */
        var awaitingIn = false

        /** bash: `command name args…` suppresses alias expansion of `name`. */
        var suppressNextWordAlias = false

        /** While we're parsing operands of `alias …` or `unalias …`, observe
         *  the definitions for the in-parse overlay so subsequent alias-name
         *  lookups see them. */
        var inAliasArgs = false
        var inUnaliasArgs = false

        /** bash declare/typeset/local/readonly/export — "assignment builtins".
         *  Their operands are syntactically assignments, not plain words, so
         *  `NAME=value` and `NAME=(...)` past the command-name slot must still
         *  tokenize as Assignment/ArrayAssign rather than as a WordTok. POSIX
         *  §2.9.1.4 + bash builtins(1). */
        var inAssignmentBuiltin = false

        /** bash eval/let — they get assignment-OK parser state so that
         *  `eval NAME=(…)` lexes the array literal as part of a single
         *  word. Unlike [inAssignmentBuiltin], the resulting tokens are
         *  emitted as WordTok (passed as args), not as inline assignments. */
        var inAssignOkBuiltin = false

        /** Nesting depth of `[[ ... ]]`. Incremented on Keyword(`[[`), decremented
         *  when a WordTok literal `]]` arrives (the inner `]]` is never at
         *  cmd-pos, so it never becomes a Keyword). Used to scope regex-rhs
         *  tokenization to inside `[[`. */
        var condDepth: Int = 0

        /** Inside `[[`, the next word read should treat balanced `(...)` as
         *  word content (regex grouping), not as a control operator. Armed by
         *  observing a WordTok literal `=~`; consumed by the next word. */
        var regexRhsArmed: Boolean = false

        /** Just emitted KW_TIME (or a `-p`/`--` WordTok following it) — the
         *  grammar's `timeOpt` rule accepts `-p` and `--` here, after which
         *  the *next* word is at command-start (and so `time` itself
         *  remains reserved). Without this flag a `-p` WordTok would clear
         *  atCommandStart and the second `time` in `time -p time echo a`
         *  would PATH-resolve as a command. */
        var inTimeOpts: Boolean = false

        /** Reset for a newline / hard statement boundary. Drops all per-statement
         *  arming flags including the `for/case/select` "awaiting in" state. */
        fun onStatementBoundary() {
            atCommandStart = true
            awaitingIn = false
            chainNextWord = false
            clearPerWordArming()
        }

        /** Reset for a control operator (`|`, `&`, `;`, `&&`, `||`, `(`, `)`).
         *  Returns to command-start but leaves `awaitingIn` alone — `&&`/`||`
         *  inside a case/for/select header don't reset the wait. The caller
         *  clears `awaitingIn` itself for `;`. */
        fun onControlOperator() {
            atCommandStart = true
            chainNextWord = false
            clearPerWordArming()
        }

        private fun clearPerWordArming() {
            suppressNextWordAlias = false
            inAliasArgs = false
            inUnaliasArgs = false
            inAssignmentBuiltin = false
            inAssignOkBuiltin = false
        }

        /** Update case-statement pattern/body tracking when an operator fires
         *  inside `case … esac`. `)` ends a pattern; `;;` / `;&` / `;;&` end
         *  the body and arm the next pattern. */
        fun onCaseOperator(op: String) {
            if (caseDepth == 0) return
            when (op) {
                ")" -> if (dataMode) dataMode = false
                ";;", ";&", ";;&" -> dataMode = true
            }
        }

        /** Apply state transitions triggered by a recognized keyword token. */
        fun onKeyword(word: String) {
            when (word) {
                "case" -> {
                    caseDepth++
                    awaitingIn = true
                }

                "esac" -> {
                    if (caseDepth > 0) caseDepth--
                    dataMode = false
                    awaitingIn = false
                }

                "for", "select" -> {
                    awaitingIn = true
                }

                // `in` at cmd-pos (e.g. after newline in `case x\n in …`) arms
                // dataMode so following pattern words aren't alias-expanded.
                "in" -> {
                    dataMode = true
                    awaitingIn = false
                }

                "do" -> {
                    dataMode = false
                    awaitingIn = false
                }

                "done" -> {
                    dataMode = false
                }
            }
        }
    }

    /**
     * Move every captured `HereDocBodyTok` to immediately follow its `<<`/`<<-`
     * `RedirOpTok`. The lexer captures bodies at the next newline (because that's
     * where they appear in the source), but the parser needs them at the
     * redirection site to attach to the AST.
     */
    private fun reorderHeredocBodies(toks: List<Token>): List<Token> {
        // Reshape both the token list AND the parallel [tokenEndsBuf] in
        // lock-step. Heredoc body tokens were emitted at the source
        // position where the body text appeared; after reorder they sit
        // adjacent to their redir op (where they're parsed-attached), but
        // their RECORDED root-source offsets stay correct — V2
        // [StatementStream] reads the max of consumed positions to
        // advance its source cursor.
        val bodyToks = ArrayDeque<Token.HereDocBodyTok>()
        val bodyEnds = ArrayDeque<Int>()
        val nonBodyToks = mutableListOf<Token>()
        val nonBodyEnds = mutableListOf<Int>()
        for ((i, t) in toks.withIndex()) {
            val end = tokenEndsBuf.getOrElse(i) { 0 }
            if (t is Token.HereDocBodyTok) {
                bodyToks += t
                bodyEnds += end
            } else {
                nonBodyToks += t
                nonBodyEnds += end
            }
        }
        val outToks = mutableListOf<Token>()
        val outEnds = mutableListOf<Int>()
        for ((i, t) in nonBodyToks.withIndex()) {
            outToks += t
            outEnds += nonBodyEnds[i]
            if (t is Token.RedirOpTok && (t.op == "<<" || t.op == "<<-")) {
                if (bodyToks.isNotEmpty()) {
                    outToks += bodyToks.removeFirst()
                    outEnds += bodyEnds.removeFirst()
                }
            }
        }
        // Replace the buffer with the reordered list.
        tokenEndsBuf.clear()
        tokenEndsBuf.addAll(outEnds)
        return outToks
    }

    private fun nextIsSpaceOrEnd(p: Int): Boolean = p >= source.length || source[p].isWhitespace() || source[p] == ';'

    private fun skipComment() {
        while (pos < source.length && source[pos] != '\n') pos++
    }

    private fun isOperatorStart(c: Char): Boolean = c == '|' || c == '&' || c == ';' || c == '(' || c == ')'

    /** True when the current position begins a redirection operator. */
    private fun isRedirStart(): Boolean {
        val c = source[pos]
        // `<(...)` / `>(...)` are word-level process substitutions, NOT
        // redirections — let the word reader pick them up via
        // [readProcessSubstitution]. Without this carve-out, `<(echo hi)` at
        // word-start would dispatch to redir parsing and fail on the `(`.
        if ((c == '<' || c == '>') && pos + 1 < source.length && source[pos + 1] == '(') return false
        if (c == '<' || c == '>') return true
        if (c == '&' && pos + 1 < source.length && (source[pos + 1] == '>')) return true
        if (c.isDigit()) {
            var p = pos
            while (p < source.length && source[p].isDigit()) p++
            // bash: a digit prefix only counts as an fd if it parses as a
            // valid (non-overflow) integer. Pathologically long literals
            // like `1111111111111111111111<file` are treated as a regular
            // command word, not a redirection — bash then runs them as
            // "command not found" rather than opening fd MAX_VALUE.
            if (source.substring(pos, p).toIntOrNull() == null) return false
            return p < source.length && (source[p] == '<' || source[p] == '>')
        }
        // Bash dynamic-fd-allocation form `{NAME}>file`, `{NAME}<file`,
        // `{NAME}>>file`, `{NAME}<&N`, etc. — `{` immediately followed by an
        // identifier, `}`, and then a redirection operator. Disambiguates from
        // brace-group `{ cmd; }` (which has whitespace inside) and from
        // ordinary word `{abc}` (no following redir).
        if (c == '{') {
            var p = pos + 1
            if (p >= source.length || !(source[p].isLetter() || source[p] == '_')) return false
            while (p < source.length && (source[p].isLetterOrDigit() || source[p] == '_')) p++
            // Optional `[subscript]` for array-element targets, e.g. `{fd[0]}<&0`.
            // Subscript body is opaque here — match to the closing `]` (no
            // nesting, no quoting; bash's parser is similarly shallow for
            // varname subscripts in this context).
            if (p < source.length && source[p] == '[') {
                var q = p + 1
                while (q < source.length && source[q] != ']' && source[q] != '\n') q++
                if (q >= source.length || source[q] != ']') return false
                p = q + 1
            }
            if (p >= source.length || source[p] != '}') return false
            p++
            // Optional fd-digits between } and the operator (e.g. {fd}2>file is
            // not valid in bash; the digit-prefix slot is incompatible with
            // {fd}). Require the operator immediately after `}`.
            return p < source.length &&
                (
                    source[p] == '<' || source[p] == '>' ||
                        (source[p] == '&' && p + 1 < source.length && source[p + 1] == '>')
                )
        }
        return false
    }

    private fun readControlOperator(): Token {
        val start = line
        val c = source[pos]
        val two = if (pos + 1 < source.length) "$c${source[pos + 1]}" else ""
        return when {
            two == "&&" || two == "||" -> {
                pos += 2
                Token.Operator(two, start)
            }

            c == '(' -> {
                pos++
                Token.Operator("(", start)
            }

            c == ')' -> {
                pos++
                Token.Operator(")", start)
            }

            c == '|' || c == '&' || c == ';' -> {
                // Case-item terminators: `;;` (break), `;&` (fallthrough), `;;&` (continue-test).
                // Longer prefix first.
                val three = if (pos + 2 < source.length) "$c${source[pos + 1]}${source[pos + 2]}" else ""
                when (c) {
                    ';' if three == ";;&" -> {
                        pos += 3
                        Token.Operator(";;&", start)
                    }

                    ';' if two == ";;" -> {
                        pos += 2
                        Token.Operator(";;", start)
                    }

                    ';' if two == ";&" -> {
                        pos += 2
                        Token.Operator(";&", start)
                    }

                    '|' if two == "|&" -> {
                        // `a |& b` ≡ `a 2>&1 | b`. AstBuilder reads the
                        // operator text to inject the dup on the LHS.
                        pos += 2
                        Token.Operator("|&", start)
                    }

                    else -> {
                        pos++
                        Token.Operator(c.toString(), start)
                    }
                }
            }

            else -> {
                error("Unreachable: not an operator at $pos")
            }
        }
    }

    private fun readRedirection(): Token.RedirOpTok {
        val startLine = line
        // Bash dynamic-fd-allocation prefix `{NAME}`: consume the brace-wrapped
        // identifier; runtime will allocate a fresh high fd and assign it to
        // shell var `$NAME`. Mutually exclusive with the digit-fd prefix —
        // `{fd}2>file` isn't a thing.
        var fdVar: String? = null
        if (source[pos] == '{') {
            val nameStart = pos + 1
            var p = nameStart
            while (p < source.length && (source[p].isLetterOrDigit() || source[p] == '_')) p++
            // Optional `[subscript]` for array-element fd targets. We keep
            // the literal `name[subscript]` in [Token.RedirOpTok.fdVar];
            // the runtime splits it back out and routes through the
            // array-element setters/getters.
            if (p < source.length && source[p] == '[') {
                var q = p + 1
                while (q < source.length && source[q] != ']' && source[q] != '\n') q++
                if (q < source.length && source[q] == ']') {
                    p = q + 1
                }
            }
            // isRedirStart guaranteed the `}` follows; defensive check anyway.
            if (p < source.length && source[p] == '}') {
                fdVar = source.substring(nameStart, p)
                pos = p + 1
            }
        }
        // Leading FD: digits attached to a redir operator.
        val fdStart = pos
        while (pos < source.length && source[pos].isDigit()) pos++
        // Pathologically large literals (the printf test corpus passes a
        // 22-digit `1111…` for an out-of-range FD diagnostic) overflow
        // `Int`; cap at Int.MAX_VALUE so the runtime surfaces "bad fd"
        // rather than crashing the lexer with NumberFormatException.
        val fd: Int? =
            if (pos > fdStart) {
                source.substring(fdStart, pos).toIntOrNull() ?: Int.MAX_VALUE
            } else {
                null
            }

        val c = source[pos]
        val two = if (pos + 1 < source.length) "$c${source[pos + 1]}" else ""
        val three = if (pos + 2 < source.length) "$c${source[pos + 1]}${source[pos + 2]}" else ""

        val op: String =
            when {
                three == "<<<" -> {
                    pos += 3
                    "<<<"
                }

                three == "<<-" -> {
                    pos += 3
                    "<<-"
                }

                two == "<<" -> {
                    pos += 2
                    "<<"
                }

                two == ">>" -> {
                    pos += 2
                    ">>"
                }

                two == ">|" -> {
                    pos += 2
                    ">|"
                }

                two == "<>" -> {
                    pos += 2
                    "<>"
                }

                two == ">&" -> {
                    pos += 2
                    ">&"
                }

                two == "<&" -> {
                    pos += 2
                    "<&"
                }

                two == "&>" && fd == null -> {
                    pos += 2
                    if (pos < source.length && source[pos] == '>') {
                        pos++
                        "&>>"
                    } else {
                        "&>"
                    }
                }

                c == '<' -> {
                    pos++
                    "<"
                }

                c == '>' -> {
                    pos++
                    ">"
                }

                else -> {
                    error("Unreachable: not a redir at $pos")
                }
            }

        // POSIX move-fd: `<&N-` / `>&N-` is `dup2(src,dst); close(src)`. The
        // trailing `-` is part of the operator, not the operand (which is
        // the source fd N). We detect the form `(digit+) -` immediately
        // after `<&` / `>&` and consume nothing here; the trailing `-` is
        // left in the source. The runtime's DUP_IN/DUP_OUT branches see
        // [Token.RedirOpTok.closeAfter] and strip the trailing `-` from
        // the operand. We do NOT consume `-` in the lexer because the
        // operand might be `${VAR}-` (variable expansion that yields fd),
        // in which case the `-` is past the expansion's source bytes and
        // there's no clean byte-level boundary to slice.
        //
        // Bare-close `<&-` / `>&-` (operand literally just `-`) goes
        // through the existing path: operand = "-" → RedirTarget.Fd(-1).
        var closeAfter = false
        if ((op == "<&" || op == ">&")) {
            // Scan past digits OR a ${...} / $NAME expansion start, then
            // peek for `-`. Cheap: only set the flag when the `-` would
            // be left dangling at end-of-operand. The runtime handles
            // the actual close.
            var p = pos
            // Skip leading whitespace? No — operand is contiguous in bash.
            while (p < source.length && source[p].isDigit()) p++
            // Or a `$VAR` / `${...}` expansion that yields an fd number.
            if (p == pos && p < source.length && source[p] == '$') {
                p++
                if (p < source.length && source[p] == '{') {
                    var depth = 1
                    p++
                    while (p < source.length && depth > 0) {
                        when (source[p]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        p++
                    }
                } else {
                    while (p < source.length && (source[p].isLetterOrDigit() || source[p] == '_')) p++
                }
            }
            if (p > pos && p < source.length && source[p] == '-') {
                closeAfter = true
            }
        }
        val tok = Token.RedirOpTok(op, fd, startLine, closeAfter = closeAfter, fdVar = fdVar)
        if (op == "<<" || op == "<<-") {
            // Bash caps the number of heredocs that can stack on a single
            // simple command (CVE-2014-7186 mitigation; `HEREDOC_MAX` is
            // 16 in bash 5.2's parse.h). `cat <<A <<B <<C ...` accumulates
            // all of them in `pendingHeredocs` until the next newline.
            // exportfunc1.sub stacks 18 and expects rejection with the
            // bash-shaped diagnostic.
            if (pendingHeredocs.size >= HEREDOC_MAX) {
                throw com.accucodeai.kash.parser
                    .ParseException("maximum here-document count exceeded", line = startLine)
            }
            // Capture delimiter word, push onto pending queue. For `<<-`,
            // bash strips leading tabs from the delim itself before
            // matching (mirrors the body-line tab-strip), so a quoted
            // delim like `<<-'\tEND'` actually matches a `\tEND` line —
            // both sides become `END` post-strip.
            skipInlineWhitespace()
            val (delimRaw, quoted) = readHereDocDelimiter()
            val isStrip = op == "<<-"
            val delim = if (isStrip) delimRaw.trimStart('\t') else delimRaw
            pendingHeredocs += PendingHereDoc(delim, isStrip, quoted, startLine)
        }
        return tok
    }

    private fun skipInlineWhitespace() {
        while (pos < source.length && (source[pos] == ' ' || source[pos] == '\t')) pos++
    }

    /** Reads the heredoc delimiter that follows `<<` / `<<-`. Returns (delim, wasQuoted). */
    private fun readHereDocDelimiter(): Pair<String, Boolean> {
        val sb = StringBuilder()
        var quoted = false
        loop@ while (pos < source.length) {
            val c = source[pos]
            when {
                c.isWhitespace() || c == ';' || c == '|' || c == '&' || c == '<' || c == '>' -> {
                    break@loop
                }

                c == '\'' -> {
                    quoted = true
                    pos++
                    while (pos < source.length && source[pos] != '\'') {
                        sb.append(source[pos])
                        pos++
                    }
                    if (pos < source.length) pos++
                }

                c == '"' -> {
                    quoted = true
                    pos++
                    while (pos < source.length && source[pos] != '"') {
                        sb.append(source[pos])
                        pos++
                    }
                    if (pos < source.length) pos++
                }

                c == '\\' -> {
                    pos++
                    if (pos < source.length) {
                        if (source[pos] == '\n') {
                            // Backslash-newline is a line continuation in the delimiter — drop both
                            // and keep reading on the next line. Doesn't quote the delimiter.
                            bumpLine()
                            pos++
                        } else {
                            quoted = true
                            sb.append(source[pos])
                            pos++
                        }
                    }
                }

                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
        // Empty delimiter (e.g. `<<''`) is allowed — bash terminates on a blank line or EOF.
        // `quoted` stays true in that case because the empty delimiter is always quoted via `''`/`""`.
        return sb.toString() to quoted
    }

    /** After consuming a Newline, drain pending heredocs from the source.
     *
     *  Cross-segment-aware: when the heredoc opener lives in an alias
     *  body and the closing delimiter must come from the surrounding
     *  source after the alias body exhausts, [hasMoreInput] pops the
     *  alias segment and reading continues in the outer segment. This
     *  matches bash's behavior where `alias 'h=cat <<EOF\nhello'; h;
     *  world; EOF` reads body `hello\nworld` (the alias-body partial
     *  line continues into the outer source on the next line).
     */
    private fun consumeHeredocBodies(out: MutableList<Token>) {
        val drained = ArrayDeque(pendingHeredocs)
        pendingHeredocs.clear()
        while (drained.isNotEmpty()) {
            val hd = drained.removeFirst()
            val bodyLines = mutableListOf<String>()
            var lastContentLine = hd.openedAtLine
            while (true) {
                if (!hasMoreInput()) {
                    // Heredoc body reaches end-of-input across ALL active
                    // segments without finding the closing delimiter —
                    // bash emits a warning naming both the line where
                    // `<<DELIM` appeared and the line where EOF was hit,
                    // then proceeds with what was consumed.
                    warnings +=
                        LexerWarning(
                            line = lastContentLine,
                            message =
                                "warning: here-document at line ${hd.openedAtLine} " +
                                    "delimited by end-of-file (wanted `${hd.delimiter}')",
                        )
                    break
                }
                lastContentLine = line
                val rawLine = readOneHeredocLine(quoted = hd.quoted)
                val candidate = if (hd.stripTabs) rawLine.trimStart('\t') else rawLine
                if (candidate == hd.delimiter) {
                    // Consume newline after delimiter (if any), do NOT keep the delimiter in body.
                    if (hasMoreInput() && source[pos] == '\n') {
                        pos++
                        bumpLine()
                    }
                    break
                }
                bodyLines += candidate
                if (hasMoreInput() && source[pos] == '\n') {
                    pos++
                    bumpLine()
                }
            }
            val bodyStr = bodyLines.joinToString("\n", postfix = if (bodyLines.isNotEmpty()) "\n" else "")
            val parts =
                if (hd.quoted) {
                    listOf<WordChunk>(WordChunk.Literal(bodyStr))
                } else {
                    lexHeredocBody(bodyStr, bodyStartLine = hd.openedAtLine + 1)
                }
            emit(out, Token.HereDocBodyTok(parts, hd.stripTabs, hd.quoted, hd.delimiter, line))
        }
    }

    /**
     * Read a single "logical" heredoc line. For unquoted heredocs, bash splices backslash-newline
     * before checking against the delimiter, so `EO\` + newline + `F` reads as one line `EOF`.
     * Quoted heredocs (`<< 'EOF'`) preserve the backslash-newline as literal text.
     *
     * Cross-segment-aware: when the current segment (e.g. an alias body)
     * exhausts mid-line without a trailing newline, [hasMoreInput] pops
     * the segment and reading continues in the outer segment so the
     * partial line completes from the source after the alias body.
     */
    private fun readOneHeredocLine(quoted: Boolean): String {
        val sb = StringBuilder()
        while (hasMoreInput() && source[pos] != '\n') {
            if (!quoted && source[pos] == '\\' && pos + 1 < source.length && source[pos + 1] == '\n') {
                // Splice: consume `\` and the newline, continue reading on next line.
                pos += 2
                bumpLine()
                continue
            }
            sb.append(source[pos])
            pos++
        }
        return sb.toString()
    }

    /** Lex a heredoc body for parameter expansion (no quote handling — backslash-escape-aware). */
    private fun lexHeredocBody(
        body: String,
        bodyStartLine: Int = line,
    ): List<WordChunk> {
        val parts = mutableListOf<WordChunk>()
        val lit = StringBuilder()

        fun flush() {
            if (lit.isNotEmpty()) {
                parts += WordChunk.Literal(lit.toString())
                lit.clear()
            }
        }
        var i = 0
        while (i < body.length) {
            when (val c = body[i]) {
                '\\' if i + 1 < body.length &&
                    body[i + 1].let {
                        it == '$' || it == '`' || it == '\\' || it == '\n'
                    }
                -> {
                    flush()
                    parts += WordChunk.Escaped(body[i + 1].toString())
                    i += 2
                }

                '$' if i + 1 < body.length && body[i + 1] == '(' -> {
                    // `$(…)` inside heredoc body. Detect unterminated form
                    // explicitly so we can emit bash's "command substitution:
                    // line N: unexpected EOF while looking for matching `)'"
                    // warning instead of leaking the literal `$(…` text into
                    // the body. Arithmetic `$((…))` is handled via the same
                    // readDollarInStringAny path; we trust the existing
                    // success branch and only intercept the failure.
                    val (exp, consumed) = readDollarInStringAny(body, i)
                    if (exp != null) {
                        flush()
                        parts += exp
                        i += consumed
                    } else {
                        // Compute the line where the cmdsub was sealed off
                        // (= line of the heredoc's closing delim). The body
                        // string contains one '\n' per heredoc-body line; the
                        // closing delim's line is bodyStartLine + total
                        // newlines in body.
                        val totalNewlines = body.count { it == '\n' }
                        warnings +=
                            LexerWarning(
                                line = bodyStartLine + totalNewlines,
                                message =
                                    "command substitution: line ${bodyStartLine + totalNewlines}: " +
                                        "unexpected EOF while looking for matching `)'",
                                suppressLinePrefix = true,
                            )
                        // Drop the unterminated text from the body — bash
                        // leaves nothing of it in the variable value.
                        i = body.length
                    }
                }

                '$' -> {
                    val (exp, consumed) = readDollarInStringAny(body, i)
                    if (exp != null) {
                        flush()
                        parts += exp
                        i += consumed
                    } else {
                        lit.append('$')
                        i++
                    }
                }

                '`' -> {
                    // Heredoc body backtick command substitution. Bash
                    // applies `$`, `` ` ``, and `\` escape processing
                    // inside the backticks; everything else is literal.
                    val cmdStart = i + 1
                    val sb = StringBuilder()
                    var j = cmdStart
                    while (j < body.length && body[j] != '`') {
                        val ch = body[j]
                        if (ch == '\\' && j + 1 < body.length) {
                            val n = body[j + 1]
                            if (n == '`' || n == '$' || n == '\\') {
                                sb.append(n)
                                j += 2
                                continue
                            }
                        }
                        sb.append(ch)
                        j++
                    }
                    if (j >= body.length) {
                        // Unterminated — treat the backtick as literal so
                        // we don't fail the whole script over a stray
                        // heredoc-body character.
                        lit.append('`')
                        i++
                    } else {
                        flush()
                        parts += WordChunk.CommandSubstitution(sb.toString())
                        i = j + 1
                    }
                }

                else -> {
                    lit.append(c)
                    i++
                }
            }
        }
        flush()
        return parts
    }

    /**
     * Given [s] with `s[braceOpen] == '{'`, return the index of the matching
     * `}`, accounting for nested `${...}`, `$(...)`, `$'...'`, `'...'`,
     * `"..."`, and backslash escapes. Returns null if no match is found.
     */
    private fun findMatchingBrace(
        s: String,
        braceOpen: Int,
    ): Int? {
        var p = braceOpen + 1
        var depth = 1
        while (p < s.length) {
            when (val ch = s[p]) {
                '\\' -> {
                    p += 2
                }

                '\'' -> {
                    p++
                    while (p < s.length && s[p] != '\'') p++
                    if (p < s.length) p++
                }

                '"' -> {
                    p++
                    while (p < s.length && s[p] != '"') {
                        if (s[p] == '\\' && p + 1 < s.length) {
                            p += 2
                        } else {
                            p++
                        }
                    }
                    if (p < s.length) p++
                }

                '$' if p + 1 < s.length && s[p + 1] == '\'' -> {
                    // `$'...'` ANSI-C — `\'` is escape inside.
                    p += 2
                    while (p < s.length && s[p] != '\'') {
                        if (s[p] == '\\' && p + 1 < s.length) {
                            p += 2
                        } else {
                            p++
                        }
                    }
                    if (p < s.length) p++
                }

                '$' if p + 1 < s.length && s[p + 1] == '{' -> {
                    depth++
                    p += 2
                }

                '$' if p + 1 < s.length && s[p + 1] == '(' -> {
                    // Scan past the balanced `$(...)`.
                    var innerDepth = 1
                    p += 2
                    while (p < s.length && innerDepth > 0) {
                        when (s[p]) {
                            '\\' -> {
                                p += 2
                            }

                            '(' -> {
                                innerDepth++
                                p++
                            }

                            ')' -> {
                                innerDepth--
                                p++
                            }

                            else -> {
                                p++
                            }
                        }
                    }
                }

                '{' -> {
                    depth++
                    p++
                }

                '}' -> {
                    depth--
                    if (depth == 0) return p
                    p++
                }

                else -> {
                    p++
                }
            }
        }
        return null
    }

    /**
     * Like [readDollarInString] but returns any WordChunk (param, cmdsub,
     * arith), so callers in heredoc-body lexing can also fold `$(…)` /
     * `$((…))` into the parts list.
     */
    private fun readDollarInStringAny(
        s: String,
        start: Int,
    ): Pair<WordChunk?, Int> {
        if (start + 1 >= s.length) return null to 0
        val next = s[start + 1]
        if (next == '(') {
            if (start + 2 < s.length && s[start + 2] == '(') {
                // `$((...))` arithmetic. Find matching `))` accounting for
                // nested `(...)` inside the expression.
                var p = start + 3
                var depth = 1
                while (p < s.length && depth > 0) {
                    when (s[p]) {
                        '\\' -> {
                            p += 2
                        }

                        '(' -> {
                            depth++
                            p++
                        }

                        ')' -> {
                            if (depth == 1 && p + 1 < s.length && s[p + 1] == ')') {
                                return WordChunk.ArithmeticExpansion(
                                    s.substring(start + 3, p),
                                ) to (p + 2 - start)
                            }
                            depth--
                            p++
                        }

                        else -> {
                            p++
                        }
                    }
                }
                return null to 0
            }
            // `$(…)` modern command substitution. Find matching `)` with
            // simple paren-balance + escape awareness — full quote/heredoc
            // tracking isn't needed for heredoc-body context because the
            // recursive Parser will re-lex the body anyway.
            var p = start + 2
            var depth = 1
            while (p < s.length && depth > 0) {
                when (s[p]) {
                    '\\' -> {
                        p += 2
                    }

                    '\'' -> {
                        p++
                        while (p < s.length && s[p] != '\'') p++
                        if (p < s.length) p++
                    }

                    '"' -> {
                        p++
                        while (p < s.length && s[p] != '"') {
                            if (s[p] == '\\' && p + 1 < s.length) p += 2 else p++
                        }
                        if (p < s.length) p++
                    }

                    '(' -> {
                        depth++
                        p++
                    }

                    ')' -> {
                        depth--
                        if (depth == 0) break else p++
                    }

                    else -> {
                        p++
                    }
                }
            }
            if (depth != 0) return null to 0
            return WordChunk.CommandSubstitution(s.substring(start + 2, p)) to (p + 1 - start)
        }
        return readDollarInString(s, start)
    }

    private fun readDollarInString(
        s: String,
        start: Int,
    ): Pair<WordChunk.ParameterExpansion?, Int> {
        if (start + 1 >= s.length) return null to 0
        val next = s[start + 1]
        return when {
            next == '{' -> {
                // Find matching `}` with awareness of nested expansions,
                // single/double quotes, and `$'...'` ANSI-C strings so that
                // a `}` inside e.g. `${x#$'no\t'}` doesn't close prematurely.
                val end =
                    findMatchingBrace(s, start + 1)
                        ?: error($$"Unterminated ${...} at line $$line")
                val braceSubstr = s.substring(start, end + 1) // includes `${...}`
                // Reuse the full braced-param machinery via a sub-Lexer.
                val sub = Lexer(braceSubstr, aliasResolver, posixMode)
                val tokens = sub.tokenize()
                val word = tokens.firstNotNullOfOrNull { it as? Token.WordTok }
                val chunk = word?.parts?.firstNotNullOfOrNull { it as? WordChunk.ParameterExpansion }
                val fallback =
                    WordChunk.ParameterExpansion(
                        parameter = s.substring(start + 2, end),
                        braced = true,
                    )
                (chunk ?: fallback) to (end + 1 - start)
            }

            next == '?' || next == '@' || next == '#' ||
                next == '*' || next == '!' || next == '$' || next == '-' || next.isDigit() -> {
                // `$$` lexes as ParameterExpansion(parameter = "$") —
                // the shell's pid. ExpanderParameter handles the "$"
                // branch. POSIX §2.5.2.
                // `$-` is the live shell-options string (e.g. `ehB`).
                WordChunk.ParameterExpansion(next.toString()) to 2
            }

            next == '_' || next.isLetter() -> {
                var p = start + 2
                while (p < s.length && (s[p] == '_' || s[p].isLetterOrDigit())) p++
                WordChunk.ParameterExpansion(s.substring(start + 1, p)) to (p - start)
            }

            else -> {
                null to 0
            }
        }
    }

    /**
     * At a command-position, try to lex `NAME=`. Otherwise read a word; if the
     * resulting word is a single literal that's a reserved word, return a Keyword.
     */
    private fun readWordOrAssignment(
        atCommandStart: Boolean,
        assignOkAsWord: Boolean = false,
    ): Token {
        if (atCommandStart) {
            val lead = tryReadAssignmentName()
            if (lead != null) {
                val startLine = line
                // Array literal: `NAME=(a b c)` / `NAME+=(a b c)`. The `(` must
                // come immediately after `=` with no intervening word — that's
                // bash's disambiguator from `NAME= ( subshell )`.
                if (lead.subscript == null && pos < source.length && source[pos] == '(') {
                    // Bash requires the `)` of a compound assignment to be at
                    // word end — `NAME=(...)<more>` with trailing chars after
                    // the closing paren is NOT a compound assignment. For
                    // integer-attr vars `a=(4*3)/2` is a scalar assignment whose
                    // RHS is arithmetically evaluated; for plain vars it's a
                    // literal scalar containing parens. Save state, attempt
                    // the body, then verify the next char is a word terminator;
                    // if not, rewind and let readWordChunks consume the whole
                    // value.
                    val savedPos = pos
                    val savedLine = line
                    pos++ // consume `(`
                    val elements = readArrayLiteralBody()
                    val ok =
                        pos >= source.length ||
                            source[pos].let { c ->
                                c == ' ' || c == '\t' || c == '\n' || c == ';' ||
                                    c == '&' || c == '|' || c == '<' || c == '>' ||
                                    c == '(' || c == ')'
                            }
                    if (ok) {
                        return Token.ArrayAssignTok(lead.name, elements, lead.append, startLine)
                    }
                    // Trailing chars after `)` — `a=(4*3)/2` style. The whole
                    // `(...)/...` is the literal assignment value; bash gives it
                    // to the variable as-is (and if the var is typeset -i, the
                    // assignment path arith-evaluates it). Grab `(...)` verbatim
                    // from the source, then keep reading remaining word chars
                    // until a real word terminator. Quotes/expansions inside the
                    // tail still need normal processing, so append them via
                    // readWordChunks once `(...)` is captured.
                    val parenLit = source.substring(savedPos, pos)
                    val rest = readWordChunks(allowAssignmentEnd = false)
                    val value =
                        buildList<WordChunk> {
                            add(WordChunk.Literal(parenLit))
                            addAll(rest)
                        }
                    return Token.AssignmentTok(lead.name, value, lead.append, startLine)
                }
                // `NAME[sub]=(...)`: bash accepts at parse-time and rejects at
                // runtime ("cannot assign list to array member"). Consume the
                // parenthesized body so the parser stays happy; flag the
                // IndexedAssignTok so the runtime fires bash's diagnostic.
                if (lead.subscript != null && pos < source.length && source[pos] == '(') {
                    pos++ // consume `(`
                    readArrayLiteralBody() // discard — runtime won't use it
                    return Token.IndexedAssignTok(
                        lead.name,
                        lead.subscript,
                        emptyList(),
                        lead.append,
                        startLine,
                        listAttempt = true,
                    )
                }
                val value = readWordChunks(allowAssignmentEnd = false)
                return if (lead.subscript != null) {
                    Token.IndexedAssignTok(lead.name, lead.subscript, value, lead.append, startLine)
                } else {
                    Token.AssignmentTok(lead.name, value, lead.append, startLine)
                }
            }
        } else if (assignOkAsWord) {
            // eval/let-style assignment-OK parser state: absorb `NAME=(…)` as a single
            // WordTok so the `(` doesn't break the word. Re-use the
            // array-literal body reader so backslash/quote processing is
            // identical to the assignment-at-command-position path, then
            // flatten the parsed elements into a sequence of WordChunks
            // joined by literal space — eval will see exactly what
            // command-position would have produced.
            val startPos = pos
            val startLine = line
            val lead = tryReadAssignmentName()
            if (lead != null && lead.subscript == null && pos < source.length && source[pos] == '(') {
                pos++ // consume `(`
                val elements = readArrayLiteralBody()
                val chunks = mutableListOf<WordChunk>()
                chunks += WordChunk.Literal(lead.name + (if (lead.append) "+=" else "=") + "(")
                for ((idx, el) in elements.withIndex()) {
                    if (idx > 0) chunks += WordChunk.Literal(" ")
                    chunks += el
                }
                chunks += WordChunk.Literal(")")
                // For `let a=(4*3)/2` and similar, any trailing word-text
                // after the `)` is part of the SAME word — `let` will
                // arith-eval the whole concatenation. Without this read,
                // `/2` would tokenize as a separate operand and `let`
                // would re-eval it on its own, producing "unexpected `/'".
                if (pos < source.length) {
                    val tail = readWordChunks(allowAssignmentEnd = false)
                    chunks += tail
                }
                return Token.WordTok(chunks, startLine)
            }
            // Not an `=(…)` pattern; restore and fall through to normal read.
            pos = startPos
        }
        // Reserved-word recognition is deferred to the tokenize main loop
        // so it can run AFTER alias substitution — bash default mode allows
        // `alias for=echo`, where `for` should expand to `echo` rather than
        // be locked in as the for-loop keyword. POSIX §2.3.1 places alias
        // substitution before grammatical rules; reserved-word recognition
        // is a grammatical rule.
        val startLine = line
        val parts = readWordChunks(allowAssignmentEnd = false)
        return Token.WordTok(parts, startLine)
    }

    private data class AssignmentLead(
        val name: String,
        val subscript: List<WordChunk>?,
        val append: Boolean,
    )

    /**
     * Returns lead info if at command position we see `NAME=`, `NAME+=`,
     * `NAME[sub]=`, or `NAME[sub]+=`; else null. On success, [pos] has advanced
     * past the `=` / `+=` so the caller reads only the RHS.
     */
    private fun tryReadAssignmentName(): AssignmentLead? {
        if (pos >= source.length) return null
        val first = source[pos]
        if (!(first == '_' || first.isLetter())) return null
        var p = pos
        while (p < source.length && (source[p] == '_' || source[p].isLetterOrDigit())) p++
        if (p >= source.length) return null
        val name = source.substring(pos, p)

        // Optional `[subscript]` segment. Read raw bytes between the matching
        // brackets — bash defers actual evaluation to assignment time.
        var subscript: List<WordChunk>? = null
        if (source[p] == '[') {
            val subStart = p + 1
            var q = subStart
            var depth = 1
            while (q < source.length && depth > 0) {
                when (source[q]) {
                    '[' -> {
                        depth++
                        q++
                    }

                    ']' -> {
                        depth--
                        q++
                    }

                    '\\' -> {
                        q += 2
                    }

                    // Skip command-substitution body so its `]` doesn't close
                    // the outer subscript bracket. `m[$(echo ])]=v` — the `]`
                    // inside `$(...)` is part of the cmdsub, not the subscript
                    // terminator. We do a paren-balanced scan; nested cmdsubs
                    // also balance because we increment depth on each `(`.
                    '$' if q + 1 < source.length && source[q + 1] == '(' -> {
                        q += 2 // past `$(`
                        var pdepth = 1
                        while (q < source.length && pdepth > 0) {
                            when (source[q]) {
                                '(' -> {
                                    pdepth++
                                    q++
                                }

                                ')' -> {
                                    pdepth--
                                    q++
                                }

                                '\\' -> {
                                    if (q + 1 < source.length) q += 2 else q++
                                }

                                '\'' -> {
                                    q++
                                    while (q < source.length && source[q] != '\'') q++
                                    if (q < source.length) q++
                                }

                                '"' -> {
                                    q++
                                    while (q < source.length && source[q] != '"') {
                                        if (source[q] == '\\' && q + 1 < source.length) q += 2 else q++
                                    }
                                    if (q < source.length) q++
                                }

                                else -> {
                                    q++
                                }
                            }
                        }
                    }

                    // Quote-aware bracket finder: `[...]` content can contain
                    // `]` inside `"..."` or `'...'`. Skip the whole quoted
                    // span so the bracket close matches the outer `]`.
                    '"' -> {
                        q++
                        while (q < source.length && source[q] != '"') {
                            if (source[q] == '\\' && q + 1 < source.length) {
                                q += 2
                            } else {
                                q++
                            }
                        }
                        if (q < source.length) q++ // consume closing "
                    }

                    '\'' -> {
                        q++
                        while (q < source.length && source[q] != '\'') q++
                        if (q < source.length) q++ // consume closing '
                    }

                    else -> {
                        q++
                    }
                }
            }
            // Need a closing `]` and then `=` or `+=` to count as an assignment.
            if (depth != 0) return null
            val subEnd = q - 1
            // Allow empty subscript here (`a[]=v`) — runtime emits bash's
            // "bad array subscript" diagnostic. Rejecting at lex time
            // would force the token to parse as a command word, producing
            // a misleading "command not found" instead.
            if (subEnd < subStart) return null
            if (q >= source.length) return null
            val isAppendLocal = source[q] == '+' && q + 1 < source.length && source[q + 1] == '='
            if (!isAppendLocal && source[q] != '=') return null
            // Lex the subscript text as a sub-stream of word chunks. Save/restore
            // [pos] and [line] around the recursive call.
            val savedPos = pos
            val savedLine = line
            pos = subStart
            subscript =
                run {
                    val accum = mutableListOf<WordChunk>()
                    val lit = StringBuilder()
                    while (pos < subEnd) {
                        val c = source[pos]
                        when (c) {
                            '$' -> {
                                val before = pos
                                val exp = readParameterExpansion()
                                if (exp != null) {
                                    if (lit.isNotEmpty()) {
                                        accum += WordChunk.Literal(lit.toString())
                                        lit.clear()
                                    }
                                    accum += exp
                                } else {
                                    lit.append('$')
                                    if (pos == before) pos++
                                }
                            }

                            '\\' if pos + 1 < subEnd -> {
                                if (lit.isNotEmpty()) {
                                    accum += WordChunk.Literal(lit.toString())
                                    lit.clear()
                                }
                                accum += WordChunk.Escaped(source[pos + 1].toString())
                                pos += 2
                            }

                            '\'' -> {
                                if (lit.isNotEmpty()) {
                                    accum += WordChunk.Literal(lit.toString())
                                    lit.clear()
                                }
                                accum += readSingleQuoted()
                            }

                            '"' -> {
                                if (lit.isNotEmpty()) {
                                    accum += WordChunk.Literal(lit.toString())
                                    lit.clear()
                                }
                                accum += readDoubleQuoted()
                            }

                            else -> {
                                lit.append(c)
                                pos++
                            }
                        }
                    }
                    if (lit.isNotEmpty()) accum += WordChunk.Literal(lit.toString())
                    accum.toList()
                }
            pos = savedPos
            line = savedLine
            val past = if (isAppendLocal) q + 2 else q + 1
            pos = past
            return AssignmentLead(name, subscript, isAppendLocal)
        }

        val isAppend = source[p] == '+' && p + 1 < source.length && source[p + 1] == '='
        if (!isAppend && source[p] != '=') return null
        pos = if (isAppend) p + 2 else p + 1
        return AssignmentLead(name, null, isAppend)
    }

    /**
     * Read the body of an array literal `(...)` — the opening `(` has already
     * been consumed. Splits elements on whitespace/newlines; recognizes nested
     * structures only as far as a single shell word is allowed to contain
     * (quotes, parameter/command substitutions, escapes). `[k]=v` element form
     * is not yet supported — element strings beginning with `[` are taken as
     * regular words.
     */
    private fun readArrayLiteralBody(): List<List<WordChunk>> {
        val elements = mutableListOf<List<WordChunk>>()
        loop@ while (pos < source.length) {
            // Skip whitespace and newlines between elements.
            while (pos < source.length) {
                val c = source[pos]
                if (c == '\n') {
                    bumpLine()
                    pos++
                } else if (c.isWhitespace()) {
                    pos++
                } else {
                    break
                }
            }
            if (pos >= source.length) break
            val c = source[pos]
            if (c == ')') {
                pos++
                break@loop
            }
            if (c == '#') {
                skipComment()
                continue
            }
            // Control operators inside an array initializer body are a syntax
            // error in bash (e.g. `a=(x & y)` → "syntax error near unexpected
            // token `&'"). Reject with the bash-shaped diagnostic so the
            // parse error fires instead of silently dropping the operator.
            if (c == '&' || c == '|' || c == ';' || c == '<' || c == '>' || c == '(') {
                // bash recognizes `<>`, `<<`, `<&`, `>>`, `>&`, `>|`, `&&`,
                // `||`, `|&`, `;;`, `;&`, `;;&` as single tokens before the
                // single-char fallback. Use a small lookahead to name the
                // 2-char operator when applicable so the diagnostic matches
                // bash's "syntax error near unexpected token `<>'".
                val next = if (pos + 1 < source.length) source[pos + 1] else 0.toChar()
                val tok =
                    when {
                        c == '<' && (next == '>' || next == '<' || next == '&') -> "$c$next"
                        c == '>' && (next == '>' || next == '&' || next == '|') -> "$c$next"
                        c == '&' && (next == '&') -> "$c$next"
                        c == '|' && (next == '|' || next == '&') -> "$c$next"
                        c == ';' && (next == ';' || next == '&') -> "$c$next"
                        else -> c.toString()
                    }
                throw ParseException(
                    "syntax error near unexpected token `$tok'",
                    line = line,
                    offendingToken = tok,
                )
            }
            val before = pos
            // Bash array-literal element with bracketed subscript:
            // `[KEY]=VALUE` is one token EVEN IF `KEY` contains spaces
            // (`wheat=([foo bar]="qux qix" )` — verified bash 5.3). The
            // bracket pair acts as a quote-context for whitespace until
            // the matching unescaped `]`. We pass that mode into
            // readWordChunks so quotes/expansions inside the brackets
            // produce properly-typed WordChunks (single-quoted `'ab]'`
            // becomes literal `ab]`, not `'ab]'`), unlike a raw substring.
            val elem = readWordChunks(allowAssignmentEnd = false, bracketBalanced = (c == '['))
            if (elem.isNotEmpty()) {
                elements += elem
            } else if (pos == before) {
                // readWordChunks made no progress on a non-operator char.
                // Skip to avoid an infinite loop.
                pos++
            }
        }
        return elements
    }

    @Suppress("UNUSED_PARAMETER")
    private fun readWordChunks(
        allowAssignmentEnd: Boolean,
        // When true, an unbalanced `[` opens a context where whitespace
        // is NOT a word terminator until the matching `]` closes it.
        // Bash array-literal elements (`name=( [foo bar]=v )`) need
        // this so `[foo bar]=...` reads as ONE word instead of three.
        // Quotes inside the brackets still parse normally (so
        // `['ab]'`'s inner `]` doesn't close the bracket).
        bracketBalanced: Boolean = false,
    ): List<WordChunk> {
        val parts = mutableListOf<WordChunk>()
        val lit = StringBuilder()
        var bracketDepth = 0
        // Inside `[[`, the rhs of `=~` is a regex — `(` opens a balanced group
        // that's part of the word, not a control-operator terminator. Pull the
        // arm-bit once at entry: the read consumes this whole word; subsequent
        // operands in the same `[[ ]]` need normal word semantics.
        val regexRhsMode = activeState?.regexRhsArmed == true

        fun flushLit() {
            if (lit.isNotEmpty()) {
                parts += WordChunk.Literal(lit.toString())
                lit.clear()
            }
        }
        loop@ while (pos < source.length) {
            val c = source[pos]
            when {
                // Extglob prefixes `?(`, `*(`, `+(`, `@(`, `!(` consume a balanced `(...)` group
                // as part of the word. Without this, the `(` would terminate the word and the
                // pattern would be mis-parsed (eg. inside `[[ X == +([0-9]) ]]`).
                extglobEnabled && c in "?*+@!" && pos + 1 < source.length && source[pos + 1] == '(' -> {
                    lit.append(c)
                    pos++
                    val groupStart = pos
                    var depth = 0
                    while (pos < source.length) {
                        val ch = source[pos]
                        when (ch) {
                            '(' -> {
                                depth++
                                pos++
                            }

                            ')' -> {
                                depth--
                                pos++
                                if (depth == 0) break
                            }

                            '\n' -> {
                                bumpLine()
                                pos++
                            }

                            else -> {
                                pos++
                            }
                        }
                    }
                    lit.append(source, groupStart, pos)
                }

                // Regex rhs of `=~` inside `[[ ]]`: consume a balanced `(...)`
                // group into the literal so `[[ x =~ a(b)c ]]` reads `a(b)c`
                // as one word instead of breaking on `(`.
                regexRhsMode && c == '(' -> {
                    val groupStart = pos
                    var depth = 0
                    while (pos < source.length) {
                        val ch = source[pos]
                        when (ch) {
                            '(' -> {
                                depth++
                                pos++
                            }

                            ')' -> {
                                depth--
                                pos++
                                if (depth == 0) break
                            }

                            '\n' -> {
                                bumpLine()
                                pos++
                            }

                            else -> {
                                pos++
                            }
                        }
                    }
                    lit.append(source, groupStart, pos)
                }

                bracketBalanced && c == '[' -> {
                    bracketDepth++
                    lit.append(c)
                    pos++
                }

                bracketBalanced && c == ']' && bracketDepth > 0 -> {
                    bracketDepth--
                    lit.append(c)
                    pos++
                }

                c.isWhitespace() && bracketBalanced && bracketDepth > 0 -> {
                    // Whitespace inside `[ … ]` is part of the key, not
                    // a word terminator. Bash `name=( [foo bar]=v )`.
                    lit.append(c)
                    pos++
                }

                c.isWhitespace() || isOperatorStart(c) -> {
                    break@loop
                }

                // Process substitution `<(...)` / `>(...)` — recognized as a
                // word-level chunk. Must be tested BEFORE the `<`/`>` break so
                // mid-word `cat <(echo hi)` doesn't terminate the word at `<`.
                (c == '<' || c == '>') && pos + 1 < source.length && source[pos + 1] == '(' -> {
                    flushLit()
                    parts += readProcessSubstitution(direction = c)
                }

                c == '<' || c == '>' -> {
                    break@loop
                }

                c == '\'' -> {
                    flushLit()
                    parts += readSingleQuoted()
                }

                c == '"' -> {
                    flushLit()
                    parts += readDoubleQuoted()
                }

                c == '`' -> {
                    flushLit()
                    parts += readBacktickSubstitution()
                }

                c == '\\' -> {
                    flushLit()
                    pos++
                    // Backslash-continuation across alias-body boundary:
                    // `alias a='printf "<%s>\n" \'; a|cat` — body ends with
                    // `\`, the escape target comes from outer source (`|`),
                    // yielding `printf "<%s>\n" \|cat`. popExhausted makes
                    // the next char read transparently see outer.
                    popExhausted()
                    if (pos < source.length) {
                        val esc = source[pos]
                        if (esc == '\n') bumpLine() else parts += WordChunk.Escaped(esc.toString())
                        pos++
                    } else {
                        // Dangling `\` at end-of-input (e.g. `bash -c 'echo
                        // escape\'`). The backslash has nothing to escape,
                        // so it survives as a literal — POSIX leaves this
                        // unspecified but bash echoes the bare `\`.
                        lit.append('\\')
                        flushLit()
                    }
                }

                c == '$' -> {
                    val before = pos
                    val exp = readParameterExpansion()
                    if (exp != null) {
                        flushLit()
                        parts += exp
                    } else {
                        lit.append('$')
                        if (pos == before) pos++
                    }
                }

                else -> {
                    lit.append(c)
                    pos++
                }
            }
        }
        flushLit()
        return parts
    }

    private fun readParameterExpansion(): WordChunk? {
        check(source[pos] == '$')
        if (pos + 1 >= source.length) return null
        val next = source[pos + 1]
        return when {
            next == '\'' -> {
                // `$'...'` ANSI-C quoting — `\n`, `\t`, `\xHH`, `\'`, etc. are interpreted.
                // POSIX/bash gates this in three ways:
                //   1. Directly inside `"..."` outside any `${...}`: `$` is
                //      literal (no ANSI-C); the carve-out is that inside a
                //      `${...}` body even within `"..."`, recognition is
                //      restored — that's the `braceDepth == 0` half.
                //   2. Inside the operand of a BARE default-value op (`-`,
                //      `+`, `=`, `?`): `$'...'` is taken LITERALLY,
                //      including the surrounding `'` quotes. The whole
                //      `$'BODY'` sequence is emitted as text. Empirically
                //      verified against bash 5.2 via nquote5.sub:
                //      `${none-a$'\01'b}` → 9-char literal `a$'\01'b`.
                //   3. Colon-prefixed default-value ops (`:-`, `:+`, `:=`,
                //      `:?`) and pattern ops (`#`, `##`, `%`, `%%`, `/`,
                //      `//`, `^`, `,`, …) recognize `$'...'` normally.
                //   4. Otherwise → recognize as ANSI-C.
                when {
                    inDefaultValueOpOperand -> readDollarSingleQuotedLiteral()
                    dqDepth > 0 && braceDepth == 0 -> null
                    else -> readDollarSingleQuoted()
                }
            }

            next == '"' -> {
                // `$"..."` is a locale-translated string. Without a translation table (always
                // our case) it behaves identically to `"..."`. Consume the leading `$` and
                // return the double-quoted chunk so no stray `$` ends up in the output.
                if (dqDepth > 0 && braceDepth == 0) {
                    null
                } else {
                    pos++ // past `$`
                    readDoubleQuoted()
                }
            }

            next == '{' -> {
                // Bash-5.2 `${ cmd; }` no-fork command substitution — whitespace after `${`
                // disambiguates from a parameter expansion `${VAR}`.
                if (pos + 2 < source.length && source[pos + 2].isWhitespace()) {
                    readBraceCommandSubstitution()
                } else {
                    readBracedParameterExpansion()
                }
            }

            next == '(' -> {
                if (pos + 2 < source.length && source[pos + 2] == '(') {
                    // `$((...))` if the content closes with `))`; otherwise it's `$( (...)` — a
                    // subshell inside a regular command substitution. Try-and-rewind.
                    tryReadArithmeticExpansion() ?: readCommandSubstitution()
                } else {
                    readCommandSubstitution()
                }
            }

            next == '?' || next == '@' || next == '#' ||
                next == '*' || next == '!' || next == '$' || next == '-' || next.isDigit() -> {
                // `$$` lexes as ParameterExpansion(parameter = "$") —
                // the shell process's pid. ExpanderParameter handles
                // the "$" branch. POSIX §2.5.2.
                // `$-` is the live shell-options string (e.g. `ehB`).
                pos += 2
                WordChunk.ParameterExpansion(next.toString())
            }

            next == '_' || next.isLetter() -> {
                pos++
                val start = pos
                while (pos < source.length && (source[pos] == '_' || source[pos].isLetterOrDigit())) pos++
                WordChunk.ParameterExpansion(source.substring(start, pos))
            }

            else -> {
                null
            }
        }
    }

    // Capture `$(...)` — paren-balanced, quote-aware, and case-aware. Plain
    // paren-balance gets fooled by case patterns whose `)` looks like a closing
    // paren but is really a pattern terminator. We track case...esac blocks and
    // the in-pattern-position state inside each, so a `)` at depth 1 is consumed
    // as a pattern terminator when the top case frame is at pattern position.
    // Heredocs inside the body are also skipped so a `)` in their body doesn't
    // close the surrounding `$(...)`.
    private fun readCommandSubstitution(): WordChunk.CommandSubstitution {
        // pos at `$`. Advance past `$(`.
        val openLine = line
        pos += 2
        var start = pos
        // Captured-text spans accumulated across Lexer segment pops.
        // When the body scanner's current segment (alias body) exhausts
        // and the underlying segment has more chars (the outer source),
        // we pop, save the just-consumed alias-body slice into [crossSegmentSpans],
        // and resume the scan in the outer segment with a fresh [start].
        // Empty in the common single-segment case — text is then built
        // by the existing `source.substring(start, pos)` path.
        val crossSegmentSpans = mutableListOf<CharSequence>()

        fun popExhaustedDuringCmdsub(): Boolean {
            // Mirror of Lexer.popExhausted but with span capture so
            // alias-body content opened by an outer alias expansion
            // (e.g. `alias x='echo $('; x ...)`) survives into the
            // captured cmdsub body text.
            var popped = false
            while (stack.size > 1 && top.pos >= top.source.length) {
                val oldSource = source
                if (start < oldSource.length) {
                    crossSegmentSpans += oldSource.subSequence(start, oldSource.length)
                }
                val poppedSeg = stack.removeFirst()
                top = stack.first()
                if (poppedSeg.chainAfter) activeState?.chainNextWord = true
                start = pos
                popped = true
            }
            return popped
        }
        var depth = 1

        // When a heredoc body inside the cmdsub soft-terminates (delim
        // appears as a prefix on a line that also closes the cmdsub),
        // the outer scanner rewinds past the offending line. The cmdsub
        // text captured at the end would then include the offending line
        // (e.g. `EOF )`), which would confuse the recursive Lexer's
        // heredoc body reader and leak `EOF ` into the body. To prevent
        // that, record a patch: cut the offending line out of the
        // captured text and inject a synthetic delim-only terminator
        // line so the recursive Lexer sees a clean body.
        data class Patch(
            val cutFrom: Int,
            val cutTo: Int,
            val replacement: String,
            /**
             * Heredoc soft-term patches truncate the captured text: the
             * replacement *replaces* everything from [cutFrom] onward. Alias
             * splicing patches don't truncate — the source range
             * [cutFrom, cutTo) is excised and replaced; source after
             * [cutTo] resumes normally.
             */
            val truncate: Boolean = false,
        )
        val patches = mutableListOf<Patch>()
        // Each entry = "is the top frame currently expecting a pattern?". `case ... in`
        // pushes a frame in pattern-pos; `;;` resets to pattern-pos; `)` consumes that.
        // Per case frame: (patternExpected, lastOpWasPipe). `case ... in`
        // pushes (true, false). A pattern terminator `)` sets pE=false,
        // lastOpWasPipe=false. `;;` sets pE=true, lastOpWasPipe=false. A
        // pipe `|` inside pattern position sets lastOpWasPipe=true (this
        // is what tells us we're mid-alternation). `esac` is treated as a
        // bare-word pattern (not the case-closer) iff we're in pattern-
        // expected position AND either:
        //   - the previous significant op was `|` (so `esac` is the next
        //     alternative — `... |esac)`), or
        //   - the very next char is `|` (so `esac` starts the next
        //     alternative — `esac|...`)
        // Otherwise `esac` pops the frame.
        val caseStack = ArrayDeque<Pair<Boolean, Boolean>>()

        fun caseTop() = caseStack.lastOrNull()

        fun setCaseTop(
            pe: Boolean,
            lastPipe: Boolean,
        ) {
            caseStack[caseStack.size - 1] = pe to lastPipe
        }
        // Depths at which a `(` opened a leading-paren case-pattern (rather
        // than a real subshell). When the matching `)` brings depth back to
        // the entry's value, treat it as the pattern terminator (transition
        // the top case-frame to body mode) instead of a subshell-end.
        val casePatternParenDepths = ArrayDeque<Int>()
        // Set by consuming a `case` keyword; cleared by the next word
        // (which becomes the case-word). Suppresses keyword interpretation
        // of `esac`/`in`/etc. while the case-word slot is still open.
        var pendingCaseWord = false
        // Pending heredoc (delim, openLine) — consumed at the next newline.
        val pendingHd = ArrayDeque<Pair<String, Int>>()
        // Tracks whether we've consumed at least one non-whitespace char
        // since the last `case` keyword. Used together with pendingCaseWord:
        // once we've seen content (the case-word), the next whitespace
        // boundary marks end-of-word and clears pendingCaseWord. This is
        // what lets `case $line in` work — the `$line` clears the slot
        // before `in` is interpreted as the keyword.
        var sawCaseWordContent = false
        // True when depth reached 0 inside an alias body's character
        // stream (not from a `)` in source). The closing-paren skip below
        // must NOT advance source pos in that case — the `\n` after the
        // alias name is a statement terminator, not the cmdsub close.
        var closedByAlias = false
        // Drain any already-exhausted segments before the main loop
        // (defensive — common case is no pops needed).
        popExhaustedDuringCmdsub()
        // Outer loop pops exhausted Lexer segments and re-enters the
        // body scanner so an alias body that opened `$(` can have its
        // closing `)` come from the underlying outer segment
        // (comsub6.sub:27 `alias comsub0='echo $(echo $DATE'; comsub0)`).
        outerLoop@ while (true) {
            while (pos < source.length && depth > 0) {
                val c = source[pos]
                val isWs = c == ' ' || c == '\t' || c == '\n'
                if (pendingCaseWord && isWs && sawCaseWordContent) {
                    pendingCaseWord = false
                    sawCaseWordContent = false
                }
                if (pendingCaseWord && !isWs && c != '#') {
                    sawCaseWordContent = true
                }
                when (c) {
                    // `<<<` here-string: content is on the same line; no heredoc body to skip.
                    '<' if pos + 2 < source.length && source[pos + 1] == '<' && source[pos + 2] == '<' -> {
                        pos += 3
                    }

                    '<' if pos + 1 < source.length && source[pos + 1] == '<' -> {
                        // `<<` or `<<-` — capture the delimiter so we can skip the heredoc body.
                        val hdOpenLine = line
                        pos += 2
                        if (pos < source.length && source[pos] == '-') pos++
                        while (pos < source.length && (source[pos] == ' ' || source[pos] == '\t')) pos++
                        val delim = readComsubHeredocDelimiter()
                        if (delim.isNotEmpty()) pendingHd.addLast(delim to hdOpenLine)
                    }

                    '(' -> {
                        // If we're at the top of a pattern-expected case frame,
                        // remember that this `(` opens a leading-paren case
                        // pattern (rather than a real subshell). When the
                        // matching `)` returns us to this depth, we'll treat it
                        // as the pattern terminator.
                        val top = caseTop()
                        if (top != null && top.first) {
                            casePatternParenDepths.addLast(depth)
                        }
                        depth++
                        pos++
                    }

                    ')' -> {
                        val top = caseTop()
                        if (depth == 1 && top != null && top.first) {
                            // Case-pattern terminator, not the closing $(. Consume and leave
                            // the case frame in body mode until the next `;;`/`esac`.
                            setCaseTop(false, false)
                            pos++
                        } else if (casePatternParenDepths.isNotEmpty() &&
                            casePatternParenDepths.last() == depth - 1
                        ) {
                            // Closing `)` of a leading-paren case pattern — drop
                            // back to that depth and transition the case frame to
                            // body mode.
                            casePatternParenDepths.removeLast()
                            depth--
                            if (caseStack.isNotEmpty()) setCaseTop(false, false)
                            pos++
                        } else {
                            depth--
                            if (depth == 0) break
                            pos++
                        }
                    }

                    ';' -> {
                        if (pos + 1 < source.length && source[pos + 1] == ';') {
                            val t = caseTop()
                            if (t != null) setCaseTop(true, false)
                            pos += 2
                        } else {
                            pos++
                        }
                    }

                    '|' -> {
                        // In pattern-expected position at depth 1, `|` separates
                        // pattern alternatives — flag so a following `esac` is
                        // recognized as the last alternative (`...|esac)`),
                        // not as the case-closer.
                        val t = caseTop()
                        if (depth == 1 && t != null && t.first) {
                            setCaseTop(true, true)
                        }
                        pos++
                    }

                    '\'' -> {
                        skipSingleQuotedBody()
                    }

                    '"' -> {
                        skipDoubleQuotedBody()
                    }

                    '\\' -> {
                        pos++
                        if (pos < source.length) {
                            if (source[pos] == '\n') bumpLine()
                            pos++
                            // Absorb the rest of the current word — every char
                            // up to the next token-boundary stays part of the
                            // same literal word as `\X`. Without this, the
                            // body scanner reads `\#esac` as `\#` + `esac`
                            // and mis-recognizes the `esac` as a case-closer
                            // (comsub1.sub:26 `echo \#esac`); similarly `\;#`
                            // would otherwise have its `#` trigger the
                            // comment heuristic (comsub1.sub:22 `\;#`).
                            while (pos < source.length) {
                                val ch = source[pos]
                                if (ch.isWhitespace() ||
                                    ch == ';' || ch == '&' || ch == '|' ||
                                    ch == '(' || ch == ')' || ch == '<' || ch == '>' ||
                                    ch == '"' || ch == '\'' || ch == '`' || ch == '\\'
                                ) {
                                    break
                                }
                                pos++
                            }
                        }
                    }

                    '\n' -> {
                        bumpLine()
                        pos++
                        // Drain pending heredocs — skip past each body to its terminator.
                        while (pendingHd.isNotEmpty()) {
                            val (delim, openLine) = pendingHd.removeFirst()
                            val outcome = skipPastHeredocBodyInGroup(delim, openLine)
                            outcome.softTermLineStart?.let { cut ->
                                // Cut the offending line out of the captured
                                // text (the caller doesn't know its end yet —
                                // the outer scanner will consume up to `)`),
                                // and inject a synthetic `<delim>\n` so the
                                // recursive Lexer sees a clean terminator and
                                // doesn't drag the line's leading delim text
                                // into the heredoc body.
                                patches +=
                                    Patch(
                                        cutFrom = cut,
                                        cutTo = cut, // closed at end-of-text patch below
                                        replacement = "$delim\n",
                                        truncate = true,
                                    )
                            }
                        }
                    }

                    '#' -> {
                        // `#` starts a shell comment only at word-start (prev char is whitespace,
                        // start, or an operator). Mid-word `#` is literal (e.g. `${X#prefix}`).
                        val prev = if (pos > start) source[pos - 1] else ' '
                        if (prev.isWhitespace() || prev == ';' || prev == '&' || prev == '|' || prev == '(') {
                            while (pos < source.length && source[pos] != '\n') pos++
                        } else {
                            pos++
                        }
                    }

                    else -> {
                        if (c.isLetter() || c == '_') {
                            val wstart = pos
                            while (pos < source.length &&
                                (source[pos].isLetterOrDigit() || source[pos] == '_')
                            ) {
                                pos++
                            }
                            // Only treat as a keyword if word-bounded (prev char isn't a name char).
                            val prev = if (wstart > 0) source[wstart - 1] else ' '
                            val isWordStart = !(prev.isLetterOrDigit() || prev == '_' || prev == '$')
                            if (isWordStart) {
                                val rawW = source.substring(wstart, pos)
                                // Alias-aware cmdsub body scan: bash's POSIX
                                // expand_aliases-in-cmdsub feature (
                                // comsub scanner) treats command-position alias
                                // names AT BOUND-FINDING TIME so the boundary
                                // reflects what the body becomes after alias
                                // substitution. We simulate the body's effect
                                // on depth/case state via a mini-scanner. If
                                // the body would close the cmdsub mid-body
                                // (depth → 0), we capture the body up to that
                                // close and stop. Otherwise we splice the
                                // body into captured text and continue the
                                // outer scan against the post-body state.
                                val aliasBody =
                                    if (aliasResolver === AliasResolver.Empty) {
                                        null
                                    } else {
                                        aliasResolver.lookup(rawW)
                                    }
                                // Process command-position aliases only — and
                                // only outside a case-word slot, so the case
                                // expression word doesn't accidentally get
                                // alias-expanded.
                                val isAtCommandPosition =
                                    run {
                                        if (pendingCaseWord) return@run false
                                        // Walk back over whitespace to find the
                                        // previous non-WS char; command position
                                        // when it's a separator or absent.
                                        var k = wstart - 1
                                        while (k >= start && (source[k] == ' ' || source[k] == '\t')) k--
                                        k < start ||
                                            source[k].let {
                                                it == ';' || it == '&' || it == '|' ||
                                                    it == '(' || it == '\n'
                                            }
                                    }
                                if (aliasBody != null && isAtCommandPosition) {
                                    val sim =
                                        simulateAliasBodyForCmdsubBound(
                                            aliasBody,
                                            depth,
                                            caseStack,
                                            casePatternParenDepths,
                                        )
                                    // Splice alias body into captured text ONLY
                                    // when the body closes the cmdsub mid-body
                                    // (e.g. `alias short='echo ok 8 )'; $( short`).
                                    // For all other cases (body doesn't close),
                                    // leave the alias name in the captured text:
                                    // runtime re-parse will alias-expand it
                                    // again, matching the lex-time depth update.
                                    // Splicing in the non-close case would cause
                                    // double-expansion (e.g. `alias let='let --'`
                                    // would become `let -- -- args` at runtime).
                                    if (sim.closedCmdsub) {
                                        patches +=
                                            Patch(
                                                cutFrom = wstart,
                                                cutTo = pos,
                                                replacement = sim.consumedBody,
                                            )
                                    }
                                    // Apply state updates from the simulation.
                                    depth = sim.newDepth
                                    // Restore caseStack/casePatternParenDepths
                                    // from the simulation's mutated copies.
                                    caseStack.clear()
                                    caseStack.addAll(sim.newCaseStack)
                                    casePatternParenDepths.clear()
                                    casePatternParenDepths.addAll(sim.newCasePatternParenDepths)
                                    if (sim.closedCmdsub) {
                                        // Alias body contained the `)` that
                                        // closed the cmdsub. Source cursor
                                        // stays at end of alias name (already
                                        // there); break out of the main loop
                                        // without consuming a source `)`.
                                        depth = 0
                                        closedByAlias = true
                                        break
                                    }
                                    if (sim.pendingCaseWord) pendingCaseWord = true
                                    // Continue main loop — don't run the
                                    // word-keyword check on rawW; the alias
                                    // body replaced it.
                                    continue
                                }
                                val w = rawW
                                // If the previous step left us "expecting a
                                // case-word" (i.e. we just consumed `case`),
                                // this word IS the case-word — don't treat it
                                // as a keyword even if its text matches `esac`
                                // / `in`. Clearing the flag here lets the next
                                // tokens follow the normal keyword rules.
                                if (pendingCaseWord) {
                                    pendingCaseWord = false
                                } else {
                                    when (w) {
                                        "case" -> {
                                            caseStack.addLast(false to false)
                                            pendingCaseWord = true
                                            sawCaseWordContent = false
                                        }

                                        "in" -> {
                                            // Only the *first* `in` after `case`
                                            // is the case-introducer keyword.
                                            // Once pE flipped to true, any later
                                            // `in` is a pattern WORD (e.g.
                                            // `case x in in|y)`) — don't reset
                                            // lastOpWasPipe.
                                            val t = caseTop()
                                            if (t != null && !t.first) setCaseTop(true, false)
                                        }

                                        "esac" -> {
                                            val t = caseTop()
                                            val nextIsPipe =
                                                pos < source.length && source[pos] == '|'
                                            val isPattern =
                                                t != null && t.first && (t.second || nextIsPipe)
                                            if (!isPattern && caseStack.isNotEmpty()) {
                                                caseStack.removeLast()
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            pos++
                        }
                    }
                }
            }
            // End of inner loop. Either depth hit 0 (success) or the
            // current segment exhausted; the outer loop decides whether
            // to pop & retry.
            if (depth == 0) break@outerLoop
            if (!popExhaustedDuringCmdsub()) break@outerLoop
        }
        if (depth > 0) {
            // Bash diagnostic format: `<script>: line N: unexpected EOF while
            // looking for matching ')'`. Throw a ParseException so the
            // interpreter's [emitShellParseError] can prepend the script
            // prefix; without the line carried, the error falls back to
            // the generic `sh: <msg>` shape (zero info). Bash reports the
            // line of the EOF (one past the last content line), not the
            // line where `$(` opened.
            throw ParseException(
                "unexpected EOF while looking for matching `)'",
                line = line,
            )
        }
        // Bash recovery: cmdsub closed with unfulfilled here-document(s).
        // Bash emits a warning and pulls the body from OUTER source past
        // the `)`. The captured text grows to include the body and delim
        // line so the recursive lexer sees a well-formed heredoc.
        //
        // Reproduces `echo $(cat <<EOF)\nfoo\nbar\nEOF\nafter`:
        //   - capture = `cat <<EOF` BEFORE recovery
        //   - recovery advances past `)`, reads `foo\nbar\nEOF\n` from
        //     root source, appends to capture
        //   - capture = `cat <<EOF\nfoo\nbar\nEOF\n` AFTER
        //   - cmdsub re-parse executes `cat` with body `foo\nbar`
        //   - outer parse continues at `after\n`
        // `cmdsubBodyEnd` is the captured slice's end (exclusive), i.e.
        // the position of the closing `)`. Normally equal to [pos]; the
        // unterminated-heredoc recovery below advances [pos] past the
        // heredoc body in OUTER source, so the slice-end stays pinned
        // to the `)` and the heredoc text is appended via [suffix].
        val cmdsubBodyEnd = pos
        var unterminatedHeredocSuffix: String? = null
        if (pendingHd.isNotEmpty() && stack.size == 1) {
            warnings +=
                LexerWarning(
                    line = openLine,
                    message =
                        "warning: command substitution: ${pendingHd.size} " +
                            "unterminated here-document" +
                            (if (pendingHd.size > 1) "s" else ""),
                )
            // Bash's recovery: the heredoc body comes from OUTER source
            // lines following the cmdsub's `)`. Walk past `)` + its
            // terminating newline (which is the newline ENDING the
            // command-line containing `$(...)`, not part of the body),
            // then drain each pending heredoc against outer source.
            // After consumption, rewind pos by one so the outer Lexer
            // sees the trailing `\n` of the delim line as its own
            // statement separator — without that, the outer word reader
            // would glue the next outer command onto the cmdsub-bearing
            // word, producing `foo barafter` instead of `foo bar` +
            // `after: command not found` (bash heredoc7.sub line 17).
            pos++ // past `)`
            if (pos < source.length && source[pos] == '\n') {
                pos++ // past the newline ending the cmdsub-line
                bumpLine()
            }
            closedByAlias = true
            val suffix = StringBuilder()
            while (pendingHd.isNotEmpty()) {
                val (delim, openL) = pendingHd.removeFirst()
                val bodyStart = pos
                skipPastHeredocBodyInGroup(delim, openL)
                suffix.append('\n')
                suffix.append(source, bodyStart, pos)
            }
            // Rewind onto the trailing newline that skipPastHeredocBody
            // consumed past, so the outer tokenize loop sees a fresh
            // statement boundary instead of stitching the next line to
            // the cmdsub's host word.
            if (pos > 0 && source[pos - 1] == '\n') {
                pos--
                if (stack.size == 1) line--
            }
            unterminatedHeredocSuffix = suffix.toString()
        }
        // Build the substring fed to the recursive Lexer. Two patch
        // sources are interleaved into the source slice:
        //   - heredoc soft-term fixup (truncate=true; replaces the
        //     offending tail line and everything after with a synthetic
        //     delim-only terminator)
        //   - alias-body splicing (truncate=false; excises the alias
        //     name from [cutFrom, cutTo) and inserts the alias body;
        //     source resumes normally after [cutTo])
        // Compute the final segment's slice (patches-aware).
        val finalSlice: CharSequence =
            if (patches.isEmpty()) {
                source.subSequence(start, cmdsubBodyEnd)
            } else {
                val sorted = patches.sortedBy { it.cutFrom }
                buildString {
                    var cursor = start
                    for (p in sorted) {
                        if (p.cutFrom > cursor) append(source, cursor, p.cutFrom)
                        append(p.replacement)
                        if (p.truncate) {
                            cursor = cmdsubBodyEnd
                            break
                        }
                        cursor = maxOf(cursor, p.cutTo)
                    }
                    if (cursor < cmdsubBodyEnd) append(source, cursor, cmdsubBodyEnd)
                }
            }
        val text =
            buildString {
                for (s in crossSegmentSpans) append(s)
                append(finalSlice)
                unterminatedHeredocSuffix?.let { append(it) }
            }
        if (!closedByAlias) pos++ // past closing `)` in source
        return WordChunk.CommandSubstitution(text, openLine = openLine)
    }

    /**
     * Result of [simulateAliasBodyForCmdsubBound]: the body content
     * consumed (up to the close, or the full body if no close), the
     * post-body state for the outer scanner to resume against, and a
     * flag indicating whether the alias body closed the cmdsub.
     */
    private class AliasSimResult(
        val consumedBody: String,
        val newDepth: Int,
        val newCaseStack: List<Pair<Boolean, Boolean>>,
        val newCasePatternParenDepths: List<Int>,
        val closedCmdsub: Boolean,
        val pendingCaseWord: Boolean,
    )

    /**
     * Mirror the cmdsub body scanner's state machine over an alias-body
     * string. Used by [readCommandSubstitution] to find the cmdsub close
     * boundary when a command-position alias name appears in the body —
     * matching bash's posix-mode "alias-aware bound finding" behavior.
     *
     * Tracks: paren depth, `case`/`esac`/`in` keyword keyword detection,
     * leading-paren case-pattern terminators, single/double quote skipping.
     * Does NOT recursively expand aliases nested inside the body (POSIX
     * rule 5 recursion guard; also keeps this self-contained).
     */
    private fun simulateAliasBodyForCmdsubBound(
        body: String,
        startDepth: Int,
        startCaseStack: ArrayDeque<Pair<Boolean, Boolean>>,
        startCasePatternParenDepths: ArrayDeque<Int>,
    ): AliasSimResult {
        var d = startDepth
        val cs = ArrayDeque(startCaseStack.toList())
        val cpp = ArrayDeque(startCasePatternParenDepths.toList())
        var pendingCw = false
        var sawCwContent = false
        var i = 0
        var closed = false
        var closeIdx = body.length

        fun setCsTop(
            pe: Boolean,
            lastPipe: Boolean,
        ) {
            cs[cs.size - 1] = pe to lastPipe
        }

        while (i < body.length) {
            val c = body[i]
            val isWs = c == ' ' || c == '\t' || c == '\n'
            if (pendingCw && isWs && sawCwContent) {
                pendingCw = false
                sawCwContent = false
            }
            if (pendingCw && !isWs && c != '#') sawCwContent = true
            when {
                c == '\'' -> {
                    // Skip single-quoted region.
                    i++
                    while (i < body.length && body[i] != '\'') i++
                    if (i < body.length) i++
                }

                c == '"' -> {
                    // Skip double-quoted region — track `\` escapes; do
                    // NOT enter nested `$(...)`/`${...}` here (the bound
                    // finder for those is a separate concern; this matcher
                    // only needs paren balance for the outer cmdsub).
                    i++
                    while (i < body.length && body[i] != '"') {
                        if (body[i] == '\\' && i + 1 < body.length) i += 2 else i++
                    }
                    if (i < body.length) i++
                }

                c == '\\' && i + 1 < body.length -> {
                    i += 2
                }

                c == '(' -> {
                    val top = cs.lastOrNull()
                    if (top != null && top.first) cpp.addLast(d)
                    d++
                    i++
                }

                c == ')' -> {
                    val top = cs.lastOrNull()
                    when {
                        d == startDepth && top != null && top.first -> {
                            // Case-pattern terminator at the outer cmdsub's
                            // depth — consume; transition case to body mode.
                            setCsTop(false, false)
                            i++
                        }

                        cpp.isNotEmpty() && cpp.last() == d - 1 -> {
                            cpp.removeLast()
                            d--
                            if (cs.isNotEmpty()) setCsTop(false, false)
                            i++
                        }

                        else -> {
                            d--
                            if (d == 0) {
                                closed = true
                                closeIdx = i // do NOT include the close in body
                                break
                            }
                            i++
                        }
                    }
                }

                c == ';' && i + 1 < body.length && body[i + 1] == ';' -> {
                    if (cs.isNotEmpty()) setCsTop(true, false)
                    i += 2
                }

                c == '|' -> {
                    val top = cs.lastOrNull()
                    if (d == startDepth && top != null && top.first) setCsTop(true, true)
                    i++
                }

                c.isLetter() || c == '_' -> {
                    val ws = i
                    while (i < body.length && (body[i].isLetterOrDigit() || body[i] == '_')) i++
                    val prev = if (ws > 0) body[ws - 1] else ' '
                    val isWordStart =
                        !(prev.isLetterOrDigit() || prev == '_' || prev == '$')
                    if (isWordStart) {
                        val w = body.substring(ws, i)
                        if (pendingCw) {
                            pendingCw = false
                        } else {
                            when (w) {
                                "case" -> {
                                    cs.addLast(false to false)
                                    pendingCw = true
                                    sawCwContent = false
                                }

                                "in" -> {
                                    val t = cs.lastOrNull()
                                    if (t != null && !t.first) setCsTop(true, false)
                                }

                                "esac" -> {
                                    val t = cs.lastOrNull()
                                    val nextIsPipe = i < body.length && body[i] == '|'
                                    val isPat =
                                        t != null && t.first && (t.second || nextIsPipe)
                                    if (!isPat && cs.isNotEmpty()) cs.removeLast()
                                }
                            }
                        }
                    }
                }

                else -> {
                    i++
                }
            }
        }
        return AliasSimResult(
            consumedBody = if (closed) body.substring(0, closeIdx) else body,
            newDepth = d,
            newCaseStack = cs.toList(),
            newCasePatternParenDepths = cpp.toList(),
            closedCmdsub = closed,
            pendingCaseWord = pendingCw,
        )
    }

    /**
     * Process substitution body reader for `<(...)` / `>(...)`. Position is at
     * the `<` or `>`; advances past the operator and `(`, scans forward
     * paren-balanced while respecting quotes/escapes/heredocs/case-patterns,
     * and returns when the matching `)` is consumed. Throws on unterminated
     * EOF.
     *
     * Case-pattern tracking mirrors [readCommandSubstitution]'s — bash's
     * `comsub scanner` treats the bound finder the same regardless
     * of whether the wrapper is `$(`, `<(`, or `>(`. Without it,
     * `cat <( case x in foo) echo;; esac )` mis-bounds at `foo)` instead
     * of at the trailing `)`.
     */
    private fun readProcessSubstitution(direction: Char): WordChunk.ProcessSubstitution {
        // pos at `<` or `>`. Advance past the operator and `(`.
        pos += 2
        val start = pos
        var depth = 1
        val pendingHd = ArrayDeque<String>()
        // Per-frame case-pattern state: (patternExpected, lastOpWasPipe).
        // See [readCommandSubstitution] for the full state-machine
        // description; the rules are identical here.
        val caseStack = ArrayDeque<Pair<Boolean, Boolean>>()

        fun caseTop() = caseStack.lastOrNull()

        fun setCaseTop(
            pe: Boolean,
            lastPipe: Boolean,
        ) {
            caseStack[caseStack.size - 1] = pe to lastPipe
        }
        val casePatternParenDepths = ArrayDeque<Int>()
        var pendingCaseWord = false
        var sawCaseWordContent = false
        while (pos < source.length && depth > 0) {
            val c = source[pos]
            val isWs = c == ' ' || c == '\t' || c == '\n'
            if (pendingCaseWord && isWs && sawCaseWordContent) {
                pendingCaseWord = false
                sawCaseWordContent = false
            }
            if (pendingCaseWord && !isWs && c != '#') sawCaseWordContent = true
            when (c) {
                '<' if pos + 2 < source.length && source[pos + 1] == '<' && source[pos + 2] == '<' -> {
                    pos += 3
                }

                '<' if pos + 1 < source.length && source[pos + 1] == '<' -> {
                    pos += 2
                    if (pos < source.length && source[pos] == '-') pos++
                    while (pos < source.length && (source[pos] == ' ' || source[pos] == '\t')) pos++
                    val delim = readComsubHeredocDelimiter()
                    if (delim.isNotEmpty()) pendingHd.addLast(delim)
                }

                '(' -> {
                    val top = caseTop()
                    if (top != null && top.first) casePatternParenDepths.addLast(depth)
                    depth++
                    pos++
                }

                ')' -> {
                    val top = caseTop()
                    if (depth == 1 && top != null && top.first) {
                        // Case-pattern terminator, not the procsub close.
                        setCaseTop(false, false)
                        pos++
                    } else if (casePatternParenDepths.isNotEmpty() &&
                        casePatternParenDepths.last() == depth - 1
                    ) {
                        // Closing `)` of a leading-paren case pattern.
                        casePatternParenDepths.removeLast()
                        depth--
                        if (caseStack.isNotEmpty()) setCaseTop(false, false)
                        pos++
                    } else {
                        depth--
                        if (depth == 0) break
                        pos++
                    }
                }

                ';' -> {
                    if (pos + 1 < source.length && source[pos + 1] == ';') {
                        val t = caseTop()
                        if (t != null) setCaseTop(true, false)
                        pos += 2
                    } else {
                        pos++
                    }
                }

                '|' -> {
                    val t = caseTop()
                    if (depth == 1 && t != null && t.first) setCaseTop(true, true)
                    pos++
                }

                '\'' -> {
                    skipSingleQuotedBody()
                }

                '"' -> {
                    skipDoubleQuotedBody()
                }

                '\\' -> {
                    pos++
                    if (pos < source.length) pos++
                }

                '\n' -> {
                    bumpLine()
                    pos++
                    while (pendingHd.isNotEmpty()) {
                        val delim = pendingHd.removeFirst()
                        skipPastHeredocBody(delim)
                    }
                }

                else -> {
                    if (c.isLetter() || c == '_') {
                        val wstart = pos
                        while (pos < source.length &&
                            (source[pos].isLetterOrDigit() || source[pos] == '_')
                        ) {
                            pos++
                        }
                        val prev = if (wstart > 0) source[wstart - 1] else ' '
                        val isWordStart = !(prev.isLetterOrDigit() || prev == '_' || prev == '$')
                        if (isWordStart) {
                            val w = source.substring(wstart, pos)
                            if (pendingCaseWord) {
                                pendingCaseWord = false
                            } else {
                                when (w) {
                                    "case" -> {
                                        caseStack.addLast(false to false)
                                        pendingCaseWord = true
                                        sawCaseWordContent = false
                                    }

                                    "in" -> {
                                        val t = caseTop()
                                        if (t != null && !t.first) setCaseTop(true, false)
                                    }

                                    "esac" -> {
                                        val t = caseTop()
                                        val nextIsPipe =
                                            pos < source.length && source[pos] == '|'
                                        val isPattern =
                                            t != null && t.first && (t.second || nextIsPipe)
                                        if (!isPattern && caseStack.isNotEmpty()) {
                                            caseStack.removeLast()
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        pos++
                    }
                }
            }
        }
        if (pos >= source.length) {
            error($$"Unterminated $$direction(...) at line $$line")
        }
        val text = source.substring(start, pos)
        pos++ // past closing `)`
        return WordChunk.ProcessSubstitution(text, direction)
    }

    /** Read a heredoc delimiter while paren-scanning inside `$(...)`. Strips one layer of quotes. */
    private fun readComsubHeredocDelimiter(): String {
        val sb = StringBuilder()
        while (pos < source.length) {
            val c = source[pos]
            when {
                c.isWhitespace() || c == ';' || c == '|' || c == '&' || c == '<' || c == '>' || c == ')' -> {
                    return sb.toString()
                }

                c == '\'' -> {
                    pos++
                    while (pos < source.length && source[pos] != '\'') {
                        sb.append(source[pos])
                        pos++
                    }
                    if (pos < source.length) pos++
                }

                c == '"' -> {
                    pos++
                    while (pos < source.length && source[pos] != '"') {
                        sb.append(source[pos])
                        pos++
                    }
                    if (pos < source.length) pos++
                }

                c == '\\' -> {
                    pos++
                    if (pos < source.length) {
                        if (source[pos] == '\n') {
                            bumpLine()
                            pos++
                        } else {
                            sb.append(source[pos])
                            pos++
                        }
                    }
                }

                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
        return sb.toString()
    }

    /** Skip lines until one matches the heredoc delimiter (or EOF). Used inside `$(...)` to step
     * over heredoc bodies so their content doesn't confuse paren-balancing. */
    private fun skipPastHeredocBody(delim: String) {
        while (pos < source.length) {
            val lineStart = pos
            while (pos < source.length && source[pos] != '\n') pos++
            val raw = source.substring(lineStart, pos)
            val candidate = raw.trimStart('\t')
            if (pos < source.length) {
                pos++
                bumpLine()
            }
            if (candidate == delim) return
        }
    }

    /**
     * Result of advancing past a heredoc body inside a `$(...)` group.
     * `softTermLineStart` is non-null when the body soft-terminated on
     * a delim-prefix line (so the caller can patch the captured cmdsub
     * substring to replace that line with a clean synthetic terminator).
     */
    private data class GroupHeredocOutcome(
        val softTermLineStart: Int? = null,
    )

    private fun skipPastHeredocBodyInGroup(
        delim: String,
        openLine: Int,
    ): GroupHeredocOutcome {
        var lastContentLine = openLine
        while (pos < source.length) {
            val lineStart = pos
            val lineAtStart = line
            while (pos < source.length && source[pos] != '\n') pos++
            val raw = source.substring(lineStart, pos)
            val candidate = raw.trimStart('\t')
            if (candidate == delim) {
                if (pos < source.length) {
                    pos++
                    bumpLine()
                }
                return GroupHeredocOutcome()
            }
            // Bash's "delim-prefix" soft-terminator: the body line starts
            // with the delimiter text (after optional tab-strip), followed
            // by whitespace or the cmdsub close. Only applies when the
            // delimiter is non-empty.
            if (delim.isNotEmpty() && candidate.startsWith(delim)) {
                val after = candidate.substring(delim.length)
                if (after.isEmpty() || after[0].isWhitespace() || after[0] == ')') {
                    // Soft-terminator: the delim appears as a prefix on a
                    // line that also closes the cmdsub. The body ends
                    // before this line; the outer scanner needs to see
                    // the post-delim portion of this line (which contains
                    // the closing `)`) but NOT the delim text itself
                    // (which would otherwise emit spurious word tokens or,
                    // when the delim *is* `)`, a stray operator token).
                    // We therefore advance pos to past the delim, and
                    // record a patch so the captured cmdsub text replaces
                    // this line with a clean `<delim>\n` terminator.
                    warnings +=
                        LexerWarning(
                            line = lineAtStart,
                            message =
                                "warning: here-document at line $openLine " +
                                    "delimited by end-of-file (wanted `$delim')",
                        )
                    val tabsAhead =
                        (lineStart until (lineStart + raw.length))
                            .takeWhile { it < source.length && source[it] == '\t' }
                            .count()
                    pos = lineStart + tabsAhead + delim.length
                    line = lineAtStart
                    return GroupHeredocOutcome(softTermLineStart = lineStart)
                }
            }
            lastContentLine = lineAtStart
            if (pos < source.length) {
                pos++
                bumpLine()
            }
        }
        // Real end-of-input — heredoc body absorbed everything to EOF.
        // Bash reports the line of the final consumed content, not one past.
        warnings +=
            LexerWarning(
                line = lastContentLine,
                message = "warning: here-document at line $openLine delimited by end-of-file (wanted `$delim')",
            )
        return GroupHeredocOutcome()
    }

    /**
     * Capture bash-5.2 `${ cmd; }` no-fork command substitution. We've already
     * verified that the char after `${` is whitespace (which is what
     * disambiguates this from `${VAR}`). Run to the matching `}` respecting
     * quotes, escapes, and nested braces.
     */
    private fun readBraceCommandSubstitution(): WordChunk.CommandSubstitution {
        pos += 2 // past `${`
        val start = pos
        var depth = 1
        while (pos < source.length && depth > 0) {
            val c = source[pos]
            when (c) {
                '{' -> {
                    depth++
                    pos++
                }

                '}' -> {
                    // Bash 5.2+ `${ cmd; }` closes on `}` preceded by a
                    // command terminator (`;` or newline, possibly with
                    // trailing spaces/tabs) OR when the body is
                    // whitespace-only (`${ }` / `${\n}` — bash 5.3 treats
                    // these as empty no-fork cmdsubs, output empty).
                    // The `;`-required rule still applies when there's
                    // actual command content: `${ $ }` (no `;`) is the
                    // CVE-2014-6278 adversarial input that bash reports
                    // as unterminated.
                    //
                    // Skip trailing spaces and tabs ONLY — newline is the
                    // terminator we're looking for, so treating it as
                    // skippable whitespace would walk past it onto the
                    // last command char and falsely flag a missing
                    // terminator on `${\n cmd\n}` (multi-line form).
                    if (depth == 1) {
                        // bodyIsBlank: nothing but whitespace inside `${ ... }`.
                        var anyNonWs = false
                        for (k in start until pos) {
                            if (!source[k].isWhitespace()) {
                                anyNonWs = true
                                break
                            }
                        }
                        if (anyNonWs) {
                            // Last non-(space|tab) char before `}` must be
                            // `;` or `\n` — newline IS a terminator, so we
                            // skip only spaces/tabs (not \n) when looking
                            // for the immediately preceding char.
                            var j = pos - 1
                            while (j >= start && (source[j] == ' ' || source[j] == '\t')) j--
                            val terminator = if (j >= start) source[j] else ' '
                            if (terminator != ';' && terminator != '\n') {
                                error("unexpected EOF while looking for matching `}'")
                            }
                        }
                    }
                    depth--
                    if (depth == 0) break
                    pos++
                }

                '\'' -> {
                    skipSingleQuotedBody()
                }

                '"' -> {
                    skipDoubleQuotedBody()
                }

                '\\' -> {
                    pos++
                    if (pos < source.length) pos++
                }

                '\n' -> {
                    bumpLine()
                    pos++
                }

                else -> {
                    pos++
                }
            }
        }
        if (pos >= source.length) error("unexpected EOF while looking for matching `}'")
        val text = source.substring(start, pos)
        pos++ // past closing `}`
        return WordChunk.CommandSubstitution(text)
    }

    /**
     * Capture `((...))` arithmetic command at command-position. Paren-balanced; terminator is `))` at top level.
     * Returns null (and rewinds pos/line) if the content can't validly close with adjacent `))` —
     * bash treats those as nested subshells `( (...) )` rather than arithmetic.
     */
    private fun tryReadArithCommand(): Token.ArithCmdTok? {
        val startLine = line
        val savedPos = pos
        val savedLine = line
        pos += 2 // past `((`
        val text = scanArithBody(savedPos, savedLine) ?: return null
        return Token.ArithCmdTok(text, startLine)
    }

    /**
     * Capture `$((...))` — paren-balanced; terminator is `))` at top level.
     * Returns null (and rewinds) if the content doesn't validly close as arithmetic — bash treats those
     * as `$(` + subshell `(...)` inside command substitution.
     */
    private fun tryReadArithmeticExpansion(): WordChunk.ArithmeticExpansion? {
        val savedPos = pos
        val savedLine = line
        pos += 3 // past `$((`
        val text = scanArithBody(savedPos, savedLine) ?: return null
        return WordChunk.ArithmeticExpansion(text)
    }

    /**
     * Shared inner scanner for `((...))` and `$((...))`. Caller has already
     * advanced [pos] past the opener; this walks to the matching `))` at
     * top level, returning the body text and leaving [pos] past the closing
     * `))`. On EOF or a stray single `)` (which means the original was
     * really a subshell `(...)` group, not arithmetic), restores
     * `pos`/`line` to the saved values and returns null so the caller can
     * fall back.
     */
    private fun scanArithBody(
        savedPos: Int,
        savedLine: Int,
    ): String? {
        val textStart = pos
        var depth = 0

        // Peek the next char from the segment underlying the current
        // top (without popping). Used when looking for the second `)`
        // of `))` in the cross-segment alias-body case (e.g.
        // `alias math0='echo $(( 4+3 )'; math0)`).
        fun peekBelowTop(): Char? {
            if (stack.size <= 1) return null
            val below = stack.toList()[1]
            return if (below.pos < below.source.length) below.source[below.pos] else null
        }
        while (pos < source.length) {
            val c = source[pos]
            when {
                c == '(' -> {
                    depth++
                    pos++
                }

                c == ')' -> {
                    if (depth == 0) {
                        // Look for the second `)` of the `))` close.
                        val secondCloseInCurrent =
                            pos + 1 < source.length && source[pos + 1] == ')'
                        val secondCloseInUnderlying =
                            !secondCloseInCurrent &&
                                pos + 1 == source.length &&
                                peekBelowTop() == ')'
                        if (secondCloseInCurrent) {
                            val text = source.substring(textStart, pos)
                            pos += 2
                            return text
                        }
                        if (secondCloseInUnderlying) {
                            // Capture current-top slice, pop the
                            // exhausted top, consume the underlying `)`.
                            val text = source.substring(textStart, pos)
                            pos++
                            while (stack.size > 1 && top.pos >= top.source.length) {
                                val poppedSeg = stack.removeFirst()
                                top = stack.first()
                                if (poppedSeg.chainAfter) activeState?.chainNextWord = true
                            }
                            // Now pos is in the underlying segment, at `)`.
                            pos++ // past the underlying `)`
                            return text
                        }
                        pos = savedPos
                        line = savedLine
                        return null
                    }
                    depth--
                    pos++
                }

                // Inner substitutions: delegate to their dedicated skip
                // scanners so their boundaries (which may contain `)` —
                // e.g. `$(case x in p) ... esac)`) don't confuse this
                // body's paren counter.
                c == '$' && pos + 1 < source.length && source[pos + 1] == '(' &&
                    pos + 2 < source.length && source[pos + 2] == '(' -> {
                    skipArithmeticExpansionBody()
                }

                c == '$' && pos + 1 < source.length && source[pos + 1] == '(' -> {
                    skipCommandSubstitutionBody()
                }

                c == '$' && pos + 1 < source.length && source[pos + 1] == '{' -> {
                    skipBraceExpansionBody()
                }

                c == '`' -> {
                    skipBacktickBody()
                }

                c == '\'' -> {
                    skipSingleQuotedBody()
                }

                c == '"' -> {
                    skipDoubleQuotedBody()
                }

                c == '\\' -> {
                    pos++
                    if (pos < source.length) pos++
                }

                c == '\n' -> {
                    bumpLine()
                    pos++
                }

                else -> {
                    pos++
                }
            }
        }
        pos = savedPos
        line = savedLine
        return null
    }

    /** Advance past a `'...'` body (pos at opening `'`), discarding content but bumping line numbers. */
    private fun skipSingleQuotedBody() {
        pos++
        while (pos < source.length && source[pos] != '\'') {
            if (source[pos] == '\n') bumpLine()
            pos++
        }
        if (pos < source.length) pos++
    }

    /** Advance past a `"..."` body (pos at opening `"`), respecting `\X` escapes and bumping line. */
    private fun skipDoubleQuotedBody() {
        pos++
        while (pos < source.length && source[pos] != '"') {
            val c = source[pos]
            if (c == '\\' && pos + 1 < source.length) {
                pos += 2
                continue
            }
            // Inside `"..."`, `$(...)` / `$((...))` / `${...}` / `` `...` ``
            // are recognized substitutions whose bodies may contain a `"`
            // that should NOT close the outer dq. Recurse into them so the
            // outer string's terminator scan doesn't trip on a nested
            // string's `"` (or, for cmdsub, a `)` inside a nested string —
            // bash test `recho $(echo "foo$(echo ")")")` exercises this).
            if (c == '$' && pos + 1 < source.length) {
                val n = source[pos + 1]
                when {
                    n == '(' && pos + 2 < source.length && source[pos + 2] == '(' -> {
                        skipArithmeticExpansionBody()
                        continue
                    }

                    n == '(' -> {
                        skipCommandSubstitutionBody()
                        continue
                    }

                    n == '{' -> {
                        skipBraceExpansionBody()
                        continue
                    }
                }
            }
            if (c == '`') {
                skipBacktickBody()
                continue
            }
            if (c == '\n') bumpLine()
            pos++
        }
        if (pos < source.length) pos++
    }

    /**
     * Skip past `$(...)` body (pos at `$`). Tracks nested `(`/`)`,
     * quotes, AND case-pattern paren state so a `case x in p) ... esac`
     * body doesn't terminate the cmdsub at the pattern's `)`.
     *
     * The case-pattern state machine mirrors [readCommandSubstitution]
     * (OVERFITPASS A1 fix). Reference: POSIX §2.6.3 — the cmdsub body
     * parses as a full command list, so a `)` closing a case pattern
     * is consumed by the case-statement parse, not by the cmdsub
     * paren-balancer.
     */
    private fun skipCommandSubstitutionBody() {
        pos += 2 // past `$(`
        var depth = 1
        // Mirror of A1's state: each frame is
        // (patternExpected, lastOpWasPipe). pE=true after `case X in`,
        // before the pattern's terminator. lastOpWasPipe handles
        // `pat|pat2)` alternation.
        val caseStack = ArrayDeque<Pair<Boolean, Boolean>>()

        fun caseTop() = caseStack.lastOrNull()

        fun setCaseTop(
            pe: Boolean,
            lastPipe: Boolean,
        ) {
            caseStack[caseStack.size - 1] = pe to lastPipe
        }
        val casePatternParenDepths = ArrayDeque<Int>()
        var pendingCaseWord = false
        var sawCaseWordContent = false
        while (pos < source.length && depth > 0) {
            val c = source[pos]
            val isWs = c == ' ' || c == '\t' || c == '\n'
            if (pendingCaseWord && isWs && sawCaseWordContent) {
                pendingCaseWord = false
                sawCaseWordContent = false
            }
            if (pendingCaseWord && !isWs && c != '#') {
                sawCaseWordContent = true
            }
            when {
                c == '\\' && pos + 1 < source.length -> {
                    pos += 2
                }

                c == '\'' -> {
                    skipSingleQuotedBody()
                }

                c == '"' -> {
                    skipDoubleQuotedBody()
                }

                c == '`' -> {
                    skipBacktickBody()
                }

                c == '$' && pos + 1 < source.length && source[pos + 1] == '(' &&
                    pos + 2 < source.length && source[pos + 2] == '(' -> {
                    skipArithmeticExpansionBody()
                }

                c == '$' && pos + 1 < source.length && source[pos + 1] == '(' -> {
                    skipCommandSubstitutionBody()
                }

                c == '$' && pos + 1 < source.length && source[pos + 1] == '{' -> {
                    skipBraceExpansionBody()
                }

                c == '(' -> {
                    val top = caseTop()
                    if (top != null && top.first) {
                        casePatternParenDepths.addLast(depth)
                    }
                    depth++
                    pos++
                }

                c == ')' -> {
                    val top = caseTop()
                    if (depth == 1 && top != null && top.first) {
                        // Case-pattern terminator at the cmdsub's top
                        // depth — consume but do NOT close the cmdsub.
                        setCaseTop(false, false)
                        pos++
                    } else if (casePatternParenDepths.isNotEmpty() &&
                        casePatternParenDepths.last() == depth - 1
                    ) {
                        // Closing `)` of a leading-paren case pattern.
                        casePatternParenDepths.removeLast()
                        depth--
                        if (caseStack.isNotEmpty()) setCaseTop(false, false)
                        pos++
                    } else {
                        depth--
                        pos++
                    }
                }

                c == ';' && pos + 1 < source.length && source[pos + 1] == ';' -> {
                    val t = caseTop()
                    if (t != null) setCaseTop(true, false)
                    pos += 2
                }

                c == '|' -> {
                    val t = caseTop()
                    if (depth == 1 && t != null && t.first) {
                        setCaseTop(true, true)
                    }
                    pos++
                }

                c == '\n' -> {
                    bumpLine()
                    pos++
                }

                c.isLetter() || c == '_' -> {
                    val wstart = pos
                    while (pos < source.length &&
                        (source[pos].isLetterOrDigit() || source[pos] == '_')
                    ) {
                        pos++
                    }
                    val prev = if (wstart > 0) source[wstart - 1] else ' '
                    val isWordStart =
                        !(prev.isLetterOrDigit() || prev == '_' || prev == '$')
                    if (isWordStart) {
                        val w = source.substring(wstart, pos)
                        if (pendingCaseWord) {
                            // The first word after `case` is the case-word
                            // (subject of the match), not a keyword.
                            // sawCaseWordContent handles the whitespace
                            // boundary below.
                        } else {
                            when (w) {
                                "case" -> {
                                    caseStack.addLast(false to false)
                                    pendingCaseWord = true
                                    sawCaseWordContent = false
                                }

                                "in" -> {
                                    val t = caseTop()
                                    if (t != null && !t.first) setCaseTop(true, false)
                                }

                                "esac" -> {
                                    val t = caseTop()
                                    val nextIsPipe =
                                        pos < source.length && source[pos] == '|'
                                    val isPattern =
                                        t != null && t.first && (t.second || nextIsPipe)
                                    if (!isPattern && caseStack.isNotEmpty()) {
                                        caseStack.removeLast()
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    pos++
                }
            }
        }
    }

    /** Skip past `$((...))` body (pos at `$`). */
    private fun skipArithmeticExpansionBody() {
        pos += 3 // past `$((`
        var depth = 1
        while (pos < source.length && depth > 0) {
            val c = source[pos]
            when {
                c == '\\' && pos + 1 < source.length -> {
                    pos += 2
                }

                c == '(' -> {
                    depth++
                    pos++
                }

                c == ')' -> {
                    if (depth == 1 && pos + 1 < source.length && source[pos + 1] == ')') {
                        pos += 2
                        depth = 0
                    } else {
                        depth--
                        pos++
                    }
                }

                c == '\n' -> {
                    bumpLine()
                    pos++
                }

                else -> {
                    pos++
                }
            }
        }
    }

    /** Skip past `${...}` body (pos at `$`). */
    private fun skipBraceExpansionBody() {
        pos += 2 // past `${`
        var depth = 1
        while (pos < source.length && depth > 0) {
            val c = source[pos]
            when {
                c == '\\' && pos + 1 < source.length -> {
                    pos += 2
                }

                c == '\'' -> {
                    skipSingleQuotedBody()
                }

                c == '"' -> {
                    skipDoubleQuotedBody()
                }

                c == '`' -> {
                    skipBacktickBody()
                }

                c == '$' && pos + 1 < source.length && source[pos + 1] == '(' -> {
                    skipCommandSubstitutionBody()
                }

                c == '$' && pos + 1 < source.length && source[pos + 1] == '{' -> {
                    depth++
                    pos += 2
                }

                c == '{' -> {
                    depth++
                    pos++
                }

                c == '}' -> {
                    depth--
                    pos++
                }

                c == '\n' -> {
                    bumpLine()
                    pos++
                }

                else -> {
                    pos++
                }
            }
        }
    }

    /** Skip past `` `...` `` body (pos at `` ` ``). */
    private fun skipBacktickBody() {
        pos++ // past opening `
        while (pos < source.length && source[pos] != '`') {
            if (source[pos] == '\\' && pos + 1 < source.length) {
                pos += 2
            } else {
                if (source[pos] == '\n') bumpLine()
                pos++
            }
        }
        if (pos < source.length) pos++
    }

    /** Capture `` `...` `` form. Bash processes `\\` `\$` `` \` `` specially inside. */
    private fun readBacktickSubstitution(): WordChunk.CommandSubstitution {
        val openLine = line
        pos++ // past opening `
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != '`') {
            val c = source[pos]
            if (c == '\\' && pos + 1 < source.length) {
                val n = source[pos + 1]
                when {
                    // `\<newline>` inside backticks is line continuation —
                    // bash strips both characters BEFORE the inner script
                    // is parsed, so single-quoted content like
                    // `'foo\<NL>bar'` collapses to `'foobar'`. Without
                    // this, `\` enters the inner parser as a literal and
                    // the single-quoted region preserves it.
                    n == '\n' -> {
                        bumpLine()
                        pos += 2
                    }

                    n == '`' || n == '$' || n == '\\' -> {
                        sb.append(n)
                        pos += 2
                    }

                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            } else {
                if (c == '\n') bumpLine()
                sb.append(c)
                pos++
            }
        }
        if (pos >= source.length) error("Unterminated backtick at line $line")
        pos++ // past closing `
        return WordChunk.CommandSubstitution(sb.toString(), openLine = openLine)
    }

    // Parse `${...}` after the leading `$`. Supports the full operator family:
    //   ${name}           ${#name}
    //   ${name:-word}     ${name-word}
    //   ${name:=word}     ${name=word}
    //   ${name:?word}     ${name?word}
    //   ${name:+word}     ${name+word}
    //   ${name#pat}       ${name##pat}
    //   ${name%pat}       ${name%%pat}
    //   ${name/pat/repl}  ${name//pat/repl}  ${name/#pat/repl}  ${name/%pat/repl}
    //   ${name^pat}       ${name^^pat}       ${name,pat}        ${name,,pat}
    //   ${name:off}       ${name:off:len}

    /**
     * Scan the raw text of a default-value-op operand to the matching
     * unescaped `}`. Mirrors bash's `brace-body capture`
     * — descends into nested `${...}`, `"..."`,
     * `` `...` ``, `$(...)` and consumes `\X` as a 2-char unit, all
     * purely to find the operand boundary. Does NOT recursively parse
     * the operand body; the caller stores the raw text on the
     * [WordChunk.ParameterExpansion] node and the expander re-lexes
     * at expansion time with the outer dq depth restored.
     *
     * Pos enters at the first char after the operator (e.g. `+`).
     * Leaves pos AT the closing `}` (caller advances past it).
     *
     * `'` handling is context-dependent (verified against bash 5.3):
     *   - outer dqDepth > 0: `'` is literal (NOT a quote opener);
     *     bash `"${IFS+'}'z}"` → operand text = `'`.
     *   - outer dqDepth == 0: `'` opens a normal sq region.
     */
    private fun extractBracedOperandTextRaw(): String {
        val start = pos
        val outerDq = dqDepth
        var bDepth = 0
        val openLine = line
        while (pos < source.length) {
            val c = source[pos]
            when (c) {
                '\\' -> {
                    // `\X` is one atomic unit — consume both. Even at
                    // EOF after `\` we just consume the `\`.
                    pos++
                    if (pos < source.length) {
                        if (source[pos] == '\n') bumpLine()
                        pos++
                    }
                }

                '$' -> {
                    when {
                        pos + 1 < source.length && source[pos + 1] == '{' -> {
                            bDepth++
                            pos += 2
                        }

                        pos + 2 < source.length && source[pos + 1] == '(' && source[pos + 2] == '(' -> {
                            // `$((...))` — scan through so its body's `}`
                            // (e.g. arithmetic with literal `}` chars) and
                            // its `)` aren't mistaken for operand structure.
                            skipArithmeticExpansionBody()
                        }

                        pos + 1 < source.length && source[pos + 1] == '(' -> {
                            // `$(...)` cmdsub. Must scan through so a `}`
                            // inside the body doesn't prematurely close the
                            // outer `${...}` operand (`${foo:-$(echo a{b,c})}`
                            // needs the inner `{b,c}` brace pair to balance
                            // before the outer `)`).
                            skipCommandSubstitutionBody()
                        }

                        else -> {
                            // Bare `$` or `$NAME`: plain char for operand
                            // delimiter matching.
                            pos++
                        }
                    }
                }

                '}' -> {
                    if (bDepth == 0) return source.substring(start, pos)
                    bDepth--
                    pos++
                }

                '\'' -> {
                    // Bash `brace-body capture`:
                    //   • non-POSIX (default): `'X'` is a quote region;
                    //     `${HOME-'}'}` operand spans `'}'`, outer `}`
                    //     closes the expansion.
                    //   • POSIX (`set -o posix`): `'` is literal in DQ;
                    //     `${IFS+'}'z}` operand is `'`, first `}` closes,
                    //     `'z}` is literal text after.
                    //
                    // V2 [com.accucodeai.kash.parser.StatementStream]
                    // re-lexes on `set -o posix` toggle, so the source
                    // span of the same expansion can shift between modes
                    // without stranding a stale cursor.
                    if (outerDq > 0 && posixMode) {
                        pos++
                    } else {
                        skipSingleQuotedBody()
                    }
                }

                '"' -> {
                    skipDoubleQuotedBody()
                }

                '`' -> {
                    // Backtick body via the existing reader (and discard
                    // the resulting chunk — we only want the side-effect
                    // of advancing pos past the matching backtick).
                    readBacktickSubstitution()
                }

                '\n' -> {
                    bumpLine()
                    pos++
                }

                else -> {
                    pos++
                }
            }
        }
        // Hit EOF without finding the closing `}`. Diagnose at the line
        // of the opening `${`, with bash's
        // `unexpected EOF while looking for matching \`}'` shape.
        throw ParseException(
            "unexpected EOF while looking for matching `}'",
            line = openLine,
        )
    }

    /**
     * After `${!`, the next character decides whether `!` is the indirect
     * prefix or the parameter name itself. Identifier-start, digit, `@`,
     * and `*` continue into a name (so `${!var}` / `${!1}` / `${!@}` /
     * `${!*}` are indirect). Operator chars (`?`, `:`, `-`, `+`, `=`, `#`,
     * `%`, `/`, `^`, `,`) mean `!` is the parameter — they form the PE op
     * for the last-bg-pid special.
     */
    private fun isIndirectFollowChar(c: Char): Boolean = c == '_' || c == '@' || c == '*' || c.isLetterOrDigit()

    /**
     * Bash transforms `$'X'` to `'<decoded-X>'` and `$"X"` to `"X"`
     * inside `${...}` body before constructing parse diagnostics
     * (single-quote-wrapping the decoded result). Replicate that for
     * our __BADSUB__ error messages so `${'x1'%$'t'}` is reported as
     * `${'x1'%'t'}: bad substitution`. The transform is purely
     * textual — runtime expansion of valid PEs goes through a
     * separate codepath.
     */
    private fun transformExtquoteForDiag(raw: String): String {
        if (!raw.contains('$')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '$' && i + 1 < raw.length && (raw[i + 1] == '\'' || raw[i + 1] == '"')) {
                val opener = raw[i + 1]
                // Scan for the closing quote of the same kind, honoring
                // `\X` as a 2-char unit (bash's string_extract_single_quoted
                // skips backslash-escaped quotes for `$'...'`; `$"..."`
                // uses double-quote rules but for our diag we don't need
                // to expand inside).
                val bodyStart = i + 2
                var j = bodyStart
                while (j < raw.length && raw[j] != opener) {
                    if (opener == '\'' && raw[j] == '\\' && j + 1 < raw.length) {
                        j += 2
                    } else {
                        j++
                    }
                }
                if (j >= raw.length) {
                    // Unclosed — emit as-is and bail.
                    out.append(raw, i, raw.length)
                    return out.toString()
                }
                val body = raw.substring(bodyStart, j)
                val decoded = if (opener == '\'') decodeAnsiCLiteral(body) else body
                out.append(opener)
                out.append(decoded)
                out.append(opener)
                i = j + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Minimal ANSI-C `$'...'` body decoder for diagnostic reuse. Mirrors
     * the common-case subset of [readDollarSingleQuoted]: `\n` `\t` `\r`
     * `\\` `\'` `\"` `\a` `\b` `\e` `\f` `\v` and octal `\NNN`. Anything
     * else passes through unchanged — diagnostics only need shape parity
     * with bash, not bit-exact rendering of every escape form.
     */
    private fun decodeAnsiCLiteral(body: String): String {
        val sb = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                val esc = body[i + 1]
                val decoded = ANSI_C_SIMPLE_ESCAPES[esc]
                if (decoded != null) {
                    sb.append(decoded)
                    i += 2
                    continue
                }
                // Leave unknown escapes verbatim (incl. the backslash).
                sb.append('\\')
                sb.append(esc)
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun readBracedParameterExpansion(): WordChunk.ParameterExpansion {
        braceDepth++
        pos += 2 // past `${`
        // ${#}  — positional count.
        // ${#name} — length of name (or of $@/$* etc).
        // bash precedence — the `#` is length-op only when it's actually
        // followed by something that can be a parameter reference:
        //   - `${#}`         → length-of-$@ (special: bare `#` = $@)
        //   - `${#name}`     → length of $name (identifier head)
        //   - `${#1}`        → length of $1 (digit positional)
        //   - `${#@}` `${#*}` → count of positionals
        //   - `${#X}` where X is a single special-param char (`-`/`?`/
        //     `!`/`$`/`#`) AND the char after is `}` → length-of-$X
        // Anything else (op chars `-+=?:/%` followed by more content)
        // means `#` itself is the special parameter and what follows
        // is the op — `${#-foo}` is default-value with $# as the
        // value, NOT length-of-`-foo`.
        var lengthOf = false
        if (pos < source.length && source[pos] == '#' &&
            pos + 1 < source.length && source[pos + 1] != '}' &&
            source[pos + 1] != ':'
        ) {
            val n = source[pos + 1]
            val nextIsNameChar = n == '_' || n.isLetterOrDigit() || n == '@' || n == '*'
            val nextIsSingleSpecial =
                (n == '-' || n == '?' || n == '!' || n == '$' || n == '#') &&
                    pos + 2 < source.length && source[pos + 2] == '}'
            if (nextIsNameChar || nextIsSingleSpecial) {
                lengthOf = true
                pos++
            }
        }
        // `${!name}` — indirect expansion (lookup value of `name`, then use that as the real name).
        // `${!}` alone is the last-bg-pid special parameter `!`.
        // `${!?}`, `${!-x}`, `${!+x}`, `${!=x}`, `${!#}`, `${!%}`, `${!/}`
        // are NOT indirect — bash treats `!` as the parameter and the
        // following operator char as the PE op. So only consume `!` as
        // an indirect prefix when the next char could start a parameter
        // name: identifier head, digit, `@`, `*`. posixexp2.sub test 6
        // depends on this for `${!?}`.
        var indirect = false
        if (pos < source.length && source[pos] == '!' &&
            pos + 1 < source.length && source[pos + 1] != '}' &&
            (
                isIndirectFollowChar(source[pos + 1]) ||
                    // bash: `${!#}` is indirect-of-`#` (look up `$#`, use as
                    // name). The standalone form only — `${!#pattern}` parses
                    // as prefix-strip on `$!`. Detect by looking at the char
                    // after `#`: only `}` triggers the indirect interpretation.
                    (source[pos + 1] == '#' && pos + 2 < source.length && source[pos + 2] == '}')
            )
        ) {
            indirect = true
            pos++
        }
        // extquote: bash extension (default-on via `shopt extquote`) that
        // applies `$'...'` ANSI-C decoding and `$"..."` locale-translation
        // to text INSIDE `${...}`. At the parameter-name position the
        // decoded string is used directly as the PE name — so
        // `${$'x1'%$'t'}` is `${x1%t}` after decoding. posixexp7.sub
        // depends on this.
        //
        // For `$"..."` we treat the content verbatim (no translation
        // catalog), matching bash without a message catalog. The operand
        // position handles `$'...'` already via the standard chunk
        // reader, so this targeted hook only needs to cover the name slot.
        val nameStart = pos
        val extquoteName: String? =
            if (pos + 1 < source.length && source[pos] == '$' &&
                (source[pos + 1] == '\'' || source[pos + 1] == '"')
            ) {
                if (source[pos + 1] == '\'') {
                    readDollarSingleQuoted().value
                } else {
                    pos++ // past `$`
                    val dq = readDoubleQuoted()
                    // Flatten dq parts to a plain string — for an identifier
                    // PE name the content should be a literal, but tolerate
                    // mixed parts by concatenating their text.
                    buildString {
                        for (part in dq.parts) {
                            when (part) {
                                is WordChunk.Literal -> append(part.value)
                                is WordChunk.SingleQuoted -> append(part.value)
                                is WordChunk.Escaped -> append(part.value)
                                else -> Unit
                            }
                        }
                    }
                }
            } else {
                null
            }
        if (extquoteName == null) {
            // Parameter name: special single-char (@, *, #, ?, !, digit) or identifier.
            // After lengthOf, also accept `-` / `$` as a single-char special-
            // param name — `${#-}` reads `-` (length of `$-`), `${#$}` reads
            // `$` (length of `$$`). These are only valid as names when
            // they're the WHOLE rest of the expansion (`}` follows), which
            // the lengthOf detection above already guarantees.
            if (pos < source.length) {
                val c = source[pos]
                when {
                    c == '@' || c == '*' || c == '#' || c == '?' || c == '!' -> {
                        pos++
                    }

                    lengthOf && (c == '-' || c == '$') -> {
                        pos++
                    }

                    c.isDigit() -> {
                        while (pos < source.length && source[pos].isDigit()) pos++
                    }

                    c == '_' || c.isLetter() -> {
                        while (pos < source.length && (source[pos] == '_' || source[pos].isLetterOrDigit())) pos++
                    }
                }
            }
        }
        val name = extquoteName ?: source.substring(nameStart, pos)
        if (pos >= source.length) error($$"Unterminated ${ at line $$line")
        // `${!prefix*}` / `${!prefix@}` — name-globbing form. Per bash, this
        // ONLY fires for the `!`-prefixed form: `${VAR@}` is a malformed
        // transform operator (missing letter → bad substitution), not a
        // name glob. `${VAR*}` likewise — the bare-prefix form requires
        // the `!`.
        val nameGlob: Char? =
            if (indirect &&
                (source[pos] == '*' || source[pos] == '@') &&
                pos + 1 < source.length && source[pos + 1] == '}'
            ) {
                val ch = source[pos]
                pos++ // past `*` / `@`
                ch
            } else {
                null
            }
        // Optional `[subscript]` — array index or `@` / `*`.
        // The bracket scanner is QUOTE-AWARE: `${myarray["a]a"]}` keeps
        // the inner `]` as part of the key because it's inside dquotes
        // (bash assoc keys can legally contain `]`). Same for single
        // quotes (`${myarray[']']}`) and backslash-escapes
        // (`${myarray[\]]}`). Verified bash 5.3 directly.
        val subscript: String? =
            if (source[pos] == '[') {
                pos++ // past `[`
                val subStart = pos
                var depth = 1
                var inSingle = false
                var inDouble = false
                while (pos < source.length && depth > 0) {
                    val ch = source[pos]
                    if (inSingle) {
                        if (ch == '\'') inSingle = false
                        if (ch == '\n') bumpLine()
                        pos++
                        continue
                    }
                    if (inDouble) {
                        if (ch == '\\' && pos + 1 < source.length) {
                            if (source[pos + 1] == '\n') bumpLine()
                            pos += 2
                            continue
                        }
                        if (ch == '"') inDouble = false
                        if (ch == '\n') bumpLine()
                        pos++
                        continue
                    }
                    when (ch) {
                        '\\' -> {
                            if (pos + 1 < source.length) {
                                if (source[pos + 1] == '\n') bumpLine()
                                pos += 2
                            } else {
                                pos++
                            }
                        }

                        '\'' -> {
                            inSingle = true
                            pos++
                        }

                        '"' -> {
                            inDouble = true
                            pos++
                        }

                        '[' -> {
                            depth++
                            pos++
                        }

                        ']' -> {
                            depth--
                            if (depth == 0) break
                            pos++
                        }

                        '\n' -> {
                            bumpLine()
                            pos++
                        }

                        else -> {
                            pos++
                        }
                    }
                }
                if (pos >= source.length) error($$"Unterminated [ in ${...[..]} at line $$line")
                val raw = source.substring(subStart, pos)
                pos++ // past `]`
                raw
            } else {
                null
            }
        // No operator — close immediately.
        if (source[pos] == '}') {
            pos++
            return WordChunk.ParameterExpansion(
                name,
                lengthOf = lengthOf,
                subscript = subscript,
                indirect = indirect,
                nameGlob = nameGlob,
                braced = true,
            )
        }
        val op = readParamOperator()
        // When the op is __BADSUB__ the lexer has consumed the rest of the
        // PE body up to `}` without parsing it. Bash's diagnostic for that
        // case includes the literal content between `${` and `}` after
        // applying its extended-quote transform: `$'X'` → `'<decoded-X>'`
        // and `$"X"` → `"X"` (single-quote-wrapping the decoded result).
        // Apply the same here so messages like `${'x1'%$'t'}` come out
        // as `${'x1'%'t'}: bad substitution`.
        val effectiveName: String =
            if (op == "__BADSUB__") transformExtquoteForDiag(source.substring(nameStart, pos)) else name
        // Bash's `'` handling inside `${...}` operand splits by op family:
        //   - default-value ops (`:-`, `-`, `:+`, `+`, `:=`, `=`, `:?`, `?`):
        //     inside outer dq, `'` is LITERAL (no quoting region), `$X`
        //     still expands. The nested `"..."` strips backslashes.
        //   - pattern ops (`#`, `##`, `%`, `%%`, `/`, `//`, `/#`, `/%`):
        //     `'` opens a quote region normally — patterns are
        //     glob-pattern strings that quote their metachars literally.
        // The dq fix in `readWordChunksUntilDelimiter` should only fire for
        // default-value ops.
        // Colon-prefixed default-value ops only: bash treats `'` as literal
        // inside their operand (in outer dq), while bare ops (`-`, `+`, `=`,
        // `?`) keep `'` as a quote opener. Braces stress-test
        // `"${a-'$('\'}"` relies on the latter; rhs-exp
        // `"${selvecs:+'$selvecs'}"` relies on the former.
        val isDefaultOp = op in setOf(":-", ":+", ":=", ":?")
        // `$'...'` literal-vs-recognized rule keys off the WIDER set —
        // both colon-prefixed and bare default-value ops disable ANSI-C
        // recognition. Pattern ops (`#`, `##`, `%`, `%%`, `/`, ...)
        // continue to recognize. Set the field around the operand reads;
        // restore inside readWordChunksUntilDelimiter when nested calls
        // re-set it (the existing save/restore handles that).
        // Each new `${...}` resets the flag based on its OWN op — a
        // pattern op inside an outer default-value op operand recognizes
        // `$'...'` normally (and vice-versa). Restored on exit so the
        // caller's context isn't disturbed.
        //
        // Bash splits the default-value family for `$'...'` handling:
        //   - bare `-`, `+`, `=`, `?`: `$'...'` is LITERAL text
        //     (e.g. `${none-a$'\01'b}` → 9-char `a$'\01'b`).
        //   - colon-prefixed `:-`, `:+`, `:=`, `:?`: `$'...'` IS
        //     recognized as ANSI-C (e.g. `${mytab:-$'\t'}` → tab byte).
        // (This is the inverse of the `'` handling rule, which uses the
        // colon set for literal-quote treatment in outer dq context.)
        val bareDefaultOp = op in setOf("-", "+", "=", "?")
        // ALL default-value ops (bare + colon-prefixed) defer operand
        // parsing to expansion time — see bash's `brace-body capture`
        // . The raw text is captured here; the expander
        // re-lexes with the outer dq depth restored, so `'` and `\X`
        // semantics match the originating context.
        val deferOperand = op in setOf("-", "+", "=", "?", ":-", ":+", ":=", ":?")
        val savedInDefault = inDefaultValueOpOperand
        inDefaultValueOpOperand = bareDefaultOp
        val dqAtEntry = dqDepth
        var rawArg1: String? = null
        val (arg1, arg2) =
            try {
                when (op) {
                    "/", "//", "/#", "/%" -> {
                        // A `/` immediately after the patsub op is a literal
                        // part of the pattern, not the pattern/replacement
                        // separator. Verified: `${a///a/}` with `a=/a`
                        // parses as pattern=`/a`, replacement=empty.
                        // Anchored ops `/#` and `/%` already consumed the
                        // anchor token, so the next char STARTS the pattern;
                        // an immediate `/` there is the empty-pattern
                        // terminator (`${var/#/x}` is pattern=empty, repl=x).
                        val leadingSlash =
                            (op == "/" || op == "//") &&
                                pos < source.length && source[pos] == '/' &&
                                pos + 1 < source.length && source[pos + 1] != '}'
                        val prefix: List<WordChunk> =
                            if (leadingSlash) {
                                pos++
                                listOf(WordChunk.Literal("/"))
                            } else {
                                emptyList()
                            }
                        val tail = readWordChunksUntilDelimiter(setOf('/', '}'), defaultValueOp = false)
                        val a = prefix + tail
                        val b =
                            if (pos < source.length && source[pos] == '/') {
                                pos++
                                // Patsub replacement: bash strips `\X` for ANY
                                // non-dq-special X ( pattern-sub operand path
                                // path runs the operand through the equivalent
                                // of inParamExpand=true). Verified: bash 5.3
                                // `"${v/X/\Y}"` → `Y` (backslash stripped) but
                                // `"\Y"` → `\Y` (regular dq keeps).
                                readWordChunksUntilDelimiter(
                                    setOf('}'),
                                    defaultValueOp = false,
                                    patsubRepl = true,
                                )
                            } else {
                                null
                            }
                        a to b
                    }

                    ":" -> {
                        val a =
                            readWordChunksUntilDelimiter(
                                setOf(':', '}'),
                                defaultValueOp = false,
                                trackParens = true,
                            )
                        val b =
                            if (pos < source.length && source[pos] == ':') {
                                pos++
                                readWordChunksUntilDelimiter(setOf('}'), defaultValueOp = false)
                            } else {
                                null
                            }
                        a to b
                    }

                    else -> {
                        if (deferOperand) {
                            rawArg1 = extractBracedOperandTextRaw()
                            null to null
                        } else {
                            // Pattern / strip ops eat-and-parse eagerly
                            // (`#`, `##`, `%`, `%%`, `^`, `^^`, `,`, `,,`, `~`,
                            // `~~`, `@`). Their operand is a glob pattern or
                            // transform code, not a word that needs deferred
                            // context-restoration.
                            readWordChunksUntilDelimiter(setOf('}'), defaultValueOp = isDefaultOp) to null
                        }
                    }
                }
            } finally {
                inDefaultValueOpOperand = savedInDefault
            }
        if (pos >= source.length || source[pos] != '}') {
            braceDepth--
            error($$"Unterminated ${...} at line $$line")
        }
        pos++
        braceDepth--
        return WordChunk.ParameterExpansion(
            effectiveName,
            lengthOf = lengthOf,
            op = op,
            arg1 = arg1,
            arg2 = arg2,
            rawArg1 = rawArg1,
            outerDqAtLex = if (rawArg1 != null) dqAtEntry else 0,
            subscript = subscript,
            indirect = indirect,
            nameGlob = nameGlob,
            braced = true,
        )
    }

    /** Read one parameter-expansion operator. Two-character ops are tried first. */
    private fun readParamOperator(): String {
        val c = source[pos]
        val two = if (pos + 1 < source.length) "$c${source[pos + 1]}" else ""
        return when {
            two == ":-" || two == ":=" || two == ":?" || two == ":+" ||
                two == "##" || two == "%%" || two == "//" ||
                two == "/#" || two == "/%" || two == "^^" || two == ",," || two == "~~" -> {
                pos += 2
                two
            }

            c == '-' || c == '=' || c == '?' || c == '+' ||
                c == '#' || c == '%' || c == '/' ||
                c == ':' || c == '^' || c == ',' || c == '~' || c == '@' -> {
                pos++
                c.toString()
            }

            else -> {
                // Bash treats unrecognized op characters as a runtime "bad
                // substitution", not a parse failure — the script keeps
                // running after the diagnostic. Consume to the closing `}`
                // and emit a sentinel op so the expander knows to error
                // at expansion time. Without this, malformed `${#1xyz}`
                // halted the entire script.
                while (pos < source.length && source[pos] != '}') pos++
                "__BADSUB__"
            }
        }
    }

    /**
     * Read word chunks until one of [stopChars] appears at the top level. Nested
     * `${...}` / quotes / escapes are consumed normally (they don't terminate).
     * Whitespace is *not* a stop condition — the only terminators are [stopChars].
     */
    private fun readWordChunksUntilDelimiter(
        stopChars: Set<Char>,
        defaultValueOp: Boolean = false,
        patsubRepl: Boolean = false,
        trackParens: Boolean = false,
    ): List<WordChunk> {
        val parts = mutableListOf<WordChunk>()
        val lit = StringBuilder()
        // bash's `${var:OFFSET:LENGTH}` parses both arith expressions
        // honoring paren balance AND C-style `?:` ternary balance —
        // `${string1:(j?1:0):j}` and `${string1:j?1:0:j}` both keep the
        // inner `:` as part of the ternary. Only the substring op uses
        // this; other ops have no nested-`:` need.
        var parenDepth = 0
        var ternaryDepth = 0

        fun flushLit() {
            if (lit.isNotEmpty()) {
                parts += WordChunk.Literal(lit.toString())
                lit.clear()
            }
        }
        while (pos < source.length) {
            val c = source[pos]
            if (c in stopChars && !(trackParens && (parenDepth > 0 || (c == ':' && ternaryDepth > 0)))) break
            if (trackParens && c == '(') {
                parenDepth++
                lit.append(c)
                pos++
                continue
            }
            if (trackParens && c == ')' && parenDepth > 0) {
                parenDepth--
                lit.append(c)
                pos++
                continue
            }
            if (trackParens && c == '?' && parenDepth == 0) {
                ternaryDepth++
                lit.append(c)
                pos++
                continue
            }
            if (trackParens && c == ':' && parenDepth == 0 && ternaryDepth > 0) {
                ternaryDepth--
                lit.append(c)
                pos++
                continue
            }
            when (c) {
                '\'' -> {
                    // For default-value ops (`${v:+word}`, `${v:-word}`, etc.) inside
                    // an outer `"..."` (dqDepth > 0), bash treats `'` as a literal
                    // character — NOT a quote opener. So `"${v:+'$v'}"` expands `$v`
                    // between literal single quotes. Pattern ops (`#`, `%`, `/`, etc.)
                    // keep normal quoting because their operands are glob patterns
                    // that need to escape metachars literally.
                    // Outside dq, behavior is lenient: open a quoted region only if
                    // a matching `'` exists before the surrounding `}` closer.
                    //
                    // Pattern ops INSIDE outer dq: bash accepts newlines inside
                    // `'...'`. posixexp.tests line 46 (`recho "${x##'}"`) — the
                    // `'` opens a region that consumes past the EOL to find a
                    // closing `'` later in the file. Pass `crossLine = true`
                    // when we know we're in that context so the lookahead
                    // doesn't bail at `\n`.
                    val crossLine = dqDepth > 0 && !defaultValueOp
                    if (defaultValueOp && dqDepth > 0) {
                        lit.append('\'')
                        pos++
                    } else if (hasMatchingSingleQuoteBeforeBraceClose(crossLine = crossLine)) {
                        flushLit()
                        parts += readSingleQuoted()
                    } else {
                        lit.append('\'')
                        pos++
                    }
                }

                '"' -> {
                    // Bash unescaped-dquote-strip ( default-value RHS expander):
                    // when the operand of a default-value op (`-`/`:-`/`+`/
                    // `:+`/`=`/`:=`/`?`/`:?`) sits inside outer `"..."`, an
                    // unescaped lone `"` (no matching closer before `}`)
                    // is deleted before RHS expansion — array.tests #6 case
                    // `"${dbg-'"hey'}"` → `'hey'`. A balanced inner `"..."`
                    // still routes through readDoubleQuoted so the inner
                    // backslash-strip (`\p` → `p` etc., rhs-exp.tests)
                    // still fires.
                    if (defaultValueOp && dqDepth > 0 && !hasMatchingDoubleQuoteBeforeBraceClose()) {
                        pos++
                    } else {
                        flushLit()
                        parts += readDoubleQuoted(inParamExpand = defaultValueOp && dqDepth > 0)
                    }
                }

                '`' -> {
                    // Backtick command substitution — the body is paren/brace-agnostic, terminated by ``.
                    // Must skip the body so `}` inside it doesn't terminate the surrounding `${...}`.
                    flushLit()
                    parts += readBacktickSubstitution()
                }

                '\\' -> {
                    pos++
                    if (pos < source.length) {
                        val n = source[pos]
                        // Inside a `${...}` operand that itself sits inside `"..."`,
                        // backslash follows dq escape rules — only `$`, `` ` ``, `"`,
                        // `\`, and newline are escapable; other chars keep the leading
                        // backslash as literal. Outside dq the operand is "unquoted"
                        // and `\` escapes any single char.
                        //
                        // For default-value ops in dq, `}` also joins the escape
                        // set — bash `"${IFS+\}z}"` → `}z` (strip `\` before `}`)
                        // but plain `"\}"` → `\}` (preserve). Verified against
                        // bash 5.3.
                        // In a patsub replacement operand, `\X` strips the
                        // backslash for ANY X — bash's
                        // `pattern-sub operand path` path runs the operand
                        // through the equivalent of inParamExpand=true so
                        // that source-script `\'` `\&` etc. consume the
                        // backslash, leaving downstream `expandRepl` to
                        // tell value-derived `\X` (preserved with CTLESC
                        // markers from `expandReplacementOperand`) apart
                        // from source-derived ones.
                        if (n == '\n' && dqDepth > 0) {
                            // Dq line continuation: `\<NL>` strips both
                            // characters. Mirrors readDoubleQuoted's
                            // behavior so deferred-operand re-lex sees
                            // line continuations the same way the
                            // top-level dq scanner does.
                            bumpLine()
                            // don't append
                        } else if (patsubRepl && dqDepth > 0 && !isDqEscapable(n, defaultValueOp)) {
                            flushLit()
                            parts += WordChunk.Escaped(n.toString())
                        } else if (dqDepth > 0 && !isDqEscapable(n, defaultValueOp)) {
                            lit.append('\\')
                            lit.append(n)
                        } else {
                            flushLit()
                            parts += WordChunk.Escaped(n.toString())
                        }
                        pos++
                    }
                }

                '$' -> {
                    val before = pos
                    val exp = readParameterExpansion()
                    if (exp != null) {
                        flushLit()
                        parts += exp
                    } else {
                        lit.append('$')
                        if (pos == before) pos++
                    }
                }

                '\n' -> {
                    bumpLine()
                    lit.append(c)
                    pos++
                }

                '<', '>' -> {
                    // Process substitution `<(...)` / `>(...)` may appear
                    // inside a parameter-expansion operand (re-lexed via
                    // [lexDeferredOperandChunks]). new-exp1.sub:29:
                    //   cat ${foo:-<(echo a)}
                    // Without this branch, `<(echo a)` got swept into a
                    // single Literal and the runtime treated `<(echo` and
                    // `a)` as filename args to cat. Lone `<` / `>` (no
                    // following `(`) stays literal — operand boundaries
                    // are controlled by the surrounding `${...}`, not by
                    // redirection operators here.
                    if (pos + 1 < source.length && source[pos + 1] == '(') {
                        flushLit()
                        parts += readProcessSubstitution(direction = c)
                    } else {
                        lit.append(c)
                        pos++
                    }
                }

                else -> {
                    lit.append(c)
                    pos++
                }
            }
        }
        flushLit()
        return parts
    }

    private fun readSingleQuoted(): WordChunk.SingleQuoted {
        pos++
        val sb = StringBuilder()
        // Quote-spanning across alias-body boundary: when reading from an
        // alias body whose `'` doesn't close before body-EOF, popExhausted
        // brings the outer source into view so the closing `'` from the
        // surrounding script can pair with our opener. POSIX §2.3.1 text
        // semantics — bash `shell_getc` does the same.
        while (run {
                popExhausted()
                pos < source.length
            } && source[pos] != '\''
        ) {
            if (source[pos] == '\n') bumpLine()
            sb.append(source[pos])
            pos++
        }
        popExhausted()
        if (pos >= source.length) error("Unterminated single-quoted string at line $line")
        pos++
        return WordChunk.SingleQuoted(sb.toString())
    }

    /**
     * Lookahead from a `"` to decide whether an unescaped matching `"` exists
     * before the surrounding `${...}` closes. Used by the unescaped-dquote-strip arm
     * in [readWordChunksUntilDelimiter] — a lone `"` with no closer gets
     * silently dropped (bash behavior); a balanced `"..."` still goes
     * through [readDoubleQuoted] for inner backslash-strip semantics.
     *
     * Brace depth tracking: `${...}` nesting may contain inner `"..."`
     * regions whose own `"` chars don't count. We don't model the full
     * brace machinery here; a simple `\\X` skip plus a `}` early-out at
     * depth 0 is enough for the deferred-operand context (no further `{`
     * can appear without `$`).
     */
    private fun hasMatchingDoubleQuoteBeforeBraceClose(): Boolean {
        var p = pos + 1
        var braceBalance = 0
        while (p < source.length) {
            when (source[p]) {
                '\\' -> {
                    p += 2
                }

                '{' -> {
                    braceBalance++
                    p++
                }

                '}' -> {
                    if (braceBalance == 0) {
                        return false
                    } else {
                        braceBalance--
                        p++
                    }
                }

                '"' -> {
                    return true
                }

                '\n' -> {
                    if (!inDeferredOperandLex) return false else p++
                }

                else -> {
                    p++
                }
            }
        }
        return false
    }

    /**
     * Lookahead from a `'` to decide whether to treat it as a quote opener or a literal char.
     * Bash inside `${...}` args is greedy: if a matching `'` exists later on the same line (or
     * before a `)`/`\n`/EOF that would close the surrounding construct), it's a quote. Otherwise
     * treat as literal. We deliberately look PAST `}` chars because `'}'` is a single-quoted `}`.
     */
    private fun hasMatchingSingleQuoteBeforeBraceClose(crossLine: Boolean = false): Boolean {
        var p = pos + 1
        // `crossLine` || `inDeferredOperandLex` → allow `'` lookahead to
        // scan past newlines. The deferred path uses the operand text as
        // a self-contained string. The crossLine path covers pattern-op
        // operands inside dq, where bash accepts newlines inside `'...'`.
        val passNewlines = crossLine || inDeferredOperandLex
        while (p < source.length) {
            when (source[p]) {
                '\'' -> return true
                '\n' -> if (!passNewlines) return false else p++
                else -> p++
            }
        }
        return false
    }

    /**
     * Read `$'...'` as a LITERAL block — no ANSI-C escape processing.
     * Used by bash's bare-default-value-op operand rule: inside
     * `${x-...}` / `${x+...}` / `${x=...}` / `${x?...}`, a `$'...'`
     * sequence is emitted as raw text including the surrounding quotes
     * and any `\X` chars (bash leaves them verbatim). The contained text
     * still respects `\'` (so `$'a\'b'` matches a closing `'` after `b`,
     * not after `a`), but the backslash itself stays in the output.
     */
    private fun readDollarSingleQuotedLiteral(): WordChunk.Literal {
        pos += 2 // past `$'`
        val sb = StringBuilder("$'")
        while (pos < source.length && source[pos] != '\'') {
            val c = source[pos]
            if (c == '\\' && pos + 1 < source.length) {
                // Keep both chars verbatim. The `\` does suppress a closing
                // `'` so e.g. `$'a\'b'` doesn't terminate at the inner `'`.
                sb.append(c)
                sb.append(source[pos + 1])
                if (source[pos + 1] == '\n') bumpLine()
                pos += 2
                continue
            }
            if (c == '\n') bumpLine()
            sb.append(c)
            pos++
        }
        if (pos < source.length) {
            sb.append('\'')
            pos++ // past closing `'`
        }
        return WordChunk.Literal(sb.toString())
    }

    private fun readDollarSingleQuoted(): WordChunk.SingleQuoted {
        pos += 2 // past `$'`
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != '\'') {
            val c = source[pos]
            if (c != '\\') {
                if (c == '\n') bumpLine()
                sb.append(c)
                pos++
                continue
            }
            // Escape sequence.
            if (pos + 1 >= source.length) {
                sb.append('\\')
                pos++
                continue
            }
            val esc = source[pos + 1]
            // Fast path: 1:1 control/quote/backslash escapes — \a \b \e \E
            // \f \n \r \t \v \\ \' \" \?. The remaining when handles the
            // variable-length forms (hex, unicode, control, octal).
            val simple = ANSI_C_SIMPLE_ESCAPES[esc]
            if (simple != null) {
                sb.append(simple)
                pos += 2
                continue
            }
            when (esc) {
                'x' -> {
                    // `\xHH` — 1-2 hex digits. `\x{HHH...}` — unbounded hex
                    // enclosed in braces (closing `}` optional; consume hex
                    // digits up to it or the first non-hex char). The braced
                    // form encodes the value as UTF-8 codepoints; a value
                    // beyond 0x10FFFF wraps to its low byte (matches bash).
                    pos += 2
                    if (pos < source.length && source[pos] == '{') {
                        pos++ // past `{`
                        val hexStart = pos
                        while (pos < source.length && source[pos].isHexDigit()) pos++
                        val hex = source.substring(hexStart, pos)
                        if (pos < source.length && source[pos] == '}') pos++
                        if (hex.isEmpty()) {
                            sb.append(Ansi.NUL)
                        } else {
                            val v = hex.toLong(16)
                            if (v in 0L..0x10FFFFL) {
                                sb.appendCodePoint(v.toInt())
                            } else {
                                sb.append((v and 0xFFL).toInt().toChar())
                            }
                        }
                    } else {
                        val hexStart = pos
                        while (pos < source.length && pos - hexStart < 2 && source[pos].isHexDigit()) pos++
                        if (pos > hexStart) {
                            sb.append(source.substring(hexStart, pos).toInt(16).toChar())
                        } else {
                            sb.append("\\x")
                        }
                    }
                }

                'u' -> {
                    pos += 2
                    val hexStart = pos
                    while (pos < source.length && pos - hexStart < 4 && source[pos].isHexDigit()) pos++
                    if (pos > hexStart) {
                        sb.appendCodePoint(source.substring(hexStart, pos).toInt(16))
                    } else {
                        sb.append("\\u")
                    }
                }

                'U' -> {
                    pos += 2
                    val hexStart = pos
                    while (pos < source.length && pos - hexStart < 8 && source[pos].isHexDigit()) pos++
                    if (pos > hexStart) {
                        // `\Uffffffff` overflows Int.toInt(16) — parse via
                        // Long, then drop values outside the valid Unicode
                        // range (> 0x10FFFF). Bash itself produces locale-
                        // dependent output for out-of-range; emitting
                        // nothing avoids a lexer crash and keeps byte-
                        // exact diff comparisons clean.
                        val cp = source.substring(hexStart, pos).toLong(16).toInt()
                        if (cp in 0..0x10FFFF) {
                            sb.appendCodePoint(cp)
                        }
                    } else {
                        sb.append("\\U")
                    }
                }

                'c' -> {
                    // \cX — control character. Bash special cases:
                    //   \c?  → 0x7F (DEL), NOT 0x3F & 0x1F = 0x1F (US)
                    //   \c\\ → 0x1C (FS); the SECOND backslash is consumed
                    //         as part of the escape, not a continuation
                    //   anything else → ch.code & 0x1F (matches \c[, \c], \c^, \c_)
                    pos += 2
                    if (pos < source.length) {
                        val ch = source[pos]
                        when {
                            ch == '?' -> {
                                sb.append(0x7F.toChar())
                                pos++
                            }

                            ch == '\\' && pos + 1 < source.length && source[pos + 1] == '\\' -> {
                                sb.append(0x1C.toChar())
                                pos += 2
                            }

                            else -> {
                                sb.append((ch.code and 0x1F).toChar())
                                pos++
                            }
                        }
                    } else {
                        sb.append("\\c")
                    }
                }

                in '0'..'7' -> {
                    // \nnn octal, 1-3 digits.
                    pos++ // past `\`
                    val octStart = pos
                    while (pos < source.length && pos - octStart < 3 && source[pos] in '0'..'7') pos++
                    sb.append(source.substring(octStart, pos).toInt(8).toChar())
                }

                else -> {
                    // Unknown escape — keep both chars literal.
                    sb.append('\\').append(esc)
                    pos += 2
                }
            }
        }
        if (pos >= source.length) error("Unterminated \$'...' at line $line")
        pos++
        return WordChunk.SingleQuoted(sb.toString())
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
        if (cp <= 0xFFFF) {
            append(cp.toChar())
        } else {
            // Surrogate pair.
            val high = ((cp - 0x10000) shr 10) + 0xD800
            val low = ((cp - 0x10000) and 0x3FF) + 0xDC00
            append(high.toChar())
            append(low.toChar())
        }
        return this
    }

    private fun readDoubleQuoted(inParamExpand: Boolean = false): WordChunk.DoubleQuoted {
        pos++
        dqDepth++
        val parts = mutableListOf<WordChunk>()
        val lit = StringBuilder()

        fun flushLit() {
            if (lit.isNotEmpty()) {
                parts += WordChunk.Literal(lit.toString())
                lit.clear()
            }
        }
        // Quote-spanning across alias-body boundary — see readSingleQuoted.
        while (run {
                popExhausted()
                pos < source.length
            } && source[pos] != '"'
        ) {
            when (val c = source[pos]) {
                '\\' -> {
                    pos++
                    if (pos < source.length) {
                        val n = source[pos]
                        if (isDqEscapable(n)) {
                            if (n == '\n') {
                                bumpLine()
                            } else {
                                flushLit()
                                parts += WordChunk.Escaped(n.toString())
                            }
                        } else if (inParamExpand) {
                            // Bash: nested `"..."` inside `${...}` operand
                            // strips backslash before non-special chars
                            // (`\'` → `'`, `\p` → `p`). The outer dq's
                            // quote-removal pass collapses the `\\` pairs
                            // produced by the operand processor.
                            lit.append(n)
                        } else {
                            lit.append('\\').append(n)
                        }
                        pos++
                    }
                }

                '$' -> {
                    val before = pos
                    val exp = readParameterExpansion()
                    if (exp != null) {
                        flushLit()
                        parts += exp
                    } else {
                        lit.append('$')
                        if (pos == before) pos++
                    }
                }

                '`' -> {
                    flushLit()
                    parts += readBacktickSubstitution()
                }

                '\n' -> {
                    bumpLine()
                    lit.append(c)
                    pos++
                }

                else -> {
                    lit.append(c)
                    pos++
                }
            }
        }
        if (pos >= source.length) {
            dqDepth--
            error("Unterminated double-quoted string at line $line")
        }
        pos++
        dqDepth--
        flushLit()
        return WordChunk.DoubleQuoted(parts)
    }
}

/**
 * Inside a double-quoted context, a backslash is only special before one of
 * these characters (bash's `dquote.c` escape set). Defined as a predicate,
 * not a `Set`, so the per-backslash hot paths in [Lexer] allocate nothing and
 * don't box the char. With [includeRBrace] true the `}` of a default-value
 * `${x+\}}` operand also joins the set (see the readWordChunks call site).
 */
private fun isDqEscapable(
    c: Char,
    includeRBrace: Boolean = false,
): Boolean = c == '$' || c == '`' || c == '"' || c == '\\' || c == '\n' || (includeRBrace && c == '}')

/**
 * ANSI-C `$'\X'` escapes that map a single literal char to a single output
 * char (no parsing of digits past the escape letter). The variable-length
 * forms — `\xHH`, `\uHHHH`, `\UHHHHHHHH`, `\cX`, `\nnn` octal — stay in
 * [Lexer.readDollarSingleQuoted]'s `when` because they need to consume
 * follow-on characters. Bash `man 1 bash` § QUOTING / ANSI-C Quoting.
 */
private val ANSI_C_SIMPLE_ESCAPES: Map<Char, Char> =
    mapOf(
        'a' to '\u0007',
        'b' to '\b',
        'e' to '\u001B',
        'E' to '\u001B',
        'f' to '\u000C',
        'n' to '\n',
        'r' to '\r',
        't' to '\t',
        'v' to '\u000B',
        '\\' to '\\',
        '\'' to '\'',
        '"' to '"',
        '?' to '?',
    )
