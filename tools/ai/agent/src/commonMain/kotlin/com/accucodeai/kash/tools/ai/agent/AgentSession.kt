package com.accucodeai.kash.tools.ai.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import com.accucodeai.kash.api.AttachmentSink
import com.accucodeai.kash.api.BasicLineEditor
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.LineEditorResult
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.net.HttpRequest
import com.accucodeai.kash.net.KashKtorClient
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Graph-based chat loop. Drives a Koog [AIAgent] whose **custom strategy
 * graph** is one long-lived `run()` that loops: read user input → call the
 * LLM → execute tools → compress history when large → back to read input.
 *
 * Why a single long-lived run rather than a `run()` per turn: Koog's
 * built-in history compression ([nodeLLMCompressHistory]) compresses the
 * agent's prompt *within* a run, and [AIAgent] re-seeds the prompt from
 * config on every `run()`. So to get JetBrains' summarization compaction
 * across turns, the whole conversation must live inside one run — hence the
 * `readInput` node that calls our [BasicLineEditor] mid-graph.
 *
 * Rendering is via the [EventHandler] feature (tool-call indicators + tool
 * results); assistant text is rendered straight from the LLM-node result.
 *
 * Preserved from the pre-graph implementation: the interactive `/v1/models`
 * picker, the hand-rolled model probe (koog #139), the LM-Studio/Ollama
 * error hints, the drag-and-drop attachment surface ([AttachmentSink]).
 */
internal class AgentSession(
    private val term: TerminalControl,
    private val ctx: CommandContext,
    private val config: AgentConfig,
    /**
     * Test seam: inject a fake [ai.koog.prompt.executor.model.PromptExecutor]
     * to drive the graph without a live LLM. Null in production — we build
     * a [MultiLLMPromptExecutor] over the LM-Studio/OpenAI client.
     */
    private val executorOverride: ai.koog.prompt.executor.model.PromptExecutor? = null,
) : AttachmentSink {
    private val out: SuspendSink get() = ctx.stdout
    private val shell = KashAgentShell(ctx)
    private val editor = BasicLineEditor(term)

    // Images read_file recognized this turn, awaiting injection as a vision
    // user-part before the next LLM call — a tool result can't carry an image
    // on the OpenAI wire format, so we bridge it through the following user
    // message (see drainPendingImages).
    private val pendingImages = mutableListOf<PendingImage>()

    // Tool output (the write_file diff) is emitted as one block; the default
    // hook prints it straight to stdout — interleaved between this tool's
    // indicator and its result (append-only; see the EventHandler in run()).
    private val toolset = KashAgentToolset(ctx, shell, onImage = { pendingImages += it })

    /** Drag-and-drop registry + multimodal user-message builder. */
    private val attachments = AgentAttachments(ctx.fs)

    /**
     * How many empty replies we've nudged this user turn. Capped at
     * [MAX_NUDGES_PER_TURN]: a model doing genuine multi-step work (a tool
     * call between each empty reply) gets nudged every time it stops, but a
     * model stuck returning empty is eventually let go instead of nudged
     * forever.
     */
    private var nudgesThisTurn = 0

    /**
     * How many assistant turns this user turn emitted a tool call whose
     * arguments were malformed JSON. Bounds the self-correction re-ask loop
     * ([MAX_MALFORMED_TOOL_CALLS_PER_TURN]): the model gets a precise error a
     * few times to fix its call, then the turn ends rather than spinning to
     * `maxAgentIterations`. Reset per user turn alongside [nudgesThisTurn].
     */
    private var malformedToolCallsThisTurn = 0

    private val llmClient: OpenAILLMClient =
        LenientOpenAILLMClient(
            apiKey = config.apiKey,
            settings = OpenAIClientSettings(baseUrl = config.baseUrl),
            // 1.0 decoupled HTTP transport from the client — every target
            // must supply a factory explicitly (JVM can ServiceLoader one;
            // wasmJs can't). The Ktor engine resolves transitively via
            // :shared:net's wasmJs Ktor dependency.
            httpClientFactory = KtorKoogHttpClient.Factory(),
        )

    private lateinit var model: LLModel

    override suspend fun add(
        path: String,
        mimeType: String,
        sizeBytes: Long,
        fileName: String,
    ): Int {
        val index = attachments.add(path, mimeType, sizeBytes, fileName)
        // Surface the drop banner above the prompt via a synthetic key the
        // line editor renders (see Key.PrintAbove). Works whether or not
        // the editor is currently reading — the event queues otherwise.
        for (n in attachments.drainNotices()) {
            val banner =
                "${C_DIM}${agentGlyphs.toolBullet} attached ${n.fileName} " +
                    "(${AgentAttachments.humanBytes(n.sizeBytes)}) as [attachment ${n.index}]${C_RESET}"
            term.pushKey(Key.PrintAbove(banner))
        }
        return index
    }

    private fun buildModel(id: String): LLModel =
        LLModel(
            provider = LLMProvider.OpenAI,
            id = id,
            capabilities =
                buildList {
                    add(LLMCapability.Completion)
                    // Completions by default. Both Koog 1.0 paths mishandle
                    // reasoning, but completions at least works for text + tools;
                    // the Responses path crashes deserializing the reasoning
                    // content-part (sealed OutputContent has no `reasoning_text`).
                    // `--responses` opts in for when Koog fixes that.
                    if (config.useResponsesApi) {
                        add(LLMCapability.OpenAIEndpoint.Responses)
                        add(LLMCapability.Thinking)
                    } else {
                        add(LLMCapability.OpenAIEndpoint.Completions)
                    }
                    add(LLMCapability.Vision.Image)
                    if (config.toolsEnabled) add(LLMCapability.Tools)
                },
        )

    /**
     * Per-endpoint request params seeded onto the agent prompt.
     *
     * Responses (default) runs **stateless**: `store = false` and no
     * `previous_response_id` — Koog always resends the full prompt, so our own
     * history management + compaction stay authoritative and nothing is
     * retained server-side. Passing an [OpenAIResponsesParams] also routes
     * Koog's `determineParams` down the Responses path. Completions uses plain
     * [LLMParams] (reasoning is dropped there; see [buildModel]). Either way we
     * thread [AgentConfig.temperature] through, which previously went unused.
     */
    private fun promptParams(): LLMParams =
        if (config.useResponsesApi) {
            OpenAIResponsesParams(temperature = config.temperature, store = false)
        } else {
            LLMParams(temperature = config.temperature)
        }

    suspend fun run(): Int {
        val chosen =
            resolveModel() ?: run {
                ctx.stderr.writeUtf8("agent: no model chosen\n")
                return 1
            }
        model = buildModel(chosen)
        printBanner()

        // Publish ourselves as the attachment sink for this process.
        val previousSink = ctx.process.attachmentSink
        ctx.process.attachmentSink = this
        try {
            val agent =
                AIAgent(
                    promptExecutor = executorOverride ?: MultiLLMPromptExecutor(llmClient),
                    agentConfig =
                        AIAgentConfig(
                            prompt =
                                ai.koog.prompt.dsl.prompt("kash-agent", params = promptParams()) {
                                    system(config.systemPrompt + "\n\n" + shellInventory)
                                },
                            model = model,
                            maxAgentIterations = config.maxIterations,
                        ),
                    strategy = buildStrategy(),
                    toolRegistry = toolset.registry(),
                ) {
                    install(EventHandler) {
                        // Append-only, one tool at a time. Koog runs tools
                        // SEQUENTIALLY (buildStrategy), firing
                        // onToolCallStarting → run → onToolCallCompleted per
                        // call in order, so each indicator is immediately
                        // followed by its own output and result
                        // (`• a / [diff] / ↳ a / • b / ↳ b`). No footer / cursor
                        // math / scrollback rewriting — robust on any terminal.
                        onToolCallStarting { e ->
                            out.writeUtf8(
                                "${C_DIM}${agentGlyphs.toolBullet}${C_RESET} ${C_CYAN}${e.toolName}${C_RESET}" +
                                    "(${truncateInline(toolArgSummary(e.toolArgs.toString()), 80)})\n",
                            )
                        }
                        onToolCallCompleted { e ->
                            val first =
                                e.toolResult
                                    ?.toString()
                                    ?.lineSequence()
                                    ?.firstOrNull { it.isNotBlank() }
                                    ?.let { truncateInline(it, 100) }
                                    .orEmpty()
                            out.writeUtf8("  ${C_DIM}${agentGlyphs.toolResultArrow}${C_RESET} $first\n")
                        }
                    }
                }
            // One run drives the whole REPL; readInput loops until /quit or EOF.
            agent.run("")
            return 0
        } catch (t: Throwable) {
            out.writeUtf8("\n${C_RED}error: ${describeError(t)}${C_RESET}\n")
            return 1
        } finally {
            ctx.process.attachmentSink = previousSink
            try {
                llmClient.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * The session-loop strategy: readInput → callLLM → (tools → compress? →
     * sendResult)* → back to readInput. Compression fires when the prompt
     * grows past the budget; [HistoryCompressionStrategy.WholeHistory]
     * summarizes the whole history into a TLDR (JetBrains-tuned).
     */
    private fun buildStrategy(): AIAgentGraphStrategy<String, String> =
        strategy("kash-agent-repl") {
            val readInput by node<String, String> {
                readNextUserInput()
            }
            // Custom non-appending LLM node: readInput already appended the
            // (possibly multimodal) user message, so we request on the
            // current prompt rather than appending the node input.
            val callLLM by node<String, Message.Assistant> {
                llm.writeSession { streamAssistant() }
            }
            // parallel = false is load-bearing for rendering: it makes Koog
            // fire onToolCallStarting/Completed one tool at a time in order, so
            // the EventHandler can render `• tool / ↳ result` interleaved
            // append-only. Flipping to parallel needs a rewriting renderer
            // (see the EventHandler comment in run()).
            val executeTools by nodeExecuteTools(parallel = false)
            // nodeExecuteTools returns the results WITHOUT writing them to
            // the prompt, so we must append them (as the stock
            // nodeLLMSendToolResults does) before asking the model to
            // respond. Skipping this left a dangling tool call in history
            // and poisoned every later turn into empty replies.
            val sendToolResults by node<ReceivedToolResults, Message.Assistant> { results ->
                llm.writeSession {
                    appendPrompt {
                        user { results.toolResults.forEach { r -> toolResult(r.toMessagePart()) } }
                    }
                    drainPendingImages()
                    streamAssistant()
                }
            }
            val compressHistory by nodeLLMCompressHistory<ReceivedToolResults>(
                strategy = HistoryCompressionStrategy.WholeHistory,
            )
            // Compaction is otherwise invisible: nodeLLMCompressHistory fires its
            // own (non-streamed) summarization request that bypasses the assistant
            // renderer, so the user just sees an unexplained pause and then older
            // tool output silently gone. Announce it on the way in. Pass-through —
            // the input is forwarded unchanged to compressHistory.
            val announceCompaction by node<ReceivedToolResults, ReceivedToolResults> { results ->
                out.writeUtf8(
                    "${C_DIM}${agentGlyphs.toolBullet} compacting history to stay within the context budget…${C_RESET}\n",
                )
                results
            }
            val sendCompressed by node<ReceivedToolResults, Message.Assistant> { results ->
                llm.writeSession {
                    appendPrompt {
                        user { results.toolResults.forEach { r -> toolResult(r.toMessagePart()) } }
                    }
                    drainPendingImages()
                    streamAssistant()
                }
            }
            // The model sometimes ends a turn with NO content for the user
            // (empty/whitespace, no tool calls) — the Qwen/LM-Studio
            // "finish_reason: stop with empty content" turn (see isBlankReply),
            // often right after tool results or even in answer to this very
            // nudge. Instead of dropping silently to the prompt, inject a hidden
            // nudge (not rendered) and re-request so it summarizes or continues.
            // EVERY node that can emit a blank turn routes here — callLLM,
            // sendToolResults, sendCompressed — so a model that answers the nudge
            // with another empty turn is nudged again rather than slipping out
            // through callLLM (which has no other blank-reply edge). The only
            // bound on the re-nudge loop is the per-turn cap (nudgesThisTurn <
            // MAX_NUDGES_PER_TURN): genuine multi-step work is nudged at each
            // stop while a stuck model is eventually let go to the prompt.
            val nudgeSummarize by node<Message.Assistant, String> {
                nudgesThisTurn++
                llm.writeSession { appendPrompt { user(SUMMARIZE_NUDGE) } }
                "go"
            }

            edge(nodeStart forwardTo readInput)
            edge(readInput forwardTo nodeFinish onCondition { it == QUIT_SENTINEL })
            edge(readInput forwardTo callLLM onCondition { it != QUIT_SENTINEL })

            edge(callLLM forwardTo executeTools onToolCalls { true })
            edge(
                callLLM forwardTo nudgeSummarize onCondition
                    { nudgesThisTurn < MAX_NUDGES_PER_TURN && it.isBlankReply() },
            )
            edge(callLLM forwardTo readInput onTextMessage { true } transformed { "" })

            edge(
                executeTools forwardTo announceCompaction
                    onCondition { llm.readSession { isHistoryTooBig() } },
            )
            edge(announceCompaction forwardTo compressHistory)
            edge(
                executeTools forwardTo sendToolResults
                    onCondition { llm.readSession { !isHistoryTooBig() } },
            )
            edge(compressHistory forwardTo sendCompressed)

            edge(sendToolResults forwardTo executeTools onToolCalls { true })
            edge(
                sendToolResults forwardTo nudgeSummarize onCondition
                    { nudgesThisTurn < MAX_NUDGES_PER_TURN && it.isBlankReply() },
            )
            edge(sendToolResults forwardTo readInput onTextMessage { true } transformed { "" })

            edge(sendCompressed forwardTo executeTools onToolCalls { true })
            edge(
                sendCompressed forwardTo nudgeSummarize onCondition
                    { nudgesThisTurn < MAX_NUDGES_PER_TURN && it.isBlankReply() },
            )
            edge(sendCompressed forwardTo readInput onTextMessage { true } transformed { "" })

            // The nudged re-request runs on the current prompt (callLLM streams).
            edge(nudgeSummarize forwardTo callLLM)
        }

    /**
     * A turn-ending reply that says nothing to the user: the model signalled
     * completion (`finish_reason: stop`) with no tool calls and only whitespace
     * text. This is the documented Qwen/LM-Studio "stop with empty content"
     * shape (empty `tool_calls: []` + blank content) that the [nudgeSummarize]
     * node re-requests on rather than dropping the user on raw tool output.
     * Keying off the stop signal — not blank text alone — avoids mistaking a
     * truncated/aborted stream for a deliberate empty turn; a dropped finalizing
     * chunk defaults to "stop" (see streamAssistant) so that case is still caught.
     */
    private fun Message.Assistant.isBlankReply(): Boolean =
        finishReason == "stop" &&
            parts.none { it is MessagePart.Tool.Call } &&
            parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.isBlank()

    /** Token-budget proxy: message count past a coarse threshold. */
    private fun ai.koog.agents.core.agent.session.AIAgentLLMReadSession.isHistoryTooBig(): Boolean =
        prompt.messages.size > (this@AgentSession.config.contextBudgetTokens / APPROX_TOKENS_PER_MESSAGE)

    /**
     * Read one user turn into the agent prompt. Handles slash commands
     * inline; loops until a real submission. Returns [QUIT_SENTINEL] to
     * route to nodeFinish, or any other string to proceed to the LLM.
     *
     * Runs inside a graph node, so it has the `llm` write session for
     * appending the (possibly multimodal) user message to the live prompt.
     */
    private suspend fun ai.koog.agents.core.agent.context.AIAgentGraphContextBase.readNextUserInput(): String {
        while (true) {
            val read =
                editor.readLine(
                    prompt = "${C_BOLD}${C_GREEN}${agentGlyphs.userPrompt}${C_RESET} ",
                    continuationPrompt = "  ",
                    isComplete = { true },
                )
            when (read) {
                is LineEditorResult.Eof -> {
                    out.writeUtf8("\n")
                    return QUIT_SENTINEL
                }

                LineEditorResult.Interrupted -> {
                    out.writeUtf8("\n")
                    continue
                }

                is LineEditorResult.Line -> {
                    val text = read.text.trim()
                    if (text.isEmpty()) continue
                    if (text.startsWith("/")) {
                        when (text) {
                            "/quit", "/exit" -> {
                                out.writeUtf8("${C_DIM}bye${C_RESET}\n")
                                return QUIT_SENTINEL
                            }

                            "/help" -> {
                                printHelp()
                                continue
                            }

                            "/clear", "/reset" -> {
                                // Drop everything but the system prompt(s) from
                                // the live prompt — the conversation restarts
                                // while the agent run keeps going.
                                llm.writeSession {
                                    rewritePrompt { p ->
                                        p.copy(messages = p.messages.filterIsInstance<Message.System>())
                                    }
                                }
                                out.writeUtf8("${C_DIM}conversation history cleared${C_RESET}\n")
                                continue
                            }

                            "/rewind" -> {
                                // Trim back to just before the last user turn.
                                llm.writeSession {
                                    rewritePrompt { p ->
                                        val i = p.messages.indexOfLast { it is Message.User }
                                        if (i <= 0) p else p.copy(messages = p.messages.take(i))
                                    }
                                }
                                out.writeUtf8("${C_DIM}rewound the last exchange${C_RESET}\n")
                                continue
                            }

                            "/model" -> {
                                val id = forceModelPick()
                                if (id != null) {
                                    model = buildModel(id)
                                    // Repoint the live session at the new model;
                                    // subsequent requests use it.
                                    llm.writeSession { model = this@AgentSession.model }
                                    out.writeUtf8("${C_DIM}switched to $id${C_RESET}\n")
                                }
                                continue
                            }

                            else -> {
                                out.writeUtf8("${C_DIM}unknown command: $text · try /help${C_RESET}\n")
                                continue
                            }
                        }
                    }
                    editor.addHistory(text)
                    // Apply queued KASH_ATTACHMENT_N exports at this quiescent
                    // point (no tool call in flight).
                    for (b in attachments.drainPendingExports()) {
                        shell.exportVar(b.name, b.path)
                    }
                    // New user turn: reset the per-turn empty-reply nudge count
                    // and the malformed-tool-call re-ask counter.
                    nudgesThisTurn = 0
                    malformedToolCallsThisTurn = 0
                    // Append the (possibly multimodal) user message to the
                    // agent's live prompt; the callLLM node then requests on it.
                    val parts = attachments.buildUserParts(text)
                    llm.writeSession { appendPrompt { user(parts) } }
                    return "go"
                }
            }
        }
    }

    private suspend fun printHelp() {
        out.writeUtf8(
            "${C_DIM}commands:\n" +
                "  /quit, /exit   leave the agent\n" +
                "  /clear, /reset clear conversation history (keeps the shell)\n" +
                "  /rewind        drop the last user/assistant exchange\n" +
                "  /model         pick a different model from /v1/models\n" +
                "  /help          show this list\n" +
                "\n" +
                "input:\n" +
                "  enter          submit\n" +
                "  ctrl-j         insert newline\n" +
                "  paste          newlines preserved${C_RESET}\n",
        )
    }

    /** Force the interactive model picker (ignores the CLI-supplied id). */
    private suspend fun forceModelPick(): String? {
        val models =
            try {
                fetchModelIds()
            } catch (t: Throwable) {
                out.writeUtf8("\n${C_RED}error: couldn't fetch models: ${describeError(t)}${C_RESET}\n")
                return null
            }
        return when (models.size) {
            0 -> {
                out.writeUtf8("${C_RED}error: server returned no models${C_RESET}\n")
                null
            }

            1 -> {
                models.first()
            }

            else -> {
                pickModelInteractive(models)
            }
        }
    }

    /**
     * Flush any images `read_file` recognized this turn into the live prompt
     * as a vision user-part, then clear the queue. Called after tool results
     * are appended and before the next [streamAssistant] request: a tool
     * result is text-only on the OpenAI wire, so the image rides in on the
     * following user message instead. No-op when nothing was queued.
     */
    private suspend fun ai.koog.agents.core.agent.session.AIAgentLLMWriteSession.drainPendingImages() {
        if (pendingImages.isEmpty()) return
        val parts =
            pendingImages.map {
                AgentAttachments.imagePart(it.bytes, it.mimeType, it.fileName)
            }
        pendingImages.clear()
        appendPrompt { user(parts) }
    }

    /**
     * Stream one assistant response on the current prompt, rendering text
     * deltas live and showing a "thinking…" spinner until the first frame
     * arrives. [requestLLMStreaming] auto-appends the assembled assistant
     * message to the prompt; we read it back off the prompt tail to hand
     * to the graph edges (which classify tool-call vs text).
     */

    private suspend fun ai.koog.agents.core.agent.session.AIAgentLLMWriteSession.streamAssistant(): Message.Assistant {
        val session = this
        val frames = mutableListOf<StreamFrame>()
        // `config` inside this write-session extension resolves to the session
        // receiver's own config, so reach the agent's via this@AgentSession.
        val showThinking = this@AgentSession.config.showThinking
        // Pull <think>…</think> reasoning out of the content stream for the
        // chat-completions endpoint (Koog gives us no reasoning frames there).
        // Null when disabled → content streams through verbatim.
        val thinkSplitter = if (this@AgentSession.config.parseThinkTags) ThinkTagSplitter() else null
        var renderedText = false
        // Whitespace runs are held back rather than written immediately, and
        // only flushed once real content follows. This collapses the leading
        // and trailing blank lines models love to emit (e.g. "\n\nHey…\n\n"
        // plus standalone "\n" parts) while preserving whitespace *between*
        // content. Anything still held at end-of-turn is dropped.
        var heldWs = ""
        // Set the moment a tool-call frame reveals the tool name, so the
        // spinner can switch from "thinking…" to "calling <tool>…" while the
        // arguments are still streaming. Previously the spinner was cancelled
        // on the FIRST frame of any kind; a tool-call turn renders no text, so
        // it went blank from that first frame until executeTools printed its
        // indicator — the "thinking… then blank for a while" the user saw.
        var pendingTool: String? = null
        // A closing newline is owed once text has been streamed; it's written
        // either when we restart the spinner for the tool phase (to drop the
        // spinner onto its own line below the text) or at end-of-turn.
        var textNewlinePending = false
        // True while a dim "thinking" block is open on screen (reasoning models
        // that stream ReasoningDelta frames). Closed — reset color + newline —
        // the moment real text, a tool call, or end-of-turn arrives.
        var reasoningOpen = false
        kotlinx.coroutines.coroutineScope {
            var spinnerJob: kotlinx.coroutines.Job? = null

            fun launchSpinner() {
                if (spinnerJob == null) {
                    spinnerJob = launch { spinnerLoop { pendingTool?.let { name -> "calling $name…" } ?: "thinking…" } }
                }
            }

            suspend fun stopSpinner() {
                val j = spinnerJob ?: return
                spinnerJob = null
                // Join so the ticker can't draw a frame AFTER we clear its line.
                j.cancel()
                j.join()
                out.writeUtf8("\r$ERASE_TO_EOL$SHOW_CURSOR")
            }

            // The model is generating tool calls: make sure a spinner is
            // showing ("calling <tool>…"). If text was already streamed, close
            // its line first so the spinner sits on its own line below it —
            // this is the "after the reply, before the tools" gap that
            // otherwise looks frozen.
            suspend fun ensureToolPhaseSpinner() {
                if (spinnerJob != null) return
                if (textNewlinePending) {
                    out.writeUtf8("\n")
                    textNewlinePending = false
                }
                launchSpinner()
            }

            // Close the dim reasoning block (if open): reset color and drop to
            // a fresh line so the answer / tool spinner doesn't inherit the dim
            // SGR. Idempotent.
            suspend fun closeReasoning() {
                if (reasoningOpen) {
                    out.writeUtf8("$C_RESET\n")
                    reasoningOpen = false
                }
            }

            // Stream visible reply text with the leading/trailing-blank-line
            // collapse described above.
            suspend fun renderAnswer(text: String) {
                val combined = heldWs + text
                val lastNonWs = combined.indexOfLast { !it.isWhitespace() }
                if (lastNonWs < 0) {
                    heldWs = combined
                } else {
                    heldWs = combined.substring(lastNonWs + 1)
                    var toEmit = combined.substring(0, lastNonWs + 1)
                    if (!renderedText) toEmit = toEmit.trimStart()
                    if (toEmit.isNotEmpty()) {
                        stopSpinner()
                        closeReasoning()
                        renderedText = true
                        textNewlinePending = true
                        out.writeUtf8(toEmit)
                    }
                }
            }

            // Stream reasoning dimmed under a one-time "thinking" header, dim
            // SGR held open across chunks (closeReasoning resets it). Gated by
            // --no-thinking; shared by Responses ReasoningDelta frames and
            // parsed <think> spans.
            suspend fun renderReasoning(text: String) {
                if (!showThinking || text.isEmpty()) return
                if (!reasoningOpen) {
                    stopSpinner()
                    // Open the dim run once and let it ride across chunks
                    // (closeReasoning resets it). No "thinking" header — it
                    // reprinted once per turn and was just noise; the faint
                    // styling is the cue. Real terminals render SGR 2 natively;
                    // the wasm web emulator now does too (AnsiInterpreter).
                    out.writeUtf8(C_DIM)
                    reasoningOpen = true
                }
                out.writeUtf8(text)
            }

            // Route a content delta: with the splitter, answer spans render as
            // reply and <think> spans render dimmed; without it, raw.
            suspend fun renderContent(text: String) {
                if (thinkSplitter == null) {
                    renderAnswer(text)
                    return
                }
                for (seg in thinkSplitter.push(text)) {
                    when (seg) {
                        is ThinkTagSplitter.Seg.Answer -> renderAnswer(seg.text)
                        is ThinkTagSplitter.Seg.Reasoning -> renderReasoning(seg.text)
                    }
                }
            }

            launchSpinner()
            try {
                session.requestLLMStreaming().collect { frame ->
                    frames += frame
                    when (frame) {
                        is StreamFrame.TextDelta -> {
                            renderContent(frame.text)
                        }

                        // Reasoning frames (Responses path). Display-only and
                        // gated by --no-thinking; the frames stay in `frames`
                        // for the history append regardless.
                        is StreamFrame.ReasoningDelta -> {
                            frame.text?.let { renderReasoning(it) }
                        }

                        // Tool-call frames: name known → relabel + (re)start the
                        // spinner so the tool-generation/execution gap shows
                        // "calling <tool>…" instead of going silent.
                        is StreamFrame.ToolCallDelta -> {
                            closeReasoning()
                            frame.name?.takeIf { it.isNotEmpty() }?.let { pendingTool = it }
                            ensureToolPhaseSpinner()
                        }

                        is StreamFrame.ToolCallComplete -> {
                            closeReasoning()
                            pendingTool = frame.name
                            ensureToolPhaseSpinner()
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Tolerate a malformed TRAILING chunk. Koog 1.0 hardcodes
                // OpenAI `stream_options.include_usage=true` and strict-parses
                // every SSE chunk; LM Studio (and some Ollama builds) emit a
                // final usage-only chunk missing the now-required fields
                // (choices/created/id/model/object), which throws *after* all
                // the real content frames already arrived. If we collected any
                // content, keep it and proceed; only rethrow on a truly empty
                // stream (a real failure, not the trailing-chunk quirk).
                if (frames.none { it is StreamFrame.TextComplete || it is StreamFrame.ToolCallComplete }) {
                    throw e
                }
            } finally {
                // Drain text the splitter held across the final chunk boundary
                // (a partial tag, or an unclosed <think>) so nothing is lost.
                thinkSplitter?.flush()?.forEach { seg ->
                    when (seg) {
                        is ThinkTagSplitter.Seg.Answer -> renderAnswer(seg.text)
                        is ThinkTagSplitter.Seg.Reasoning -> renderReasoning(seg.text)
                    }
                }
                // Reset the dim reasoning SGR (if a thinking block is still
                // open) and clear any running spinner (both idempotent) so the
                // next committed line starts clean.
                closeReasoning()
                stopSpinner()
            }
        }
        // Close the text line if we streamed text and haven't already (a tool
        // phase consumes the pending newline when it restarts the spinner).
        if (textNewlinePending) out.writeUtf8("\n")
        // The write session's requestLLMStreaming does NOT auto-append the
        // assembled message (only structured requests do), so we build it
        // ourselves and append it — this carries the assistant turn into
        // history AND is what the graph edges classify (tool-call vs text).
        //
        // We can't lean on Koog's toMessageResponse alone: it assembles only
        // from *Complete frames, but LM Studio streams text as TextDelta and
        // its finalizing chunk is often the one LenientOpenAILLMClient had to
        // drop — leaving zero Complete frames and a parts=[] message that
        // matches NO edge (dead-ends the graph). So: if there are tool calls,
        // trust Koog's assembly; otherwise build a Text part from the
        // completes, falling back to concatenated deltas, and always emit at
        // least an (empty) Text part so the onTextMessage edge routes back to
        // the prompt.
        val toolCalls = frames.filterIsInstance<StreamFrame.ToolCallComplete>()
        // Full streamed content (Complete frames, else concatenated deltas).
        val completed = frames.filterIsInstance<StreamFrame.TextComplete>().joinToString("") { it.text }
        val rawText =
            completed.ifEmpty {
                frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text }
            }
        // Split out <think> reasoning so history carries a clean answer plus a
        // Reasoning part (which Koog re-serializes as `reasoning_content` for
        // prefix caching). Done for both branches so reasoning that preceded a
        // tool call still lands in history. Null when parsing is off / no tags.
        val cleanText: String
        val parsedReasoning: String?
        if (this@AgentSession.config.parseThinkTags) {
            val split = splitThinkTags(rawText)
            cleanText = split.first
            parsedReasoning = split.second.ifEmpty { null }
        } else {
            cleanText = rawText
            parsedReasoning = null
        }
        // Tool calls whose `arguments` didn't parse to a JSON object — the
        // small-model failure mode (empty/truncated/mis-escaped args). We do
        // NOT silently coerce them to `{}` (that reads to the model as "I sent
        // valid empty args" and it loops); instead we preserve the raw text and
        // surface a precise "malformed JSON" error it can self-correct from
        // (see [markMalformedToolCallArgs] and KashAgentToolset's decodeArgs).
        val malformed = toolCalls.filter { it.contentJsonResult.isFailure }
        val base =
            if (toolCalls.isNotEmpty()) {
                // Bound it: a model that can't form a valid call after a few
                // tries must not spin to maxAgentIterations. Once every call in
                // the turn is malformed AND we're past the cap, drop the calls
                // and end the turn with a plain-text explanation (routes to
                // readInput) instead of re-asking forever.
                if (malformed.isNotEmpty()) malformedToolCallsThisTurn++
                val allMalformed = malformed.size == toolCalls.size
                if (allMalformed && malformedToolCallsThisTurn > MAX_MALFORMED_TOOL_CALLS_PER_TURN) {
                    val tool = toolCalls.first().name.ifEmpty { "the tool" }
                    val giveUp =
                        "I couldn't build a valid `$tool` call after several tries (the arguments kept " +
                            "coming back as invalid JSON), so I've stopped to avoid looping. Try rephrasing, " +
                            "or for a large file write it via shell_exec with a heredoc."
                    // This message is fabricated after the streaming loop, so it
                    // was never rendered — write it out so the user sees why the
                    // turn ended (it routes to readInput as a normal text reply).
                    out.writeUtf8("$giveUp\n")
                    Message.Assistant(
                        content = giveUp,
                        metaInfo = ResponseMetaInfo.Empty,
                        finishReason = "stop",
                    )
                } else {
                    frames.markMalformedToolCallArgs().toMessageResponse()
                }
            } else {
                // Carry the model's real finish reason so isBlankReply can key
                // off the documented "stop with empty content" signal. LM Studio
                // routinely drops the finalizing chunk (see LenientOpenAILLMClient),
                // so a missing End frame defaults to "stop" — that content-less
                // turn is exactly the one we still want to nudge.
                val finish = frames.filterIsInstance<StreamFrame.End>().lastOrNull()?.finishReason ?: "stop"
                Message.Assistant(content = cleanText, metaInfo = ResponseMetaInfo.Empty, finishReason = finish)
            }
        val assistant = reconcileReasoning(base, frames, parsedReasoning)
        appendPrompt { message(assistant) }
        return assistant
    }

    /**
     * Re-wrap any malformed tool-call arguments in a sentinel object that
     * **preserves the raw text the model emitted**, before Koog assembles the
     * message.
     *
     * Koog 1.0's [toMessageResponse] does an UNGUARDED
     * `Json.parseToJsonElement(content).jsonObject` on every
     * [StreamFrame.ToolCallComplete] (see `StreamFrameExt`). Small local models
     * routinely emit a tool call with an EMPTY / truncated / mis-escaped
     * `arguments` string (worst on a large `content`); that parse throws
     * "unexpected end of the input at path: $", which would propagate out of
     * [streamAssistant], kill the agent subgraph, and abort the HTTP stream
     * (the "Client disconnected" LM Studio logs).
     *
     * We previously defaulted such args to `{}` — but that's a known
     * anti-pattern: the model is then told "fields missing" on what looks like
     * a deliberate empty call and re-sends the same thing, burning iterations.
     * Instead we stash the original string under [MALFORMED_ARGS_KEY] so it
     * survives assembly as valid JSON, and KashAgentToolset's `decodeArgs`
     * override turns it into a precise, recoverable error that echoes what the
     * model actually sent ("your arguments were not valid JSON: …"). Only
     * frames whose content doesn't already parse to a JSON object are touched.
     */
    private fun List<StreamFrame>.markMalformedToolCallArgs(): List<StreamFrame> =
        map { frame ->
            if (frame is StreamFrame.ToolCallComplete && frame.contentJsonResult.isFailure) {
                val raw = frame.content.take(MALFORMED_ARGS_ECHO_LIMIT)
                // JsonObject.toString() emits valid, properly-escaped JSON.
                val sentinel = JsonObject(mapOf(MALFORMED_ARGS_KEY to JsonPrimitive(raw))).toString()
                frame.copy(content = sentinel)
            } else {
                frame
            }
        }

    /**
     * One-shot split of a full reply into (think-stripped answer, reasoning).
     * Mirrors the streaming [ThinkTagSplitter] but over the assembled text, so
     * history is built independently of how the deltas were chunked (and works
     * for TextComplete-only backends that never streamed deltas).
     */
    private fun splitThinkTags(raw: String): Pair<String, String> {
        val splitter = ThinkTagSplitter()
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        (splitter.push(raw) + splitter.flush()).forEach { seg ->
            when (seg) {
                is ThinkTagSplitter.Seg.Answer -> answer.append(seg.text)
                is ThinkTagSplitter.Seg.Reasoning -> reasoning.append(seg.text)
            }
        }
        return answer.toString() to reasoning.toString()
    }

    /**
     * Make the assistant message's reasoning match policy + reality before it
     * lands in history.
     *
     * With `--no-reasoning-history` ([AgentConfig.appendReasoning] false) we
     * strip any [MessagePart.Reasoning] — for hosted APIs that reject reasoning
     * echoed back. (DeepSeek is the opposite: it *requires* it in thinking
     * mode, so leave the default on there.)
     *
     * Otherwise we keep reasoning so the resent prefix matches what the server
     * generated, which is what lets reasoning models hit their prefix cache.
     * Reasoning is sourced, in priority order: [parsedReasoning] (pulled from
     * the content's `<think>` spans on the completions path), then
     * `ReasoningComplete`/`ReasoningDelta` frames (the Responses path) —
     * [toMessageResponse] only builds the part from `ReasoningComplete`, and
     * LM Studio-style backends drop the finalizing chunk, so we synthesize it
     * from the deltas when the assembled message has none.
     */
    private fun reconcileReasoning(
        msg: Message.Assistant,
        frames: List<StreamFrame>,
        parsedReasoning: String?,
    ): Message.Assistant {
        if (!config.appendReasoning) {
            val stripped = msg.parts.filterNot { it is MessagePart.Reasoning }
            return if (stripped.size == msg.parts.size) msg else msg.copy(parts = stripped)
        }
        if (msg.parts.any { it is MessagePart.Reasoning }) return msg
        val reasoning =
            parsedReasoning?.takeIf { it.isNotBlank() }
                ?: frames
                    .filterIsInstance<StreamFrame.ReasoningComplete>()
                    .joinToString("") { it.content.joinToString("") }
                    .ifEmpty {
                        frames
                            .filterIsInstance<StreamFrame.ReasoningDelta>()
                            .mapNotNull { it.text }
                            .joinToString("")
                    }
        if (reasoning.isEmpty()) return msg
        return msg.copy(parts = listOf(MessagePart.Reasoning(reasoning)) + msg.parts)
    }

    /**
     * Spinner ticker — overwrites its own line; cancelled when real output
     * starts (or the turn ends). [label] is re-read every tick so the caller
     * can switch the activity text live (e.g. "thinking…" → "calling X…").
     */
    private suspend fun spinnerLoop(label: () -> String = { "thinking…" }) {
        out.writeUtf8(HIDE_CURSOR)
        val started =
            kotlin.time.TimeSource.Monotonic
                .markNow()
        var i = 0
        while (true) {
            val frame = agentGlyphs.spinnerFrames[i % agentGlyphs.spinnerFrames.size]
            val elapsed = started.elapsedNow().inWholeSeconds.toInt()
            val tag = if (elapsed > 0) " ${C_DIM}(${elapsed}s)${C_RESET}" else ""
            val activity = label().ifEmpty { "thinking…" }
            out.writeUtf8("\r${C_CYAN}$frame${C_RESET} ${C_DIM}$activity${C_RESET}$tag$ERASE_TO_EOL")
            i++
            kotlinx.coroutines.delay(SPINNER_TICK_MS)
        }
    }

    private fun toolArgSummary(argsRaw: String): String {
        val args =
            try {
                Json.parseToJsonElement(argsRaw).jsonObject
            } catch (
                _: Throwable,
            ) {
                return argsRaw.replace('\n', ' ').take(80)
            }
        for (k in listOf("command", "path", "file")) {
            // Use the unescaped primitive value, not toString(): the latter
            // re-serializes a JSON string with quotes/escapes, so a command
            // like `echo "hi"` rendered as `echo \"hi\"` (and truncated
            // mid-escape). content gives the real text.
            val v = (args[k] as? JsonPrimitive)?.content
            if (!v.isNullOrEmpty()) return v.replace('\n', ' ')
        }
        return args.toString().replace('\n', ' ').take(80)
    }

    private fun describeError(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.toList()
        val text = chain.joinToString(" · ") { it.message ?: it::class.simpleName.orEmpty() }
        if ("400" in text || "Bad Request" in text) {
            return "$text\n${C_DIM}hint: HTTP 400 usually means the loaded model rejected the request.\n" +
                "  · if the model doesn't support tool/function calling, retry with --no-tools\n" +
                "  · double-check the model id matches what's loaded in LM Studio/Ollama${C_RESET}"
        }
        if ("Connection refused" in text || "ConnectException" in text) {
            return "$text\n${C_DIM}hint: is your LLM server running at ${config.baseUrl}?${C_RESET}"
        }
        return text
    }

    /**
     * Inventory of in-shell commands the model can invoke through
     * shell_exec — pinned at session start so the model doesn't probe.
     */
    private val shellInventory: String by lazy {
        val reg = ctx.process.machine.registry
        val builtins = reg.namesOfKind(CommandKind.BUILTIN).sorted()
        val tools = reg.namesOfKind(CommandKind.TOOL).sorted()
        buildString {
            append("kash shell inventory (callable via shell_exec):\n")
            if (builtins.isNotEmpty()) append("  builtins: ").append(builtins.joinToString(" ")).append('\n')
            if (tools.isNotEmpty()) append("  tools: ").append(tools.joinToString(" ")).append('\n')
            append(
                "\nThis is the COMPLETE list — don't probe with `compgen -c` / `which` / " +
                    "`ls /bin`. If a tool isn't above, it isn't available; reach for the " +
                    "closest substitute or tell the user it's missing.",
            )
        }
    }

    private suspend fun resolveModel(): String? {
        val explicit = config.modelId?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit

        out.writeUtf8("${C_DIM}fetching available models from ${config.baseUrl}…${C_RESET}\n")
        val models =
            try {
                fetchModelIds()
            } catch (t: Throwable) {
                out.writeUtf8("\n${C_RED}error: couldn't fetch models: ${describeError(t)}${C_RESET}\n")
                out.writeUtf8(
                    "${C_DIM}hint: pass a model id on the command line, e.g.\n" +
                        "  agent ${config.baseUrl} <model-id>${C_RESET}\n",
                )
                return null
            }
        return when (models.size) {
            0 -> {
                out.writeUtf8("${C_RED}error: server returned no models${C_RESET}\n")
                null
            }

            1 -> {
                out.writeUtf8("${C_DIM}using ${models.first()} (only one available)${C_RESET}\n")
                models.first()
            }

            else -> {
                pickModelInteractive(models)
            }
        }
    }

    private suspend fun pickModelInteractive(models: List<String>): String? {
        val pageSize = (term.size().rows - 4).coerceIn(5, 20).coerceAtMost(models.size)
        var selected = 0
        var pageTop = 0
        term.enterRawMode()
        out.writeUtf8(HIDE_CURSOR)
        try {
            out.writeUtf8("${C_DIM}select a model · ${PICKER_HINT}${C_RESET}\n")
            repeat(pageSize) { out.writeUtf8("\n") }
            while (true) {
                out.writeUtf8("$ESC[${pageSize}A")
                for (row in 0 until pageSize) {
                    val idx = pageTop + row
                    out.writeUtf8("\r$ESC[2K")
                    if (idx >= models.size) {
                        out.writeUtf8("\n")
                        continue
                    }
                    val marker = if (idx == selected) "${C_GREEN}${agentGlyphs.pickerArrow} ${C_BOLD}" else "  "
                    val tail = if (idx == selected) "$marker${models[idx]}${C_RESET}" else "  ${models[idx]}"
                    out.writeUtf8("$tail\n")
                }
                val key =
                    try {
                        term.readKey()
                    } catch (_: Throwable) {
                        return null
                    }
                when (key) {
                    Key.Named.UP -> {
                        if (selected > 0) selected -= 1
                        if (selected < pageTop) pageTop = selected
                    }

                    Key.Named.DOWN -> {
                        if (selected < models.size - 1) selected += 1
                        if (selected >= pageTop + pageSize) pageTop = selected - pageSize + 1
                    }

                    Key.Named.HOME -> {
                        selected = 0
                        pageTop = 0
                    }

                    Key.Named.END -> {
                        selected = models.size - 1
                        pageTop = (models.size - pageSize).coerceAtLeast(0)
                    }

                    Key.Named.PGUP -> {
                        selected = (selected - pageSize).coerceAtLeast(0)
                        pageTop = (pageTop - pageSize).coerceAtLeast(0)
                    }

                    Key.Named.PGDN -> {
                        selected = (selected + pageSize).coerceAtMost(models.size - 1)
                        pageTop = (pageTop + pageSize).coerceAtMost((models.size - pageSize).coerceAtLeast(0))
                    }

                    Key.Named.ENTER -> {
                        out.writeUtf8("$ESC[${pageSize + 1}A")
                        repeat(pageSize + 1) { out.writeUtf8("\r$ESC[2K\n") }
                        out.writeUtf8("$ESC[${pageSize + 1}A")
                        out.writeUtf8("${C_DIM}${agentGlyphs.pickerCheck} selected: ${C_RESET}${models[selected]}\n\n")
                        return models[selected]
                    }

                    Key.Named.ESC -> {
                        return null
                    }

                    is Key.Ctrl -> {
                        if (key.letter == 'C' || key.letter == 'D') return null
                    }

                    else -> {
                        Unit
                    }
                }
            }
        } finally {
            try {
                out.writeUtf8(SHOW_CURSOR)
            } catch (_: Throwable) {
            }
            try {
                term.exitRawMode()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun fetchModelIds(): List<String> {
        val base = config.baseUrl.trimEnd('/')
        val url = "$base/v1/models"
        val headers =
            buildList {
                add("Accept" to "application/json")
                if (config.apiKey.isNotBlank()) add("Authorization" to "Bearer ${config.apiKey}")
            }
        val policy = ctx.process.machine.networkPolicy
        val client = KashKtorClient(policy)
        return try {
            client.execute(HttpRequest(url = url, method = "GET", headers = headers)) { resp ->
                if (resp.status !in 200..299) {
                    error("HTTP ${resp.status} ${resp.statusText} fetching $url")
                }
                val body = resp.readAllBytes().decodeToString()
                val root = Json.parseToJsonElement(body).jsonObject
                val data = (root["data"] ?: error("no 'data' field in $url response")).jsonArray
                val all =
                    data
                        .mapNotNull { entry ->
                            (entry as? JsonObject)?.get("id")?.jsonPrimitive?.content
                        }.distinct()
                val chatModels =
                    all.filter { id ->
                        val lower = id.lowercase()
                        !lower.contains("embed") &&
                            !lower.contains("rerank") &&
                            !lower.contains("whisper") &&
                            !lower.contains("tts")
                    }
                chatModels.takeIf { it.isNotEmpty() } ?: all
            }
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun printBanner() {
        out.writeUtf8("${C_DIM}kash agent · ${model.id} @ ${config.baseUrl}${C_RESET}\n")
        if (config.toolsEnabled) {
            out.writeUtf8("${C_DIM}tools: shell_exec read_file write_file list_dir${C_RESET}\n")
        } else {
            out.writeUtf8("${C_DIM}tools: disabled (--no-tools)${C_RESET}\n")
        }
        out.writeUtf8("${C_DIM}/help for commands · /quit to exit${C_RESET}\n\n")
    }

    private fun truncateInline(
        s: String,
        max: Int,
    ): String = if (s.length <= max) s else s.take(max - 1) + "…"

    private companion object {
        /** readInput → nodeFinish marker. */
        const val QUIT_SENTINEL: String = " __kash_agent_quit__"

        /** Coarse message→token proxy for the compaction trigger. */
        const val APPROX_TOKENS_PER_MESSAGE: Int = 2000

        /**
         * Hidden (never rendered) nudge appended when the model ends a
         * post-tool turn with nothing for the user, so it summarizes instead
         * of leaving them on raw tool output.
         */
        const val SUMMARIZE_NUDGE: String =
            "Do not leave the user on a tool call with no reply. Briefly summarize what you " +
                "found or why you stopped — or continue the task."

        /**
         * Most empty post-tool replies we'll nudge in one user turn. Generous
         * so legitimate multi-step work (the model stopping after each step) is
         * always nudged; bounds a model stuck emitting empties so it isn't
         * nudged indefinitely.
         */
        const val MAX_NUDGES_PER_TURN: Int = 16

        /**
         * How many malformed-argument tool calls we'll re-ask (with a precise
         * error) in one user turn before giving up and ending the turn. Small
         * enough that a stuck model doesn't burn the whole iteration budget,
         * large enough that a capable model gets a couple of shots to fix its
         * JSON.
         */
        const val MAX_MALFORMED_TOOL_CALLS_PER_TURN: Int = 3

        const val ESC = "\u001B"
        const val C_RESET = "$ESC[0m"
        const val C_BOLD = "$ESC[1m"
        const val C_DIM = "$ESC[2m"
        const val C_GREEN = "$ESC[32m"
        const val C_CYAN = "$ESC[36m"
        const val C_RED = "$ESC[31m"
        const val PICKER_HINT = "↑↓ navigate · enter select · esc cancel"
        const val HIDE_CURSOR = "$ESC[?25l"
        const val SHOW_CURSOR = "$ESC[?25h"
        const val ERASE_TO_EOL = "$ESC[K"
        const val SPINNER_TICK_MS: Long = 80L
    }
}
