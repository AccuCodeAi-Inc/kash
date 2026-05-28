package com.accucodeai.kash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.accucodeai.kash.webres.JetBrainsMono_Bold
import com.accucodeai.kash.webres.JetBrainsMono_Regular
import com.accucodeai.kash.webres.Res
import org.jetbrains.compose.resources.Font
import kotlin.math.floor

/**
 * Selection range in absolute row coordinates. Rows are numbered
 * 0..scrollback.size-1 for scrollback rows, then scrollback.size+0..
 * scrollback.size+visible.size-1 for the live viewport. The renderer
 * normalizes ordering — start may be after end.
 */
public data class SelectionRange(
    val startAbsRow: Int,
    val startCol: Int,
    val endAbsRow: Int,
    val endCol: Int,
)

/**
 * Cell-grid renderer for the wasmJs Compose terminal.
 *
 * Measurement walks a long monospace run and divides to get the per-cell
 * advance — a single-`M` measure picks up side bearings and inflates the
 * cell width. The derived `(cols, rows)` are pushed to [onCellSize] each
 * layout, and per-cell `(cellW, cellH)` are pushed to [onCellMetrics] so
 * the parent can translate pointer coordinates into cell coordinates for
 * mouse selection.
 *
 * Painting walks the snapshot's visible rows (optionally shifted into
 * scrollback when [scrollOffsetRows] > 0). The integer part picks the row
 * window; the fractional part shifts the content sub-row for smooth
 * scrolling. Background rects only drawn for non-default-bg cells; glyphs
 * drawn cell-by-cell. Inverted cursor cell painted last so it always wins
 * z-order, and only when showing the live viewport (offset == 0) AND
 * [cursorBlinkOn] is true.
 *
 * When [selection] is non-null, a translucent rect highlights the
 * selected region (rows/cells in absolute row coordinates — see
 * [SelectionRange]).
 *
 * When the offset rounds to N > 0 rows, a one-line dim bar at the bottom
 * of the viewport announces "↓ N lines below — End to return".
 */
@Composable
@Suppress("ktlint:standard:function-naming")
public fun TerminalCanvas(
    snapshot: ComposeTerminal.Snapshot,
    scrollOffsetRows: Float,
    cursorBlinkOn: Boolean,
    selection: SelectionRange?,
    fontSizeSp: Int,
    background: Color,
    foreground: Color,
    onCellSize: (cols: Int, rows: Int) -> Unit,
    onCellMetrics: (cellW: Float, cellH: Float) -> Unit,
    /**
     * Canvas top-left in CSS pixels (window-relative). Selection-drag
     * uses this to translate raw `MouseEvent.clientX/clientY` into the
     * canvas's local coordinate space — without it, a TopAppBar or
     * any other chrome above the canvas pushes the click into the
     * wrong cell.
     */
    onCanvasOrigin: (xPxCss: Float, yPxCss: Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // Primary terminal face: JetBrains Mono (static Regular + Bold instances —
    // we ship static, not variable, since Skiko/Wasm mis-renders variable
    // fonts). It covers Latin / Greek / Cyrillic / box-drawing. CJK glyphs it
    // lacks fall back to Noto Sans Mono CJK SC, which `KashWorkspace` preloads
    // into the Skiko fallback collection before the terminal first paints
    // (Compose Web has no system fonts, so the fallback chain is whatever we
    // preload). Both faces are monospace, so the grid's per-column pixel
    // pinning stays exact across the fallback boundary.
    val terminalFont =
        FontFamily(
            Font(Res.font.JetBrainsMono_Regular, weight = FontWeight.Normal, style = FontStyle.Normal),
            Font(Res.font.JetBrainsMono_Bold, weight = FontWeight.Bold, style = FontStyle.Normal),
        )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle =
        TextStyle(
            fontFamily = terminalFont,
            fontSize = fontSizeSp.sp,
            color = foreground,
            letterSpacing = 0.sp,
        )

    BoxWithConstraints(
        modifier =
            modifier.onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                val d = density.density
                onCanvasOrigin(pos.x / d, pos.y / d)
            },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val sampleLen = 32
        val sample = remember(sampleLen) { "M".repeat(sampleLen) }
        val metrics =
            remember(measurer, baseStyle, sample) {
                measurer.measure(AnnotatedString(sample), baseStyle)
            }
        val cellW = (metrics.size.width.toFloat() / sampleLen).coerceAtLeast(1f)
        val cellH =
            metrics.size.height
                .toFloat()
                .coerceAtLeast(1f)
        val cols = (widthPx / cellW).toInt().coerceAtLeast(1)
        val rows = (heightPx / cellH).toInt().coerceAtLeast(1)

        LaunchedEffect(cols, rows) { onCellSize(cols, rows) }
        // Hand the parent CSS-pixel cell sizes (not physical-pixel). Mouse
        // events deliver clientX/clientY in CSS pixels; our internal
        // measurements are in physical pixels (post-density). Dividing by
        // density keeps the two coordinate spaces aligned — without this
        // selection-drag on a HiDPI screen lands at 1/density of the
        // intended cell.
        val d = density.density
        LaunchedEffect(cellW, cellH, d) { onCellMetrics(cellW / d, cellH / d) }

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTerminal(
                snapshot = snapshot,
                scrollOffsetRows = scrollOffsetRows,
                cursorBlinkOn = cursorBlinkOn,
                selection = selection,
                cellW = cellW,
                cellH = cellH,
                viewportRows = rows,
                background = background,
                foreground = foreground,
                style = baseStyle,
                measurer = measurer,
            )
        }
    }
}

