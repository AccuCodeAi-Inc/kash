package com.accucodeai.kash.tools.shuf

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into
 * [com.accucodeai.kash.tools.posix.posixCommands].
 */
public val shufCommands: List<CommandSpec> =
    listOf(
        ShufCommand(),
    )
