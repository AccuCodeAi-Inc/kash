package com.accucodeai.kash.api.terminal

/**
 * Line-editing front-end for the interactive shell. Reads a (possibly
 * multi-line) statement from the user, handling cursor movement, history,
 * and continuation-prompt redraw.
 *
 * Today the only impl is `BasicLineEditor` (`:corevm`) — pure Kotlin on
 * top of [TerminalControl], KMP-friendly. The interface remains as a seam
 * so a fancier editor (vi mode, fuzzy history search, completion menus)
 * can slot in later without touching `KashShellCommand`'s REPL loop, and
 * so future platform-specific implementations (a JS editor wrapping
 * xterm.js, etc.) have a target shape.
 *
 * The interactive shell constructs `BasicLineEditor` inline on the
 * terminal handle attached to fd 0's [OpenFileDescription] — see
 * `KashShellCommand.runInteractive`. Future fancier editors can simply
 * swap that construction site.
 */
public interface LineEditor {
    /**
     * Read one (possibly multi-line) input statement.
     *
     * @param prompt the primary prompt string (typically `PS1`, e.g.
     *   `"kash:/home/user$ "`). Written verbatim — caller pre-expands.
     * @param continuationPrompt the secondary prompt (typically `PS2`, e.g.
     *   `"> "`), shown when [isComplete] returns false and the editor reads
     *   another line.
     * @param isComplete tested after each `\n`. Returning `true` finishes
     *   the read. The accumulated buffer (joined with `\n`) is the argument.
     *   The shell's parser supplies this — typically `Parser(s).parse() !is
     *   Incomplete`. Pure-data callback; the editor MUST NOT mutate shell
     *   state.
     */
    public suspend fun readLine(
        prompt: String,
        continuationPrompt: String,
        isComplete: (accumulated: String) -> Boolean,
    ): LineEditorResult

    /**
     * Record a successfully-executed line in history. The editor decides
     * persistence (in-memory ring, file, both, none). The shell calls this
     * after a non-empty line has been parsed and dispatched — duplicate
     * filtering, blank skipping, and history-expansion are the editor's
     * concern.
     */
    public suspend fun addHistory(entry: String)

    /**
     * Read-only snapshot of the editor's in-memory history, oldest entry
     * first. Backs the bash `history` builtin (which lists, indexes, and
     * deletes from this buffer) and `fc -l`. Editors with no history
     * return an empty list. Pure read — does not mutate editor state.
     */
    public suspend fun history(): List<String> = emptyList()
}

/**
 * Outcome of one [LineEditor.readLine] call.
 */
public sealed interface LineEditorResult {
    /** A complete (possibly multi-line) input. Newlines are `\n`-separated. */
    public data class Line(
        val text: String,
    ) : LineEditorResult

    /** Ctrl-D at the primary prompt with an empty buffer. The shell exits. */
    public data object Eof : LineEditorResult

    /** Ctrl-C at the prompt. The shell discards partial input and reprompts. */
    public data object Interrupted : LineEditorResult
}
