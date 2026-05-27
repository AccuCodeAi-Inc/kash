package com.accucodeai.kash.tools.make

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.ShellInvocation
import com.accucodeai.kash.api.io.AsyncPipe
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.tools.make.parser.parseMakefile
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

public class MakeOptions(
    public val files: List<String> = emptyList(),
    public val targets: List<String> = emptyList(),
    public val workdir: String? = null,
    public val keepGoing: Boolean = false,
    public val stopOnFirstError: Boolean = false,
    public val dryRun: Boolean = false,
    public val silent: Boolean = false,
    public val ignoreErrors: Boolean = false,
    public val envOverride: Boolean = false,
    public val noBuiltinRules: Boolean = false,
    public val touchMode: Boolean = false,
    public val questionMode: Boolean = false,
    public val printDatabase: Boolean = false,
    public val cliMacros: Map<String, Pair<MacroFlavor, String>> = emptyMap(),
    public val jobs: Int = 1,
)

public sealed interface MakeBuildOutcome {
    public val exitCode: Int
}

public data class MakeOk(
    override val exitCode: Int,
) : MakeBuildOutcome

public data class MakeFailed(
    override val exitCode: Int,
    val message: String,
) : MakeBuildOutcome

public class MakeEngine(
    private val ctx: CommandContext,
    private val opts: MakeOptions,
) {
    private val env = MacroEnv()
    private val explicitRules = mutableListOf<RuleStmt>()
    private val patternRules = mutableListOf<RuleStmt>()
    private val phonyTargets = mutableSetOf<String>()
    private val preciousTargets = mutableSetOf<String>()
    private val silentTargets = mutableSetOf<String>()
    private val ignoreTargets = mutableSetOf<String>()
    private val cwd: String = opts.workdir?.let { Paths.resolve(ctx.cwd, it) } ?: ctx.cwd
    private val builtTargets = mutableSetOf<String>()
    private val inProgress = mutableSetOf<String>()
    private var anyError: Boolean = false
    private var firstErrorMessage: String? = null
    private var allTargetsAreSilent: Boolean = false
    private var allTargetsAreIgnore: Boolean = false
    private var defaultGoal: String? = null

    public suspend fun run(): MakeBuildOutcome {
        seedBuiltins()
        seedEnvAndCli()
        try {
            loadMakefile()
        } catch (e: MakeParseError) {
            ctx.stderr.writeUtf8("make: ${e.message}\n")
            return MakeFailed(2, e.message ?: "parse error")
        } catch (e: FileNotFound) {
            ctx.stderr.writeUtf8("make: *** No targets specified and no makefile found.  Stop.\n")
            return MakeFailed(2, "no makefile")
        }
        if (opts.printDatabase) {
            printDatabaseSuspending()
            return MakeOk(0)
        }
        val goals =
            opts.targets.ifEmpty {
                defaultGoal?.let { listOf(it) } ?: emptyList()
            }
        if (goals.isEmpty()) {
            ctx.stderr.writeUtf8("make: *** No targets.  Stop.\n")
            return MakeFailed(2, "no targets")
        }
        for (g in goals) {
            try {
                build(g)
            } catch (e: MakeRuntimeError) {
                ctx.stderr.writeUtf8("make: ${e.message}\n")
                anyError = true
                firstErrorMessage = firstErrorMessage ?: (e.message ?: "build failed")
                if (!opts.keepGoing) break
            }
        }
        return if (anyError) MakeFailed(2, firstErrorMessage ?: "build failed") else MakeOk(0)
    }

    private fun seedBuiltins() {
        env.put("MAKE", MacroValue("make", MacroFlavor.RECURSIVE, MacroOrigin.DEFAULT))
        env.put("MAKEFLAGS", MacroValue(buildMakeflags(), MacroFlavor.RECURSIVE, MacroOrigin.DEFAULT))
        env.put("CURDIR", MacroValue(cwd, MacroFlavor.RECURSIVE, MacroOrigin.DEFAULT))
        env.put("SHELL", MacroValue("sh", MacroFlavor.RECURSIVE, MacroOrigin.DEFAULT))
        env.put(".SHELLFLAGS", MacroValue("-c", MacroFlavor.RECURSIVE, MacroOrigin.DEFAULT))
        if (!opts.noBuiltinRules) {
            env.put("CC", MacroValue("cc", MacroFlavor.RECURSIVE, MacroOrigin.DEFAULT))
            env.put("CXX", MacroValue("c++", MacroFlavor.RECURSIVE, MacroOrigin.DEFAULT))
            env.put("AR", MacroValue("ar", MacroFlavor.RECURSIVE, MacroOrigin.DEFAULT))
            env.put("RM", MacroValue("rm -f", MacroFlavor.RECURSIVE, MacroOrigin.DEFAULT))
        }
    }

    private fun buildMakeflags(): String {
        val sb = StringBuilder()
        if (opts.keepGoing) sb.append('k')
        if (opts.silent) sb.append('s')
        if (opts.ignoreErrors) sb.append('i')
        if (opts.dryRun) sb.append('n')
        if (opts.noBuiltinRules) sb.append('r')
        return sb.toString()
    }

    private fun seedEnvAndCli() {
        for ((k, v) in ctx.env) {
            val origin = if (opts.envOverride) MacroOrigin.OVERRIDE else MacroOrigin.ENVIRONMENT
            env.put(k, MacroValue(v, MacroFlavor.RECURSIVE, origin, exported = true))
        }
        for ((k, fv) in opts.cliMacros) {
            env.assign(k, fv.first, fv.second, MacroOrigin.COMMAND_LINE)
        }
    }

    private suspend fun loadMakefile() {
        val mfFiles =
            if (opts.files.isNotEmpty()) {
                opts.files
            } else {
                listOf(findDefaultMakefile() ?: throw FileNotFound("Makefile"))
            }
        for (f in mfFiles) {
            if (f == "-") {
                val text = ctx.stdin.readUtf8Text()
                processMakefile(text)
            } else {
                val abs = Paths.resolve(cwd, f)
                if (!ctx.fs.exists(abs)) {
                    ctx.stderr.writeUtf8("make: $f: No such file or directory\n")
                    throw FileNotFound(f)
                }
                val text = ctx.fs.readBytes(abs).decodeToString()
                processMakefile(text)
            }
        }
        if (!opts.noBuiltinRules) {
            for (r in defaultPatternRules()) patternRules += r
        }
    }

    private fun findDefaultMakefile(): String? {
        for (name in listOf("GNUmakefile", "makefile", "Makefile")) {
            val abs = Paths.resolve(cwd, name)
            if (ctx.fs.exists(abs)) return name
        }
        return null
    }

    private suspend fun processMakefile(text: String) {
        val mf = parseMakefile(text)
        processStatements(mf.statements)
    }

    private suspend fun processStatements(stmts: List<MakeStmt>) {
        for (s in stmts) {
            processStmt(s)
        }
    }

    private suspend fun processStmt(s: MakeStmt) {
        when (s) {
            is Assignment -> {
                processAssignment(s)
            }

            is RuleStmt -> {
                processRule(s)
            }

            is IncludeStmt -> {
                processInclude(s)
            }

            is ConditionalStmt -> {
                processConditional(s)
            }

            is ExportStmt -> {
                processExport(s)
            }

            is UnexportStmt -> {
                for (n in s.names) {
                    val v = env.get(n) ?: continue
                    env.put(n, v.copy(exported = false))
                }
            }

            is DefineStmt -> {
                env.assign(s.name, s.flavor, s.body, MacroOrigin.FILE)
            }
        }
    }

    private suspend fun processAssignment(a: Assignment) {
        val origin = if (a.isOverride) MacroOrigin.OVERRIDE else MacroOrigin.FILE
        if (a.flavor == MacroFlavor.SHELL) {
            env.assignSuspending(a.name, MacroFlavor.SHELL, a.value, origin) { script ->
                runShellCapture(script)
            }
        } else {
            env.assign(a.name, a.flavor, a.value, origin)
        }
        if (a.exported) env.export(a.name)
    }

    private suspend fun processRule(r: RuleStmt) {
        val expandedTargets = r.targets.flatMap { splitWordsLocal(env.expand(it)) }
        val expandedPrereqs = r.prereqs.flatMap { splitWordsLocal(env.expand(it)) }
        val expandedOrderOnly = r.orderOnly.flatMap { splitWordsLocal(env.expand(it)) }
        val isPattern = expandedTargets.any { '%' in it }

        for (t in expandedTargets) {
            if (t == ".PHONY") {
                phonyTargets += expandedPrereqs
                continue
            }
            if (t == ".PRECIOUS") {
                preciousTargets += expandedPrereqs
                continue
            }
            if (t == ".SILENT") {
                if (expandedPrereqs.isEmpty()) allTargetsAreSilent = true else silentTargets += expandedPrereqs
                continue
            }
            if (t == ".IGNORE") {
                if (expandedPrereqs.isEmpty()) allTargetsAreIgnore = true else ignoreTargets += expandedPrereqs
                continue
            }
            if (t == ".SUFFIXES" || t == ".DEFAULT" || t == ".POSIX" ||
                t == ".NOTPARALLEL" || t == ".WAIT" || t == ".SECONDARY" ||
                t == ".INTERMEDIATE"
            ) {
                continue
            }
        }

        val realTargets = expandedTargets.filterNot { it.startsWith(".") && it == it.uppercase() && it[0] == '.' }
        if (realTargets.isEmpty()) return

        val storedRule =
            RuleStmt(
                targets = realTargets,
                prereqs = expandedPrereqs,
                orderOnly = expandedOrderOnly,
                inlineRecipe = r.inlineRecipe,
                recipes = r.recipes,
                isPattern = isPattern,
                line = r.line,
            )
        if (isPattern) {
            patternRules += storedRule
        } else {
            explicitRules += storedRule
            if (defaultGoal == null && realTargets.first()[0] != '.') {
                defaultGoal = realTargets.first()
            }
        }
    }

    private suspend fun processInclude(inc: IncludeStmt) {
        for (p in inc.paths) {
            val expanded = env.expand(p)
            for (path in splitWordsLocal(expanded)) {
                val abs = Paths.resolve(cwd, path)
                if (!ctx.fs.exists(abs)) {
                    if (!inc.optional) {
                        throw MakeParseError("$path: No such file or directory", inc.line)
                    }
                    continue
                }
                val text = ctx.fs.readBytes(abs).decodeToString()
                processMakefile(text)
            }
        }
    }

    private suspend fun processConditional(c: ConditionalStmt) {
        for (b in c.branches) {
            if (evalCondition(b.kind, b.args)) {
                processStatements(b.body)
                return
            }
        }
        c.elseBody?.let { processStatements(it) }
    }

    private fun evalCondition(
        kind: CondKind,
        argText: String,
    ): Boolean =
        when (kind) {
            CondKind.IFEQ, CondKind.IFNEQ -> {
                val (a, b) = parseEqArgs(argText)
                val match = env.expand(a) == env.expand(b)
                if (kind == CondKind.IFEQ) match else !match
            }

            CondKind.IFDEF -> {
                val n = env.expand(argText).trim()
                env.isDefined(n) && env.get(n)?.raw?.isNotEmpty() == true
            }

            CondKind.IFNDEF -> {
                val n = env.expand(argText).trim()
                !env.isDefined(n) || env.get(n)?.raw?.isEmpty() == true
            }
        }

    private fun parseEqArgs(s: String): Pair<String, String> {
        val t = s.trim()
        if (t.startsWith("(") && t.endsWith(")")) {
            val body = t.substring(1, t.length - 1)
            val (l, r) = splitCommaTop(body)
            return l.trim() to r.trim()
        }
        if (t.startsWith("\"") || t.startsWith("'")) {
            val parts = parseQuotedPair(t)
            if (parts != null) return parts
        }
        return t to ""
    }

    private fun splitCommaTop(s: String): Pair<String, String> {
        var depth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '$' && i + 1 < s.length && (s[i + 1] == '(' || s[i + 1] == '{')) {
                depth++
                i += 2
                continue
            }
            if (depth > 0) {
                if (c == ')' || c == '}') depth--
                i++
                continue
            }
            if (c == ',') return s.substring(0, i) to s.substring(i + 1)
            i++
        }
        return s to ""
    }

    private fun parseQuotedPair(s: String): Pair<String, String>? {
        var i = 0

        fun readQuoted(): String? {
            if (i >= s.length) return null
            val q = s[i]
            if (q != '"' && q != '\'') return null
            i++
            val sb = StringBuilder()
            while (i < s.length && s[i] != q) {
                sb.append(s[i])
                i++
            }
            if (i >= s.length) return null
            i++
            return sb.toString()
        }

        val a = readQuoted() ?: return null
        while (i < s.length && s[i].isWhitespace()) i++
        val b = readQuoted() ?: return null
        return a to b
    }

    private suspend fun processExport(e: ExportStmt) {
        if (e.embedded != null) {
            processAssignment(e.embedded)
        }
        for (n in e.names) env.export(n)
    }

    private suspend fun printDatabaseSuspending() {
        for (n in env.snapshotNames().sorted()) {
            val v = env.get(n) ?: continue
            val op =
                when (v.flavor) {
                    MacroFlavor.RECURSIVE -> "="
                    MacroFlavor.IMMEDIATE, MacroFlavor.IMMEDIATE_TRIPLE -> ":="
                    MacroFlavor.CONDITIONAL -> "?="
                    MacroFlavor.APPEND -> "+="
                    MacroFlavor.SHELL -> "!="
                }
            ctx.stdout.writeUtf8("$n $op ${v.raw}\n")
        }
    }

    private suspend fun build(target: String) {
        if (target in builtTargets) return
        if (target in inProgress) throw MakeRuntimeError("*** Circular dependency on '$target'")
        inProgress += target

        val rule = resolveRule(target)
        if (rule == null) {
            val abs = Paths.resolve(cwd, target)
            if (ctx.fs.exists(abs) || target in phonyTargets) {
                builtTargets += target
                inProgress -= target
                return
            }
            throw MakeRuntimeError("*** No rule to make target '$target'.  Stop.")
        }

        val (matchedRule, stem) = rule
        val allPrereqs = matchedRule.prereqs.map { resolveStem(it, stem) }
        val orderOnly = matchedRule.orderOnly.map { resolveStem(it, stem) }

        for (p in allPrereqs + orderOnly) {
            try {
                build(p)
            } catch (e: MakeRuntimeError) {
                anyError = true
                firstErrorMessage = firstErrorMessage ?: (e.message ?: "")
                if (!opts.keepGoing) throw e
            }
        }

        val needBuild = shouldBuild(target, allPrereqs)
        if (needBuild) {
            runRecipes(target, matchedRule, allPrereqs, stem)
        }
        builtTargets += target
        inProgress -= target
    }

    private fun resolveStem(
        prereqPattern: String,
        stem: String?,
    ): String {
        if (stem == null) return prereqPattern
        val pct = prereqPattern.indexOf('%')
        return if (pct < 0) prereqPattern else prereqPattern.substring(0, pct) + stem + prereqPattern.substring(pct + 1)
    }

    private fun resolveRule(target: String): Pair<RuleStmt, String?>? {
        val explicit = explicitRules.firstOrNull { target in it.targets }
        if (explicit != null) {
            val merged = mergeRules(target)
            if (merged.recipes.isNotEmpty() || merged.inlineRecipe != null || merged.prereqs.isNotEmpty()) {
                return merged to null
            }
        }
        for (pr in patternRules) {
            for (pat in pr.targets) {
                val stem = matchPattern(pat, target) ?: continue
                if (pr.prereqs.any { p ->
                        val resolved = resolveStem(p, stem)
                        !canBeBuilt(resolved)
                    }
                ) {
                    continue
                }
                return pr to stem
            }
        }
        return null
    }

    private fun mergeRules(target: String): RuleStmt {
        val matching = explicitRules.filter { target in it.targets }
        if (matching.size == 1) return matching[0]
        val prereqs = matching.flatMap { it.prereqs }.distinct()
        val orderOnly = matching.flatMap { it.orderOnly }.distinct()
        val recipesRule = matching.lastOrNull { it.recipes.isNotEmpty() || it.inlineRecipe != null }
        return RuleStmt(
            targets = listOf(target),
            prereqs = prereqs,
            orderOnly = orderOnly,
            inlineRecipe = recipesRule?.inlineRecipe,
            recipes = recipesRule?.recipes ?: emptyList(),
            isPattern = false,
            line = matching.first().line,
        )
    }

    private fun canBeBuilt(path: String): Boolean {
        if (explicitRules.any { path in it.targets }) return true
        val abs = Paths.resolve(cwd, path)
        if (ctx.fs.exists(abs)) return true
        return false
    }

    private fun matchPattern(
        pattern: String,
        target: String,
    ): String? {
        val pct = pattern.indexOf('%')
        if (pct < 0) return if (pattern == target) "" else null
        val prefix = pattern.substring(0, pct)
        val suffix = pattern.substring(pct + 1)
        if (!target.startsWith(prefix) || !target.endsWith(suffix)) return null
        if (target.length < prefix.length + suffix.length) return null
        return target.substring(prefix.length, target.length - suffix.length)
    }

    private fun shouldBuild(
        target: String,
        prereqs: List<String>,
    ): Boolean {
        if (target in phonyTargets) return true
        val abs = Paths.resolve(cwd, target)
        if (!ctx.fs.exists(abs)) return true
        val targetMtime =
            try {
                ctx.fs.stat(abs).mtimeEpochSeconds
            } catch (_: Throwable) {
                return true
            }
        for (p in prereqs) {
            if (p in phonyTargets) return true
            val pabs = Paths.resolve(cwd, p)
            if (!ctx.fs.exists(pabs)) return true
            val pMtime =
                try {
                    ctx.fs.stat(pabs).mtimeEpochSeconds
                } catch (_: Throwable) {
                    return true
                }
            if (pMtime > targetMtime) return true
        }
        return false
    }

    private suspend fun runRecipes(
        target: String,
        rule: RuleStmt,
        prereqs: List<String>,
        stem: String?,
    ) {
        if (opts.touchMode) {
            if (target !in phonyTargets) {
                val abs = Paths.resolve(cwd, target)
                if (!ctx.fs.exists(abs)) {
                    try {
                        ctx.fs.writeBytes(abs, ByteArray(0))
                    } catch (e: Exception) {
                        ctx.stderr.writeUtf8("make: cannot touch '$target': ${e.message}\n")
                    }
                }
                try {
                    ctx.fs.setMtime(abs, currentTime())
                } catch (_: Throwable) {
                }
            }
            return
        }

        val recipes = mutableListOf<String>()
        rule.inlineRecipe?.let { recipes += it }
        recipes += rule.recipes
        if (recipes.isEmpty()) return

        for (raw in recipes) {
            val (line, prefixes) = stripPrefixes(raw)
            val silent =
                opts.silent ||
                    allTargetsAreSilent ||
                    target in silentTargets ||
                    prefixes.contains('@')
            val ignore =
                opts.ignoreErrors ||
                    allTargetsAreIgnore ||
                    target in ignoreTargets ||
                    prefixes.contains('-')
            val alwaysRun = prefixes.contains('+')

            val autoVars = buildAutoVars(target, prereqs, stem)
            val saved = installAutoVars(autoVars)
            val expanded =
                try {
                    env.expand(line)
                } finally {
                    restoreAutoVars(saved)
                }

            if (!silent && !opts.silent) {
                ctx.stdout.writeUtf8("$expanded\n")
            }

            if (opts.dryRun && !alwaysRun) continue
            if (opts.questionMode) {
                anyError = true
                return
            }

            val rc = invokeRecipe(expanded, target, rule.line)
            if (rc != 0 && !ignore) {
                throw MakeRuntimeError("*** [$target] Error $rc")
            }
        }
    }

    private fun stripPrefixes(line: String): Pair<String, Set<Char>> {
        val prefixes = mutableSetOf<Char>()
        var i = 0
        while (i < line.length && (line[i] == '@' || line[i] == '-' || line[i] == '+')) {
            prefixes += line[i]
            i++
        }
        return line.substring(i) to prefixes
    }

    private fun buildAutoVars(
        target: String,
        prereqs: List<String>,
        stem: String?,
    ): Map<String, String> {
        val all = prereqs.joinToString(" ")
        val unique = prereqs.distinct().joinToString(" ")
        val first = prereqs.firstOrNull() ?: ""
        val newer =
            prereqs
                .filter { p ->
                    val tabs = Paths.resolve(cwd, target)
                    val pabs = Paths.resolve(cwd, p)
                    !ctx.fs.exists(tabs) || (ctx.fs.exists(pabs) && tryMtime(pabs) > tryMtime(tabs))
                }.joinToString(" ")
        return buildMap {
            put("@", target)
            put("<", first)
            put("^", unique)
            put("+", all)
            put("?", newer)
            put("*", stem ?: target.substringBeforeLast('.', missingDelimiterValue = target))
            put("@D", dirOf(target))
            put("@F", baseOf(target))
            put("<D", dirOf(first))
            put("<F", baseOf(first))
            put("^D", unique.split(' ').joinToString(" ") { dirOf(it) })
            put("^F", unique.split(' ').joinToString(" ") { baseOf(it) })
            put("?D", newer.split(' ').joinToString(" ") { dirOf(it) })
            put("?F", newer.split(' ').joinToString(" ") { baseOf(it) })
        }
    }

    private fun tryMtime(abs: String): Long =
        try {
            ctx.fs.stat(abs).mtimeEpochSeconds
        } catch (_: Throwable) {
            0L
        }

    private fun dirOf(p: String): String {
        val i = p.lastIndexOf('/')
        return if (i < 0) "./" else p.substring(0, i) + "/"
    }

    private fun baseOf(p: String): String {
        val i = p.lastIndexOf('/')
        return if (i < 0) p else p.substring(i + 1)
    }

    private fun installAutoVars(autos: Map<String, String>): Map<String, MacroValue?> {
        val prev = mutableMapOf<String, MacroValue?>()
        for ((k, v) in autos) {
            prev[k] = env.get(k)
            env.put(k, MacroValue(v, MacroFlavor.IMMEDIATE, MacroOrigin.AUTOMATIC))
        }
        return prev
    }

    private fun restoreAutoVars(prev: Map<String, MacroValue?>) {
        for ((k, v) in prev) {
            if (v == null) {
                // No public unset; reassign empty as a stand-in.
                env.put(k, MacroValue("", MacroFlavor.RECURSIVE, MacroOrigin.AUTOMATIC))
            } else {
                env.put(k, v)
            }
        }
    }

    private suspend fun invokeRecipe(
        script: String,
        target: String,
        lineNum: Int,
    ): Int {
        val runner = ctx.shellRunner
        if (runner == null) {
            ctx.stderr.writeUtf8("make: shell runner unavailable (no interpreter context)\n")
            return 2
        }
        return runner.run(
            ShellInvocation(
                script = script,
                stdout = ctx.stdout,
                stderr = ctx.stderr,
                scriptName = "Makefile:$lineNum",
            ),
        )
    }

    private suspend fun runShellCapture(script: String): String =
        coroutineScope {
            val runner = ctx.shellRunner ?: return@coroutineScope ""
            val pipe = AsyncPipe()
            val job =
                launch {
                    runner.run(
                        ShellInvocation(
                            script = script,
                            stdout = pipe.sink,
                            stderr = ctx.stderr,
                        ),
                    )
                    pipe.sink.close()
                }
            val output = pipe.source.readUtf8Text()
            job.join()
            output.trimEnd('\n').replace('\n', ' ')
        }

    private fun currentTime(): Long =
        ctx.process.machine.clock
            .now()
            .epochSeconds
}

internal fun splitWordsLocal(s: String): List<String> = s.split(Regex("[ \t]+")).filter { it.isNotEmpty() }

internal fun defaultPatternRules(): List<RuleStmt> {
    val mk: (List<String>, List<String>, List<String>) -> RuleStmt = { tgts, pre, recs ->
        RuleStmt(
            targets = tgts,
            prereqs = pre,
            orderOnly = emptyList(),
            inlineRecipe = null,
            recipes = recs,
            isPattern = true,
            line = 0,
        )
    }
    return listOf(
        mk(listOf("%.o"), listOf("%.c"), listOf("\$(CC) \$(CPPFLAGS) \$(CFLAGS) -c -o \$@ \$<")),
        mk(listOf("%.o"), listOf("%.cpp"), listOf("\$(CXX) \$(CPPFLAGS) \$(CXXFLAGS) -c -o \$@ \$<")),
        mk(listOf("%.o"), listOf("%.cc"), listOf("\$(CXX) \$(CPPFLAGS) \$(CXXFLAGS) -c -o \$@ \$<")),
        mk(listOf("%"), listOf("%.o"), listOf("\$(CC) \$(LDFLAGS) \$^ \$(LDLIBS) -o \$@")),
    )
}
