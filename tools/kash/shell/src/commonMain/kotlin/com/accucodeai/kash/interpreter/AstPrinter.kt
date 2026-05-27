package com.accucodeai.kash.interpreter

import com.accucodeai.kash.ast.ArithCommand
import com.accucodeai.kash.ast.ArithForCommand
import com.accucodeai.kash.ast.CaseCommand
import com.accucodeai.kash.ast.CaseTerminator
import com.accucodeai.kash.ast.Command
import com.accucodeai.kash.ast.CompoundCommand
import com.accucodeai.kash.ast.CondCommand
import com.accucodeai.kash.ast.CondExpr
import com.accucodeai.kash.ast.Connector
import com.accucodeai.kash.ast.CoprocCommand
import com.accucodeai.kash.ast.ForCommand
import com.accucodeai.kash.ast.FunctionDef
import com.accucodeai.kash.ast.Group
import com.accucodeai.kash.ast.IfCommand
import com.accucodeai.kash.ast.InlineAssignment
import com.accucodeai.kash.ast.Pipeline
import com.accucodeai.kash.ast.RedirOp
import com.accucodeai.kash.ast.RedirTarget
import com.accucodeai.kash.ast.Redirection
import com.accucodeai.kash.ast.SimpleCommand
import com.accucodeai.kash.ast.Statement
import com.accucodeai.kash.ast.Subshell
import com.accucodeai.kash.ast.WhileCommand
import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart

/**
 * AST → bash-source pretty-printer used by `declare -f NAME` and
 * `type NAME` for shell functions. Matches bash's output convention:
 *
 * ```
 * name ()
 * {
 *     cmd1;
 *     cat <<DELIM
 * heredoc body
 * DELIM
 *     trailing;
 * }
 * ```
 *
 * Indent unit is 4 spaces. Heredocs are emitted INLINE (body + delim on
 * their own lines, no `;` terminator on the heredoc line) so that
 * `eval "$(type funcname | sed 1d)"` round-trips through the parser.
 *
 * Imperfect: alias substitution is permanent (loses original alias name);
 * here-string is reduced to `<<<`-prefixed Word, which is fine; process
 * substitution prints as the literal `<(...)` since the AST has the
 * inner Script which we can't simply re-emit yet.
 *
 * Per instance — call sites construct a fresh printer to keep the
 * heredoc-counter and pending list local.
 */
internal class AstPrinter {
    companion object {
        fun functionBody(fn: FunctionDef): String = AstPrinter().printFunctionBody(fn)

        /**
         * Single-assignment renderer used by xtrace for array/indexed
         * literals (bash emits the source form `metas=(\| \&...)`, NOT the
         * expanded values).
         */
        fun formatAssignment(a: InlineAssignment): String {
            val p = AstPrinter()
            p.printAssignment(a)
            return p.out.toString()
        }
    }

    private val out = StringBuilder()
    private var heredocCounter = 0

    /**
     * Heredoc bodies pending after the current command line. Flushed by
     * [terminateStatement] before printing the statement terminator —
     * bash's format is `cmd <<DELIM\nBODY\nDELIM\n` with no `;` because
     * the closing delimiter line acts as the statement separator.
     */
    private val pendingHeredocs = mutableListOf<PendingHeredoc>()

    private data class PendingHeredoc(
        val delim: String,
        val body: Word,
        val quoted: Boolean,
        val stripTabs: Boolean,
    )

    /**
     * Whether [name] needs the `function` keyword prefix in declare -f
     * output. The POSIX `name() { ... }` form lexes `name=value()` as
     * an assignment, so any name containing `=` must use the keyword
     * form to round-trip. Other non-POSIX-identifier characters (pure
     * digits, `+`, etc.) parse fine in the bare form.
     */
    private fun needsFunctionKeyword(name: String): Boolean = '=' in name

