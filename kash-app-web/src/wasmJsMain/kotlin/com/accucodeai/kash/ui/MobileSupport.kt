@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accucodeai.kash.api.terminal.Key
import kotlinx.browser.document
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event

/*
 * Mobile / touch input support for the wasmJs terminal.
 *
 * The terminal renders to a Skiko WebGL canvas with input captured at the
 * `document` level (see [KashTerminalApp]). A canvas is not an editable DOM
 * node, so mobile browsers never raise the on-screen keyboard, and — even
 * if they did — soft keyboards deliver text through `beforeinput` /
 * composition events, not the `keydown` stream our desktop path reads. The
 * pieces here bridge that gap the same way xterm.js, Blink, and vscode.dev
 * do: an off-screen but focusable proxy element owns input/focus while the
 * canvas stays pure paint.
 *
 *  - [MobileInputProxy] — a hidden `<textarea>` that, when focused, summons
 *    the soft keyboard and feeds typed text / Enter / Backspace into the
 *    terminal via `beforeinput` + composition events.
 *  - [installMobileViewportFix] — keeps the visible terminal above the
 *    keyboard by shrinking the document to the `visualViewport` height.
 *  - [MobileAccessoryBar] / [KeyboardFab] — an on-screen key strip (Esc,
 *    Tab, Ctrl, arrows, common shell symbols) for the keys a soft keyboard
 *    can't easily produce, plus a button to summon/dismiss the keyboard.
 *
 * All of this is gated behind [isTouchDevice]; desktop is untouched.
 */

/** True on phones / tablets / touch laptops (coarse pointer present). */
internal fun isTouchDevice(): Boolean = jsIsTouchDevice()

/**
 * Off-screen `<textarea>` proxy that owns text input on touch devices.
 *
 * Why a textarea and not the canvas: focusing an editable element is the
 * only cross-browser way to raise the soft keyboard, and `.focus()` must
 * run inside a user gesture (a tap), which is why callers focus it from a
 * `touchend` handler.
 *
 * Input model — text flows through `beforeinput`, NOT `keydown`:
 *  - Soft keyboards fire `keydown` with `key === "Unidentified"` (Android)
 *    or inconsistently (iOS), so `keydown` is useless for text. `beforeinput`
 *    is reliable on both. We `preventDefault()` every input so the textarea
 *    never actually mutates — it stays a pure event source.
 *  - A persistent one-char **sentinel** lives in the textarea so that
 *    Backspace always has something to delete and therefore always fires a
 *    `deleteContentBackward` `beforeinput` (an empty field may fire nothing).
 *  - IME / pinyin / autocorrect composition is left to edit normally
 *    (we don't preventDefault while composing) and harvested on
 *    `compositionend`.
 *
 * Navigation/control keys (arrows, Tab, Esc, Ctrl-combos) still arrive via
 * the document-level `keydown` listener in [KashTerminalApp]; that handler
 * deliberately ignores text/Enter/Backspace when the event targets this
 * proxy so the two paths don't double-fire.
 */
