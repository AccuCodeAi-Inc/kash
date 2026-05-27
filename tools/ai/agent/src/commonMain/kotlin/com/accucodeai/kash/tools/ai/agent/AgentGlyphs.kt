package com.accucodeai.kash.tools.ai.agent

/**
 * Per-target glyph palette for the inline TUI. Dingbat codepoints (`✻`,
 * `❯`, `▸`, …) render beautifully in a real terminal emulator with a font
 * like Cascadia Code or JetBrains Mono, but a stock browser canvas font
 * shipped with `compose-for-web` tends to draw `.notdef` boxes for them.
 *
 * Each target supplies a palette tuned to what its host font actually
 * has. JVM gets the dingbats; wasmJs falls back to safe ASCII so the
 * agent stays legible in the browser kash terminal.
 */
internal expect val agentGlyphs: AgentGlyphs

internal data class AgentGlyphs(
    /** Marker drawn at the left of the user's input line — e.g. `❯` / `>`. */
    val userPrompt: String,
    /** Gutter ahead of streamed assistant text — e.g. `✻` / `*`. */
    val assistantGutter: String,
    /** Bullet ahead of each tool-call indicator — e.g. `·` / `-`. */
    val toolBullet: String,
    /** Arrow ahead of the tool-result preview — e.g. `→` / `->`. */
    val toolResultArrow: String,
    /** Caret ahead of the currently-selected row in the model picker. */
    val pickerArrow: String,
    /** Mark drawn next to the final `selected:` line after the picker exits. */
    val pickerCheck: String,
    /** Spinner cycle — ticked at roughly one frame every 80 ms. */
    val spinnerFrames: List<String>,
    /**
     * Fenced-code-block frame characters. Used by the streaming markdown
     * renderer to draw a top corner / horizontal rule / left gutter /
     * bottom corner around assistant-emitted ``` … ``` blocks.
     */
    val codeBoxTopLeft: String,
    val codeBoxBottomLeft: String,
    val codeBoxHorizontal: String,
    val codeBoxVertical: String,
)
