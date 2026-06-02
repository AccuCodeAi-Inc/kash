package com.accucodeai.kash.parser

import com.accucodeai.kash.antlr.AntlrSyntaxException
import com.accucodeai.kash.antlr.configureForFailFast
import com.accucodeai.kash.antlr.displayToken
import com.accucodeai.kash.antlr.toAntlrDiagnostic
import com.accucodeai.kash.antlr.twoStageParse
import com.accucodeai.kash.antlr.twoStageParseFrom
import com.accucodeai.kash.ast.Script
import com.accucodeai.kash.ast.Statement
import com.accucodeai.kash.parser.antlr.AstBuilder
import com.accucodeai.kash.parser.antlr.kashTokenSourceFrom
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.misc.ParseCancellationException
import com.accucodeai.kash.parser.antlr.KashParser as AntlrKashParser

/**
 * Thrown by [Parser.parseScript] on any parse failure (including incomplete
 * input). New code should prefer [Parser.parse] which returns a typed
 * [ParseResult] so the REPL can distinguish "needs more input" from "real
 * syntax error".
 */
public class ParseException(
    message: String,
    /** Source line where the failure occurred (1-based), or 0 if unknown. */
    public val line: Int = 0,
    /** Column of the offending token (0-based), or 0 if unknown. */
    public val column: Int = 0,
    /**
     * Text of the token that triggered the failure, if identifiable. Used
     * by `sh -c` and script-load diagnostics to format bash-style
     * `syntax error near unexpected token '<tok>'`.
     */
    public val offendingToken: String? = null,
) : RuntimeException(message)

/**
 * Facade for shell-script parsing.
 *
 *  - [parse] returns a structured [ParseResult] (`Ok` / `Incomplete` / `Error`),
 *    which the REPL uses to decide whether to prompt for more input.
 *  - [parseScript] throws [ParseException] on any non-`Ok` result — kept for
 *    script-execution callers that want the all-or-nothing contract.
 *
 * `WordChunk.CommandSubstitution` recurses through this facade so nested
 * `$(...)` parsing goes through the same code path as the top-level script —
 * propagating [aliasResolver] so runtime aliases apply inside command
 * substitutions too.
 */
public class Parser(
    private val source: String,
    private val aliasResolver: AliasResolver = AliasResolver.Empty,
    /** POSIX mode — see [Lexer]'s posixMode. */
    private val posixMode: Boolean = false,
    /**
     * Starting source-line offset for the inner [Lexer]. Defaults to 1
     * for a fresh top-level parse. Deferred `$(...)`/backtick re-parses
     * pass the outer-source line where the cmdsub was opened, so every
     * Statement / Word / runtime diagnostic produced from the body
     * carries the correct outer-source line. See
     * [com.accucodeai.kash.interpreter.parseCommandSubstitutionBody].
     */
    private val startLine: Int = 1,
    /**
     * Whether extglob pattern prefixes are recognized at lex time — see
     * [Lexer]'s extglobEnabled. Defaults to true (kash's historical
     * always-on behavior). Wired through [StatementStream] so a
     * mid-script `shopt -s extglob` / `shopt -u extglob` affects
     * subsequent statements.
     */
    private val extglobEnabled: Boolean = true,
) {
    public fun parse(): ParseResult = parseViaAntlr(source, aliasResolver, posixMode, startLine, extglobEnabled)

    public fun parseScript(): Script =
        when (val r = parseViaAntlr(source, aliasResolver, posixMode, startLine, extglobEnabled)) {
            is ParseResult.Ok -> r.script
            is ParseResult.Incomplete -> throw ParseException("incomplete: ${r.reason}", r.line)
            is ParseResult.Error -> throw ParseException(r.message, r.line, r.column, r.offendingToken)
        }

    /**
     * Statement-streaming parse: collect statements one at a time via
     * `parser.statement()` until either input is exhausted or a parse
     * error is encountered. Returns (statements parsed before the error,
     * optional error).
     *
     * Bash interleaves parse and execute — a syntax error mid-script does
     * NOT prevent already-parsed statements from executing. The
     * `${THIS_SH} -c '... echo foo in v\ndo …'` conformance case relies
     * on this: bash prints `foo in v` (from the first statement) before
     * emitting `syntax error near unexpected token 'do'`. Our ANTLR-based
     * up-front parse can't capture that interleaving, but
     * parse-then-execute-up-to-error is a close approximation that
     * suffices for the conformance corpus.
     */
    public fun parseUntilError(): Pair<List<Statement>, ParseException?> =
        parseStatementStream(source, aliasResolver, posixMode, startLine, extglobEnabled)
            .let { (s, e, _) -> s to e }

    /** Like [parseUntilError] but also returns lexer-collected warnings. */
    public fun parseUntilErrorWithWarnings(): Triple<List<Statement>, ParseException?, List<ParseWarning>> =
        parseStatementStream(source, aliasResolver, posixMode, startLine, extglobEnabled)
}