    private fun printFunctionBody(fn: FunctionDef): String {
        // Bash declare -f wraps even subshell function bodies in `{ }`
        // — the subshell shows up INSIDE as `( ... )`. This matches
        // bash always brackets the function body this way.
        // Names that the POSIX `name() { ... }` form can't represent
        // (currently: contains `=`, which would lex as an assignment
        // word in the bare form) get the `function` keyword prefix to
        // round-trip through declare -f. Bash applies the same rule.
        if (needsFunctionKeyword(fn.name)) out.append("function ")
        out.append(fn.name).append(" () \n{ \n")
        printFunctionBodyInterior(fn.body, indent = 1)
        out.append("}")
        // For Group bodies, the parser attaches `f() { ...; } >&2`-style
        // trailing redirections to the Group. They render after the
        // closing `}`. For Subshell bodies, the redirections live on
        // the subshell and were already emitted inline alongside `( )`.
        if (fn.body !is Subshell) appendRedirections(fn.body.redirections)
        appendRedirections(fn.redirections)
        flushHeredocsAfter()
        out.append('\n')
        return out.toString()
    }

    /**
     * Print the interior of a function body's `{ }` wrapper. For
     * subshell-body functions (`f() ( ... )`), bash declare -f emits
     * `{ ( ... ) }` — the subshell is wrapped as a single inline
     * compound statement inside the function brace. Group bodies emit
     * statement-by-statement as usual.
     */
    private fun printFunctionBodyInterior(
        body: CompoundCommand,
        indent: Int,
    ) {
        if (body is Subshell) {
            indent(indent)
            out.append("( ")
            for ((idx, st) in body.body.withIndex()) {
                if (idx > 0) out.append("; ")
                printPipelinesInline(st, indent)
            }
            out.append(" )")
            appendRedirections(body.redirections)
            out.append('\n')
            return
        }
        printGroupContents(body, indent, isFunctionBody = true)
    }

    private fun printGroupContents(
        cmd: CompoundCommand,
        indent: Int,
        isFunctionBody: Boolean = false,
    ) {
        val statements: List<Statement> =
            when (cmd) {
                is Group -> {
                    cmd.body
                }

                is Subshell -> {
                    cmd.body
                }

                else -> {
                    indent(indent)
                    printCommand(cmd as Command, indent)
                    terminateStatement(background = false, isLast = true)
                    return
                }
            }
        var prevBackground = false
        for ((idx, st) in statements.withIndex()) {
            // Bash's `cmd & next-cmd` declare-f form keeps `next-cmd` on
            // the SAME line — `&` itself terminates the prior, no newline.
            // So suppress the leading indent when the previous statement
            // ended with `&`.
            if (!prevBackground) indent(indent)
            // Bash's declare -f omits the trailing `;` on the very last
            // statement of a function body Group (any nesting depth —
            // nested function bodies follow the same rule). Other group
            // contexts (loops/conditionals/plain groups) retain the `;`.
            printStatement(st, indent, isLast = idx == statements.lastIndex && isFunctionBody)
            prevBackground = st.background
        }
        // If the block ended with a background statement, close out the
        // dangling ` & ` with a newline so the caller's `}` lands at
        // column 0 of a fresh line.
        if (prevBackground && out.isNotEmpty() && out.last() != '\n') out.append('\n')
    }

    private fun printStatement(
        st: Statement,
        indent: Int,
        isLast: Boolean = false,
    ) {
        for ((idx, pl) in st.pipelines.withIndex()) {
            if (idx > 0) {
                val op = st.operators[idx - 1]
                out.append(if (op == Connector.AND) " && " else " || ")
            }
            printPipeline(pl, indent)
        }
        terminateStatement(st.background, isLast)
    }

