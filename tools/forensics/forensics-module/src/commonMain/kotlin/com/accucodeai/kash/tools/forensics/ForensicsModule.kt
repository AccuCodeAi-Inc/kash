package com.accucodeai.kash.tools.forensics

import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.tools.binwalk.binwalkCommands
import com.accucodeai.kash.tools.forensics.strings.stringsCommands
import com.accucodeai.kash.tools.openssl.openSslCommands
import com.accucodeai.kash.tools.xxd.xxdCommands

/**
 * Forensics / security-tooling subsystem.
 *
 * Aggregated into the default catalog by [com.accucodeai.kash.defaultCommandSpecs].
 */
public val forensicsCommands: List<CommandSpec> = binwalkCommands + openSslCommands + stringsCommands + xxdCommands