private fun parseViaAntlr(
    source: String,
    aliasResolver: AliasResolver,
    posixMode: Boolean = false,
    startLine: Int = 1,
    extglobEnabled: Boolean = true,
): ParseResult {
    // Lexer-level errors (unterminated quote, pending heredoc body) bubble out
    // as IllegalStateException — preserved from the legacy lexer's contract.
    // Callers that want to treat them as continuation (the REPL) catch it at
    // their own boundary; scripts get the original exception type.
    //
    // POSIX §2.3.1: alias substitution happens *inside* the Lexer at the
    // character level (bash `alias-input-stack push`/`alias-input-stack pop` mechanics — see
    // Lexer.Segment). This lets a body's open quote pair with a closing
    // quote in the surrounding source, e.g. `alias foo="echo 'Error:";
    // foo bar'` works as bash intends.
    val lexer = Lexer(source, aliasResolver, posixMode, startLine = startLine, extglobEnabled = extglobEnabled)
    val sourceTokens = lexer.tokenize()
    val warnings = lexer.warnings.map { ParseWarning(it.line, it.message, it.suppressLinePrefix) }
    // Lex-time recovery sentinel: tokens before this one are complete, but
    // an unterminated construct stopped the lex. Bash treats input ending
    // mid-`${...`/`'...`/`"...` as incomplete in interactive mode (PS2
    // prompt) and as a hard error in script mode; the REPL distinguishes
    // by reading [ParseResult.Incomplete] vs [ParseResult.Error]. Unbalanced
    // openers count as Incomplete; otherwise hard error.
    val lexError = sourceTokens.lastOrNull() as? Token.LexError
    if (lexError != null) {
        val depth =
            sourceTokens.sumOf {
                when (it) {
                    is Token.Keyword if it.word in COMPOUND_OPENERS -> 1
                    is Token.Keyword if it.word in COMPOUND_CLOSERS -> -1
                    is Token.Operator if it.op == "(" -> 1
                    is Token.Operator if it.op == ")" -> -1
                    else -> 0
                }
            }
        return if (depth > 0) {
            ParseResult.Incomplete(lexError.message, lexError.line)
        } else {
            ParseResult.Error(lexError.message, lexError.line, column = 0, offendingToken = lexError.offendingToken)
        }
    }
    val tokenSource = kashTokenSourceFrom(sourceTokens, lexer.tokenEndPositions())
    val stream = CommonTokenStream(tokenSource)
    val parser = AntlrKashParser(stream).also { configureForFailFast(it) }
    return try {
        val scriptCtx = twoStageParse(parser, stream) { parser.script() }
        ParseResult.Ok(AstBuilder(aliasResolver, source).script(scriptCtx), warnings)
    } catch (e: ParseCancellationException) {
        classifyAntlrFailure(sourceTokens, e.cause as? RecognitionException, e.message)
    } catch (e: RecognitionException) {
        classifyAntlrFailure(sourceTokens, e, e.message)
    } catch (e: AntlrSyntaxException) {
        classifyAntlrFailure(sourceTokens, e.cause as? RecognitionException, e.message)
    } catch (e: ParseException) {
        ParseResult.Error(
            e.message ?: "parse error",
            line = e.line,
            column = e.column,
            offendingToken = e.offendingToken,
        )
    }
}

/**
 * Map an ANTLR parse failure to [ParseResult] using opener/closer balance on
 * the original token stream. If compound openers (`if`, `for`, `while`,
 * `until`, `case`, `[[`, `{`, `(`) outnumber their matching closers, the input
 * ended mid-construct and more lines can save it. Otherwise the failure is a
 * real syntax error.
 *
 * ANTLR's own `offendingToken` rewinds after prediction failure so the parser
 * state can't be trusted here; balancing the lexer output is the same signal
 * the legacy `peekIsWord` parser relied on, just expressed declaratively.
 */
private fun classifyAntlrFailure(
    tokens: List<Token>,
    re: RecognitionException?,
    fallbackMessage: String?,
): ParseResult {
    // ANTLR's `NoViableAltException.message` is often null or a stack-trace-y
    // class name. Synthesize a bash-style diagnostic from the offending token
    // text so downstream `sh -c` callers can emit `syntax error near
    // unexpected token '<tok>'` (POSIX §2.8.1 mandates a diagnostic; format
    // is unspecified — we match bash for conformance fixtures).
    val diag = re?.toAntlrDiagnostic()
    val line = diag?.line ?: 0
    val col = diag?.column ?: 0
    // ANTLR's offendingToken rewinds after prediction failure and is
    // frequently unrelated to the real error site (e.g. for
    // `case x in x) ;; x) done esac` it reports `x` rather than the
    // reserved word `done` that bash would name). Run a structural
    // scanner over the lexer token stream that walks compound openers/
    // closers; if it finds a closer in the wrong context (e.g. `done`
    // outside `for/while/until/select`, `esac` outside `case`, `fi`
    // outside `if`, `}` outside `{`, `)` outside a balanced opener), use
    // that token as the offender. Falls back to ANTLR's offendingToken
    // when no structural mismatch is found.
    val structural = findOffendingByStructure(tokens)
    val displayTok = structural?.text ?: displayToken(diag?.offendingText)
    val effectiveLine = structural?.line ?: line
    val effectiveCol = structural?.col ?: col
    // Bash's EOF-in-unclosed-compound diagnostic (see classifyStreamError
    // for the rationale). Only fires when ANTLR's offending text is EOF
    // AND the structural scan didn't pin a mid-stream mismatch — i.e.
    // the input genuinely runs out inside an open compound.
    val offendingIsEof =
        diag?.offendingText == "<EOF>" ||
            diag?.offendingText == "EOF" ||
            diag?.offendingText.isNullOrBlank()
    val unclosed =
        if (offendingIsEof && structural == null) findUnclosedOpener(tokens) else null
    val synth =
        when {
            unclosed != null && unclosed.text == "(" -> {
                "unexpected EOF while looking for matching `)'"
            }

            unclosed != null -> {
                "syntax error: unexpected end of file from `${unclosed.text}' command on line ${unclosed.line}"
            }

            displayTok != null -> {
                "syntax error near unexpected token `$displayTok'"
            }

            else -> {
                null
            }
        }
    val msg = synth ?: re?.message ?: fallbackMessage ?: "parse error"

    // Pending heredoc: `<<EOF` with no body token following means the lexer
    // hit input-end before it could capture the body. The REPL should prompt
    // for more so the body and terminator can arrive.
    val lastSignificant = tokens.lastOrNull { it !is Token.Eof && it !is Token.Newline }
    if (lastSignificant is Token.RedirOpTok && (lastSignificant.op == "<<" || lastSignificant.op == "<<-")) {
        return ParseResult.Incomplete("expected heredoc body", lastSignificant.line)
    }

    // Compound-construct balance: an unclosed opener means the input ended
    // mid-`if`/`for`/`while`/`case`/`[[`/`{`/`(` and more lines might close
    // it (→ Incomplete). Everything balanced (or a misplaced closer pinned
    // by the structural scan) — the failure is a real syntax error.
    //
    // We reuse [findUnclosedOpener]'s stack walk rather than a naive
    // open-minus-close token sum: a case-pattern terminator `)` (as in
    // `Linux*)`) is a `Token.Operator ")"` but does NOT close the enclosing
    // `case`. A flat sum miscounts it as a closer, balancing the `case` to
    // zero and misclassifying a half-read `case` (e.g. fed one line at a time
    // by the piped-stdin loop) as a hard Error — which then discards the
    // pending buffer mid-statement. The stack walk only pops a `case` on
    // `esac`, so the partial input is correctly Incomplete. A structural
    // mismatch (closer in the wrong place) still wins as an Error.
    return if (structural == null && findUnclosedOpener(tokens) != null) {
        ParseResult.Incomplete(msg, line)
    } else {
        ParseResult.Error(msg, effectiveLine, effectiveCol, displayTok)
    }
}

