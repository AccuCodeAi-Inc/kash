package com.accucodeai.kash.tools.python3

import com.accucodeai.kash.api.CommandSpec

/**
 * `python3` is a one-of-a-kind tool — it needs a [PythonEngine] which is
 * supplied by a *different module* (`:tools:kash:python3-graalpy` on JVM,
 * `:tools:kash:python3-pyodide` on wasmJs). So this isn't an autonomous
 * `val xxxCommands` like the rest of the catalog; it's a function the
 * entry point calls with whichever engine that target has built.
 *
 * Forgetting to call this from an entry point just means `python3` is
 * missing from the registry — the same failure mode as forgetting to add
 * any other tool.
 */
public fun python3Commands(engine: PythonEngine): List<CommandSpec> = listOf(Python3Command(engine))
