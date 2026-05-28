package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.test.FakeTerminalControl
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.ai.agent.openai.ChatClient
import com.accucodeai.kash.tools.ai.agent.openai.ChatMessage
import com.accucodeai.kash.tools.ai.agent.openai.ChatResponse
import com.accucodeai.kash.tools.ai.agent.openai.ChatStreamEvent
import com.accucodeai.kash.tools.ai.agent.openai.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end exercise of the manual chat loop with a *scripted* LLM. Proves
 * the loop wires up and runs: read input → stream assistant reply → execute
 * any tool calls → loop → EOF → return.
 *
 * The agent now talks directly to a [ChatClient] (the production
 * [com.accucodeai.kash.tools.ai.agent.openai.OpenAIChatClient] in prod;
 * [FakeChatClient] here). The fake scripts assistant turns via
 * [ChatStreamEvent] flows — one [emitText]/[emitToolCall]/[emitBlank] per
 * turn — keyed off `streamCalls` so a single executor can express a
 * multi-round conversation.
 */
class AgentLoopTest {
    /**
     * Scripts assistant responses via per-turn flows. Behavior is selected
     * by the boolean flags on the constructor; each flag corresponds to one
     * test scenario.
     */
    private class FakeChatClient(
        private val toolThenText: Boolean = false,
        private val parallelTools: Boolean = false,
        private val quotedToolArg: Boolean = false,
        private val writeFileTool: Boolean = false,
        private val multiRound: Boolean = false,
        private val blankAfterTool: Boolean = false,
        private val blankBetweenProgress: Boolean = false,
        private val relentlessBlank: Boolean = false,
        private val blankInAnswerToNudge: Boolean = false,
        private val compactsThenText: Boolean = false,
        private val reasoningThenText: Boolean = false,
        private val thinkInContent: Boolean = false,
        /**
         * Emit `arguments=""` (BLOCKED-style) on the first tool call; the
         * follow-up turn records what `tool` result the model receives so
         * we can assert the error message is precise.
         */
        private val emptyArgsToolCall: Boolean = false,
        /**
         * Emit non-empty but malformed JSON args; the follow-up records
         * the resulting tool result. Regression for the prior
         * double-encode + sentinel hacks.
         */
        private val malformedThenText: Boolean = false,
    ) : ChatClient {
        var streamCalls = 0
        var completeCalls = 0
        var sawToolResultBeforeReply = false
        var nudgesInPrompt = 0
        var sawReasoningInHistory = false
        var reasoningTextInHistory: String? = null
        var assistantContentInHistory: String? = null
        var malformedToolResult: String? = null

        override fun stream(
            model: String,
            messages: List<ChatMessage>,
            temperature: Double?,
            tools: List<ToolDescriptor>?,
        ): Flow<ChatStreamEvent> =
            flow {
                streamCalls++
                if (toolThenText) {
                    if (streamCalls == 1) {
                        emitToolCall("c1", "shell_exec", """{"command":"echo hi"}""")
                    } else {
                        sawToolResultBeforeReply =
                            messages.any { it is ChatMessage.Tool }
                        emitText("ran it")
                    }
                    return@flow
                }
                if (parallelTools) {
                    if (streamCalls == 1) {
                        emitToolCall("c1", "shell_exec", """{"command":"echo UNIQUEONE"}""", index = 0)
                        emitToolCall("c2", "shell_exec", """{"command":"echo UNIQUETWO"}""", index = 1)
                        emit(ChatStreamEvent.End("tool_calls"))
                    } else {
                        emitText("both ran")
                    }
                    return@flow
                }
                if (quotedToolArg) {
                    if (streamCalls == 1) {
                        emitToolCall("c1", "shell_exec", """{"command":"echo \"hi\""}""")
                    } else {
                        emitText("ok")
                    }
                    return@flow
                }
                if (multiRound) {
                    when (streamCalls) {
                        1 -> {
                            emitToolCall("c1", "shell_exec", """{"command":"echo R1OUT"}""")
                        }

                        2 -> {
                            emit(ChatStreamEvent.TextDelta("babbling here"))
                            emit(ChatStreamEvent.ToolCallStarted(0, "c2", "shell_exec"))
                            emit(
                                ChatStreamEvent.ToolCallComplete(
                                    0,
                                    "c2",
                                    "shell_exec",
                                    """{"command":"echo R2OUT"}""",
                                ),
                            )
                            emit(ChatStreamEvent.End("tool_calls"))
                        }

                        else -> {
                            emitText("all done")
                        }
                    }
                    return@flow
                }
                if (reasoningThenText) {
                    if (streamCalls == 1) {
                        emit(ChatStreamEvent.ReasoningDelta("PONDERING: 2+2 "))
                        emit(ChatStreamEvent.ReasoningDelta("= 4."))
                        emitText("The answer is 4.")
                    } else {
                        recordReasoningHistory(messages)
                        emitText("ok")
                    }
                    return@flow
                }
                if (thinkInContent) {
                    if (streamCalls == 1) {
                        emit(ChatStreamEvent.TextDelta("<thi"))
                        emit(ChatStreamEvent.TextDelta("nk>weighing options</think>"))
                        emit(ChatStreamEvent.TextDelta("The answer is 42."))
                        emit(ChatStreamEvent.End("stop"))
                    } else {
                        recordReasoningHistory(messages)
                        val assistant = messages.filterIsInstance<ChatMessage.Assistant>().firstOrNull()
                        assistantContentInHistory = assistant?.content
                        emitText("ok")
                    }
                    return@flow
                }
                if (blankAfterTool) {
                    when (streamCalls) {
                        1 -> {
                            emitToolCall("c1", "shell_exec", """{"command":"echo X"}""")
                        }

                        2 -> {
                            emitBlank()
                        }

                        else -> {
                            countNudges(messages)
                            emitText("summary done")
                        }
                    }
                    return@flow
                }
                if (blankBetweenProgress) {
                    when (streamCalls) {
                        1 -> {
                            emitToolCall("c1", "shell_exec", """{"command":"echo X"}""")
                        }

                        2 -> {
                            emitBlank()
                        }

                        3 -> {
                            emitToolCall("c2", "shell_exec", """{"command":"echo Y"}""")
                        }

                        4 -> {
                            emitBlank()
                        }

                        else -> {
                            countNudges(messages)
                            emitText("done")
                        }
                    }
                    return@flow
                }
                if (relentlessBlank) {
                    if (streamCalls % 2 == 1) {
                        emitToolCall("c$streamCalls", "shell_exec", """{"command":"echo X"}""")
                    } else {
                        emitBlank()
                    }
                    return@flow
                }
                if (blankInAnswerToNudge) {
                    when (streamCalls) {
                        1 -> {
                            emitToolCall("c1", "shell_exec", """{"command":"echo X"}""")
                        }

                        2 -> {
                            emitBlank()
                        }

                        3 -> {
                            emitBlank()
                        }

                        else -> {
                            countNudges(messages)
                            emitText("done")
                        }
                    }
                    return@flow
                }
                if (compactsThenText) {
                    if (streamCalls == 1) {
                        emitToolCall("c1", "shell_exec", """{"command":"echo X"}""")
                    } else {
                        emitText("recovered after compaction")
                    }
                    return@flow
                }
                if (writeFileTool) {
                    if (streamCalls == 1) {
                        emitToolCall(
                            "c1",
                            "write_file",
                            """{"content":"hello world","path":"/tmp/demo.txt"}""",
                        )
                    } else {
                        emitText("saved")
                    }
                    return@flow
                }
                if (emptyArgsToolCall) {
                    when (streamCalls) {
                        1 -> {
                            emitToolCall("c1", "shell_exec", """{"command":"echo hi"}""")
                        }

                        2 -> {
                            emitToolCall("c2", "shell_exec", "")
                        }

                        else -> {
                            malformedToolResult = lastToolResult(messages)
                            emitText("recovered")
                        }
                    }
                    return@flow
                }
                if (malformedThenText) {
                    if (streamCalls == 1) {
                        emitToolCall(
                            "m1",
                            "write_file",
                            "{\"path\":\"x.sh\",\"content\":\"#!/bin/ba",
                        )
                    } else {
                        malformedToolResult = lastToolResult(messages)
                        emitText("done")
                    }
                    return@flow
                }
                emitText("Hi from the fake model.")
            }

        override suspend fun complete(
            model: String,
            messages: List<ChatMessage>,
            temperature: Double?,
            tools: List<ToolDescriptor>?,
        ): ChatResponse {
            completeCalls++
            // Compaction asks for a TLDR — return a short canned summary.
            val tldr = ChatMessage.Assistant(content = "prior conversation summarized")
            return ChatResponse(choices = listOf(ChatResponse.Choice(message = tldr, finishReason = "stop")))
        }

        override suspend fun listModels(): List<String> = listOf("fake-model")

        override fun close() = Unit

        private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatStreamEvent>.emitText(text: String) {
            if (text.isNotEmpty()) emit(ChatStreamEvent.TextDelta(text))
            emit(ChatStreamEvent.End("stop"))
        }

        private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatStreamEvent>.emitBlank() {
            emit(ChatStreamEvent.TextDelta("\n\n"))
            emit(ChatStreamEvent.End("stop"))
        }

        private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatStreamEvent>.emitToolCall(
            id: String,
            name: String,
            args: String,
            index: Int = 0,
        ) {
            emit(ChatStreamEvent.ToolCallStarted(index, id, name))
            emit(ChatStreamEvent.ToolCallComplete(index, id, name, args))
            emit(ChatStreamEvent.End("tool_calls"))
        }

        private fun countNudges(messages: List<ChatMessage>) {
            nudgesInPrompt =
                messages.filterIsInstance<ChatMessage.User>().count { u ->
                    val s = u.content.toString()
                    "Do not leave the user on a tool call" in s
                }
        }

        private fun recordReasoningHistory(messages: List<ChatMessage>) {
            val assistant = messages.filterIsInstance<ChatMessage.Assistant>().firstOrNull()
            reasoningTextInHistory = assistant?.reasoningContent
            sawReasoningInHistory = !reasoningTextInHistory.isNullOrEmpty()
        }

        private fun lastToolResult(messages: List<ChatMessage>): String? =
            messages.filterIsInstance<ChatMessage.Tool>().lastOrNull()?.content
    }