private val COMPOUND_OPENERS = setOf("if", "for", "while", "until", "case", "[[", "{")
private val COMPOUND_CLOSERS = setOf("fi", "done", "esac", "]]", "}")

/** A structural mismatch found while walking compound open/close tokens. */
private data class OffendingTok(
    val text: String,
    val line: Int,
    val col: Int,
)

/** An unclosed compound-command opener at EOF. */
private data class UnclosedOpener(
    /** Opener text — `{`, `(`, `[[`, `if`, `case`, `for`, `while`, `until`, `select`. */
    val text: String,
    /** Source line where the opener appeared. */
    val line: Int,
)

/**
 * Walks [tokens] and returns the innermost (most-recently-opened) compound
 * opener that has no matching closer in the stream. Returns `null` if every
 * opener pairs cleanly. The caller uses this to emit bash's
 *   "syntax error: unexpected end of file from `<opener>' command on line N"
 * diagnostic when EOF arrives mid-compound — matching the shape bash emits
 * from `eval`/`source` on adversarial input (bash man page §SHELL GRAMMAR
 * names the unclosed opener in the diagnostic, e.g. unmatched `{`).
 *
 * Symmetric to [findOffendingByStructure] but looks at the unclosed *prefix*
 * instead of the misplaced *closer*.
 */
private fun findUnclosedOpener(tokens: List<Token>): UnclosedOpener? {
    val stack = ArrayDeque<UnclosedOpener>()
    for (t in tokens) {
        when (t) {
            is Token.Keyword -> {
                when (t.word) {
                    "if" -> {
                        stack.addLast(UnclosedOpener("if", t.line))
                    }

                    "case" -> {
                        stack.addLast(UnclosedOpener("case", t.line))
                    }

                    "for", "while", "until", "select" -> {
                        stack.addLast(UnclosedOpener(t.word, t.line))
                    }

                    // `{` / `}` are emitted as Keywords when they appear at
                    // command-start (Lexer.kt:566). Track them here so a
                    // function body's `{ ... ` shows up as unclosed.
                    "{" -> {
                        stack.addLast(UnclosedOpener("{", t.line))
                    }

                    "}" -> {
                        if (stack.lastOrNull()?.text == "{") stack.removeLast()
                    }

                    "fi" -> {
                        if (stack.lastOrNull()?.text == "if") stack.removeLast()
                    }

                    "esac" -> {
                        if (stack.lastOrNull()?.text == "case") stack.removeLast()
                    }

                    "done" -> {
                        if (stack.lastOrNull()?.text in setOf("for", "while", "until", "select")) {
                            stack.removeLast()
                        }
                    }

                    else -> { /* ignore other keywords */ }
                }
            }

            is Token.Operator -> {
                when (t.op) {
                    "(" -> {
                        stack.addLast(UnclosedOpener("(", t.line))
                    }

                    "{" -> {
                        stack.addLast(UnclosedOpener("{", t.line))
                    }

                    "[[" -> {
                        stack.addLast(UnclosedOpener("[[", t.line))
                    }

                    ")" -> {
                        if (stack.lastOrNull()?.text == "(") stack.removeLast()
                    }

                    "}" -> {
                        if (stack.lastOrNull()?.text == "{") stack.removeLast()
                    }

                    "]]" -> {
                        if (stack.lastOrNull()?.text == "[[") stack.removeLast()
                    }

                    else -> { /* ignore other operators */ }
                }
            }

            else -> { /* ignore non-structural tokens */ }
        }
    }
    return stack.lastOrNull()
}

/**
 * Scan [tokens] and return the first reserved-word or operator that lands
 * in a structurally impossible position — the offender bash names in its
 * `syntax error near unexpected token` diagnostic. Walks a small stack:
 * - `if` expects `fi`
 * - `for`/`while`/`until`/`select`/`do` expects `done`
 * - `case` expects `esac`
 * - `(` expects `)`, `{` expects `}`, `[[` expects `]]`
 *
 * When a closer is seen but the stack top expects a different closer (or
 * the stack is empty), that closer is the offender. Returns `null` if
 * every closer matched its open context — caller falls back to ANTLR's
 * own report.
 */
