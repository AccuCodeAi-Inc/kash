package com.accucodeai.kash.tools.zip

import com.accucodeai.kash.api.CommandSpec

/** Command specs exposed by this tool subsystem. */
public val zipCommands: List<CommandSpec> =
    listOf(
        ZipCommand(),
        UnzipCommand(),
    )
