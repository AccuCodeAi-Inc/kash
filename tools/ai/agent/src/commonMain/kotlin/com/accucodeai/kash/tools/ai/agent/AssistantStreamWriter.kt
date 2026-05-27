package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8

/**
 * Line-buffered streaming renderer for one assistant turn. Eats text
 * fragments as they arrive from the LLM, decorates them with a thin
 * subset of markdown, writes the rendered output straight to a sink.
 *
 * Rendering today:
 *  - Fenced code blocks (`` ``` ` ` … ``` ``) get a header line, a left
 *    gutter on every interior row, and a closing rule. The fence's
 *    language tag (`` ```kotlin ``) is shown in the header.
 *  - ATX headings (`# H1`, `## H2`, `### H3`) get bold styling.
 *  - Everything else streams as-is.
 *
 * Why streaming-aware: assistant deltas arrive as arbitrary chunks
 * (often a token or two each). To detect a fence line we need to wait
 * for the newline that terminates it — buffering one partial line at
 * a time. The rest streams through with minimal latency.
 *
 * Lifecycle:
 *  - Construct once per assistant turn
 *  - [append] for each TextDelta
 *  - [flush] exactly once before the next prompt — drains the partial
 *    line buffer and closes any unterminated code block so the output
 *    doesn't leak into the user's next prompt line.
 *
 * Color/glyph palette comes from [AgentGlyphs] so wasmJs falls back to
 * ASCII box characters where dingbats would otherwise tofu.
 *
 * The first time any content is written, we emit the assistant gutter
 * (`✻ `) ahead of it — matches the previous behavior of the plain
 * streamer this replaces.
 */