private fun findOffendingByStructure(tokens: List<Token>): OffendingTok? {
    val expected = ArrayDeque<String>() // stack of expected closer tokens
    // First pass: detect mid-command `NAME=(` patterns. The lexer emits this
    // as WORD("NAME=") followed by Operator("(") when not at command position
    // (only assignment-prefix gets the ArrayAssignTok). Bash names the `(`
    // as the offender in `syntax error near unexpected token '('`. ANTLR
    // rewinds to the preceding WORD after prediction failure, so the
    // structural scanner needs to surface the `(` explicitly.
    //
    // The pattern is: at least one prior WORD (the command name), then a
    // WORD whose literal text ends with `=` or `+=`, immediately followed
    // by a `(` operator. Subshell `cmd (subshell)` at command-position is
    // covered by the grammar so we only flag the WORD-ending-in-`=` case.
    var hadCommandWord = false
    for ((i, t) in tokens.withIndex()) {
        if (t is Token.WordTok) {
            if (!hadCommandWord) {
                hadCommandWord = true
                continue
            }
            // Look at the LAST chunk — if it's a Literal ending with `=`
            // or `+=`, we have an assignment-prefix word (the source
            // might be `NAME=`, `''=`, `"name"=`, `+=`, etc. — quoted-
            // empty heads still produce a trailing Literal("=") chunk).
            val lastLit = (t.parts.lastOrNull() as? WordChunk.Literal)?.value
            if (lastLit != null && (lastLit.endsWith("=") || lastLit.endsWith("+="))) {
                val next = tokens.getOrNull(i + 1)
                if (next is Token.Operator && next.op == "(") {
                    return OffendingTok("(", next.line, 0)
                }
            }
        } else if (t is Token.Newline ||
            (t is Token.Operator && (t.op == ";" || t.op == "&" || t.op == "|" || t.op == "||" || t.op == "&&"))
        ) {
            hadCommandWord = false
        }
    }
    for (t in tokens) {
        when (t) {
            is Token.Keyword -> {
                when (t.word) {
                    "if" -> {
                        expected.addLast("fi")
                    }

                    "case" -> {
                        expected.addLast("esac")
                    }

                    "for", "while", "until", "select" -> {
                        expected.addLast("done")
                    }

                    "do" -> {
                        // `do` only legal when a `for`/`while`/`until`/`select`
                        // has pushed `done`. Bash names a bare `do` as the
                        // offender (e.g. when an alias has replaced the loop
                        // keyword with a non-keyword).
                        if (expected.isEmpty() || expected.last() != "done") {
                            return OffendingTok("do", t.line, 0)
                        }
                    }

                    "then" -> {
                        if (expected.isEmpty() || expected.last() != "fi") {
                            return OffendingTok("then", t.line, 0)
                        }
                    }

                    "fi", "esac", "done" -> {
                        if (expected.isEmpty() || expected.last() != t.word) {
                            return OffendingTok(t.word, t.line, 0)
                        }
                        expected.removeLast()
                    }
                }
            }

            is Token.Operator -> {
                when (t.op) {
                    "(" -> {
                        expected.addLast(")")
                    }

                    "{" -> {
                        expected.addLast("}")
                    }

                    "[[" -> {
                        expected.addLast("]]")
                    }

                    ")" -> {
                        if (expected.isNotEmpty() && expected.last() == ")") {
                            expected.removeLast()
                        }
                        // `)` outside a `(` group: usually part of a case
                        // pattern terminator — don't flag.
                    }

                    "}" -> {
                        if (expected.isEmpty() || expected.last() != "}") {
                            return OffendingTok("}", t.line, 0)
                        }
                        expected.removeLast()
                    }

                    "]]" -> {
                        if (expected.isEmpty() || expected.last() != "]]") {
                            return OffendingTok("]]", t.line, 0)
                        }
                        expected.removeLast()
                    }
                }
            }

            else -> { /* ignore */ }
        }
    }
    return null
}

// Statement-streaming parse loop — calls `parser.statement()` rather
// than `parser.script()`, collecting statements until input is
// exhausted or a parse error is hit. Bash interleaves parse and
// execute; the per-statement helpers below return the pre-error
// statements so the runtime can execute them before emitting the
// diagnostic.
//
// Each iteration:
//   1. Skip separator tokens (NL, ;, &).
//   2. If EOF: return.
//   3. Try `parser.statement()`. On success: build AST, look at the
//      trailing separator to decide background, consume it.
//   4. On failure: classify and return what we have plus the error.
//
// Stage 1 uses SLL prediction (fast path); stage 2 falls back to LL
// like the legacy `parseViaAntlr` does, but per-statement.

/**
 * Cursor over the statements in [source], parsed lazily one at a time.
 *
 * Each [next] call invokes [posixModeProvider] to read the *current* POSIX
 * mode and re-parses the source against that mode before returning the
 * next statement. The parse-execute-parse-execute shape is what every
 * POSIX shell uses for interactive and script mode — runtime state
 * changes like `set -o posix` between iterations affect the lex of
 * subsequent statements.
 *
 * V1 implementation re-parses the whole source on each call and returns
 * `statements[consumed]`. O(N²) total work for N statements, but the lex
 * is a single linear scan and shell scripts are bounded.
 *
 * Errors are yielded LAST: pre-error statements are returned in order
 * via [NextResult.Statement], then [NextResult.Error] once. After that,
 * subsequent calls return [NextResult.Eof] (the stream is drained).
 *
 * Warnings (lexer-collected) are attached to the first yielded statement.
 */
