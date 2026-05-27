package com.accucodeai.kash.tools.unexpand

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem.
 */
public val unexpandCommands: List<CommandSpec> =
    listOf(
        UnexpandCommand(),
    )
