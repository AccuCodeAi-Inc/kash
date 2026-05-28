package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.api.AttachmentSink
import com.accucodeai.kash.api.BasicLineEditor
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.LineEditorResult
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.tools.ai.agent.openai.ChatClient
import com.accucodeai.kash.tools.ai.agent.openai.ChatMessage
import com.accucodeai.kash.tools.ai.agent.openai.ChatStreamEvent
import com.accucodeai.kash.tools.ai.agent.openai.OpenAIChatClient
import com.accucodeai.kash.tools.ai.agent.openai.ToolCall
import com.accucodeai.kash.tools.ai.agent.openai.ToolCallFunction
import com.accucodeai.kash.tools.ai.agent.openai.userMessage
import com.accucodeai.kash.tools.ai.agent.tools.PendingImage
import com.accucodeai.kash.tools.ai.agent.tools.ToolRegistry
import com.accucodeai.kash.tools.ai.agent.tools.defaultToolRegistry
import com.accucodeai.kash.tools.ai.agent.tools.toContentPart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Manual chat loop. One [run] = one interactive session: read user input,
 * stream the assistant reply, execute any tool calls the model emitted,
 * loop. No graph DSL, no strategy nodes — just procedural control flow
 * that's easy to read and step through in a debugger.
 *
 * Rendering is inlined: assistant text streams live, reasoning streams
 * dimmed, tool calls show a `• tool(args)` indicator followed by their
 * own output (the `write_file` diff) and a `↳ result` line. All
 * append-only — no terminal-clear / scrollback rewriting.
 *
 * Compaction: when the history grows past the budget, a side
 * non-streaming LLM call summarizes everything and the history is replaced
 * with the system prompt, a synthetic system "previously: <tldr>" message,
 * and the most recent user/assistant pair.
 */
