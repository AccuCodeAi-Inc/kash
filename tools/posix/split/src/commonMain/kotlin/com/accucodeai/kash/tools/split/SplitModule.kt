package com.accucodeai.kash.tools.split

import com.accucodeai.kash.api.CommandSpec

/** Command specs exposed by the `split` subsystem. */
public val splitCommands: List<CommandSpec> =
    listOf(
        SplitCommand(),
    )
