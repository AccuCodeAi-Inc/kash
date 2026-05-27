package com.accucodeai.kash.tools.ai.agent

/**
 * JVM ships into a real terminal emulator (iTerm2 / Terminal.app /
 * Alacritty / WezTerm / Windows Terminal). Dingbats render fine.
 */
internal actual val agentGlyphs: AgentGlyphs =
    AgentGlyphs(
        userPrompt = "❯",
        assistantGutter = "✻",
        toolBullet = "·",
        toolResultArrow = "→",
        pickerArrow = "▸",
        pickerCheck = "✓",
        spinnerFrames = listOf("✻", "✦", "✧", "✶", "✷", "✸"),
        codeBoxTopLeft = "╭",
        codeBoxBottomLeft = "╰",
        codeBoxHorizontal = "─",
        codeBoxVertical = "│",
    )
