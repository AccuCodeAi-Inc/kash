package com.accucodeai.kash.tools.less

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by the `less` subsystem. Aggregated into the kash
 * tool catalog by `KashToolsModule`.
 */
public val lessCommands: List<CommandSpec> =
    listOf(
        LessCommand(),
    )
