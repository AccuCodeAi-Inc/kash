package com.accucodeai.kash.tools.awk.eval

import com.accucodeai.kash.tools.awk.AwkInputFile
import com.accucodeai.kash.tools.awk.AwkLineReader
import com.accucodeai.kash.tools.awk.AwkOptions
import com.accucodeai.kash.tools.awk.AwkOutputMode
import com.accucodeai.kash.tools.awk.AwkOutputWriter
import com.accucodeai.kash.tools.awk.ast.ArrayRef
import com.accucodeai.kash.tools.awk.ast.Assign
import com.accucodeai.kash.tools.awk.ast.AssignOp
import com.accucodeai.kash.tools.awk.ast.AwkExpr
import com.accucodeai.kash.tools.awk.ast.AwkProgram
import com.accucodeai.kash.tools.awk.ast.AwkStmt
import com.accucodeai.kash.tools.awk.ast.BinOp
import com.accucodeai.kash.tools.awk.ast.Binary
import com.accucodeai.kash.tools.awk.ast.BlockStmt
import com.accucodeai.kash.tools.awk.ast.BreakStmt
import com.accucodeai.kash.tools.awk.ast.ContinueStmt
import com.accucodeai.kash.tools.awk.ast.DeleteStmt
import com.accucodeai.kash.tools.awk.ast.DoWhileStmt
import com.accucodeai.kash.tools.awk.ast.ExitStmt
import com.accucodeai.kash.tools.awk.ast.ExprStmt
import com.accucodeai.kash.tools.awk.ast.FieldRef
import com.accucodeai.kash.tools.awk.ast.ForInStmt
import com.accucodeai.kash.tools.awk.ast.ForStmt
import com.accucodeai.kash.tools.awk.ast.FunCall
import com.accucodeai.kash.tools.awk.ast.FunctionDef
import com.accucodeai.kash.tools.awk.ast.GetlineExpr
import com.accucodeai.kash.tools.awk.ast.GetlineSource
import com.accucodeai.kash.tools.awk.ast.Grouping
import com.accucodeai.kash.tools.awk.ast.IfStmt
import com.accucodeai.kash.tools.awk.ast.InExpr
import com.accucodeai.kash.tools.awk.ast.NextFileStmt
import com.accucodeai.kash.tools.awk.ast.NextStmt
import com.accucodeai.kash.tools.awk.ast.NumLit
import com.accucodeai.kash.tools.awk.ast.OutputTarget
import com.accucodeai.kash.tools.awk.ast.Pattern
import com.accucodeai.kash.tools.awk.ast.PrintStmt
import com.accucodeai.kash.tools.awk.ast.PrintfStmt
import com.accucodeai.kash.tools.awk.ast.RegexLit
import com.accucodeai.kash.tools.awk.ast.ReturnStmt
import com.accucodeai.kash.tools.awk.ast.Rule
import com.accucodeai.kash.tools.awk.ast.StrLit
import com.accucodeai.kash.tools.awk.ast.Ternary
import com.accucodeai.kash.tools.awk.ast.Unary
import com.accucodeai.kash.tools.awk.ast.UnaryOp
import com.accucodeai.kash.tools.awk.ast.VarRef
import com.accucodeai.kash.tools.awk.ast.WhileStmt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.pow

/**
 * Interpreter for awk. The driver pulls records from an input sequence,
 * splits them into fields per `FS`, then runs every matching rule's action
 * for each record. `BEGIN` rules run before the first record; `END` rules
 * run after the last (or after an explicit `exit`).
 *
 * The visitor is suspending end-to-end. The sink (`emit`), openers
 * ([fileOpener]/[outputOpener]/[cmdOpener]), and [AwkLineReader] /
 * [AwkOutputWriter] all take `suspend` — so `print > file`,
 * `getline < file`, `print | "cmd"`, `cmd | getline`, and `system()`
 * can drive real I/O through a coroutine-native host (FS,
 * `ShellRunner`, `AsyncPipe`) without `runBlocking`.
 *
 * Control flow uses Kotlin exceptions ([BreakSignal], [ContinueSignal],
 * [NextSignal], [NextFileSignal], [ExitSignal], [ReturnSignal]) — the cost
 * of throwing on every loop iteration is non-trivial but easier to read
 * than threading a "control-flow state" value through every visitor.
 */
