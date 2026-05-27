package com.accucodeai.kash.tools.curl

import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.sandbox.NetworkPolicy

/**
 * Factory for the `curl` tool.
 *
 * - `curlCommands` (no args) — defers policy to the [com.accucodeai.kash
 *   .api.KashMachine.networkPolicy] at run time. This is the right form
 *   for the in-tree `extCommands` aggregator: the embedder picks the
 *   policy once at machine construction, and curl honors it without any
 *   per-tool wiring.
 *
 * - `curlCommandsWith(policy)` — pins the curl instance to [policy],
 *   overriding whatever the machine carries. Use for tests, or for hosts
 *   that want curl to run under a stricter posture than the rest of the
 *   shell.
 */
public val curlCommands: List<CommandSpec> = listOf(CurlCommand(policyOverride = null))

/** Build the curl command list against a caller-supplied [policy]. */
public fun curlCommandsWith(policy: NetworkPolicy): List<CommandSpec> = listOf(CurlCommand(policyOverride = policy))
