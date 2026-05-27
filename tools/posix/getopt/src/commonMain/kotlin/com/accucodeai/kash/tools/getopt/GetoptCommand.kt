package com.accucodeai.kash.tools.getopt

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * Standalone `getopt(1)` — the util-linux-style argv parser. Distinct
 * from the POSIX `getopts` shell builtin: scripts call this once,
 * `eval set --` the quoted output, then iterate the normalized argv with
 * `case $1 in`.
 *
 * Always reports as "enhanced" — `-T` exits 4.
 */
public class GetoptCommand :
    Command,
    CommandSpec {
    override val name: String = "getopt"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var optsSpec: String? = null
        val longSpecs = mutableListOf<String>()
        var progName = "getopt"
        var quiet = false
        var quietOutput = false
        var shellFlavor = "bash"
        var unquoted = false
        var alternative = false
        var testMode = false
        val remaining = mutableListOf<String>()

        var i = 0
        var sawDoubleDash = false
        while (i < args.size) {
            val a = args[i]
            if (sawDoubleDash) {
                remaining += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    sawDoubleDash = true
                }

                a == "-T" || a == "--test" -> {
                    testMode = true
                }

                a == "-q" || a == "--quiet" -> {
                    quiet = true
                }

                a == "-Q" || a == "--quiet-output" -> {
                    quietOutput = true
                }

                a == "-u" || a == "--unquoted" -> {
                    unquoted = true
                }

                a == "-a" || a == "--alternative" -> {
                    alternative = true
                }

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8(helpText())
                    return CommandResult(exitCode = 0)
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("getopt (kash)\n")
                    return CommandResult(exitCode = 0)
                }

                a == "-o" || a == "--options" -> {
                    if (i + 1 >= args.size) {
                        if (!quiet) ctx.stderr.writeUtf8("getopt: option requires an argument -- 'o'\n")
                        return CommandResult(exitCode = 2)
                    }
                    optsSpec = args[++i]
                }

                a.startsWith("--options=") -> {
                    optsSpec = a.substring("--options=".length)
                }

                a.startsWith("-o") && a.length > 2 -> {
                    optsSpec = a.substring(2)
                }

                a == "-l" || a == "--long" || a == "--longoptions" -> {
                    if (i + 1 >= args.size) {
                        if (!quiet) ctx.stderr.writeUtf8("getopt: option requires an argument -- 'l'\n")
                        return CommandResult(exitCode = 2)
                    }
                    longSpecs += args[++i]
                }

                a.startsWith("--long=") -> {
                    longSpecs += a.substring("--long=".length)
                }

                a.startsWith("--longoptions=") -> {
                    longSpecs += a.substring("--longoptions=".length)
                }

                a.startsWith("-l") && a.length > 2 -> {
                    longSpecs += a.substring(2)
                }

                a == "-n" || a == "--name" -> {
                    if (i + 1 >= args.size) {
                        if (!quiet) ctx.stderr.writeUtf8("getopt: option requires an argument -- 'n'\n")
                        return CommandResult(exitCode = 2)
                    }
                    progName = args[++i]
                }

                a.startsWith("--name=") -> {
                    progName = a.substring("--name=".length)
                }

                a.startsWith("-n") && a.length > 2 -> {
                    progName = a.substring(2)
                }

                a == "-s" || a == "--shell" -> {
                    if (i + 1 >= args.size) {
                        if (!quiet) ctx.stderr.writeUtf8("getopt: option requires an argument -- 's'\n")
                        return CommandResult(exitCode = 2)
                    }
                    shellFlavor = args[++i]
                }

                a.startsWith("--shell=") -> {
                    shellFlavor = a.substring("--shell=".length)
                }

                a.startsWith("-s") && a.length > 2 -> {
                    shellFlavor = a.substring(2)
                }

                a.startsWith("-") && a != "-" && optsSpec == null -> {
                    // Could be compatibility mode: first non-option arg is short spec.
                    // Util-linux: if -o not specified, the first non-option token
                    // is treated as the short spec, and the rest as args to parse.
                    remaining += a
                    i++
                    while (i < args.size) {
                        remaining += args[i]
                        i++
                    }
                    break
                }

                else -> {
                    // Compatibility mode: first positional is short spec.
                    if (optsSpec == null) {
                        optsSpec = a
                        i++
                        while (i < args.size) {
                            remaining += args[i]
                            i++
                        }
                        break
                    } else {
                        remaining += a
                    }
                }
            }
            i++
        }

        if (testMode) {
            // Always enhanced.
            return CommandResult(exitCode = 4)
        }

        if (optsSpec == null && longSpecs.isEmpty()) {
            if (!quiet) ctx.stderr.writeUtf8("getopt: missing optstring argument\n")
            return CommandResult(exitCode = 2)
        }

        val (shortSpecs, mode) = parseShortSpec(optsSpec ?: "")
        val longSpecsParsed = parseLongSpec(longSpecs)

        val parsed =
            try {
                parseArgv(
                    argv = remaining,
                    shorts = shortSpecs,
                    longs = longSpecsParsed,
                    alternative = alternative,
                    stopAtNonOption = mode == ShortMode.REQUIRE_ORDER,
                )
            } catch (e: GetoptParseException) {
                if (!quiet) ctx.stderr.writeUtf8("$progName: ${e.message}\n")
                return CommandResult(exitCode = e.rc)
            }

        if (quietOutput) return CommandResult(exitCode = 0)

        val quoter: (String) -> String =
            when (shellFlavor) {
                "csh", "tcsh" -> ::cshQuote
                else -> ::shQuote
            }
        val line = renderOutput(parsed, quoter, unquoted)
        ctx.stdout.writeUtf8(line)
        ctx.stdout.writeUtf8("\n")
        return CommandResult(exitCode = 0)
    }

    private fun helpText(): String =
        """
        Usage: getopt [options] [--] optstring parameters
               getopt [options] -o|--options optstring [options] [--] parameters

        Options:
          -a, --alternative             allow long options with a single dash
          -l, --longoptions <list>      long options, comma-separated
          -n, --name <name>             program name for error messages
          -o, --options <string>        short option spec
          -q, --quiet                   suppress error messages
          -Q, --quiet-output            suppress normalized output
          -s, --shell <shell>           sh/bash/csh/tcsh quoting style
          -T, --test                    test for enhanced getopt; exit 4
          -u, --unquoted                do not quote output
          -h, --help                    print help
          -V, --version                 print version
        """.trimIndent() + "\n"
}
