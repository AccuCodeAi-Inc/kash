@file:Suppress("HttpUrlsUsage")

package com.accucodeai.kash.tools.ai.agent

/**
 * Effective configuration for one `agent` invocation. Built from CLI args
 * (highest priority) → env vars on [com.accucodeai.kash.api.CommandContext.env]
 * → defaults.
 *
 * @property baseUrl OpenAI-compatible HTTP origin — *no* trailing
 *   `/v1/chat/completions`. Koog's [ai.koog.prompt.executor.clients.openai
 *   .OpenAIClientSettings] appends `chatCompletionsPath` itself (default
 *   `v1/chat/completions`). We accept either form on the CLI and normalize
 *   in [parse].
 * @property modelId the `model` field sent to the chat-completions endpoint.
 *   `null` means "ask the user" — [AgentSession] probes the server's
 *   `/v1/models` endpoint and shows an arrow-key picker. Explicit values
 *   (CLI positional or `KASH_AGENT_MODEL`) skip the picker entirely.
 * @property apiKey forwarded as `Authorization: Bearer <key>`. Empty string
 *   for self-hosted servers that ignore auth (LM Studio default).
 * @property systemPrompt seed prompt — short on purpose; users can override
 *   via `--system`.
 * @property temperature passed through to the chat-completion request.
 * @property toolsEnabled when false, the agent runs in chat-only mode (no
 *   `tools` array, no `shell_exec` etc.). Useful for self-hosted models
 *   that don't honor the tool-call protocol.
 */
