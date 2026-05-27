package com.accucodeai.kash.tools.grep

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into the per-
 * subsystem catalog (`posixCommands` / `extCommands` / `kashCommands`).
 *
 * No DI — adding a new command means typing its constructor here.
 */
public val grepCommands: List<CommandSpec> =
    listOf(
        GrepCommandSpec(),
        EgrepCommandSpec(),
        FgrepCommandSpec(),
    )
