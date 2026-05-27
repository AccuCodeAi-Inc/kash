package com.accucodeai.kash.interpreter

/**
 * Shell option flags — backs `set -e` / `set -u` / `set -o pipefail`
 * and the rest of the family that POSIX `set [-+]<flag>` and
 * `set -o <name>` toggle. Held as a cohesive cluster so
 * [Interpreter.forkSubshell] can carry the full option state to a
 * subshell with one [copyFrom] call instead of N field-by-field
 * assignments (which silently miss any newly-added flag).
 *
 * Interpreter exposes each option as a top-level property
 * (`errexit`, `nounset`, …) that forwards to this holder, so call
 * sites stay terse — only the field declarations and forkSubshell
 * know about [ShellOptions].
 */
internal class ShellOptions {
    /** `set -e` / `set -o errexit`. See [Interpreter] for the suppression rules. */
    var errexit: Boolean = false

    /**
     * Suppression depth for [errexit]. Bumped on entry to `if`/`while`/`until`
     * conditions, `&&`/`||` non-last legs, `!`-inverted commands. errexit
     * fires only when this is zero.
     */
    var errexitSuppressed: Int = 0

    /** `set -u` / `set -o nounset`. */
    var nounset: Boolean = false

    /** `set -o pipefail`. */
    var pipefail: Boolean = false

    /** `set -C` / `set -o noclobber`. */
    var noclobber: Boolean = false

    /** `set -o posix`. */
    var posixModeRuntime: Boolean = false

    /**
     * `set -r` / `set -o restricted`. Once set it cannot be cleared (the
     * `set` intrinsic enforces this). Restricts `cd`, `exec`, output
     * redirection, slashes in command names, etc.
     */
    var restricted: Boolean = false

    /** `shopt -s extdebug`. */
    var extdebugEnabled: Boolean = false

    /**
     * `set -x` / `set -o xtrace`. When on, the dispatcher emits a
     * `<PS4><command>\n` line to stderr immediately before executing
     * each command — see [com.accucodeai.kash.interpreter.emitXtrace].
     * Single central choke point; per-shape formatting (simple, arith,
     * for, case, while-cond) lives there.
     */
    var xtrace: Boolean = false

    /**
     * `set -m` / `set -o monitor`. Bash gates fg/bg diagnostics on
     * this — without monitor mode, `fg %1` reports `fg: no job
     * control` instead of resuming. Interactive shells default-on;
     * scripts default-off. Mostly observable through diagnostics
     * since kash is in-process and doesn't transfer tty foreground
     * groups.
     */
    var monitor: Boolean = false

    /**
     * `set -E` / `set -o errtrace`. When on, the ERR trap is inherited
     * by shell functions, command substitutions, and subshells. Off
     * (POSIX default), each of those scopes starts with the ERR trap
     * reset to default — matches bash 5.x's documented semantics.
     * Consulted at function entry (in [traceFramesActive]) and at
     * subshell entry (in [com.accucodeai.kash.traps.TrapTable.inheritFrom]).
     */
    var errtrace: Boolean = false

    /**
     * `set -T` / `set -o functrace`. When on, DEBUG and RETURN traps
     * are inherited by shell functions, command substitutions, and
     * subshells. Off (POSIX default), those traps reset at scope
     * entry. Consulted at the same two sites as [errtrace].
     */
    var functrace: Boolean = false

    /**
     * `set -o emacs` / `set -o vi` — readline line editor selection. kash
     * has no interactive line editor; the only place this is observable is
     * `${var@P}` prompt decode, which emits `\[`/`\]` as `\001`/`\002` only
     * when an editor is active.
     */
    var emacsMode: Boolean = false
    var viMode: Boolean = false

    /**
     * `set -a` / `set -o allexport`. When on, every variable assignment
     * automatically gets the `Export` attribute, so new vars are visible
     * to spawned child processes. POSIX §2.14.1.
     */
    var allexport: Boolean = false

    /**
     * `set -f` / `set -o noglob`. When on, pathname (glob) expansion is
     * disabled — the expander leaves `*`/`?`/`[...]` as literal text.
     * POSIX §2.14.1.
     */
    var noglob: Boolean = false

    /**
     * `set -v` / `set -o verbose`. When on, each shell input line is
     * echoed to stderr immediately before being lexed/parsed. POSIX
     * §2.14.1.
     */
    var verbose: Boolean = false

    /**
     * `set -n` / `set -o noexec`. When on, commands are read and parsed
     * but not executed — used to syntax-check scripts. POSIX §2.14.1
     * documents that interactive shells ignore this; we only gate at
     * the statement-execution layer.
     */
    var noexec: Boolean = false

    /** Replace this holder's state with [other]'s. Used by `forkSubshell`. */
    fun copyFrom(other: ShellOptions) {
        errexit = other.errexit
        errexitSuppressed = other.errexitSuppressed
        nounset = other.nounset
        pipefail = other.pipefail
        noclobber = other.noclobber
        posixModeRuntime = other.posixModeRuntime
        restricted = other.restricted
        extdebugEnabled = other.extdebugEnabled
        xtrace = other.xtrace
        monitor = other.monitor
        errtrace = other.errtrace
        functrace = other.functrace
        emacsMode = other.emacsMode
        viMode = other.viMode
        allexport = other.allexport
        noglob = other.noglob
        verbose = other.verbose
        noexec = other.noexec
    }
}
