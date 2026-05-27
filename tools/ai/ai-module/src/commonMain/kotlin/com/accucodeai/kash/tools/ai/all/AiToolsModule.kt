package com.accucodeai.kash.tools.ai.all

import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.tools.ai.agent.agentCommands

/**
 * Tools whose execution path goes through an external LLM. Kept out of
 * [com.accucodeai.kash.defaultCommandSpecs] on purpose — the bare `Kash()`
 * embedder and conformance fixtures must stay LLM-free. App entry points
 * (`KashAppModule`, `KashAppWebModule`) opt in by appending this list.
 */
public val aiCommands: List<CommandSpec> = agentCommands
