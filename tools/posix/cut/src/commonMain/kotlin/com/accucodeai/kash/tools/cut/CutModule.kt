package com.accucodeai.kash.tools.cut

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into the per-
 * subsystem catalog (`posixCommands` / `extCommands` / `kashCommands`).
 *
 * No DI — adding a new command means typing its constructor here.
 */
public val cutCommands: List<CommandSpec> =
    listOf(
        CutCommand(),
    )