    /**
     * Flush any heredoc bodies queued by the just-printed command, then
     * emit the statement terminator.
     *
     * Bash's `declare -f` convention:
     *  - Internal statements end with `;` (e.g. `echo hi;\n`).
     *  - The LAST statement of a block ends with just `\n` (no `;`).
     *  - Heredocs end with `\n` after their delimiter line (no `;`)
     *    because the delim line itself acts as the terminator. A blank
     *    line follows so the next statement starts cleanly.
     *  - Background statements use ` &` instead of `;`.
     */
    private fun terminateStatement(
        background: Boolean,
        isLast: Boolean,
    ) {
        if (pendingHeredocs.isNotEmpty()) {
            flushHeredocsAfter()
            out.append('\n')
            lastTerminationFlushedHeredoc = true
            return
        }
        // Compound deparse subtle case: a `for/while/if/case ... done|fi|esac`
        // whose body's terminal statement flushed a heredoc emits its
        // closing keyword on a fresh line *after* the blank line from the
        // flush. The blank already separates the compound from the next
        // statement — no trailing `;` is needed, even when this compound
        // isn't the last statement of its enclosing block.
        if (lastTerminationFlushedHeredoc) {
            lastTerminationFlushedHeredoc = false
            out.append('\n')
            return
        }
        if (background) {
            // Bash's declare -f uses ` & ` (space-flanked) so the next
            // statement continues on the same line. If background is the
            // last statement of a block, a trailing newline closes it.
            out.append(" & ")
        } else if (isLast) {
            out.append('\n')
        } else {
            out.append(";\n")
        }
    }

    /**
     * Set by [terminateStatement] when it consumes a pending heredoc batch.
     * Surviving across the inner `printStatement`'s return, the next outer
     * `terminateStatement` reads it to elide the `;` after a compound
     * whose body just emitted heredoc bodies (the blank-line separator
     * already disambiguates).
     */
    private var lastTerminationFlushedHeredoc: Boolean = false

    private fun flushHeredocsAfter() {
        if (pendingHeredocs.isEmpty()) return
        out.append('\n')
        for ((delim, body, quoted, _) in pendingHeredocs) {
            // body is captured by the lexer; for quoted heredocs the
            // body's parts are literal text. For unquoted heredocs the
            // body Word can contain ParameterExpansion etc. — round-trip
            // those back to source form via printWord.
            val bodyStart = out.length
            printWord(body)
            // Ensure body ends with newline before the delim line.
            if (out.length == bodyStart || out.last() != '\n') out.append('\n')
            out.append(delim).append('\n')
        }
        pendingHeredocs.clear()
    }

    private fun printPipeline(
        p: Pipeline,
        indent: Int,
    ) {
        if (p.timed != null) {
            out.append("time")
            if (p.timed == com.accucodeai.kash.ast.TimeSpec.POSIX) out.append(" -p")
            // Bash's declare -f always emits a trailing space after the
            // `time` keyword, even for a bare `time` pipeline with no
            // commands — the line reads `    time ` not `    time`.
            out.append(' ')
        }
        if (p.negated) out.append("! ")
        for ((idx, c) in p.commands.withIndex()) {
            if (idx > 0) out.append(" | ")
            printCommand(c, indent)
        }
    }

