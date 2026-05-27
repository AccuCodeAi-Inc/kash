package com.accucodeai.kash.tools.test

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * POSIX `test` / `[`. Returns exit code 0 if the expression is true, 1 if false,
 * 2 on usage error.
 */
public class TestCommand :
    Command,
    CommandSpec {
    override val name: String = "test"
    override val kind: CommandKind = CommandKind.BUILTIN
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.BASH_BUILTIN)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult = evalTest(name = "test", rawArgs = args, requireBracket = false, ctx = ctx)
}

public class BracketTestCommand :
    Command,
    CommandSpec {
    override val name: String = "["
    override val kind: CommandKind = CommandKind.BUILTIN
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.BASH_BUILTIN)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult = evalTest(name = "[", rawArgs = args, requireBracket = true, ctx = ctx)
}

private suspend fun evalTest(
    name: String,
    rawArgs: List<String>,
    requireBracket: Boolean,
    ctx: CommandContext,
): CommandResult {
    val args =
        if (requireBracket) {
            if (rawArgs.lastOrNull() != "]") {
                ctx.stderr.writeUtf8("$name: missing ']'\n")
                return CommandResult(exitCode = 2)
            }
            rawArgs.dropLast(1)
        } else {
            rawArgs
        }

    if (args.isEmpty()) return CommandResult(exitCode = 1)
    return try {
        val result = TestEvaluator(args, ctx).parseOr()
        CommandResult(exitCode = if (result) 0 else 1)
    } catch (e: TestSyntaxError) {
        ctx.stderr.writeUtf8("$name: ${e.message}\n")
        CommandResult(exitCode = 2)
    } catch (_: AlreadyDiagnosedError) {
        CommandResult(exitCode = 2)
    }
}

// Sentinel thrown when the evaluator has already written the bash-shape
// diagnostic itself (so the wrapper must NOT prefix another `test:` line).
private class AlreadyDiagnosedError : RuntimeException()

// ---------------------------------------------------------------------------
// test/[ expression evaluator. Used by TestCommand and BracketTestCommand.

private class TestSyntaxError(
    message: String,
) : RuntimeException(message)

