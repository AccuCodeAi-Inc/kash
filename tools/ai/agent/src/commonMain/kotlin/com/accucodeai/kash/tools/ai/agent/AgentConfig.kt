@file:Suppress("HttpUrlsUsage")

package com.accucodeai.kash.tools.ai.agent

/**
 * Effective configuration for one `agent` invocation. Built from CLI args
 * (highest priority) → env vars on [com.accucodeai.kash.api.CommandContext.env]
 * → defaults.
 *
 * @property baseUrl OpenAI-compatible HTTP origin — *no* trailing
 *   `/v1/chat/completions`. Our chat client (see `openai/OpenAIChatClient.kt`)
 *   appends the chat-completions path itself. We accept either form on the
 *   CLI and normalize in [normalizeBaseUrl].
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
     * Carry reasoning back into history as `reasoning_content` on the
     * assistant turn, so the resent prefix matches what the server
     * generated (prefix-cache hits on reasoning models). On by default;
     * disable with `--no-reasoning-history` only for hosted APIs that
     * reject echoed reasoning. (DeepSeek is the *opposite* — it *requires*
     * `reasoning_content` be passed back in thinking mode, so keep it on
     * there.)
     */
    val appendReasoning: Boolean = true,
    /**
     * Parse inline `<think>…</think>` spans out of the streamed reply and
     * treat the inside as reasoning (dimmed display via [showThinking],
     * routed to history via [appendReasoning]). Some models emit reasoning
     * inside their content stream as `<think>` tags rather than out-of-band
     * in the `reasoning_content` field; this normalizes the two paths.
     * Disable with `--no-think-tags` to pass content through verbatim.
     */
    val parseThinkTags: Boolean = true,
    /**
     * Drop user-typed input that arrives while the agent is streaming
     * a response (regular character keys, arrows, etc.) so it doesn't
     * auto-replay at the next prompt — the classic UNIX type-ahead
     * problem, much worse here because a stray Enter would submit a
     * half-typed message to the LLM. Interrupts (Ctrl-C / ESC),
     * Ctrl-D, and out-of-band events (`Key.Paste`, `Key.PrintAbove`
     * from drag-drop / accessibility hooks) are preserved either way.
     *
     * Default on for interactive use. Tests that drive a queue of
     * inputs through a [FakeTerminalControl] (the existing
     * `AgentLoopTest` pattern) should set this to false — under those
     * tests "type-ahead" is the entire delivery mechanism for
     * multi-turn input, and strict draining would swallow the second
     * turn's text along with everything else.
     */
    val strictTurnTaking: Boolean = true,
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
         * Our OpenAI chat client appends `v1/chat/completions`, so we feed
         * it the origin only.
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