    private fun printCommand(
        cmd: Command,
        indent: Int,
    ) {
        when (cmd) {
            is SimpleCommand -> {
                printSimple(cmd)
            }

            is IfCommand -> {
                printIf(cmd, indent)
            }

            is ForCommand -> {
                printFor(cmd, indent)
            }

            is WhileCommand -> {
                printWhile(cmd, indent)
            }

            is CaseCommand -> {
                printCase(cmd, indent)
            }

            is Group -> {
                // Bash declare -f: the LAST statement of any block group
                // (function body OR nested `{ … }`) drops the trailing
                // `;`. Same rule as the function-body Group itself.
                out.append("{ \n")
                for ((idx, st) in cmd.body.withIndex()) {
                    indent(indent + 1)
                    printStatement(st, indent + 1, isLast = idx == cmd.body.lastIndex)
                }
                indent(indent)
                out.append("}")
                appendRedirections(cmd.redirections)
                // Bracket-terminated compounds always get a trailing `;`
                // even when an inner statement flushed heredocs. Clear the
                // flag so the outer terminateStatement doesn't substitute
                // `\n` (that elision is for `done`/`fi`/`esac` only).
                lastTerminationFlushedHeredoc = false
            }

            is Subshell -> {
                // `declare -f` keeps `( ... )` subshells SINGLE-LINE when
                // contents are simple, multi-line when contents are a
                // compound. We print body inline with `; ` separators; the
                // inner compound (Group, if, …) handles its own line breaks.
                //
                // If any body statement queued heredocs, flush them BEFORE
                // emitting `)` so the bodies land between the opener and
                // the closer. The closing `)` follows on its own indented
                // line in that case — bash's deparse convention for
                // `( cat <<EOF ... EOF )`.
                out.append("( ")
                for ((idx, st) in cmd.body.withIndex()) {
                    if (idx > 0) out.append("; ")
                    printPipelinesInline(st, indent)
                }
                if (pendingHeredocs.isNotEmpty()) {
                    // flushHeredocsAfter already terminates with the delim
                    // line + `\n` — no extra blank line needed before `)`,
                    // just a single space.
                    flushHeredocsAfter()
                    out.append(" )")
                } else {
                    out.append(" )")
                }
                appendRedirections(cmd.redirections)
                // See [Group]: bracket-terminated, always wants outer `;`.
                lastTerminationFlushedHeredoc = false
            }

            is FunctionDef -> {
                // Nested function definitions (appearing inside another
                // function body — indent > 0) are emitted with the
                // `function` keyword prefix to match bash's declare -f
                // normalization for `declare -f`.
                // Top-level defs use the bare `name () ` form regardless
                // of how they were parsed.
                if (indent > 0) out.append("function ")
                out.append(cmd.name).append(" () \n")
                indent(indent)
                out.append("{ \n")
                printFunctionBodyInterior(cmd.body, indent + 1)
                indent(indent)
                out.append("}")
                if (cmd.body !is Subshell) appendRedirections(cmd.body.redirections)
                appendRedirections(cmd.redirections)
            }

            is ArithCommand -> {
                out.append("((").append(cmd.rawText).append("))")
            }

            is ArithForCommand -> {
                out
                    .append("for ((")
                    .append(cmd.init)
                    .append("; ")
                    .append(cmd.cond)
                    .append("; ")
                    .append(cmd.update)
                    .append(" )); do\n")
                for (st in cmd.body) {
                    indent(indent + 1)
                    printStatement(st, indent + 1, isLast = false)
                }
                indent(indent)
                out.append("done")
                appendRedirections(cmd.redirections)
            }

            is CondCommand -> {
                out.append("[[ ")
                printCondExpr(cmd.expression)
                out.append(" ]]")
                appendRedirections(cmd.redirections)
            }

            is CoprocCommand -> {
                // `coproc [NAME] BODY` deparse:
                //   - simple-command body: render `coproc cmd args` inline,
                //     no NAME prefix (NAME defaults to COPROC implicitly).
                //   - Group / Subshell body: `coproc NAME { ... }` /
                //     `coproc NAME ( ... )` with NAME ALWAYS explicit (even
                //     when it's the default "COPROC"), so the subscript
                //     form `${NAME[0]}` round-trips through declare -f.
                out.append("coproc")
                val body = cmd.body
                val nameToShow =
                    if (body is Group || body is Subshell) {
                        cmd.name ?: "COPROC"
                    } else {
                        null
                    }
                if (nameToShow != null) {
                    out.append(' ').append(nameToShow)
                }
                out.append(' ')
                printCommand(body, indent)
                appendRedirections(cmd.redirections)
            }
        }
    }

    /**
     * Inline-statement print used inside `$(...)`, `<(...)`, and other
     * single-line subscripts. Statements separated by `; `, no terminator
     * after the last one. Pending heredocs from inner commands are
     * flushed eagerly (heredocs inside cmdsub already terminate at the
     * `)` close).
     */
    private fun printSubScript(statements: List<Statement>) {
        for ((idx, st) in statements.withIndex()) {
            if (idx > 0) out.append("; ")
            printPipelinesInline(st)
            // A heredoc inside `$(...)` doesn't have a place to put its
            // body in re-parseable form on one line; bash's declare -f
            // actually fails to round-trip these too. Drop the buffer
            // rather than emit a broken delimiter line.
            pendingHeredocs.clear()
        }
    }

