package com.accucodeai.kash.tools.shasum

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound

private fun resolvePath(
    cwd: String,
    path: String,
): String = if (path.startsWith("/")) path else "$cwd/$path"

/**
 * `shasum` — compute and check SHA digests for files (or stdin).
 *
 * Supported options:
 * - `-a {1,224,256,384,512}` — algorithm by bit length (default 256).
 * - `-b` — binary mode (output marker `*`); we always read bytes verbatim,
 *   so the digest is identical to text mode, but the marker is honored for
 *   coreutils compatibility.
 * - `-c` — read checksums from a file (or stdin) and verify each.
 * - `--` — end of options.
 *
 * Output format matches coreutils:
 *   `<hex>  <filename>`   (text mode, two spaces)
 *   `<hex> *<filename>`   (binary mode, space + asterisk)
 */
public class ShaSumCommand :
    Command,
    CommandSpec {
    override val name: String = "shasum"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var alg = 256
        var binary = false
        var check = false
        val files = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--" -> {
                    i++
                    while (i < args.size) {
                        files += args[i]
                        i++
                    }
                    continue
                }

                a == "-a" || a == "--algorithm" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("shasum: option requires an argument -- a\n")
                        return CommandResult(exitCode = 1)
                    }
                    val v = args[i + 1].toIntOrNull()
                    if (v == null || v !in SUPPORTED_ALGS) {
                        ctx.stderr.writeUtf8("shasum: unrecognized algorithm: ${args[i + 1]}\n")
                        return CommandResult(exitCode = 1)
                    }
                    alg = v
                    i += 2
                    continue
                }

                a.startsWith("-a") && a.length > 2 -> {
                    val v = a.substring(2).toIntOrNull()
                    if (v == null || v !in SUPPORTED_ALGS) {
                        ctx.stderr.writeUtf8("shasum: unrecognized algorithm: ${a.substring(2)}\n")
                        return CommandResult(exitCode = 1)
                    }
                    alg = v
                }

                a == "-b" || a == "--binary" -> {
                    binary = true
                }

                a == "-t" || a == "--text" -> {
                    binary = false
                }

                a == "-c" || a == "--check" -> {
                    check = true
                }

                a == "-" -> {
                    files += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("shasum: unknown option: $a\n")
                    return CommandResult(exitCode = 1)
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        return if (check) {
            runCheck(files, alg, ctx)
        } else {
            runCompute(files, alg, binary, ctx)
        }
    }

    private suspend fun runCompute(
        files: List<String>,
        alg: Int,
        binary: Boolean,
        ctx: CommandContext,
    ): CommandResult {
        val marker = if (binary) " *" else "  "
        if (files.isEmpty()) {
            val hex = shaDigest(alg, ctx.stdin)
            ctx.stdout.writeLine("$hex$marker-")
            return CommandResult()
        }

        var anyError = false
        for (arg in files) {
            if (arg == "-") {
                val hex = shaDigest(alg, ctx.stdin)
                ctx.stdout.writeLine("$hex$marker-")
                continue
            }
            val path = resolvePath(ctx.process.cwd, arg)
            try {
                val src = ctx.process.fs.source(path)
                val hex =
                    try {
                        shaDigest(alg, src)
                    } finally {
                        src.close()
                    }
                ctx.stdout.writeLine("$hex$marker$arg")
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("shasum: $arg: No such file or directory\n")
                anyError = true
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private suspend fun runCheck(
        files: List<String>,
        alg: Int,
        ctx: CommandContext,
    ): CommandResult {
        // Gather checksum lines: stdin if no files; otherwise concat each file.
        val lines = mutableListOf<Pair<String, String>>() // (source, line)
        if (files.isEmpty()) {
            val text = ctx.stdin.readUtf8Text()
            for (l in text.lineSequence()) if (l.isNotBlank()) lines += "-" to l
        } else {
            for (arg in files) {
                val text =
                    if (arg == "-") {
                        ctx.stdin.readUtf8Text()
                    } else {
                        val path = resolvePath(ctx.process.cwd, arg)
                        try {
                            val src = ctx.process.fs.source(path)
                            try {
                                src.readUtf8Text()
                            } finally {
                                src.close()
                            }
                        } catch (_: FileNotFound) {
                            ctx.stderr.writeUtf8("shasum: $arg: No such file or directory\n")
                            return CommandResult(exitCode = 1)
                        }
                    }
                for (l in text.lineSequence()) if (l.isNotBlank()) lines += arg to l
            }
        }

        var failed = 0
        var missing = 0
        var malformed = 0
        for ((_, line) in lines) {
            val parsed = parseChecksumLine(line)
            if (parsed == null) {
                malformed++
                continue
            }
            val (expected, fname) = parsed
            // Validate expected hex length matches the chosen algorithm. SHA-1
            // is named "1" but outputs 160 bits = 40 hex chars.
            val expectedLen = if (alg == 1) 40 else alg / 4
            if (expected.length != expectedLen) {
                ctx.stderr.writeUtf8("shasum: $fname: bad checksum length for SHA-$alg\n")
                failed++
                continue
            }
            val actualHex: String =
                if (fname == "-") {
                    shaDigest(alg, ctx.stdin)
                } else {
                    val path = resolvePath(ctx.process.cwd, fname)
                    try {
                        val src = ctx.process.fs.source(path)
                        try {
                            shaDigest(alg, src)
                        } finally {
                            src.close()
                        }
                    } catch (_: FileNotFound) {
                        ctx.stdout.writeLine("$fname: FAILED open or read")
                        missing++
                        continue
                    }
                }
            if (actualHex.equals(expected, ignoreCase = true)) {
                ctx.stdout.writeLine("$fname: OK")
            } else {
                ctx.stdout.writeLine("$fname: FAILED")
                failed++
            }
        }
        if (malformed > 0) {
            ctx.stderr.writeUtf8("shasum: WARNING: $malformed line(s) are improperly formatted\n")
        }
        if (missing > 0) {
            ctx.stderr.writeUtf8("shasum: WARNING: $missing listed file(s) could not be read\n")
        }
        if (failed > 0) {
            ctx.stderr.writeUtf8("shasum: WARNING: $failed computed checksum(s) did NOT match\n")
        }
        val ok = failed == 0 && missing == 0 && malformed == 0
        return CommandResult(exitCode = if (ok) 0 else 1)
    }

    /**
     * Parse a coreutils-format checksum line: `<hex>  <name>` (text) or
     * `<hex> *<name>` (binary). Returns `(hex, name)` or null if malformed.
     */
    private fun parseChecksumLine(line: String): Pair<String, String>? {
        // The hex portion is contiguous non-space; then one space; then either
        // ' ' (text) or '*' (binary); then the filename to end-of-line.
        val sp = line.indexOf(' ')
        if (sp <= 0 || sp + 2 > line.length) return null
        val hex = line.substring(0, sp)
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val sep = line[sp + 1]
        if (sep != ' ' && sep != '*') return null
        val name = line.substring(sp + 2)
        if (name.isEmpty()) return null
        return hex to name
    }

    public companion object {
        public val SUPPORTED_ALGS: Set<Int> = setOf(1, 224, 256, 384, 512)
    }
}
