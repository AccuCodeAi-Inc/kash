package com.accucodeai.kash.tools.bzip2

import com.accucodeai.kash.api.CommandSpec

/**
 * `bzip2`, `bunzip2`, `bzcat` — three entry points sharing one implementation.
 * `bunzip2` is `bzip2 -d`; `bzcat` is `bzip2 -dc`.
 */
public val bzip2Commands: List<CommandSpec> =
    listOf(
        Bzip2Command(name = "bzip2", defaultMode = Bzip2Mode.COMPRESS),
        Bzip2Command(name = "bunzip2", defaultMode = Bzip2Mode.DECOMPRESS),
        Bzip2Command(name = "bzcat", defaultMode = Bzip2Mode.DECOMPRESS_TO_STDOUT),
    )