    /** Inline-statement form for `( s1; s2; s3 )` subshell context. */
    private fun printPipelinesInline(
        st: Statement,
        indent: Int = 0,
    ) {
        for ((idx, pl) in st.pipelines.withIndex()) {
            if (idx > 0) {
                val op = st.operators[idx - 1]
                out.append(if (op == Connector.AND) " && " else " || ")
            }
            printPipeline(pl, indent)
        }
    }

    private fun printSimple(cmd: SimpleCommand) {
        // bash special-case: a SimpleCommand with no name, no args, no
        // assignments, and exactly one input redirection is rendered as
        // `<file` (no leading space), used inside `$(<file)`. The
        // general printSimple path would yield ` <file` (with the
        // leading space from appendRedirections), and surrounded by
        // `$(` and `)` that becomes `$( <file)` — wrong shape.
        if (cmd.name == null && cmd.args.isEmpty() && cmd.assignments.isEmpty() &&
            cmd.argAssignments.isEmpty() && cmd.redirections.size == 1
        ) {
            val r = cmd.redirections[0]
            if (r.operator == RedirOp.INPUT && r.target is RedirTarget.File && r.fd == null) {
                out.append('<')
                printWord(r.target.word)
                return
            }
        }
        var first = true
        // Some parser configurations populate BOTH `assignments` and
        // `argAssignments` with the same inline assignment for
        // assignment-aware builtins (`local`, `declare`, `export`,
        // `readonly`, `typeset`). When there's a command word and
        // argAssignments mirrors the leading assignments, treat the
        // leading set as redundant — print only argAssignments after
        // the command word.
        val skipLeading =
            cmd.name != null && cmd.argAssignments.isNotEmpty() &&
                cmd.assignments.size == cmd.argAssignments.size &&
                cmd.assignments.zip(cmd.argAssignments).all { (a, b) -> a == b }
        if (!skipLeading) {
            for (a in cmd.assignments) {
                if (!first) out.append(' ')
                printAssignment(a)
                first = false
            }
        }
        cmd.name?.let {
            if (!first) out.append(' ')
            printWord(it)
            first = false
        }
        for (a in cmd.argAssignments) {
            if (!first) out.append(' ')
            printAssignment(a)
            first = false
        }
        // Skip args that are textual fallbacks for an argAssignment we
        // already printed — see [SimpleCommand.argFallbackIndices].
        for ((i, w) in cmd.args.withIndex()) {
            if (i in cmd.argFallbackIndices) continue
            if (!first) out.append(' ')
            printWord(w)
            first = false
        }
        appendRedirections(cmd.redirections)
    }

    private fun printAssignment(a: InlineAssignment) {
        out.append(a.name).append(if (a.append) "+=" else "=")
        when (a) {
            is InlineAssignment.Scalar -> {
                printWord(a.value)
            }

            is InlineAssignment.Indexed -> {
                out.append('[')
                printWord(a.subscript)
                out.append("]=")
                printWord(a.value)
            }

            is InlineAssignment.Array -> {
                out.append('(')
                for ((idx, e) in a.elements.withIndex()) {
                    if (idx > 0) out.append(' ')
                    printWord(e)
                }
                out.append(')')
            }
        }
    }

    private fun printIf(
        cmd: IfCommand,
        indent: Int,
    ) {
        // Bash's declare -f converts `if-elif-elif-else` into nested
        // `if-else { if-else { … } }` form. The on-the-wire compactness
        // of the elif keyword is lost in the round-trip; we mirror that
        // expansion here so output is byte-equivalent to bash's.
        printIfClauses(cmd.clauses, cmd.elseBody, indent)
        appendRedirections(cmd.redirections)
    }