internal class AssistantStreamWriter(
    private val out: SuspendSink,
    private val glyphs: AgentGlyphs,
    private val palette: Palette = Palette.Default,
) {
    /** ANSI bundle so we don't repeat the escape strings here. */
    internal data class Palette(
        val reset: String,
        val dim: String,
        val bold: String,
        val cyan: String,
    ) {
        companion object {
            // Plain strings — AgentSession passes its own with the leading
            // ESC byte filled in. Default is identity for tests.
            val Default: Palette = Palette("", "", "", "")
        }
    }

    private val buf = StringBuilder()
    private var inCodeBlock = false
    private var codeLang: String? = null
    private var anyContentWritten = false

    /** True iff at least one rendered line (or partial fragment) was emitted. */
    val hasContent: Boolean get() = anyContentWritten

    /**
     * Append the next chunk of assistant text. Multi-line chunks are
     * processed line-by-line; the trailing partial line stays in the
     * buffer until the next [append] (or [flush]) completes it. Outside
     * of a code block, partial fragments are streamed eagerly so the
     * user sees per-token motion as text arrives.
     */
    suspend fun append(text: String) {
        if (text.isEmpty()) return
        buf.append(text)
        // Process every complete line.
        while (true) {
            val nl = buf.indexOf('\n')
            if (nl < 0) break
            val line = buf.substring(0, nl)
            buf.deleteRange(0, nl + 1)
            renderCompleteLine(line)
        }
        // If we're outside a code block and the buffered tail has no
        // chance of being a fence, flush it now to keep the perceived
        // latency low. Inside a code block we hold the partial line
        // (we want to prefix it with the gutter once we know it's not
        // the closing fence).
        if (!inCodeBlock && buf.isNotEmpty() && !buf.startsWith("`")) {
            val tail = buf.toString()
            buf.clear()
            ensureGutter()
            out.writeUtf8(tail)
            anyContentWritten = true
        }
    }

    /**
     * End of stream. Flushes any partial line and closes an
     * unterminated code block. Idempotent — calling twice is safe.
     */
    suspend fun flush() {
        if (buf.isNotEmpty()) {
            // Final partial line — render whatever's left as if it
            // were a complete line, minus the trailing newline.
            val tail = buf.toString()
            buf.clear()
            renderFinalPartial(tail)
        }
        if (inCodeBlock) {
            closeCodeBlock()
            inCodeBlock = false
            codeLang = null
        }
        // Always leave the cursor on its own column-0 line so the next
        // prompt / tool indicator starts cleanly.
        if (anyContentWritten) {
            out.writeUtf8("\n")
        }
    }

    private suspend fun renderCompleteLine(line: String) {
        val trimmed = line.trimStart()
        if (isFenceLine(trimmed)) {
            if (inCodeBlock) {
                closeCodeBlock()
                inCodeBlock = false
                codeLang = null
            } else {
                codeLang = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
                openCodeBlock(codeLang)
                inCodeBlock = true
            }
            return
        }
        if (inCodeBlock) {
            writeCodeLine(line)
            return
        }
        val headingLevel = atxHeadingLevel(line)
        if (headingLevel > 0) {
            writeHeading(line, headingLevel)
            return
        }
        writePlainLine(line)
    }

    private suspend fun renderFinalPartial(tail: String) {
        // Partial line at end of stream. Most models terminate code blocks
        // with `\`\`\`` followed by a newline, but some emit the closing
        // fence with no trailing newline — recognize that case explicitly
        // so the fence doesn't render as a stray code line.
        val trimmed = tail.trimStart()
        if (isFenceLine(trimmed)) {
            if (inCodeBlock) {
                closeCodeBlock()
                inCodeBlock = false
                codeLang = null
            }
            // Opening a code block on the last partial line would leave
            // it unterminated; skip the open and let flush() handle EOF.
            return
        }
        if (inCodeBlock) {
            writeCodeLinePartial(tail)
        } else {
            val headingLevel = atxHeadingLevel(tail)
            if (headingLevel > 0) {
                writeHeadingPartial(tail, headingLevel)
            } else {
                ensureGutter()
                out.writeUtf8(tail)
                anyContentWritten = true
            }
        }
    }

    private fun isFenceLine(trimmed: String): Boolean = trimmed.startsWith("```")

    /** ATX heading level (1..3), or 0 if the line isn't a heading. */
    private fun atxHeadingLevel(line: String): Int {
        var i = 0
        while (i < line.length && i < 3 && line[i] == '#') i++
        if (i == 0) return 0
        if (i >= line.length || line[i] != ' ') return 0
        return i
    }

    // No box framing here — that treatment is reserved for write_file
    // diffs, where the boundary actually carries information. For inline
    // prose code blocks we just shift color so the lines visually separate
    // from prose without dragging the user's eye to a rectangle.

    private suspend fun openCodeBlock(lang: String?) {
        // Fences are the model's structural marker; we don't echo them.
        // No-op other than the state flip (handled in renderCompleteLine).
        // We deliberately do NOT print a header line — the cyan color
        // shift is enough signal, and a header bar competes for attention
        // with the content underneath.
        @Suppress("UNUSED_PARAMETER")
        lang
    }

    private suspend fun closeCodeBlock() {
        // No-op — see [openCodeBlock]. The trailing newline is supplied
        // by whatever wrote the last code line.
    }

    private suspend fun writeCodeLine(line: String) {
        ensureGutterOwnLine()
        out.writeUtf8("${palette.cyan}$line${palette.reset}\n")
        anyContentWritten = true
    }

    private suspend fun writeCodeLinePartial(line: String) {
        ensureGutterOwnLine()
        // No trailing newline — caller (flush()) appends one.
        out.writeUtf8("${palette.cyan}$line${palette.reset}")
        anyContentWritten = true
    }

    /**
     * Drop the assistant gutter on its own line if we haven't written
     * anything yet for this turn. Used by code lines so the colored
     * code doesn't start at column 0 with no indication that it's the
     * assistant talking.
     */
    private suspend fun ensureGutterOwnLine() {
        if (anyContentWritten) return
        out.writeUtf8("${palette.dim}${glyphs.assistantGutter}${palette.reset}\n")
        anyContentWritten = true
    }

    private suspend fun writeHeading(
        line: String,
        level: Int,
    ) {
        ensureGutter()
        val body = line.substring(level + 1)
        out.writeUtf8("${palette.bold}$body${palette.reset}\n")
        anyContentWritten = true
    }

    private suspend fun writeHeadingPartial(
        tail: String,
        level: Int,
    ) {
        ensureGutter()
        val body = tail.substring(level + 1)
        out.writeUtf8("${palette.bold}$body${palette.reset}")
        anyContentWritten = true
    }

    private suspend fun writePlainLine(line: String) {
        ensureGutter()
        out.writeUtf8("$line\n")
        anyContentWritten = true
    }

    /**
     * Emit the assistant gutter (`✻ `) once, ahead of the first content
     * of this turn. Idempotent after the first call.
     */
    private suspend fun ensureGutter() {
        if (anyContentWritten) return
        out.writeUtf8("${palette.dim}${glyphs.assistantGutter}${palette.reset} ")
    }
}
