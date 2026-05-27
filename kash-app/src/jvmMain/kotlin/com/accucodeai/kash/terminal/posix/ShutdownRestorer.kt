package com.accucodeai.kash.terminal.posix

/**
 * Installs a JVM shutdown hook that calls [PosixTerminalControl.stop] —
 * restoring termios + emitting cursor-show + alt-screen-off + keypad-local
 * escapes. Without this, a `kash` process that exits while in raw mode
 * leaves the user's terminal wedged (no line buffering, no echo) until
 * they manually type `reset`.
 *
 * Matches the safety guarantee `JlineTerminalControl.installShutdownHook`
 * used to provide.
 *
 * Caveats:
 *  - SIGKILL / `kill -9` bypasses JVM shutdown hooks; nothing we can do
 *    about that. Same as JLine.
 *  - OOM and stack overflow with no leftover memory may also skip hooks.
 *  - Multiple `install` calls layer hooks (each fires once). Call once
 *    per process — typically from `Main.kt` right after `start()`.
 */
internal object ShutdownRestorer {
    fun install(control: PosixTerminalControl) {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { control.stop() }
            }.apply {
                name = "kash-terminal-restore"
                isDaemon = false
            },
        )
    }
}
