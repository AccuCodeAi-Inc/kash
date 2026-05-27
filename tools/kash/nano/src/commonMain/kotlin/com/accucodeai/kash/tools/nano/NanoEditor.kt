package com.accucodeai.kash.tools.nano

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.fs.FileSystem

/**
 * Editor entry point. Owns the terminal lifecycle (raw mode + alt screen)
 * and the main key-dispatch loop. Returns an exit code.
 *
 *  - 0 on clean exit (saved, or quit-without-save confirmed)
 *  - 1 on an unrecoverable I/O error
 */
internal class NanoEditor(
    private val term: TerminalControl,
    private val fs: FileSystem,
    private val cwd: String,
    private val initialFile: String?,
) {
    private val renderer = NanoRenderer(term)
    private val minibuffer = NanoMinibuffer(term)
    private var notice: String = ""
    private var clipboard: String? = null

    suspend fun run(): Int {
        val initial = loadInitial() ?: return 1
        var buf = initial
        term.enterRawMode()
        term.useAlternateScreen(true)
        try {
            while (true) {
                renderer.draw(buf, notice)
                notice = ""
                val key = term.readKey()
                val next = handle(buf, key) ?: return 0
                buf = next.normalized()
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

    /**
     * @return next buffer state, or null if the editor should exit cleanly.
     */
    private suspend fun handle(
        buf: NanoBuffer,
        key: Key,
    ): NanoBuffer? =
        when (key) {
            is Key.Char -> {
                buf.insertCodepoint(key.codepoint)
            }

            Key.Named.UP -> {
                buf.moveUp()
            }

            Key.Named.DOWN -> {
                buf.moveDown()
            }

            Key.Named.LEFT -> {
                buf.moveLeft()
            }

            Key.Named.RIGHT -> {
                buf.moveRight()
            }

            Key.Named.HOME -> {
                buf.moveLineHome()
            }

            Key.Named.END -> {
                buf.moveLineEnd()
            }

            Key.Named.PGUP -> {
                pageUp(buf)
            }

            Key.Named.PGDN -> {
                pageDown(buf)
            }

            Key.Named.BACKSPACE -> {
                buf.backspace()
            }

            Key.Named.DELETE -> {
                buf.deleteForward()
            }

            Key.Named.ENTER -> {
                buf.splitLine()
            }

            Key.Named.TAB -> {
                buf.insertCodepoint('\t'.code)
            }

            Key.Named.ESC, Key.Named.ALT_ENTER -> {
                buf
            }

            is Key.Function -> {
                buf
            }

            is Key.Paste -> {
                // Bracketed paste delivered as one chunk. Insert codepoints
                // verbatim — embedded newlines stay literal so a pasted
                // multi-line buffer doesn't get treated as one giant line.
                key.text.fold(buf) { b, ch -> b.insertCodepoint(ch.code) }
            }

            is Key.PrintAbove -> {
                // Out-of-band banner content from a host-side hook
                // (drag-and-drop, etc). Nano draws its own full-screen
                // UI in the alt-screen buffer; injecting a line into
                // the buffer body would silently corrupt the user's
                // edit. Ignore — the host can re-deliver via a
                // diagnostic-line write if it really wants to surface
                // something here.
                buf
            }

            is Key.Ctrl -> {
                when (key.letter) {
                    'X' -> {
                        if (handleQuit(buf)) null else buf
                    }

                    'O' -> {
                        handleSave(buf, promptFilename = true)
                    }

                    'S' -> {
                        handleSave(buf, promptFilename = false)
                    }

                    'W' -> {
                        handleSearch(buf)
                    }

                    'G' -> {
                        showHelp(buf)
                    }

                    'L' -> {
                        buf
                    }

                    // forced redraw — next iteration repaints
                    'A' -> {
                        buf.moveLineHome()
                    }

                    'E' -> {
                        buf.moveLineEnd()
                    }

                    'K' -> {
                        val (next, cut) = buf.cutLine()
                        clipboard = cut
                        next
                    }

                    'U' -> {
                        clipboard?.let { buf.pasteLineAbove(it) } ?: buf
                    }

                    'P' -> {
                        buf.moveUp()
                    }

                    'N' -> {
                        buf.moveDown()
                    }

                    'F' -> {
                        buf.moveRight()
                    }

                    'B' -> {
                        buf.moveLeft()
                    }

                    else -> {
                        buf
                    }
                }
            }
        }

    private fun pageUp(buf: NanoBuffer): NanoBuffer {
        val height = (term.size().rows - 4).coerceAtLeast(1)
        var b = buf
        repeat(height) { b = b.moveUp() }
        return b
    }

    private fun pageDown(buf: NanoBuffer): NanoBuffer {
        val height = (term.size().rows - 4).coerceAtLeast(1)
        var b = buf
        repeat(height) { b = b.moveDown() }
        return b
    }

    /** @return true to exit the editor. */
    private suspend fun handleQuit(buf: NanoBuffer): Boolean {
        if (!buf.dirty) return true
        val answer = minibuffer.confirm("Save modified buffer? ")
        return when (answer) {
            true -> {
                val saved = handleSave(buf, promptFilename = buf.filename == null)
                // Only exit if save succeeded (dirty cleared).
                !saved.dirty
            }

            false -> {
                true
            }

            // discard
            null -> {
                false
            } // cancelled, return to editor
        }
    }

    private suspend fun handleSave(
        buf: NanoBuffer,
        promptFilename: Boolean,
    ): NanoBuffer {
        val path =
            if (promptFilename || buf.filename == null) {
                minibuffer.readLine("File Name to Write: ", initial = buf.filename ?: "")
                    ?: run {
                        notice = "[ Cancelled ]"
                        return buf
                    }
            } else {
                buf.filename
            }
        if (path.isEmpty()) {
            notice = "[ Cancelled ]"
            return buf
        }
        val resolved = resolve(path)
        return try {
            val joined = buf.lines.joinToString("\n") + "\n"
            fs.writeBytes(resolved, joined.encodeToByteArray())
            notice = "[ Wrote ${buf.lines.size} line${if (buf.lines.size == 1) "" else "s"} ]"
            buf.copy(filename = path, dirty = false)
        } catch (t: Throwable) {
            notice = "Error writing $path: ${t.message}"
            buf
        }
    }

    private suspend fun handleSearch(buf: NanoBuffer): NanoBuffer {
        val q =
            minibuffer.readLine("Search: ")
                ?: run {
                    notice = "[ Cancelled ]"
                    return buf
                }
        if (q.isEmpty()) return buf
        val hit = buf.findNext(q)
        return if (hit == null) {
            notice = "\"$q\" not found"
            buf
        } else {
            buf.copy(cursorRow = hit.first, cursorCol = hit.second)
        }
    }

    private suspend fun showHelp(buf: NanoBuffer): NanoBuffer {
        // One-screen overlay. Repaints once; next key dismisses.
        val sb = StringBuilder()
        sb.append(Ansi.CURSOR_HOME)
        sb.append(Ansi.CLEAR_SCREEN)
        sb.append("kash nano — quick help\r\n\r\n")
        sb.append("  ^O  Write Out (save)         ^X  Exit\r\n")
        sb.append("  ^S  Save (no prompt)         ^W  Where Is (search)\r\n")
        sb.append("  ^K  Cut line                 ^U  Paste line\r\n")
        sb.append("  ^A  Beginning of line        ^E  End of line\r\n")
        sb.append("  ^P  Prev line  ^N  Next line ^F  Forward  ^B  Back\r\n")
        sb.append("  ^L  Refresh                  ^G  This help\r\n\r\n")
        sb.append("Press any key to return to the editor...")
        term.write(sb.toString())
        term.flush()
        term.readKey() // consume one key
        return buf
    }

    private suspend fun loadInitial(): NanoBuffer? {
        val name = initialFile ?: return NanoBuffer.empty(null)
        val resolved = resolve(name)
        return try {
            if (!fs.exists(resolved)) {
                NanoBuffer.empty(name).also { notice = "[ New File ]" }
            } else if (fs.isDirectory(resolved)) {
                notice = "$name: is a directory"
                NanoBuffer.empty(null)
            } else {
                val bytes = fs.readBytes(resolved)
                NanoBuffer.fromText(bytes.decodeToString(), name)
            }
        } catch (t: Throwable) {
            notice = "Error reading $name: ${t.message}"
            NanoBuffer.empty(name)
        }
    }

    private fun resolve(path: String): String =
        if (path.startsWith("/")) {
            path
        } else if (cwd.endsWith("/")) {
            "$cwd$path"
        } else {
            "$cwd/$path"
        }
}