private fun DrawScope.drawTerminal(
    snapshot: ComposeTerminal.Snapshot,
    scrollOffsetRows: Float,
    cursorBlinkOn: Boolean,
    selection: SelectionRange?,
    cellW: Float,
    cellH: Float,
    viewportRows: Int,
    background: Color,
    foreground: Color,
    style: TextStyle,
    measurer: TextMeasurer,
) {
    drawRect(color = background, size = Size(size.width, size.height))

    val sb = snapshot.scrollback
    val vis = snapshot.visible
    // Fractional rows-above-live offset. The integer part `n` selects the
    // row window; `frac` is the sub-row pixel shift that makes scrolling
    // smooth instead of quantized to whole rows.
    val offset = if (snapshot.onAlt) 0f else scrollOffsetRows.coerceIn(0f, sb.size.toFloat())
    val n = floor(offset).toInt()
    val frac = offset - n

    // Absolute row coord space: 0..sb.size-1 = scrollback, then visible.
    // Two regimes:
    //   - safeOffset == 0 → bottom-anchor `vis` to the bottom of the
    //     viewport. The grid is the source of truth for "how many live
    //     rows exist"; if the canvas has more rows than the grid (the
    //     transient between a window resize and the grid receiving the
    //     new size from `onCellSize`), the top of the viewport stays
    //     blank rather than pulling scrollback up — that's what was
    //     making resize look like a "scrollback reset".
    //   - safeOffset > 0 → slide the viewport window N rows up through
    //     absolute-row space. This is what lets the user scroll back
    //     through the whole scrollback ring, not just one viewport.
    val totalRows = sb.size + vis.size
    val viewportBottomAbs: Int
    val firstAbsRow: Int
    if (n == 0) {
        viewportBottomAbs = sb.size + vis.size
        firstAbsRow = sb.size + vis.size - viewportRows
    } else {
        viewportBottomAbs = totalRows - n
        firstAbsRow = (viewportBottomAbs - viewportRows).coerceAtLeast(0)
    }
    // One extra slot above the top row (slot 0 == view row -1) so a
    // partially revealed older row can slide in when frac > 0. Slot i maps
    // to view row (i - 1).
    val slots = viewportRows + 1
    val rowSources = arrayOfNulls<Array<Cell>>(slots)
    val rowDim = BooleanArray(slots)
    for (i in 0 until slots) {
        val absRow = firstAbsRow + (i - 1)
        if (absRow < 0 || absRow >= viewportBottomAbs || absRow >= totalRows) continue
        if (absRow < sb.size) {
            rowSources[i] = sb[absRow]
            rowDim[i] = true
        } else {
            rowSources[i] = vis.getOrNull(absRow - sb.size)
            rowDim[i] = false
        }
    }

    val showScrollIndicator = n > 0 && !snapshot.onAlt

    // Content passes shift down by the sub-row fraction so scrolling is
    // smooth. clipRect confines the top extra row's overhang to the canvas
    // so it can't bleed up over the tab bar above. The indicator bar (drawn
    // later, outside this block) sits at a fixed viewport position and its
    // solid fill occludes whatever content lands beneath it.
    clipRect {
        translate(top = frac * cellH) {
            // Background pass.
            for (i in 0 until slots) {
                val src = rowSources[i] ?: continue
                paintRowBackground(src, i - 1, cellW, cellH, foreground, background)
            }

            // Selection overlay (under glyphs so text stays readable).
            if (selection != null && !snapshot.onAlt) {
                paintSelection(
                    selection,
                    firstAbsRow,
                    viewportRows,
                    cellW,
                    cellH,
                    foreground,
                    rowSources,
                )
            }

            // Glyph pass.
            for (i in 0 until slots) {
                val src = rowSources[i] ?: continue
                paintRowGlyphs(src, i - 1, cellW, cellH, foreground, background, style, measurer, dim = rowDim[i])
            }
        }
    }

    // Cursor — only when fully anchored on live content (offset == 0).
    if (snapshot.cursorVisible && cursorBlinkOn && n == 0 && frac == 0f) {
        val cRow = snapshot.cursorRow.coerceIn(0, vis.size - 1)
        var cCol = snapshot.cursorCol.coerceAtLeast(0)
        // If the cursor landed on a continuation cell of a wide char,
        // snap to the leader so the inverted rect covers both halves
        // and the glyph paints right. Symmetric: if it's already on
        // the leader, paint a 2-cell-wide rect.
        val rowCells = vis.getOrNull(cRow)
        val landed = rowCells?.getOrNull(cCol)
        if (landed?.width == 0 && cCol > 0) cCol--
        val cellAtCursor = rowCells?.getOrNull(cCol)
        val cursorCells = if (cellAtCursor?.width == 2) 2 else 1
        val viewRow = (sb.size + cRow) - firstAbsRow
        if (viewRow in 0 until viewportRows) {
            val topLeft = Offset(cCol * cellW, viewRow * cellH)
            drawRect(color = foreground, topLeft = topLeft, size = Size(cellW * cursorCells, cellH))
            val text = renderableCellText(cellAtCursor?.ch ?: ' ', cellAtCursor?.extras ?: "")
            if (text != " ") {
                val layout =
                    measurer.measure(
                        AnnotatedString(text),
                        style.copy(color = background),
                    )
                drawText(layout, topLeft = topLeft)
            }
        }
    }

    // Scrolled-back indicator — solid bar at the bottom, high-contrast
    // text. Painted last so nothing overlaps it.
    if (showScrollIndicator) {
        val msg = "↓ $n lines below — End/PgDn to return"
        val barTop = (viewportRows - 1) * cellH
        drawRect(
            color = foreground,
            topLeft = Offset(0f, barTop),
            size = Size(size.width, cellH),
        )
        val layout =
            measurer.measure(
                AnnotatedString(msg),
                style.copy(color = background),
            )
        drawText(layout, topLeft = Offset(cellW, barTop))
    }
}

