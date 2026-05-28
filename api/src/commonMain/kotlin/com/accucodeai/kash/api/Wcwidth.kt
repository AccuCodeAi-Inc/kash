package com.accucodeai.kash.api

/**
 * Display width of a single BMP codepoint in a monospace terminal cell
 * grid. Returns `1` for normal cells, `2` for "wide" cells (East Asian
 * Wide / Fullwidth — CJK ideographs, hiragana/katakana, hangul, full-
 * width forms, etc.).
 *
 * Used everywhere the layer above a raw character stream needs to keep
 * cursor / column accounting honest:
 *  - the wasmJs `TerminalGrid` decides whether a `put(ch)` reserves 1
 *    or 2 cells and whether it must wrap before writing
 *  - the shell line editor in `BasicLineEditor` rebuilds the post-write
 *    cursor (row, col) to position the rendered prompt/buffer correctly
 *
 * Ranges are taken from Unicode TR11 (East Asian Width property),
 * trimmed to the BMP blocks that actually matter in a terminal:
 *  - Hangul Jamo (leading / vowels / trailing)
 *  - CJK Symbols, Punctuation, Hiragana, Katakana, Bopomofo,
 *    Hangul Compatibility Jamo, Kanbun, CJK Strokes,
 *    Katakana Phonetic Extensions
 *  - CJK Unified Ideographs (incl. Extension A)
 *  - Yi Syllables / Radicals
 *  - Hangul Jamo Extended-A, Hangul Syllables
 *  - CJK Compatibility Ideographs
 *  - Vertical Forms, the wide half of Halfwidth-and-Fullwidth Forms,
 *    Fullwidth currency / sign block
 *
 * Surrogate halves are out of scope — callers either fold non-renderable
 * codepoints to single-cell substitutes (see [renderableChar] in the
 * terminal painter) or treat unmatched chars as width-1. A future
 * supplementary-plane pass (emoji, CJK Extension B–F) would shift this
 * function to a `(codepoint: Int)` signature.
 */
public fun charWidth(ch: Char): Int {
    val cp = ch.code
    // Fast path — most terminal traffic is ASCII / Latin-1.
    if (cp < 0x0300) return 1
    // Zero-advance / combining (Mn, Me) and a handful of explicit
    // protocol-level "merge into previous cell" codepoints. These
    // attach to the preceding cell rather than reserving one of
    // their own, matching xterm / xterm.js / iTerm2.
    if (isCombining(ch, cp)) return 0
    if (cp < 0x1100) return 1
    return if (isWideCodepoint(cp)) 2 else 1
}

/**
 * Display width of a full Unicode scalar value (codepoint), including the
 * supplementary planes that [charWidth] can't see through a single UTF-16
 * `Char`. BMP codepoints defer to [charWidth]; astral ones are classified
 * here:
 *  - Emoji pictographs (U+1F300..U+1FAFF — emoticons, transport, symbols,
 *    Supplemental Symbols & Pictographs, Symbols Ext-A) render as wide
 *    (2 cells), matching how every terminal emulator advances them.
 *  - CJK Unified Ideographs Extension B–F (U+20000..U+3FFFD) are wide.
 *  - Everything else astral defaults to single-width.
 *
 * Used by the web `TerminalGrid` when it coalesces a surrogate pair into one
 * cell, so an emoji reserves the 2 columns it visually occupies.
 */
public fun codepointWidth(codepoint: Int): Int =
    when {
        codepoint < 0x10000 -> charWidth(codepoint.toChar())
        codepoint in 0x1F300..0x1FAFF -> 2
        codepoint in 0x20000..0x3FFFD -> 2
        else -> 1
    }

/** Sum of [charWidth] across every char of [text]. */
public fun displayWidth(text: CharSequence): Int {
    var total = 0
    for (i in text.indices) total += charWidth(text[i])
    return total
}

/**
 * True for codepoints that should attach to the *previous* cell rather
 * than reserve their own — combining marks (Unicode category Mn / Me /
 * Mc), the zero-width joiner / non-joiner, and variation selectors.
 * The caller (TerminalGrid.put) is responsible for appending [ch] to
 * the prior cell's `extras` string.
 *
 * We lean on `Char.category` to pick up most of the Unicode Mn/Me
 * range (≈700 codepoints across Latin/Greek/Cyrillic/Hebrew/Arabic/
 * Syriac/Indic/Tibetan/SE-Asia/musical/etc.) without hand-rolling the
 * table. Then we hard-code the four cases that aren't strictly Mn/Me
 * but xterm treats as zero-width: ZWNJ (U+200C), ZWJ (U+200D),
 * variation selectors (U+FE00-U+FE0F), and the ideographic VS
 * (U+E0100-U+E01EF — outside BMP, not reachable via Char anyway).
 *
 * Mc (spacing combining marks — Devanagari vowel signs etc.) are
 * technically "spacing" in Unicode terms but render with zero advance
 * in monospace grids; classic xterm and xterm.js both treat them as
 * width 0, so we do too.
 */
private fun isCombining(
    ch: Char,
    cp: Int,
): Boolean {
    // Explicit protocol codepoints (these aren't all Mn/Me).
    if (cp == 0x200C || cp == 0x200D) return true // ZWNJ, ZWJ
    if (cp in 0xFE00..0xFE0F) return true // variation selectors
    return when (ch.category) {
        CharCategory.NON_SPACING_MARK,
        CharCategory.ENCLOSING_MARK,
        CharCategory.COMBINING_SPACING_MARK,
        -> true

        else -> false
    }
}

private fun isWideCodepoint(cp: Int): Boolean =
    when {
        cp in 0x1100..0x115F -> true
        cp in 0x2E80..0x303E -> true
        cp in 0x3041..0x33FF -> true
        cp in 0x3400..0x4DBF -> true
        cp in 0x4E00..0x9FFF -> true
        cp in 0xA000..0xA4CF -> true
        cp in 0xA960..0xA97F -> true
        cp in 0xAC00..0xD7A3 -> true
        cp in 0xF900..0xFAFF -> true
        cp in 0xFE30..0xFE4F -> true
        cp in 0xFF00..0xFF60 -> true
        cp in 0xFFE0..0xFFE6 -> true
        else -> false
    }
