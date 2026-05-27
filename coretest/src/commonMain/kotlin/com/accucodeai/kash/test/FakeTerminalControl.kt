package com.accucodeai.kash.test

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.api.terminal.TerminalSize
import kotlinx.coroutines.channels.Channel

/**
 * Scripted [TerminalControl] for tests of any tool or editor that needs
 * raw-mode terminal access (today: `nano`, `BasicLineEditor`). Keys are
 * pushed onto [keys] via [pushKey] / [pushChars]; the editor pulls via
 * [readKey]. All writes land in [output] in order so tests can assert
 * "did the screen show X".
 *
 * `enterRawMode` / `useAlternateScreen` are no-ops that just bump flags
 * the test can inspect.
 */
public class FakeTerminalControl(
    private val cols: Int = 80,
    private val rows: Int = 24,
) : TerminalControl {
    public val keys: Channel<Key> = Channel(capacity = Channel.UNLIMITED)
    public val output: StringBuilder = StringBuilder()
    public var rawModeEntered: Int = 0
    public var exitRawCalls: Int = 0
    public var altScreenOn: Boolean = false

    override fun pushKey(key: Key) {
        keys.trySend(key)
    }

    public fun pushChars(s: String) {
        for (ch in s) keys.trySend(Key.Char(ch.code))
    }

    override suspend fun enterRawMode() {
        rawModeEntered++
    }

    override suspend fun exitRawMode() {
        exitRawCalls++
    }

    override suspend fun useAlternateScreen(enable: Boolean) {
        altScreenOn = enable
    }

    override fun size(): TerminalSize = TerminalSize(cols, rows)

    override suspend fun readKey(): Key = keys.receive()

    override suspend fun write(s: String) {
        output.append(s)
    }

    override suspend fun flush() {}
}
