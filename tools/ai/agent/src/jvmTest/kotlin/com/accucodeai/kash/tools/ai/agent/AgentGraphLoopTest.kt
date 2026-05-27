package com.accucodeai.kash.tools.ai.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.test.FakeTerminalControl
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end exercise of the graph-based agent loop with a *scripted* LLM
 * (no network). Proves the strategy graph wires up and runs: nodeStart →
 * readInput → callLLM (streaming) → onTextMessage → readInput → EOF →
 * nodeFinish. The fake [PromptExecutor] returns a plain text answer, so no
 * tool round-trip — that path still needs a live model, but this verifies
 * the core loop, the streaming render, and clean termination.
 */
class AgentGraphLoopTest {
    /** Scripts a single text response over the streaming API. */
    private class FakeExecutor(
        /** Simulate LM Studio: throw on the trailing chunk after content arrives. */
        private val throwTrailingChunk: Boolean = false,
        /** Simulate LM Studio: stream text as deltas with NO TextComplete frame. */
        private val deltasOnly: Boolean = false,
        /**
         * Emit the real-world shape models produce around a reply:
         * "\n\n<text>\n\n" plus trailing standalone "\n" parts. Both the
         * leading and trailing blank lines must be collapsed on screen.
         */
        private val surroundingBlankText: Boolean = false,
        /**
         * Tool round-trip: call 1 → a shell_exec tool call; call 2 → text.
         * On call 2 we assert the tool RESULT is already in the prompt (the
         * bug: sendToolResults must append it before requesting).
         */
        private val toolThenText: Boolean = false,
        /**
         * Call 1 → TWO shell_exec calls in one assistant message; call 2 →
         * text. Drives the parallel-call rendering-order guard.
         */
        private val parallelTools: Boolean = false,
        /**
         * Call 1 → one shell_exec whose command contains quotes; call 2 →
         * text. Drives the arg-summary un-escaping guard.
         */
        private val quotedToolArg: Boolean = false,
        /** Call 1 → a write_file call (renders a diff); call 2 → text. */
        private val writeFileTool: Boolean = false,
        /**
         * Multi-round: tool call → (result) → text+tool call → (result) →
         * text. Reproduces "does tools, responds, babbles, does more tools" —
         * the sequence the user reports freezing.
         */
        private val multiRound: Boolean = false,
        /** Tool call → blank (whitespace-only) reply → (after nudge) real text. */
        private val blankAfterTool: Boolean = false,
        /** tool → blank → (nudge) → tool → blank → (nudge) → text: nudge EACH stop. */
        private val blankBetweenProgress: Boolean = false,
        /** Always tool-then-blank: the nudge cap must stop it (no endless nudging). */
        private val relentlessBlank: Boolean = false,
        /**
         * tool → blank (post-tool, nudged) → blank AGAIN in answer to the nudge
         * → text. The second blank comes out of callLLM (the node the nudge
         * re-requests on); without callLLM's own nudge edge it drops silently to
         * the prompt with no summary.
         */
        private val blankInAnswerToNudge: Boolean = false,
        /**
         * tool → (history over the tiny budget) compaction → text. Drives the
         * announceCompaction indicator. Paired with a low contextBudgetTokens so
         * the first tool round trips isHistoryTooBig.
         */
        private val compactsThenText: Boolean = false,
        /**
         * Turn 1 → reasoning deltas + a text answer (no ReasoningComplete, the
         * LM Studio shape). On the next call, record whether the prior
         * assistant message carried a [MessagePart.Reasoning] part — the
         * prefix-cache echo.
         */
        private val reasoningThenText: Boolean = false,
        /**
         * Turn 1 → a reply with inline `<think>…</think>` in the *content*
         * stream (the LM Studio chat-completions shape, tags split across
         * deltas). On the next call, record the prior assistant message's
         * reasoning part and its (think-stripped) answer text.
         */
        private val thinkInContent: Boolean = false,
    ) : PromptExecutor() {
        var streamCalls = 0
        var sawToolResultBeforeReply = false
        var sawNudgeBeforeReply = false
        var nudgesInPrompt = 0
        var sawReasoningInHistory = false
        var reasoningTextInHistory: String? = null
        var answerTextInHistory: String? = null

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> =
            flow {
                streamCalls++
                if (toolThenText) {
                    if (streamCalls == 1) {
                        emit(
                            StreamFrame.ToolCallComplete(
                                id = "c1",
                                name = "shell_exec",
                                content = """{"command":"echo hi"}""",
                            ),
                        )
                        emit(StreamFrame.End(finishReason = "tool_calls", metaInfo = ResponseMetaInfo.Empty))
                        return@flow
                    }
                    // Follow-up call: the tool result must already be in history.
                    sawToolResultBeforeReply =
                        prompt.messages.any { m -> m.parts.any { it is MessagePart.Tool.Result } }
                    emit(StreamFrame.TextDelta("ran it"))
                    emit(StreamFrame.TextComplete("ran it"))
                    emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                    return@flow
                }
                if (parallelTools) {
                    if (streamCalls == 1) {
                        emit(StreamFrame.ToolCallComplete("c1", "shell_exec", """{"command":"echo UNIQUEONE"}"""))
                        emit(StreamFrame.ToolCallComplete("c2", "shell_exec", """{"command":"echo UNIQUETWO"}"""))
                        emit(StreamFrame.End(finishReason = "tool_calls", metaInfo = ResponseMetaInfo.Empty))
                        return@flow
                    }
                    emit(StreamFrame.TextDelta("both ran"))
                    emit(StreamFrame.TextComplete("both ran"))
                    emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                    return@flow
                }
                if (quotedToolArg) {
                    if (streamCalls == 1) {
                        // command value is: echo "hi"  (JSON-escaped inside args)
                        emit(StreamFrame.ToolCallComplete("c1", "shell_exec", """{"command":"echo \"hi\""}"""))
                        emit(StreamFrame.End(finishReason = "tool_calls", metaInfo = ResponseMetaInfo.Empty))
                        return@flow
                    }
                    emit(StreamFrame.TextDelta("ok"))
                    emit(StreamFrame.TextComplete("ok"))
                    emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                    return@flow
                }
                if (multiRound) {
                    when (streamCalls) {
                        1 -> {
                            emit(StreamFrame.ToolCallComplete("c1", "shell_exec", """{"command":"echo R1OUT"}"""))
                            emit(StreamFrame.End(finishReason = "tool_calls", metaInfo = ResponseMetaInfo.Empty))
                        }

                        2 -> {
                            // Mixed: babble text AND another tool call in one message.
                            emit(StreamFrame.TextDelta("babbling here"))
                            emit(StreamFrame.TextComplete("babbling here"))
                            emit(StreamFrame.ToolCallComplete("c2", "shell_exec", """{"command":"echo R2OUT"}"""))
                            emit(StreamFrame.End(finishReason = "tool_calls", metaInfo = ResponseMetaInfo.Empty))
                        }

                        else -> {
                            emit(StreamFrame.TextDelta("all done"))
                            emit(StreamFrame.TextComplete("all done"))
                            emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                        }
                    }
                    return@flow
                }

                if (reasoningThenText) {
                    if (streamCalls == 1) {
                        emit(StreamFrame.ReasoningDelta(text = "PONDERING: 2+2 "))
                        emit(StreamFrame.ReasoningDelta(text = "= 4."))
                        emit(StreamFrame.TextDelta("The answer is 4."))
                        emit(StreamFrame.TextComplete("The answer is 4."))
                        emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                        return@flow
                    }
                    // Follow-up turn: inspect history for the reasoning echo.
                    val part =
                        prompt.messages
                            .filterIsInstance<Message.Assistant>()
                            .flatMap { it.parts }
                            .filterIsInstance<MessagePart.Reasoning>()
                            .firstOrNull()
                    sawReasoningInHistory = part != null
                    reasoningTextInHistory = part?.content?.joinToString("")
                    emit(StreamFrame.TextDelta("ok"))
                    emit(StreamFrame.TextComplete("ok"))
                    emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                    return@flow
                }

                if (thinkInContent) {
                    if (streamCalls == 1) {
                        // Tags straddle delta boundaries, like a real stream.
                        emit(StreamFrame.TextDelta("<thi"))
                        emit(StreamFrame.TextDelta("nk>weighing options</think>"))
                        emit(StreamFrame.TextDelta("The answer is 42."))
                        emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                        return@flow
                    }
                    val assistant =
                        prompt.messages.filterIsInstance<Message.Assistant>().firstOrNull()
                    answerTextInHistory =
                        assistant?.parts?.filterIsInstance<MessagePart.Text>()?.joinToString("") { it.text }
                    val rpart = assistant?.parts?.filterIsInstance<MessagePart.Reasoning>()?.firstOrNull()
                    sawReasoningInHistory = rpart != null
                    reasoningTextInHistory = rpart?.content?.joinToString("")
                    emit(StreamFrame.TextDelta("ok"))
                    emit(StreamFrame.TextComplete("ok"))
                    emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                    return@flow
                }

                fun countNudges() {
                    nudgesInPrompt =
                        prompt.messages.filterIsInstance<Message.User>().count { u ->
                            u.parts.filterIsInstance<MessagePart.Text>().any {
                                "Do not leave the user on a tool call" in it.text
                            }
                        }
                    sawNudgeBeforeReply = nudgesInPrompt > 0
                }

                suspend fun emitTool() {
                    emit(StreamFrame.ToolCallComplete("c$streamCalls", "shell_exec", """{"command":"echo X"}"""))
                    emit(StreamFrame.End(finishReason = "tool_calls", metaInfo = ResponseMetaInfo.Empty))
                }

                suspend fun emitBlank() {
                    emit(StreamFrame.TextDelta("\n\n"))
                    emit(StreamFrame.TextComplete("\n\n"))
                    emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                }

                suspend fun emitText(s: String) {
                    emit(StreamFrame.TextDelta(s))
                    emit(StreamFrame.TextComplete(s))
                    emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                }

                if (blankAfterTool) {
                    when (streamCalls) {
                        1 -> {
                            emitTool()
                        }

                        2 -> {
                            emitBlank()
                        }

                        else -> {
                            countNudges()
                            emitText("summary done")
                        }
                    }
                    return@flow
                }
                if (blankBetweenProgress) {
                    when (streamCalls) {
                        1 -> {
                            emitTool()
                        }

                        // user prompt → tool
                        2 -> {
                            emitBlank()
                        }

                        // post-tool blank → nudge #1
                        3 -> {
                            emitTool()
                        }

                        // continued after nudge (progress)
                        4 -> {
                            emitBlank()
                        }

                        // post-tool blank again → nudge #2 (the fix)
                        else -> {
                            countNudges()
                            emitText("done")
                        }
                    }
                    return@flow
                }
                if (relentlessBlank) {
                    // Odd call = tool (progress), even = blank (post-tool). The
                    // per-turn nudge cap must eventually stop the cycle.
                    if (streamCalls % 2 == 1) emitTool() else emitBlank()
                    return@flow
                }
                if (blankInAnswerToNudge) {
                    when (streamCalls) {
                        // user prompt → tool
                        1 -> {
                            emitTool()
                        }

                        // post-tool blank (sendToolResults) → nudge #1 → callLLM
                        2 -> {
                            emitBlank()
                        }

                        // answers the nudge with ANOTHER blank, on callLLM → nudge #2
                        3 -> {
                            emitBlank()
                        }

                        // continued after the second nudge
                        else -> {
                            countNudges()
                            emitText("done")
                        }
                    }
                    return@flow
                }
                if (compactsThenText) {
                    if (streamCalls == 1) emitTool() else emitText("recovered after compaction")
                    return@flow
                }
                if (writeFileTool) {
                    if (streamCalls == 1) {
                        emit(
                            StreamFrame.ToolCallComplete(
                                "c1",
                                "write_file",
                                """{"content":"hello world","path":"/tmp/demo.txt"}""",
                            ),
                        )
                        emit(StreamFrame.End(finishReason = "tool_calls", metaInfo = ResponseMetaInfo.Empty))
                        return@flow
                    }
                    emit(StreamFrame.TextDelta("saved"))
                    emit(StreamFrame.TextComplete("saved"))
                    emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                    return@flow
                }
                if (surroundingBlankText) {
                    // Mirrors the real wire shape: leading + trailing blanks in
                    // the first part, then standalone "\n" parts.
                    emit(StreamFrame.TextDelta("\n\nHey! Let me show off a few tools real quick:\n\n"))
                    emit(StreamFrame.TextDelta("\n"))
                    emit(StreamFrame.TextDelta("\n"))
                    emit(StreamFrame.TextComplete("\n\nHey! Let me show off a few tools real quick:\n\n\n\n"))
                    emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                    return@flow
                }
                emit(StreamFrame.TextDelta("Hi from the fake model."))
                // Real streaming usually emits a TextComplete for assembly,
                // but LM Studio often streams deltas only and the finalizing
                // chunk is the one the lenient client drops.
                if (!deltasOnly) emit(StreamFrame.TextComplete("Hi from the fake model."))
                if (throwTrailingChunk) {
                    // Koog 1.0 + LM Studio: strict decode of the final
                    // usage-only chunk fails AFTER content already streamed.
                    error("Fields [choices, created, id, model, object] are required … missing at path: $")
                }
                emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
            }

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = Message.Assistant(content = "Hi from the fake model.", metaInfo = ResponseMetaInfo.Empty)

        override suspend fun moderate(
            prompt: Prompt,
            model: LLModel,
        ) = error("moderation not used by the agent graph")

        override fun close() {}
    }