public class StatementStream(
    private val source: String,
    private val aliasResolver: AliasResolver = AliasResolver.Empty,
    private val posixModeProvider: () -> Boolean = { false },
    private val startLine: Int = 1,
    /**
     * When true, syntax errors at the top level abort the stream
     * immediately (bash's `bash -c '...'` behavior: a single syntax error
     * exits with status 2, subsequent statements never run). When false
     * (default), the stream recovers at the next newline so already-
     * parsed valid statements behind a mid-script error can continue —
     * bash's behavior for in-command errors like
     * `test=(first & second); echo $?`.
     */
    private val abortOnSyntaxError: Boolean = false,
    /**
     * Mid-script `shopt -s extglob` / `shopt -u extglob` toggle source.
     * Sampled at each [next] call; a value change relative to the cached
     * lex's snapshot invalidates the cache, same mechanism as
     * [posixModeProvider]. Defaults to a constant `true` to preserve
     * kash's historical always-on lex-time extglob behavior.
     */
    private val extglobProvider: () -> Boolean = { true },
    /**
     * Monotonic alias-table version. When the value sampled at [next]
     * differs from the cache's recorded version, the cached lex is
     * dropped and the remaining source re-lexed — necessary because
     * the cmdsub body bound finder consults aliasResolver at lex time,
     * so a multi-statement batch that captured cmdsub bodies before
     * an `alias` statement ran would have the wrong boundary.
     */
    private val aliasVersionProvider: () -> Long = { 0L },
) {
    // Source-character cursor: position in [source] where the NEXT lex
    // will start when the current cache is invalidated. Does NOT
    // advance after every statement — multi-statement alias bodies
    // produce all their statements from ONE lex pass before cursor
    // moves past the alias call site in root source.
    private var cursor: Int = 0

    // Logical line at `source[cursor]`. Re-computed by counting `\n`s
    // in the consumed span each time cursor advances.
    private var line: Int = startLine
    private var done: Boolean = false
    private var pendingError: ParseException? = null

    // Warnings staged for an upcoming [NextResult.Error] yield when the
    // statement-yield path can't carry them (because no statement
    // parsed before the lex error). Drained as the Error's
    // `warnings` payload.
    private var pendingErrorWarnings: List<ParseWarning> = emptyList()
    private var warningsEmitted: Boolean = false

    // Cached lex pass. Holds an open ANTLR parser+stream so multiple
    // statements from one lex (e.g. multi-statement alias bodies whose
    // tokens all share the same root-source position) can be yielded
    // one at a time before re-lexing.
    //
    // Invalidated when [posixModeProvider] reports a value different
    // from [CachedLex.posixMode] — bash's `set -o posix` mid-script
    // re-parses subsequent commands under the new rule.
    private var cachedLex: CachedLex? = null

    internal class CachedLex(
        val parser: AntlrKashParser,
        val stream: CommonTokenStream,
        val builder: AstBuilder,
        val rawTokens: List<Token>,
        val pendingLexError: ParseException?,
        val warnings: List<ParseWarning>,
        val posixMode: Boolean,
        val extglobEnabled: Boolean,
        val aliasVersion: Long,
    )

    public sealed interface NextResult {
        public data class Statement(
            val statement: com.accucodeai.kash.ast.Statement,
            val warnings: List<ParseWarning>,
            /** Source line of the LAST char consumed for this statement
             *  (1-based). For a heredoc command at line 181 whose body
             *  extends to line 184, this is 184. Used by
             *  [com.accucodeai.kash.interpreter.Interpreter.runStreaming]
             *  to flush lex warnings whose `line` falls within the
             *  statement's source span — bash's "warning right before
             *  the affected command's output." */
            val consumedToLine: Int,
        ) : NextResult

        public data class Error(
            val error: ParseException,
            /** Lexer-collected warnings from the same lex pass that produced
             *  this error. Non-empty when the error fires WITHOUT any prior
             *  successful statement (so the [Statement] yield path never
             *  got the chance to emit warnings) — typically a script that
             *  goes straight to lex error, e.g. an unclosed cmdsub body
             *  containing an unterminated heredoc. */
            val warnings: List<ParseWarning> = emptyList(),
        ) : NextResult

        public data object Eof : NextResult
    }

    /** True once the stream has surfaced its terminal error or EOF and
     *  will only return [NextResult.Eof] from here on. */
    public fun done(): Boolean = done

    /** Yield the next statement, an error, or EOF. */
    public fun next(): NextResult {
        if (done) return NextResult.Eof
        pendingError?.let {
            pendingError = null
            val ws = pendingErrorWarnings
            pendingErrorWarnings = emptyList()
            // Recover at the next statement boundary so subsequent statements
            // can still parse — bash discards the failing command and
            // continues at the next newline. Without recovery, a single
            // mid-script syntax error aborts the rest of the script.
            //
            // Only attempt recovery if we can identify the offending source
            // line AND the error carries an offendingToken (i.e. it's a
            // real syntax error like `a=(x & y)`, not an unterminated
            // construct that swallows the rest of the input by nature —
            // for those, bash also stops).
            val errorLine = it.line
            if (recoverable(it) && errorLine > 0) {
                val oldCursor = cursor
                advanceCursorPastLine(errorLine)
                if (cursor == oldCursor || cursor >= source.length) done = true
            } else {
                done = true
            }
            return NextResult.Error(it, ws)
        }

        val currentPosix = posixModeProvider()
        val currentExtglob = extglobProvider()
        val currentAliasVersion = aliasVersionProvider()
        val cache = cachedLex
        if (cache != null &&
            (
                cache.posixMode != currentPosix ||
                    cache.extglobEnabled != currentExtglob
            )
        ) {
            // posix/extglob toggled — advance cursor past whatever the
            // stale cache covered, drop the cache, fall through to re-lex.
            advanceCursorAndDropCache()
        } else if (cache != null && cache.aliasVersion != currentAliasVersion) {
            // Aliases mutated mid-cache. Two scenarios:
            //  (1) The cache covers a single outer-source statement (cursor
            //      just past it) — invalidating advances cursor to next
            //      statement, which is re-lexed against the new alias
            //      state. Needed so `alias x=case; echo $( x in p) ... )`
            //      sees `x` as alias `case` at the cmdsub body lex.
            //  (2) The cache covers an alias-body expansion (multiple
            //      statements sharing the same root-source position; the
            //      alias mutated INSIDE the body). Invalidating here drops
            //      remaining statements (advanceCursor would skip past
            //      them since they share the call-site sourceEnd). Detect
            //      this by checking whether the lookahead has more tokens
            //      with sourceEnd ≤ the last-consumed token's sourceEnd —
            //      if so, the cache is mid-alias-body and we must NOT
            //      drop it.
            if (!cacheStillInsideAliasBody()) {
                advanceCursorAndDropCache()
            }
        }

        if (cachedLex == null) {
            if (cursor >= source.length) {
                done = true
                return NextResult.Eof
            }
            cachedLex =
                buildCachedLex(
                    source.substring(cursor),
                    aliasResolver,
                    currentPosix,
                    line,
                    currentExtglob,
                    currentAliasVersion,
                )
        }
        val c = cachedLex!!

        // Skip leading separators.
        fun isSep(t: Int): Boolean =
            t == AntlrKashParser.Tokens.NL ||
                t == AntlrKashParser.Tokens.OP_SEMI ||
                t == AntlrKashParser.Tokens.OP_AMP
        while (isSep(c.stream.LA(1))) c.stream.consume()

        // Cached stream exhausted → advance cursor, drop cache, recurse
        // (the next iteration re-lexes from the new cursor position).
        // Any lex error captured by this cache surfaces after the prior
        // successful statements have been yielded — bash's "earlier
        // commands run, then the error fires" semantic. Mode-toggle
        // re-lex is handled separately at the top of [next] by
        // comparing [cache.posixMode] against [posixModeProvider]; once
        // we're in the EOF branch the cache's worldview is final.
        if (c.stream.LA(1) == AntlrKashParser.Tokens.EOF) {
            val err = c.pendingLexError
            // If this cache produced warnings that were never attached to
            // a [NextResult.Statement] (because no statement parsed
            // cleanly from it), stage them so the upcoming Error yield
            // carries them. Matches bash's "heredoc-EOF warning fires
            // alongside the cmdsub parse error" output for
            // comsub-eof-style scripts.
            if (err != null && !warningsEmitted && c.warnings.isNotEmpty()) {
                pendingErrorWarnings = c.warnings
                warningsEmitted = true
            }
            // For a recoverable lex error, defer cursor advancement to
            // the recovery handler in [next] — it knows the offending
            // source line and can advance precisely past it. Without
            // this, advanceCursorAndDropCache's "no positioned tokens
            // → advance to source.length" fallback fires when the lex
            // error happens before any clean token, killing recovery.
            if (err != null && recoverable(err)) {
                cachedLex = null
                pendingError = err
                return next()
            }
            advanceCursorAndDropCache()
            if (err != null) pendingError = err
            return next()
        }

        // When a parse-time exception fires before any statement yielded
        // from this cache, the cache's lex warnings would otherwise be
        // dropped — attach them to the Error.
        fun errorWithCacheWarnings(e: ParseException): NextResult.Error {
            val ws =
                if (!warningsEmitted && c.warnings.isNotEmpty()) {
                    warningsEmitted = true
                    c.warnings
                } else {
                    emptyList()
                }
            return NextResult.Error(e, ws)
        }
        val markStart = c.stream.index()
        val mark = markStart
        val stmtCtx: AntlrKashParser.StatementContext =
            try {
                twoStageParseFrom(c.parser, c.stream, mark) { c.parser.statement() }
            } catch (e: ParseCancellationException) {
                val err = c.pendingLexError ?: classifyStreamError(e, c.rawTokens)
                tryRecoverFromAntlrError(err)
                return errorWithCacheWarnings(err)
            } catch (e: RecognitionException) {
                val err = c.pendingLexError ?: classifyStreamError(e, c.rawTokens)
                tryRecoverFromAntlrError(err)
                return errorWithCacheWarnings(err)
            } catch (e: AntlrSyntaxException) {
                val err = c.pendingLexError ?: classifyStreamError(e, c.rawTokens)
                tryRecoverFromAntlrError(err)
                return errorWithCacheWarnings(err)
            }
        if (c.stream.index() == mark) {
            done = true
            return errorWithCacheWarnings(
                ParseException(
                    "internal: parser made no progress at token ${c.stream.LA(1)}",
                    line = 0,
                ),
            )
        }
        val bg = c.stream.LA(1) == AntlrKashParser.Tokens.OP_AMP
        val stmt: com.accucodeai.kash.ast.Statement =
            try {
                c.builder.singleStatement(stmtCtx, bg)
            } catch (e: ParseException) {
                tryRecoverFromAntlrError(e)
                return errorWithCacheWarnings(e)
            }
        // Eat the trailing separator (if any) so the next iteration's
        // leading-separator skip doesn't re-process it. If the next
        // token is neither a separator nor EOF, the two statements run
        // together without `;`/`\n` between them — that's a bash syntax
        // error (`x { :;} { echo vuln; }` is exactly the CVE-2014-6278
        // adversarial-input shape from bash/exportfunc).
        val sep = c.stream.LA(1)
        if (sep == AntlrKashParser.Tokens.OP_AMP ||
            sep == AntlrKashParser.Tokens.OP_SEMI ||
            sep == AntlrKashParser.Tokens.NL
        ) {
            c.stream.consume()
        } else if (sep != AntlrKashParser.Tokens.EOF) {
            val offending = c.stream.LT(1)?.text ?: "?"
            val err =
                ParseException(
                    "syntax error near unexpected token `$offending'",
                    line = c.stream.LT(1)?.line ?: 0,
                    offendingToken = offending,
                )
            tryRecoverFromAntlrError(err)
            return errorWithCacheWarnings(err)
        }
        val emitted =
            if (!warningsEmitted) {
                warningsEmitted = true
                c.warnings
            } else {
                emptyList()
            }
        // Compute the source-line of the LAST char consumed for this
        // statement. Take max sourceEnd over tokens at indices
        // [markStart, c.stream.index()), convert local-substring offset
        // → global by adding cursor, then count newlines in
        // source[0..globalEnd) to get the absolute line.
        var stmtMaxEnd = 0
        val endIdx = c.stream.index()
        for (i in markStart until endIdx) {
            val tok = c.stream.get(i) as? com.accucodeai.kash.parser.antlr.KashAntlrToken ?: continue
            if (tok.sourceEnd > stmtMaxEnd) stmtMaxEnd = tok.sourceEnd
        }
        val globalEnd = (cursor + stmtMaxEnd).coerceAtMost(source.length)
        var consumedLine = startLine
        for (i in 0 until globalEnd) {
            if (source[i] == '\n') consumedLine++
        }
        return NextResult.Statement(stmt, emitted, consumedLine)
    }

    /**
     * True if the cache is currently in the middle of yielding statements
     * from an alias-body expansion — i.e. there are buffered tokens
     * ahead whose root-source position doesn't advance past the
     * already-consumed tokens. In that case the cache is committed to
     * those statements; dropping it would lose them because the
     * cursor-advance is computed from sourceEnd, and alias-body tokens
     * share the call-site sourceEnd.
     */
    private fun cacheStillInsideAliasBody(): Boolean {
        val c = cachedLex ?: return false
        val idx = c.stream.index()
        // sourceEnd of the most recently CONSUMED positioned token.
        var lastConsumedEnd = 0
        for (i in 0 until idx) {
            val tok = c.stream.get(i) as? com.accucodeai.kash.parser.antlr.KashAntlrToken ?: continue
            if (tok.sourceEnd > lastConsumedEnd) lastConsumedEnd = tok.sourceEnd
        }
        val totalTokens = c.stream.size()
        for (i in idx until totalTokens) {
            val tok = c.stream.get(i) as? com.accucodeai.kash.parser.antlr.KashAntlrToken ?: continue
            // EOF token (sourceEnd typically 0/end) shouldn't count.
            if (tok.type == AntlrKashParser.Tokens.EOF) continue
            // If any pending token has sourceEnd ≤ lastConsumedEnd, it's
            // sharing the call-site position with already-consumed tokens
            // (alias-body expansion).
            if (tok.sourceEnd <= lastConsumedEnd && tok.sourceEnd > 0) return true
        }
        return false
    }

    /**
     * Skip cursor past the newline terminating [errorLine]. Used to recover
     * from a mid-script lex error so subsequent statements can still parse,
     * matching bash's "discard failing command, continue at next boundary"
     * semantic.
     */
    private fun advanceCursorPastLine(errorLine: Int) {
        var p = cursor
        var ln = line
        while (p < source.length) {
            val c = source[p]
            p++
            if (c == '\n') {
                ln++
                if (ln > errorLine) break
            }
        }
        cursor = p
        line = ln
    }

    /**
     * Recovery handler for ANTLR-raised parse errors. If the error is
     * recoverable (concrete-token syntax error), advance cursor past
     * the offending source line and drop the cache; otherwise mark the
     * stream done. Mirrors the lex-error recovery handled in [next].
     */
    private fun tryRecoverFromAntlrError(err: ParseException) {
        if (recoverable(err) && err.line > 0) {
            val oldCursor = cursor
            advanceCursorPastLine(err.line)
            if (cursor == oldCursor || cursor >= source.length) {
                // No progress, or we ran off the end. Either way there's
                // nothing more to lex — surface the error as terminal so
                // the consumer's `pendingError` path fires (and exit goes
                // to 2, not 1).
                done = true
            } else {
                cachedLex = null
            }
        } else {
            done = true
        }
    }

    /**
     * Whether a [ParseException] is recoverable — i.e. the stream should
     * skip past the offending source line and try the next statement.
     * Concrete-token syntax errors (`offendingToken` set) recover; the
     * unterminated-construct family (`unexpected EOF…`, etc.) does not,
     * because by nature it swallowed the rest of the input.
     */
    private fun recoverable(e: ParseException): Boolean {
        if (abortOnSyntaxError) return false
        if (e.offendingToken == null) return false
        val msg = e.message ?: return false
        if (msg.contains("unexpected EOF") || msg.contains("unexpected end of file")) return false
        // Top-level reserved-word-out-of-context errors are not recoverable:
        // bash stops the script entirely. Compare:
        //   `echo a\ndo echo b\ndone`           → bash stops at `do` (exit 2)
        //   `test=(first & second)\necho $?`    → bash continues, `echo` runs
        // The difference is that `do`/`done`/`then`/`fi`/`else`/`elif`/`esac`/
        // `}` raised as the offending token means a reserved word appeared at
        // the start of a statement where no enclosing compound is open — a
        // structural failure that can't be papered over by jumping to the
        // next newline (we'd just hit the matching reserved word and error
        // again). Array-literal/redirect-target/etc. errors carry an
        // operator (`&`, `|`, etc.) as the offending token and stay
        // recoverable.
        val offText = e.offendingToken
        return offText !in setOf("do", "done", "then", "fi", "else", "elif", "esac", "}")
    }

    private fun advanceCursorAndDropCache() {
        val c = cachedLex ?: return
        var maxEnd = 0
        val idx = c.stream.index()
        for (i in 0 until idx) {
            val tok = c.stream.get(i) as? com.accucodeai.kash.parser.antlr.KashAntlrToken ?: continue
            if (tok.sourceEnd > maxEnd) maxEnd = tok.sourceEnd
        }
        val oldCursor = cursor
        // sourceEnd is in local-substring coords (the lex ran on
        // source.substring(cursor)); translate to global by adding
        // cursor. If max came up 0 (no positioned tokens), advance to
        // end-of-source so we don't infinite-loop.
        cursor = if (maxEnd > 0) (cursor + maxEnd).coerceAtMost(source.length) else source.length
        for (i in oldCursor until cursor) if (source[i] == '\n') line++
        cachedLex = null
    }
}

