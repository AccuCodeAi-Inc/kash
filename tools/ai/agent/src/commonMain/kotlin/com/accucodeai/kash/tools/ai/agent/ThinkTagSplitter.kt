package com.accucodeai.kash.tools.ai.agent

/**
 * Incremental splitter that pulls `<think>…</think>` reasoning spans out of a
 * streamed text channel. Koog 1.0's OpenAI chat-completions streaming has no
 * reasoning field (see the notes in [AgentSession.buildModel]), so reasoning
 * models that emit think-tags inline in their content are our only way to
 * surface reasoning on that endpoint — we parse the tags ourselves.
 *
 * Stateful and order-dependent: feed every content delta to [push] in arrival
 * order, then call [flush] once the stream ends. Each call returns zero or
 * more [Seg]s already classified as [Seg.Answer] (visible reply) or
 * [Seg.Reasoning] (the thinking to dim / route to history). Tags that straddle
 * a chunk boundary (`"<thi"` + `"nk>"`) are handled by holding back the
 * longest suffix that could still become the open/close tag until the next
 * chunk resolves it.
 *
 * Not thread-safe; one instance per stream. The tag pair is configurable for
 * models that use a different marker, defaulting to the de-facto `<think>` /
 * `</think>` used by Qwen, DeepSeek-distill, and friends.
 */
internal class ThinkTagSplitter(
    private val openTag: String = "<think>",
    private val closeTag: String = "</think>",
) {
    sealed interface Seg {
        val text: String

        /** Visible reply text (outside any think span). */
        data class Answer(
            override val text: String,
        ) : Seg

        /** Reasoning text (inside a `<think>…</think>` span). */
        data class Reasoning(
            override val text: String,
        ) : Seg
    }

    private var buf = ""
    private var inThink = false

    /** Feed one content chunk; returns the segments resolvable so far. */
    fun push(chunk: String): List<Seg> {
        if (chunk.isEmpty()) return emptyList()
        buf += chunk
        val out = ArrayList<Seg>()
        while (true) {
            val tag = if (inThink) closeTag else openTag
            val idx = buf.indexOf(tag)
            if (idx >= 0) {
                if (idx > 0) out.add(seg(buf.substring(0, idx)))
                buf = buf.substring(idx + tag.length)
                inThink = !inThink
                // Loop: the remainder may hold more tags.
            } else {
                // No complete tag. Emit everything except a trailing run that
                // could still grow into the expected tag next chunk.
                val hold = partialSuffixLen(buf, tag)
                val emitLen = buf.length - hold
                if (emitLen > 0) {
                    out.add(seg(buf.substring(0, emitLen)))
                    buf = buf.substring(emitLen)
                }
                break
            }
        }
        return out
    }

    /**
     * Drain whatever is buffered at end-of-stream. A held partial tag (or an
     * unclosed `<think>` with no closer) is emitted literally in the current
     * mode — better to show stray text than to silently swallow it.
     */
    fun flush(): List<Seg> {
        if (buf.isEmpty()) return emptyList()
        val s = seg(buf)
        buf = ""
        return listOf(s)
    }

    private fun seg(s: String): Seg = if (inThink) Seg.Reasoning(s) else Seg.Answer(s)

    private companion object {
        /** Longest k in [1, tag.length-1] where [buf]'s last k chars == tag's first k. */
        fun partialSuffixLen(
            buf: String,
            tag: String,
        ): Int {
            val max = minOf(tag.length - 1, buf.length)
            for (k in max downTo 1) {
                if (buf.regionMatches(buf.length - k, tag, 0, k)) return k
            }
            return 0
        }
    }
}
