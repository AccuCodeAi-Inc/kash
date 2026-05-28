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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the strict-turn-taking behavior of the agent: while a
 * stream is running, no `readLine` consumer is active, so any
 * keystrokes that arrive in `term`'s queue should be either drained
 * (regular typing — would otherwise auto-replay into the next prompt)
 * or honored (Ctrl-C / ESC cancel the stream; Ctrl-D / Paste survive
 * to the next turn).
 *
 * These tests focus on the drain plumbing, not on rendered output —
 * the latter is covered by [AgentLoopTest]. We use deterministic
 * scenarios: either a fake stream that returns immediately, or one
 * that suspends in `awaitCancellation()` until the drain coroutine
 * cancels it on Ctrl-C.
 */
class StrictTurnTakingTest {
    // ---- drainKeys() mechanism --------------------------------------

    @Test
    fun drainKeysDropsCharAndRequeuesKeepers() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("ab")
            term.pushKey(Key.Paste("pasted"))
            term.pushKey(Key.Ctrl('C'))
            term.pushChars("c")

            val discarded =
                term.drainKeys(keep = { k ->
                    k is Key.Paste || (k is Key.Ctrl && k.letter == 'C')
                })

            // a, b, c — three Key.Char entries — went to the discard pile.
            assertEquals(3, discarded.size)
            assertTrue(discarded.all { it is Key.Char })
            // Order-preserved re-queue: Paste arrived before Ctrl-C, so
            // the next two reads come back in that order.
            val first = term.readKey()
            val second = term.readKey()
            assertTrue(first is Key.Paste, "paste re-queued first; got $first")
            assertTrue(second is Key.Ctrl && second.letter == 'C', "ctrl-c re-queued second; got $second")
        }

    // ---- Integration with AgentSession ------------------------------

    /**
     * Fake client whose stream emits a fixed text reply and then ends.
     * `streamCalls` counts how many times the stream was started.
     */
    private class ImmediateFakeClient(
        private val replyText: String = "ok",
    ) : ChatClient {
        var streamCalls = 0

        override fun stream(
            model: String,
            messages: List<ChatMessage>,
            temperature: Double?,
            tools: List<ToolDescriptor>?,
        ): Flow<ChatStreamEvent> =
            flow {
                streamCalls++
                if (replyText.isNotEmpty()) emit(ChatStreamEvent.TextDelta(replyText))
                emit(ChatStreamEvent.End("stop"))
            }

        override suspend fun complete(
            model: String,
            messages: List<ChatMessage>,
            temperature: Double?,
            tools: List<ToolDescriptor>?,
        ): ChatResponse =
            ChatResponse(
                choices =
                    listOf(
                        ChatResponse.Choice(
                            message = ChatMessage.Assistant(content = "summary"),
                            finishReason = "stop",
                        ),
                    ),
            )

        override suspend fun listModels(): List<String> = listOf("fake-model")

        override fun close() = Unit
    }

    /**
     * Fake client whose stream suspends in `awaitCancellation()` after
     * emitting a single text delta. Lets us deterministically test the
     * Ctrl-C interrupt path: the test pre-queues Ctrl-C, the agent's
     * drain coroutine reads it, the surrounding scope is cancelled,
     * and `awaitCancellation()` throws.
     *
     * `streamStartedSignal` completes the moment the stream's first
     * `emit` runs — exposed so future tests that need to push keys
     * AFTER the stream starts can `await` on it.
     */
    private class SuspendingFakeClient : ChatClient {
        var streamCalls = 0
        var streamCancelled = false
        val streamStartedSignal: CompletableDeferred<Unit> = CompletableDeferred()

        override fun stream(
            model: String,
            messages: List<ChatMessage>,
            temperature: Double?,
            tools: List<ToolDescriptor>?,
        ): Flow<ChatStreamEvent> =
            flow {
                streamCalls++
                emit(ChatStreamEvent.TextDelta("partial"))
                streamStartedSignal.complete(Unit)
                try {
                    awaitCancellation()
                } catch (t: Throwable) {
                    streamCancelled = true
                    throw t
                }
            }

        override suspend fun complete(
            model: String,
            messages: List<ChatMessage>,
            temperature: Double?,
            tools: List<ToolDescriptor>?,
        ): ChatResponse =
            ChatResponse(
                choices =
                    listOf(
                        ChatResponse.Choice(
                            message = ChatMessage.Assistant(content = "summary"),
                            finishReason = "stop",
                        ),
                    ),
            )

        override suspend fun listModels(): List<String> = listOf("fake-model")

        override fun close() = Unit
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

    private fun cfg() =
        AgentConfig(
            baseUrl = "http://localhost:1234",
            modelId = "fake-model",
            apiKey = "",
            systemPrompt = "test",
            temperature = 0.0,
            toolsEnabled = false,
            contextBudgetTokens = AgentConfig.DEFAULT_CONTEXT_BUDGET,
            showThinking = false,
            appendReasoning = false,
            parseThinkTags = false,
        )

    @Test
    fun typeAheadCharsDuringStreamAreDropped() =
        runTest {
            // Pre-queue: submit "hi", then leftover "X" type-ahead,
            // then Ctrl-D to quit cleanly. The X simulates a user
            // typing in the gap between Enter and the stream starting
            // — strict-turn-taking should drop it so it doesn't
            // auto-submit at the next prompt.
            val term = FakeTerminalControl()
            term.pushChars("hi")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Char('X'.code))
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink = capturingSink(captured)
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val fake = ImmediateFakeClient(replyText = "ok")

            AgentSession(term, ctx, cfg(), clientOverride = fake).run()

            // Stream ran once for "hi".
            assertEquals(1, fake.streamCalls)
            // Agent rendered the assistant reply.
            assertTrue("ok" in captured.toString(), "reply rendered · output=<<<$captured>>>")
            // Queue is empty at the end — Ctrl-D quit the loop cleanly
            // and nothing leaked. (If X had leaked, it would have
            // become the buffer of a second readLine call, which then
            // would have submitted at the next Enter and triggered a
            // second stream call — but there's no second Enter, so
            // the loop would deadlock instead. Either way: failing
            // either of `streamCalls==1` or "agent.run() returns" is
            // a regression.)
            val leftover = term.keys.tryReceive()
            assertTrue(leftover.isFailure, "key queue should be empty after agent.run() · got ${leftover.getOrNull()}")
        }

    @Test
    fun ctrlCDuringStreamCancelsTheStream() =
        runTest {
            // Pre-queue: "hi" + Enter + Ctrl-C + Ctrl-D. The pre-stream
            // drain keeps Ctrl-C and Ctrl-D (they survive the predicate);
            // the drain coroutine inside the stream window picks up
            // Ctrl-C, calls `scope.cancel()`, the surrounding
            // `coroutineScope` throws CancellationException, the agent
            // catches it and posts a partial turn, then the loop runs
            // readNextLine again which sees Ctrl-D and quits.
            //
            // We don't assert `fake.streamCancelled` because runTest's
            // virtual scheduler may dispatch drainJob before the
            // stream collect even subscribes — in that path the stream
            // body never reaches `awaitCancellation()`. The
            // user-observable contract is: agent.run() returns, no
            // hang, no leaked keys. Both are what we check.
            val term = FakeTerminalControl()
            term.pushChars("hi")
            term.pushKey(Key.Named.ENTER)
            term.pushKey(Key.Ctrl('C'))
            term.pushKey(Key.Ctrl('D'))

            val captured = StringBuilder()
            val sink = capturingSink(captured)
            val ctx = bareCommandContext(stdout = sink, stderr = sink)
            val fake = SuspendingFakeClient()

            AgentSession(term, ctx, cfg(), clientOverride = fake).run()

            // Agent returned without hanging. Key queue is empty —
            // Ctrl-D was honored by the second readNextLine as EOF.
            val leftover = term.keys.tryReceive()
            assertTrue(leftover.isFailure, "key queue should be empty after agent.run() · got ${leftover.getOrNull()}")
        }
}