/** Build a [StatementStream.CachedLex] over [source] with the given
 *  POSIX mode and starting line. Truncates partial trailing tokens
 *  past a mid-stream lex sentinel so the parser doesn't try to run a
 *  half-formed command. */
private fun buildCachedLex(
    source: String,
    aliasResolver: AliasResolver,
    posixMode: Boolean,
    startLine: Int,
    extglobEnabled: Boolean,
    aliasVersion: Long,
): StatementStream.CachedLex {
    val lexer = Lexer(source, aliasResolver, posixMode, startLine = startLine, extglobEnabled = extglobEnabled)
    val tokenized = lexer.tokenize()
    val warnings = lexer.warnings.map { ParseWarning(it.line, it.message, it.suppressLinePrefix) }
    val pendingLexError: ParseException? =
        (tokenized.lastOrNull() as? Token.LexError)?.let {
            ParseException(it.message, line = it.line, offendingToken = it.offendingToken)
        }
    val rawTokens: List<Token> =
        if (pendingLexError != null) {
            val withoutSentinel = tokenized.dropLast(1)
            var cut = withoutSentinel.size
            while (cut > 0) {
                val t = withoutSentinel[cut - 1]
                val isSep =
                    t is Token.Newline ||
                        (
                            t is Token.Operator &&
                                (t.op == ";" || t.op == "&" || t.op == "&&" || t.op == "||" || t.op == "|")
                        )
                if (isSep) break
                cut--
            }
            withoutSentinel.subList(0, cut)
        } else {
            tokenized
        }
    val tokenSource = kashTokenSourceFrom(rawTokens, lexer.tokenEndPositions())
    val stream = CommonTokenStream(tokenSource)
    val parser = AntlrKashParser(stream).also { configureForFailFast(it) }
    val builder = AstBuilder(aliasResolver, source)
    return StatementStream.CachedLex(
        parser,
        stream,
        builder,
        rawTokens,
        pendingLexError,
        warnings,
        posixMode,
        extglobEnabled,
        aliasVersion,
    )
}