internal class MobileInputProxy(
    private val onText: (String) -> Unit,
    private val onKey: (Key) -> Unit,
    private val onFocusChange: (Boolean) -> Unit,
) {
    // Zero-width space: invisible, deletable, doesn't perturb anything if a
    // browser ever echoes the textarea. Reset after every harvested event.
    private val sentinel = "​"

    private var composing = false

    private val el: HTMLTextAreaElement =
        (document.createElement("textarea") as HTMLTextAreaElement).also { t ->
            t.id = PROXY_ID
            // Kill every "smart" keyboard feature — a shell can't tolerate
            // autocorrect rewriting `cd` into `cd.` or capitalising flags.
            t.setAttribute("autocomplete", "off")
            t.setAttribute("autocorrect", "off")
            t.setAttribute("autocapitalize", "none")
            t.setAttribute("spellcheck", "false")
            t.setAttribute("aria-hidden", "true")
            t.tabIndex = -1
            // Off-screen but still focusable (display:none / visibility:hidden
            // would make it unfocusable and the keyboard wouldn't open). A
            // 1px transparent box pinned bottom-left, behind the canvas.
            t.style.cssText =
                "position:fixed;left:0;bottom:0;width:1px;height:1px;padding:0;border:0;" +
                "margin:0;opacity:0;z-index:-1;caret-color:transparent;resize:none;" +
                "overflow:hidden;white-space:nowrap;"
        }

    init {
        document.body?.appendChild(el)

        el.addEventListener("compositionstart", { composing = true })
        el.addEventListener("compositionend", { e ->
            composing = false
            val data = jsCompositionData(e)
            if (data.isNotEmpty()) onText(data)
            resetSentinel()
        })
        el.addEventListener("beforeinput", { e -> handleBeforeInput(e) })
        el.addEventListener("focus", { onFocusChange(true) })
        el.addEventListener("blur", { onFocusChange(false) })
    }

    private fun handleBeforeInput(e: Event) {
        // While composing (IME / multi-step autocorrect) let the field edit
        // freely; we harvest the final text on compositionend.
        if (composing) return
        val type = jsInputType(e)
        val data = jsInputData(e)
        when (type) {
            "insertText",
            "insertReplacementText",
            "insertFromComposition",
            -> {
                if (data.isNotEmpty()) onText(data)
                e.preventDefault()
            }

            "insertLineBreak", "insertParagraph" -> {
                onKey(Key.Named.ENTER)
                e.preventDefault()
            }

            "deleteContentBackward", "deleteWordBackward", "deleteSoftLineBackward" -> {
                onKey(Key.Named.BACKSPACE)
                e.preventDefault()
            }

            "deleteContentForward", "deleteWordForward" -> {
                onKey(Key.Named.DELETE)
                e.preventDefault()
            }

            // Native paste fires its own `paste` event which the document
            // listener already turns into Key.Paste — swallow it here so it
            // doesn't double up, and keep the textarea clean.
            "insertFromPaste" -> {
                e.preventDefault()
            }

            else -> {
                e.preventDefault()
            }
        }
    }

    /** Re-seed the sentinel and park the caret after it. */
    private fun resetSentinel() {
        el.value = sentinel
        runCatching { el.setSelectionRange(sentinel.length, sentinel.length) }
    }

    /** Focus the proxy (raising the soft keyboard). Call from a gesture. */
    fun focus() {
        resetSentinel()
        runCatching { el.focus() }
        jsKeyboardShow()
    }

    /** Drop focus, dismissing the soft keyboard. */
    fun blur() {
        runCatching { el.blur() }
        jsKeyboardHide()
    }

    fun dispose() {
        runCatching { el.blur() }
        runCatching { el.remove() }
    }

    internal companion object {
        const val PROXY_ID: String = "kash-mobile-input"
    }
}

/** True when [event] originated from the [MobileInputProxy] textarea. */
internal fun isMobileProxyEvent(event: Event): Boolean = jsEventTargetId(event) == MobileInputProxy.PROXY_ID

// ---------------------------------------------------------------------------
// Compose UI: accessory key bar + keyboard FAB
// ---------------------------------------------------------------------------

/**
 * On-screen key strip shown above the soft keyboard. Carries the keys a
 * mobile keyboard makes hard or impossible: Esc, Tab, a sticky Ctrl, the
 * arrow keys, and a handful of shell-critical symbols. Scrolls horizontally
 * so it fits the narrowest phone.
 *
 * `Ctrl` is *sticky*: tap it (it highlights), then the next character —
 * whether tapped here or typed on the soft keyboard — is sent as Ctrl-<x>.
 * The latch lives in the caller so a soft-keyboard letter can consume it.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
internal fun MobileAccessoryBar(
    ctrlLatched: Boolean,
    onToggleCtrl: () -> Unit,
    onKey: (Key) -> Unit,
    onScalar: (Int) -> Unit,
    onHideKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color(0xFF161616))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccKey("esc") { onKey(Key.Named.ESC) }
        AccKey("tab") { onKey(Key.Named.TAB) }
        AccKey("ctrl", active = ctrlLatched) { onToggleCtrl() }
        AccKey("◂") { onKey(Key.Named.LEFT) }
        AccKey("▴") { onKey(Key.Named.UP) }
        AccKey("▾") { onKey(Key.Named.DOWN) }
        AccKey("▸") { onKey(Key.Named.RIGHT) }
        // Shell-critical symbols, in code-point form so the sticky-Ctrl
        // latch in onScalar can fold them if Ctrl is held.
        for (sym in listOf('|', '/', '\\', '-', '~', '*', '$', ':', '"', '\'')) {
            AccKey(sym.toString()) { onScalar(sym.code) }
        }
        AccKey("⌄") { onHideKeyboard() }
    }
}

/** A single tappable key in the accessory bar. */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun AccKey(
    label: String,
    active: Boolean = false,
    onTap: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .padding(horizontal = 3.dp, vertical = 6.dp)
                .size(width = 40.dp, height = 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (active) Color(0xFF3A3A5A) else Color(0xFF2A2A2A))
                .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = label,
            color = if (active) Color(0xFFB9C2FF) else Color(0xFFEAEAEA),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            fontSize = 15.sp,
        )
    }
}

