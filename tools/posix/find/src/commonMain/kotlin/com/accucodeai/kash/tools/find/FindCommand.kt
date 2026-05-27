package com.accucodeai.kash.tools.find

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.EmptySuspendSource
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.matchGlob
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.Paths

/**
 * POSIX `find` — recursive filesystem walker.
 *
 * Subset implemented:
 *   - One or more starting paths (`find . path/to/x`).
 *   - Primaries: `-name`, `-iname`, `-type [fd]`, `-print`, `-print0`,
 *     `-empty`, `-size N[ckMG]`, `-mtime N` (with optional `+`/`-` prefix),
 *     `-prune`, `-exec UTIL ... ;` (per-match), `-exec UTIL ... +`
 *     (batched, auto-flushed every BATCH_FLUSH_LIMIT paths),
 *     `-maxdepth N`, `-mindepth N`.
 *   - Operators: `!`/`-not`, `-a`/`-and` (implicit between adjacent
 *     primaries), `-o`/`-or`, grouping with `(` / `)`. POSIX precedence:
 *     `-a` binds tighter than `-o`.
 *
 * `-exec` runs via [CommandContext.utilityRunner] (no shell, no intrinsics).
 * For shell behavior write `-exec sh -c '...' _ {} \;` — `sh` is itself a
 * registered utility, and positional operands flow through to `$0..$N`
 * inside the sub-script.
 *
 * Exit status: 0 if no errors; non-zero if any path was unreadable or any
 * `-exec` invocation returned non-zero.
 */
public class FindCommand :
    Command,
    CommandSpec {
    override val name: String = "find"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val (paths, exprStart) = collectStartPaths(args)
        if (paths.isEmpty()) {
            ctx.stderr.writeUtf8("find: missing path\n")
            return CommandResult(exitCode = 1)
        }

        val program =
            try {
                Parser(args, exprStart).parseProgram()
            } catch (e: ParseError) {
                ctx.stderr.writeUtf8("find: ${e.message}\n")
                return CommandResult(exitCode = 2)
            }

        var aggregateExit = 0
        val onError = { rc: Int -> if (rc != 0) aggregateExit = rc }

        for (root in paths) {
            if (!ctx.process.fs.exists(Paths.resolve(ctx.process.cwd, root))) {
                ctx.stderr.writeUtf8("find: $root: No such file or directory\n")
                aggregateExit = 1
                continue
            }
            walk(ctx.process.fs, ctx.process.cwd, root, 0, program, ctx, onError)
        }

        // Flush any pending `-exec ... +` batches.
        for (batch in program.batchedExecs) {
            if (batch.pending.isEmpty()) continue
            val rc = runExec(ctx, batch.utility, batch.args, batch.pending)
            onError(rc)
            batch.pending.clear()
        }

        return CommandResult(exitCode = aggregateExit)
    }

    private fun collectStartPaths(args: List<String>): Pair<List<String>, Int> {
        val paths = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            // First arg that looks like a primary, operator, or group end ends the path list.
            if (a.startsWith("-") || a == "!" || a == "(" || a == ")") break
            paths.add(a)
            i++
        }
        return paths to i
    }

    private suspend fun walk(
        fs: FileSystem,
        cwd: String,
        // Path as the user supplied it (possibly relative). Carried through walk
        // so `-print`/`-exec` see the same prefix the user typed (`find .` →
        // `./foo`, not `/home/user/foo`).
        displayPath: String,
        depth: Int,
        program: FindProgram,
        ctx: CommandContext,
        onError: (Int) -> Unit,
    ) {
        if (program.maxDepth != null && depth > program.maxDepth) return
        val absPath = Paths.resolve(cwd, displayPath)
        val isDir = fs.isDirectory(absPath)
        val shouldEvaluate = program.minDepth == null || depth >= program.minDepth
        val pruned = if (shouldEvaluate) program.evaluate(displayPath, absPath, isDir, ctx, onError) else false
        if (pruned) return
        if (isDir) {
            val children =
                try {
                    fs.list(absPath)
                } catch (_: Throwable) {
                    ctx.stderr.writeUtf8("find: $displayPath: cannot read\n")
                    onError(1)
                    return
                }
            for (child in children) {
                val childDisplay = if (displayPath == "/") "/$child" else "$displayPath/$child"
                walk(fs, cwd, childDisplay, depth + 1, program, ctx, onError)
            }
        }
    }
}