private fun parseStatementStream(
    source: String,
    aliasResolver: AliasResolver,
    posixMode: Boolean,
    startLine: Int = 1,
    extglobEnabled: Boolean = true,
): Triple<List<Statement>, ParseException?, List<ParseWarning>> {
    val lexer = Lexer(source, aliasResolver, posixMode, startLine = startLine, extglobEnabled = extglobEnabled)
    val tokenized = lexer.tokenize()
    val lexerWarnings = lexer.warnings.map { ParseWarning(it.line, it.message, it.suppressLinePrefix) }
    // The lexer seals a [Token.LexError] sentinel into its output when it
    // hit an unterminated construct mid-stream — POSIX shell recovery
    // semantics: tokens emitted *before* the failure represent commands
    // that already lexed cleanly and would have executed. Strip the
    // sentinel from the token stream we hand to ANTLR and remember it
    // as the post-loop parse error so we surface the same diagnostic
    // + exit-2 behavior bash does.
    val pendingLexError: ParseException? =
        (tokenized.lastOrNull() as? Token.LexError)?.let {
            ParseException(it.message, line = it.line, offendingToken = it.offendingToken)
        }
    val rawTokens: List<Token> =
        if (pendingLexError != null) {
            // Bash discards the *failing command*, not just the failing
            // token. When the lex error happens mid-statement (e.g.
            // unterminated `${...}` inside an `echo` argument), any tokens
            // we already emitted for that statement are partial — running
            // them would execute `echo` with no args and print a stray
            // newline before the parse-error diagnostic. Truncate back to
            // the last unambiguous statement separator (NL / `;` / `&`)
            // so the partial command is dropped, matching bash.
            val withoutSentinel = tokenized.dropLast(1)
            var cut = withoutSentinel.size
            while (cut > 0) {
                val t = withoutSentinel[cut - 1]
                val isSep =
                    t is Token.Newline ||
                        (
                            t is Token.Operator &&
                                (t.op == ";" || t.op == "&" || t.op == "&&" || t.op == "||" || t.op == "|")
                        )
                if (isSep) break
                cut--
            }
            withoutSentinel.subList(0, cut)
        } else {
            tokenized
        }
    val tokenSource = kashTokenSourceFrom(rawTokens, lexer.tokenEndPositions())
    val stream = CommonTokenStream(tokenSource)
    val parser = AntlrKashParser(stream).also { configureForFailFast(it) }
    val builder = AstBuilder(aliasResolver, source)
    val statements = mutableListOf<Statement>()

    // Token types that act as statement separators. NL (newline), OP_SEMI
    // (;), and OP_AMP (&) are the only ones; & also marks the *previous*
    // statement as background.
    fun isSeparator(t: Int): Boolean =
        t == AntlrKashParser.Tokens.NL ||
            t == AntlrKashParser.Tokens.OP_SEMI ||
            t == AntlrKashParser.Tokens.OP_AMP

    while (true) {
        // Skip leading/inter-statement separators. We don't care about
        // their identity here — only the separator immediately following
        // a parsed statement matters (it's what tells us "background").
        while (isSeparator(stream.LA(1))) stream.consume()
        if (stream.LA(1) == AntlrKashParser.Tokens.EOF) break

        val markBeforeStatement = stream.index()
        val stmtCtx: AntlrKashParser.StatementContext =
            try {
                // Per-statement two-stage parse. The helper restores SLL in
                // a finally so the next iteration starts back on the fast
                // path; the order parser.reset()-then-seek inside the helper
                // matters because parser.reset itself seeks the stream
                // to 0 (which would otherwise undo the rewind).
                twoStageParseFrom(parser, stream, markBeforeStatement) {
                    parser.statement()
                }
            } catch (e: ParseCancellationException) {
                return Triple(statements.toList(), classifyStreamError(e, rawTokens), lexerWarnings)
            } catch (e: RecognitionException) {
                return Triple(statements.toList(), classifyStreamError(e, rawTokens), lexerWarnings)
            } catch (e: AntlrSyntaxException) {
                return Triple(statements.toList(), classifyStreamError(e, rawTokens), lexerWarnings)
            }
        // Defense against an ANTLR rule that returns without consuming any
        // tokens (would otherwise infinite-loop). Shouldn't happen for the
        // `statement` rule which always requires at least one pipeline, but
        // guard explicitly so a grammar regression can't hang the runtime.
        if (stream.index() == markBeforeStatement) {
            return Triple(
                statements.toList(),
                ParseException(
                    "internal: parser made no progress at token ${stream.LA(1)}",
                    line = 0,
                ),
                lexerWarnings,
            )
        }
        // Look ahead at the trailing separator — `&` makes the parsed
        // statement async. Skip the separator after deciding.
        val bg = stream.LA(1) == AntlrKashParser.Tokens.OP_AMP
        // AstBuilder may throw ParseException for structurally-valid trees
        // that violate POSIX-level rules (e.g. unparenthesized `esac` as a
        // case pattern, rule 4). Caught here so callers see a parse error
        // alongside any earlier statements, rather than an uncaught throw.
        try {
            statements += builder.singleStatement(stmtCtx, bg)
        } catch (e: ParseException) {
            return Triple(statements.toList(), e, lexerWarnings)
        }
    }
    return Triple(statements.toList(), pendingLexError, lexerWarnings)
}