/**
 * Floating button shown bottom-right while the keyboard is hidden, so a
 * touch user has an obvious way to summon it (tapping the terminal also
 * works, but this is discoverable).
 */
@Composable
@Suppress("ktlint:standard:function-naming")
internal fun KeyboardFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xCC2A2A40))
                .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = "⌨",
            color = Color(0xFFEAEAEA),
            fontSize = 22.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// JS interop
// ---------------------------------------------------------------------------

private fun jsIsTouchDevice(): Boolean =
    js(
        """{
            try {
                return (('ontouchstart' in window) ||
                        (navigator.maxTouchPoints && navigator.maxTouchPoints > 0) ||
                        (window.matchMedia && window.matchMedia('(pointer: coarse)').matches)) ? true : false;
            } catch (_) { return false; }
        }""",
    )

private fun jsInputType(e: Event): String =
    js("""{ try { return e && e.inputType ? e.inputType : ''; } catch (_) { return ''; } }""")

private fun jsInputData(e: Event): String =
    js("""{ try { return (e && e.data != null) ? e.data : ''; } catch (_) { return ''; } }""")

private fun jsCompositionData(e: Event): String =
    js("""{ try { return (e && e.data != null) ? e.data : ''; } catch (_) { return ''; } }""")

private fun jsEventTargetId(e: Event): String =
    js("""{ try { return (e && e.target && e.target.id) ? e.target.id : ''; } catch (_) { return ''; } }""")

// Number of active touch points on a touch Event (touchstart/move use
// `touches`, touchend uses `changedTouches`).
internal fun jsTouchCount(e: Event): Int =
    js(
        """{
            try {
                if (e.touches && e.touches.length) return e.touches.length;
                if (e.changedTouches && e.changedTouches.length) return e.changedTouches.length;
                return 0;
            } catch (_) { return 0; }
        }""",
    )

internal fun jsFirstTouchX(e: Event): Double =
    js(
        """{
            try {
                var t = (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]);
                return t ? t.clientX : 0;
            } catch (_) { return 0; }
        }""",
    )

internal fun jsFirstTouchY(e: Event): Double =
    js(
        """{
            try {
                var t = (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]);
                return t ? t.clientY : 0;
            } catch (_) { return 0; }
        }""",
    )

/**
 * Bind the document size to the `visualViewport` so the soft keyboard
 * overlays from the bottom instead of hiding the prompt behind it. When the
 * keyboard opens, `visualViewport.height` shrinks; we mirror that onto the
 * `<html>`/`<body>` height, which makes Compose's canvas re-measure to fewer
 * rows (a SIGWINCH-equivalent) so the live line sits just above the keyboard.
 *
 * Idempotent and touch-only — installed once; harmless to call repeatedly.
 */
internal fun installMobileViewportFix() {
    js(
        """{
            try {
                if (window.__kashVVBound || !window.visualViewport) return;
                window.__kashVVBound = true;
                var vv = window.visualViewport;
                var apply = function () {
                    var h = Math.round(vv.height);
                    if (h > 0) {
                        document.documentElement.style.height = h + 'px';
                        document.body.style.height = h + 'px';
                        // REQUIRED, not optional: Compose's ComposeViewport
                        // recomputes its canvas size ONLY on a `window`
                        // 'resize' event (it then reads body.clientHeight) —
                        // it has no ResizeObserver on the body. Without this
                        // synthetic dispatch the canvas never shrinks and the
                        // keyboard would overlay the prompt + accessory bar.
                        window.dispatchEvent(new Event('resize'));
                    }
                };
                vv.addEventListener('resize', apply);
                vv.addEventListener('scroll', apply);
                apply();
            } catch (_) { /* best effort */ }
        }""",
    )
}

// Best-effort explicit keyboard show/hide via the VirtualKeyboard API
// (Chromium/Android only). Focusing the proxy already raises the keyboard on
// every browser; these just help where the API is present. No-ops elsewhere.
private fun jsKeyboardShow() {
    js(
        """{
            try { if (navigator.virtualKeyboard && navigator.virtualKeyboard.show) navigator.virtualKeyboard.show(); }
            catch (_) {}
        }""",
    )
}

private fun jsKeyboardHide() {
    js(
        """{
            try { if (navigator.virtualKeyboard && navigator.virtualKeyboard.hide) navigator.virtualKeyboard.hide(); }
            catch (_) {}
        }""",
    )
}

/** Iterate the Unicode scalar values of [s], decoding surrogate pairs. */
internal fun forEachScalar(
    s: String,
    action: (Int) -> Unit,
) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
            action(cp)
            i += 2
        } else {
            action(c.code)
            i++
        }
    }
}
