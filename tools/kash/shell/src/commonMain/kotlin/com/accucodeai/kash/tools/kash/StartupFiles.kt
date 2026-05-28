package com.accucodeai.kash.tools.kash

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.EmptySuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter
import com.accucodeai.kash.interpreter.ScriptStatementSource
import com.accucodeai.kash.parser.Parser

/**
 * Bash-parity startup-file sourcing, per `man bash` § INVOCATION. Each
 * helper feeds the named file through the live [Interpreter] using the
 * same parse-and-run path the interactive REPL uses for `~/.kashrc`,
 * so env, functions, aliases, and shell options set in a startup file
 * are visible to everything that runs after it.
 *
 * Error policy (matches the spirit of "if any of the files exist but
 * cannot be read, bash reports an error"):
 *
 * - Missing file → silent skip (the file is optional).
 * - Read failure (exists, but I/O throws) → `kash: <path>: <msg>` on stderr.
 * - Parse / runtime failure → `kash: <path>: <msg>` on stderr; the rest
 *   of the cascade continues so a user's broken `.kashrc` doesn't brick
 *   the shell.
 *
 * Naming convention (kash-branded, paralleling the existing `~/.kashrc`):
 *
 * | bash                | kash                                     |
 * |---------------------|------------------------------------------|
 * | `/etc/profile`      | `/etc/profile` (POSIX-shared)            |
 * | `/etc/bashrc`       | `/etc/kashrc`                            |
 * | `~/.bash_profile`   | `~/.kash_profile`                        |
 * | `~/.bash_login`     | `~/.kash_login`                          |
 * | `~/.profile`        | `~/.profile` (POSIX-shared)              |
 * | `~/.bashrc`         | `~/.kashrc`                              |
 * | `~/.bash_logout`    | `~/.kash_logout`                         |
 * | `$BASH_ENV`         | `$KASH_ENV` (also honors `$BASH_ENV`)    |
 * | `$ENV`              | `$ENV` (POSIX-shared)                    |
 */
internal object StartupFiles {
    /**
     * Source a single file into [interp]. Returns true iff the file
     * existed (whether or not execution succeeded) — callers use this
     * to short-circuit the `kash_profile|kash_login|.profile` cascade
     * on the first hit, matching bash.
     */
    suspend fun sourceFile(
        path: String,
        interp: Interpreter,
        ctx: CommandContext,
    ): Boolean {
        val fs = ctx.process.fs
        if (!fs.exists(path)) return false
        if (fs.isDirectory(path)) return false
        val text =
            try {
                fs.readBytes(path).decodeToString()
            } catch (t: Throwable) {
                // "Exists but unreadable" — bash documents this as a
                // reported error, distinct from "missing" (silent skip).
                ctx.stderr.writeUtf8("kash: $path: ${t.message ?: "read error"}\n")
                return true
            }
        try {
            val ast = Parser(text, interp.aliasResolver).parseScript()
            interp.runStreaming(
                source = ScriptStatementSource(ast.statements),
                initialStdin = EmptySuspendSource,
                stdout = ctx.stdout,
                stderr = ctx.stderr,
                stdinIsTty = false,
            )
        } catch (e: IllegalStateException) {
            ctx.stderr.writeUtf8("kash: $path: ${e.message ?: "parse error"}\n")
        } catch (t: Throwable) {
            ctx.stderr.writeUtf8("kash: $path: ${t.message ?: t::class.simpleName}\n")
        }
        return true
    }

    /**
     * Interactive login shell entry cascade: `/etc/profile`, then the
     * first readable of `~/.kash_profile` → `~/.kash_login` → `~/.profile`.
     * Suppressed entirely by `--noprofile`.
     */
    suspend fun sourceLoginProfiles(
        interp: Interpreter,
        ctx: CommandContext,
    ) {
        sourceFile("/etc/profile", interp, ctx)
        val home = home(ctx)
        for (name in LOGIN_PERSONAL) {
            if (sourceFile(joinHome(home, name), interp, ctx)) break
        }
    }

    /**
     * Interactive non-login shell entry: `/etc/kashrc`, then the user
     * file (default `~/.kashrc`, overridable via `--rcfile FILE` /
     * `--init-file FILE`). Suppressed entirely by `--norc`.
     */
    suspend fun sourceInteractiveRc(
        interp: Interpreter,
        ctx: CommandContext,
        rcFileOverride: String?,
    ) {
        sourceFile("/etc/kashrc", interp, ctx)
        val userRc = rcFileOverride ?: joinHome(home(ctx), ".kashrc")
        sourceFile(userRc, interp, ctx)
    }

    /**
     * Non-interactive entry: read `$KASH_ENV` (else `$BASH_ENV`), expand
     * a leading `~/`, and source the resulting path. Per bash: "the
     * value of the PATH variable is not used to search for the file
     * name" — we read literally.
     */
    suspend fun sourceNonInteractiveEnv(
        interp: Interpreter,
        ctx: CommandContext,
    ) {
        val raw =
            ctx.process.env["KASH_ENV"]?.takeIf { it.isNotBlank() }
                ?: ctx.process.env["BASH_ENV"]?.takeIf { it.isNotBlank() }
                ?: return
        sourceFile(expandHome(raw, ctx), interp, ctx)
    }

    /**
     * POSIX / `--posix` interactive entry: read `$ENV`, tilde-expand,
     * source. Nothing else is sourced in this mode.
     */
    suspend fun sourcePosixEnv(
        interp: Interpreter,
        ctx: CommandContext,
    ) {
        val raw = ctx.process.env["ENV"]?.takeIf { it.isNotBlank() } ?: return
        sourceFile(expandHome(raw, ctx), interp, ctx)
    }

    /**
     * Login-shell exit hook: source `~/.kash_logout` if present.
     * Invoked from the REPL's `finally` block so it runs for both the
     * `logout` builtin and a plain EOF (Ctrl-D) — same as bash.
     */
    suspend fun sourceLogout(
        interp: Interpreter,
        ctx: CommandContext,
    ) {
        sourceFile(joinHome(home(ctx), ".kash_logout"), interp, ctx)
    }

    /**
     * `sh -l` / `-sh` argv[0] login entry: per bash § "If bash is
     * invoked with the name sh", only `/etc/profile` and `~/.profile`
     * are read. Suppressed by `--noprofile`.
     */
    suspend fun sourceShLoginProfiles(
        interp: Interpreter,
        ctx: CommandContext,
    ) {
        sourceFile("/etc/profile", interp, ctx)
        sourceFile(joinHome(home(ctx), ".profile"), interp, ctx)
    }

    private val LOGIN_PERSONAL = listOf(".kash_profile", ".kash_login", ".profile")

    private fun home(ctx: CommandContext): String = ctx.process.env["HOME"] ?: ctx.userDb.current().home

    private fun joinHome(
        home: String,
        name: String,
    ): String = if (home.endsWith("/")) "$home$name" else "$home/$name"

    /**
     * Cheap tilde expansion for `$BASH_ENV` / `$ENV` values: handles
     * `~` and `~/…` against `$HOME`. We don't try to do full word
     * expansion here — typical values are absolute paths or `~/foo`,
     * and bash itself doesn't run path-search on these.
     */
    private fun expandHome(
        raw: String,
        ctx: CommandContext,
    ): String =
        when {
            raw == "~" -> home(ctx)
            raw.startsWith("~/") -> joinHome(home(ctx), raw.removePrefix("~/"))
            else -> raw
        }
}
