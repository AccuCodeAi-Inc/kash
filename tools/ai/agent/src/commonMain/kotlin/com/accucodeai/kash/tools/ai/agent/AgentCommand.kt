package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `agent` — interactive LLM agent with shell tool access. TUI in the alt
 * screen, talks directly to any OpenAI-compatible `/v1/chat/completions`
 * endpoint (LM Studio, Ollama, OpenRouter, real OpenAI…) over our own
 * hand-rolled Ktor client. Streams assistant deltas into the history pane
 * in real time.
 *
 * Refuses cleanly when stdin/stdout aren't a TTY or when no
 * [com.accucodeai.kash.api.terminal.TerminalControl] is available — mirrors
 * the nano gating in `NanoCommand`.
 *
 * Argument shapes:
 *   - `agent`                                — default URL + default model
 *   - `agent URL`                            — custom URL, default model
 *   - `agent URL MODEL`                      — both overridden
 *   - `agent URL MODEL --system "..."`       — additional flags
 *
 * Env fallbacks (read via `ctx.env`): `KASH_AGENT_URL`, `KASH_AGENT_MODEL`,
 * `KASH_AGENT_API_KEY`.
 */
public class AgentCommand :
    Command,
    CommandSpec {
    override val name: String = "agent"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE, CommandTag.NETWORK)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val parsed =
            when (val p = parseArgs(args, ctx)) {
                is Parsed.Usage -> {
                    ctx.stderr.writeUtf8(USAGE)
                    return CommandResult(exitCode = 2)
                }

                is Parsed.Version -> {
                    ctx.stdout.writeUtf8("agent (kash) 0.1\n")
                    return CommandResult()
                }

                is Parsed.Err -> {
                    ctx.stderr.writeUtf8("agent: ${p.msg}\n")
                    return CommandResult(exitCode = 2)
                }

                is Parsed.Ok -> {
                    p
                }
            }

        if (!ctx.process.isTty(0) || !ctx.process.isTty(1)) {
            ctx.stderr.writeUtf8("agent: stdin/stdout is not a TTY\n")
            return CommandResult(exitCode = 1)
        }
        // BasicLineEditor needs a TerminalControl handle for raw-mode-during-
        // input. Bail with the same diagnostic shape as nano / python3 when
        // the session didn't supply one.
        val term =
            ctx.process.fdTable[0]
                ?.ofd
                ?.terminalControl ?: run {
                ctx.stderr.writeUtf8("agent: no terminal control available in this session\n")
                return CommandResult(exitCode = 1)
            }

        return CommandResult(exitCode = AgentSession(term, ctx, parsed.config).run())
    }

    private sealed interface Parsed {
        data class Ok(
            val config: AgentConfig,
        ) : Parsed

        data class Err(
            val msg: String,
        ) : Parsed

        data object Usage : Parsed

        data object Version : Parsed
    }

    private fun parseArgs(
        args: List<String>,
        ctx: CommandContext,
    ): Parsed {
        var baseUrl: String? = null
        var modelId: String? = null
        var systemPrompt: String? = null
        var apiKey: String? = null
        var temperature: Double = AgentConfig.DEFAULT_TEMPERATURE
        var toolsEnabled: Boolean = true
        var contextBudget: Int? = null
        var maxSteps: Int? = null
        var showThinking = true
        var appendReasoning = true
        var parseThinkTags = true

        val positionals = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-h" || a == "--help" -> {
                    return Parsed.Usage
                }

                a == "-V" || a == "--version" -> {
                    return Parsed.Version
                }

                a == "--no-tools" -> {
                    toolsEnabled = false
                }

                a == "--no-thinking" -> {
                    showThinking = false
                }

                a == "--no-reasoning-history" -> {
                    appendReasoning = false
                }

                a == "--no-think-tags" -> {
                    parseThinkTags = false
                }

                a == "--system" -> {
                    if (i + 1 >= args.size) return Parsed.Err("--system requires a value")
                    systemPrompt = args[i + 1]
                    i++
                }

                a.startsWith("--system=") -> {
                    systemPrompt = a.substringAfter('=')
                }

                a == "--api-key" -> {
                    if (i + 1 >= args.size) return Parsed.Err("--api-key requires a value")
                    apiKey = args[i + 1]
                    i++
                }

                a.startsWith("--api-key=") -> {
                    apiKey = a.substringAfter('=')
                }

                a == "--temp" || a == "--temperature" -> {
                    if (i + 1 >= args.size) return Parsed.Err("$a requires a value")
                    temperature =
                        args[i + 1].toDoubleOrNull() ?: return Parsed.Err("invalid temperature: ${args[i + 1]}")
                    i++
                }

                a == "--context-budget" -> {
                    if (i + 1 >= args.size) return Parsed.Err("$a requires a value")
                    contextBudget =
                        args[i + 1].toIntOrNull()?.takeIf { it > 0 }
                            ?: return Parsed.Err("invalid context budget: ${args[i + 1]}")
                    i++
                }

                a == "--max-steps" -> {
                    if (i + 1 >= args.size) return Parsed.Err("$a requires a value")
                    maxSteps =
                        args[i + 1].toIntOrNull()?.takeIf { it > 0 }
                            ?: return Parsed.Err("invalid max-steps: ${args[i + 1]}")
                    i++
                }

                a == "--" -> {
                    for (j in i + 1 until args.size) positionals.add(args[j])
                    i = args.size
                    continue
                }

                a.startsWith("-") && a.length > 1 -> {
                    return Parsed.Err("unknown option: $a")
                }

                else -> {
                    positionals.add(a)
                }
            }
            i++
        }

        if (positionals.size > 2) return Parsed.Err("too many positional args; expected [URL] [MODEL]")
        if (positionals.size >= 1) baseUrl = positionals[0]
        if (positionals.size >= 2) modelId = positionals[1]

        val effectiveBase =
            AgentConfig.normalizeBaseUrl(
                baseUrl
                    ?: ctx.env["KASH_AGENT_URL"]
                    ?: AgentConfig.DEFAULT_BASE_URL,
            )
        // Null model → AgentSession probes /v1/models and shows a picker.
        val effectiveModel: String? = modelId ?: ctx.env["KASH_AGENT_MODEL"]
        val effectiveKey = apiKey ?: ctx.env["KASH_AGENT_API_KEY"] ?: ""
        val effectiveSystem = systemPrompt ?: AgentConfig.DEFAULT_SYSTEM_PROMPT
        val effectiveBudget =
            contextBudget
                ?: ctx.env["KASH_AGENT_CONTEXT_BUDGET"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: AgentConfig.DEFAULT_CONTEXT_BUDGET
        val effectiveMaxSteps =
            maxSteps
                ?: ctx.env["KASH_AGENT_MAX_STEPS"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: AgentConfig.DEFAULT_MAX_ITERATIONS

        return Parsed.Ok(
            AgentConfig(
                baseUrl = effectiveBase,
                modelId = effectiveModel,
                apiKey = effectiveKey,
                systemPrompt = effectiveSystem,
                temperature = temperature,
                toolsEnabled = toolsEnabled,
                contextBudgetTokens = effectiveBudget,
                maxIterations = effectiveMaxSteps,
                showThinking = showThinking,
                appendReasoning = appendReasoning,
                parseThinkTags = parseThinkTags,
            ),
        )
    }

    private companion object {
        const val USAGE: String =
            "Usage: agent [URL] [MODEL] [OPTIONS]\n" +
                "  URL                 OpenAI-compatible base URL (default: http://localhost:1234)\n" +
                "  MODEL               model id (omit to pick one from /v1/models)\n" +
                "  --system PROMPT     override the system prompt\n" +
                "  --api-key KEY       Authorization: Bearer KEY\n" +
                "  --temp T            sampling temperature (default: 0.7)\n" +
                "  --no-tools          disable shell/file tool calls\n" +
                "  --no-thinking       don't display the model's reasoning tokens (shown by default)\n" +
                "  --no-think-tags     don't parse inline <think>…</think> spans out of the reply\n" +
                "  --no-reasoning-history  don't echo reasoning back into history (for APIs that reject it)\n" +
                "  --context-budget N  prompt-token budget before history compaction (default: 250000)\n" +
                "  --max-steps N       safety cap on tool rounds per turn (default: 1000)\n" +
                "  -h, --help          show this help\n" +
                "  -V, --version       show version\n" +
                "\n" +
                "Env: KASH_AGENT_URL, KASH_AGENT_MODEL, KASH_AGENT_API_KEY,\n" +
                "     KASH_AGENT_CONTEXT_BUDGET, KASH_AGENT_MAX_STEPS override the defaults.\n"
    }
}
