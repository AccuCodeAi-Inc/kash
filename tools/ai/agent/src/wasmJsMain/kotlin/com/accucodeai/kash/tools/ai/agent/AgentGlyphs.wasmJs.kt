package com.accucodeai.kash.tools.ai.agent

/**
 * In a `compose-for-web` canvas terminal we can't count on the host font
 * shipping dingbats. Use plain ASCII so nothing renders as `.notdef`
 * boxes — the classic `| / - \` spinner reads as motion just fine.
 */
internal actual val agentGlyphs: AgentGlyphs =
    AgentGlyphs(
        userPrompt = ">",
        assistantGutter = "*",
        toolBullet = "*",
        toolResultArrow = "->",
        pickerArrow = ">",
        pickerCheck = "*",
        spinnerFrames = listOf("|", "/", "-", "\\"),
        codeBoxTopLeft = "+",
        codeBoxBottomLeft = "+",
        codeBoxHorizontal = "-",
        codeBoxVertical = "|",
    )
