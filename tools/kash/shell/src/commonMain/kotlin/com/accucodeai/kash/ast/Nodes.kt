package com.accucodeai.kash.ast

import kotlinx.serialization.Serializable

/**
 * Shell AST — an independent sealed hierarchy modeling shell constructs.
 *
 * Pipeline: Source -> Lexer -> Parser -> Node -> Interpreter -> ExecResult
 */

public sealed interface Node {
    public val line: Int
}

@Serializable
public data class Script(
    val statements: List<Statement>,
    override val line: Int = 1,
) : Node

@Serializable
public data class Statement(
    val pipelines: List<Pipeline>,
    /** Length = pipelines.size - 1. */
    val operators: List<Connector>,
    val background: Boolean = false,
    override val line: Int = 1,
) : Node

@Serializable
public enum class Connector { AND, OR }

@Serializable
public data class Pipeline(
    val commands: List<Command>,
    /** `!` prefix: invert final exit status. */
    val negated: Boolean = false,
    /**
     * `time` reserved-word prefix. `null` = untimed; otherwise selects the
     * output format. Bash emits timing on stderr after the pipeline finishes.
     * When set with empty [commands], it's the bare `time` form (`time;`)
     * which still prints timing (all zeros for an empty body).
     */
    val timed: TimeSpec? = null,
    /**
     * Per-separator flag: `pipeStderr[i] == true` means the separator
     * between `commands[i]` and `commands[i+1]` was `|&` (bash's
     * stdout+stderr pipe), equivalent to `commands[i] 2>&1 | commands[i+1]`.
     * Length is `commands.size - 1` (or empty when the field defaults
     * to "all plain `|`"); callers should `getOrElse(idx) { false }`.
     */
    val pipeStderr: List<Boolean> = emptyList(),
    override val line: Int = 1,
) : Node

@Serializable
public enum class TimeSpec {
    /** Default — format follows `$TIMEFORMAT` env var (or a built-in default). */
    DEFAULT,

    /** `time -p` — POSIX-mandated `real %f / user %f / sys %f` format. */
    POSIX,
}

@Serializable
public sealed interface Command : Node {
    public val redirections: List<Redirection>
}

/**
 * Simple command: optional assignments + name + args + redirections.
 *
 * Inline assignments (`FOO=bar cmd …`) apply for the duration of the command;
 * a [SimpleCommand] with [name] == null and non-empty [assignments] is a bare
 * assignment that mutates the shell's environment.
 *
 * [argAssignments] carries `NAME=value` / `NAME=(...)` tokens that appeared
 * *after* the command name and tokenized as assignments because the command
 * is an assignment-aware builtin (`declare`, `typeset`, `local`, `readonly`,
 * `export`). Their order relative to plain args is not preserved — bash
 * processes them all together regardless. Empty for ordinary commands.
 */
@Serializable
public data class SimpleCommand(
    val name: Word?,
    val args: List<Word>,
    val assignments: List<InlineAssignment> = emptyList(),
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
    val argAssignments: List<InlineAssignment> = emptyList(),
    /**
     * Verbatim source slice for this simple command, captured by the
     * parser from the original script. Populated when the parser had
     * the source available; null for ASTs constructed programmatically
     * (snapshots, tests, brace-expansion expansions).
     *
     * Backs bash's dynamic `$BASH_COMMAND` variable — set in
     * [com.accucodeai.kash.interpreter.InterpreterPipelines] right
     * before the command dispatches.
     */
    val srcText: String? = null,
    /**
     * Indices into [args] of words that are textual fallbacks for an
     * entry in [argAssignments] — i.e. the lexer saw `NAME=value` after
     * the command word and recorded it both as a structured argAssignment
     * AND as a textual word so non-assignment-aware commands still see
     * the operand. The interpreter uses these directly; the AST printer
     * (`type fn`, `declare -f`) skips them to avoid printing the same
     * `name=value` twice.
     */
    val argFallbackIndices: Set<Int> = emptySet(),
) : Command

@Serializable
public sealed interface InlineAssignment {
    public val name: String
    public val append: Boolean

    /** `NAME=value` / `NAME+=value` — scalar form. */
    @Serializable
    public data class Scalar(
        override val name: String,
        val value: Word,
        override val append: Boolean = false,
    ) : InlineAssignment