private fun DrawScope.paintSelection(
    sel: SelectionRange,
    firstAbsRow: Int,
    viewportRows: Int,
    cellW: Float,
    cellH: Float,
    foreground: Color,
    rowSources: Array<Array<Cell>?>,
) {
    // Normalize ordering.
    val (a0, c0, a1, c1) =
        if (sel.startAbsRow < sel.endAbsRow ||
            (sel.startAbsRow == sel.endAbsRow && sel.startCol <= sel.endCol)
        ) {
            quad(sel.startAbsRow, sel.startCol, sel.endAbsRow, sel.endCol)
        } else {
            quad(sel.endAbsRow, sel.endCol, sel.startAbsRow, sel.startCol)
        }
    val tint = foreground.copy(alpha = 0.30f)
    for (absRow in a0..a1) {
        val viewRow = absRow - firstAbsRow
        if (viewRow !in 0 until viewportRows) continue
        // Selection on a row spans cols [lo .. hi].
        val lo = if (absRow == a0) c0 else 0
        val hi = if (absRow == a1) c1 else Int.MAX_VALUE
        // When the end column [hi] sits on a wide leader, extend the
        // tint to cover its continuation cell so the highlighted span
        // visually wraps the whole glyph. Without this the right half
        // of a wide char at the selection's tail end paints unhighlighted
        // even though `selectionText` copies the char in full.
        val rowCells = rowSources.getOrNull(viewRow + 1)
        val hiEffective =
            if (hi != Int.MAX_VALUE && rowCells != null && hi in rowCells.indices &&
                rowCells[hi].width == 2
            ) {
                hi + 1
            } else {
                hi
            }
        val pxLo = lo * cellW
        val pxHi = if (hiEffective == Int.MAX_VALUE) size.width else (hiEffective + 1) * cellW
        drawRect(
            color = tint,
            topLeft = Offset(pxLo, viewRow * cellH),
            size = Size((pxHi - pxLo).coerceAtLeast(0f), cellH),
        )
    }
}

