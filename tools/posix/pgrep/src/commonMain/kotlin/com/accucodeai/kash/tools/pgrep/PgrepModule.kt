package com.accucodeai.kash.tools.pgrep

import com.accucodeai.kash.api.CommandSpec

/** Aggregator picked up by the per-subsystem catalog. */
public val pgrepCommands: List<CommandSpec> =
    listOf(
        PgrepCommand(),
    )
