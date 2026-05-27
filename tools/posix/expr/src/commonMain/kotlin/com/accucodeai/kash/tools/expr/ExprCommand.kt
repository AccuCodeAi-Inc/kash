package com.accucodeai.kash.tools.expr

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * POSIX `expr`. Evaluates an expression given as argv tokens and writes
 * the result followed by a newline to stdout.
 *
 * Exit codes:
 *  - 0 — result is non-null and non-zero.
 *  - 1 — result is null (empty string) or zero.
 *  - 2 — invalid expression / usage error.
 *  - 3 — internal error (unused; reserved).
 */
public class ExprCommand :
    Command,
    CommandSpec {
    override val name: String = "expr"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        if (args.size == 1) {
            when (args[0]) {
                "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult(exitCode = 0)
                }

                "--version" -> {
                    ctx.stdout.writeUtf8("expr (kash)\n")
                    return CommandResult(exitCode = 0)
                }
            }
        }
        if (args.isEmpty()) {
            ctx.stderr.writeUtf8("expr: missing operand\n")
            return CommandResult(exitCode = 2)
        }
        return try {
            val v = ExprParser(args).parse()
            val s = v.asString()
            ctx.stdout.writeUtf8(s)
            ctx.stdout.writeUtf8("\n")
            CommandResult(exitCode = if (v.isTruthy()) 0 else 1)
        } catch (e: ExprError) {
            ctx.stderr.writeUtf8("expr: ${e.message}\n")
            CommandResult(exitCode = e.exit)
        }
    }

    private companion object {
        const val HELP =
            "Usage: expr EXPRESSION\n" +
                "Evaluate EXPRESSION and write the result to standard output.\n"
    }
}