    private fun printIfClauses(
        clauses: List<com.accucodeai.kash.ast.IfClause>,
        elseBody: List<Statement>?,
        indent: Int,
    ) {
        val first = clauses[0]
        out.append("if ")
        for ((idx, st) in first.condition.withIndex()) {
            if (idx > 0) out.append("; ")
            printPipelinesInline(st)
        }
        // Bash declare -f: if the condition pipeline opened any
        // heredocs, the bodies flush BEFORE `then` (and `then` lands
        // on its own line, no leading `;`). Without heredocs, `; then`
        // stays inline.
        if (pendingHeredocs.isNotEmpty()) {
            flushHeredocsAfter()
            indent(indent)
            out.append("then\n")
        } else {
            out.append("; then\n")
        }
        for ((idx, st) in first.body.withIndex()) {
            indent(indent + 1)
            printStatement(st, indent + 1, isLast = false)
        }
        val rest = clauses.drop(1)
        if (rest.isNotEmpty()) {
            // Recurse: the remaining elif-chain (+ else body) becomes the
            // inner if of a synthesized `else { if … fi }`. Trailing `;`
            // on the inner `fi` matches bash's wrap convention.
            indent(indent)
            out.append("else\n")
            indent(indent + 1)
            printIfClauses(rest, elseBody, indent + 1)
            out.append(";\n")
            indent(indent)
            out.append("fi")
        } else {
            elseBody?.let { body ->
                indent(indent)
                out.append("else\n")
                for ((idx, st) in body.withIndex()) {
                    indent(indent + 1)
                    printStatement(st, indent + 1, isLast = false)
                }
            }
            indent(indent)
            out.append("fi")
        }
    }

    private fun printFor(
        cmd: ForCommand,
        indent: Int,
    ) {
        out.append(if (cmd.isSelect) "select " else "for ").append(cmd.variable)
        cmd.words?.let { ws ->
            out.append(" in")
            for (w in ws) {
                out.append(' ')
                printWord(w)
            }
        }
        // Bash's declare -f puts `do` on its own line for for/select,
        // unlike while/until where `; do` stays inline.
        out.append(";\n")
        indent(indent)
        out.append("do\n")
        for ((idx, st) in cmd.body.withIndex()) {
            indent(indent + 1)
            printStatement(st, indent + 1, isLast = false)
        }
        indent(indent)
        out.append("done")
        appendRedirections(cmd.redirections)
    }

    private fun printWhile(
        cmd: WhileCommand,
        indent: Int,
    ) {
        out.append(if (cmd.until) "until " else "while ")
        for ((idx, st) in cmd.condition.withIndex()) {
            if (idx > 0) out.append("; ")
            printPipelinesInline(st)
        }
        // Bash declare -f: same convention as `if` — heredocs in the
        // condition flush BEFORE `do`, and `do` lands on its own line
        // without the leading `;`. See [printIfClauses].
        if (pendingHeredocs.isNotEmpty()) {
            flushHeredocsAfter()
            indent(indent)
            out.append("do\n")
        } else {
            out.append("; do\n")
        }
        for ((idx, st) in cmd.body.withIndex()) {
            indent(indent + 1)
            printStatement(st, indent + 1, isLast = false)
        }
        indent(indent)
        out.append("done")
        appendRedirections(cmd.redirections)
    }

    private fun printCase(
        cmd: CaseCommand,
        indent: Int,
    ) {
        out.append("case ")
        printWord(cmd.word)
        // Bash declare -f trails `in` with a space — `case X in \n`.
        out.append(" in \n")
        for (item in cmd.items) {
            indent(indent + 1)
            for ((idx, p) in item.patterns.withIndex()) {
                if (idx > 0) out.append(" | ")
                printWord(p)
            }
            out.append(")\n")
            // Bash declare -f drops the trailing `;` on the last stmt of
            // a case-clause body (similar to function-body-Group's last
            // stmt rule, just one level deeper).
            for ((sidx, st) in item.body.withIndex()) {
                indent(indent + 2)
                printStatement(st, indent + 2, isLast = sidx == item.body.lastIndex)
            }
            indent(indent + 1)
            val term =
                when (item.terminator) {
                    CaseTerminator.BREAK -> ";;"
                    CaseTerminator.FALLTHROUGH -> ";&"
                    CaseTerminator.CONTINUE_TEST -> ";;&"
                }
            out.append(term).append('\n')
        }
        indent(indent)
        out.append("esac")
        appendRedirections(cmd.redirections)
    }