// ---- Parser ----

internal class ParseError(
    message: String,
) : RuntimeException(message)

/**
 * Recursive-descent parser for find expressions.
 *
 * Grammar (POSIX-shape; `-a` binds tighter than `-o`):
 *   program  = or
 *   or       = and ( ('-o' | '-or') and )*
 *   and      = not ( ('-a' | '-and')? not )*    -- implicit AND
 *   not      = ('!' | '-not')? primary
 *   primary  = '(' or ')' | option | predicate | action
 *
 * `-maxdepth` / `-mindepth` are global options (not part of the predicate
 * tree); they're stripped out and stored on [FindProgram].
 */
internal class Parser(
    private val tokens: List<String>,
    private var i: Int,
) {
    private var maxDepth: Int? = null
    private var minDepth: Int? = null
    private val batchedExecs = mutableListOf<ExecBatch>()

    suspend fun parseProgram(): FindProgram {
        val expr = if (eof()) Predicate(True) else parseOr()
        if (!eof()) throw ParseError("unexpected token: '${peek()}'")
        val withDefault =
            if (expr.hasAction) {
                expr
            } else {
                And(expr, Predicate(Print(nulTerminated = false)))
            }
        return FindProgram(withDefault, maxDepth, minDepth, batchedExecs)
    }

    private suspend fun parseOr(): FindExpr {
        var left = parseAnd()
        while (!eof() && (peek() == "-o" || peek() == "-or")) {
            consume()
            val right = parseAnd()
            left = Or(left, right)
        }
        return left
    }

    private suspend fun parseAnd(): FindExpr {
        var left = parseNot()
        while (!eof() && peek() != "-o" && peek() != "-or" && peek() != ")") {
            if (peek() == "-a" || peek() == "-and") consume()
            val right = parseNot()
            left = And(left, right)
        }
        return left
    }

    private suspend fun parseNot(): FindExpr {
        if (!eof() && (peek() == "!" || peek() == "-not")) {
            consume()
            return Not(parseNot())
        }
        return parsePrimary()
    }

    private suspend fun parsePrimary(): FindExpr {
        if (eof()) throw ParseError("missing expression")
        return when (val tok = peek()) {
            "(" -> {
                consume()
                val inner = parseOr()
                if (eof() || peek() != ")") throw ParseError("missing ')'")
                consume()
                inner
            }

            "-maxdepth" -> {
                consume()
                val n =
                    consumeOrFail("-maxdepth").toIntOrNull()
                        ?: throw ParseError("-maxdepth: invalid argument")
                // Take the tightest constraint if specified more than once.
                maxDepth = maxDepth?.let { minOf(it, n) } ?: n
                Predicate(True)
            }

            "-mindepth" -> {
                consume()
                val n =
                    consumeOrFail("-mindepth").toIntOrNull()
                        ?: throw ParseError("-mindepth: invalid argument")
                minDepth = minDepth?.let { maxOf(it, n) } ?: n
                Predicate(True)
            }

            "-name" -> {
                consume()
                Predicate(NameMatch(consumeOrFail("-name"), caseInsensitive = false))
            }

            "-iname" -> {
                consume()
                Predicate(NameMatch(consumeOrFail("-iname"), caseInsensitive = true))
            }

            "-type" -> {
                consume()
                val t = consumeOrFail("-type")
                if (t !in setOf("f", "d")) {
                    throw ParseError("-type: unsupported value '$t' (only f/d)")
                }
                Predicate(TypeMatch(t[0]))
            }

            "-print" -> {
                consume()
                Predicate(Print(nulTerminated = false))
            }

            "-print0" -> {
                consume()
                Predicate(Print(nulTerminated = true))
            }

            "-empty" -> {
                consume()
                Predicate(Empty)
            }

            "-prune" -> {
                consume()
                Predicate(Prune)
            }

            "-size" -> {
                consume()
                Predicate(SizeMatch.parse(consumeOrFail("-size")))
            }

            "-mtime" -> {
                consume()
                Predicate(MtimeMatch.parse(consumeOrFail("-mtime")))
            }

            "-exec" -> {
                consume()
                val collected = mutableListOf<String>()
                var terminator: String? = null
                while (!eof()) {
                    val t = peek()
                    if (t == ";" || t == "+") {
                        terminator = t
                        consume()
                        break
                    }
                    collected.add(consume())
                }
                if (terminator == null) throw ParseError("-exec: missing ';' or '+'")
                if (collected.isEmpty()) throw ParseError("-exec: missing utility")
                val utility = collected.first()
                val tail = collected.drop(1)
                if (terminator == "+") {
                    val batch = ExecBatch(utility, tail)
                    batchedExecs.add(batch)
                    Predicate(batch)
                } else {
                    Predicate(ExecOne(utility, tail))
                }
            }

            ")" -> {
                throw ParseError("unexpected ')'")
            }

            else -> {
                throw ParseError("unknown primary or operand: $tok")
            }
        }
    }

    private fun eof() = i >= tokens.size

    private fun peek() = tokens[i]

    private suspend fun consume(): String = tokens[i++]

    private suspend fun consumeOrFail(flag: String): String {
        if (eof()) throw ParseError("$flag: missing argument")
        return consume()
    }
}

