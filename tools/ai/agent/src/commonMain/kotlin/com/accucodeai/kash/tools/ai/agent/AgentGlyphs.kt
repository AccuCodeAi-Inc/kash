package com.accucodeai.kash.tools.ai.agent

/**
 * Glyph palette for the inline TUI. Dingbat codepoints (`✻`, `❯`, `▸`,
 * …) and box-drawing characters render via the host terminal's font.
 *
 * Both targets are now covered by the same palette:
 *  - JVM ships into a real terminal emulator (iTerm2 / Terminal.app /
 *    Alacritty / WezTerm / Windows Terminal) — any modern monospace
 *    font carries these.
 *  - wasmJs's browser terminal uses bundled JetBrains Mono (primary)
 *    with Noto Sans Mono CJK SC preloaded as a glyph-fallback face.
 *    JetBrains Mono carries every codepoint in this palette plus the
 *    U+2500 box-drawing and U+2580 block-elements ranges; CJK falls
 *    back to Noto. The historical ASCII fallback existed before any
 *    font was bundled and is no longer needed.
 */
internal val agentGlyphs: AgentGlyphs =
    AgentGlyphs(
        userPrompt = "❯",
        // ◆ (U+25C6 Black Diamond) — present in JetBrains Mono. The
        // old ✻ (U+273B) tofu'd on the web build because the bundled
        // font didn't carry it; the diamond reads as a distinct
        // gutter mark without clashing with the ❯ user-prompt or
        // ▸ picker arrow.
        assistantGutter = "◆",
        toolBullet = "·",
        toolResultArrow = "→",
        pickerArrow = "▸",
        pickerCheck = "✓",
        // Single-corner quadrant rotation. All four codepoints are
        // in the U+2596..U+259F Block Elements range that JetBrains Mono
        // carries with crisp pixel-aligned metrics. Reads as a
        // clockwise dot orbit at the SPINNER_TICK_MS
        // cadence (~600 ms full cycle). Replaces the old ✻ ✦ ✧ ✶ ✷ ✸ star
        // set, of which only ✶ was actually carried by the bundled
        // fonts — the rest tofu'd, which is why only one spinner
        // frame ever showed up on the web build.
        spinnerFrames = listOf("▖", "▘", "▝", "▗"),
        codeBoxTopLeft = "╭",
        codeBoxBottomLeft = "╰",
        codeBoxHorizontal = "─",
        codeBoxVertical = "│",
    )

internal data class AgentGlyphs(
    /** Marker drawn at the left of the user's input line — e.g. `❯`. */
    val userPrompt: String,
    /** Gutter ahead of streamed assistant text — e.g. `✻`. */
    val assistantGutter: String,
    /** Bullet ahead of each tool-call indicator — e.g. `·`. */
    val toolBullet: String,
    /** Arrow ahead of the tool-result preview — e.g. `→`. */
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
