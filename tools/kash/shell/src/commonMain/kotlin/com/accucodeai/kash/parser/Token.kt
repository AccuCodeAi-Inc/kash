package com.accucodeai.kash.parser

internal sealed interface Token {
    val line: Int

    /** A shell word. */
    data class WordTok(
        val parts: List<WordChunk>,
        override val line: Int,
    ) : Token

    /** Bare assignment token: `NAME=value-word` or `NAME+=value-word` at command-position. */
    data class AssignmentTok(
        val name: String,
        val value: List<WordChunk>,
        val append: Boolean,
        override val line: Int,
    ) : Token

    /** `NAME=(a b c)` / `NAME+=(a b c)` — indexed-array literal assignment. */
    data class ArrayAssignTok(
        val name: String,
        val elements: List<List<WordChunk>>,
        val append: Boolean,
        override val line: Int,
    ) : Token

    /** `NAME[sub]=value` / `NAME[sub]+=value` — single-element array write.
     *
     * [listAttempt] is true when the source was `NAME[sub]=(...)`. Bash
     * parses this OK but emits a runtime "cannot assign list to array
     * member" error; lexer captures so the runtime can fire the right
     * diagnostic instead of an obscure parse error. */
    data class IndexedAssignTok(
        val name: String,
        val subscript: List<WordChunk>,
        val value: List<WordChunk>,
        val append: Boolean,
        override val line: Int,
        val listAttempt: Boolean = false,
    ) : Token

    /** Reserved word (keyword) — emitted only at command-position. */
    data class Keyword(
        val word: String,
        override val line: Int,
    ) : Token

    /** A redirection operator with an optional leading fd: `2>`, `>>`, `&>`, etc.
     *
     * [closeAfter] is true for the POSIX move-fd forms `<&N-` / `>&N-`, where
     * the source fd is dup'd onto the target and then closed (`dup2(src,dst);
     * close(src)`).
     *
     * [fdVar] is non-null for the bash dynamic-fd-allocation form
     * `{NAME}>file` / `{NAME}<file`: the runtime allocates a fresh high fd,
     * opens the file there, and assigns the resulting fd number to shell
     * variable `$NAME`. Mutually exclusive with [fd] (the lexer enforces).
     */
    data class RedirOpTok(
        val op: String,
        val fd: Int?,
        override val line: Int,
        val closeAfter: Boolean = false,
        val fdVar: String? = null,
    ) : Token

    /** Heredoc body captured by the lexer between newline and delimiter. */
    data class HereDocBodyTok(
        val parts: List<WordChunk>,
        val stripTabs: Boolean,
        val quoted: Boolean,
        /** Original delimiter token as it appeared in source (`END`,
         *  `'EOF'`, etc.). Bash deparsers (e.g. `declare -f`) print the
         *  heredoc back with this same delimiter; without preserving it,
         *  a function-dump shows `<<_HD0_` synthetic names instead. */
        val delim: String,
        override val line: Int,
    ) : Token

    /** Control operator. */
    data class Operator(
        val op: String,
        override val line: Int,
    ) : Token

    /** `((expression))` arithmetic command read at command-position. Raw text excludes the surrounding `((` `))`. */
    data class ArithCmdTok(
        val rawText: String,
        override val line: Int,
    ) : Token

    data class Newline(
        override val line: Int,
    ) : Token

    data class Eof(
        override val line: Int,
    ) : Token

    /**
     * Sentinel emitted by [Lexer.tokenize] when an unterminated construct
     * (e.g. `${...`, `'`, `"`) was hit mid-stream. All tokens emitted
     * before this one are valid and complete; the rest of the source was
     * abandoned. This mirrors POSIX shell recovery semantics: a parse
     * error discards the current command but already-executed commands
     * are preserved. The parser's per-statement loop reads up
     * to this token, then surfaces [message] / [line] as the script's
     * parse-error diagnostic.
     */
    data class LexError(
        val message: String,
        override val line: Int,
        /** Token whose appearance caused the syntax error (e.g. `&` inside
         *  an array literal). Used to enable the bash second-line source-echo
         *  in [emitShellParseError]; null for unterminated-construct cases
         *  where bash doesn't echo the source line. */
        val offendingToken: String? = null,
    ) : Token
}

internal sealed interface WordChunk {
    data class Literal(
        val value: String,
    ) : WordChunk

    data class SingleQuoted(
        val value: String,
    ) : WordChunk

    data class DoubleQuoted(
        val parts: List<WordChunk>,
    ) : WordChunk

    data class Escaped(
        val value: String,
    ) : WordChunk