internal class AgentSession(
    private val term: TerminalControl,
    private val ctx: CommandContext,
    private val config: AgentConfig,
    /**
     * Test seam: inject a [ChatClient] (the production [OpenAIChatClient]
     * or a fake) instead of the default which talks to the network. Null
     * in production — we build a real client against [config.baseUrl].
     */
    private val clientOverride: ChatClient? = null,
) : AttachmentSink {
    private val out: SuspendSink get() = ctx.stdout
    private val shell = KashAgentShell(ctx)
    private val editor = BasicLineEditor(term)

    /**
     * Images `read_file` recognized this turn, awaiting injection as a vision
     * content part on the next user message — a tool result is text-only on
     * the OpenAI wire format, so the bytes ride back via the following user
     * message instead.
     */
    private val pendingImages = mutableListOf<PendingImage>()

    private val toolRegistry: ToolRegistry =
        defaultToolRegistry(ctx, shell, onImage = { pendingImages += it })

    /** Drag-and-drop registry + multimodal user-message builder. */
    private val attachments = AgentAttachments(ctx.fs)

    /**
     * Conversation history seeded with [systemPrompt] + shell inventory in
     * [run]; appended to during the loop. The session's source of truth for
     * what gets sent on every request.
     */
    private val history: MutableList<ChatMessage> = mutableListOf()

    /**
     * How many empty post-tool replies we've nudged this user turn. Capped
     * at [MAX_NUDGES_PER_TURN]: a model doing genuine multi-step work (a
     * tool call between each empty reply) gets nudged every time it stops,
     * but a model stuck returning empty is eventually let go instead of
     * nudged forever.
     */
    private var nudgesThisTurn = 0

    private val client: ChatClient =
        clientOverride ?: OpenAIChatClient(baseUrl = config.baseUrl, apiKey = config.apiKey)

    private lateinit var modelId: String

    override suspend fun add(
        path: String,
        mimeType: String,
        sizeBytes: Long,
        fileName: String,
    ): Int {
        val index = attachments.add(path, mimeType, sizeBytes, fileName)
        // Surface the drop banner above the prompt via a synthetic key the
        // line editor renders.
        for (n in attachments.drainNotices()) {
            val banner =
                "${C_DIM}${agentGlyphs.toolBullet} attached ${n.fileName} " +
                    "(${AgentAttachments.humanBytes(n.sizeBytes)}) as [attachment ${n.index}]${C_RESET}"
            term.pushKey(Key.PrintAbove(banner))
        }
        return index
    }

    suspend fun run(): Int {
        val chosen =
            resolveModel() ?: run {
                ctx.stderr.writeUtf8("agent: no model chosen\n")
                return 1
            }
        modelId = chosen
        printBanner()

        history += ChatMessage.System(content = config.systemPrompt + "\n\n" + shellInventory)

        val previousSink = ctx.process.attachmentSink
        ctx.process.attachmentSink = this
        try {
            mainLoop()
            return 0
        } catch (t: Throwable) {
            out.writeUtf8("\n${C_RED}error: ${describeError(t)}${C_RESET}\n")
            return 1
        } finally {
            ctx.process.attachmentSink = previousSink
            try {
                if (clientOverride == null) client.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * The REPL: read a user line (or slash command), append it to history,
     * run [runAssistantTurn] until it stops calling tools, repeat. Exits
     * cleanly on EOF (ctrl-D on an empty line) or `/quit`.
     */
    private suspend fun mainLoop() {
        while (true) {
            when (val read = readNextLine()) {
                ReadResult.Quit -> {
                    return
                }

                is ReadResult.Submission -> {
                    nudgesThisTurn = 0
                    // Drain queued env exports at this quiescent point.
                    for (b in attachments.drainPendingExports()) shell.exportVar(b.name, b.path)
                    val parts = attachments.buildUserParts(read.text)
                    val imageContentParts =
                        parts.images +
                            pendingImages.map { it.toContentPart() }.also {
                                pendingImages.clear()
                            }
                    history += userMessage(parts.text, imageContentParts)
                    runAssistantTurn()
                }
            }
        }
    }

    /**
     * Drive one user→assistant exchange. Streams the model's reply, executes
     * any tool calls, loops back for the model's follow-up reply, all the
     * way down to a final assistant text message with no tool calls.
     *
     * Bounded by [AgentConfig.maxIterations] (the safety backstop) and by
     * [MAX_NUDGES_PER_TURN] (the post-tool-blank nudge cap).
     */
    private suspend fun runAssistantTurn() {
        var rounds = 0
        while (rounds < config.maxIterations) {
            rounds++
            // Compact history if it's grown past the budget BEFORE the
            // request, so the side-LLM call to summarize runs on an
            // assistant-result message it can sensibly TLDR.
            if (isHistoryTooBig()) compactHistory()

            val stream = streamAssistantTurn()

            if (stream.toolCalls.isNotEmpty()) {
                // Append the assistant message (text + tool_calls), execute
                // each call sequentially, append a `tool` message per
                // result, drain queued images as a synthetic user message
                // (vision parts must ride a user turn, not a tool result),
                // and loop for the model's follow-up.
                history += assistantOf(stream)
                executeAndAppendToolResults(stream.toolCalls)
                drainPendingImagesIntoHistory()
                continue
            }

            // No tool calls: either a real reply or a blank one we want to
            // nudge. isBlankReply keys off finishReason=="stop" + empty
            // text, matching the documented Qwen/LM-Studio empty-turn shape.
            if (stream.isBlankReply() && nudgesThisTurn < MAX_NUDGES_PER_TURN) {
                // Don't commit the blank to history; nudge and re-request.
                nudgesThisTurn++
                history += ChatMessage.User(content = JsonPrimitive(SUMMARIZE_NUDGE))
                continue
            }

            // Real text reply — commit it and end the turn.
            history += assistantOf(stream)
            return
        }
    }

    /**
     * Stream one assistant response, rendering deltas live and collecting
     * tool calls into the returned [StreamedTurn]. The spinner shows
     * `thinking…` until the first text/reasoning/tool-name arrives, then
     * `calling <tool>…` once a tool call announces its name.
     *
     * Reasoning is split out of `<think>…</think>` spans inside content via
     * [ThinkTagSplitter] when [AgentConfig.parseThinkTags] is on; otherwise
     * content streams verbatim.
     */
    private suspend fun streamAssistantTurn(): StreamedTurn {
        val showThinking = config.showThinking
        val thinkSplitter = if (config.parseThinkTags) ThinkTagSplitter() else null

        val visibleText = StringBuilder()
        val reasoning = StringBuilder()
        val toolCallsAccum = linkedMapOf<Int, ToolCallBuild>()
        var finishReason = "stop"

        // Held-back whitespace at the start/end so leading/trailing blank
        // lines the model emits don't pollute the visible reply. Flushed
        // when real content follows.
        var heldWs = ""
        var renderedText = false
        var textNewlinePending = false
        var reasoningOpen = false
        var pendingToolName: String? = null
        var userInterrupted = false

        // Predicate shared by pre-/post-stream drains: keep keys that
        // carry the user's actual intent, drop pure type-ahead noise.
        //   - Paste / PrintAbove: out-of-band events for the next
        //     readLine (drag-drop, OSC paste, host hook).
        //   - Ctrl-C / ESC: interrupt requests — survive so the drain
        //     coroutine inside the stream window can act on them.
        //   - Ctrl-D: EOF/quit — survives so a user who mashed it
        //     right after Enter still gets out at the next prompt.
        // Everything else (regular char typing, arrows, BS, TAB, ...)
        // is "I typed during the agent's turn"; drop it so the next
        // readLine starts fresh.
        fun keepKey(k: Key): Boolean =
            k is Key.Paste ||
                k is Key.PrintAbove ||
                (k is Key.Ctrl && (k.letter == 'C' || k.letter == 'D')) ||
                (k is Key.Named && k == Key.Named.ESC)

        // Pre-stream drain: discard the type-ahead noise, preserve
        // interrupts and out-of-band events. Skipped entirely when
        // strict turn-taking is off — under that mode (see
        // [AgentConfig.strictTurnTaking]) the queue at stream-start
        // legitimately holds the next turn's input.
        if (config.strictTurnTaking) {
            term.drainKeys(keep = ::keepKey)
        }

        // Keys the in-stream drain coroutine pulls out of the channel
        // but wants to preserve for the next readLine (Ctrl-D, Paste,
        // PrintAbove). We can't `term.pushKey(k)` them back inside the
        // drain loop because the drain is also the channel's reader —
        // it would immediately re-receive its own push and spin forever.
        // Stash them in this list and re-queue once the coroutineScope
        // returns (drainJob is fully settled by then).
        val keptDuringStream = mutableListOf<Key>()

        try {
            kotlinx.coroutines.coroutineScope {
                val streamScope = this
                var spinnerJob: kotlinx.coroutines.Job? = null

                // Strict turn-taking. While the agent is producing, no
                // readLine consumer is active, so anything the user types
                // would otherwise queue up and auto-replay on the next
                // prompt (the classic UNIX type-ahead bug, much worse for
                // a chat-with-AI flow where a stray Enter submits a half-
                // typed message). Spawn a sibling coroutine that consumes
                // every key during the stream window: Ctrl-C / ESC cancel
                // the stream scope (we catch the resulting cancellation
                // below); Key.Paste / Key.PrintAbove / Ctrl-D land in
                // [keptDuringStream] for re-queue afterwards; everything
                // else is silently dropped. Skipped when strict turn-
                // taking is off; under that mode user input typed during
                // a stream rides through to the next readLine.
                val drainJob: kotlinx.coroutines.Job? =
                    if (!config.strictTurnTaking) {
                        null
                    } else {
                        launch {
                            while (true) {
                                val k = term.readKey()
                                when {
                                    k is Key.Ctrl && k.letter == 'C' -> {
                                        userInterrupted = true
                                        streamScope.cancel(
                                            kotlinx.coroutines.CancellationException(
                                                "user interrupt (Ctrl-C)",
                                            ),
                                        )
                                        break
                                    }

                                    k is Key.Named && k == Key.Named.ESC -> {
                                        userInterrupted = true
                                        streamScope.cancel(
                                            kotlinx.coroutines.CancellationException(
                                                "user interrupt (ESC)",
                                            ),
                                        )
                                        break
                                    }

                                    keepKey(k) -> {
                                        // Ctrl-D / Paste / PrintAbove —
                                        // legitimate next-turn signals.
                                        // Stash for post-stream re-queue.
                                        keptDuringStream.add(k)
                                    }

                                    else -> { /* swallow type-ahead */ }
                                }
                            }
                        }
                    }

                fun launchSpinner() {
                    if (spinnerJob == null) {
                        spinnerJob =
                            launch {
                                spinnerLoop { pendingToolName?.let { "calling $it…" } ?: "thinking…" }
                            }
                    }
                }

                suspend fun stopSpinner() {
                    val j = spinnerJob ?: return
                    spinnerJob = null
                    j.cancel()
                    j.join()
                    out.writeUtf8("\r$ERASE_TO_EOL$SHOW_CURSOR")
                }

                suspend fun closeReasoning() {
                    if (reasoningOpen) {
                        out.writeUtf8("$C_RESET\n")
                        reasoningOpen = false
                    }
                }

                suspend fun ensureToolPhaseSpinner() {
                    if (spinnerJob != null) return
                    if (textNewlinePending) {
                        out.writeUtf8("\n")
                        textNewlinePending = false
                    }
                    launchSpinner()
                }

                suspend fun renderAnswer(text: String) {
                    visibleText.append(text)
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

                suspend fun renderReasoning(text: String) {
                    if (!showThinking || text.isEmpty()) {
                        reasoning.append(text)
                        return
                    }
                    reasoning.append(text)
                    if (!reasoningOpen) {
                        stopSpinner()
                        out.writeUtf8(C_DIM)
                        reasoningOpen = true
                    }
                    out.writeUtf8(text)
                }

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
                    client
                        .stream(
                            model = modelId,
                            messages = history.toList(),
                            temperature = config.temperature,
                            tools = if (config.toolsEnabled) toolRegistry.descriptors() else null,
                        ).collect { event ->
                            when (event) {
                                is ChatStreamEvent.TextDelta -> {
                                    renderContent(event.text)
                                }

                                is ChatStreamEvent.ReasoningDelta -> {
                                    renderReasoning(event.text)
                                }

                                is ChatStreamEvent.ToolCallStarted -> {
                                    pendingToolName = event.name
                                    ensureToolPhaseSpinner()
                                }

                                is ChatStreamEvent.ToolCallComplete -> {
                                    toolCallsAccum[event.index] =
                                        ToolCallBuild(event.index, event.id, event.name, event.arguments)
                                }

                                is ChatStreamEvent.End -> {
                                    finishReason = event.finishReason
                                }
                            }
                        }
                } finally {
                    thinkSplitter?.flush()?.forEach { seg ->
                        when (seg) {
                            is ThinkTagSplitter.Seg.Answer -> renderAnswer(seg.text)
                            is ThinkTagSplitter.Seg.Reasoning -> renderReasoning(seg.text)
                        }
                    }
                    closeReasoning()
                    stopSpinner()
                    // Stream loop done (success, error, or cancellation) —
                    // tear down the drain coroutine. `cancel()` interrupts
                    // its suspended `term.readKey()`; the launched coroutine
                    // exits on the next cancellation check.
                    drainJob?.cancel()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Distinguish a user-initiated stream interrupt (Ctrl-C / ESC
            // — we set `userInterrupted` and cancelled `streamScope` from
            // inside the drain coroutine) from cancellation propagating
            // from the parent (process shutdown, parent job cancel). The
            // first case is recoverable: return partial output, let the
            // outer agent loop reprompt. The second must be rethrown so
            // shutdown can complete.
            if (!userInterrupted) throw e
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
            // Land the cursor on a fresh line so the next prompt isn't
            // glued to whatever partial token the model was mid-emit on.
            out.writeUtf8("\n")
        }

        if (textNewlinePending) out.writeUtf8("\n")

        // Re-queue the out-of-band events the drain coroutine caught
        // during the stream. `coroutineScope { ... }` returning above
        // guarantees `drainJob` is fully settled, so [keptDuringStream]
        // is no longer being mutated — safe to iterate.
        for (k in keptDuringStream) term.pushKey(k)

        // Post-stream drain: same predicate as pre-stream. Stale
        // Ctrl-C/ESC riding through here is harmless — the next
        // readLine treats Ctrl-C as Interrupted (which it discards
        // anyway in the agent loop). Ctrl-D / Paste / PrintAbove
        // need to survive so the user's "quit when convenient" or
        // "queued attachment from drag-drop" reaches the next turn.
        // Skipped when strict turn-taking is off, same as pre-drain.
        if (config.strictTurnTaking) {
            term.drainKeys(keep = ::keepKey)
        }

        return StreamedTurn(
            text = visibleText.toString().trim(),
            reasoning = reasoning.toString().ifEmpty { null },
            toolCalls = toolCallsAccum.values.toList().sortedBy { it.index },
            finishReason = if (userInterrupted) "user_interrupt" else finishReason,
        )
    }

    /**
     * Run each tool call in order, append its `tool` result message to
     * history, render the indicator + result line. Sequential — tool
     * results are rendered between this tool's indicator and the next
     * tool's indicator, an append-only stream the user can read.
     */
    private suspend fun executeAndAppendToolResults(calls: List<ToolCallBuild>) {
        for (call in calls) {
            // Indicator line.
            val argsSummary = toolArgSummary(call.arguments)
            out.writeUtf8(
                "${C_DIM}${agentGlyphs.toolBullet}$C_RESET ${C_CYAN}${call.name}$C_RESET" +
                    "(${truncateInline(argsSummary, 80)})\n",
            )
            val result =
                runCatching { invokeTool(call) }
                    .fold(
                        onSuccess = { it },
                        onFailure = { e -> "error: ${e.message ?: e::class.simpleName}" },
                    )
            val firstLine =
                result
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.let { truncateInline(it, 100) }
                    .orEmpty()
            out.writeUtf8("  ${C_DIM}${agentGlyphs.toolResultArrow}$C_RESET $firstLine\n")
            history += ChatMessage.Tool(content = result, toolCallId = call.id)
        }
    }

    private suspend fun invokeTool(call: ToolCallBuild): String {
        val tool =
            toolRegistry.get(call.name)
                ?: return "error: unknown tool `${call.name}`. Available: ${toolRegistry.all.joinToString { it.name }}"
        val argsObj: JsonObject =
            if (call.arguments.isBlank()) {
                throw IllegalArgumentException(
                    "${call.name}: arguments were empty. Send a JSON object matching the tool's schema.",
                )
            } else {
                runCatching {
                    Json.parseToJsonElement(call.arguments).jsonObject
                }.getOrElse {
                    throw IllegalArgumentException(
                        "${call.name}: arguments were not valid JSON. You sent: " +
                            "`${call.arguments.take(300)}`. Resend as one valid JSON object.",
                    )
                }
            }
        return tool.execute(argsObj)
    }

    /**
     * Append queued image attachments as a follow-up user message — vision
     * content parts must ride a user turn, not a tool-result message
     * (`tool` messages are text-only in the OpenAI wire format).
     */
    private fun drainPendingImagesIntoHistory() {
        if (pendingImages.isEmpty()) return
        val parts = pendingImages.map { it.toContentPart() }
        pendingImages.clear()
        history += userMessage(text = "", images = parts)
    }

    /**
     * Convert an in-progress [StreamedTurn] to the [ChatMessage.Assistant] we
     * resend. Two wire-compliance coercions happen here:
     *  - **Empty `arguments` → `"{}"`** on the resend shape. OpenAI's spec
     *    requires `tool_calls[i].function.arguments` to be a valid JSON-
     *    encoded string; an empty string isn't one and strict servers reject
     *    the next request. We've already executed the tool with the actual
     *    (empty) emission and surfaced a precise error in the `tool` result,
     *    so coercing the wire-only echo to `{}` doesn't hide anything from
     *    the model — and keeps the request shape conformant.
     *  - **Missing `id` → synthetic `tc-<index>-<turn>`.** OpenAI rejects
     *    empty tool-call IDs and the `tool` follow-up must reference the
     *    same id via `tool_call_id`. Models occasionally drop the id field;
     *    synthesize one so the linkage stays valid.
     */
    private fun assistantOf(turn: StreamedTurn): ChatMessage.Assistant {
        val reasoningToSend = if (config.appendReasoning) turn.reasoning else null
        val toolCalls =
            if (turn.toolCalls.isEmpty()) {
                null
            } else {
                turn.toolCalls.map { call ->
                    ToolCall(
                        id = call.id,
                        function =
                            ToolCallFunction(
                                name = call.name,
                                arguments = call.arguments.ifBlank { "{}" },
                            ),
                    )
                }
            }
        return ChatMessage.Assistant(
            content = turn.text.ifBlank { null },
            reasoningContent = reasoningToSend,
            toolCalls = toolCalls,
        )
    }

    private fun StreamedTurn.isBlankReply(): Boolean = toolCalls.isEmpty() && finishReason == "stop" && text.isBlank()

    /**
     * Token-budget proxy: message count past a coarse threshold. We don't
     * (yet) thread token-counting back from the server — message count is a
     * reasonable enough proxy since each tool round adds two messages and
     * a model that's not making progress will balloon both.
     */
    private fun isHistoryTooBig(): Boolean = history.size > (config.contextBudgetTokens / APPROX_TOKENS_PER_MESSAGE)

    /**
     * Side LLM call to summarize the conversation into a TLDR, then replace
     * history with the system prompt + a synthetic system "previously"
     * message + the most recent user turn (so the model doesn't lose track
     * of what was just asked).
     *
     * Best-effort: if the summarization call fails, we keep going on the
     * uncompacted history — the next request might still squeeze in.
     */
    private suspend fun compactHistory() {
        out.writeUtf8(
            "${C_DIM}${agentGlyphs.toolBullet} compacting history to stay within the context budget…$C_RESET\n",
        )
        // Lift out the system prompt and the most recent user turn to keep
        // them after compaction; summarize everything in between.
        val systemMsg = history.firstOrNull { it is ChatMessage.System }
        val lastUserIndex = history.indexOfLast { it is ChatMessage.User }
        val toSummarize = if (lastUserIndex > 0) history.subList(0, lastUserIndex).toList() else history.toList()
        val summaryReq =
            toSummarize +
                ChatMessage.User(
                    content =
                        JsonPrimitive(
                            "Summarize the conversation so far in a concise TLDR (a few short " +
                                "paragraphs). Preserve key facts the assistant should remember " +
                                "(file paths edited, decisions made, what the user is trying to do) " +
                                "but drop verbose tool output. Reply with the TLDR only — no preamble.",
                        ),
                )
        val tldr =
            try {
                val resp =
                    client.complete(
                        model = modelId,
                        messages = summaryReq,
                        temperature = 0.0,
                        tools = null,
                    )
                resp.choices
                    .firstOrNull()
                    ?.message
                    ?.content
                    ?.trim()
                    .orEmpty()
            } catch (_: Throwable) {
                ""
            }
        if (tldr.isEmpty()) return
        val recent = if (lastUserIndex >= 0) history.subList(lastUserIndex, history.size).toList() else emptyList()
        history.clear()
        if (systemMsg != null) history += systemMsg
        history += ChatMessage.System(content = "Previously in this conversation:\n$tldr")
        history += recent
    }

    private suspend fun readNextLine(): ReadResult {
        while (true) {
            val read =
                editor.readLine(
                    prompt = "$C_BOLD$C_GREEN${agentGlyphs.userPrompt}$C_RESET ",
                    continuationPrompt = "  ",
                    isComplete = { true },
                )
            when (read) {
                is LineEditorResult.Eof -> {
                    out.writeUtf8("\n")
                    return ReadResult.Quit
                }

                LineEditorResult.Interrupted -> {
                    out.writeUtf8("\n")
                    continue
                }

                is LineEditorResult.Line -> {
                    val text = read.text.trim()
                    if (text.isEmpty()) continue
                    if (text.startsWith("/")) {
                        if (handleSlashCommand(text)) return ReadResult.Quit
                        continue
                    }
                    editor.addHistory(text)
                    return ReadResult.Submission(text)
                }
            }
        }
    }

    /** Handle a `/`-prefixed slash command. Returns true if the session should quit. */
    private suspend fun handleSlashCommand(text: String): Boolean {
        when (text) {
            "/quit", "/exit" -> {
                out.writeUtf8("${C_DIM}bye$C_RESET\n")
                return true
            }

            "/help" -> {
                printHelp()
            }

            "/clear", "/reset" -> {
                val system = history.filterIsInstance<ChatMessage.System>()
                history.clear()
                history.addAll(system)
                out.writeUtf8("${C_DIM}conversation history cleared$C_RESET\n")
            }

            "/rewind" -> {
                val i = history.indexOfLast { it is ChatMessage.User }
                if (i > 0) {
                    while (history.size > i) history.removeAt(history.size - 1)
                }
                out.writeUtf8("${C_DIM}rewound the last exchange$C_RESET\n")
            }

            "/model" -> {
                val id = forceModelPick()
                if (id != null) {
                    modelId = id
                    out.writeUtf8("${C_DIM}switched to $id$C_RESET\n")
                }
            }

            else -> {
                out.writeUtf8("${C_DIM}unknown command: $text · try /help$C_RESET\n")
            }
        }
        return false
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
                "  paste          newlines preserved$C_RESET\n",
        )
    }

    private suspend fun forceModelPick(): String? {
        val models =
            runCatching { client.listModels() }.getOrElse {
                out.writeUtf8("\n${C_RED}error: couldn't fetch models: ${describeError(it)}$C_RESET\n")
                return null
            }
        return when (models.size) {
            0 -> {
                out.writeUtf8("${C_RED}error: server returned no models$C_RESET\n")
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

    private suspend fun resolveModel(): String? {
        val explicit = config.modelId?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit

        out.writeUtf8("${C_DIM}fetching available models from ${config.baseUrl}…$C_RESET\n")
        val models =
            runCatching { client.listModels() }.getOrElse {
                out.writeUtf8("\n${C_RED}error: couldn't fetch models: ${describeError(it)}$C_RESET\n")
                out.writeUtf8(
                    "${C_DIM}hint: pass a model id on the command line, e.g.\n" +
                        "  agent ${config.baseUrl} <model-id>$C_RESET\n",
                )
                return null
            }
        return when (models.size) {
            0 -> {
                out.writeUtf8("${C_RED}error: server returned no models$C_RESET\n")
                null
            }

            1 -> {
                out.writeUtf8("${C_DIM}using ${models.first()} (only one available)$C_RESET\n")
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
            out.writeUtf8("${C_DIM}select a model · $PICKER_HINT$C_RESET\n")
            repeat(pageSize) { out.writeUtf8("\n") }
            while (true) {
                out.writeUtf8(Ansi.cursorUp(pageSize))
                for (row in 0 until pageSize) {
                    val idx = pageTop + row
                    out.writeUtf8("\r" + Ansi.ERASE_LINE)
                    if (idx >= models.size) {
                        out.writeUtf8("\n")
                        continue
                    }
                    val marker = if (idx == selected) "$C_GREEN${agentGlyphs.pickerArrow} $C_BOLD" else "  "
                    val tail = if (idx == selected) "$marker${models[idx]}$C_RESET" else "  ${models[idx]}"
                    out.writeUtf8("$tail\n")
                }
                val key = runCatching { term.readKey() }.getOrNull() ?: return null
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
                        out.writeUtf8(Ansi.cursorUp(pageSize + 1))
                        repeat(pageSize + 1) { out.writeUtf8("\r" + Ansi.ERASE_LINE + "\n") }
                        out.writeUtf8(Ansi.cursorUp(pageSize + 1))
                        out.writeUtf8("${C_DIM}${agentGlyphs.pickerCheck} selected: $C_RESET${models[selected]}\n\n")
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

    private suspend fun spinnerLoop(label: () -> String = { "thinking…" }) {
        out.writeUtf8(HIDE_CURSOR)
        val started =
            kotlin.time.TimeSource.Monotonic
                .markNow()
        var i = 0
        while (true) {
            val frame = agentGlyphs.spinnerFrames[i % agentGlyphs.spinnerFrames.size]
            val elapsed = started.elapsedNow().inWholeSeconds.toInt()
            val tag = if (elapsed > 0) " $C_DIM(${elapsed}s)$C_RESET" else ""
            val activity = label().ifEmpty { "thinking…" }
            out.writeUtf8("\r$C_CYAN$frame$C_RESET $C_DIM$activity$C_RESET$tag$ERASE_TO_EOL")
            i++
            kotlinx.coroutines.delay(SPINNER_TICK_MS)
        }
    }

    private fun toolArgSummary(argsRaw: String): String {
        if (argsRaw.isEmpty()) return ""
        val args =
            runCatching {
                Json.parseToJsonElement(argsRaw).jsonObject
            }.getOrElse {
                return argsRaw.replace('\n', ' ').take(80)
            }
        for (k in listOf("command", "path", "file")) {
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
                "  · double-check the model id matches what's loaded in LM Studio/Ollama$C_RESET"
        }
        if ("Connection refused" in text || "ConnectException" in text) {
            return "$text\n${C_DIM}hint: is your LLM server running at ${config.baseUrl}?$C_RESET"
        }
        return text
    }

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

    private suspend fun printBanner() {
        out.writeUtf8("${C_DIM}kash agent · $modelId @ ${config.baseUrl}$C_RESET\n")
        if (config.toolsEnabled) {
            out.writeUtf8("${C_DIM}tools: ${toolRegistry.all.joinToString(" ") { it.name }}$C_RESET\n")
        } else {
            out.writeUtf8("${C_DIM}tools: disabled (--no-tools)$C_RESET\n")
        }
        out.writeUtf8("${C_DIM}/help for commands · /quit to exit$C_RESET\n\n")
    }

    private fun truncateInline(
        s: String,
        max: Int,
    ): String = if (s.length <= max) s else s.take(max - 1) + "…"

    // -------------------------------------------------------------------------
    // Helpers / value types
    // -------------------------------------------------------------------------

    private sealed interface ReadResult {
        data object Quit : ReadResult

        data class Submission(
            val text: String,
        ) : ReadResult
    }

    /** One assembled tool call from the stream. */
    internal data class ToolCallBuild(
        val index: Int,
        val id: String,
        val name: String,
        val arguments: String,
    )

    /** What a [streamAssistantTurn] returned after the stream completed. */
    internal data class StreamedTurn(
        val text: String,
        val reasoning: String?,
        val toolCalls: List<ToolCallBuild>,
        val finishReason: String,
    )

    internal companion object {
        /** Coarse message→token proxy for the compaction trigger. */
        const val APPROX_TOKENS_PER_MESSAGE: Int = 2000

        const val SUMMARIZE_NUDGE: String =
            "Do not leave the user on a tool call with no reply. Briefly summarize what you " +
                "found or why you stopped — or continue the task."

        const val MAX_NUDGES_PER_TURN: Int = 16

        // All escape strings route through [Ansi]
        // (api/.../ansi/Ansi.kt) so the source never carries raw
        // `\u001B` bytes — editors / lint tools have a habit of
        // silently stripping those on save, which is exactly how a
        // bare `const val ESC = ""` ended up empty in this file
        // before and emitted literal `[1m` to the terminal.
        const val C_RESET = Ansi.CSI + "0m"
        const val C_BOLD = Ansi.CSI + "1m"
        const val C_DIM = Ansi.CSI + "2m"
        const val C_GREEN = Ansi.CSI + "32m"
        const val C_CYAN = Ansi.CSI + "36m"
        const val C_RED = Ansi.CSI + "31m"
        const val PICKER_HINT = "↑↓ navigate · enter select · esc cancel"
        const val HIDE_CURSOR = Ansi.HIDE_CURSOR
        const val SHOW_CURSOR = Ansi.SHOW_CURSOR
        const val ERASE_TO_EOL = Ansi.ERASE_TO_END_OF_LINE
        const val SPINNER_TICK_MS: Long = 150L
    }
}
