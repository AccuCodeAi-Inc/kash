package com.accucodeai.kash.terminal.posix

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Project Panama bindings for the libc symbols [PosixTerminalControl] needs.
 * Resolved once at class load via the platform [Linker.defaultLookup] — works
 * for macOS and Linux without an explicit `dlopen("libc.so")`, since libc is
 * always in the process's default symbol space.
 *
 * Why not JLine: JLine pulls in 4 MB of jar with platform-specific native
 * blobs under `org/jline/nativ/`. JDK 22+ Panama lets us call libc directly
 * with zero extra deps and no native shared libraries shipped. The trade is
 * that we own the FFI bindings; the offset is one Kotlin file.
 *
 * The bindings here are pure data — function pointers + descriptors. Higher-
 * level abstractions live in [Termios] / [Winsize] / the stdin byte pump
 * inside [PosixTerminalControl].
 *
 * Concurrency: all [MethodHandle]s are immutable and thread-safe. Callers
 * must manage their own [Arena] lifetimes for any [MemorySegment]s they pass.
 */
internal object Libc {
    private val linker: Linker = Linker.nativeLinker()
    private val lookup: SymbolLookup = linker.defaultLookup()

    /**
     * Resolve [name] from the platform default lookup. Throws if missing —
     * every symbol we need (tcgetattr/etc.) is POSIX-mandatory on both
     * macOS and Linux, so a missing symbol is a "we're on an unsupported
     * platform" signal, not a recoverable condition.
     */
    private fun handle(
        name: String,
        descriptor: FunctionDescriptor,
        critical: Boolean = false,
    ): MethodHandle {
        val sym =
            lookup.find(name).orElseThrow {
                UnsatisfiedLinkError("libc symbol not found: $name (kash requires macOS or Linux)")
            }
        val options =
            if (critical) {
                arrayOf<Linker.Option>(Linker.Option.critical(true))
            } else {
                emptyArray()
            }
        return linker.downcallHandle(sym, descriptor, *options)
    }

    // --- termios ---
    // int tcgetattr(int fd, struct termios *termios_p);
    private val tcgetattrHandle: MethodHandle =
        handle("tcgetattr", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))

    // int tcsetattr(int fd, int optional_actions, const struct termios *termios_p);
    private val tcsetattrHandle: MethodHandle =
        handle(
            "tcsetattr",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

    // void cfmakeraw(struct termios *termios_p);
    //
    // Available on both glibc (Linux) and Darwin libc. Sets the termios for
    // raw mode in one call rather than us hand-clearing ECHO/ICANON/ISIG/
    // IEXTEN/INLCR/IGNCR/ICRNL/IXON/OPOST and setting CS8 ourselves.
    private val cfmakerawHandle: MethodHandle =
        handle("cfmakeraw", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

    fun tcgetattr(
        fd: Int,
        termios: MemorySegment,
    ): Int = tcgetattrHandle.invokeExact(fd, termios) as Int

    fun tcsetattr(
        fd: Int,
        optionalActions: Int,
        termios: MemorySegment,
    ): Int = tcsetattrHandle.invokeExact(fd, optionalActions, termios) as Int

    fun cfmakeraw(termios: MemorySegment) {
        cfmakerawHandle.invokeExact(termios)
    }

    /** Apply changes immediately. POSIX `TCSANOW` constant. */
    const val TCSANOW: Int = 0

    // --- tty introspection ---
    // int isatty(int fd);
    //
    // critical(true) is tempting since isatty is a leaf, but JDK 25's
    // Linker semantics for critical downcalls are stricter on macOS arm64
    // — leaf functions that touch errno may still crash the JVM if a GC
    // overlaps the call. We pay the small safepoint cost everywhere
    // rather than risk SIGSEGV at runtime.
    private val isattyHandle: MethodHandle =
        handle("isatty", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))

    fun isatty(fd: Int): Int = isattyHandle.invokeExact(fd) as Int

    // --- ioctl (for TIOCGWINSZ) ---
    // int ioctl(int fd, unsigned long request, void *argp);
    //
    // ioctl is variadic in C. On macOS arm64 the ABI for variadic args
    // differs from non-variadic: var-args go on the stack past a fixed
    // arg threshold, where fixed args go in registers. We must declare
    // the third arg as variadic via Linker.Option.firstVariadicArg(2)
    // so the trampoline passes `argp` correctly; without this kash
    // crashes inside the ioctl trampoline on Apple Silicon.
    private val ioctlHandle: MethodHandle =
        linker.downcallHandle(
            lookup.find("ioctl").orElseThrow { UnsatisfiedLinkError("ioctl not found") },
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            ),
            Linker.Option.firstVariadicArg(2),
        )

    fun ioctl(
        fd: Int,
        request: Long,
        argp: MemorySegment,
    ): Int = ioctlHandle.invokeExact(fd, request, argp) as Int

    // --- byte I/O ---
    //
    // We use libc read/write directly (not System.in / System.out) for the
    // reader thread because:
    //   1. System.in.read() holds a JVM-wide static InputStream lock; close()
    //      on shutdown is racy.
    //   2. Closing fd 0 via libc close(0) reliably unblocks libc read(0,...)
    //      on macOS / Linux, giving us a clean shutdown path.
    //
    // ssize_t read(int fd, void *buf, size_t count);
    //
    // NOT critical: read blocks indefinitely on terminal input. A critical
    // downcall is required to be a "leaf" — must not block, must not upcall.
    // Marking a blocking syscall as critical crashes the JVM if a GC happens
    // to want to stop the world while we're suspended in the read.
    private val readHandle: MethodHandle =
        handle(
            "read",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )

    // ssize_t write(int fd, const void *buf, size_t count);
    //
    // NOT critical for the same reason as read: write can block on a slow
    // terminal or when the kernel buffer fills.
    private val writeHandle: MethodHandle =
        handle(
            "write",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )

    // int close(int fd);
    private val closeHandle: MethodHandle =
        handle("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))

    fun read(
        fd: Int,
        buf: MemorySegment,
        count: Long,
    ): Long = readHandle.invokeExact(fd, buf, count) as Long

    fun write(
        fd: Int,
        buf: MemorySegment,
        count: Long,
    ): Long = writeHandle.invokeExact(fd, buf, count) as Long

    fun close(fd: Int): Int = closeHandle.invokeExact(fd) as Int

    /** Reserved global arena for symbols that outlive the JVM. */
    val GLOBAL_ARENA: Arena = Arena.global()
}
