package com.accucodeai.kash.tools.lz4

import com.accucodeai.kash.api.CommandSpec

public val lz4Commands: List<CommandSpec> =
    listOf(
        Lz4Command(name = "lz4", defaultMode = Lz4Mode.COMPRESS),
        Lz4Command(name = "unlz4", defaultMode = Lz4Mode.DECOMPRESS),
        Lz4Command(name = "lz4cat", defaultMode = Lz4Mode.DECOMPRESS_TO_STDOUT),
    )
