// Bash-compat name lists used by `compgen` actions. These were once
// hardcoded copies of bash's `complete.right` fixture — including names
// for builtins kash didn't actually implement. After Stages 1-4 every
// entry below is backed by something real: an [IntrinsicCatalog] entry,
// a registry tool, a registered shopt option, or a help-topic in
// [HelpTopics]. The only literal name lists here are short and document
// the gap between "names bash treats as builtins" and "names kash
// implements as intrinsics" — the leftovers are kash registry tools
// (`cd`, `echo`, `printf`, ...) that bash labels as builtins.

package com.accucodeai.kash.completion

import com.accucodeai.kash.interpreter.HelpTopics
import com.accucodeai.kash.intrinsics.IntrinsicCatalog

/**
 * Names bash labels as shell builtins that kash implements as registry
 * tools rather than intrinsics. They live in the standard registry
 * (`echo`, `printf`, `cd`, `test`, ...); we keep them here as a short
 * explicit list because the registry's tool set is broader than just
 * "bash builtins" and we don't want to enumerate every external utility.
 *
 * `suspend` is included even though it isn't yet implemented anywhere —
 * compgen scripts probe for it, and registering the name as "known"
 * costs nothing.
 */
internal val BASH_TOOL_BUILTINS: List<String> =
    listOf(
        "[",
        "cd",
        "disown",
        "echo",
        "false",
        "printf",
        "pwd",
        "read",
        "suspend",
        "test",
        "true",
    )

/**
 * `compgen -b` / `compgen -A enabled` — names bash treats as shell
 * builtins. Derived from the real [IntrinsicCatalog] plus the small
 * set of registry tools that bash also labels as builtins.
 */
internal val BASH_BUILTIN_NAMES: List<String>
    get() =
        (IntrinsicCatalog.names + BASH_TOOL_BUILTINS)
            .toSet()
            .sorted()

/**
 * `compgen -A helptopic` — derived from [HelpTopics]. Each entry has
 * real synopsis / description text in the help index.
 */
internal val BASH_HELPTOPICS: List<String>
    get() = HelpTopics.all.keys.sorted()

/**
 * Names bash recognizes as `shopt` options. Includes every shopt the
 * bash 5.x manual documents, even ones where kash's [Interpreter] only
 * tracks the boolean state without changing behavior. The four
 * (`globskipdots`, `patsub_replacement`, `bash_source_fullpath`,
 * `array_expand_once`) plus `extdebug` actually drive code paths; the
 * rest are accept-and-record.
 *
 * Used both by `compgen -A shopt` and to seed [Interpreter.shoptOptions]
 * with bash's documented defaults — single source of truth.
 */
internal val KNOWN_SHOPT_NAMES: List<String> =
    listOf(
        "array_expand_once",
        "assoc_expand_once",
        "autocd",
        "bash_source_fullpath",
        "cdable_vars",
        "cdspell",
        "checkhash",
        "checkjobs",
        "checkwinsize",
        "cmdhist",
        "compat31",
        "compat32",
        "compat40",
        "compat41",
        "compat42",
        "compat43",
        "compat44",
        "complete_fullquote",
        "direxpand",
        "dirspell",
        "dotglob",
        "execfail",
        "expand_aliases",
        "extdebug",
        "extglob",
        "extquote",
        "failglob",
        "force_fignore",
        "globasciiranges",
        "globskipdots",
        "globstar",
        "gnu_errfmt",
        "histappend",
        "histreedit",
        "histverify",
        "hostcomplete",
        "huponexit",
        "inherit_errexit",
        "interactive_comments",
        "lastpipe",
        "lithist",
        "localvar_inherit",
        "localvar_unset",
        "login_shell",
        "mailwarn",
        "no_empty_cmd_completion",
        "nocaseglob",
        "nocasematch",
        "noexpand_translation",
        "nullglob",
        "patsub_replacement",
        "progcomp",
        "progcomp_alias",
        "promptvars",
        "restricted_shell",
        "shift_verbose",
        "sourcepath",
        "varredir_close",
        "xpg_echo",
    )

/**
 * Bash defaults per the 5.2 manual. Names not present default to false.
 * Used to seed [Interpreter.shoptOptions] at construction time.
 */
internal val DEFAULT_SHOPT_VALUES: Map<String, Boolean> =
    mapOf(
        "bash_source_fullpath" to false,
        "checkhash" to false,
        "checkjobs" to false,
        "checkwinsize" to true,
        "cmdhist" to true,
        "complete_fullquote" to true,
        "expand_aliases" to true,
        // Bash defaults extglob OFF for non-interactive shells, ON for
        // interactive. Kash defaults ON to match its historical
        // always-on lex-time recognition of `?(…)` / `*(…)` etc. —
        // changing this would require auditing every conformance
        // fixture that doesn't explicitly `shopt -s extglob`. Tracked
        // as a follow-up.
        "extglob" to true,
        "extquote" to true,
        "force_fignore" to true,
        "globasciiranges" to true,
        "globskipdots" to true,
        "hostcomplete" to true,
        "interactive_comments" to true,
        "patsub_replacement" to true,
        "progcomp" to true,
        "promptvars" to true,
        "sourcepath" to true,
    )