private fun classifyStreamError(
    e: Throwable,
    sourceTokens: List<Token>,
): ParseException {
    val diag = e.toAntlrDiagnostic()
    val offendingIsEof =
        diag.offendingText == "<EOF>" ||
            diag.offendingText == "EOF" ||
            diag.offendingText.isNullOrBlank()
    // Bash's EOF-in-unclosed-compound diagnostic names the *opener* that's
    // waiting for closure, and gives its line:
    //   "syntax error: unexpected end of file from `{' command on line N"
    // Applies when EOF arrives mid-compound (group `{`, subshell `(`,
    // `if`/`for`/`while`/`until`/`select`/`case`/`[[`). We only emit this
    // shape when the offending token is EOF — otherwise the structural
    // scan below still names the offending mid-stream token.
    val unclosed = if (offendingIsEof) findUnclosedOpener(sourceTokens) else null
    if (unclosed != null) {
        // Subshell `(` keeps the legacy "looking for matching" phrasing —
        // bash uses that wording specifically for parenthesized groups.
        // Everything else uses the "from <opener> command on line N" shape.
        val msg =
            if (unclosed.text == "(") {
                "unexpected EOF while looking for matching `)'"
            } else {
                "syntax error: unexpected end of file from `${unclosed.text}' command on line ${unclosed.line}"
            }
        return ParseException(
            msg,
            line = diag.line,
            column = diag.column,
            offendingToken = null,
        )
    }
    // Pre-existing fallback for unbalanced `(` when the structural scan
    // above couldn't pair it (e.g. mid-expression). Kept for safety.
    val parenDepth =
        sourceTokens.sumOf {
            when (it) {
                is Token.Operator if it.op == "(" -> 1
                is Token.Operator if it.op == ")" -> -1
                else -> 0
            }
        }
    if (parenDepth > 0) {
        return ParseException(
            "unexpected EOF while looking for matching `)'",
            line = diag.line,
            column = diag.column,
            offendingToken = null,
        )
    }
    // Prefer a structural mismatch over ANTLR's rewound offendingToken — see
    // [findOffendingByStructure] for the rationale.
    val structural = findOffendingByStructure(sourceTokens)
    val displayTok = structural?.text ?: displayToken(diag.offendingText)
    val line = structural?.line ?: diag.line
    val col = structural?.col ?: diag.column
    val msg =
        if (displayTok != null) {
            "syntax error near unexpected token `$displayTok'"
        } else {
            e.message ?: "parse error"
        }
    return ParseException(msg, line, col, displayTok)
}