    private fun printCondExpr(e: CondExpr) {
        when (e) {
            is CondExpr.And -> {
                printCondExpr(e.left)
                out.append(" && ")
                printCondExpr(e.right)
            }

            is CondExpr.Or -> {
                printCondExpr(e.left)
                out.append(" || ")
                printCondExpr(e.right)
            }

            is CondExpr.Not -> {
                out.append("! ")
                printCondExpr(e.expr)
            }

            is CondExpr.Lone -> {
                printWord(e.word)
            }

            is CondExpr.Unary -> {
                out.append(e.op).append(' ')
                printWord(e.operand)
            }

            is CondExpr.Binary -> {
                printWord(e.left)
                out.append(' ').append(e.op).append(' ')
                printWord(e.right)
            }
        }
    }

    private fun printWord(w: Word) {
        for (p in w.parts) printWordPart(p)
    }

    private fun printWordPart(p: WordPart) {
        when (p) {
            is WordPart.Literal -> {
                out.append(p.value)
            }

            is WordPart.SingleQuoted -> {
                out.append('\'').append(p.value).append('\'')
            }

            is WordPart.DoubleQuoted -> {
                out.append('"')
                for (sub in p.parts) printWordPart(sub)
                out.append('"')
            }

            is WordPart.Escaped -> {
                out.append('\\').append(p.value)
            }

            is WordPart.ParameterExpansion -> {
                if (p.braced || p.op != null || p.lengthOf || p.indirect || p.subscript != null || p.nameGlob != null) {
                    out.append("\${")
                    if (p.lengthOf) out.append('#')
                    if (p.indirect) out.append('!')
                    out.append(p.parameter)
                    p.subscript?.let { out.append('[').append(it).append(']') }
                    p.nameGlob?.let { out.append(it) }
                    p.op?.let {
                        out.append(it)
                        p.arg1?.let { w -> printWord(w) }
                        p.arg2?.let { w ->
                            out.append(':')
                            printWord(w)
                        }
                    }
                    out.append('}')
                } else {
                    out.append('$').append(p.parameter)
                }
            }

            is WordPart.CommandSubstitution -> {
                // `declare -f` shape: body's leading/trailing whitespace is
                // stripped (`$( cmd )` → `$(cmd)`). Internal whitespace stays
                // verbatim — we don't re-parse + pretty-print here because
                // that would require a parser dependency and an alias
                // resolver, and a fully-faithful round-trip would diverge
                // from upstream conventions on edge cases like comments.
                //
                // The `$(<file)` short-form (POSIX-shell shorthand for
                // `$(cat file)`) deparses with a space after `<` — the
                // declare -f convention is `$(< file)`, not `$(<file)`.
                val body = p.rawText.trim()
                val canonical =
                    if (body.startsWith("<") && body.length > 1 && body[1] != ' ' && body[1] != '<') {
                        "< " + body.substring(1)
                    } else {
                        body
                    }
                out.append("$(").append(canonical).append(')')
            }

            is WordPart.ProcessSubstitution -> {
                out
                    .append(p.direction)
                    .append('(')
                    .append(p.rawText.trim())
                    .append(')')
            }

            is WordPart.ArithmeticExpansion -> {
                out.append("\$((").append(p.rawText).append("))")
            }

            is WordPart.ExpandedText -> {
                out.append(p.value)
            }
        }
    }

