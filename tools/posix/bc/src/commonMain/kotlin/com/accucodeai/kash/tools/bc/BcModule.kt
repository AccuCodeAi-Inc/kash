package com.accucodeai.kash.tools.bc

import com.accucodeai.kash.api.CommandSpec

/**
 * `bc` command spec aggregator. Wired into the POSIX tool catalog.
 */
public val bcCommands: List<CommandSpec> =
    listOf(
        BcCommand(),
    )
