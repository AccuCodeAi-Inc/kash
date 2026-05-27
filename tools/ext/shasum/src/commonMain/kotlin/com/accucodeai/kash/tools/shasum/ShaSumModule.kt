package com.accucodeai.kash.tools.shasum

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into the per-
 * subsystem catalog (`posixCommands` / `extCommands` / `kashCommands`).
 *
 * No DI — adding a new command means typing its constructor here.
 */
public val shaSumCommands: List<CommandSpec> =
    listOf(
        Md5SumCommand(),
        Sha1SumCommand(),
        Sha224SumCommand(),
        Sha256SumCommand(),
        Sha384SumCommand(),
        Sha512SumCommand(),
        ShaSumCommand(),
    )