    private fun appendRedirections(redirs: List<Redirection>) {
        for (r in redirs) {
            out.append(' ')
            // Bash dynamic-fd form `{NAME}>file` mutually excludes a
            // literal fd prefix — the runtime allocates a high fd and
            // stores it in $NAME. AstPrinter has to round-trip this back
            // through declare -f / type output or scripts that re-source
            // the function lose the fd-variable association.
            if (r.fdVar != null) {
                out.append('{').append(r.fdVar).append('}')
            } else if (r.fd != null) {
                out.append(r.fd)
            } else if (r.operator == RedirOp.DUP_OUT) {
                // Bash declare -f normalizes `>&N` to `1>&N` (the default
                // source fd for output duplication is 1).
                out.append(1)
            } else if (r.operator == RedirOp.DUP_IN) {
                // Symmetric default for input duplication: `<&N` → `0<&N`.
                out.append(0)
            }
            val op = r.operator
            // Close-fd close form (`<&-`/`>&-`, encoded as Fd(-1)) deparses
            // canonically as `>&-` regardless of the input direction —
            // matches the bash deparse convention; both forms have the
            // same closing semantics.
            val effectiveOp =
                if (op == RedirOp.DUP_IN && r.target is RedirTarget.Fd && r.target.fd < 0) {
                    RedirOp.DUP_OUT
                } else {
                    op
                }
            out.append(
                when (effectiveOp) {
                    RedirOp.INPUT -> "<"
                    RedirOp.OUTPUT -> ">"
                    RedirOp.APPEND -> ">>"
                    RedirOp.HERESTRING -> "<<<"
                    RedirOp.HEREDOC -> "<<"
                    RedirOp.HEREDOC_STRIP -> "<<-"
                    RedirOp.DUP_IN -> "<&"
                    RedirOp.DUP_OUT -> ">&"
                    RedirOp.READ_WRITE -> "<>"
                    RedirOp.CLOBBER -> ">|"
                    RedirOp.OUT_AND_ERR -> "&>"
                    RedirOp.OUT_AND_ERR_APPEND -> "&>>"
                },
            )
            // Bash's declare -f puts a space between the operator and a
            // file target (`> /dev/null`, not `>/dev/null`), but writes
            // fd duplications and heredoc delimiters tightly (`>&2`, `<<EOF`).
            val needsSpace =
                when (op) {
                    RedirOp.DUP_IN, RedirOp.DUP_OUT, RedirOp.HEREDOC, RedirOp.HEREDOC_STRIP -> false
                    else -> r.target is RedirTarget.File
                }
            if (needsSpace) out.append(' ')
            when (val t = r.target) {
                is RedirTarget.File -> {
                    printWord(t.word)
                }

                is RedirTarget.HereDoc -> {
                    // Prefer the original source delimiter (preserved on
                    // RedirTarget.HereDoc.delim). Fall back to synthesized
                    // `_HD<N>_` only when missing — typically test-built
                    // ASTs that never went through the parser.
                    val delim = t.delim.ifEmpty { pickHeredocDelimiter(t.body) }
                    if (t.quoted) {
                        out.append('\'').append(delim).append('\'')
                    } else {
                        out.append(delim)
                    }
                    pendingHeredocs.add(PendingHeredoc(delim, t.body, t.quoted, t.stripTabs))
                }

                is RedirTarget.Fd -> {
                    // -1 is the close-fd sentinel (`>&-` / `<&-`). Render
                    // as `-` so `<& -1` doesn't escape as a literal integer.
                    if (t.fd < 0) out.append('-') else out.append(t.fd)
                }
            }
        }
    }

    /**
     * Pick a delimiter for emitting a heredoc body. Original delimiter
     * is lost in the AST, so generate `_HD<N>_` and bump on collision.
     * The body content is rendered through [printWord] for comparison,
     * which matches what the re-parser will see.
     */
    private fun pickHeredocDelimiter(body: Word): String {
        // Render once to check for delimiter collision.
        val rendered =
            StringBuilder()
                .also { sb ->
                    val tmp = AstPrinter().also { p -> p.out.append(sb) }
                    // Cheaper: stringify the literal parts inline.
                    for (part in body.parts) {
                        if (part is WordPart.Literal) {
                            sb.append(part.value)
                        } else if (part is WordPart.SingleQuoted) {
                            sb.append(part.value)
                        }
                    }
                }.toString()
        var n = heredocCounter
        while (true) {
            val cand = "_HD${n}_"
            // The delimiter must NOT appear on a line by itself in the body.
            val asLine = rendered.lineSequence().any { it == cand }
            if (!asLine) {
                heredocCounter = n + 1
                return cand
            }
            n++
        }
    }

    private fun indent(n: Int) {
        repeat(n) { out.append("    ") }
    }
}
