package com.accucodeai.kash.tools.tput

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into
 * `posixCommands`.
 */
public val tputCommands: List<CommandSpec> =
    listOf(
        TputCommand(),
    )
