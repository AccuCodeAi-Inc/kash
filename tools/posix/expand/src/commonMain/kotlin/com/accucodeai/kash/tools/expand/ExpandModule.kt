package com.accucodeai.kash.tools.expand

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into the
 * per-subsystem catalog (`posixCommands`).
 */
public val expandCommands: List<CommandSpec> =
    listOf(
        ExpandCommand(),
    )
