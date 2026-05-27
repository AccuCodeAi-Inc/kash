package com.accucodeai.kash.tools.getopt

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into the per-
 * subsystem catalog (`posixCommands`).
 */
public val getoptCommands: List<CommandSpec> =
    listOf(
        GetoptCommand(),
    )