private data class Quad(
    val a: Int,
    val b: Int,
    val c: Int,
    val d: Int,
)

private fun quad(
    a: Int,
    b: Int,
    c: Int,
    d: Int,
) = Quad(a, b, c, d)

private fun DrawScope.paintRowBackground(
    row: Array<Cell>,
    viewRow: Int,
    cellW: Float,
    cellH: Float,
    defaultFg: Color,
    defaultBg: Color,
) {
    var c = 0
    while (c < row.size) {
        val bg = effectiveBg(row[c].style, defaultFg, defaultBg)
        if (bg == null) {
            c++
            continue
        }
        var end = c + 1
        while (end < row.size && effectiveBg(row[end].style, defaultFg, defaultBg) == bg) end++
        drawRect(
            color = bg,
            topLeft = Offset(c * cellW, viewRow * cellH),
            size = Size((end - c) * cellW, cellH),
        )
        c = end
    }
}

private fun effectiveBg(
    s: CellStyle,
    defaultFg: Color,
    defaultBg: Color,
): Color? =
    when {
        s.inverse -> s.fg ?: defaultFg
        s.bg != null -> s.bg
        else -> null
    }

/**
 * Substitute cell content that Skia would either fail to shape or that
 * would explode the glyph atlas. Control codes, C1 controls, DEL, and
 * lone surrogate halves all render as tofu and (more importantly) each
 * unique codepoint occupies a permanent slot in the GPU glyph atlas —
 * `cat /dev/random` would otherwise push thousands of distinct
 * codepoints into the atlas and drive WebGL's `texSubImage2D` past its
 * 2 GiB upload limit. Folding them all to `·` caps the universe.
 */
private fun renderableChar(c: Char): Char =
    when {
        c < ' ' -> '·'
        c == '' -> '·'
        c in ''..'' -> '·'
        c in '\uD800'..'\uDFFF' -> '·'
        else -> c
    }

/**
 * Render text for one cell (`ch + extras`), preserving valid astral scalars.
 * A high+low surrogate pair is a real codepoint (emoji / CJK Ext-B) and is
 * kept verbatim so the shaper can fall back to the emoji / CJK font; lone
 * surrogates and control codes still fold to `·` via [renderableChar].
 *
 * [renderableChar] operates on a single `Char`, so it would fold *both*
 * halves of an emoji. Cells that can carry a surrogate pair (the wide /
 * combining-mark / cursor cells) must render through here instead; a batched
 * width-1 run is BMP-only and stays on [renderableChar].
 */
private fun renderableCellText(
    ch: Char,
    extras: String,
): String {
    if (extras.isEmpty()) return renderableChar(ch).toString()
    val s = ch.toString() + extras
    return buildString {
        var i = 0
        while (i < s.length) {
            val c0 = s[i]
            if (c0.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                append(c0)
                append(s[i + 1])
                i += 2
            } else {
                append(renderableChar(c0))
                i++
            }
        }
    }
}