// ---- AST ----

/** Expression nodes — combinators over [Predicate]s. */
internal sealed interface FindExpr

internal data class And(
    val left: FindExpr,
    val right: FindExpr,
) : FindExpr

internal data class Or(
    val left: FindExpr,
    val right: FindExpr,
) : FindExpr

internal data class Not(
    val expr: FindExpr,
) : FindExpr

internal data class Predicate(
    val leaf: FindLeaf,
) : FindExpr

/** Leaf nodes — predicates and actions. */
internal sealed interface FindLeaf {
    val isAction: Boolean
}

internal data object True : FindLeaf {
    override val isAction = false
}

internal data class NameMatch(
    val pattern: String,
    val caseInsensitive: Boolean,
) : FindLeaf {
    override val isAction = false
}

internal data class TypeMatch(
    val kind: Char,
) : FindLeaf {
    override val isAction = false
}

internal data class Print(
    val nulTerminated: Boolean,
) : FindLeaf {
    override val isAction = true
}

internal data class ExecOne(
    val utility: String,
    val args: List<String>,
) : FindLeaf {
    override val isAction = true
}

internal class ExecBatch(
    val utility: String,
    val args: List<String>,
    val pending: MutableList<String> = mutableListOf(),
) : FindLeaf {
    override val isAction = true
}

/** True if file is 0 bytes, or directory contains no entries. */
internal data object Empty : FindLeaf {
    override val isAction = false
}

/**
 * Always true; signals the walker to skip descending into the current
 * directory. Standalone `find … -prune` matches every entry without
 * recursion — usually combined with `-name X -prune -o …` to skip subtrees.
 */
internal data object Prune : FindLeaf {
    override val isAction = false
}

/** How a numeric primary compares its argument against the file's value. */
internal enum class Compare { EXACT, GREATER, LESSER }

/**
 * `-size N[ckMG]`. POSIX: bare `N` counts 512-byte blocks, rounding up.
 * `c` = bytes, `k` = 1024, `M` = 1024², `G` = 1024³. `+N`/`-N` for more/less.
 */
