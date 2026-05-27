package com.accucodeai.kash.tools.kash.all

import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.commands.builtinsCommands
import com.accucodeai.kash.tools.ed.edCommands
import com.accucodeai.kash.tools.fzf.fzfCommands
import com.accucodeai.kash.tools.git.gitCommands
import com.accucodeai.kash.tools.jq.jqCommands
import com.accucodeai.kash.tools.kash.kashShellCommands
import com.accucodeai.kash.tools.less.lessCommands
import com.accucodeai.kash.tools.nano.nanoCommands
import com.accucodeai.kash.tools.vi.viCommands

/**
 * kash-only tools (the shell itself, jq, nano) plus the in-shell POSIX
 * builtins (cd/pwd/read/...) concatenated into one list. Picked up by
 * [com.accucodeai.kash.KashCoreModule].
 *
 * `python3Commands` is intentionally NOT here: it requires a [com.accucodeai
 * .kash.tools.python3.PythonEngine] that's supplied by a separate per-target
 * module (graalpy on JVM, pyodide on wasmJs). The app-level entry point
 * (`KashAppModule` / `KashAppWebModule`) appends `python3Commands(engine)`
 * itself.
 */
public val kashCommands: List<CommandSpec> =
    kashShellCommands +
        builtinsCommands +
        gitCommands() +
        jqCommands +
        nanoCommands +
        viCommands +
        lessCommands +
        fzfCommands +
        edCommands
