package com.accucodeai.kash.tools.uuidgen

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into the per-
 * subsystem catalog (`posixCommands` / `extCommands` / `kashCommands`).
 *
 * No DI — adding a new command means typing its constructor here.
 */
public val uuidgenCommands: List<CommandSpec> =
    listOf(
        UuidgenCommand(),
    )
