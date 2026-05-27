package com.accucodeai.kash.tools.make.parser

import com.accucodeai.kash.tools.make.Assignment
import com.accucodeai.kash.tools.make.CondBranch
import com.accucodeai.kash.tools.make.CondKind
import com.accucodeai.kash.tools.make.ConditionalStmt
import com.accucodeai.kash.tools.make.DefineStmt
import com.accucodeai.kash.tools.make.ExportStmt
import com.accucodeai.kash.tools.make.IncludeStmt
import com.accucodeai.kash.tools.make.MacroFlavor
import com.accucodeai.kash.tools.make.MakeParseError
import com.accucodeai.kash.tools.make.MakeStmt
import com.accucodeai.kash.tools.make.Makefile
import com.accucodeai.kash.tools.make.RuleStmt
import com.accucodeai.kash.tools.make.UnexportStmt

internal fun assembleMakefile(lines: List<RawLine>): Makefile {
    val statements = Assembler(lines).parseBlock(closers = emptySet())
    return Makefile(statements)
}

private class Assembler(
    private val lines: List<RawLine>,
) {
    var pos: Int = 0

    fun parseBlock(closers: Set<String>): List<MakeStmt> {
        val out = mutableListOf<MakeStmt>()
        while (pos < lines.size) {
            val l = lines[pos]
            when (l) {
                is RawElse -> {
                    if ("else" in closers) return out else error("misplaced else", l.line)
                }

                is RawEndif -> {
                    if ("endif" in closers) return out else error("misplaced endif", l.line)
                }

                is RawEndef -> {
                    error("misplaced endef", l.line)
                }

                is RawConditional -> {
                    out += parseConditional()
                }

                is RawAssign -> {
                    out += makeAssignment(l, exported = false, isOverride = false)
                    pos++
                }

                is RawRule -> {
                    out += parseRule(l)
                }

                is RawInclude -> {
                    pos++
                    out += IncludeStmt(splitWords(l.paths), l.optional, l.line)
                }

                is RawDefine -> {
                    out += parseDefine(l)
                }

                is RawExport -> {
                    pos++
                    out += parseExport(l)
                }

                is RawUnexport -> {
                    pos++
                    out += UnexportStmt(splitWords(l.names), l.line)
                }

                is RawOverride -> {
                    pos++
                    out += parseOverride(l)
                }

                is RawRecipe -> {
                    error("recipe commences before first target", l.line)
                }

                is RawBareWords -> {
                    pos++
                    // Bare-words is either an export-style decl from a prior
                    // line (no-op for v1) or a misplaced line. Tolerate.
                }
            }
        }
        if (closers.isNotEmpty()) error("missing $closers", lines.lastOrNull()?.line ?: 0)
        return out
    }

    private fun parseConditional(): ConditionalStmt {
        val branches = mutableListOf<CondBranch>()
        var elseBody: List<MakeStmt>? = null
        val opener = lines[pos] as RawConditional
        pos++
        val firstBody = parseBlock(closers = setOf("else", "endif"))
        branches += CondBranch(condKind(opener.kind), opener.args, firstBody)
        if (pos < lines.size && lines[pos] is RawElse) {
            pos++
            elseBody = parseBlock(closers = setOf("endif"))
        }
        if (pos >= lines.size || lines[pos] !is RawEndif) {
            error("missing endif", opener.line)
        }
        pos++
        return ConditionalStmt(branches, elseBody, opener.line)
    }

    private fun parseRule(header: RawRule): RuleStmt {
        pos++
        val recipes = mutableListOf<String>()
        while (pos < lines.size && lines[pos] is RawRecipe) {
            recipes += (lines[pos] as RawRecipe).text
            pos++
        }
        val isPattern = header.targets.any { '%' in it }
        return RuleStmt(
            targets = header.targets,
            prereqs = header.prereqs,
            orderOnly = header.orderOnly,
            inlineRecipe = header.inline,
            recipes = recipes,
            isPattern = isPattern,
            line = header.line,
        )
    }

    private fun parseDefine(header: RawDefine): DefineStmt {
        pos++
        val sb = StringBuilder()
        var first = true
        while (pos < lines.size && lines[pos] !is RawEndef) {
            val l = lines[pos]
            val text = rawText(l)
            if (!first) sb.append('\n')
            sb.append(text)
            first = false
            pos++
        }
        if (pos >= lines.size) error("missing endef", header.line)
        pos++
        val flavor = opToFlavor(header.op)
        return DefineStmt(header.name, flavor, sb.toString(), header.line)
    }

    private fun parseExport(line: RawExport): MakeStmt {
        val rest = line.rest.trim()
        if (rest.isEmpty()) return ExportStmt(emptyList(), null, line.line)
        val op = findAssignOp(rest)
        if (op != null) {
            val (name, opStr, rhs) = op
            val assign =
                Assignment(
                    name = name,
                    flavor = opToFlavor(opStr),
                    value = rhs,
                    exported = true,
                    isOverride = false,
                    line = line.line,
                )
            return ExportStmt(listOf(name), assign, line.line)
        }
        return ExportStmt(splitWords(rest), null, line.line)
    }

    private fun parseOverride(line: RawOverride): MakeStmt {
        val rest = line.rest.trim()
        val op = findAssignOp(rest)
        if (op != null) {
            val (name, opStr, rhs) = op
            return Assignment(
                name = name,
                flavor = opToFlavor(opStr),
                value = rhs,
                exported = false,
                isOverride = true,
                line = line.line,
            )
        }
        return ExportStmt(splitWords(rest), null, line.line)
    }

    private fun makeAssignment(
        raw: RawAssign,
        exported: Boolean,
        isOverride: Boolean,
    ): Assignment =
        Assignment(
            name = raw.name,
            flavor = opToFlavor(raw.op),
            value = raw.rhs,
            exported = exported,
            isOverride = isOverride,
            line = raw.line,
        )

    private fun rawText(l: RawLine): String =
        when (l) {
            is RawRecipe -> "\t" + l.text
            is RawAssign -> l.name + " " + l.op + " " + l.rhs
            is RawRule -> l.targets.joinToString(" ") + ": " + l.prereqs.joinToString(" ")
            is RawInclude -> (if (l.optional) "-include " else "include ") + l.paths
            is RawConditional -> l.kind + " " + l.args
            is RawElse -> "else"
            is RawEndif -> "endif"
            is RawDefine -> "define " + l.name + " " + l.op
            is RawEndef -> "endef"
            is RawExport -> "export " + l.rest
            is RawUnexport -> "unexport " + l.names
            is RawOverride -> "override " + l.rest
            is RawBareWords -> l.text
        }

    private fun error(
        msg: String,
        ln: Int,
    ): Nothing = throw MakeParseError("Makefile:$ln: $msg", ln)
}

