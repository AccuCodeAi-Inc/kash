package com.accucodeai.kash.tools.xz

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Six wrappers around the
 * shared [XzCommand] engine: `xz` / `unxz` / `xzcat` for the XZ container,
 * `lzma` / `unlzma` / `lzcat` for the legacy LZMA1 alone-format.
 */
public val xzCommands: List<CommandSpec> =
    listOf(
        XzCommand(name = "xz", defaultDecode = false, defaultStdout = false, format = XzFormat.XZ),
        XzCommand(name = "unxz", defaultDecode = true, defaultStdout = false, format = XzFormat.XZ),
        XzCommand(name = "xzcat", defaultDecode = true, defaultStdout = true, format = XzFormat.XZ),
        XzCommand(name = "lzma", defaultDecode = false, defaultStdout = false, format = XzFormat.LZMA),
        XzCommand(name = "unlzma", defaultDecode = true, defaultStdout = false, format = XzFormat.LZMA),
        XzCommand(name = "lzcat", defaultDecode = true, defaultStdout = true, format = XzFormat.LZMA),
    )
