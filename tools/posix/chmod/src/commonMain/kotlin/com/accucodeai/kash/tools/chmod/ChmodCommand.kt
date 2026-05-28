package com.accucodeai.kash.tools.chmod

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.DirWalkGuard
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.fs.Paths

/**
 * `chmod [-R] MODE FILE...`. Supports the POSIX mode-spec grammar
 * (https://pubs.opengroup.org/onlinepubs/9699919799/utilities/chmod.html):
 *
 *  - **Octal**: `chmod 0755 file` / `chmod 4755 file` — full bit pattern.
 *  - **Symbolic**: comma-separated clauses, each `[ugoa]*[+-=][rwxst]*`.
 *    Examples: `u+x`, `go-w`, `a=r`, `u=rwx,go=rx`, `+t`, `u+s`.
 *
 * Symbolic perm chars: `r`/`w`/`x` for read/write/execute, `s` for
 * setuid (with `u`) or setgid (with `g`), `t` for the sticky bit. We
 * skip GNU's `X` (conditional exec), copy-from-class (`g=u`), and umask
 * application for bare ops — bare `+x` is treated as `a+x` directly.
 *
 * `-R` walks directories. Errors on individual paths report to stderr but
 * processing continues; exit is 1 if any path failed, 0 otherwise.
 */
