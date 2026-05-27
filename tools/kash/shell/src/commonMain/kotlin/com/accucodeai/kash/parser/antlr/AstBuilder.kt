package com.accucodeai.kash.parser.antlr

import com.accucodeai.kash.ast.ArithCommand
import com.accucodeai.kash.ast.ArithForCommand
import com.accucodeai.kash.ast.CaseCommand
import com.accucodeai.kash.ast.CaseItem
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
import com.accucodeai.kash.ast.IfClause
import com.accucodeai.kash.ast.IfCommand
import com.accucodeai.kash.ast.InlineAssignment
import com.accucodeai.kash.ast.Pipeline
import com.accucodeai.kash.ast.RedirOp
import com.accucodeai.kash.ast.RedirTarget
import com.accucodeai.kash.ast.Redirection
import com.accucodeai.kash.ast.Script
import com.accucodeai.kash.ast.SimpleCommand
import com.accucodeai.kash.ast.Statement
import com.accucodeai.kash.ast.Subshell
import com.accucodeai.kash.ast.WhileCommand
import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart
import com.accucodeai.kash.parser.AliasResolver
import com.accucodeai.kash.parser.ParseException
import com.accucodeai.kash.parser.Token
import com.accucodeai.kash.parser.WordChunk
import com.accucodeai.kash.parser.chunksToWord as chunksToWordImpl
import com.accucodeai.kash.parser.toWord as toWordImpl

/**
 * Translates an ANTLR parse tree into the existing kash AST. Each method
 * mirrors one grammar rule and produces the matching [com.accucodeai.kash.ast]
 * node type. The translation reads structured payload (word parts, heredoc
 * bodies, raw arithmetic text, …) off the original [Token] preserved on each
 * [KashAntlrToken.source].
 *
 * The [aliasResolver] is propagated into nested `$(...)` parsing via the word-
 * conversion helpers so runtime aliases apply inside command substitutions.
 */
