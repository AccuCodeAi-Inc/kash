package com.accucodeai.kash.tools.pathchk

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * POSIX `pathchk` — validate pathname operands. No filesystem I/O; pure
 * name analysis.
 *
 * Default checks:
 * - path is non-empty,
 * - total path length does not exceed PATH_MAX (4096),
 * - no component exceeds NAME_MAX (255).
 *
 * Flags:
 * - `-p`  restrict to the POSIX portable filename character set
 *         (`[A-Za-z0-9._-]`) and the POSIX-minimum length limits
 *         (`_POSIX_NAME_MAX` = 14, `_POSIX_PATH_MAX` = 255).
 * - `-P`  additionally reject paths containing an empty component, and
 *         paths whose first component starts with `-`.
 *
 * Exit code is 0 iff every operand passes; otherwise 1. Each failure is
 * reported on stderr but processing continues so all operands are checked.
 */
public class PathchkCommand :
    Command,
    CommandSpec {
    override val name: String = "pathchk"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private companion object {
        const val NAME_MAX = 255
        const val PATH_MAX = 4096
        const val POSIX_NAME_MAX = 14
        const val POSIX_PATH_MAX = 255
        val PORTABLE_REGEX = Regex("^[A-Za-z0-9._-]+$")
    }

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var portable = false
        var stricter = false
        val paths = mutableListOf<String>()
        var endOfOpts = false
        for (a in args) {
            if (endOfOpts) {
                paths += a
            } else if (a == "--") {
                endOfOpts = true
            } else if (a == "-h" || a == "--help") {
                ctx.stdout.writeUtf8("Usage: pathchk [-p] [-P] PATH...\n")
                return CommandResult()
            } else if (a == "-p") {
                portable = true
            } else if (a == "-P") {
                stricter = true
            } else if (a == "-pP" || a == "-Pp") {
                portable = true
                stricter = true
            } else if (a.startsWith("-") && a.length > 1 && a != "-") {
                ctx.stderr.writeUtf8("pathchk: unrecognized option: $a\n")
                return CommandResult(exitCode = 2)
            } else {
                paths += a
            }
        }
        if (paths.isEmpty()) {
            ctx.stderr.writeUtf8("pathchk: missing operand\n")
            return CommandResult(exitCode = 2)
        }

        var anyBad = false
        for (p in paths) {
            val err = validate(p, portable, stricter)
            if (err != null) {
                ctx.stderr.writeUtf8("pathchk: $err: '$p'\n")
                anyBad = true
            }
        }
        return CommandResult(exitCode = if (anyBad) 1 else 0)
    }

    internal fun validate(
        path: String,
        portable: Boolean,
        stricter: Boolean,
    ): String? {
        if (path.isEmpty()) return "empty path"

        val pathMax = if (portable) POSIX_PATH_MAX else PATH_MAX
        val nameMax = if (portable) POSIX_NAME_MAX else NAME_MAX

        if (path.length > pathMax) return "pathname too long ($pathMax)"

        // Component-wise checks. POSIX: leading '/' is allowed (absolute path).
        val components = path.split('/')
        // For absolute paths, the first element of split is "" — that's not a
        // real component, only mark as empty for stricter ("-P") when an
        // internal empty appears.
        for ((idx, c) in components.withIndex()) {
            val isLeadingSlash = (idx == 0 && c.isEmpty() && path.startsWith("/"))
            val isTrailingSlash = (idx == components.lastIndex && c.isEmpty() && path != "/" && path.endsWith("/"))
            if (c.isEmpty()) {
                if (stricter && !isLeadingSlash && !isTrailingSlash) {
                    return "empty component"
                }
                continue
            }
            if (c.length > nameMax) return "component too long ($nameMax)"
            if (portable && !PORTABLE_REGEX.matches(c)) {
                return "non-portable character in component"
            }
            if (portable && c.startsWith("-")) return "component starts with '-'"
        }

        if (stricter) {
            // First non-empty component must not begin with '-'.
            val first = components.firstOrNull { it.isNotEmpty() }
            if (first != null && first.startsWith("-")) return "leading component starts with '-'"
        }
        return null
    }
}
