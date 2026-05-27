package com.accucodeai.kash.tools.forensics.strings

import com.accucodeai.kash.api.CommandSpec

/** Command specs exposed by the `strings` subsystem. */
public val stringsCommands: List<CommandSpec> =
    listOf(
        StringsCommand(),
    )