internal class AstBuilder(
    private val aliasResolver: AliasResolver = AliasResolver.Empty,
    /**
     * Original script text. When non-null, each [SimpleCommand] gets its
     * verbatim source slice attached via [SimpleCommand.srcText] — that
     * value backs bash's `$BASH_COMMAND` dynamic variable. Null is the
     * legacy / programmatic-AST path (snapshots, tests) where we don't
     * have the source to slice from.
     */
    private val source: String? = null,
) {
    private val sourceLines: List<String>? by lazy { source?.lines() }

    /**
     * Extract the verbatim source span for an ANTLR parse-tree node, used to
     * back `$BASH_COMMAND`. The kash lexer doesn't propagate character-level
     * `startIndex`/`stopIndex` through [com.accucodeai.kash.parser.antlr.KashAntlrToken]
     * (only `line`), so we fall back to whole-line slicing — accurate for
     * one-command-per-line scripts (every command in the bash dynvar corpus
     * and the conformance suite at large), conservative for multi-command
     * lines like `echo a; echo b` where both commands would see the joined
     * source. Bash users almost never observe the difference because
     * `$BASH_COMMAND` is only ever read inside traps and debug hooks.
     */
    private fun sourceSpan(ctx: org.antlr.v4.kotlinruntime.ParserRuleContext): String? {
        val lines = sourceLines ?: return null
        val startLine = ctx.start?.line ?: return null
        val stopLine = ctx.stop?.line ?: startLine
        if (startLine !in 1..stopLine || stopLine > lines.size) return null
        return if (startLine == stopLine) {
            lines[startLine - 1].trim()
        } else {
            lines.subList(startLine - 1, stopLine).joinToString("\n") { it.trim() }
        }
    }

    // Member-shadow extensions: keep call sites below (`tok.toWord()`,
    // `chunksToWord(parts, line)`) unchanged while threading the resolver.
    private fun Token.WordTok.toWord(): com.accucodeai.kash.ast.Word = toWordImpl(aliasResolver)

    private fun chunksToWord(
        parts: List<WordChunk>,
        line: Int,
    ): Word = chunksToWordImpl(parts, line, aliasResolver)

    fun script(ctx: KashParser.ScriptContext): Script {
        val stmts = statementsFromChildren(ctx.children ?: emptyList())
        return Script(stmts, line = ctx.start?.line ?: 1)
    }

    /**
     * Build a single Statement AST from one parsed [KashParser.StatementContext].
     * Used by [Parser.parseUntilError]'s statement-streaming loop, which calls
     * `parser.statement()` repeatedly rather than `parser.script()` so the
     * runtime can interleave parse and execute (bash semantics — execute
     * statements before encountering a later syntax error).
     */
    internal fun singleStatement(
        ctx: KashParser.StatementContext,
        background: Boolean,
    ): Statement = statement(ctx, background)

    private fun statement(
        ctx: KashParser.StatementContext,
        background: Boolean,
    ): Statement {
        val pipelines = ctx.pipeline().map { pipeline(it) }
        val connectors =
            ctx.connector().map {
                if (it.OP_AND_AND() != null) Connector.AND else Connector.OR
            }
        return Statement(pipelines, connectors, background, line = pipelines.first().line)
    }

    /**
     * Walk a parent's children (`script`, `compoundList`, `caseItem`) and
     * build the list of [Statement]s, deciding per-statement whether to run
     * in the background by looking at the [SepContext] tokens that follow
     * the statement (up to the next statement or end of the parent). A sep
     * carrying `OP_AMP` marks the preceding statement async; `;` and `NL`
     * do not. This is how POSIX `cmd1 & cmd2` decomposes — two statements,
     * the first backgrounded.
     */
    private fun statementsFromChildren(children: List<org.antlr.v4.kotlinruntime.tree.ParseTree>): List<Statement> {
        val starts = children.indices.filter { children[it] is KashParser.StatementContext }
        return starts.mapIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: children.size
            val bg =
                (start + 1 until end).any {
                    (children[it] as? KashParser.SepContext)?.OP_AMP() != null
                }
            statement(children[start] as KashParser.StatementContext, bg)
        }
    }

    private fun pipeline(ctx: KashParser.PipelineContext): Pipeline {
        fun decodeTimeOpt(
            opt: KashParser.TimeOptContext?,
            line: Int?,
        ): com.accucodeai.kash.ast.TimeSpec {
            var spec = com.accucodeai.kash.ast.TimeSpec.DEFAULT
            opt?.WORD()?.forEach { w ->
                when (w.text) {
                    "-p" -> {
                        spec = com.accucodeai.kash.ast.TimeSpec.POSIX
                    }

                    "--" -> { /* end-of-options marker */ }

                    else -> {
                        throw ParseException("time: bad option `${w.text}' at line $line")
                    }
                }
            }
            return spec
        }

        // The grammar threads pipeSep between each pair of commands; read
        // whether each separator was `|` or `|&`. Empty list when no
        // `|&` is present so the simple-`|` case stays cheap.
        fun pipeSepFlags(seps: List<KashParser.PipeSepContext>): List<Boolean> {
            if (seps.isEmpty() || seps.none { it.OP_PIPE_AMP() != null }) return emptyList()
            return seps.map { it.OP_PIPE_AMP() != null }
        }

        // Bash composition: nested `time`s OR their flag bits onto the same
        // command node (: `$2->flags |= $1; $$ = $2`).
        // POSIX bit dominates non-POSIX — any `-p` in the stack flips the
        // result to POSIX.
        fun mergeTimeSpec(
            existing: com.accucodeai.kash.ast.TimeSpec?,
            next: com.accucodeai.kash.ast.TimeSpec,
        ): com.accucodeai.kash.ast.TimeSpec =
            when {
                existing == null -> {
                    next
                }

                existing == com.accucodeai.kash.ast.TimeSpec.POSIX ||
                    next == com.accucodeai.kash.ast.TimeSpec.POSIX -> {
                    com.accucodeai.kash.ast.TimeSpec.POSIX
                }

                else -> {
                    com.accucodeai.kash.ast.TimeSpec.DEFAULT
                }
            }
        // Walk the recursive pipelineCmd chain, accumulating one negated
        // parity bit and one timed spec, then build a single Pipeline from
        // the leaf cmds. Matches bash's "flags OR'd onto one inner command"
        // structure — kash's Pipeline data class is the analogue of that
        // single command node.
        var node: KashParser.PipelineCmdContext = ctx.pipelineCmd()
        var negated = false
        var timed: com.accucodeai.kash.ast.TimeSpec? = null
        while (true) {
            when (node) {
                is KashParser.TimedPipelineCmdContext -> {
                    val t = node
                    timed = mergeTimeSpec(timed, decodeTimeOpt(t.timeOpt(), t.start?.line))
                    val inner = t.pipelineCmd()
                    if (inner == null) {
                        // Bare `time;` — empty body with the time flag set.
                        return Pipeline(emptyList(), negated, timed = timed, line = ctx.start?.line ?: 1)
                    }
                    node = inner
                }

                is KashParser.BangPipelineCmdContext -> {
                    // Bash: `$2->flags ^= CMD_INVERT_RETURN` — parity-toggle
                    // per layer, so `! ! cmd` cancels back to non-negated.
                    negated = !negated
                    val inner =
                        node.pipelineCmd() ?: // Bare `!` (and `! !` runs) — empty pipeline; runtime
                            // applies the accumulated negation to implicit-true.
                            return Pipeline(emptyList(), negated, timed = timed, line = ctx.start?.line ?: 1)
                    node = inner
                }

                is KashParser.UntimedPipelineCmdContext -> {
                    val u = node
                    val cmds = u.command().map { command(it) }
                    val pipeSep = pipeSepFlags(u.pipeSep())
                    val line = cmds.firstOrNull()?.line ?: ctx.start?.line ?: 1
                    return Pipeline(cmds, negated, timed = timed, pipeStderr = pipeSep, line = line)
                }

                else -> {
                    throw ParseException(
                        "internal: unknown pipelineCmd shape at line ${node.start?.line}",
                    )
                }
            }
        }
    }

    private fun command(ctx: KashParser.CommandContext): Command {
        ctx.ifCmd()?.let { return ifCmd(it) }
        ctx.forCmd()?.let { return forCmd(it) }
        ctx.selectCmd()?.let { return selectCmd(it) }
        ctx.whileCmd()?.let { return whileCmd(it, until = false) }
        ctx.untilCmd()?.let { return untilCmd(it) }
        ctx.caseCmd()?.let { return caseCmd(it) }
        ctx.groupCmd()?.let { return groupCmd(it) }
        ctx.subshellCmd()?.let { return subshellCmd(it) }
        ctx.condCmd()?.let { return condCmd(it) }
        ctx.arithCmd()?.let { return arithCmd(it) }
        ctx.functionFuncDef()?.let { return functionFuncDef(it) }
        ctx.nameFuncDef()?.let { return nameFuncDef(it) }
        ctx.coprocCmd()?.let { return coprocCmd(it) }
        ctx.simpleCommand()?.let { return simpleCommand(it) }
        throw ParseException("internal: empty command alternative at line ${ctx.start?.line}")
    }

    // -------- Simple command --------

    private fun simpleCommand(ctx: KashParser.SimpleCommandContext): SimpleCommand {
        val parts = ctx.commandPart()
        val assignments = mutableListOf<InlineAssignment>()
        val argAssignments = mutableListOf<InlineAssignment>()
        val words = mutableListOf<Word>()
        val redirs = mutableListOf<Redirection>()
        val argFallbackWordIndices = mutableSetOf<Int>()
        var startLine = ctx.start?.line ?: 1
        var seenWord = false

        // Add an assignment to either `assignments` (pre-word) or
        // `argAssignments` (post-word, for assignment-aware builtins). For the
        // post-word case, optionally surface a textual word fallback so
        // ordinary (non-builtin) commands still see the operand.
        fun record(
            a: InlineAssignment,
            wordFallback: () -> Word?,
        ) {
            if (!seenWord) {
                assignments += a
            } else {
                argAssignments += a
                wordFallback()?.let {
                    argFallbackWordIndices += words.size
                    words += it
                }
            }
        }

        for (cp in parts) {
            when {
                cp.ASSIGN() != null -> {
                    val tok = cp.ASSIGN()!!.symbol.kashAssign()
                    record(
                        InlineAssignment.Scalar(tok.name, chunksToWord(tok.value, tok.line), tok.append),
                    ) { assignAsWord(tok) }
                }

                cp.ARRAY_ASSIGN() != null -> {
                    val tok = cp.ARRAY_ASSIGN()!!.symbol.kashArrayAssign()
                    // `name=(elems)` has no clean textual form that survives
                    // splitting, so the post-word path provides no fallback —
                    // non-builtin commands rejected this in the prior impl.
                    record(
                        InlineAssignment.Array(
                            tok.name,
                            tok.elements.map { chunksToWord(it, tok.line) },
                            tok.append,
                        ),
                    ) { null }
                }

                cp.INDEXED_ASSIGN() != null -> {
                    val tok = cp.INDEXED_ASSIGN()!!.symbol.kashIndexedAssign()
                    record(
                        InlineAssignment.Indexed(
                            tok.name,
                            chunksToWord(tok.subscript, tok.line),
                            chunksToWord(tok.value, tok.line),
                            tok.append,
                            listAttempt = tok.listAttempt,
                        ),
                    ) { Word.literal("${tok.name}[…]=", tok.line) }
                }

                cp.WORD() != null -> {
                    val tok = cp.WORD()!!.symbol.kashWord()
                    if (!seenWord) startLine = tok.line
                    seenWord = true
                    words += tok.toWord()
                }

                cp.redirection() != null -> {
                    redirs += redirection(cp.redirection()!!)
                }
            }
        }

        if (words.isEmpty() && assignments.isEmpty() && redirs.isEmpty()) {
            throw ParseException("expected a command at line $startLine")
        }
        // argFallbackWordIndices indexes `words` (cmd-name at slot 0). Translate
        // to `args` indices by subtracting 1 — the cmd-name slot is never a
        // fallback (fallbacks are only added after seenWord=true).
        val argFallbacks = argFallbackWordIndices.map { it - 1 }.filter { it >= 0 }.toSet()
        return SimpleCommand(
            words.firstOrNull(),
            words.drop(1),
            assignments,
            redirs,
            line = if (words.isEmpty()) parts.first().start?.line ?: startLine else startLine,
            argAssignments = argAssignments,
            srcText = sourceSpan(ctx),
            argFallbackIndices = argFallbacks,
        )
    }

    private fun assignAsWord(tok: Token.AssignmentTok): Word {
        val literal = WordPart.Literal("${tok.name}${if (tok.append) "+=" else "="}")
        val rest =
            chunksToWord(tok.value, tok.line).parts
        return Word(listOf(literal) + rest, tok.line)
    }

    // -------- Compound: if --------

    private fun ifCmd(ctx: KashParser.IfCmdContext): IfCommand {
        // Top-level compoundList holds only the if-cond + then-body. Each
        // elifClause carries its own pair, and elseClause its single body.
        val ifThen = ctx.compoundList()
        val clauses = mutableListOf<IfClause>()
        clauses += IfClause(statementsOf(ifThen[0]), statementsOf(ifThen[1]))
        for (elif in ctx.elifClause()) {
            val pair = elif.compoundList()
            clauses += IfClause(statementsOf(pair[0]), statementsOf(pair[1]))
        }
        val elseBody = ctx.elseClause()?.let { statementsOf(it.compoundList()) }
        return IfCommand(
            clauses = clauses,
            elseBody = elseBody,
            redirections = redirsAfter(ctx.redirection()),
            line = ctx.start?.line ?: 1,
        )
    }

    // -------- Compound: for / while / until --------

    private fun forCmd(ctx: KashParser.ForCmdContext): CompoundCommand {
        val start = ctx.start?.line ?: 1
        ctx.arithForHeader()?.let { arith ->
            val (init, cond, update) = splitArithFor(arith.ARITH_CMD().symbol.text ?: "")
            return ArithForCommand(
                init = init,
                cond = cond,
                update = update,
                body = statementsOf(ctx.body().compoundList()),
                redirections = redirsAfter(ctx.redirection()),
                line = start,
            )
        }
        val (varName, words) = forVarAndWords(ctx.forVar()!!, ctx.forInClause(), "for-loop", start)
        return ForCommand(
            variable = varName,
            words = words,
            body = statementsOf(ctx.body().compoundList()),
            redirections = redirsAfter(ctx.redirection()),
            line = start,
        )
    }

    private fun selectCmd(ctx: KashParser.SelectCmdContext): CompoundCommand {
        val start = ctx.start?.line ?: 1
        val (varName, words) = forVarAndWords(ctx.forVar(), ctx.forInClause(), "select", start)
        return ForCommand(
            variable = varName,
            words = words,
            body = statementsOf(ctx.body().compoundList()),
            isSelect = true,
            redirections = redirsAfter(ctx.redirection()),
            line = start,
        )
    }

    private fun forVarAndWords(
        forVarCtx: KashParser.ForVarContext,
        inClause: KashParser.ForInClauseContext?,
        kind: String,
        line: Int,
    ): Pair<String, List<Word>?> {
        val name =
            wordOrKeywordText(forVarCtx.WORD(), forVarCtx.anyKeyword())
                ?: throw ParseException("expected $kind variable at line $line")
        val words =
            inClause?.forWord()?.map {
                it
                    .WORD()
                    .symbol
                    .kashWord()
                    .toWord()
            }
        return name to words
    }

    private fun whileCmd(
        ctx: KashParser.WhileCmdContext,
        until: Boolean,
    ): WhileCommand =
        WhileCommand(
            condition = statementsOf(ctx.compoundList(0)!!),
            body = statementsOf(ctx.compoundList(1)!!),
            until = until,
            redirections = redirsAfter(ctx.redirection()),
            line = ctx.start?.line ?: 1,
        )

    private fun untilCmd(ctx: KashParser.UntilCmdContext): WhileCommand =
        WhileCommand(
            condition = statementsOf(ctx.compoundList(0)!!),
            body = statementsOf(ctx.compoundList(1)!!),
            until = true,
            redirections = redirsAfter(ctx.redirection()),
            line = ctx.start?.line ?: 1,
        )

    // -------- Compound: case --------

    private fun caseCmd(ctx: KashParser.CaseCmdContext): CaseCommand {
        val word = caseWord(ctx.caseWord())
        val rawItems = ctx.caseItem()
        // Bash treats `(...)` after a non-final case-item body as a SUBSHELL
        // starting the body, not as the leading-paren of a new pattern. If
        // the subshell's content is the bare reserved word `esac`, bash
        // errors with `syntax error near unexpected token \`esac''. Our
        // grammar is happier to take the leading-paren-pattern alternative,
        // so emulate bash's rejection here: when any non-first case-item has
        // a leading `(` AND its first pattern is the bare word `esac`, throw
        // the same diagnostic.
        for ((idx, item) in rawItems.withIndex()) {
            if (idx == 0) continue
            if (item.OP_LPAREN() == null) continue
            val first = item.casePattern().firstOrNull() ?: continue
            if (casePatternText(first) == "esac") {
                throw ParseException(
                    "syntax error near unexpected token `esac'",
                    line = item.start?.line ?: 1,
                    offendingToken = "esac",
                )
            }
        }
        val items = rawItems.map { caseItem(it) }
        return CaseCommand(
            word = word,
            items = items,
            redirections = redirsAfter(ctx.redirection()),
            line = ctx.start?.line ?: 1,
        )
    }

    private fun caseWord(ctx: KashParser.CaseWordContext): Word =
        wordOrKeywordWord(ctx.WORD(), ctx.anyKeyword())
            ?: throw ParseException("expected case word at line ${ctx.start?.line}")

    private fun caseItem(ctx: KashParser.CaseItemContext): CaseItem {
        // POSIX.2 grammar rule 4: when the case-item opens *without* `(`, the
        // first pattern word may not be the keyword `esac` — that's the
        // case-statement terminator. Bash 5.2+ rejects this with a syntax
        // error near `)`; matched here so eval/exec users see the same
        // diagnostic.
        if (ctx.OP_LPAREN() == null) {
            // POSIX.2 rule 4: with no leading `(`, the first pattern may not
            // be `esac`. Bash relaxes this when there are MORE patterns
            // alternated with `|` — `case x in esac|y)` is a valid bare-word
            // `esac` pattern. So only flag when `esac` stands alone.
            val patterns = ctx.casePattern()
            val first = patterns.firstOrNull()
            if (first != null && casePatternText(first) == "esac" && patterns.size == 1) {
                throw ParseException(
                    "syntax error near unexpected token `)'",
                    line = ctx.start?.line ?: 1,
                    offendingToken = ")",
                )
            }
        }
        // Bash rejects `in` as a non-first pattern in a `|`-alternation
        // list — `case x in esac|in)` triggers `unexpected token \`in''.
        // Bash DOES allow `case in in in)` where `in` is the single pattern
        // (no alternation), so the check must be on multi-pattern lists.
        val rawPatterns = ctx.casePattern()
        if (rawPatterns.size > 1) {
            for ((idx, p) in rawPatterns.withIndex()) {
                if (idx == 0) continue
                if (casePatternText(p) == "in") {
                    throw ParseException(
                        "syntax error near unexpected token `in'",
                        line = ctx.start?.line ?: 1,
                        offendingToken = "in",
                    )
                }
            }
        }
        val patterns = rawPatterns.map { casePattern(it) }
        val cib = ctx.caseItemBody()
        val body = statementsFromChildren(cib.children ?: emptyList()).toMutableList()
        val terminator =
            cib.caseTerminator()?.let { t ->
                when {
                    t.OP_DSEMI_AMP() != null -> CaseTerminator.FALLTHROUGH
                    t.OP_DSEMI_SEMI() != null -> CaseTerminator.CONTINUE_TEST
                    else -> CaseTerminator.BREAK
                }
            } ?: CaseTerminator.BREAK
        return CaseItem(patterns, body, terminator)
    }

    private fun casePattern(ctx: KashParser.CasePatternContext): Word =
        wordOrKeywordWord(ctx.WORD(), ctx.anyKeyword())
            ?: throw ParseException("expected pattern at line ${ctx.start?.line}")

    /** Single-literal text of a casePattern (for the `esac`-as-pattern check), or null if quoted/expanded/keyword-shaped. */
    private fun casePatternText(ctx: KashParser.CasePatternContext): String? =
        wordOrKeywordText(ctx.WORD(), ctx.anyKeyword())

    // -------- Compound: group / subshell --------

    private fun groupCmd(ctx: KashParser.GroupCmdContext): Group =
        Group(
            body = statementsOf(ctx.compoundList()),
            redirections = redirsAfter(ctx.redirection()),
            line = ctx.start?.line ?: 1,
        )

    private fun subshellCmd(ctx: KashParser.SubshellCmdContext): Subshell =
        Subshell(
            body = statementsOf(ctx.compoundList()),
            redirections = redirsAfter(ctx.redirection()),
            line = ctx.start?.line ?: 1,
        )

    private fun coprocCmd(ctx: KashParser.CoprocCmdContext): CoprocCommand {
        val tail = ctx.coprocTail()
        val name: String?
        val body: Command
        when (tail) {
            is KashParser.CoprocNamedCompoundContext -> {
                name =
                    tail.coprocName().WORD().let { tok ->
                        val word = (tok.symbol as KashAntlrToken).kashSource as? Token.WordTok
                        val parts = word?.parts
                        if (parts?.size == 1) (parts[0] as? WordChunk.Literal)?.value else null
                    }
                body = coprocCompound(tail.coprocCompound())
            }

            is KashParser.CoprocAnonymousCompoundContext -> {
                name = null
                body = coprocCompound(tail.coprocCompound())
            }

            is KashParser.CoprocSimpleContext -> {
                name = null
                body = simpleCommand(tail.simpleCommand())
            }

            else -> {
                throw ParseException("internal: empty coproc tail at line ${ctx.start?.line}")
            }
        }
        return CoprocCommand(
            name = name,
            body = body,
            line = ctx.start?.line ?: 1,
        )
    }

    private fun coprocCompound(ctx: KashParser.CoprocCompoundContext): Command =
        ctx.groupCmd()?.let { groupCmd(it) }
            ?: ctx.subshellCmd()?.let { subshellCmd(it) }
            ?: ctx.ifCmd()?.let { ifCmd(it) }
            ?: ctx.forCmd()?.let { forCmd(it) }
            ?: ctx.whileCmd()?.let { whileCmd(it, until = false) }
            ?: ctx.untilCmd()?.let { untilCmd(it) }
            ?: ctx.caseCmd()?.let { caseCmd(it) }
            ?: ctx.selectCmd()?.let { selectCmd(it) }
            ?: ctx.condCmd()?.let { condCmd(it) }
            ?: ctx.arithCmd()?.let { arithCmd(it) }
            ?: throw ParseException("internal: empty coproc compound at line ${ctx.start?.line}")

    // -------- [[ ... ]] conditional --------

    private fun condCmd(ctx: KashParser.CondCmdContext): CondCommand =
        CondCommand(
            expression = condExpr(ctx.condExpr()),
            redirections = redirsAfter(ctx.redirection()),
            line = ctx.start?.line ?: 1,
        )

    private fun condExpr(ctx: KashParser.CondExprContext): CondExpr = condOr(ctx.condOr())

    private fun condOr(ctx: KashParser.CondOrContext): CondExpr = ctx.condAnd().map { condAnd(it) }.reduce(CondExpr::Or)

    private fun condAnd(ctx: KashParser.CondAndContext): CondExpr =
        ctx.condUnary().map { condUnary(it) }.reduce(CondExpr::And)

    private fun condUnary(ctx: KashParser.CondUnaryContext): CondExpr {
        if (ctx.KW_BANG() != null) {
            return CondExpr.Not(condUnary(ctx.condUnary()!!))
        }
        return condPrimary(ctx.condPrimary()!!)
    }

    private fun condPrimary(ctx: KashParser.CondPrimaryContext): CondExpr =
        when (ctx) {
            is KashParser.CondParenContext -> {
                condExpr(ctx.condExpr())
            }

            is KashParser.CondTriWordContext -> {
                val ws = ctx.WORD()
                val opTok = ws[1].symbol.kashWord()
                val opLit =
                    literalText(opTok) ?: throw ParseException(
                        "expected binary op in [[ ]] at line ${opTok.line}",
                    )
                CondExpr.Binary(
                    op = opLit,
                    left = ws[0].symbol.kashWord().toWord(),
                    right = ws[2].symbol.kashWord().toWord(),
                )
            }

            is KashParser.CondStringCmpContext -> {
                val op = if (ctx.REDIR_LT() != null) "<" else ">"
                val ws = ctx.WORD()
                CondExpr.Binary(
                    op = op,
                    left = ws[0].symbol.kashWord().toWord(),
                    right = ws[1].symbol.kashWord().toWord(),
                )
            }

            is KashParser.CondUnaryWordContext -> {
                val ws = ctx.WORD()
                val opTok = ws[0].symbol.kashWord()
                val opLit =
                    literalText(opTok) ?: throw ParseException(
                        "expected unary op in [[ ]] at line ${opTok.line}",
                    )
                CondExpr.Unary(opLit, ws[1].symbol.kashWord().toWord())
            }

            is KashParser.CondLoneContext -> {
                CondExpr.Lone(
                    ctx
                        .WORD()
                        .symbol
                        .kashWord()
                        .toWord(),
                )
            }

            else -> {
                throw ParseException("unknown condPrimary at line ${ctx.start?.line}")
            }
        }

    // -------- Arithmetic --------

    private fun arithCmd(ctx: KashParser.ArithCmdContext): ArithCommand =
        ArithCommand(
            rawText = ctx.ARITH_CMD().symbol.text ?: "",
            redirections = redirsAfter(ctx.redirection()),
            line = ctx.start?.line ?: 1,
        )

    // -------- Function definitions --------

    private fun functionFuncDef(ctx: KashParser.FunctionFuncDefContext): FunctionDef {
        val start = ctx.start?.line ?: 1
        val nameCtx = ctx.fnName()
        // The `function` keyword form accepts names that the bare
        // POSIX `name()` form can't, including ones containing `$`,
        // `<`, etc. literalText fails on such names (they lex into
        // ParameterExpansion / ProcessSubstitution chunks); fall back
        // to a source-text reconstruction. Runtime validation
        // (`InterpreterPipelines`) decides whether to register the
        // function or print the bash "not a valid identifier"
        // diagnostic and skip — `function` form parse never fails.
        val literal = wordOrKeywordText(nameCtx.WORD(), nameCtx.anyKeyword())
        val name =
            literal ?: nameCtx.WORD()?.let { reconstructWordSource(it.symbol.kashWord()) }
                ?: throw ParseException("expected function name at line $start")
        // Bash diagnoses an invalid-name function at the line of the
        // CLOSING `}` (or the last token of the def, for compound-cmd
        // bodies). Record the end-line so the runtime validator's
        // "not a valid identifier" prefix matches bash byte-for-byte.
        val endLine = ctx.stop?.line ?: start
        return FunctionDef(name, fnBody(ctx.fnBody()), redirections = redirsAfter(ctx.redirection()), line = endLine)
    }

    private fun nameFuncDef(ctx: KashParser.NameFuncDefContext): FunctionDef {
        val start = ctx.start?.line ?: 1
        // Same recovery logic as [functionFuncDef]: if the parsed name
        // contains expansion chunks (e.g. `<(:) ()` where `<(:)` lexes
        // as a process-substitution token), reconstruct the literal
        // source and let runtime validation reject it with bash's
        // "not a valid identifier" diagnostic — parse never throws.
        val wordTok = ctx.WORD().symbol.kashWord()
        val name = literalText(wordTok) ?: reconstructWordSource(wordTok)
        val endLine = ctx.stop?.line ?: start
        return FunctionDef(name, fnBody(ctx.fnBody()), redirections = redirsAfter(ctx.redirection()), line = endLine)
    }

    private fun fnBody(ctx: KashParser.FnBodyContext): CompoundCommand {
        ctx.groupCmd()?.let { return groupCmd(it) }
        ctx.subshellCmd()?.let { return subshellCmd(it) }
        ctx.ifCmd()?.let { return ifCmd(it) }
        ctx.forCmd()?.let { return forCmd(it) }
        ctx.whileCmd()?.let { return whileCmd(it, until = false) }
        ctx.untilCmd()?.let { return untilCmd(it) }
        ctx.caseCmd()?.let { return caseCmd(it) }
        ctx.selectCmd()?.let { return selectCmd(it) }
        ctx.condCmd()?.let { return condCmd(it) }
        ctx.arithCmd()?.let { return arithCmd(it) }
        throw ParseException("expected function body at line ${ctx.start?.line}")
    }

    // -------- Redirection --------

    private fun redirection(ctx: KashParser.RedirectionContext): Redirection {
        val hereDocOp =
            ctx.REDIR_HEREDOC() ?: ctx.REDIR_HEREDOC_STRIP()
        if (hereDocOp != null) {
            val opTok = hereDocOp.symbol as KashAntlrToken
            val redirTok = opTok.kashSource as Token.RedirOpTok
            val bodyNode = ctx.HEREDOC_BODY()!!
            val bodyTok = (bodyNode.symbol as KashAntlrToken).kashSource as Token.HereDocBodyTok
            val target =
                RedirTarget.HereDoc(
                    body = chunksToWord(bodyTok.parts, bodyTok.line),
                    stripTabs = bodyTok.stripTabs,
                    quoted = bodyTok.quoted,
                    delim = bodyTok.delim,
                )
            return Redirection(
                fd = redirTok.fd,
                operator = redirOpFor(redirTok.op),
                target = target,
                line = redirTok.line,
                fdVar = redirTok.fdVar,
            )
        }
        val opCtx = ctx.redirOpFile()!!
        val opTokNode = (opCtx.start as KashAntlrToken)
        val redirTok = opTokNode.kashSource as Token.RedirOpTok
        val wordTok = ctx.WORD()!!.symbol.kashWord()
        val target =
            when (redirTok.op) {
                ">&", "<&" -> {
                    // For move-fd `>&N-` / `<&N-`, the lexer leaves the
                    // trailing `-` on the operand. Strip it so the literal
                    // path picks up the digits as RedirTarget.Fd. For the
                    // expansion path (`>&${VAR}-`), the strip happens at
                    // runtime in applyRedirections.
                    var lit = literalText(wordTok)
                    if (redirTok.closeAfter && lit != null && lit.endsWith("-")) {
                        lit = lit.dropLast(1)
                    }
                    // Bare close-fd: `>&-` / `<&-` (operand literally `-`).
                    // Both forms close the fd; canonicalize as Fd(-1) so
                    // the deparser emits the spec form `>&-` regardless of
                    // input direction.
                    if (lit == "-") {
                        RedirTarget.Fd(-1)
                    } else {
                        val fd = lit?.toIntOrNull()
                        if (fd != null) RedirTarget.Fd(fd) else RedirTarget.File(wordTok.toWord())
                    }
                }

                else -> {
                    RedirTarget.File(wordTok.toWord())
                }
            }
        return Redirection(
            fd = redirTok.fd,
            operator = redirOpFor(redirTok.op),
            target = target,
            line = redirTok.line,
            closeAfter = redirTok.closeAfter,
            fdVar = redirTok.fdVar,
        )
    }

    private fun redirOpFor(op: String): RedirOp =
        when (op) {
            "<" -> RedirOp.INPUT
            ">" -> RedirOp.OUTPUT
            ">>" -> RedirOp.APPEND
            ">|" -> RedirOp.CLOBBER
            "<>" -> RedirOp.READ_WRITE
            ">&" -> RedirOp.DUP_OUT
            "<&" -> RedirOp.DUP_IN
            "&>" -> RedirOp.OUT_AND_ERR
            "&>>" -> RedirOp.OUT_AND_ERR_APPEND
            "<<<" -> RedirOp.HERESTRING
            "<<" -> RedirOp.HEREDOC
            "<<-" -> RedirOp.HEREDOC_STRIP
            else -> throw ParseException("unknown redirection $op")
        }

    private fun redirsAfter(ctxs: List<KashParser.RedirectionContext>): List<Redirection> = ctxs.map { redirection(it) }

    // -------- compoundList / body helpers --------

    private fun statementsOf(ctx: KashParser.CompoundListContext): List<Statement> =
        statementsFromChildren(ctx.children ?: emptyList())

    // -------- Helpers to recover Token payload from antlr terminal nodes --------

    private fun org.antlr.v4.kotlinruntime.Token.kashWord(): Token.WordTok =
        (this as KashAntlrToken).kashSource as Token.WordTok

    private fun org.antlr.v4.kotlinruntime.Token.kashAssign(): Token.AssignmentTok =
        (this as KashAntlrToken).kashSource as Token.AssignmentTok

    private fun org.antlr.v4.kotlinruntime.Token.kashArrayAssign(): Token.ArrayAssignTok =
        (this as KashAntlrToken).kashSource as Token.ArrayAssignTok

    private fun org.antlr.v4.kotlinruntime.Token.kashIndexedAssign(): Token.IndexedAssignTok =
        (this as KashAntlrToken).kashSource as Token.IndexedAssignTok

    /**
     * Render a Word token as a flat literal string when it's a plain identifier
     * or a backslash-escaped identifier. Bash accepts `for f\1 in …` at the
     * parse level — the post-escape literal `f1` reaches the runtime which
     * then diagnoses it (well, the source-form `f\1` per bash's error). To
     * give the runtime something to diagnose, accept Literal+Escaped chunks
     * here too. Quoted spans and expansions still come back as null —
     * those legitimately don't form a single identifier word.
     */
    private fun literalText(tok: Token.WordTok): String? {
        val sb = StringBuilder()
        for (p in tok.parts) {
            when (p) {
                is WordChunk.Literal -> sb.append(p.value)
                is WordChunk.Escaped -> sb.append('\\').append(p.value)
                else -> return null
            }
        }
        return sb.toString()
    }

    /**
     * Reconstruct a source-text view of a word, suitable for the
     * function-name slot (where `$var`, `<(:)` etc. must round-trip
     * verbatim because the function-name lex doesn't expand). Used
     * only for diagnosing invalid names — the validator downstream
     * checks the result against bash's identifier rules.
     */
    private fun reconstructWordSource(tok: Token.WordTok): String {
        val sb = StringBuilder()
        for (p in tok.parts) {
            when (p) {
                is WordChunk.Literal -> {
                    sb.append(p.value)
                }

                is WordChunk.Escaped -> {
                    sb.append('\\').append(p.value)
                }

                is WordChunk.SingleQuoted -> {
                    sb.append('\'').append(p.value).append('\'')
                }

                is WordChunk.DoubleQuoted -> {
                    sb.append('"')
                    for (inner in p.parts) sb.append(reconstructPart(inner))
                    sb.append('"')
                }

                is WordChunk.ParameterExpansion -> {
                    sb.append('$')
                    if (p.braced) sb.append('{').append(p.parameter).append('}') else sb.append(p.parameter)
                }

                is WordChunk.CommandSubstitution -> {
                    sb.append("$(").append(p.rawText).append(')')
                }

                is WordChunk.ProcessSubstitution -> {
                    sb
                        .append(p.direction)
                        .append('(')
                        .append(p.rawText)
                        .append(')')
                }

                is WordChunk.ArithmeticExpansion -> {
                    sb.append("\$((").append(p.rawText).append("))")
                }
            }
        }
        return sb.toString()
    }

    private fun reconstructPart(p: WordChunk): String =
        when (p) {
            is WordChunk.Literal -> p.value
            is WordChunk.Escaped -> "\\" + p.value
            else -> reconstructWordSource(Token.WordTok(listOf(p), 0))
        }

    private fun keywordWord(ctx: KashParser.AnyKeywordContext): Token.Keyword =
        (ctx.start as KashAntlrToken).kashSource as Token.Keyword

    /** "WORD or anyKeyword" -> single literal/keyword text, or null if neither resolves. */
    private fun wordOrKeywordText(
        word: org.antlr.v4.kotlinruntime.tree.TerminalNode?,
        keyword: KashParser.AnyKeywordContext?,
    ): String? =
        word?.let { literalText(it.symbol.kashWord()) }
            ?: keyword?.let { keywordWord(it).word }

    /** "WORD or anyKeyword" -> a [Word] node, or null if neither resolves. */
    private fun wordOrKeywordWord(
        word: org.antlr.v4.kotlinruntime.tree.TerminalNode?,
        keyword: KashParser.AnyKeywordContext?,
    ): Word? {
        word?.let { return it.symbol.kashWord().toWord() }
        keyword?.let {
            val kw = keywordWord(it)
            return Word(listOf(WordPart.Literal(kw.word)), kw.line)
        }
        return null
    }

    /**
     * Split an arithmetic for-loop header `init; cond; update` into a
     * triple. `;` at depth 0 separates sections; `$(...)` is skipped
     * as an opaque unit (using [com.accucodeai.kash.parser.findCmdSubClose]
     * for case-aware boundary finding, so `for (( $(case x in p) ... esac);; ))`
     * doesn't mis-split on the case-pattern `)`).
     */
    private fun splitArithFor(raw: String): Triple<String, String, String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var i = 0
        while (i < raw.length) {
            when (val c = raw[i]) {
                '$' if i + 1 < raw.length && raw[i + 1] == '(' &&
                    (i + 2 >= raw.length || raw[i + 2] != '(') -> {
                    val end =
                        com.accucodeai.kash.parser
                            .findCmdSubClose(raw, i + 2)
                    if (end < 0) {
                        // Unbalanced — copy rest verbatim and bail.
                        sb.append(raw, i, raw.length)
                        i = raw.length
                    } else {
                        sb.append(raw, i, end + 1)
                        i = end + 1
                    }
                }

                '$' if i + 1 < raw.length && raw[i + 1] == '{' -> {
                    // Brace-cmdsub `${ ... }` (bash 5.2 funsub) — skip
                    // as an opaque unit so a `)` inside the body (e.g.
                    // a case-pattern terminator) doesn't drive the
                    // section's depth counter negative.
                    val end =
                        com.accucodeai.kash.parser
                            .findBraceClose(raw, i + 2)
                    if (end < 0) {
                        sb.append(raw, i, raw.length)
                        i = raw.length
                    } else {
                        sb.append(raw, i, end + 1)
                        i = end + 1
                    }
                }

                '(' -> {
                    depth++
                    sb.append(c)
                    i++
                }

                ')' -> {
                    depth--
                    sb.append(c)
                    i++
                }

                ';' if depth == 0 -> {
                    // trimStart only — bash xtrace `(( i++  ))` preserves a
                    // trailing space inside the update section but strips
                    // any leading whitespace from each section.
                    parts += sb.toString().trimStart()
                    sb.clear()
                    i++
                }

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        parts += sb.toString().trimStart()
        // Bash requires exactly three semicolon-separated sections (any of
        // them may be empty). `for (( init; cond ))` (only one `;`) is a
        // syntax error in bash — kash used to fall through with an empty
        // update which silently turned `for (( i=0; "i<3" ))` into an
        // infinite loop instead of the expected diagnostic.
        if (parts.size > 3) throw ParseException("too many sections in C-style for header")
        if (parts.size < 3) throw ParseException("syntax error: arithmetic expression required")
        return Triple(parts[0], parts[1], parts[2])
    }
}
