package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.api.CommandSpec

/**
 * Command specs exposed by this tool subsystem. Aggregated into the
 * AI tier's catalog (`aiCommands`).
 */
public val agentCommands: List<CommandSpec> =
    listOf(
        AgentCommand(),
    )