private class TestEvaluator(
    private val args: List<String>,
    private val ctx: CommandContext,
) {
    private var i = 0

    suspend fun parseOr(): Boolean {
        var left = parseAnd()
        while (i < args.size && args[i] == "-o") {
            i++
            left = parseAnd() || left
        }
        return left
    }

    private suspend fun parseAnd(): Boolean {
        var left = parseUnary()
        while (i < args.size && args[i] == "-a") {
            i++
            left = parseUnary() && left
        }
        return left
    }

    private suspend fun parseUnary(): Boolean {
        if (i >= args.size) throw TestSyntaxError("unexpected end of expression")
        if (args[i] == "!") {
            i++
            return !parseUnary()
        }
        if (args[i] == "(") {
            i++
            val r = parseOr()
            if (i >= args.size || args[i] != ")") throw TestSyntaxError("missing ')'")
            i++
            return r
        }
        return parsePrimary()
    }

    private suspend fun parsePrimary(): Boolean {
        if (i + 2 < args.size && args[i + 1] in BINARY_OPS) {
            val a = args[i]
            val op = args[i + 1]
            val b = args[i + 2]
            i += 3
            return when (op) {
                "=", "==" -> a == b
                "!=" -> a != b
                "-eq" -> intCmp(a, b) { x, y -> x == y }
                "-ne" -> intCmp(a, b) { x, y -> x != y }
                "-lt" -> intCmp(a, b) { x, y -> x < y }
                "-le" -> intCmp(a, b) { x, y -> x <= y }
                "-gt" -> intCmp(a, b) { x, y -> x > y }
                "-ge" -> intCmp(a, b) { x, y -> x >= y }
                else -> throw TestSyntaxError("unknown binary operator: $op")
            }
        }
        val tok = args[i]
        if (tok.startsWith("-") && tok.length == 2 && i + 1 < args.size) {
            val operand = args[i + 1]
            i += 2
            return when (tok) {
                "-z" -> {
                    operand.isEmpty()
                }

                "-n" -> {
                    operand.isNotEmpty()
                }

                "-v" -> {
                    // bash-extension `-v NAME` / `-v NAME[sub]`: true if
                    // variable (or array element) is set. We approximate
                    // via env for plain names. For the bracketed form,
                    // first apply the array_expand_once security baseline:
                    // a literal `$(...)` in the subscript must not be
                    // re-evaluated. Diagnose with an arith-error shape
                    // (matches the array normalize rule that folds it to
                    // <ARITH_ERR>).
                    val lb = operand.indexOf('[')
                    val rb = operand.lastIndexOf(']')
                    if (lb > 0 && rb == operand.length - 1) {
                        val sub = operand.substring(lb + 1, rb)
                        if ("\$(" in sub) {
                            // Emit the bash-shape arith error directly and
                            // fail the test with exit 2 via a sentinel
                            // exception subclass that the dispatcher
                            // turns into the right shell-side outcome
                            // without prefixing "test:" again.
                            ctx.stderr.writeUtf8(
                                "${ctx.shellDiagPrefix}$sub: arithmetic syntax error: operand expected (error token is \"$sub\")\n",
                            )
                            throw AlreadyDiagnosedError()
                        }
                        val base = operand.substring(0, lb)
                        ctx.process.env.containsKey(base)
                    } else {
                        ctx.process.env.containsKey(operand)
                    }
                }

                "-e" -> {
                    // POSIX `-e` follows symlinks: dangling link → false. Use
                    // stat (which follows) rather than exists (lstat-style).
                    val p =
                        com.accucodeai.kash.fs.Paths
                            .resolve(ctx.process.cwd, operand)
                    try {
                        ctx.process.fs.stat(p)
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }

                "-f" -> {
                    val p =
                        com.accucodeai.kash.fs.Paths
                            .resolve(ctx.process.cwd, operand)
                    try {
                        ctx.process.fs
                            .stat(p)
                            .type == com.accucodeai.kash.fs.FileType.REGULAR
                    } catch (_: Throwable) {
                        false
                    }
                }

                "-d" -> {
                    ctx.process.fs.isDirectory(
                        com.accucodeai.kash.fs.Paths
                            .resolve(ctx.process.cwd, operand),
                    )
                }

                "-r", "-w", "-x" -> {
                    // POSIX: check the owner-perm bit of the resolved file (we
                    // don't model multiple users, so owner bits are the only
                    // ones with meaning). Follows symlinks. Missing → false.
                    val p =
                        com.accucodeai.kash.fs.Paths
                            .resolve(ctx.process.cwd, operand)
                    try {
                        val st = ctx.process.fs.stat(p)
                        val bit =
                            when (tok) {
                                "-r" -> 0b100_000_000

                                // 0o400
                                "-w" -> 0b010_000_000

                                // 0o200
                                else -> 0b001_000_000 // 0o100
                            }
                        (st.mode and bit) != 0
                    } catch (_: Throwable) {
                        false
                    }
                }

                "-s" -> {
                    val p =
                        com.accucodeai.kash.fs.Paths
                            .resolve(ctx.process.cwd, operand)
                    ctx.process.fs.exists(p) && !ctx.process.fs.isDirectory(p) &&
                        try {
                            ctx.process.fs
                                .readBytes(p)
                                .isNotEmpty()
                        } catch (_: Throwable) {
                            false
                        }
                }

                "-t" -> {
                    // POSIX `-t N`: true iff fd N is a terminal. Backed by
                    // the per-fd bits the interpreter sets on
                    // [CommandContext] — these account for pipe stages and
                    // redirections, not just session-level interactivity.
                    when (operand.toIntOrNull()) {
                        0 -> ctx.process.isTty(0)
                        1 -> ctx.process.isTty(1)
                        2 -> ctx.process.isTty(2)
                        else -> false
                    }
                }

                "-L", "-h" -> {
                    val p =
                        com.accucodeai.kash.fs.Paths
                            .resolve(ctx.process.cwd, operand)
                    try {
                        ctx.process.fs
                            .statLink(p)
                            .type == com.accucodeai.kash.fs.FileType.SYMLINK
                    } catch (_: com.accucodeai.kash.fs.FileNotFound) {
                        false
                    } catch (_: Throwable) {
                        false
                    }
                }

                "-b", "-c", "-p", "-S" -> {
                    val p =
                        com.accucodeai.kash.fs.Paths
                            .resolve(ctx.process.cwd, operand)
                    try {
                        val want =
                            when (tok) {
                                "-b" -> com.accucodeai.kash.fs.FileType.BLOCK
                                "-c" -> com.accucodeai.kash.fs.FileType.CHAR
                                "-p" -> com.accucodeai.kash.fs.FileType.FIFO
                                else -> com.accucodeai.kash.fs.FileType.SOCKET
                            }
                        ctx.process.fs
                            .stat(p)
                            .type == want
                    } catch (_: Throwable) {
                        false
                    }
                }

                "-g", "-u", "-k" -> {
                    val p =
                        com.accucodeai.kash.fs.Paths
                            .resolve(ctx.process.cwd, operand)
                    try {
                        val bit =
                            when (tok) {
                                "-u" -> 0b100_000_000_000
                                "-g" -> 0b010_000_000_000
                                else -> 0b001_000_000_000
                            }
                        (
                            ctx.process.fs
                                .stat(p)
                                .mode and bit
                        ) != 0
                    } catch (_: Throwable) {
                        false
                    }
                }

                else -> {
                    throw TestSyntaxError("unknown unary operator: $tok")
                }
            }
        }
        i++
        return tok.isNotEmpty()
    }

    private inline fun intCmp(
        a: String,
        b: String,
        cmp: (Long, Long) -> Boolean,
    ): Boolean {
        val x = a.toLongOrNull() ?: throw TestSyntaxError("integer expression expected: $a")
        val y = b.toLongOrNull() ?: throw TestSyntaxError("integer expression expected: $b")
        return cmp(x, y)
    }

    companion object {
        val BINARY_OPS =
            setOf(
                "=",
                "==",
                "!=",
                "-eq",
                "-ne",
                "-lt",
                "-le",
                "-gt",
                "-ge",
            )
    }
}