internal data class SizeMatch(
    val bytes: Long,
    val compare: Compare,
) : FindLeaf {
    override val isAction = false

    companion object {
        suspend fun parse(raw: String): SizeMatch {
            val (compare, rest) =
                when {
                    raw.startsWith("+") -> Compare.GREATER to raw.drop(1)
                    raw.startsWith("-") -> Compare.LESSER to raw.drop(1)
                    else -> Compare.EXACT to raw
                }
            val (digits, suffix) =
                if (rest.isNotEmpty() && !rest.last().isDigit()) {
                    rest.dropLast(1) to rest.last()
                } else {
                    rest to ' '
                }
            val n = digits.toLongOrNull() ?: throw ParseError("-size: invalid argument: $raw")
            val bytes =
                when (suffix) {
                    'c' -> n
                    'k' -> n * 1024L
                    'M' -> n * 1024L * 1024L
                    'G' -> n * 1024L * 1024L * 1024L
                    ' ' -> n * 512L
                    else -> throw ParseError("-size: unknown suffix '$suffix' (use c/k/M/G)")
                }
            return SizeMatch(bytes, compare)
        }
    }
}

/**
 * `-mtime N`. Compares against file mtime measured in 24-hour periods
 * before "now". `+N` = older than N days, `-N` = newer than N days.
 */
internal data class MtimeMatch(
    val days: Long,
    val compare: Compare,
) : FindLeaf {
    override val isAction = false

    companion object {
        suspend fun parse(raw: String): MtimeMatch {
            val (compare, rest) =
                when {
                    raw.startsWith("+") -> Compare.GREATER to raw.drop(1)
                    raw.startsWith("-") -> Compare.LESSER to raw.drop(1)
                    else -> Compare.EXACT to raw
                }
            val n = rest.toLongOrNull() ?: throw ParseError("-mtime: invalid argument: $raw")
            return MtimeMatch(n, compare)
        }
    }
}

/** Max paths buffered per `-exec UTIL {} +` batch before an auto-flush. */
internal const val BATCH_FLUSH_LIMIT: Int = 1024

/**
 * Fully-parsed find program: predicate tree + global options.
 *
 * [batchedExecs] is shared with the tree's [ExecBatch] leaves so the
 * caller can flush them after the walk completes.
 */
internal class FindProgram(
    val expr: FindExpr,
    val maxDepth: Int?,
    val minDepth: Int?,
    val batchedExecs: List<ExecBatch>,
    /** Wall-clock seconds since epoch, used by `-mtime`. */
    val nowSeconds: () -> Long = {
        kotlin.time.Clock.System
            .now()
            .epochSeconds
    },
) {
    /**
     * Evaluates against one (path, isDir) and returns whether this entry
     * triggered `-prune` (walker must not descend).
     */
    suspend fun evaluate(
        displayPath: String,
        absPath: String,
        isDir: Boolean,
        ctx: CommandContext,
        onError: (Int) -> Unit,
    ): Boolean {
        val state = EvalState(nowSeconds())
        eval(expr, displayPath, absPath, isDir, ctx, onError, state)
        return state.prune
    }
}

/** Mutable per-evaluation flags; currently just the prune signal. */
internal class EvalState(
    val nowSeconds: Long,
) {
    var prune: Boolean = false
}

/**
 * Walks the predicate tree with POSIX truth semantics: `-a` short-circuits
 * on false, `-o` short-circuits on true, actions count toward truth via
 * their exit (0 = true, non-zero = false), and `-not` flips it.
 */
private suspend fun eval(
    expr: FindExpr,
    displayPath: String,
    absPath: String,
    isDir: Boolean,
    ctx: CommandContext,
    onError: (Int) -> Unit,
    state: EvalState,
): Boolean =
    when (expr) {
        is And -> {
            eval(expr.left, displayPath, absPath, isDir, ctx, onError, state) &&
                eval(expr.right, displayPath, absPath, isDir, ctx, onError, state)
        }

        is Or -> {
            eval(expr.left, displayPath, absPath, isDir, ctx, onError, state) ||
                eval(expr.right, displayPath, absPath, isDir, ctx, onError, state)
        }

        is Not -> {
            !eval(expr.expr, displayPath, absPath, isDir, ctx, onError, state)
        }

        is Predicate -> {
            evalLeaf(expr.leaf, displayPath, absPath, isDir, ctx, onError, state)
        }
    }