private fun condKind(kw: String): CondKind =
    when (kw) {
        "ifeq" -> CondKind.IFEQ
        "ifneq" -> CondKind.IFNEQ
        "ifdef" -> CondKind.IFDEF
        "ifndef" -> CondKind.IFNDEF
        else -> CondKind.IFEQ
    }

private fun opToFlavor(op: String): MacroFlavor =
    when (op) {
        "=" -> MacroFlavor.RECURSIVE
        ":=", "::=" -> MacroFlavor.IMMEDIATE
        ":::=" -> MacroFlavor.IMMEDIATE_TRIPLE
        "?=" -> MacroFlavor.CONDITIONAL
        "+=" -> MacroFlavor.APPEND
        "!=" -> MacroFlavor.SHELL
        else -> MacroFlavor.RECURSIVE
    }

internal fun splitWords(s: String): List<String> = s.split(Regex("[ \t]+")).filter { it.isNotEmpty() }

internal fun findAssignOp(s: String): Triple<String, String, String>? {
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
        if (c == '\\' && i + 1 < s.length) {
            i += 2
            continue
        }
        if (c == ':' && i + 3 < s.length && s.substring(i, i + 4) == ":::=") {
            return Triple(s.substring(0, i).trim(), ":::=", s.substring(i + 4).trim())
        }
        if (c == ':' && i + 2 < s.length && s.substring(i, i + 3) == "::=") {
            return Triple(s.substring(0, i).trim(), "::=", s.substring(i + 3).trim())
        }
        if (c == ':' && i + 1 < s.length && s[i + 1] == '=') {
            return Triple(s.substring(0, i).trim(), ":=", s.substring(i + 2).trim())
        }
        if ((c == '?' || c == '+' || c == '!') && i + 1 < s.length && s[i + 1] == '=') {
            return Triple(s.substring(0, i).trim(), "$c=", s.substring(i + 2).trim())
        }
        if (c == '=') {
            return Triple(s.substring(0, i).trim(), "=", s.substring(i + 1).trim())
        }
        i++
    }
    return null
}
