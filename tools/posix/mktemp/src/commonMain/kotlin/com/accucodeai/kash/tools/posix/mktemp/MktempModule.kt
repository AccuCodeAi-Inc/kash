package com.accucodeai.kash.tools.posix.mktemp

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into
 * `posixCommands` in `:tools:posix:posix-module`.
 */
public val mktempCommands: List<CommandSpec> =
    listOf(
        MktempCommand(),
    )
