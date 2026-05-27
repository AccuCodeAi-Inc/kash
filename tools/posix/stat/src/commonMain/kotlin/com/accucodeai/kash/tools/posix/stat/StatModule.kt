package com.accucodeai.kash.tools.posix.stat

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into
 * `posixCommands` in `:tools:posix:posix-module`.
 */
public val statCommands: List<CommandSpec> =
    listOf(
        StatCommand(),
    )
