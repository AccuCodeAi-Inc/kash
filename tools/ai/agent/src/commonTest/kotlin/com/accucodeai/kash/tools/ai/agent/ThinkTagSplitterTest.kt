package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.tools.ai.agent.ThinkTagSplitter.Seg
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The splitter is the riskiest piece (streaming tag boundaries), so it gets
 * the bulk of the coverage as a pure unit.
 */
class ThinkTagSplitterTest {
    /** Feed each chunk through push(), then flush(); concatenate all segments. */
    private fun run(vararg chunks: String): List<Seg> {
        val s = ThinkTagSplitter()
        val out = mutableListOf<Seg>()
        for (c in chunks) out += s.push(c)
        out += s.flush()
        return out
    }

    /** Collapse adjacent same-kind segments so assertions don't depend on chunking. */
    private fun List<Seg>.normalized(): List<Seg> {
        val out = mutableListOf<Seg>()
        for (seg in this) {
            val last = out.lastOrNull()
            when {
                last is Seg.Answer && seg is Seg.Answer -> {
                    out[out.lastIndex] = Seg.Answer(last.text + seg.text)
                }

                last is Seg.Reasoning && seg is Seg.Reasoning -> {
                    out[out.lastIndex] =
                        Seg.Reasoning(last.text + seg.text)
                }

                else -> {
                    out += seg
                }
            }
        }
        return out
    }

    private fun answer(text: String) = Seg.Answer(text)

    private fun reasoning(text: String) = Seg.Reasoning(text)

    @Test
    fun plain_text_with_no_tags_is_all_answer() {
        assertEquals(listOf(answer("hello world")), run("hello world").normalized())
    }

    @Test
    fun single_think_span_then_answer() {
        val out = run("<think>pondering</think>the answer").normalized()
        assertEquals(listOf(reasoning("pondering"), answer("the answer")), out)
    }

    @Test
    fun think_at_start_with_no_trailing_answer() {
        assertEquals(listOf(reasoning("just thinking")), run("<think>just thinking</think>").normalized())
    }

    @Test
    fun open_tag_split_across_chunks() {
        val out = run("<thi", "nk>deep</think>done").normalized()
        assertEquals(listOf(reasoning("deep"), answer("done")), out)
    }

    @Test
    fun close_tag_split_across_chunks() {
        val out = run("<think>deep</thi", "nk>done").normalized()
        assertEquals(listOf(reasoning("deep"), answer("done")), out)
    }

    @Test
    fun single_char_chunks_still_parse() {
        val whole = "ab<think>xy</think>cd"
        val s = ThinkTagSplitter()
        val out = mutableListOf<Seg>()
        for (ch in whole) out += s.push(ch.toString())
        out += s.flush()
        assertEquals(
            listOf(answer("ab"), reasoning("xy"), answer("cd")),
            out.normalized(),
        )
    }

    @Test
    fun text_before_and_after_think() {
        val out = run("answer1 <think>r</think> answer2").normalized()
        assertEquals(listOf(answer("answer1 "), reasoning("r"), answer(" answer2")), out)
    }

    @Test
    fun multiple_think_spans() {
        val out = run("a<think>r1</think>b<think>r2</think>c").normalized()
        assertEquals(
            listOf(answer("a"), reasoning("r1"), answer("b"), reasoning("r2"), answer("c")),
            out,
        )
    }

    @Test
    fun unclosed_think_flushes_as_reasoning() {
        val out = run("<think>thinking but the stream ended").normalized()
        assertEquals(listOf(reasoning("thinking but the stream ended")), out)
    }

    @Test
    fun held_partial_tag_at_eos_is_emitted_literally() {
        // A lone "<thi" never completes → it's literal answer text, not swallowed.
        assertEquals(listOf(answer("hello <thi")), run("hello <thi").normalized())
    }

    @Test
    fun lone_close_tag_without_open_is_literal_answer() {
        // Outside a think span we only hunt for the open tag, so a stray
        // </think> is ordinary text.
        assertEquals(listOf(answer("a</think>b")), run("a</think>b").normalized())
    }

    @Test
    fun angle_bracket_that_is_not_a_tag_passes_through() {
        assertEquals(listOf(answer("1 < 2 and 3 > 2")), run("1 < 2 and 3 > 2").normalized())
    }

    @Test
    fun back_to_back_tags_yield_empty_reasoning_and_continue() {
        // <think></think> immediately → no reasoning text, answer follows.
        assertEquals(listOf(answer("x")), run("<think></think>x").normalized())
    }

    @Test
    fun custom_tags_are_honored() {
        val s = ThinkTagSplitter(openTag = "<reason>", closeTag = "</reason>")
        val out = (s.push("pre<reason>why</reason>post") + s.flush())
        assertEquals(
            listOf(answer("pre"), reasoning("why"), answer("post")),
            out,
        )
    }
}
