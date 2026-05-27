package com.accucodeai.kash.tools.make

public sealed interface MakeStmt {
    public val line: Int
}

public data class Assignment(
    val name: String,
    val flavor: MacroFlavor,
    val value: String,
    val exported: Boolean = false,
    val isOverride: Boolean = false,
    override val line: Int,
) : MakeStmt

public data class RuleStmt(
    val targets: List<String>,
    val prereqs: List<String>,
    val orderOnly: List<String>,
    val inlineRecipe: String?,
    val recipes: List<String>,
    val isPattern: Boolean,
    override val line: Int,
) : MakeStmt

public data class IncludeStmt(
    val paths: List<String>,
    val optional: Boolean,
    override val line: Int,
) : MakeStmt

public data class ConditionalStmt(
    val branches: List<CondBranch>,
    val elseBody: List<MakeStmt>?,
    override val line: Int,
) : MakeStmt

public data class CondBranch(
    val kind: CondKind,
    val args: String,
    val body: List<MakeStmt>,
)

public enum class CondKind { IFEQ, IFNEQ, IFDEF, IFNDEF }

public data class ExportStmt(
    val names: List<String>,
    val embedded: Assignment?,
    override val line: Int,
) : MakeStmt

public data class UnexportStmt(
    val names: List<String>,
    override val line: Int,
) : MakeStmt

public data class DefineStmt(
    val name: String,
    val flavor: MacroFlavor,
    val body: String,
    override val line: Int,
) : MakeStmt

public enum class MacroFlavor {
    RECURSIVE,
    IMMEDIATE,
    IMMEDIATE_TRIPLE,
    CONDITIONAL,
    APPEND,
    SHELL,
}

public data class Makefile(
    val statements: List<MakeStmt>,
)
