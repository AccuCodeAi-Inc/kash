package com.accucodeai.kash.tools.uname

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into the per-
 * subsystem catalog (`posixCommands`).
 */
public val unameCommands: List<CommandSpec> =
    listOf(
        UnameCommand(),
    )