    data class ParameterExpansion(
        val parameter: String,
        val lengthOf: Boolean = false,
        val op: String? = null,
        val arg1: List<WordChunk>? = null,
        val arg2: List<WordChunk>? = null,
        /**
         * Raw source text of the operand for default-value ops
         * (`-`/`+`/`=`/`?` and `:-`/`:+`/`:=`/`:?`). When non-null, the
         * expander re-lexes this text at expansion time with the
         * outer dq depth restored, matching bash's
         * `brace-body capture` + `default-value RHS expander`
         * deferred-parse design. Mutually
         * exclusive with [arg1] — exactly one is set per op family.
         */
        val rawArg1: String? = null,
        /** Outer dq depth at lex time. Carried to the re-lex so `'`/`\X` rules match the originating context. */
        val outerDqAtLex: Int = 0,
        /** Raw text inside `[...]` for `${name[sub]}`. Null = no subscript. */
        val subscript: String? = null,
        /** `${!name}` indirect expansion — look up `parameter` to get the real name, then expand that. */
        val indirect: Boolean = false,
        /** `${prefix*}` / `${prefix@}` (and `${!prefix*}` / `${!prefix@}`) — variable-name globbing. '*' joins, '@' splits. */
        val nameGlob: Char? = null,
        /**
         * True if this came from the `${NAME}` syntax (with explicit braces).
         * False for bare `$NAME`. Brace expansion uses this to decide whether
         * to text-merge trailing identifier chars into the variable name —
         * `$var{x,y}` → `$varx` `$vary` (mergeable); `${var}{x,y}` → `bazx`
         * `bazy` (delimited, no merge).
         */
        val braced: Boolean = false,
    ) : WordChunk

    /** `$(...)` or backtick command substitution. Inner text is parsed later by the parser. */
    data class CommandSubstitution(
        val rawText: String,
        /**
         * 1-based source line where the opening `$(` / backtick appeared.
         * Used by [com.accucodeai.kash.parser.WordConversion] to map an
         * inner [ParseException]'s `line` (which counts from 1 inside the
         * captured text) back to the corresponding outer-script line, so
         * diagnostics like `bash: -c: line 3:` match bash.
         */
        val openLine: Int = 1,
    ) : WordChunk

    /**
     * `<(...)` (input) or `>(...)` (output) process substitution.
     * [direction] is `'<'` for input (parent reads, child writes) or `'>'` for
     * output (parent writes, child reads). [rawText] is the inner shell
     * source between `(` and the balanced `)`, parsed later via [Parser].
     */
    data class ProcessSubstitution(
        val rawText: String,
        val direction: Char,
    ) : WordChunk

    /** `$((...))` arithmetic expansion. Raw expression text — evaluated lazily. */
    data class ArithmeticExpansion(
        val rawText: String,
    ) : WordChunk
}

internal val RESERVED_WORDS =
    setOf(
        "if",
        "then",
        "elif",
        "else",
        "fi",
        "for",
        "select",
        "in",
        "do",
        "done",
        "while",
        "until",
        "case",
        "esac",
        "function",
        "{",
        "}",
        "!",
        "[[",
        "]]",
        "coproc",
        "time",
    )

/**
 * Bash "assignment builtins" — past the command-name slot, their operands
 * are tokenized as `NAME=value` / `NAME=(...)` assignments instead of plain
 * words. POSIX §2.9.1.4 + bash builtins(1). The set matches bash's
 * `assignment_builtin` flag on the builtin table.
 */
internal val ASSIGNMENT_BUILTIN_NAMES =
    setOf(
        "declare",
        "typeset",
        "local",
        "readonly",
        "export",
    )

/**
 * Builtins whose argument words get the array-literal `=(…)` parsing
 * (assignment-OK parser state in bash; see ) but are *not* declaration
 * utilities — their arguments don't become inline assignments. `eval` and
 * `let` enable this so that `eval NAME=(…)` tokenizes as a single word
 * (which `eval` then re-parses internally) rather than failing on the
 * `(` metacharacter.
 */
internal val ASSIGN_OK_BUILTIN_NAMES =
    setOf(
        "eval",
        "let",
    )

/**
 * Keywords whose emission *keeps* the lexer at command-start (the very next
 * token is still a fresh simple command). Anything else flips command-start
 * back off. Inline assignments (`FOO=1 cmd`) also preserve command-start —
 * those are handled by token-type checks in [Lexer.tokenize], not this set.
 */
internal val KEYWORDS_KEEPING_CMD_START =
    setOf(
        "if",
        "then",
        "elif",
        "else",
        "while",
        "until",
        "for",
        "select",
        "do",
        "case",
        "{",
        "(",
        "!",
        ";",
        "[[",
        "coproc",
        "time",
    )