    @Test
    fun loop_reads_input_streams_a_reply_then_quits_on_eof() =
        runTest {
            val term = FakeTerminalControl()
            // Turn 1: type "hello" + Enter. Turn 2: Ctrl-D on empty → EOF → quit.
            term.pushChars("hello")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val capturingSink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = capturingSink, stderr = capturingSink)
            val fake = FakeExecutor()
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model", // set → resolveModel skips the /v1/models probe
                    apiKey = "",
                    systemPrompt = "you are a test agent",
                    temperature = 0.0,
                    toolsEnabled = true,
                )

            val session = AgentSession(term, ctx, config, executorOverride = fake)
            val code = session.run()

            assertEquals(0, code, "clean exit on EOF · output=<<<$captured>>>")
            assertTrue(fake.streamCalls >= 1, "the LLM streaming node ran at least once")
            assertTrue(
                "Hi from the fake model." in captured.toString(),
                "streamed text rendered · output=<<<$captured>>>",
            )
        }

    @Test
    fun tool_round_trip_appends_result_before_follow_up() =
        runTest {
            // call 1 → tool call → executeTools → sendToolResults (must append
            // the result) → call 2 → text → loop → EOF. Regression guard for
            // the "empty replies after first tool call" bug.
            val term = FakeTerminalControl()
            term.pushChars("use a tool")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val fake = FakeExecutor(toolThenText = true)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code = AgentSession(term, ctx, config, executorOverride = fake).run()
            assertEquals(0, code, "tool round-trip completes · output=<<<$captured>>>")
            assertTrue(fake.streamCalls >= 2, "a follow-up LLM call happened after the tool")
            assertTrue(fake.sawToolResultBeforeReply, "tool result was appended to the prompt before the follow-up")
            assertTrue("ran it" in captured.toString(), "post-tool reply rendered · output=<<<$captured>>>")
            // The tool-call indicator (rendered by the EventHandler) and its
            // summarized args must appear.
            assertTrue("shell_exec" in captured.toString(), "tool-call indicator rendered · output=<<<$captured>>>")
            assertTrue("echo hi" in captured.toString(), "tool args summarized in indicator · output=<<<$captured>>>")
        }

    @Test
    fun assembles_message_from_deltas_when_no_textcomplete() =
        runTest {
            // LM Studio streams text as deltas only; toMessageResponse would
            // yield parts=[] and dead-end the graph. We must fall back to
            // concatenated deltas so the turn routes back to the prompt.
            val term = FakeTerminalControl()
            term.pushChars("hello")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code = AgentSession(term, ctx, config, executorOverride = FakeExecutor(deltasOnly = true)).run()
            assertEquals(0, code, "delta-only stream still routes · output=<<<$captured>>>")
            assertTrue("Hi from the fake model." in captured.toString(), "deltas rendered · output=<<<$captured>>>")
        }

    @Test
    fun collapses_surrounding_blank_lines_in_the_reply() =
        runTest {
            // Models wrap replies in blank lines ("\n\n<text>\n\n" plus
            // standalone "\n" parts). Neither the leading nor the trailing
            // blanks may render — only the content, plus the single newline we
            // add to end the turn.
            val term = FakeTerminalControl()
            term.pushChars("hello")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code =
                AgentSession(
                    term,
                    ctx,
                    config,
                    executorOverride = FakeExecutor(surroundingBlankText = true),
                ).run()
            val out = captured.toString()
            assertEquals(0, code, "reply still routes · output=<<<$out>>>")
            assertTrue("Hey! Let me show off a few tools real quick:" in out, "content rendered · output=<<<$out>>>")
            assertTrue("\n\nHey!" !in out, "leading blank lines stripped · output=<<<$out>>>")
            // The model sent four trailing newlines; collapsed they become the
            // single turn-ending "\n" (the loop's EOF teardown may add one
            // more, so allow up to two — but never the model's run of 3+).
            assertTrue("quick:\n\n\n" !in out, "trailing blank lines collapsed · output=<<<$out>>>")
        }

    @Test
    fun tolerates_malformed_trailing_stream_chunk() =
        runTest {
            // Reproduces the live LM Studio failure: the SSE stream throws on
            // the final usage chunk after the real content already arrived.
            // The turn must still render the reply and the loop must survive.
            val term = FakeTerminalControl()
            term.pushChars("hello")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val capturingSink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = capturingSink, stderr = capturingSink)
            val fake = FakeExecutor(throwTrailingChunk = true)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "you are a test agent",
                    temperature = 0.0,
                    toolsEnabled = true,
                )

            val code = AgentSession(term, ctx, config, executorOverride = fake).run()

            assertEquals(0, code, "clean exit despite the trailing-chunk error · output=<<<$captured>>>")
            assertTrue(
                "Hi from the fake model." in captured.toString(),
                "content still rendered despite trailing-chunk error · output=<<<$captured>>>",
            )
        }

    @Test
    fun parallel_tool_calls_render_indicator_then_result_interleaved() =
        runTest {
            // Two shell_exec calls in ONE assistant message. Koog runs them
            // sequentially, so each indicator must be immediately followed by
            // its OWN result — `• one / ↳ one / • two / ↳ two`, NOT all
            // indicators batched ahead of all results. Guard: the result of
            // the first tool must appear before the indicator of the second.
            val term = FakeTerminalControl()
            term.pushChars("run two")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code = AgentSession(term, ctx, config, executorOverride = FakeExecutor(parallelTools = true)).run()
            // Append-only rendering: each tool commits `• … / ↳ …` inline as it
            // runs, so the raw stream is the final history.
            val screen = captured.toString()
            assertEquals(0, code, "two-tool turn completes · screen=<<<$screen>>>")
            assertTrue("UNIQUEONE" in screen && "UNIQUETWO" in screen, "both tools committed · screen=<<<$screen>>>")
            // In the committed history, tool one's indicator AND result both
            // precede tool two's. Batching (`• 1 / • 2 / ↳ 1 / ↳ 2`) would put
            // tool-two's indicator before tool-one's result → this flips false.
            assertTrue(
                screen.lastIndexOf("UNIQUEONE") < screen.indexOf("UNIQUETWO"),
                "tool one fully renders before tool two · screen=<<<$screen>>>",
            )
        }

    @Test
    fun tool_arg_summary_is_unescaped() =
        runTest {
            // A command containing quotes (`echo "hi"`) must show with real
            // quotes in the indicator, not JSON escapes (`echo \"hi\"`).
            val term = FakeTerminalControl()
            term.pushChars("quote me")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code = AgentSession(term, ctx, config, executorOverride = FakeExecutor(quotedToolArg = true)).run()
            val out = captured.toString()
            assertEquals(0, code, "quoted-arg turn completes · output=<<<$out>>>")
            assertTrue("echo \"hi\"" in out, "indicator shows unescaped quotes · output=<<<$out>>>")
            assertTrue("echo \\\"hi\\\"" !in out, "indicator must not show JSON escapes · output=<<<$out>>>")
        }

    @Test
    fun write_file_diff_is_committed_to_history() =
        runTest {
            // The write_file diff is emitted (via the toolset hook) inline
            // between the tool's indicator and its result: indicator → diff →
            // result.
            val term = FakeTerminalControl()
            term.pushChars("make a file")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code = AgentSession(term, ctx, config, executorOverride = FakeExecutor(writeFileTool = true)).run()
            // Append-only rendering: the raw stream is the final history.
            val screen = captured.toString()
            assertEquals(0, code, "write_file turn completes · screen=<<<$screen>>>")
            assertTrue("demo.txt" in screen, "diff/indicator references the path · screen=<<<$screen>>>")
            assertTrue("hello world" in screen, "diff body committed to history · screen=<<<$screen>>>")
            assertTrue("ok: wrote" in screen, "result committed · screen=<<<$screen>>>")
            // Order: indicator (write_file) → diff (hello world) → result (ok: wrote).
            assertTrue(
                screen.indexOf("write_file") < screen.indexOf("hello world") &&
                    screen.indexOf("hello world") < screen.indexOf("ok: wrote"),
                "indicator → diff → result order · screen=<<<$screen>>>",
            )
        }

    @Test
    fun multi_round_tools_then_text_then_tools_does_not_hang() =
        runTest {
            // tool → result → (text + tool) → result → text → readInput → EOF.
            // If any node output matched no graph edge, run() would never
            // return and runTest would time out.
            val term = FakeTerminalControl()
            term.pushChars("go")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val fake = FakeExecutor(multiRound = true)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code = AgentSession(term, ctx, config, executorOverride = fake).run()
            // Append-only rendering: the raw stream is the final history.
            val screen = captured.toString()
            assertEquals(0, code, "multi-round conversation completes · screen=<<<$screen>>>")
            assertTrue(fake.streamCalls >= 3, "all three rounds ran · calls=${fake.streamCalls}")
            assertTrue("babbling here" in screen, "mid-conversation text rendered · screen=<<<$screen>>>")
            assertTrue("all done" in screen, "final text rendered · screen=<<<$screen>>>")
            // The babble line is closed (newline) before the tool phase begins
            // — this is the transition that restarts the "calling …" spinner so
            // the post-text/pre-tool gap doesn't look frozen.
            assertTrue("babbling here\n" in screen, "text line closed before tools · screen=<<<$screen>>>")
        }

    @Test
    fun blank_reply_after_tool_nudges_then_summarizes() =
        runTest {
            // tool → blank post-tool reply → (hidden nudge, re-request) → real
            // summary. The nudge must reach the model but never be rendered.
            val term = FakeTerminalControl()
            term.pushChars("go")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val fake = FakeExecutor(blankAfterTool = true)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code = AgentSession(term, ctx, config, executorOverride = fake).run()
            val out = captured.toString()
            assertEquals(0, code, "turn completes · output=<<<$out>>>")
            assertEquals(3, fake.streamCalls, "blank reply triggered exactly one re-request")
            assertTrue(fake.sawNudgeBeforeReply, "the hidden nudge reached the model")
            assertTrue("summary done" in out, "the re-requested summary rendered · output=<<<$out>>>")
            assertTrue(
                "Do not leave the user on a tool call" !in out,
                "the nudge must NOT be shown in the UI · output=<<<$out>>>",
            )
        }

    @Test
    fun blank_replies_between_tool_progress_are_nudged_each_time() =
        runTest {
            // Multi-step work: the model stops (empty) after each step. It must
            // be nudged at EVERY stop, not just the first — the once-per-turn
            // bug left the user hanging on the second stop.
            val term = FakeTerminalControl()
            term.pushChars("go")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val fake = FakeExecutor(blankBetweenProgress = true)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code = AgentSession(term, ctx, config, executorOverride = fake).run()
            val out = captured.toString()
            assertEquals(0, code, "turn completes · output=<<<$out>>>")
            assertEquals(5, fake.streamCalls, "two empty stops → two re-requests")
            assertEquals(2, fake.nudgesInPrompt, "the model was nudged at BOTH stops · nudges=${fake.nudgesInPrompt}")
            assertTrue("done" in out, "final summary rendered · output=<<<$out>>>")
        }

    @Test
    fun relentlessly_blank_agent_is_bounded_by_the_nudge_cap() =
        runTest {
            // A model that only ever does tool-then-blank must not be nudged
            // forever: the per-turn cap ends the turn (routes to the prompt).
            val term = FakeTerminalControl()
            term.pushChars("go")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val fake = FakeExecutor(relentlessBlank = true)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code = AgentSession(term, ctx, config, executorOverride = fake).run()
            assertEquals(0, code, "turn ends cleanly at the cap (no hang, no error)")
            // ~ 1 initial + cap*2 calls; bounded well under maxIterations.
            assertTrue(fake.streamCalls in 30..40, "bounded by the nudge cap · calls=${fake.streamCalls}")
        }

    @Test
    fun blank_in_answer_to_the_nudge_is_nudged_again_not_dropped() =
        runTest {
            // tool → post-tool blank (nudge #1) → the model answers the nudge
            // with ANOTHER blank. That second blank comes out of callLLM, the
            // node the nudge re-requests on. Before callLLM had its own blank
            // edge it dropped straight to the prompt — flag found, no summary
            // shown. Now it's nudged again and the model finally summarizes.
            val term = FakeTerminalControl()
            term.pushChars("go")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val fake = FakeExecutor(blankInAnswerToNudge = true)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            val code = AgentSession(term, ctx, config, executorOverride = fake).run()
            val out = captured.toString()
            assertEquals(0, code, "turn completes · output=<<<$out>>>")
            assertEquals(4, fake.streamCalls, "the callLLM blank was nudged, not dropped · calls=${fake.streamCalls}")
            assertEquals(2, fake.nudgesInPrompt, "the model was nudged at BOTH blanks · nudges=${fake.nudgesInPrompt}")
            assertTrue("done" in out, "final summary rendered · output=<<<$out>>>")
            assertTrue(
                "Do not leave the user on a tool call" !in out,
                "the nudge must NOT be shown in the UI · output=<<<$out>>>",
            )
        }

    @Test
    fun history_compaction_is_announced_in_the_ui() =
        runTest {
            // A tiny context budget trips isHistoryTooBig on the first tool
            // round, routing through compressHistory. That node's summarization
            // call bypasses the assistant renderer, so without the announce node
            // the user sees an unexplained pause and vanished tool output. The
            // indicator must be printed, and the turn must still complete.
            val term = FakeTerminalControl()
            term.pushChars("go")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val fake = FakeExecutor(compactsThenText = true)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                    // Threshold = budget / 2000 = 1 message; the first tool round
                    // is already over it.
                    contextBudgetTokens = 2000,
                )
            val code = AgentSession(term, ctx, config, executorOverride = fake).run()
            val out = captured.toString()
            assertEquals(0, code, "compaction turn completes · output=<<<$out>>>")
            assertTrue("compacting history" in out, "compaction indicator shown · output=<<<$out>>>")
            assertTrue(
                "recovered after compaction" in out,
                "post-compaction reply rendered · output=<<<$out>>>",
            )
        }

    @Test
    fun tool_rendering_never_nukes_the_scrollback() =
        runTest {
            // Rendering must never clear-screen/clear-scrollback — the root of
            // the known terminal scrollback-destruction bugs. Append-only
            // rendering only ever appends + uses the spinner's erase-to-EOL.
            val term = FakeTerminalControl()
            term.pushChars("run two")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink =
                object : SuspendSink {
                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val config =
                AgentConfig(
                    baseUrl = "http://localhost:1234",
                    modelId = "fake-model",
                    apiKey = "",
                    systemPrompt = "test",
                    temperature = 0.0,
                    toolsEnabled = true,
                )
            AgentSession(term, ctx, config, executorOverride = FakeExecutor(parallelTools = true)).run()
            val out = captured.toString()
            assertTrue("[2J" !in out, "must never clear the screen")
            assertTrue("[3J" !in out, "must never clear scrollback")
        }

    @Test
    fun reasoning_streams_and_is_appended_to_history_by_default() =
        runTest {
            val fake = FakeExecutor(reasoningThenText = true)
            val out = captureRun(reasoningConfig(), fake, listOf("think", "again"))
            assertTrue("PONDERING" in out, "reasoning streamed to screen · output=<<<$out>>>")
            assertTrue("The answer is 4." in out, "answer rendered · output=<<<$out>>>")
            assertTrue(fake.sawReasoningInHistory, "reasoning echoed into history for prefix caching")
            assertEquals("PONDERING: 2+2 = 4.", fake.reasoningTextInHistory)
        }

    @Test
    fun no_thinking_hides_reasoning_display_but_still_appends_it() =
        runTest {
            val fake = FakeExecutor(reasoningThenText = true)
            val out = captureRun(reasoningConfig(showThinking = false), fake, listOf("think", "again"))
            assertTrue("PONDERING" !in out, "reasoning hidden from screen · output=<<<$out>>>")
            assertTrue("The answer is 4." in out, "answer still rendered · output=<<<$out>>>")
            assertTrue(fake.sawReasoningInHistory, "append is independent of the display toggle")
        }

    @Test
    fun no_reasoning_history_keeps_display_but_strips_from_history() =
        runTest {
            val fake = FakeExecutor(reasoningThenText = true)
            val out = captureRun(reasoningConfig(appendReasoning = false), fake, listOf("think", "again"))
            assertTrue("PONDERING" in out, "still displayed · output=<<<$out>>>")
            assertTrue(!fake.sawReasoningInHistory, "reasoning stripped from history for APIs that reject it")
        }

    @Test
    fun inline_think_tags_are_parsed_dimmed_and_answer_kept_clean() =
        runTest {
            val fake = FakeExecutor(thinkInContent = true)
            val out = captureRun(reasoningConfig(), fake, listOf("solve", "again"))
            assertTrue("weighing options" in out, "reasoning rendered · output=<<<$out>>>")
            assertTrue("The answer is 42." in out, "answer rendered · output=<<<$out>>>")
            // The tags themselves are stripped from the visible stream.
            assertTrue("<think>" !in out, "open tag not shown raw · output=<<<$out>>>")
            assertEquals("weighing options", fake.reasoningTextInHistory, "reasoning routed to history")
            assertEquals("The answer is 42.", fake.answerTextInHistory, "history answer is think-stripped")
        }

    @Test
    fun no_think_tags_passes_content_through_verbatim() =
        runTest {
            val fake = FakeExecutor(thinkInContent = true)
            val out = captureRun(reasoningConfig(parseThinkTags = false), fake, listOf("solve", "again"))
            assertTrue("<think>weighing options</think>" in out, "tags shown raw · output=<<<$out>>>")
            assertTrue(!fake.sawReasoningInHistory, "no reasoning part when parsing is off")
            assertEquals(
                "<think>weighing options</think>The answer is 42.",
                fake.answerTextInHistory,
                "content kept verbatim in history",
            )
        }

    private fun reasoningConfig(
        showThinking: Boolean = true,
        appendReasoning: Boolean = true,
        parseThinkTags: Boolean = true,
    ): AgentConfig =
        AgentConfig(
            baseUrl = "http://localhost:1234",
            modelId = "fake-model",
            apiKey = "",
            systemPrompt = "test",
            temperature = 0.0,
            toolsEnabled = true,
            showThinking = showThinking,
            appendReasoning = appendReasoning,
            parseThinkTags = parseThinkTags,
        )

    private suspend fun captureRun(
        config: AgentConfig,
        fake: FakeExecutor,
        inputs: List<String>,
    ): String {
        val term = FakeTerminalControl()
        for (line in inputs) {
            term.pushChars(line)
            term.pushKey(Key.Named.ENTER)
        }
        term.pushKey(Key.Ctrl('D'))
        val captured = StringBuilder()
        val sink =
            object : SuspendSink {
                override suspend fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    captured.append(source.readByteArray(byteCount.toInt()).decodeToString())
                }

                override suspend fun flush() {}

                override fun close() {}
            }
        val ctx = bareCommandContext(stdout = sink, stderr = sink)
        AgentSession(term, ctx, config, executorOverride = fake).run()
        return captured.toString()
    }
}
