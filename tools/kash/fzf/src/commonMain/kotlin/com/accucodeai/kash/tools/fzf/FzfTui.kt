package com.accucodeai.kash.tools.fzf

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl

/**
 * Result of running the TUI loop.
 *
 *  - [Accepted] — user pressed Enter; `lines` is what should go to stdout.
 *  - [Cancelled] — user pressed Esc/Ctrl-C/Ctrl-G; exit code 130, no output.
 *  - [Empty] — nothing on stdin and no candidates; exit code 1.
 */
internal sealed interface FzfOutcome {
    data class Accepted(
        val lines: List<String>,
    ) : FzfOutcome

    data object Cancelled : FzfOutcome

    data object Empty : FzfOutcome
}

internal data class FzfOptions(
    val multi: Boolean,
    val initialQuery: String,
    val prompt: String,
    val caseSensitive: Boolean?,
)

/**
 * Terminal lifecycle + main key-dispatch loop. Mirrors the structure of
 * `NanoEditor`: alt-screen + raw mode under try/finally, repaint every
 * frame, single suspend on `readKey`.
 */
internal class FzfTui(
    private val term: TerminalControl,
    private val candidates: List<String>,
    private val options: FzfOptions,
) {
    private val renderer = FzfRenderer(term, options.prompt, options.multi)

    suspend fun run(): FzfOutcome {
        if (candidates.isEmpty()) return FzfOutcome.Empty
        var state =
            FzfState.initial(
                candidates = candidates,
                initialQuery = options.initialQuery,
                caseSensitive = options.caseSensitive,
            )
        term.enterRawMode()
        term.useAlternateScreen(true)
        try {
            while (true) {
                renderer.draw(state)
                val key = term.readKey()
                val result = handle(state, key)
                when (result) {
                    is StepResult.Continue -> state = result.next
                    is StepResult.Done -> return result.outcome
                }
            }
        } finally {
            try {
                term.useAlternateScreen(false)
            } catch (_: Throwable) {
            }
            try {
                term.exitRawMode()
            } catch (_: Throwable) {
            }
            try {
                term.flush()
            } catch (_: Throwable) {
            }
        }
    }

    private sealed interface StepResult {
        data class Continue(
            val next: FzfState,
        ) : StepResult

        data class Done(
            val outcome: FzfOutcome,
        ) : StepResult
    }

    private fun handle(
        state: FzfState,
        key: Key,
    ): StepResult =
        when (key) {
            is Key.Char -> {
                StepResult.Continue(state.appendCodepoint(key.codepoint))
            }

            Key.Named.BACKSPACE -> {
                StepResult.Continue(state.deleteLastCodepoint())
            }

            Key.Named.UP -> {
                StepResult.Continue(state.moveCursor(-1))
            }

            Key.Named.DOWN -> {
                StepResult.Continue(state.moveCursor(+1))
            }

            Key.Named.PGUP -> {
                StepResult.Continue(state.moveCursor(-pageSize()))
            }

            Key.Named.PGDN -> {
                StepResult.Continue(state.moveCursor(+pageSize()))
            }

            Key.Named.HOME -> {
                StepResult.Continue(state.moveCursor(-state.filtered.size))
            }

            Key.Named.END -> {
                StepResult.Continue(state.moveCursor(+state.filtered.size))
            }

            Key.Named.LEFT, Key.Named.RIGHT -> {
                StepResult.Continue(state)
            }

            // v1: query cursor not supported

            Key.Named.TAB -> {
                if (options.multi) {
                    // Toggle current, advance cursor — matches real fzf UX.
                    StepResult.Continue(state.toggleSelectionAtCursor().moveCursor(+1))
                } else {
                    StepResult.Continue(state)
                }
            }

            Key.Named.ENTER -> {
                StepResult.Done(FzfOutcome.Accepted(state.accept(options.multi)))
            }

            Key.Named.ESC -> {
                StepResult.Done(FzfOutcome.Cancelled)
            }

            Key.Named.ALT_ENTER -> {
                StepResult.Continue(state)
            }

            Key.Named.DELETE -> {
                StepResult.Continue(state)
            }

            // no-op v1

            is Key.Function -> {
                StepResult.Continue(state)
            }

            is Key.Paste -> {
                var s = state
                for (ch in key.text) {
                    if (ch == '\n' || ch == '\r') continue
                    s = s.appendCodepoint(ch.code)
                }
                StepResult.Continue(s)
            }

            is Key.PrintAbove -> {
                // Out-of-band banner from a host-side hook (drag-drop,
                // etc.). fzf owns the full screen via the alt-screen
                // buffer; surfacing it here would corrupt the rendered
                // candidate list. Ignore.
                StepResult.Continue(state)
            }

            is Key.Ctrl -> {
                handleCtrl(state, key.letter)
            }
        }

    private fun handleCtrl(
        state: FzfState,
        letter: Char,
    ): StepResult =
        when (letter) {
            'C', 'G', 'Q' -> StepResult.Done(FzfOutcome.Cancelled)
            'H' -> StepResult.Continue(state.deleteLastCodepoint())
            'U' -> StepResult.Continue(state.clearQuery())
            'W' -> StepResult.Continue(state.deleteLastWord())
            'P', 'K' -> StepResult.Continue(state.moveCursor(-1))
            'N', 'J' -> StepResult.Continue(state.moveCursor(+1))
            'M' -> StepResult.Done(FzfOutcome.Accepted(state.accept(options.multi)))
            else -> StepResult.Continue(state)
        }

    private fun pageSize(): Int = (term.size().rows - 2).coerceAtLeast(1)
}

/** Write the accept lines to stdout, newline-separated, with trailing newline per line. */
internal suspend fun writeAccepted(
    sink: SuspendSink,
    lines: List<String>,
) {
    for (l in lines) sink.writeLine(l)
}
