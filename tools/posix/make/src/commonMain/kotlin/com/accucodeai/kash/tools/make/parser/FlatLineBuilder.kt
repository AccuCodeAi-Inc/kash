package com.accucodeai.kash.tools.make.parser

import com.accucodeai.kash.tools.make.parser.antlr.MakefileParser

internal sealed interface RawLine {
    val line: Int
}

internal data class RawRecipe(
    val text: String,
    override val line: Int,
) : RawLine

internal data class RawAssign(
    val name: String,
    val op: String,
    val rhs: String,
    override val line: Int,
) : RawLine

internal data class RawRule(
    val header: String,
    val targets: List<String>,
    val prereqs: List<String>,
    val orderOnly: List<String>,
    val inline: String?,
    override val line: Int,
) : RawLine

internal data class RawInclude(
    val paths: String,
    val optional: Boolean,
    override val line: Int,
) : RawLine

internal data class RawConditional(
    val kind: String,
    val args: String,
    override val line: Int,
) : RawLine

internal data class RawElse(
    override val line: Int,
) : RawLine

internal data class RawEndif(
    override val line: Int,
) : RawLine

internal data class RawDefine(
    val name: String,
    val op: String,
    override val line: Int,
) : RawLine

internal data class RawEndef(
    override val line: Int,
) : RawLine

internal data class RawExport(
    val rest: String,
    override val line: Int,
) : RawLine

internal data class RawUnexport(
    val names: String,
    override val line: Int,
) : RawLine

internal data class RawOverride(
    val rest: String,
    override val line: Int,
) : RawLine

internal data class RawBareWords(
    val text: String,
    override val line: Int,
) : RawLine

internal class FlatLineBuilder {
    fun build(ctx: MakefileParser.ProgramContext): List<RawLine> {
        val out = mutableListOf<RawLine>()
        for (lineCtx in ctx.line()) {
            when (lineCtx) {
                is MakefileParser.RecipeLineFormContext -> {
                    val tok = lineCtx.RECIPE_LINE().symbol
                    val raw = tok.text ?: ""
                    val body = if (raw.startsWith('\t')) raw.substring(1) else raw
                    out += RawRecipe(body, tok.line)
                }

                is MakefileParser.BlankLineContext -> {
                    // skip
                }

                is MakefileParser.LogicalLineFormContext -> {
                    val logical = lineCtx.logicalLine()
                    val ln = startLine(logical)
                    val line = logicalLine(logical, ln)
                    if (line != null) out += line
                }

                else -> {}
            }
        }
        return out
    }

    private fun logicalLine(
        ctx: MakefileParser.LogicalLineContext,
        ln: Int,
    ): RawLine? =
        when (ctx) {
            is MakefileParser.LineDirectiveContext -> {
                directive(ctx.directive(), ln)
            }

            is MakefileParser.LineCondOpenerContext -> {
                condOpener(ctx.conditionalOpener(), ln)
            }

            is MakefileParser.LineElseContext -> {
                RawElse(ln)
            }

            is MakefileParser.LineEndifContext -> {
                RawEndif(ln)
            }

            is MakefileParser.LineDefineContext -> {
                val rest = (ctx.words()?.let { wordsText(it) } ?: "").trim()
                val (name, op) = parseDefineHeader(rest)
                RawDefine(name, op, ln)
            }

            is MakefileParser.LineEndefContext -> {
                RawEndef(ln)
            }

            is MakefileParser.LineAssignRuleTextContext -> {
                assignOrRule(ctx.assignOrRuleOrText(), ln)
            }

            else -> {
                null
            }
        }

    private fun directive(
        ctx: MakefileParser.DirectiveContext,
        ln: Int,
    ): RawLine =
        when (ctx) {
            is MakefileParser.IncludeDirectiveContext -> {
                val optional = ctx.KW_DASH_INCLUDE() != null || ctx.KW_SINCLUDE() != null
                val paths = ctx.words()?.let { wordsText(it) } ?: ""
                RawInclude(paths.trim(), optional, ln)
            }

            is MakefileParser.ExportDirectiveContext -> {
                val rest = ctx.words()?.let { wordsText(it) } ?: ""
                RawExport(rest, ln)
            }

            is MakefileParser.ExportAssignDirectiveContext -> {
                val name = ctx.words(0)?.let { wordsText(it) } ?: ""
                val op = ctx.assignOp().text
                val rhs = ctx.words().getOrNull(1)?.let { wordsText(it) } ?: ""
                RawExport("$name $op $rhs", ln)
            }

            is MakefileParser.UnexportDirectiveContext -> {
                val rest = wordsText(ctx.words())
                RawUnexport(rest, ln)
            }

            is MakefileParser.OverrideDirectiveContext -> {
                val rest = textOfAssignOrRule(ctx.assignOrRuleOrText())
                RawOverride(rest, ln)
            }

            else -> {
                RawBareWords("", ln)
            }
        }

