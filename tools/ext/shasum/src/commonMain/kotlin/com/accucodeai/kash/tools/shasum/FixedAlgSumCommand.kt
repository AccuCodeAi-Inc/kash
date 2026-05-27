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
 * Shared engine for GNU-style per-algorithm hash commands:
 * `md5sum`, `sha1sum`, `sha224sum`, `sha256sum`, `sha384sum`, `sha512sum`.
 *
 * Each is a thin wrapper that fixes the algorithm; the CLI surface matches
 * coreutils: optional `FILE...` operands (default stdin), `-b` binary
 * marker, `-t` text marker, `-c` check mode, `--tag` BSD-style output,
 * `--` end-of-options. The algorithm is implied by the command name —
 * no `-a` flag.
 *
 * Default mode is binary (matching coreutils on most platforms); `-t` flips
 * it. The marker only affects the output line, never the digest itself
 * (kash is byte-stream identical in either mode).
 */
public abstract class FixedAlgSumCommand(
    final override val name: String,
    /** Algorithm bits as understood by [shaDigest]; `0` denotes MD5. */
    private val algBits: Int,
    /** Hex length expected in `-c` checksum lines (32 for MD5, 40 for SHA-1, ...). */
    private val hexLen: Int,
    /** BSD `--tag` label, e.g. "MD5", "SHA256". */
    private val tagName: String,
) : Command,
    CommandSpec {
    final override val kind: CommandKind = CommandKind.TOOL
    final override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    final override val command: Command get() = this

    final override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        // GNU md5sum/sha*sum default to binary on most platforms.
        var binary = true
        var check = false
        var bsdTag = false
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

                a == "-b" || a == "--binary" -> {
                    binary = true
                }

                a == "-t" || a == "--text" -> {
                    binary = false
                }

                a == "-c" || a == "--check" -> {
                    check = true
                }

                a == "--tag" -> {
                    bsdTag = true
                    binary = true
                }

                a == "-" -> {
                    files += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("$name: unknown option: $a\n")
                    return CommandResult(exitCode = 1)
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        return if (check) {
            runCheck(files, ctx)
        } else {
            runCompute(files, binary, bsdTag, ctx)
        }
    }

    private suspend fun runCompute(
        files: List<String>,
        binary: Boolean,
        bsdTag: Boolean,
        ctx: CommandContext,
    ): CommandResult {
        val marker = if (binary) " *" else "  "

        suspend fun format(
            hex: String,
            label: String,
        ): String =
            if (bsdTag) {
                "$tagName ($label) = $hex"
            } else {
                "$hex$marker$label"
            }

        if (files.isEmpty()) {
            val hex = shaDigest(algBits, ctx.stdin)
            ctx.stdout.writeLine(format(hex, "-"))
            return CommandResult()
        }

        var anyError = false
        for (arg in files) {
            if (arg == "-") {
                val hex = shaDigest(algBits, ctx.stdin)
                ctx.stdout.writeLine(format(hex, "-"))
                continue
            }
            val path = resolvePath(ctx.process.cwd, arg)
            try {
                val src = ctx.process.fs.source(path)
                val hex =
                    try {
                        shaDigest(algBits, src)
                    } finally {
                        src.close()
                    }
                ctx.stdout.writeLine(format(hex, arg))
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("$name: $arg: No such file or directory\n")
                anyError = true
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private suspend fun runCheck(
        files: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val lines = mutableListOf<String>()
        if (files.isEmpty()) {
            val text = ctx.stdin.readUtf8Text()
            for (l in text.lineSequence()) if (l.isNotBlank()) lines += l
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
                            ctx.stderr.writeUtf8("$name: $arg: No such file or directory\n")
                            return CommandResult(exitCode = 1)
                        }
                    }
                for (l in text.lineSequence()) if (l.isNotBlank()) lines += l
            }
        }

        var failed = 0
        var missing = 0
        var malformed = 0
        for (line in lines) {
            val parsed = parseLine(line)
            if (parsed == null) {
                malformed++
                continue
            }
            val (expected, fname) = parsed
            if (expected.length != hexLen) {
                ctx.stderr.writeUtf8("$name: $fname: bad checksum length\n")
                failed++
                continue
            }
            val actualHex: String =
                if (fname == "-") {
                    shaDigest(algBits, ctx.stdin)
                } else {
                    val path = resolvePath(ctx.process.cwd, fname)
                    try {
                        val src = ctx.process.fs.source(path)
                        try {
                            shaDigest(algBits, src)
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
            ctx.stderr.writeUtf8("$name: WARNING: $malformed line(s) are improperly formatted\n")
        }
        if (missing > 0) {
            ctx.stderr.writeUtf8("$name: WARNING: $missing listed file(s) could not be read\n")
        }
        if (failed > 0) {
            ctx.stderr.writeUtf8("$name: WARNING: $failed computed checksum(s) did NOT match\n")
        }
        val ok = failed == 0 && missing == 0 && malformed == 0
        return CommandResult(exitCode = if (ok) 0 else 1)
    }

    /**
     * Parse one checksum line. Accepts both coreutils formats
     * (`<hex>  <name>` / `<hex> *<name>`) and BSD `--tag` style
     * (`ALGO (<name>) = <hex>`).
     */
    private fun parseLine(line: String): Pair<String, String>? {
        // BSD tag: "ALGO (name) = hex"
        if (line.startsWith("$tagName (")) {
            val close = line.lastIndexOf(") = ")
            if (close > 0) {
                val fname = line.substring(tagName.length + 2, close)
                val hex = line.substring(close + 4)
                if (hex.all { it.isHex() } && fname.isNotEmpty()) return hex to fname
            }
        }
        val sp = line.indexOf(' ')
        if (sp <= 0 || sp + 2 > line.length) return null
        val hex = line.substring(0, sp)
        if (!hex.all { it.isHex() }) return null
        val sep = line[sp + 1]
        if (sep != ' ' && sep != '*') return null
        val name = line.substring(sp + 2)
        if (name.isEmpty()) return null
        return hex to name
    }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

public class Md5SumCommand : FixedAlgSumCommand("md5sum", algBits = 0, hexLen = 32, tagName = "MD5")

public class Sha1SumCommand : FixedAlgSumCommand("sha1sum", algBits = 1, hexLen = 40, tagName = "SHA1")

public class Sha224SumCommand : FixedAlgSumCommand("sha224sum", algBits = 224, hexLen = 56, tagName = "SHA224")

public class Sha256SumCommand : FixedAlgSumCommand("sha256sum", algBits = 256, hexLen = 64, tagName = "SHA256")

public class Sha384SumCommand : FixedAlgSumCommand("sha384sum", algBits = 384, hexLen = 96, tagName = "SHA384")

public class Sha512SumCommand : FixedAlgSumCommand("sha512sum", algBits = 512, hexLen = 128, tagName = "SHA512")