/**
 * Walk the row in runs of cells that share a [CellStyle], batching
 * consecutive width-1 cells into one [TextMeasurer.measure] +
 * [drawText] call so we don't pay 1920 per-cell measures per frame on
 * a 24×80 viewport.
 *
 * Wide characters (width=2) **break the batch and draw individually**
 * at their own `col × cellW` origin. This is the same approach every
 * serious terminal renderer takes (Warp, Contour, xterm.js WebGL,
 * alacritty, kitty): the fallback CJK font's "double-width" glyph
 * advance is *meant* to equal `2 × cellW`, but font hinting and
 * sub-pixel rounding leave a sub-pixel mismatch that accumulates
 * across a long CJK run, producing a visible drift between glyph
 * positions and the cell grid (and therefore the selection rect,
 * cursor box, etc.). Pinning each wide glyph to its grid column
 * eliminates the accumulation — only the per-glyph rendering inside
 * the 2-cell box can shift, and the next glyph starts fresh at the
 * correct origin. ASCII runs still batch normally; in pure-Latin
 * rows the cost is identical to the old single-call path.
 *
 * Trailing default-style spaces are trimmed off the tail of any
 * batched run (nothing to paint).
 */
private fun DrawScope.paintRowGlyphs(
    row: Array<Cell>,
    viewRow: Int,
    cellW: Float,
    cellH: Float,
    defaultFg: Color,
    defaultBg: Color,
    base: TextStyle,
    measurer: TextMeasurer,
    dim: Boolean,
) {
    val y = viewRow * cellH

    /** Resolve the row-cell style → effective TextStyle for this paint. */
    fun resolveStyle(s: CellStyle): TextStyle {
        val fg =
            when {
                s.inverse -> s.bg ?: defaultBg
                s.fg != null -> s.fg
                else -> defaultFg
            }
        val drawColor = if (dim || s.dim) fg.copy(alpha = 0.55f) else fg
        return base.copy(
            color = drawColor,
            fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (s.underline) TextDecoration.Underline else null,
        )
    }

    val sb = StringBuilder()
    var c = 0
    while (c < row.size) {
        val here = row[c]
        // Width=0 continuation cells are owned by the leader at c-1 —
        // they paint nothing of their own. Skip past them.
        if (here.width == 0) {
            c++
            continue
        }
        if (here.width == 2) {
            // Pin the wide glyph to this grid column. One measure +
            // one drawText per wide char — the only way to keep the
            // cell grid and CJK glyph positions perfectly aligned
            // across a long row. Trailing combining marks (extras)
            // ride along with the base glyph.
            val style = resolveStyle(here.style)
            val text = renderableCellText(here.ch, here.extras)
            val layout = measurer.measure(AnnotatedString(text), style)
            drawText(layout, topLeft = Offset(c * cellW, y))
            c += 2
            continue
        }
        // width == 1: batch all consecutive same-style width-1 cells.
        // A cell carrying combining-mark `extras` could in principle
        // stay in the batch (Skia shapes them with the preceding
        // base char), but if the cluster grows past a single grapheme
        // its layout advance can drift from cellW the same way wide
        // chars do. Cells with non-empty extras are therefore drawn
        // individually, just like width=2 cells.
        if (here.extras.isNotEmpty()) {
            val style = resolveStyle(here.style)
            val text = renderableCellText(here.ch, here.extras)
            val layout = measurer.measure(AnnotatedString(text), style)
            drawText(layout, topLeft = Offset(c * cellW, y))
            c++
            continue
        }
        val runStyle = here.style
        val runStart = c
        sb.clear()
        while (c < row.size && row[c].width == 1 && row[c].extras.isEmpty() && row[c].style == runStyle) {
            sb.append(renderableChar(row[c].ch))
            c++
        }
        // Nothing to paint for a default-style run of spaces.
        if (runStyle == CellStyle.Default) {
            var end = sb.length
            while (end > 0 && sb[end - 1] == ' ') end--
            if (end == 0) continue
            sb.setLength(end)
        }
        val style = resolveStyle(runStyle)
        val layout = measurer.measure(AnnotatedString(sb.toString()), style)
        drawText(layout, topLeft = Offset(runStart * cellW, y))
    }
}