public data class AgentConfig(
    val baseUrl: String,
    val modelId: String?,
    val apiKey: String,
    val systemPrompt: String,
    val temperature: Double,
    val toolsEnabled: Boolean,
    /**
     * Approximate prompt-token budget. When a turn's reported (or
     * estimated) input-token count crosses [COMPACT_FRACTION] of this, the
     * agent compacts history (elides old tool-output bodies) instead of
     * letting the context grow until the backend rejects it. This — not a
     * step counter — is what bounds how long a turn can run. Overridable
     * via `--context-budget` / `KASH_AGENT_CONTEXT_BUDGET`.
     */
    val contextBudgetTokens: Int = DEFAULT_CONTEXT_BUDGET,
    /**
     * Pure safety backstop on tool-call rounds per turn — NOT the primary
     * control. Loop detection stops a stuck agent in a handful of steps,
     * and the token budget + compaction bound a long-but-progressing run;
     * this only exists so a pathological case the other two miss (every
     * step distinct, no usage data, context never filling) can't hang the
     * tab forever. Set very high so normal agentic work never hits it.
     * Overridable via `--max-steps` / `KASH_AGENT_MAX_STEPS`.
     */
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    /**
     * Render the model's reasoning/thinking tokens live (dimmed) as they
     * stream, for reasoning models that emit them. Display only — has no
     * effect on what's sent back to the model. Disable with `--no-thinking`.
     */
    val showThinking: Boolean = true,
    /**
     * Carry reasoning back into history as a [ai.koog.prompt.message.MessagePart.Reasoning]
     * part on the assistant turn, so the resent prefix matches what the
     * server generated (prefix-cache hits on reasoning models). On by
     * default; disable with `--no-reasoning-history` only for hosted APIs that
     * reject echoed reasoning. (DeepSeek is the *opposite* — it *requires*
     * `reasoning_content` be passed back in thinking mode, see Koog #1599 /
     * #1999 — so keep this on there.)
     */
    val appendReasoning: Boolean = true,
    /**
     * Opt into the OpenAI **Responses** API (streaming, stateless) instead of
     * chat **completions**. Default OFF — both Koog 1.0 paths mishandle
     * reasoning, but differently: completions silently *drops* it (its
     * stream-delta model has no reasoning field), while Responses *crashes* on
     * it (`OutputContent` is a sealed type with no `reasoning_text` variant, so
     * the `response.content_part.added` reasoning event fails strict
     * deserialization — and it's unshimmable: sealed type + private decode).
     * Completions at least works for text + tools, so it's the safe default.
     * `--responses` flips this on for when Koog fixes the Responses decoder.
     */
    val useResponsesApi: Boolean = false,
    /**
     * Parse inline `<think>…</think>` spans out of the streamed reply and treat
     * the inside as reasoning (dimmed display via [showThinking], routed to
     * history via [appendReasoning]). This is how reasoning surfaces on the
     * chat-completions endpoint, where Koog drops the dedicated reasoning field
     * — the model has to emit think-tags in its content (LM Studio: turn its
     * reasoning *separation* off). A no-op when the content has no tags.
     * Disable with `--no-think-tags` to pass content through verbatim.
     */
    val parseThinkTags: Boolean = true,
) {
    public companion object {
        public const val DEFAULT_BASE_URL: String = "http://localhost:1234"
        public const val DEFAULT_TEMPERATURE: Double = 0.7

        /**
         * Default prompt-token budget. 250k targets the large-context
         * models people actually run the agent against; compaction
         * triggers at [COMPACT_FRACTION] of this. Lower it with
         * `--context-budget` for a small local model.
         */
        public const val DEFAULT_CONTEXT_BUDGET: Int = 250_000

        /** Compact when input tokens exceed this fraction of the budget. */
        public const val COMPACT_FRACTION: Double = 0.75

        /**
         * Safety-backstop step ceiling. Deliberately high — the real
         * controls are loop detection + the token budget. Effectively
         * "never" for legitimate work.
         */
        public const val DEFAULT_MAX_ITERATIONS: Int = 1000
        public const val DEFAULT_SYSTEM_PROMPT: String =
            "You are kash-agent, a coding assistant running inside a persistent kash shell. " +
                "Use shell_exec to run commands and explore — it runs in a non-interactive kash " +
                "subshell where cwd, env, functions, and aliases survive across calls. But to read " +
                "or change a file's contents, use read_file and write_file: write_file shows the " +
                "user a diff of your edit, whereas editing through shell_exec (sed, echo >, " +
                "redirects) hides the change from them.\n\n" +
                "Keep working until the task is actually done. The turn ends the moment you reply " +
                "with plain text and no tool call, so don't stop to narrate progress or ask " +
                "permission mid-task — just keep calling tools. Reply with text only when the work " +
                "is complete or you're genuinely blocked, and keep that final reply concise.\n\n" +
                "To look at an image file, read_file it — you'll see the picture directly on the " +
                "next step. Text files: read_file. Other binary: shell_exec (xxd, base64 -d, …).\n\n" +
                "Attachments: dragged-in files appear as `[attachment N]` markers under an " +
                "`Attachments:` block listing each file's name, type, size, its path under " +
                "/tmp/drops/, and a pre-exported `\$KASH_ATTACHMENT_N` env var holding that path — " +
                "prefer the env var in shell_exec (`unzip \"\$KASH_ATTACHMENT_1\"`). Attached images " +
                "are shown to you directly, so just look at them."

        /**
         * Normalize a user-typed base URL. We accept the shapes people copy-paste
         * from LM Studio / Ollama docs:
         *   - `http://localhost:1234`             — as-is
         *   - `http://localhost:1234/`            — strip trailing slash
         *   - `http://localhost:1234/v1`          — strip `/v1`
         *   - `http://localhost:1234/v1/`         — strip `/v1/`
         *   - `localhost:1234`                    — prepend `http://`
         * The Koog client always appends `v1/chat/completions`, so we feed it
         * the origin only.
         */
        public fun normalizeBaseUrl(raw: String): String {
            val withScheme =
                if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    raw
                } else {
                    "http://$raw"
                }
            return withScheme
                .trimEnd('/')
                .removeSuffix("/v1")
                .trimEnd('/')
        }
    }
}