    private fun capturingSink(buf: StringBuilder): SuspendSink =
        object : SuspendSink {
            override suspend fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                buf.append(source.readByteArray(byteCount.toInt()).decodeToString())
            }

            override suspend fun flush() {}

            override fun close() {}
        }

    private fun cfg(
        contextBudgetTokens: Int = AgentConfig.DEFAULT_CONTEXT_BUDGET,
        showThinking: Boolean = true,
        appendReasoning: Boolean = true,
        parseThinkTags: Boolean = true,
    ) = AgentConfig(
        baseUrl = "http://localhost:1234",
        modelId = "fake-model",
        apiKey = "",
        systemPrompt = "test",
        temperature = 0.0,
        toolsEnabled = true,
        contextBudgetTokens = contextBudgetTokens,
        showThinking = showThinking,
        appendReasoning = appendReasoning,
        parseThinkTags = parseThinkTags,
        // captureRun pre-queues all turns at once via FakeTerminalControl —
        // under strict turn-taking the pre-stream drain would treat the
        // second turn's input as type-ahead and swallow it. These tests
        // are about agent-loop behavior, not input throttling.
        strictTurnTaking = false,
    )

    private suspend fun captureRun(
        config: AgentConfig,
        fake: FakeChatClient,
        inputs: List<String>,
    ): String {
        val term = FakeTerminalControl()
        for (line in inputs) {
            term.pushChars(line)
            term.pushKey(Key.Named.ENTER)
        }
        term.pushKey(Key.Ctrl('D'))
        val captured = StringBuilder()
        val sink = capturingSink(captured)
        val ctx = bareCommandContext(stdout = sink, stderr = sink)
        AgentSession(term, ctx, config, clientOverride = fake).run()
        return captured.toString()
    }

    @Test
    fun loop_reads_input_streams_a_reply_then_quits_on_eof() =
        runTest {
            val fake = FakeChatClient()
            val out = captureRun(cfg(), fake, listOf("hello"))
            assertTrue(fake.streamCalls >= 1, "LLM ran at least once")
            assertTrue("Hi from the fake model." in out, "reply rendered · output=<<<$out>>>")
        }

    @Test
    fun tool_round_trip_appends_result_before_follow_up() =
        runTest {
            val fake = FakeChatClient(toolThenText = true)
            val out = captureRun(cfg(), fake, listOf("use a tool"))
            assertTrue(fake.streamCalls >= 2, "a follow-up LLM call happened after the tool")
            assertTrue(fake.sawToolResultBeforeReply, "tool result was appended to the prompt before the follow-up")
            assertTrue("ran it" in out, "post-tool reply rendered · output=<<<$out>>>")
            assertTrue("shell_exec" in out, "tool-call indicator rendered · output=<<<$out>>>")
            assertTrue("echo hi" in out, "tool args summarized in indicator · output=<<<$out>>>")
        }

    @Test
    fun parallel_tool_calls_render_indicator_then_result_interleaved() =
        runTest {
            val out = captureRun(cfg(), FakeChatClient(parallelTools = true), listOf("run two"))
            assertTrue("UNIQUEONE" in out && "UNIQUETWO" in out, "both tools committed · screen=<<<$out>>>")
            assertTrue(
                out.lastIndexOf("UNIQUEONE") < out.indexOf("UNIQUETWO"),
                "tool one fully renders before tool two · screen=<<<$out>>>",
            )
        }

    @Test
    fun tool_arg_summary_is_unescaped() =
        runTest {
            val out = captureRun(cfg(), FakeChatClient(quotedToolArg = true), listOf("quote me"))
            assertTrue("echo \"hi\"" in out, "indicator shows unescaped quotes · output=<<<$out>>>")
            assertTrue("echo \\\"hi\\\"" !in out, "indicator must not show JSON escapes · output=<<<$out>>>")
        }

    @Test
    fun write_file_diff_is_committed_to_history() =
        runTest {
            val out = captureRun(cfg(), FakeChatClient(writeFileTool = true), listOf("make a file"))
            assertTrue("demo.txt" in out, "diff/indicator references the path · screen=<<<$out>>>")
            assertTrue("hello world" in out, "diff body committed to history · screen=<<<$out>>>")
            assertTrue("ok: wrote" in out, "result committed · screen=<<<$out>>>")
            assertTrue(
                out.indexOf("write_file") < out.indexOf("hello world") &&
                    out.indexOf("hello world") < out.indexOf("ok: wrote"),
                "indicator → diff → result order · screen=<<<$out>>>",
            )
        }

    @Test
    fun multi_round_tools_then_text_then_tools_does_not_hang() =
        runTest {
            val fake = FakeChatClient(multiRound = true)
            val out = captureRun(cfg(), fake, listOf("go"))
            assertTrue(fake.streamCalls >= 3, "all three rounds ran · calls=${fake.streamCalls}")
            assertTrue("babbling here" in out, "mid-conversation text rendered · screen=<<<$out>>>")
            assertTrue("all done" in out, "final text rendered · screen=<<<$out>>>")
        }

    @Test
    fun blank_reply_after_tool_nudges_then_summarizes() =
        runTest {
            val fake = FakeChatClient(blankAfterTool = true)
            val out = captureRun(cfg(), fake, listOf("go"))
            assertEquals(3, fake.streamCalls, "blank reply triggered exactly one re-request")
            assertEquals(1, fake.nudgesInPrompt, "the model was nudged once")
            assertTrue("summary done" in out, "the re-requested summary rendered · output=<<<$out>>>")
            assertTrue(
                "Do not leave the user on a tool call" !in out,
                "the nudge must NOT be shown in the UI · output=<<<$out>>>",
            )
        }

    @Test
    fun blank_replies_between_tool_progress_are_nudged_each_time() =
        runTest {
            val fake = FakeChatClient(blankBetweenProgress = true)
            val out = captureRun(cfg(), fake, listOf("go"))
            assertEquals(5, fake.streamCalls, "two empty stops → two re-requests")
            assertEquals(2, fake.nudgesInPrompt, "the model was nudged at BOTH stops · nudges=${fake.nudgesInPrompt}")
            assertTrue("done" in out, "final summary rendered · output=<<<$out>>>")
        }

    @Test
    fun relentlessly_blank_agent_is_bounded_by_the_nudge_cap() =
        runTest {
            val fake = FakeChatClient(relentlessBlank = true)
            captureRun(cfg(), fake, listOf("go"))
            // 1 initial tool + cap*(blank+tool) calls — bounded, never hangs.
            assertTrue(fake.streamCalls in 30..40, "bounded by the nudge cap · calls=${fake.streamCalls}")
        }

    @Test
    fun blank_in_answer_to_the_nudge_is_nudged_again_not_dropped() =
        runTest {
            val fake = FakeChatClient(blankInAnswerToNudge = true)
            val out = captureRun(cfg(), fake, listOf("go"))
            assertEquals(4, fake.streamCalls, "the second blank was nudged, not dropped · calls=${fake.streamCalls}")
            assertEquals(2, fake.nudgesInPrompt, "the model was nudged at BOTH blanks · nudges=${fake.nudgesInPrompt}")
            assertTrue("done" in out, "final summary rendered · output=<<<$out>>>")
            assertTrue(
                "Do not leave the user on a tool call" !in out,
                "the nudge must NOT be shown in the UI · output=<<<$out>>>",
            )
        }

    @Test
    fun history_compaction_is_announced_and_uses_complete_call() =
        runTest {
            val fake = FakeChatClient(compactsThenText = true)
            val out = captureRun(cfg(contextBudgetTokens = 2000), fake, listOf("go"))
            assertTrue("compacting history" in out, "compaction indicator shown · output=<<<$out>>>")
            assertTrue(fake.completeCalls >= 1, "side LLM call ran · complete=${fake.completeCalls}")
            assertTrue(
                "recovered after compaction" in out,
                "post-compaction reply rendered · output=<<<$out>>>",
            )
        }

    @Test
    fun tool_rendering_never_nukes_the_scrollback() =
        runTest {
            val out = captureRun(cfg(), FakeChatClient(parallelTools = true), listOf("run two"))
            assertTrue("[2J" !in out, "must never clear the screen")
            assertTrue("[3J" !in out, "must never clear scrollback")
        }

    @Test
    fun reasoning_is_displayed_and_appended_to_history_by_default() =
        runTest {
            val fake = FakeChatClient(reasoningThenText = true)
            val out = captureRun(cfg(), fake, listOf("think", "again"))
            assertTrue("PONDERING" in out, "reasoning rendered · output=<<<$out>>>")
            assertTrue("The answer is 4." in out, "answer rendered · output=<<<$out>>>")
            assertTrue(fake.sawReasoningInHistory, "reasoning echoed into history for prefix caching")
            assertEquals("PONDERING: 2+2 = 4.", fake.reasoningTextInHistory)
        }

    @Test
    fun no_thinking_hides_reasoning_display_but_still_appends_it() =
        runTest {
            val fake = FakeChatClient(reasoningThenText = true)
            val out = captureRun(cfg(showThinking = false), fake, listOf("think", "again"))
            assertTrue("PONDERING" !in out, "reasoning hidden from screen · output=<<<$out>>>")
            assertTrue("The answer is 4." in out, "answer still rendered · output=<<<$out>>>")
            assertTrue(fake.sawReasoningInHistory, "append is independent of the display toggle")
        }

    @Test
    fun no_reasoning_history_keeps_display_but_strips_from_history() =
        runTest {
            val fake = FakeChatClient(reasoningThenText = true)
            val out = captureRun(cfg(appendReasoning = false), fake, listOf("think", "again"))
            assertTrue("PONDERING" in out, "still displayed · output=<<<$out>>>")
            assertTrue(!fake.sawReasoningInHistory, "reasoning stripped from history for APIs that reject it")
        }

    @Test
    fun inline_think_tags_are_parsed_dimmed_and_answer_kept_clean() =
        runTest {
            val fake = FakeChatClient(thinkInContent = true)
            val out = captureRun(cfg(), fake, listOf("solve", "again"))
            assertTrue("weighing options" in out, "reasoning rendered · output=<<<$out>>>")
            assertTrue("The answer is 42." in out, "answer rendered · output=<<<$out>>>")
            assertTrue("<think>" !in out, "open tag not shown raw · output=<<<$out>>>")
            assertEquals("weighing options", fake.reasoningTextInHistory, "reasoning routed to history")
            assertEquals("The answer is 42.", fake.assistantContentInHistory, "history answer is think-stripped")
        }

    @Test
    fun no_think_tags_passes_content_through_verbatim() =
        runTest {
            val fake = FakeChatClient(thinkInContent = true)
            val out = captureRun(cfg(parseThinkTags = false), fake, listOf("solve", "again"))
            assertTrue("<think>weighing options</think>" in out, "tags shown raw · output=<<<$out>>>")
            assertTrue(!fake.sawReasoningInHistory, "no reasoning part when parsing is off")
            assertEquals(
                "<think>weighing options</think>The answer is 42.",
                fake.assistantContentInHistory,
                "content kept verbatim in history",
            )
        }

    @Test
    fun empty_tool_call_arguments_produce_a_precise_self_correction_error() =
        runTest {
            // Regression for the LM Studio "BLOCKED announce-only" case: a
            // tool_call arrives with arguments=""; the tool must produce a
            // precise error the model can self-correct from (and we must
            // NOT silently coerce to `{}` — that's the loop-trigger bug).
            val fake = FakeChatClient(emptyArgsToolCall = true)
            val out = captureRun(cfg(), fake, listOf("go"))
            val fed = fake.malformedToolResult ?: error("no tool-result fed back")
            assertTrue(
                "arguments were empty" in fed,
                "model is told its args were empty · fed=<<<$fed>>>",
            )
            assertTrue("recovered" in out, "the model self-corrected and finished · output=<<<$out>>>")
        }

    @Test
    fun malformed_tool_call_arguments_echo_the_raw_back_to_the_model() =
        runTest {
            // Non-empty but invalid JSON: the tool's argument parse rejects
            // the call with a message that includes the raw the model
            // emitted, so it can self-correct.
            val fake = FakeChatClient(malformedThenText = true)
            val out = captureRun(cfg(), fake, listOf("write a file"))
            val fed = fake.malformedToolResult ?: error("no tool-result fed back")
            assertTrue("not valid JSON" in fed, "told the model its args were malformed JSON · fed=<<<$fed>>>")
            assertTrue("#!/bin/ba" in fed, "raw malformed args echoed back · fed=<<<$fed>>>")
            assertTrue("done" in out, "the model self-corrected and finished · output=<<<$out>>>")
        }
}