internal class AwkInterpreter(
    private val program: AwkProgram,
    private val options: AwkOptions,
    private val fileOpener: (suspend (String) -> AwkLineReader?)? = null,
    private val outputOpener: (suspend (String, AwkOutputMode) -> AwkOutputWriter?)? = null,
    private val cmdOpener: (suspend (String) -> AwkLineReader?)? = null,
    private val systemHook: (suspend (String) -> Int)? = null,
) {
    private val scalars: MutableMap<String, AwkValue> = HashMap()
    private val arrays: MutableMap<String, MutableMap<String, AwkValue>> = HashMap()
    private val functions: Map<String, FunctionDef> =
        program.items.filterIsInstance<FunctionDef>().associateBy { it.name }
    private val rules: List<Rule> = program.items.filterIsInstance<Rule>()

    // Field cache. Index 0 = $0 (whole record). Lazily recomposed if a
    // field assignment dirties them.
    private var fields: MutableList<String> = mutableListOf()

    init {
        // Default special-variable values per POSIX, then overlay -v pre-assignments.
        scalars["FS"] = StrVal(options.fieldSeparator ?: " ")
        scalars["OFS"] = StrVal(" ")
        scalars["ORS"] = StrVal("\n")
        scalars["RS"] = StrVal("\n")
        scalars["NR"] = NumVal(0.0)
        scalars["NF"] = NumVal(0.0)
        scalars["FNR"] = NumVal(0.0)
        scalars["SUBSEP"] = StrVal("")
        scalars["CONVFMT"] = StrVal("%.6g")
        scalars["OFMT"] = StrVal("%.6g")
        scalars["FILENAME"] = StrVal("")
        if (options.environ.isNotEmpty()) {
            val env = HashMap<String, AwkValue>(options.environ.size)
            for ((k, v) in options.environ) env[k] = stringValueWithHint(v)
            arrays["ENVIRON"] = env
        }
        for ((k, v) in options.preAssignments) scalars[k] = stringValueWithHint(v)
    }

    private fun ofmt(): String = (scalars["OFMT"] ?: StrVal("%.6g")).toAwkString("%.6g")

    private fun convfmt(): String = (scalars["CONVFMT"] ?: StrVal("%.6g")).toAwkString("%.6g")

    fun run(input: Sequence<String>): Flow<String> = runFiles(sequenceOf(AwkInputFile("", input)))

    fun runFiles(files: Sequence<AwkInputFile>): Flow<String> =
        flow {
            // Buffer raw write chunks (`print` includes its ORS; `printf`
            // emits exactly the bytes the format produced). We yield a
            // chunk to the consumer only when a `\n` is seen, so a
            // sequence of `printf "%d", 1; printf " %s\n", "ok"` appears
            // as a single output line `"1 ok\n"` rather than two
            // separate elements. ORS-less `printf` runs without a
            // trailing newline are flushed at END.
            val pending = StringBuilder()
            val previousSink = sink
            sink = { text -> pending.append(text) }

            suspend fun flushLines() {
                if (pending.isEmpty()) return
                val text = pending.toString()
                pending.clear()
                var idx = 0
                while (idx < text.length) {
                    val nl = text.indexOf('\n', idx)
                    if (nl < 0) {
                        pending.append(text, idx, text.length)
                        break
                    }
                    emit(text.substring(idx, nl + 1))
                    idx = nl + 1
                }
            }

            suspend fun flushAll() {
                flushLines()
                if (pending.isNotEmpty()) {
                    emit(pending.toString())
                    pending.clear()
                }
            }
            val cursor = MultiFileCursor(files.iterator())
            mainCursor = cursor
            try {
                val beginExited = runBegin()
                flushLines()
                if (beginExited) {
                    runEnd()
                    flushAll()
                    return@flow
                }
                while (true) {
                    val rec = cursor.next() ?: break
                    scalars["NR"] = NumVal(((scalars["NR"] ?: NumVal(0.0)).toAwkNumber()) + 1)
                    scalars["FNR"] = NumVal(cursor.fnr.toDouble())
                    scalars["FILENAME"] = StrVal(cursor.filename)
                    splitInto(rec)
                    try {
                        for (rule in rules) {
                            if (matchPattern(rule.pattern, isBegin = false, isEnd = false)) {
                                execute(rule.action ?: BlockStmt(listOf(PrintStmt(emptyList()))))
                            }
                        }
                    } catch (_: NextSignal) {
                        // continue with next record
                    } catch (_: NextFileSignal) {
                        // Skip the rest of the current file; the next
                        // iteration pulls from the next file (if any).
                        cursor.skipCurrentFile()
                    } catch (e: ExitSignal) {
                        scalars["__exitCode"] = NumVal(e.code.toDouble())
                        flushLines()
                        runEnd()
                        flushAll()
                        return@flow
                    }
                    flushLines()
                }
                runEnd()
                flushAll()
            } finally {
                sink = previousSink
                mainCursor = null
                for (r in openFiles.values) {
                    try {
                        r.close()
                    } catch (_: Throwable) {
                        // best-effort cleanup
                    }
                }
                openFiles.clear()
                for (r in openCommands.values) {
                    try {
                        r.close()
                    } catch (_: Throwable) {
                        // best-effort cleanup
                    }
                }
                openCommands.clear()
                for (w in openOutputs.values) {
                    try {
                        w.close()
                    } catch (_: Throwable) {
                        // best effort
                    }
                }
                openOutputs.clear()
            }
        }

    /** Active output sink. Swapped during `run` so PrintStmt routes into
     *  the streaming buffer that drains into the flow collector. Always
     *  set while [runFiles] is in scope; calling print outside of a run
     *  throws an NPE (which is the intended fail-fast). */
    private var sink: (suspend (String) -> Unit) = { /* set in runFiles */ }

    /** The main input cursor while a run is in progress. Bare `getline`
     *  pulls from this; it advances across input files transparently
     *  and exposes the current FILENAME / FNR. Null between runs and
     *  during END-time flush. */
    private var mainCursor: MultiFileCursor? = null

    /**
     * Evaluate a `getline` expression. Returns NumVal(1) on success,
     * NumVal(0) at EOF, NumVal(-1) on error.
     *
     * Forms supported:
     *  - bare `getline`            — pulls next record into $0, updates NF/NR/FNR
     *  - `getline var`             — pulls next record into var, updates NR/FNR (NOT NF)
     *  - `getline < file`          — opens file (lazily), pulls into $0, updates NF
     *  - `getline var < file`      — pulls into var
     *
     *  - `cmd | getline [var]`     — spawns `cmd` via [cmdOpener]; reads its
     *                                  stdout line-by-line into $0 or var.
     */
    private suspend fun evalGetline(g: GetlineExpr): AwkValue {
        when (val src = g.source) {
            GetlineSource.MainInput -> {
                val c = mainCursor ?: return NumVal(0.0)
                val rec = c.next() ?: return NumVal(0.0)
                if (g.target == null) {
                    splitInto(rec)
                } else {
                    storeLval(g.target, stringValueWithHint(rec))
                }
                scalars["NR"] = NumVal((scalars["NR"] ?: NumVal(0.0)).toAwkNumber() + 1)
                scalars["FNR"] = NumVal(c.fnr.toDouble())
                scalars["FILENAME"] = StrVal(c.filename)
                return NumVal(1.0)
            }

            is GetlineSource.FromFile -> {
                val path = evalExpr(src.path).toAwkString(convfmt())
                val reader = openInputFile(path) ?: return NumVal(-1.0)
                val rec = reader.readLine() ?: return NumVal(0.0)
                if (g.target == null) {
                    splitInto(rec)
                } else {
                    storeLval(g.target, stringValueWithHint(rec))
                }
                scalars["NR"] = NumVal((scalars["NR"] ?: NumVal(0.0)).toAwkNumber() + 1)
                return NumVal(1.0)
            }

            is GetlineSource.FromCommand -> {
                val cmd = evalExpr(src.cmd).toAwkString(convfmt())
                val reader = openCommand(cmd) ?: return NumVal(-1.0)
                val rec = reader.readLine() ?: return NumVal(0.0)
                if (g.target == null) {
                    splitInto(rec)
                } else {
                    storeLval(g.target, stringValueWithHint(rec))
                }
                scalars["NR"] = NumVal((scalars["NR"] ?: NumVal(0.0)).toAwkNumber() + 1)
                return NumVal(1.0)
            }
        }
    }

    /** Per-path line iterators for `getline < file`. Opened lazily, kept
     *  open across calls so the file is read sequentially. */
    private val openFiles: MutableMap<String, AwkLineReader> = HashMap()

    /** Per-command line iterators for `cmd | getline`. One spawn per
     *  unique command string; subsequent reads pull the next line. */
    private val openCommands: MutableMap<String, AwkLineReader> = HashMap()

    private suspend fun openInputFile(path: String): AwkLineReader? {
        openFiles[path]?.let { return it }
        val reader = fileOpener?.invoke(path) ?: return null
        openFiles[path] = reader
        return reader
    }

    private suspend fun openCommand(cmd: String): AwkLineReader? {
        openCommands[cmd]?.let { return it }
        val reader = cmdOpener?.invoke(cmd) ?: return null
        openCommands[cmd] = reader
        return reader
    }

    /** Returns true if a BEGIN block called `exit` — caller should skip
     *  the main record loop and go straight to END (POSIX). */
    private suspend fun runBegin(): Boolean {
        for (rule in rules) {
            if (rule.pattern is Pattern.Begin) {
                try {
                    execute(rule.action ?: BlockStmt(emptyList()))
                } catch (e: ExitSignal) {
                    scalars["__exitCode"] = NumVal(e.code.toDouble())
                    return true
                }
            }
        }
        return false
    }

    private suspend fun runEnd() {
        for (rule in rules) {
            if (rule.pattern is Pattern.End) {
                try {
                    execute(rule.action ?: BlockStmt(emptyList()))
                } catch (e: ExitSignal) {
                    scalars["__exitCode"] = NumVal(e.code.toDouble())
                    return
                }
            }
        }
    }

    /** Per-Range active state. Range patterns toggle on a `from` match
     *  and off after a `to` match (inclusive). */
    private val rangeActive: MutableMap<Pattern.Range, Boolean> = HashMap()

    /** A bare regex used as a pattern is an implicit `$0 ~ /re/`. */
    private suspend fun evalPatternExpr(e: AwkExpr): Boolean =
        if (e is RegexLit) {
            matchRegex(field(0), e.pattern)
        } else {
            evalExpr(e).toAwkBool()
        }

    private suspend fun matchPattern(
        p: Pattern,
        isBegin: Boolean,
        isEnd: Boolean,
    ): Boolean =
        when (p) {
            is Pattern.Always -> {
                !isBegin && !isEnd
            }

            is Pattern.Begin -> {
                isBegin
            }

            is Pattern.End -> {
                isEnd
            }

            is Pattern.Expr -> {
                evalPatternExpr(p.expr)
            }

            is Pattern.Range -> {
                // Toggled state machine — once `from` is true on some
                // record, the range is active through (and including)
                // the record where `to` becomes true. Each Range
                // pattern carries its own active state, keyed by
                // identity in `rangeActive`.
                when {
                    isBegin || isEnd -> {
                        false
                    }

                    rangeActive[p] == true -> {
                        if (evalPatternExpr(p.to)) rangeActive[p] = false
                        true
                    }

                    evalPatternExpr(p.from) -> {
                        // Start the range. If `to` also matches on the
                        // same record, the range starts and ends here.
                        rangeActive[p] = !evalPatternExpr(p.to)
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        }

    // -- Field handling -----------------------------------------------------

    private fun splitInto(record: String) {
        fields.clear()
        fields.add(record)
        val fs = (scalars["FS"] ?: StrVal(" ")).toAwkString(convfmt())
        val parts =
            when {
                record.isEmpty() -> {
                    emptyList()
                }

                fs == " " -> {
                    // POSIX default: split on runs of whitespace,
                    // leading/trailing whitespace stripped.
                    record.trim().split(Regex("""[ \t]+""")).filter { it.isNotEmpty() }
                }

                fs.length == 1 -> {
                    // Single-character separator, exact byte match.
                    record.split(fs)
                }

                else -> {
                    // Multi-char or regex FS — interpret as ERE.
                    record.split(Regex(fs))
                }
            }
        fields.addAll(parts)
        scalars["NF"] = NumVal((fields.size - 1).toDouble())
    }

    private fun field(idx: Int): String {
        if (idx == 0) return fields.getOrNull(0) ?: ""
        return fields.getOrNull(idx) ?: ""
    }

    /**
     * Assign to `NF`, with the POSIX side effects: truncate or extend
     * the field array, then rebuild `$0` from `$1..$NF` joined by `OFS`.
     */
    private fun setNF(newNf: Int) {
        if (newNf < 0) runtimeError("NF set to negative ($newNf)")
        // Ensure index 0 ($0) plus exactly newNf field slots.
        if (fields.isEmpty()) fields.add("")
        while (fields.size - 1 < newNf) fields.add("")
        while (fields.size - 1 > newNf) fields.removeAt(fields.size - 1)
        val ofs = (scalars["OFS"] ?: StrVal(" ")).toAwkString(convfmt())
        fields[0] = fields.drop(1).joinToString(ofs)
        scalars["NF"] = NumVal(newNf.toDouble())
    }

    private fun setField(
        idx: Int,
        value: String,
    ) {
        if (idx < 0) runtimeError("negative field index $idx")
        // Pad with empty fields if needed.
        while (fields.size <= idx) fields.add("")
        if (idx == 0) {
            fields[0] = value
            // Re-split per FS.
            val rest = value
            val saved = fields.toList()
            splitInto(rest)
            // splitInto sets fields[0] = rest already; we're done.
            return
        }
        fields[idx] = value
        // Recompose $0 from $1..$NF using OFS.
        val ofs = (scalars["OFS"] ?: StrVal(" ")).toAwkString(convfmt())
        fields[0] = fields.drop(1).joinToString(ofs)
        scalars["NF"] = NumVal((fields.size - 1).toDouble())
    }

    // -- Statements ---------------------------------------------------------

    private suspend fun execute(s: AwkStmt) {
        when (s) {
            is BlockStmt -> {
                for (inner in s.stmts) execute(inner)
            }

            is IfStmt -> {
                if (evalExpr(s.cond).toAwkBool()) execute(s.thenBranch) else s.elseBranch?.let { execute(it) }
            }

            is WhileStmt -> {
                while (evalExpr(s.cond).toAwkBool()) {
                    try {
                        execute(s.body)
                    } catch (_: BreakSignal) {
                        return
                    } catch (_: ContinueSignal) {
                        // continue evaluating cond
                    }
                }
            }

            is DoWhileStmt -> {
                do {
                    try {
                        execute(s.body)
                    } catch (_: BreakSignal) {
                        return
                    } catch (_: ContinueSignal) {
                        // fall through to cond
                    }
                } while (evalExpr(s.cond).toAwkBool())
            }

            is ForStmt -> {
                s.init?.let { execute(it) }
                while (s.cond?.let { evalExpr(it).toAwkBool() } != false) {
                    try {
                        execute(s.body)
                    } catch (_: BreakSignal) {
                        return
                    } catch (_: ContinueSignal) {
                        // fall through to step
                    }
                    s.step?.let { execute(it) }
                }
            }

            is ForInStmt -> {
                val arr = arrays[s.arrayName] ?: return
                // Snapshot keys so user-deletes during iteration don't crash us.
                val keys = arr.keys.toList()
                for (k in keys) {
                    scalars[s.varName] = StrVal(k)
                    try {
                        execute(s.body)
                    } catch (_: BreakSignal) {
                        return
                    } catch (_: ContinueSignal) {
                        // continue with next key
                    }
                }
            }

            is ExprStmt -> {
                evalExpr(s.expr)
            }

            is PrintStmt -> {
                doPrint(s)
            }

            is PrintfStmt -> {
                doPrintf(s)
            }

            is DeleteStmt -> {
                if (s.subscript == null) {
                    arrays.remove(s.arrayName)
                } else {
                    val key = arraySubscript(s.subscript)
                    arrays[s.arrayName]?.remove(key)
                }
            }

            is BreakStmt -> {
                throw BreakSignal
            }

            is ContinueStmt -> {
                throw ContinueSignal
            }

            is NextStmt -> {
                throw NextSignal
            }

            is NextFileStmt -> {
                throw NextFileSignal
            }

            is ExitStmt -> {
                val code = s.exprOrNull?.let { evalExpr(it).toAwkNumber().toInt() } ?: 0
                throw ExitSignal(code)
            }

            is ReturnStmt -> {
                throw ReturnSignal(s.exprOrNull?.let { evalExpr(it) } ?: Uninit)
            }
        }
    }

    private suspend fun doPrint(s: PrintStmt) {
        val ofs = (scalars["OFS"] ?: StrVal(" ")).toAwkString(convfmt())
        val ors = (scalars["ORS"] ?: StrVal("\n")).toAwkString(convfmt())
        val text =
            if (s.args.isEmpty()) {
                field(0)
            } else {
                val parts = mutableListOf<String>()
                for (a in s.args) parts += evalExpr(a).toAwkString(ofmt())
                parts.joinToString(ofs)
            }
        emitTo(s.output, text + ors)
    }

    private suspend fun doPrintf(s: PrintfStmt) {
        if (s.args.isEmpty()) runtimeError("printf called with no format string")
        val fmt = evalExpr(s.args[0]).toAwkString(ofmt())
        val rest = s.args.drop(1).map { evalExpr(it) }
        emitTo(s.output, formatPrintf(fmt, rest))
    }

    /** Track open output targets across statements so successive
     *  `print > "f"` calls append to the same writer (POSIX: a target
     *  is opened once and reused until closed). Keyed by
     *  `"$modePrefix:$path"` so `> "f"` and `>> "f"` don't share a
     *  writer. */
    private val openOutputs: MutableMap<String, AwkOutputWriter> = HashMap()

    private suspend fun emitTo(
        target: OutputTarget,
        text: String,
    ) {
        when (target) {
            OutputTarget.Stdout -> sink(text)
            is OutputTarget.ToFile -> resolveOutput(target.path, AwkOutputMode.Truncate, ">").write(text)
            is OutputTarget.AppendFile -> resolveOutput(target.path, AwkOutputMode.Append, ">>").write(text)
            is OutputTarget.ToPipe -> resolveOutput(target.cmd, AwkOutputMode.Pipe, "|").write(text)
        }
    }

    private suspend fun resolveOutput(
        pathExpr: AwkExpr,
        mode: AwkOutputMode,
        prefix: String,
    ): AwkOutputWriter {
        val path = evalExpr(pathExpr).toAwkString(convfmt())
        val key = "$prefix:$path"
        openOutputs[key]?.let { return it }
        val opener = outputOpener ?: runtimeError("output redirection not supported in this context")
        val w = opener(path, mode) ?: runtimeError("cannot open `$path` for $mode")
        openOutputs[key] = w
        return w
    }

    // -- Expressions --------------------------------------------------------

    private suspend fun evalExpr(e: AwkExpr): AwkValue =
        when (e) {
            is NumLit -> {
                NumVal(e.value)
            }

            is StrLit -> {
                StrVal(e.value)
            }

            is RegexLit -> {
                // Bare regex outside a match context implicitly matches against $0.
                NumVal(if (matchRegex(field(0), e.pattern)) 1.0 else 0.0)
            }

            is Grouping -> {
                evalExpr(e.inner)
            }

            is VarRef -> {
                scalars[e.name] ?: Uninit
            }

            is FieldRef -> {
                val idx = evalExpr(e.index).toAwkNumber().toInt()
                stringValueWithHint(field(idx))
            }

            is ArrayRef -> {
                val key = arraySubscript(e.subscript)
                arrays.getOrPut(e.name) { HashMap() }[key] ?: Uninit
            }

            is InExpr -> {
                val key = arraySubscript(e.subscript)
                val arr = arrays[e.arrayName]
                NumVal(if (arr != null && key in arr) 1.0 else 0.0)
            }

            is FunCall -> {
                callBuiltin(e.name, e.args)
            }

            is Assign -> {
                doAssign(e)
            }

            is Ternary -> {
                if (evalExpr(e.cond).toAwkBool()) evalExpr(e.thenExpr) else evalExpr(e.elseExpr)
            }

            is Binary -> {
                evalBinary(e)
            }

            is Unary -> {
                evalUnary(e)
            }

            is GetlineExpr -> {
                evalGetline(e)
            }
        }

    private suspend fun arraySubscript(parts: List<AwkExpr>): String {
        if (parts.size == 1) return evalExpr(parts[0]).toAwkString(convfmt())
        val sep = (scalars["SUBSEP"] ?: StrVal("")).toAwkString(convfmt())
        val pieces = mutableListOf<String>()
        for (p in parts) pieces += evalExpr(p).toAwkString(convfmt())
        return pieces.joinToString(sep)
    }

    private suspend fun doAssign(a: Assign): AwkValue {
        val rhs = evalExpr(a.value)
        val newValue =
            when (a.op) {
                AssignOp.Eq -> {
                    rhs
                }

                AssignOp.PlusEq -> {
                    NumVal(loadLval(a.target).toAwkNumber() + rhs.toAwkNumber())
                }

                AssignOp.MinusEq -> {
                    NumVal(loadLval(a.target).toAwkNumber() - rhs.toAwkNumber())
                }

                AssignOp.MultEq -> {
                    NumVal(loadLval(a.target).toAwkNumber() * rhs.toAwkNumber())
                }

                AssignOp.DivEq -> {
                    val d = rhs.toAwkNumber()
                    if (d == 0.0) runtimeError("division by zero")
                    NumVal(loadLval(a.target).toAwkNumber() / d)
                }

                AssignOp.ModEq -> {
                    val d = rhs.toAwkNumber()
                    if (d == 0.0) runtimeError("division by zero in %")
                    NumVal(loadLval(a.target).toAwkNumber() % d)
                }

                AssignOp.PowEq -> {
                    NumVal(loadLval(a.target).toAwkNumber().pow(rhs.toAwkNumber()))
                }
            }
        storeLval(a.target, newValue)
        return newValue
    }

    private suspend fun loadLval(target: AwkExpr): AwkValue =
        when (target) {
            is VarRef -> {
                scalars[target.name] ?: Uninit
            }

            is FieldRef -> {
                stringValueWithHint(field(evalExpr(target.index).toAwkNumber().toInt()))
            }

            is ArrayRef -> {
                val key = arraySubscript(target.subscript)
                arrays.getOrPut(target.name) { HashMap() }[key] ?: Uninit
            }

            else -> {
                runtimeError("not an assignable expression")
            }
        }

    private suspend fun storeLval(
        target: AwkExpr,
        v: AwkValue,
    ) {
        when (target) {
            is VarRef -> {
                if (target.name == "NF") {
                    setNF(v.toAwkNumber().toInt())
                    return
                }
                scalars[target.name] = v
            }

            is FieldRef -> {
                setField(evalExpr(target.index).toAwkNumber().toInt(), v.toAwkString(ofmt()))
            }

            is ArrayRef -> {
                val key = arraySubscript(target.subscript)
                arrays.getOrPut(target.name) { HashMap() }[key] = v
            }

            else -> {
                runtimeError("not an assignable expression")
            }
        }
    }

    private suspend fun evalBinary(b: Binary): AwkValue {
        val op = b.op
        if (op == BinOp.And) {
            val l = evalExpr(b.left).toAwkBool()
            if (!l) return NumVal(0.0)
            return NumVal(if (evalExpr(b.right).toAwkBool()) 1.0 else 0.0)
        }
        if (op == BinOp.Or) {
            val l = evalExpr(b.left).toAwkBool()
            if (l) return NumVal(1.0)
            return NumVal(if (evalExpr(b.right).toAwkBool()) 1.0 else 0.0)
        }
        val l = evalExpr(b.left)
        val r = evalExpr(b.right)
        return when (op) {
            BinOp.Add -> {
                NumVal(l.toAwkNumber() + r.toAwkNumber())
            }

            BinOp.Sub -> {
                NumVal(l.toAwkNumber() - r.toAwkNumber())
            }

            BinOp.Mul -> {
                NumVal(l.toAwkNumber() * r.toAwkNumber())
            }

            BinOp.Div -> {
                val d = r.toAwkNumber()
                if (d == 0.0) runtimeError("division by zero")
                NumVal(l.toAwkNumber() / d)
            }

            BinOp.Mod -> {
                val d = r.toAwkNumber()
                if (d == 0.0) runtimeError("division by zero in %")
                NumVal(l.toAwkNumber() % d)
            }

            BinOp.Pow -> {
                NumVal(l.toAwkNumber().pow(r.toAwkNumber()))
            }

            BinOp.Concat -> {
                StrVal(l.toAwkString(convfmt()) + r.toAwkString(convfmt()))
            }

            BinOp.Lt, BinOp.Le, BinOp.Gt, BinOp.Ge, BinOp.Eq, BinOp.Ne -> {
                NumVal(if (compareValues(op, l, r)) 1.0 else 0.0)
            }

            BinOp.Match, BinOp.NotMatch -> {
                val target = l.toAwkString(convfmt())
                val pattern =
                    when (val rr = b.right) {
                        is RegexLit -> rr.pattern
                        else -> r.toAwkString(convfmt())
                    }
                val matches = matchRegex(target, pattern)
                NumVal(if (op == BinOp.Match) (if (matches) 1.0 else 0.0) else (if (matches) 0.0 else 1.0))
            }

            BinOp.And, BinOp.Or -> {
                error("unreachable: short-circuited above")
            }
        }
    }

    /**
     * POSIX comparison rules. If both operands are numbers, or both are
     * numeric strings, compare as numbers. Otherwise, compare as strings.
     */
    private fun compareValues(
        op: BinOp,
        l: AwkValue,
        r: AwkValue,
    ): Boolean {
        // POSIX: uninitialized variables have both numeric (0) and string ("")
        // faces — they go numeric when the other side is numeric, string
        // otherwise. So `b == 0` with uninit b is true (numeric), but
        // `b == "0"` is false (string compare "" vs "0").
        fun numericish(v: AwkValue): Boolean = v is NumVal || (v is StrVal && v.isNumericString) || v is Uninit
        val bothNumeric = numericish(l) && numericish(r)
        return if (bothNumeric) {
            val ln = l.toAwkNumber()
            val rn = r.toAwkNumber()
            when (op) {
                BinOp.Lt -> ln < rn
                BinOp.Le -> ln <= rn
                BinOp.Gt -> ln > rn
                BinOp.Ge -> ln >= rn
                BinOp.Eq -> ln == rn
                BinOp.Ne -> ln != rn
                else -> error("not a comparison op")
            }
        } else {
            val ls = l.toAwkString(convfmt())
            val rs = r.toAwkString(convfmt())
            val c = ls.compareTo(rs)
            when (op) {
                BinOp.Lt -> c < 0
                BinOp.Le -> c <= 0
                BinOp.Gt -> c > 0
                BinOp.Ge -> c >= 0
                BinOp.Eq -> c == 0
                BinOp.Ne -> c != 0
                else -> error("not a comparison op")
            }
        }
    }

    private suspend fun evalUnary(u: Unary): AwkValue =
        when (u.op) {
            UnaryOp.Negate -> {
                NumVal(-evalExpr(u.operand).toAwkNumber())
            }

            UnaryOp.Plus -> {
                NumVal(+evalExpr(u.operand).toAwkNumber())
            }

            UnaryOp.Not -> {
                NumVal(if (evalExpr(u.operand).toAwkBool()) 0.0 else 1.0)
            }

            UnaryOp.PreInc -> {
                val cur = loadLval(u.operand).toAwkNumber() + 1.0
                storeLval(u.operand, NumVal(cur))
                NumVal(cur)
            }

            UnaryOp.PreDec -> {
                val cur = loadLval(u.operand).toAwkNumber() - 1.0
                storeLval(u.operand, NumVal(cur))
                NumVal(cur)
            }

            UnaryOp.PostInc -> {
                val cur = loadLval(u.operand).toAwkNumber()
                storeLval(u.operand, NumVal(cur + 1.0))
                NumVal(cur)
            }

            UnaryOp.PostDec -> {
                val cur = loadLval(u.operand).toAwkNumber()
                storeLval(u.operand, NumVal(cur - 1.0))
                NumVal(cur)
            }
        }

    // -- Regex --------------------------------------------------------------

    private val regexCache: MutableMap<String, Regex> = HashMap()

    private fun compileRegex(pattern: String): Regex =
        regexCache.getOrPut(pattern) {
            try {
                Regex(pattern)
            } catch (e: Throwable) {
                runtimeError("invalid regex /$pattern/: ${e.message}")
            }
        }

    private fun matchRegex(
        text: String,
        pattern: String,
    ): Boolean = compileRegex(pattern).containsMatchIn(text)

    // -- Builtins -----------------------------------------------------------

    /**
     * Call a user-defined function. POSIX semantics:
     *  - Scalars pass by value, arrays by reference.
     *  - Extra parameters past `args.size` are locals, initially Uninit.
     *  - Caller's bindings for the parameter names are saved on entry
     *    and restored on exit (so a function param `n` doesn't clobber
     *    a global `n`).
     *  - We classify each arg at call time: a bare VarRef whose name is
     *    *not currently a scalar* is treated as an array reference (the
     *    function's param shares the underlying map). Anything else is
     *    evaluated as a scalar value. This matches the lazy
     *    array-vs-scalar promotion bwk awk does — uninitialized names
     *    promote to arrays if the callee uses them as such.
     */
    private suspend fun callUserFunction(
        name: String,
        argExprs: List<AwkExpr>,
    ): AwkValue {
        val fn = functions[name] ?: runtimeError("internal: unknown function `$name`")
        if (argExprs.size > fn.params.size) {
            runtimeError("function `$name`: too many arguments (${argExprs.size} > ${fn.params.size})")
        }

        // Classify args first, BEFORE we touch any param bindings —
        // otherwise binding param 0 could perturb the lookup that
        // decides what param 1 sees.
        val argBindings: List<Pair<AwkValue?, MutableMap<String, AwkValue>?>> =
            argExprs.map { arg ->
                if (arg is VarRef && arg.name !in scalars) {
                    // Pass by reference: alias to the same map (creating
                    // an empty one in the caller's scope if needed, so
                    // any writes the callee makes are visible).
                    val map = arrays.getOrPut(arg.name) { HashMap() }
                    null to map
                } else {
                    evalExpr(arg) to null
                }
            }

        // Snapshot caller's bindings for the param names so we can
        // restore them when the call returns.
        val savedScalars = fn.params.map { p -> p to scalars[p] }
        val savedArrays = fn.params.map { p -> p to arrays[p] }

        // Bind params.
        for (i in fn.params.indices) {
            val p = fn.params[i]
            scalars.remove(p)
            arrays.remove(p)
            if (i < argBindings.size) {
                val (scalarVal, arrayMap) = argBindings[i]
                if (arrayMap != null) {
                    arrays[p] = arrayMap
                } else if (scalarVal != null) {
                    scalars[p] = scalarVal
                }
            }
            // else: local, leave both unset (read as Uninit)
        }

        val result =
            try {
                execute(fn.body)
                Uninit
            } catch (r: ReturnSignal) {
                r.value
            }

        // Restore caller's bindings.
        for ((p, v) in savedScalars) {
            if (v != null) scalars[p] = v else scalars.remove(p)
        }
        for ((p, m) in savedArrays) {
            if (m != null) arrays[p] = m else arrays.remove(p)
        }

        return result
    }

    private suspend fun callBuiltin(
        name: String,
        args: List<AwkExpr>,
    ): AwkValue {
        if (name in functions) return callUserFunction(name, args)
        return when (name) {
            "length" -> {
                if (args.isEmpty()) {
                    NumVal(
                        field(0).length.toDouble(),
                    )
                } else {
                    NumVal(evalExpr(args[0]).toAwkString(convfmt()).length.toDouble())
                }
            }

            "substr" -> {
                val s = evalExpr(args[0]).toAwkString(convfmt())
                val start = evalExpr(args[1]).toAwkNumber().toInt()
                val n = if (args.size > 2) evalExpr(args[2]).toAwkNumber().toInt() else Int.MAX_VALUE
                StrVal(awkSubstr(s, start, n))
            }

            "index" -> {
                val s = evalExpr(args[0]).toAwkString(convfmt())
                val t = evalExpr(args[1]).toAwkString(convfmt())
                NumVal((s.indexOf(t) + 1).toDouble()) // awk indices are 1-based; 0 = not found
            }

            "tolower" -> {
                StrVal(evalExpr(args[0]).toAwkString(convfmt()).lowercase())
            }

            "toupper" -> {
                StrVal(evalExpr(args[0]).toAwkString(convfmt()).uppercase())
            }

            "int" -> {
                NumVal(evalExpr(args[0]).toAwkNumber().toLong().toDouble())
            }

            "close" -> {
                // POSIX: close releases the named target. The name is
                // the same string passed to `getline < name` /
                // `print > name` / `print | name`. We don't know which
                // category the user means, so check input readers
                // first, then output writers under any of the three
                // mode prefixes. Returns 0 if anything was closed,
                // -1 otherwise.
                val target = evalExpr(args[0]).toAwkString(convfmt())
                var closed = false
                openFiles.remove(target)?.let {
                    try {
                        it.close()
                    } catch (_: Throwable) {
                        // best effort
                    }
                    closed = true
                }
                for (prefix in listOf(">", ">>", "|")) {
                    openOutputs.remove("$prefix:$target")?.let {
                        try {
                            it.close()
                        } catch (_: Throwable) {
                            // best effort
                        }
                        closed = true
                    }
                }
                NumVal(if (closed) 0.0 else -1.0)
            }

            "fflush" -> {
                // We don't buffer at the engine level (the host's
                // SuspendSink does), so fflush is a no-op that always
                // succeeds. POSIX returns 0 on success.
                NumVal(0.0)
            }

            "sprintf" -> {
                if (args.isEmpty()) runtimeError("sprintf requires a format string")
                val fmt = evalExpr(args[0]).toAwkString(ofmt())
                StrVal(formatPrintf(fmt, args.drop(1).map { evalExpr(it) }))
            }

            "split" -> {
                if (args.size < 2) runtimeError("split: not enough arguments")
                val s = evalExpr(args[0]).toAwkString(convfmt())
                // args[1] must be an array reference — we accept VarRef
                // and treat its name as the destination array (clearing
                // any existing contents).
                val arrName =
                    when (val a = args[1]) {
                        is VarRef -> a.name
                        else -> runtimeError("split: second argument must be an array name")
                    }
                val sep =
                    if (args.size > 2) {
                        when (val a = args[2]) {
                            is RegexLit -> a.pattern
                            else -> evalExpr(a).toAwkString(convfmt())
                        }
                    } else {
                        (scalars["FS"] ?: StrVal(" ")).toAwkString(convfmt())
                    }
                val parts =
                    when {
                        s.isEmpty() -> emptyList()
                        sep == " " -> s.trim().split(Regex("""[ \t]+""")).filter { it.isNotEmpty() }
                        sep.length == 1 -> s.split(sep)
                        else -> s.split(Regex(sep))
                    }
                // Mutate the existing array in place, don't replace the
                // map. If the array is aliased into a function param,
                // replacing would break the alias and the caller would
                // see an empty array.
                val dest = arrays.getOrPut(arrName) { HashMap() }
                dest.clear()
                for ((i, p) in parts.withIndex()) dest[(i + 1).toString()] = stringValueWithHint(p)
                NumVal(parts.size.toDouble())
            }

            "match" -> {
                val s = evalExpr(args[0]).toAwkString(convfmt())
                val pat =
                    when (val p = args[1]) {
                        is RegexLit -> p.pattern
                        else -> evalExpr(p).toAwkString(convfmt())
                    }
                val m = compileRegex(pat).find(s)
                if (m == null) {
                    scalars["RSTART"] = NumVal(0.0)
                    scalars["RLENGTH"] = NumVal(-1.0)
                    NumVal(0.0)
                } else {
                    scalars["RSTART"] = NumVal((m.range.first + 1).toDouble())
                    scalars["RLENGTH"] = NumVal((m.value.length).toDouble())
                    NumVal((m.range.first + 1).toDouble())
                }
            }

            "sub", "gsub" -> {
                val pat =
                    when (val p = args[0]) {
                        is RegexLit -> p.pattern
                        else -> evalExpr(p).toAwkString(convfmt())
                    }
                val repl = evalExpr(args[1]).toAwkString(convfmt())
                val target = if (args.size > 2) args[2] else FieldRef(NumLit(0.0))
                val original = loadLval(target).toAwkString(convfmt())
                val regex = compileRegex(pat)
                val result =
                    if (name == "sub") {
                        val m = regex.find(original)
                        if (m == null) {
                            original to 0
                        } else {
                            val expanded = expandReplacement(repl, m.value)
                            original.substring(0, m.range.first) + expanded + original.substring(m.range.last + 1) to 1
                        }
                    } else {
                        var count = 0
                        val newStr =
                            regex.replace(original) { mr ->
                                count++
                                expandReplacement(repl, mr.value)
                            }
                        newStr to count
                    }
                storeLval(target, StrVal(result.first))
                NumVal(result.second.toDouble())
            }

            "atan2" -> {
                NumVal(kotlin.math.atan2(evalExpr(args[0]).toAwkNumber(), evalExpr(args[1]).toAwkNumber()))
            }

            "cos" -> {
                NumVal(kotlin.math.cos(evalExpr(args[0]).toAwkNumber()))
            }

            "sin" -> {
                NumVal(kotlin.math.sin(evalExpr(args[0]).toAwkNumber()))
            }

            "exp" -> {
                NumVal(kotlin.math.exp(evalExpr(args[0]).toAwkNumber()))
            }

            "log" -> {
                NumVal(kotlin.math.ln(evalExpr(args[0]).toAwkNumber()))
            }

            "sqrt" -> {
                NumVal(kotlin.math.sqrt(evalExpr(args[0]).toAwkNumber()))
            }

            "rand" -> {
                NumVal(rng.nextDouble())
            }

            "srand" -> {
                val newSeed = if (args.isEmpty()) defaultSeed() else evalExpr(args[0]).toAwkNumber().toLong()
                val old = rngSeed
                rng = kotlin.random.Random(newSeed)
                rngSeed = newSeed
                NumVal(old.toDouble())
            }

            "system" -> {
                if (args.isEmpty()) runtimeError("system called with no arguments")
                val cmd = evalExpr(args[0]).toAwkString(convfmt())
                val hook = systemHook ?: runtimeError("system() not supported in this context")
                NumVal(hook(cmd).toDouble())
            }

            else -> {
                runtimeError("unknown function: $name")
            }
        }
    }

    private var rngSeed: Long = 0L
    private var rng = kotlin.random.Random(rngSeed)

    private fun defaultSeed(): Long =
        0L // POSIX: srand() with no args uses time-of-day; we keep deterministic for tests.

    /**
     * Substring per POSIX awk semantics: 1-based start index, clamping to
     * the actual range; negative or huge `n` yields the tail of the string
     * from `start`.
     */
    private fun awkSubstr(
        s: String,
        start: Int,
        length: Int,
    ): String {
        val len = s.length
        val from = (start - 1).coerceAtLeast(0)
        if (from >= len) return ""
        val available = len - from
        val n = length.coerceIn(0, available)
        return s.substring(from, from + n)
    }

    /**
     * Expand backslash references in sub/gsub replacement strings. Per
     * POSIX, `&` is replaced with the full match; `\&` is a literal `&`;
     * `\\` is a literal backslash.
     */
    private fun expandReplacement(
        repl: String,
        matched: String,
    ): String {
        if ('&' !in repl && '\\' !in repl) return repl
        val out = StringBuilder(repl.length + matched.length)
        var i = 0
        while (i < repl.length) {
            val c = repl[i]
            when {
                c == '\\' && i + 1 < repl.length -> {
                    when (val n = repl[i + 1]) {
                        '&' -> {
                            out.append('&')
                        }

                        '\\' -> {
                            out.append('\\')
                        }

                        else -> {
                            out.append('\\')
                            out.append(n)
                        }
                    }
                    i += 2
                }

                c == '&' -> {
                    out.append(matched)
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}

/**
 * Iterator over a sequence of named input files. Tracks the current
 * filename and per-file record number (FNR) so the interpreter can
 * update `FILENAME` / `FNR` after each pull. `next()` transparently
 * advances across file boundaries — bare `getline` and the main record
 * loop both see one flat stream while still observing per-file state.
 */
internal class MultiFileCursor(
    private val files: Iterator<AwkInputFile>,
) {
    private var current: Iterator<String>? = null
    var filename: String = ""
        private set
    var fnr: Int = 0
        private set

    /** Next record across all files, or null when fully exhausted. */
    fun next(): String? {
        while (true) {
            val cur = current
            if (cur != null && cur.hasNext()) {
                fnr++
                return cur.next()
            }
            if (!files.hasNext()) return null
            val nextFile = files.next()
            current = nextFile.records.iterator()
            filename = nextFile.name
            fnr = 0
        }
    }

    /** Discard the remainder of the file currently being read. The next
     *  [next] call advances to the next file (or returns null). */
    fun skipCurrentFile() {
        current = null
    }
}

// -- Control-flow signals ---------------------------------------------------

internal object BreakSignal : RuntimeException() {
    private fun readResolve(): Any = BreakSignal
}

internal object ContinueSignal : RuntimeException() {
    private fun readResolve(): Any = ContinueSignal
}

internal object NextSignal : RuntimeException() {
    private fun readResolve(): Any = NextSignal
}

internal object NextFileSignal : RuntimeException() {
    private fun readResolve(): Any = NextFileSignal
}

internal class ExitSignal(
    val code: Int,
) : RuntimeException()

internal class ReturnSignal(
    val value: AwkValue,
) : RuntimeException()
