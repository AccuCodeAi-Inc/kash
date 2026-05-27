package com.accucodeai.kash.interpreter

/**
 * Help-topic index for the `help` builtin. One entry per shell builtin
 * (intrinsic) and reserved word. Text is original and intentionally
 * concise — about a line of synopsis plus a short paragraph of behavior
 * notes. Not derived from bash's (GPL-3) help strings.
 *
 * Format mirrors bash's `help [-dms] PATTERN` output shape:
 *  - `synopsis`: one-line usage skeleton, e.g. `cd [-L|-P] [dir]`.
 *  - `short`: one-line summary for `help -d` and the list view.
 *  - `long`: paragraph(s) describing flags and behavior. Rendered for
 *    plain `help PATTERN`. Lines are pre-wrapped to fit a default 80-col
 *    terminal — callers don't re-flow.
 */
internal data class HelpTopic(
    val name: String,
    val synopsis: String,
    val short: String,
    val long: String,
)

internal object HelpTopics {
    private fun topic(
        name: String,
        synopsis: String,
        short: String,
        long: String,
    ): Pair<String, HelpTopic> = name to HelpTopic(name, synopsis, short, long)

    /** Indexed by topic name; iteration order matches the registration order below. */
    val all: Map<String, HelpTopic> =
        linkedMapOf(
            topic(
                ":",
                ":",
                "Null command. Does nothing and exits 0.",
                "Accepts any arguments without using them. Most often spelled in scripts as a placeholder body — `while true; do :; done` — or to expand its arguments for their side effects, e.g. `: \${VAR:=default}` to set VAR if unset.",
            ),
            topic(
                ".",
                ". filename [arguments]",
                "Source a script in the current shell.",
                "Read and execute commands from `filename` in the current shell environment, so any variables or functions it defines persist after the script returns. Optional `arguments` become the positional parameters for the script.",
            ),
            topic(
                "source",
                "source filename [arguments]",
                "Alias for `.` — source a script in the current shell.",
                "Behaves identically to `.`. Reads commands from `filename` in the current shell, preserving any side effects on variables, functions, and the working directory.",
            ),
            topic(
                "alias",
                "alias [-p] [name[=value] ...]",
                "Define or list shell aliases.",
                "With no arguments or `-p`, prints all aliases in a re-readable form. With `name=value`, registers a textual alias expanded at parse time on the first word of a command. With `name` (no value), prints that one alias.",
            ),
            topic(
                "bg",
                "bg [job_spec ...]",
                "Resume jobs in the background.",
                "Continue the specified stopped jobs in the background, as if they had been started with `&`. With no argument, acts on the most recent stopped job.",
            ),
            topic(
                "bind",
                "bind [-lpsvPSVX] [-m keymap] [-f filename] [-q name] ...",
                "Display or modify readline key bindings.",
                "Inspect or configure the readline layer that powers interactive line editing. The interactive surface is a moving target; in kash today `bind -l` and `bind -p` work and most other forms are accepted with limited effect.",
            ),
            topic(
                "break",
                "break [n]",
                "Exit a `for`, `while`, `until`, or `select` loop.",
                "Stops execution of the innermost loop. With argument `n` (a positive integer), breaks out of `n` levels of nested loops.",
            ),
            topic(
                "builtin",
                "builtin name [arguments]",
                "Run a shell builtin, bypassing function lookup.",
                "Forces a builtin to be invoked even when a function with the same name shadows it. Returns the builtin's exit status, or 1 with a diagnostic if `name` is not a builtin.",
            ),
            topic(
                "caller",
                "caller [expr]",
                "Print the current call frame.",
                "With no argument, prints `<line> <function>` for the immediate caller of the current function. With `expr` (a non-negative integer), prints `<line> <function> <source>` for that frame, counting outward from the innermost call. Exits non-zero when no such frame exists.",
            ),
            topic(
                "case",
                "case word in [pattern [| pattern]...) commands ;;]... esac",
                "Pattern-based multi-way branch.",
                "Evaluates `word` against each pattern in order and runs the first matching branch's commands. Use `;;` to end a branch and `;&` / `;;&` to fall through. Patterns use shell glob syntax.",
            ),
            topic(
                "cd",
                "cd [-L|-P] [dir]",
                "Change the current working directory.",
                "With no argument, change to `\$HOME`. With `-`, change back to `\$OLDPWD` and print the new directory. Tracks the previous directory in `\$OLDPWD`.",
            ),
            topic(
                "command",
                "command [-pVv] command [arguments ...]",
                "Run a command, bypassing function lookup.",
                "Executes `command` without consulting the shell function table — the resolution goes straight to builtins and PATH. `-v` prints the command's resolved form and exits; `-V` prints a verbose description.",
            ),
            topic(
                "compgen",
                "compgen [option] [word]",
                "Generate completion candidates without binding them.",
                "Emits the candidates that would be offered by the matching `complete` action — e.g. `compgen -c ec` lists every command name starting with `ec`. Read-only; used both interactively and by completion scripts.",
            ),
            topic(
                "complete",
                "complete [-abcdefgjksuv] [options] [name ...]",
                "Register a programmable completion spec.",
                "Associates a completion strategy with one or more command names. Subsequent TAB completions on the bound commands consult the registered spec rather than the default file/command heuristic.",
            ),
            topic(
                "compopt",
                "compopt [-o|+o option] [-DEI] [name ...]",
                "Modify an active completion spec.",
                "Inside a completion function, tweaks the in-flight completion options. Outside, edits the registered spec for the named commands.",
            ),
            topic(
                "continue",
                "continue [n]",
                "Skip to the next iteration of an enclosing loop.",
                "Restarts the innermost loop at its test. With argument `n`, restarts the n-th enclosing loop.",
            ),
            topic(
                "coproc",
                "coproc [NAME] command [redirections]",
                "Run a command as an asynchronous coprocess.",
                "Starts `command` with its stdin/stdout connected to a pair of file descriptors readable as `\${NAME[0]}` (read) and `\${NAME[1]}` (write). The default `NAME` is `COPROC`.",
            ),
            topic(
                "declare",
                "declare [-aAfFgilnrtux] [-p] [name[=value] ...]",
                "Set variable attributes and values.",
                "Manipulates the shell's variable table: declare arrays (`-a` / `-A`), integers (`-i`), exported (`-x`), readonly (`-r`), and so on. With `-p`, prints declarations in a re-readable form.",
            ),
            topic(
                "dirs",
                "dirs [-clpv] [+N|-N]",
                "Print the directory stack.",
                "Shows the stack maintained by `pushd`/`popd`. `-c` clears it; `-v` numbers each entry; `+N` / `-N` print a specific entry from the top or bottom.",
            ),
            topic(
                "echo",
                "echo [-neE] [arg ...]",
                "Write arguments to standard output.",
                "Prints its arguments space-separated and terminated by a newline. `-n` suppresses the trailing newline; `-e` enables backslash escapes; `-E` disables them (the default).",
            ),
            topic(
                "enable",
                "enable [-adnps] [name ...]",
                "Enable or disable shell builtins.",
                "With `-n`, suppress the named builtins so command resolution falls through to PATH. Without `-n`, re-enable previously suppressed entries. `-p` prints state in a re-readable form; `-a` lists every known builtin.",
            ),
            topic(
                "eval",
                "eval [arg ...]",
                "Concatenate arguments and execute as shell input.",
                "Joins `arg ...` with spaces, parses the result as shell input, and runs it in the current shell. Returns the exit status of the executed command, or 0 if no arguments.",
            ),
            topic(
                "exec",
                "exec [-cl] [-a name] [command [arguments ...]]",
                "Replace the shell process, or apply redirections in place.",
                "With `command`, replaces the shell with that command — no new process is created. Without `command`, any redirections take effect on the current shell. `-l` makes the new process a login shell; `-a name` sets `argv[0]`.",
            ),
            topic(
                "exit",
                "exit [n]",
                "Exit the shell with status n.",
                "Terminates the shell with exit status `n` (default: status of the last command).",
            ),
            topic(
                "export",
                "export [-fn] [name[=value] ...]",
                "Mark variables for export to child processes.",
                "Adds the named variables to the environment passed to executed commands. `-f` exports a function; `-n` removes the export attribute without unsetting the variable. With no arguments, prints all exported names.",
            ),
            topic(
                "false",
                "false",
                "Exit with status 1.",
                "Always returns false. Used as a no-op failure marker in conditionals.",
            ),
            topic(
                "fc",
                "fc [-e ename] [-lnr] [first] [last]   or   fc -s [pat=rep] [command]",
                "List, edit, or re-execute history entries.",
                "`fc -l` lists recent history. `fc -ln` omits line numbers; `-r` reverses order. Without `-l`, opens the entries in an editor and re-executes the result (editor invocation not yet implemented in kash).",
            ),
            topic(
                "fg",
                "fg [job_spec]",
                "Bring a backgrounded job to the foreground.",
                "Resumes the specified job in the foreground. With no argument, acts on the most recent backgrounded job.",
            ),
            topic(
                "for",
                "for name [in words ...]; do commands; done",
                "Iterate over a word list.",
                "Assigns each word to `name` in turn and runs `commands`. Also supports the C-style form `for ((init; cond; post))` for arithmetic loops.",
            ),
            topic(
                "function",
                "function name { commands; }   or   name() { commands; }",
                "Define a shell function.",
                "Registers `commands` as a function callable by name. Functions inherit the caller's positional parameters by default; `local` introduces function-scoped variables.",
            ),
            topic(
                "getopts",
                "getopts optstring name [arg ...]",
                "Parse positional options.",
                "Walks one positional argument at a time, setting `name` to the option letter and `OPTARG` to its argument value. Returns non-zero when arguments are exhausted.",
            ),
            topic(
                "hash",
                "hash [-lr] [-p path] [-dt] [name ...]",
                "Show or manage the path cache.",
                "Caches the resolved PATH location of recently executed commands. `-r` clears the cache; `-p path` registers `name` to a specific path; `-t` prints the cached path for `name`.",
            ),
            topic(
                "help",
                "help [-dms] [pattern ...]",
                "Display help for shell builtins.",
                "Prints synopsis and description for builtins matching each `pattern`. `-d` prints only the one-line short description; `-s` prints only the synopsis; `-m` prints a man-page-style block. With no pattern, lists every available topic.",
            ),
            topic(
                "history",
                "history [-c] [-d offset] [n]   or   history -anrw [filename]   or   history -ps arg ...",
                "Manage the command history.",
                "`history` prints the history list, numbered. `-c` clears it; `-d N` removes a single entry; `-s STR` appends a literal entry. `-r/-w/-a/-n` exchange the history with a file (defaulting to `\$HISTFILE`). History expansion (`!!`, `!N`) is parsed elsewhere.",
            ),
            topic(
                "if",
                "if cond; then commands; [elif cond; then commands;]... [else commands;] fi",
                "Conditional branch.",
                "Runs the first branch whose `cond` exits zero. `elif` chains additional tests; `else` provides a fallback. Use `[[ ... ]]` or `[ ... ]` for typical predicate forms.",
            ),
            topic(
                "jobs",
                "jobs [-lnprs] [job_spec ...]",
                "List active jobs.",
                "Prints the status of each background or stopped job. `-l` adds the PID; `-p` prints only PIDs; `-n` lists only jobs whose status changed since last reported.",
            ),
            topic(
                "kill",
                "kill [-s sigspec | -n signum | -sigspec] pid | job_spec ...   or   kill -l",
                "Send a signal to processes or jobs.",
                "Delivers a signal (default `TERM`) to the named processes or jobs. `-l` lists signal names. Signals may be named (`TERM`, `INT`, `HUP`) or numbered.",
            ),
            topic(
                "let",
                "let expression [expression ...]",
                "Evaluate arithmetic expressions.",
                "Evaluates each expression as integer arithmetic, mutating variables via assignment operators (`x=1`, `i+=2`). Returns 0 if the last expression is non-zero, 1 if zero.",
            ),
            topic(
                "local",
                "local [option] name[=value] ...",
                "Declare function-scoped variables.",
                "Within a function, creates variables visible only inside that function and its callees. Outside a function, errors out.",
            ),
            topic(
                "logout",
                "logout [n]",
                "Exit a login shell.",
                "Exits with status `n` (default: status of the last command) — but only inside a login shell. In a regular shell, errors with `not login shell: use 'exit'`.",
            ),
            topic(
                "mapfile",
                "mapfile [-d delim] [-n count] [-O origin] [-s skip] [-tu] [-C callback] [-c quantum] [array]",
                "Read lines into an indexed array.",
                "Consumes standard input one line at a time (or one delimiter-terminated chunk with `-d`) and stores entries in the named array, defaulting to `MAPFILE`. `-t` strips trailing delimiters. `-n` caps the count; `-O` offsets the first index.",
            ),
            topic(
                "popd",
                "popd [+N | -N]",
                "Pop the directory stack and cd into the new top.",
                "Removes an entry from the stack maintained by `pushd`. With no argument, drops the entry below the current cwd and cds into it. `+N` / `-N` remove the entry at that position.",
            ),
            topic(
                "printf",
                "printf [-v var] format [arguments ...]",
                "Format and print arguments.",
                "Renders `arguments` per `format`, a C-like conversion string with `%s`, `%d`, `%x`, `%q` (shell-quoted), and `%b` (backslash-escape). With `-v var`, captures the result into `var` instead of printing.",
            ),
            topic(
                "pushd",
                "pushd [DIR | +N | -N]",
                "Push a directory onto the stack and cd into it.",
                "With `DIR`, pushes the current cwd and cds into `DIR`. With `+N` / `-N`, rotates the stack so that entry becomes the new top. With no argument, swaps the top two entries.",
            ),
            topic(
                "pwd",
                "pwd [-LP]",
                "Print the current working directory.",
                "Prints the absolute path of the current directory. `-P` resolves symlinks; `-L` (the default) preserves them.",
            ),
            topic(
                "read",
                "read [-r] [-p prompt] [-d delim] [-n n] [-t timeout] [-u fd] [name ...]",
                "Read a line into shell variables.",
                "Splits a line from standard input on `\$IFS` and assigns the pieces to the named variables in order. `-r` disables backslash interpretation; `-d` overrides the line delimiter; `-t` adds a timeout.",
            ),
            topic(
                "readarray",
                "readarray [options] [array]",
                "Synonym for `mapfile`.",
                "Identical to `mapfile`. See `help mapfile` for the option list.",
            ),
            topic(
                "readonly",
                "readonly [-aAf] [name[=value] ...]",
                "Mark variables as read-only.",
                "Once marked, the named variables cannot be reassigned or unset. With `-f`, applies to functions instead.",
            ),
            topic(
                "return",
                "return [n]",
                "Return from a function.",
                "Exits the enclosing function with status `n` (default: status of the last command). Outside a function or sourced script, behaves like `exit`.",
            ),
            topic(
                "select",
                "select name [in words ...]; do commands; done",
                "Interactive menu loop.",
                "Prints a numbered menu of `words` to stderr, prompts the user, and assigns the chosen word to `name`. Loops until interrupted or `break` runs.",
            ),
            topic(
                "set",
                "set [-+abCEefhkmnptuvx] [-+o option] [arg ...]",
                "Set shell options and positional parameters.",
                "Toggles shell options (`-e`, `-x`, `-o pipefail`, ...) and resets the positional parameter list to the given arguments. With no arguments, prints all variables.",
            ),
            topic(
                "shift",
                "shift [n]",
                "Drop positional parameters.",
                "Shifts the positional parameters left by `n` (default 1). `\$1` becomes the old `\$2`, and so on. Errors when `n` exceeds the number of remaining parameters.",
            ),
            topic(
                "shopt",
                "shopt [-pqsu] [-o] [optname ...]",
                "Toggle additional shell behavior options.",
                "Manages a separate option namespace from `set`. `-s` enables; `-u` disables; `-q` queries (returns 0 if on); `-p` prints in re-readable form. With no operands, lists every tracked option.",
            ),
            topic(
                "test",
                "test expression   or   [ expression ]",
                "Evaluate a conditional expression.",
                "POSIX file/string/numeric tests: `-f file`, `-d dir`, `-n str`, `-z str`, `=`, `!=`, `-eq`, `-lt`, ... Returns 0 (true) or 1 (false).",
            ),
            topic(
                "time",
                "time [-p] [pipeline]",
                "Report elapsed time for a pipeline.",
                "Runs the pipeline and prints user/system/real timing to stderr. `-p` switches to a POSIX-conformant layout.",
            ),
            topic(
                "times",
                "times",
                "Print accumulated user/system CPU time.",
                "Prints the shell's and its children's cumulative CPU time, in `user system` form, on two lines.",
            ),
            topic(
                "trap",
                "trap [-lp] [[arg] signal_spec ...]",
                "Run commands on signals or shell events.",
                "Registers `arg` (an action string) to run when each listed signal fires. Special pseudo-signals: `EXIT` (shell exit), `ERR` (command failure under `set -e`), `DEBUG` (before every command), `RETURN` (function/script return).",
            ),
            topic(
                "true",
                "true",
                "Exit with status 0.",
                "Always returns true. Useful as a sentinel in conditionals and infinite-loop tests.",
            ),
            topic(
                "type",
                "type [-aftpP] name [name ...]",
                "Describe how each name would be resolved.",
                "Reports whether `name` is a builtin, function, alias, keyword, or external command. `-t` prints just the kind; `-a` lists every matching kind; `-p` / `-P` print the resolved path.",
            ),
            topic(
                "typeset",
                "typeset [options] [name[=value] ...]",
                "Synonym for `declare`.",
                "Identical to `declare`. See `help declare`.",
            ),
            topic(
                "ulimit",
                "ulimit [-HSabcdefiklmnpqrstuvxPRT] [limit]",
                "Get or set per-process resource limits.",
                "Reads or adjusts limits inherited by child processes. With no value, prints the current limit; with a value, sets it. `-H` for hard, `-S` for soft (default both).",
            ),
            topic(
                "umask",
                "umask [-Sp] [mode]",
                "Show or set the file-creation mask.",
                "With no argument, prints the current mask. `umask -S` prints the symbolic form `u=rwx,g=rx,o=rx`. With a mode (octal or `u=rwx`-style), sets the mask.",
            ),
            topic(
                "unalias",
                "unalias [-a] name [name ...]",
                "Remove aliases.",
                "Deletes the named aliases from the alias table. `-a` removes every alias.",
            ),
            topic(
                "unset",
                "unset [-fv] [name ...]",
                "Remove variables or functions.",
                "Deletes the named variables (default) or functions (`-f`) from the shell's table. Readonly entries cannot be unset.",
            ),
            topic(
                "until",
                "until cond; do commands; done",
                "Loop while a condition is false.",
                "Runs `commands` repeatedly until `cond` exits zero. Mirror of `while` with inverted predicate.",
            ),
            topic(
                "wait",
                "wait [-fn] [-p var] [id ...]",
                "Wait for processes or jobs to finish.",
                "Blocks until each listed process or job exits, then returns its status. With no argument, waits for all backgrounded children. `-n` returns after any one finishes.",
            ),
            topic(
                "while",
                "while cond; do commands; done",
                "Loop while a condition is true.",
                "Runs `commands` repeatedly so long as `cond` exits zero.",
            ),
            topic(
                "{ ... }",
                "{ commands; }",
                "Group commands in the current shell.",
                "Runs the enclosed commands as a unit in the current shell — no subshell is created. Suitable for grouping redirections or assignments.",
            ),
            topic(
                "[[ ... ]]",
                "[[ expression ]]",
                "Conditional expression with extended operators.",
                "Bash-enhanced test command. Supports `==`/`!=` with glob patterns, `=~` regex matching, `&&` / `||` operators, and skips word splitting on its operands.",
            ),
            topic(
                "!",
                "! pipeline",
                "Negate the exit status of a pipeline.",
                "Runs `pipeline` and returns its complemented exit status — zero becomes 1, non-zero becomes 0.",
            ),
            topic(
                "%",
                "%[N|+|-|string]",
                "Job specifier — refer to a backgrounded job.",
                "`%N` selects job N; `%+` and `%%` the current (most recent) job; `%-` the previous job; `%string` the most-recent job whose command starts with `string`. Used as the argument to `fg`, `bg`, `wait`, `kill`, etc.",
            ),
            topic(
                "(( ... ))",
                "(( expression ))",
                "Arithmetic command.",
                "Evaluates the integer arithmetic expression in C-like syntax. Returns exit 0 when the result is non-zero, 1 when zero — useful as a loop or `if` predicate.",
            ),
            topic(
                "[",
                "[ expression ]",
                "Conditional expression — POSIX `test` form.",
                "Identical to `test` but requires a closing `]`. Supports file, string, and numeric predicates. Use `[[ ... ]]` for the bash-extended form with glob and regex matching.",
            ),
            topic(
                "disown",
                "disown [-ar] [-h] [jobspec ...]",
                "Detach jobs from the shell.",
                "Removes the specified jobs from the shell's job table so the shell doesn't track them or send SIGHUP on exit. `-a` acts on all jobs; `-r` only running jobs; `-h` keeps the job but marks it to not receive SIGHUP.",
            ),
            topic(
                "for ((",
                "for ((init; test; step)); do commands; done",
                "C-style arithmetic for-loop.",
                "Three arithmetic expressions separated by `;` drive the loop: `init` runs once, the loop continues while `test` is non-zero, and `step` runs after each iteration.",
            ),
            topic(
                "suspend",
                "suspend [-f]",
                "Suspend the current shell.",
                "Stops the shell as if it had received `SIGSTOP`. Errors out in a login shell unless `-f` is supplied. (kash does not yet wire SIGSTOP plumbing.)",
            ),
            topic(
                "variables",
                "variables",
                "Common shell variables and their meanings.",
                "The shell exposes special variables such as `\$?` (last exit), `\$\$` (shell pid), `\$@` / `\$*` (positional parameters), `BASH_SOURCE`, `FUNCNAME`, `BASH_LINENO`, `IFS`, `PATH`, `HOME`, `OLDPWD`. See bash(1) for the complete list.",
            ),
        )
}