    private fun condOpener(
        ctx: MakefileParser.ConditionalOpenerContext,
        ln: Int,
    ): RawConditional {
        val kw =
            when {
                ctx.KW_IFEQ() != null -> "ifeq"
                ctx.KW_IFNEQ() != null -> "ifneq"
                ctx.KW_IFDEF() != null -> "ifdef"
                ctx.KW_IFNDEF() != null -> "ifndef"
                else -> "ifeq"
            }
        val args = ctx.words()?.let { wordsText(it) } ?: ""
        return RawConditional(kw, args.trim(), ln)
    }

    private fun assignOrRule(
        ctx: MakefileParser.AssignOrRuleOrTextContext,
        ln: Int,
    ): RawLine =
        when (ctx) {
            is MakefileParser.FormAssignContext -> {
                val name = ctx.words(0)?.let { wordsText(it) }?.trim() ?: ""
                val op = ctx.assignOp().text
                val rhs = ctx.words().getOrNull(1)?.let { wordsText(it) } ?: ""
                RawAssign(name, op, rhs.trim(), ln)
            }

            is MakefileParser.FormRuleContext -> {
                val (targets, preWords, inline) = decomposeRuleCtx(ctx)
                val (prereqs, orderOnly) = splitOrderOnly(preWords)
                RawRule(
                    header = "",
                    targets = targets,
                    prereqs = prereqs,
                    orderOnly = orderOnly,
                    inline = inline,
                    line = ln,
                )
            }

            is MakefileParser.FormBareWordsContext -> {
                val txt = ctx.words().joinToString(" ") { wordsText(it) }
                RawBareWords(txt, ln)
            }

            else -> {
                RawBareWords("", ln)
            }
        }

    private fun decomposeRuleCtx(ctx: MakefileParser.FormRuleContext): Triple<List<String>, List<String>, String?> {
        val targets = mutableListOf<String>()
        val prereqs = mutableListOf<String>()
        var inline: String? = null
        var seenColon = false
        var seenSemi = false
        val inlineBuf = StringBuilder()
        for (child in ctx.children ?: emptyList()) {
            val text = child.text
            when {
                text == ":" -> {
                    seenColon = true
                }

                text == ";" -> {
                    seenSemi = true
                }

                child is MakefileParser.WordsContext -> {
                    val ws = wordList(child)
                    when {
                        seenSemi -> {
                            if (inlineBuf.isNotEmpty()) inlineBuf.append(' ')
                            inlineBuf.append(ws.joinToString(" "))
                        }

                        seenColon -> {
                            prereqs += ws
                        }

                        else -> {
                            targets += ws
                        }
                    }
                }
            }
        }
        if (seenSemi) inline = inlineBuf.toString()
        return Triple(targets, prereqs, inline)
    }

    private fun textOfAssignOrRule(ctx: MakefileParser.AssignOrRuleOrTextContext): String {
        val start = ctx.start?.startIndex ?: return ""
        val stop = ctx.stop?.stopIndex ?: return ""
        val src =
            ctx.start?.inputStream?.getText(
                org.antlr.v4.kotlinruntime.misc
                    .Interval(start, stop),
            ) ?: ""
        return src
    }

    private fun wordsText(ctx: MakefileParser.WordsContext): String =
        ctx.WORD().joinToString(" ") { it.symbol.text ?: "" }

    private fun wordList(ctx: MakefileParser.WordsContext?): List<String> =
        ctx?.WORD()?.map { it.symbol.text ?: "" }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun startLine(ctx: MakefileParser.LogicalLineContext): Int = ctx.start?.line ?: 0

    private fun parseDefineHeader(rest: String): Pair<String, String> {
        val trimmed = rest.trim()
        for (op in listOf(":::=", "::=", ":=", "?=", "+=", "!=", "=")) {
            val idx = trimmed.lastIndexOf(op)
            if (idx > 0 && trimmed.substring(idx) == op) {
                return trimmed.substring(0, idx).trim() to op
            }
        }
        return trimmed to "="
    }

    private fun splitOrderOnly(words: List<String>): Pair<List<String>, List<String>> {
        val idx = words.indexOfFirst { it.contains('|') }
        if (idx < 0) return words to emptyList()
        val w = words[idx]
        val pipeAt = w.indexOf('|')
        val before = w.substring(0, pipeAt)
        val after = w.substring(pipeAt + 1)
        val pre = words.subList(0, idx).toMutableList().apply { if (before.isNotEmpty()) add(before) }
        val ord = mutableListOf<String>()
        if (after.isNotEmpty()) ord += after
        ord += words.subList(idx + 1, words.size)
        return pre to ord
    }
}