    /** `NAME=(a b c)` / `NAME+=(a b c)` — indexed-array literal. Elements are
     *  ordered words; subscript-tagged elements `[k]=v` not yet supported. */
    @Serializable
    public data class Array(
        override val name: String,
        val elements: List<Word>,
        override val append: Boolean = false,
    ) : InlineAssignment

    /** `NAME[sub]=value` / `NAME[sub]+=value` — single-element write.
     *
     * [listAttempt] is true when the source was `NAME[sub]=(...)`. The
     * runtime emits bash's "NAME[sub]: cannot assign list to array
     * member" diagnostic and skips the actual write. */
    @Serializable
    public data class Indexed(
        override val name: String,
        val subscript: Word,
        val value: Word,
        override val append: Boolean = false,
        val listAttempt: Boolean = false,
    ) : InlineAssignment
}

// ----- Compound commands -----

@Serializable
public sealed interface CompoundCommand : Command

@Serializable
public data class IfCommand(
    val clauses: List<IfClause>,
    /** `else` body, or null if none. */
    val elseBody: List<Statement>?,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : CompoundCommand

@Serializable
public data class IfClause(
    val condition: List<Statement>,
    val body: List<Statement>,
)

@Serializable
public data class ForCommand(
    val variable: String,
    /** Words to iterate; null = `"$@"`. */
    val words: List<Word>?,
    val body: List<Statement>,
    /** `true` for the `select` variant: print a menu, read a choice from stdin
     *  on each iteration, loop until EOF or `break`. `false` is plain `for`. */
    val isSelect: Boolean = false,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : CompoundCommand

/** C-style `for ((init; cond; update)); do ... done`. Each expression is raw arithmetic text; empty string means "absent" (cond defaults to true). */
@Serializable
public data class ArithForCommand(
    val init: String,
    val cond: String,
    val update: String,
    val body: List<Statement>,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : CompoundCommand

/** Standalone `((expression))` arithmetic command. Exit is 0 if value is non-zero, 1 if zero. */
@Serializable
public data class ArithCommand(
    val rawText: String,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : CompoundCommand

@Serializable
public data class WhileCommand(
    val condition: List<Statement>,
    val body: List<Statement>,
    /** When true, loop while condition is false (until-loop). */
    val until: Boolean = false,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : CompoundCommand

@Serializable
public data class CaseCommand(
    val word: Word,
    val items: List<CaseItem>,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : CompoundCommand

@Serializable
public data class CaseItem(
    val patterns: List<Word>,
    val body: List<Statement>,
    val terminator: CaseTerminator = CaseTerminator.BREAK,
)

@Serializable
public enum class CaseTerminator { BREAK, FALLTHROUGH, CONTINUE_TEST }

@Serializable
public data class Subshell(
    val body: List<Statement>,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : CompoundCommand

@Serializable
public data class Group(
    val body: List<Statement>,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : CompoundCommand

/**
 * `coproc [NAME] body` — bash coprocess. Runs [body] asynchronously with
 * its stdin/stdout connected to a pair of pipes whose parent-side fds are
 * exposed via `${name}[0]` (read from child) / `${name}[1]` (write to
 * child) and whose pid is exposed via `${name}_PID`. Defaults to NAME =
 * "COPROC" when omitted.
 */
@Serializable
public data class CoprocCommand(
    val name: String?,
    val body: Command,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : CompoundCommand

/**
 * `[[ EXPR ]]` — bash conditional. The expression tree mirrors the source.
 * Unlike `[ … ]`, words inside `[[ … ]]` are not subject to word splitting
 * and `==`/`!=` perform glob pattern matching against the right-hand side.
 */
@Serializable
public data class CondCommand(
    val expression: CondExpr,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : CompoundCommand

@Serializable
public sealed interface CondExpr {
    /** Lazy AND. */
    @Serializable
    public data class And(
        val left: CondExpr,
        val right: CondExpr,
    ) : CondExpr

    /** Lazy OR. */
    @Serializable
    public data class Or(
        val left: CondExpr,
        val right: CondExpr,
    ) : CondExpr

    /** Logical NOT. */
    @Serializable
    public data class Not(
        val expr: CondExpr,
    ) : CondExpr

    /** Unary operator (`-f`, `-z`, …). */
    @Serializable
    public data class Unary(
        val op: String,
        val operand: Word,
    ) : CondExpr

    /** Binary operator (`=`, `==`, `!=`, `<`, `>`, `=~`, `-eq`, …). */
    @Serializable
    public data class Binary(
        val op: String,
        val left: Word,
        val right: Word,
    ) : CondExpr

    /** Lone word: true iff non-empty after expansion. */
    @Serializable
    public data class Lone(
        val word: Word,
    ) : CondExpr
}

// ----- Functions -----

@Serializable
public data class FunctionDef(
    val name: String,
    val body: CompoundCommand,
    override val redirections: List<Redirection> = emptyList(),
    override val line: Int = 1,
) : Command

// ----- Redirections -----

@Serializable
public data class Redirection(
    val fd: Int?,
    val operator: RedirOp,
    val target: RedirTarget,
    val line: Int = 1,
    /** POSIX move-fd: `<&N-` / `>&N-` dup then close the source fd. */
    val closeAfter: Boolean = false,
    /** Bash `{NAME}>file` / `{NAME}<file` form: runtime allocates a fresh
     *  high fd, opens the file there, assigns the fd number to shell var
     *  `$NAME`. Mutually exclusive with [fd]. */
    val fdVar: String? = null,
)

@Serializable
public enum class RedirOp {
    INPUT, // <
    OUTPUT, // >
    APPEND, // >>
    CLOBBER, // >|
    READ_WRITE, // <>
    DUP_OUT, // >&
    DUP_IN, // <&
    OUT_AND_ERR, // &>
    OUT_AND_ERR_APPEND, // &>>
    HERESTRING, // <<<
    HEREDOC, // <<
    HEREDOC_STRIP, // <<-
}

@Serializable
public sealed interface RedirTarget {
    /** File-target: word is expanded then opened. */
    @Serializable
    public data class File(
        val word: Word,
    ) : RedirTarget

    /** Duplication target: `>&N` / `<&N`. -1 means close (e.g. `>&-`). */
    @Serializable
    public data class Fd(
        val fd: Int,
    ) : RedirTarget

    /** Heredoc body — already captured by the lexer. */
    @Serializable
    public data class HereDoc(
        val body: Word,
        val stripTabs: Boolean,
        /** If the delimiter was quoted, body is not parameter-expanded. */
        val quoted: Boolean,
        /** Original delimiter ("END", "EOF", etc.) preserved from source so
         *  AstPrinter can deparse `<<END … END` instead of synthesizing
         *  `_HD0_`. Empty for redirections that don't originate from a
         *  source heredoc (e.g. test-constructed AST). */
        val delim: String = "",
    ) : RedirTarget
}

// ----- Words -----

@Serializable
public data class Word(
    val parts: List<WordPart>,
    override val line: Int = 1,
) : Node {
    public companion object {
        public fun literal(
            value: String,
            line: Int = 1,
        ): Word = Word(listOf(WordPart.Literal(value)), line)
    }
}

@Serializable
public sealed interface WordPart {
    @Serializable
    public data class Literal(
        val value: String,
    ) : WordPart

    @Serializable
    public data class SingleQuoted(
        val value: String,
    ) : WordPart

    @Serializable
    public data class DoubleQuoted(
        val parts: List<WordPart>,
    ) : WordPart

    @Serializable
    public data class Escaped(
        val value: String,
    ) : WordPart

    /**
     * `$NAME` / `${NAME}` and the full `${...}` family.
     *
     * - [lengthOf] handles `${#NAME}` (string/positional length).
     * - [op] is null for plain `$NAME`/`${NAME}`. Otherwise it's one of:
     *   `:-`, `-`, `:=`, `=`, `:?`, `?`, `:+`, `+` (defaults/alternates),
     *   `#`, `##`, `%`, `%%` (prefix/suffix strip),
     *   `/`, `//`, `/#`, `/%` (pattern substitution),
     *   `^`, `^^`, `,`, `,,` (case modification),
     *   `:` (substring — [arg1]=offset, [arg2]=length-optional).
     * - [arg1]/[arg2] hold the operand words (or null when absent).
     */
    @Serializable
    public data class ParameterExpansion(
        val parameter: String,
        val lengthOf: Boolean = false,
        val op: String? = null,
        val arg1: Word? = null,
        val arg2: Word? = null,
        /**
         * Raw operand source for default-value ops (`-`/`+`/`=`/`?` and
         * their colon-prefixed forms). When non-null, the expander
         * re-lexes this text with [outerDqAtLex] restored so the
         * operand's `'` / `\X` quoting rules match the originating
         * context. Mirrors bash's `brace-body capture` +
         * `default-value RHS expander` deferred-parse design
         *. Mutually exclusive with [arg1].
         */
        val rawArg1: String? = null,
        /** Outer dq depth at lex time. Carried to the re-lex so the `'`/`\X` rules match context. */
        val outerDqAtLex: Int = 0,
        /** Raw text inside `[...]` for `${name[sub]}`. Null = no subscript. */
        val subscript: String? = null,
        /**
         * Unexpanded subscript source, captured before parameter/command/
         * arith expansion rewrote [subscript]. Bash echoes this verbatim in
         * the "bad array subscript" diagnostic when a subscript that expands
         * to empty is used on an associative array — e.g. an unset `$unset`
         * key produces `$unset: bad array subscript` (with the original
         * dollar text, not the empty expansion). Null when no expansion was
         * applied (then [subscript] already holds the literal text).
         */
        val rawSubscript: String? = null,
        /** `${!name}` indirect — look up `parameter` to get the real name, then expand that. */
        val indirect: Boolean = false,
        /** `${prefix*}` / `${prefix@}` — variable-name globbing. '*' joins on IFS, '@' splits. */
        val nameGlob: Char? = null,
        /**
         * True iff this came from the explicit `${NAME}` syntax. False for the
         * bare `$NAME` form. Brace expansion text-merges trailing identifier
         * chars into a bare `$NAME` (bash: `$var{x,y}` → `$varx` `$vary`) but
         * leaves a braced `${NAME}` delimited.
         */
        val braced: Boolean = false,
    ) : WordPart

    /**
     * `$(...)` or backtick substitution. Carries the **raw body text** and
     * the line where the opening `$(` / `` ` `` appeared in the outer source.
     * The body is parsed at expansion time (see
     * [com.accucodeai.kash.interpreter.evalCommandSubstitution]) so the
     * interpreter's runtime alias / function tables — populated by any
     * earlier statement that ran before this expansion fires — are visible
     * to the inner parse. The body is stored as a raw string at outer-parse
     * time and re-parsed at expansion time.
     */
    @Serializable
    public data class CommandSubstitution(
        val rawText: String,
        val openLine: Int = 1,
    ) : WordPart

    /**
     * `<(...)` / `>(...)` process substitution. [direction] is `'<'` (parent
     * reads child's stdout) or `'>'` (parent writes to child's stdin). The
     * interpreter resolves this to a `/dev/fd/N` path string at expansion
     * time, replacing the part with an [ExpandedText]. Body is stored as
     * raw text and parsed at expansion time — same rationale as
     * [CommandSubstitution].
     */
    @Serializable
    public data class ProcessSubstitution(
        val direction: Char,
        val rawText: String,
        val openLine: Int = 1,
    ) : WordPart

    /** `$((...))` arithmetic. The interpreter evaluates [rawText] at expansion time. */
    @Serializable
    public data class ArithmeticExpansion(
        val rawText: String,
    ) : WordPart

    /**
     * Runtime-only marker emitted by the interpreter's pre-pass when it
     * resolves a [CommandSubstitution] or [ArithmeticExpansion] to a string.
     * Distinct from [Literal] so the expander can treat its characters as
     * eligible for IFS word splitting — POSIX §2.6.5 splits results of
     * expansions/substitutions, not source literals. Parsers never produce
     * this; only [Interpreter.preprocessWord] does.
     */
    @Serializable
    public data class ExpandedText(
        val value: String,
        /**
         * Optional source-form rendering of the part that was pre-resolved
         * (`$(cmd)` / `$((expr))` / `<(cmd)`). Used only by diagnostics that
         * cite the source expression rather than its expanded value — e.g.
         * `${@:1:$(($# - 2))}` with `$# = 1` produces `-1: substring
         * expression < 0` in bash's literal-int form but
         * `$(($# - 2)): substring expression < 0` for the arith form.
         */
        val rawSource: String? = null,
    ) : WordPart
}
