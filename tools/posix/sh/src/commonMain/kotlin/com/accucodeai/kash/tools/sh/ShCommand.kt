package com.accucodeai.kash.tools.sh

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.ShellInvocation
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * POSIX `sh` (subset): a passthrough into the kash interpreter via
 * [CommandContext.shellRunner]. Supports:
 *
 * - `sh -c SCRIPT [NAME [ARG1 ...]]` — run SCRIPT as a shell command. NAME
 *   becomes `$0`, ARG1+ become `$1..$N`.
 * - `sh [--] FILE [ARG1 ...]` — read FILE and run its contents with
 *   `$0 = FILE`, `$1..$N = ARG1...`.
 * - `sh` (no args) — read stdin to EOF and run with `$0 = "kash"` and
 *   empty positional params.
 *
 * Each invocation runs in a subshell — env, cwd, locals, positional, and
 * `$0` mutations do NOT leak back to the caller. This is what
 * `find -exec sh -c '...' \;` and shell-driven `xargs sh -c '...'` rely on.
 *
 * Login-shell behavior (`sh -l` / `--login` / argv[0]==`-sh`): when set,
 * `/etc/profile` and `~/.profile` are dot-sourced before the user script,
 * matching `man bash` § "If bash is invoked with the name sh". No other
 * profile/rc files are read in `sh` mode.
 *
 * Not implemented: `-e`, `-x`, `-u`, `-o` runtime toggles (parsed but
 * not enforced beyond what the runner already handles).
 */
public class ShCommand :
    Command,
    CommandSpec {
    override val name: String = "sh"
    override val kind: CommandKind = CommandKind.BUILTIN
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val runner =
            ctx.shellRunner ?: run {
                ctx.stderr.writeUtf8("sh: not supported in this context\n")
                return CommandResult(exitCode = 2)
            }

        var i = 0
        var script: String? = null
        var scriptFile: String? = null
        var scriptName = "kash"
        var positional: List<String> = emptyList()
        var posixMode = false
        var loginShell = false
        // Bash startup flags collected from -e/-u/-C etc. Prepended to the
        // resolved script source so the runtime sees `set -<flags>` on the
        // very first parsed statement.
        val startupFlags = StringBuilder()
        // Operand parsing — minimal POSIX subset.
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-c" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("sh: -c: option requires an argument\n")
                        return CommandResult(exitCode = 2)
                    }
                    script = args[i + 1]
                    // POSIX: `sh -c SCRIPT [NAME [ARG1 ...]]` — NAME is $0,
                    // the rest are $1..$N.
                    if (i + 2 < args.size) {
                        scriptName = args[i + 2]
                        positional = args.drop(i + 3)
                    }
                    i = args.size
                }

                a == "--" -> {
                    i++
                    if (i < args.size && scriptFile == null) {
                        scriptFile = args[i]
                        scriptName = scriptFile
                        positional = args.drop(i + 1)
                    }
                    i = args.size
                }

                a == "--login" -> {
                    loginShell = true
                    i++
                }

                a == "-o" || a == "+o" -> {
                    // POSIX `sh -o NAME` enables an option. We accept the
                    // names bash recognizes but only honor `posix` (which
                    // controls a small surface — most relevantly, alias
                    // substitution of reserved words is suppressed). Other
                    // names are silently accepted no-ops so scripts that
                    // do `set -o pipefail` etc. don't choke at startup.
                    if (i + 1 >= args.size) {
                        i++
                    } else {
                        val name = args[i + 1]
                        if (name == "posix" && a == "-o") posixMode = true
                        if (name == "posix" && a == "+o") posixMode = false
                        i += 2
                    }
                }

                a.startsWith("-") && a.length > 1 -> {
                    // POSIX combined flags: `-ce CMD` ≡ `-c -e CMD`, `-ec CMD`
                    // same. We recognize `-e`/`-u`/`-x`/`-v`/`-o` (silently
                    // accepted, runner-side toggles are handled by the
                    // interpreter on first parse, not here) plus `-c` which
                    // requires a CMD operand. Anything else is unknown.
                    val flags = a.drop(1)
                    var hasC = false
                    var unknown: Char? = null
                    for (c in flags) {
                        when (c) {
                            'c' -> {
                                hasC = true
                            }

                            'l' -> {
                                loginShell = true
                            }

                            'e', 'u', 'C' -> {
                                if (startupFlags.indexOf(c) < 0) startupFlags.append(c)
                            }

                            'x', 'v', 'a', 'h', 'm', 'n', 'B', 'H', 'P', 'T' -> {
                                Unit
                            }

                            else -> {
                                unknown = c
                                break
                            }
                        }
                    }
                    if (unknown != null) {
                        ctx.stderr.writeUtf8("sh: unknown option: -$unknown\n")
                        return CommandResult(exitCode = 2)
                    }
                    if (hasC) {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("sh: -c: option requires an argument\n")
                            return CommandResult(exitCode = 2)
                        }
                        script = args[i + 1]
                        if (i + 2 < args.size) {
                            scriptName = args[i + 2]
                            positional = args.drop(i + 3)
                        }
                        i = args.size
                    } else {
                        i++
                    }
                }

                else -> {
                    if (scriptFile == null) {
                        scriptFile = a
                        scriptName = a
                        positional = args.drop(i + 1)
                        i = args.size
                    } else {
                        i++
                    }
                }
            }
        }

        val rawSource: String =
            when {
                script != null -> {
                    script
                }

                scriptFile != null -> {
                    val resolved = Paths.resolve(ctx.process.cwd, scriptFile)
                    if (!ctx.process.fs.exists(resolved)) {
                        ctx.stderr.writeUtf8("sh: $scriptFile: No such file or directory\n")
                        return CommandResult(exitCode = 127)
                    }
                    ctx.process.fs
                        .readBytes(resolved)
                        .decodeToString()
                }

                else -> {
                    ctx.stdin.readUtf8Text()
                }
            }
        // Login-shell profile prelude (`sh -l`): per `man bash`,
        // when invoked as `sh`, only /etc/profile and ~/.profile are
        // read — not the bash-style `~/.bash_profile` cascade. Use the
        // POSIX dot-builtin (silently skip if absent via `[ -r FILE ]
        // && . FILE`). We let the runner's error path surface any read
        // failure, matching how the user's own `. /etc/profile` would
        // behave from a script.
        val loginPrelude: String =
            if (loginShell) {
                "[ -r /etc/profile ] && . /etc/profile; " +
                    "[ -r \"\${HOME:-}/.profile\" ] && . \"\${HOME:-}/.profile\"; "
            } else {
                ""
            }
        // Prepend `set -<flags>` so the runtime-side errexit/nounset/etc.
        // are active for the very first parsed statement of the script.
        // Use `;` (not `\n`) as the separator so the user's first script
        // line still parses as line 1 — bash's `-uc 'cmd'` diagnostics
        // cite `line 1` regardless of how the option was passed.
        val source: String =
            buildString {
                if (loginPrelude.isNotEmpty()) append(loginPrelude)
                if (startupFlags.isNotEmpty()) {
                    append("set -")
                    append(startupFlags)
                    append("; ")
                }
                append(rawSource)
            }

        val exit =
            runner.run(
                ShellInvocation(
                    script = source,
                    scriptName = scriptName,
                    positional = positional,
                    stdin = ctx.stdin,
                    stdout = ctx.stdout,
                    stderr = ctx.stderr,
                    isCLine = script != null,
                    posixMode = posixMode,
                ),
            )
        return CommandResult(exitCode = exit)
    }
}