private suspend fun evalLeaf(
    leaf: FindLeaf,
    displayPath: String,
    absPath: String,
    isDir: Boolean,
    ctx: CommandContext,
    onError: (Int) -> Unit,
    state: EvalState,
): Boolean =
    when (leaf) {
        True -> {
            true
        }

        is NameMatch -> {
            val base = displayPath.substringAfterLast('/').ifEmpty { displayPath }
            val pat = if (leaf.caseInsensitive) leaf.pattern.lowercase() else leaf.pattern
            val txt = if (leaf.caseInsensitive) base.lowercase() else base
            matchGlob(pat, txt)
        }

        is TypeMatch -> {
            (leaf.kind == 'd' && isDir) || (leaf.kind == 'f' && !isDir)
        }

        Empty -> {
            if (isDir) {
                runCatching {
                    ctx.process.fs
                        .list(absPath)
                        .isEmpty()
                }.getOrDefault(false)
            } else {
                runCatching {
                    ctx.process.fs
                        .stat(absPath)
                        .size == 0L
                }.getOrDefault(false)
            }
        }

        Prune -> {
            // `-prune` always evaluates true; side effect tells the walker
            // not to descend into this directory.
            state.prune = true
            true
        }

        is SizeMatch -> {
            val size =
                runCatching {
                    ctx.process.fs
                        .stat(absPath)
                        .size
                }.getOrDefault(0L)
            when (leaf.compare) {
                Compare.EXACT -> size == leaf.bytes
                Compare.GREATER -> size > leaf.bytes
                Compare.LESSER -> size < leaf.bytes
            }
        }

        is MtimeMatch -> {
            val mtime =
                runCatching {
                    ctx.process.fs
                        .stat(absPath)
                        .mtimeEpochSeconds
                }.getOrDefault(0L)
            val ageDays = (state.nowSeconds - mtime) / 86_400L
            when (leaf.compare) {
                Compare.EXACT -> ageDays == leaf.days
                Compare.GREATER -> ageDays > leaf.days
                Compare.LESSER -> ageDays < leaf.days
            }
        }

        is Print -> {
            if (leaf.nulTerminated) {
                ctx.stdout.writeUtf8(displayPath)
                ctx.stdout.writeBytes(byteArrayOf(0))
            } else {
                ctx.stdout.writeLine(displayPath)
            }
            true
        }

        is ExecOne -> {
            val rc = runExec(ctx, leaf.utility, leaf.args, listOf(displayPath))
            onError(rc)
            rc == 0
        }

        is ExecBatch -> {
            leaf.pending.add(displayPath)
            if (leaf.pending.size >= BATCH_FLUSH_LIMIT) {
                val rc = runExec(ctx, leaf.utility, leaf.args, leaf.pending.toList())
                onError(rc)
                leaf.pending.clear()
            }
            true
        }
    }

internal suspend fun runExec(
    ctx: CommandContext,
    utility: String,
    args: List<String>,
    paths: List<String>,
): Int {
    val runner =
        ctx.utilityRunner ?: run {
            ctx.stderr.writeUtf8("find: -exec: not supported in this context\n")
            return 1
        }
    // POSIX: `{}` alone → replaced by the pathname; in `+` form `{}` is
    // replaced by the whole batch as separate args.
    val finalArgs =
        if (paths.size <= 1) {
            args.map { arg -> if (arg == "{}") paths.firstOrNull() ?: "" else arg }
        } else {
            val out = mutableListOf<String>()
            for (arg in args) {
                if (arg == "{}") out.addAll(paths) else out.add(arg)
            }
            out
        }
    return runner.run(
        name = utility,
        args = finalArgs,
        stdin = EmptySuspendSource,
        stdout = ctx.stdout,
        stderr = ctx.stderr,
    )
}

/** Whether the expression performs any action (`-print`, `-print0`, `-exec`). */
internal val FindExpr.hasAction: Boolean
    get() =
        when (this) {
            is And -> left.hasAction || right.hasAction
            is Or -> left.hasAction || right.hasAction
            is Not -> expr.hasAction
            is Predicate -> leaf.isAction
        }