public class ChmodCommand :
    Command,
    CommandSpec {
    override val name: String = "chmod"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.STATEFUL)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var recursive = false
        var i = 0
        // Flag parsing — stop at first non-flag (which is the mode spec).
        while (i < args.size) {
            val a = args[i]
            if (a == "--") {
                i++
                break
            }
            if (a.startsWith("-") && a.length > 1 && !looksLikeMode(a)) {
                for (ch in a.drop(1)) {
                    when (ch) {
                        'R' -> {
                            recursive = true
                        }

                        'v', 'c', 'f' -> {
                            Unit
                        }

                        // verbose/quiet — accepted, no-op
                        else -> {
                            ctx.stderr.writeUtf8("chmod: invalid option -- '$ch'\n")
                            return CommandResult(exitCode = 2)
                        }
                    }
                }
                i++
            } else {
                break
            }
        }

        if (i >= args.size) {
            ctx.stderr.writeUtf8("chmod: missing operand\n")
            return CommandResult(exitCode = 2)
        }
        val modeSpec = args[i]
        i++
        // Allow `chmod MODE -- FILE...` to separate the path list explicitly.
        if (i < args.size && args[i] == "--") i++
        if (i >= args.size) {
            ctx.stderr.writeUtf8("chmod: missing operand after '$modeSpec'\n")
            return CommandResult(exitCode = 2)
        }
        val paths = args.drop(i)

        var anyError = false
        for (operand in paths) {
            val resolved = Paths.resolve(ctx.process.cwd, operand)
            try {
                applyTo(ctx, resolved, modeSpec, recursive, depth = 0, guard = DirWalkGuard(ctx.process.fs))
            } catch (e: ModeParseError) {
                ctx.stderr.writeUtf8("chmod: invalid mode: '$modeSpec'\n")
                return CommandResult(exitCode = 2)
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("chmod: cannot access '$operand': No such file or directory\n")
                anyError = true
            } catch (e: UnsupportedOperationException) {
                ctx.stderr.writeUtf8("chmod: ${e.message}\n")
                return CommandResult(exitCode = 1)
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private fun applyTo(
        ctx: CommandContext,
        path: String,
        modeSpec: String,
        recursive: Boolean,
        depth: Int,
        guard: DirWalkGuard,
    ) {
        val stat = ctx.process.fs.stat(path)
        val newMode = computeMode(stat.mode, modeSpec)
        ctx.process.fs.chmod(path, newMode)
        // Recurse only into real directories — never descend a symlinked dir
        // discovered during the walk (GNU chmod -R default), which also makes
        // a symlink cycle terminate. The command-line operand (depth 0) is
        // followed regardless.
        if (recursive && stat.type == FileType.DIRECTORY &&
            (depth == 0 || guard.shouldDescend(path, depth))
        ) {
            for (child in ctx.process.fs.listStat(path)) {
                applyTo(ctx, child.path, modeSpec, recursive, depth + 1, guard)
            }
        }
    }

    private fun looksLikeMode(arg: String): Boolean {
        // POSIX: `chmod -w file` is ambiguous — could be flag or mode.
        // Real chmod treats it as mode if it's a single permission char or
        // an octal literal. We use the same heuristic: arg starts with -
        // followed by [ugoaA0-9rwxst+=,] only.
        if (arg.length < 2) return false
        return arg.drop(1).all { it in "ugoaA0-9rwxst+=,XS" }
    }
}

internal class ModeParseError(
    message: String,
) : RuntimeException(message)

/**
 * Apply [modeSpec] to [current] mode bits, returning the new bits.
 *
 * Octal numbers (1-4 digits) replace mode entirely. Symbolic specs are
 * comma-separated clauses applied left-to-right against the running mode.
 */
internal fun computeMode(
    current: Int,
    modeSpec: String,
): Int {
    if (modeSpec.isEmpty()) throw ModeParseError("empty mode")
    // Octal: all digits, no symbolic chars.
    if (modeSpec.all { it in '0'..'7' }) {
        if (modeSpec.length > 4) throw ModeParseError("octal mode too long")
        return modeSpec.toInt(8)
    }
    var mode = current
    for (clause in modeSpec.split(',')) {
        if (clause.isEmpty()) throw ModeParseError("empty clause")
        mode = applyClause(mode, clause)
    }
    return mode
}

private fun applyClause(
    current: Int,
    clause: String,
): Int {
    val opIdx = clause.indexOfFirst { it == '+' || it == '-' || it == '=' }
    if (opIdx < 0) throw ModeParseError("clause missing op: '$clause'")
    val whoStr = clause.substring(0, opIdx).ifEmpty { "a" }
    val op = clause[opIdx]
    val permStr = clause.substring(opIdx + 1)

    // Validate `who` chars.
    for (ch in whoStr) {
        if (ch !in "ugoa") throw ModeParseError("bad who char '$ch'")
    }
    // Validate `perm` chars.
    for (ch in permStr) {
        if (ch !in "rwxstX") throw ModeParseError("bad perm char '$ch'")
    }

    val whoMask = whoMask(whoStr) // bits affected by this clause for r/w/x
    var rwxBits = 0
    if ('r' in permStr) rwxBits = rwxBits or 0b100_100_100
    if ('w' in permStr) rwxBits = rwxBits or 0b010_010_010
    if ('x' in permStr) rwxBits = rwxBits or 0b001_001_001
    // X — set exec only if currently executable somewhere or is a directory.
    // We approximate "directory" by leaving this as a runtime check elsewhere;
    // for the simple case treat as plain `x` if any exec bit set.
    if ('X' in permStr && (current and 0b001_001_001 != 0)) {
        rwxBits = rwxBits or 0b001_001_001
    }
    val rwxEffective = rwxBits and whoMask

    // Special bits: s/t map to specific positions regardless of who.
    var specialAdd = 0
    var specialMask = 0
    if ('s' in permStr) {
        if ('u' in whoStr || whoStr == "a") {
            specialAdd = specialAdd or 0b100_000_000_000 // setuid
            specialMask = specialMask or 0b100_000_000_000
        }
        if ('g' in whoStr || whoStr == "a") {
            specialAdd = specialAdd or 0b010_000_000_000 // setgid
            specialMask = specialMask or 0b010_000_000_000
        }
    }
    if ('t' in permStr) {
        specialAdd = specialAdd or 0b001_000_000_000 // sticky
        specialMask = specialMask or 0b001_000_000_000
    }

    return when (op) {
        '+' -> {
            current or rwxEffective or specialAdd
        }

        '-' -> {
            current and (rwxEffective or specialAdd).inv()
        }

        '=' -> {
            // Clear the slots covered by who (rwx and any special bits this clause
            // could touch), then set the new bits.
            val clearMask = (whoMask or specialMask).inv()
            (current and clearMask) or rwxEffective or specialAdd
        }

        else -> {
            throw ModeParseError("impossible op $op")
        }
    }
}

private fun whoMask(who: String): Int {
    var mask = 0
    if ('u' in who || who == "a") mask = mask or 0b111_000_000
    if ('g' in who || who == "a") mask = mask or 0b000_111_000
    if ('o' in who || who == "a") mask = mask or 0b000_000_111
    return mask
}
