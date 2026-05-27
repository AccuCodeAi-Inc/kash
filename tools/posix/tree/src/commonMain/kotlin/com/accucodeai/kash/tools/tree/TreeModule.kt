package com.accucodeai.kash.tools.tree

import com.accucodeai.kash.api.CommandSpec

/** Tools subsystem entry. Plain list, no DI. */
public val treeCommands: List<CommandSpec> = listOf(TreeCommand())
