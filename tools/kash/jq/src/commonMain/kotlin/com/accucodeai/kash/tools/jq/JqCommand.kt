package com.accucodeai.kash.tools.jq

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
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.json.JsonValue
import com.accucodeai.kash.json.KashJson
import com.accucodeai.kash.json.jsonArray
import com.accucodeai.kash.json.jsonString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wraps the [Jq] engine as a kash [Command]. Streams results to stdout one
 * per line — `jq -n '1,2,3' | head -n 1` emits the first value and the
 * downstream close breaks the loop on the next write.
 *
 * CLI surface: `-n`, `-r`, `-c` (compact; output is pretty-printed by
 * default), `--tab` / `--indent N`, `-s`, `-R` (raw input), `-S` (sort keys),
 * `-j` (join output), `-e` (exit status), `--`, combined short flags (`-Rr`),
 * file arguments, and the variable-binding family
 * `--arg`/`--argjson`/`--slurpfile`/`--rawfile`.
 */
public class JqCommand :
    Command,
    CommandSpec {
    override val name: String = "jq"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var nullInput = false
        var rawOutput = false
        var rawInput = false
        var slurp = false
        var sortKeys = false
        var joinOutput = false
        var exitStatus = false
        var compact = false
        var indent = "  " // jq's default pretty indent (2 spaces)
        var filter: String? = null
        val files = mutableListOf<String>()
        val vars = linkedMapOf<String, JsonValue>()

        // --- argument parsing -------------------------------------------------
        // Pre-expand combined single-dash short flags (`-Rr` → `-R -r`); all
        // jq short options are boolean toggles, so this is unambiguous. Stops
        // at `--`; long options (`--arg`) and the filter are left untouched.
        val argv = expandShortFlags(args)

        var i = 0
        var optionsEnded = false
        while (i < argv.size) {
            val a = argv[i]

            // Helper to fetch the next argv element for an option that needs it.
            fun next(opt: String): String? {
                if (i + 1 >= argv.size) {
                    return null
                }
                i++
                return argv[i]
            }

            when {
                optionsEnded -> {
                    if (filter == null) filter = a else files += a
                }

                a == "-n" || a == "--null-input" -> {
                    nullInput = true
                }

                a == "-r" || a == "--raw-output" -> {
                    rawOutput = true
                }

                a == "-c" || a == "--compact-output" -> {
                    compact = true
                }

                a == "--tab" -> {
                    indent = "\t"
                }

                a == "--indent" -> {
                    val n =
                        next(a)?.toIntOrNull()?.takeIf { it in 0..7 }
                            ?: return usage(ctx, "--indent takes a number between 0 and 7")
                    // jq: --indent 0 means compact output.
                    if (n == 0) compact = true else indent = " ".repeat(n)
                }

                a == "-s" || a == "--slurp" -> {
                    slurp = true
                }

                a == "-R" || a == "--raw-input" -> {
                    rawInput = true
                }

                a == "-S" || a == "--sort-keys" -> {
                    sortKeys = true
                }

                a == "-j" || a == "--join-output" -> {
                    // -j implies -r and additionally omits the trailing newline.
                    joinOutput = true
                    rawOutput = true
                }

                a == "-e" || a == "--exit-status" -> {
                    exitStatus = true
                }

                a == "--" -> {
                    optionsEnded = true
                }

                a == "--arg" -> {
                    val n = next(a) ?: return usage(ctx, "$a takes two parameters")
                    val v = next(a) ?: return usage(ctx, "$a takes two parameters")
                    vars[n] = jsonString(v)
                }

                a == "--argjson" -> {
                    val n = next(a) ?: return usage(ctx, "$a takes two parameters")
                    val v = next(a) ?: return usage(ctx, "$a takes two parameters")
                    vars[n] =
                        try {
                            KashJson.parse(v)
                        } catch (e: Exception) {
                            ctx.stderr.writeUtf8("jq: Invalid JSON text passed to --argjson\n")
                            return CommandResult(exitCode = 2)
                        }
                }

                a == "--slurpfile" -> {
                    val n = next(a) ?: return usage(ctx, "$a takes two parameters")
                    val path = next(a) ?: return usage(ctx, "$a takes two parameters")
                    val text =
                        readFile(ctx, path)
                            ?: return fileError(ctx, path)
                    vars[n] =
                        try {
                            jsonArray(KashJson.parseStream(text).toList())
                        } catch (e: Exception) {
                            ctx.stderr.writeUtf8("jq: Invalid JSON text in $path\n")
                            return CommandResult(exitCode = 2)
                        }
                }

                a == "--rawfile" -> {
                    val n = next(a) ?: return usage(ctx, "$a takes two parameters")
                    val path = next(a) ?: return usage(ctx, "$a takes two parameters")
                    val text = readFile(ctx, path) ?: return fileError(ctx, path)
                    vars[n] = jsonString(text)
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("jq: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                filter == null -> {
                    filter = a
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        val f =
            filter ?: run {
                ctx.stderr.writeUtf8("jq: filter required\n")
                return CommandResult(exitCode = 2)
            }

        val program =
            try {
                Jq.compile(f)
            } catch (e: JqParseError) {
                ctx.stderr.writeUtf8("jq: ${e.message}\n")
                return CommandResult(exitCode = 3)
            }

        // --- gather the input values -----------------------------------------
        // These feed both the top-level loop AND `input`/`inputs`, so they're
        // gathered the same way regardless of -n/-s (the driver decides how to
        // consume them). -R/--raw-input: each line is a string (with -s, the
        // whole input is one string). Otherwise parse JSON: files concatenate
        // their JSON streams in order; else read stdin.
        val values: List<JsonValue> =
            when {
                rawInput -> {
                    val text = readAllInput(ctx, files) ?: return fileError(ctx, files.first())
                    if (slurp) {
                        listOf(jsonString(text))
                    } else {
                        // Each line is a string; a trailing newline doesn't
                        // yield a final empty line (matches jq).
                        text
                            .split("\n")
                            .let { if (it.isNotEmpty() && it.last() == "") it.dropLast(1) else it }
                            .map { jsonString(it) }
                    }
                }

                else -> {
                    val acc = mutableListOf<JsonValue>()
                    if (files.isNotEmpty()) {
                        for (path in files) {
                            val text = readFile(ctx, path) ?: return fileError(ctx, path)
                            try {
                                acc += KashJson.parseStream(text).toList()
                            } catch (e: Exception) {
                                ctx.stderr.writeUtf8("jq: error: $path: ${e.message}\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                    } else {
                        val text = ctx.stdin.readUtf8Text().trim()
                        if (text.isNotEmpty()) {
                            try {
                                acc += KashJson.parseStream(text).toList()
                            } catch (e: Exception) {
                                ctx.stderr.writeUtf8("jq: error: ${e.message}\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                    }
                    acc
                }
            }

        // --- evaluate + emit --------------------------------------------------
        // The driver shares one input cursor between the main loop and
        // input/inputs. Raw-input slurp already collapsed to one string, so
        // only JSON-mode slurp asks the driver to array-wrap.
        val results =
            program.applyAll(
                values,
                JqOptions(args = vars),
                nullInput = nullInput,
                slurp = slurp && !rawInput,
            )
        var lastOutput: JsonValue? = null
        var produced = false
        try {
            for (result in results) {
                produced = true
                lastOutput = result
                val rendered =
                    Jq.format(
                        if (sortKeys) sortKeysDeep(result) else result,
                        raw = rawOutput,
                        pretty = !compact,
                        indent = indent,
                    )
                if (joinOutput) ctx.stdout.writeUtf8(rendered) else ctx.stdout.writeLine(rendered)
            }
        } catch (e: JqRuntimeError) {
            ctx.stderr.writeUtf8("jq: ${e.message}\n")
            return CommandResult(exitCode = 5)
        }

        // -e/--exit-status: 0 if the last output was truthy, 1 if it was
        // null/false, 4 if there was no output at all.
        if (exitStatus) {
            val code =
                when {
                    !produced -> 4
                    isFalsy(lastOutput) -> 1
                    else -> 0
                }
            return CommandResult(exitCode = code)
        }
        return CommandResult()
    }

    /** Concatenated raw text of all [files] (in order), or stdin when none. */
    private suspend fun readAllInput(
        ctx: CommandContext,
        files: List<String>,
    ): String? {
        if (files.isEmpty()) return ctx.stdin.readUtf8Text()
        val sb = StringBuilder()
        for (path in files) sb.append(readFile(ctx, path) ?: return null)
        return sb.toString()
    }

    private suspend fun usage(
        ctx: CommandContext,
        msg: String,
    ): CommandResult {
        ctx.stderr.writeUtf8("jq: $msg\n")
        return CommandResult(exitCode = 2)
    }

    private suspend fun fileError(
        ctx: CommandContext,
        path: String,
    ): CommandResult {
        ctx.stderr.writeUtf8("jq: error: Could not open $path: No such file or directory\n")
        return CommandResult(exitCode = 2)
    }

    private suspend fun readFile(
        ctx: CommandContext,
        arg: String,
    ): String? {
        val path = Paths.resolve(ctx.cwd, arg)
        return try {
            ctx.fs.source(path).readUtf8Text()
        } catch (_: FileNotFound) {
            null
        }
    }

    /**
     * Split bundled single-dash short flags (`-Rr` → `-R`, `-r`) into separate
     * args. Only touches single-dash, all-letter tokens before `--`; leaves
     * `--long`, the filter, and post-`--` args alone. jq short options take no
     * inline values, so the split is unambiguous.
     */
    private fun expandShortFlags(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var ended = false
        for (a in args) {
            when {
                ended -> {
                    out += a
                }

                a == "--" -> {
                    ended = true
                    out += a
                }

                a.length > 2 && a[0] == '-' && a[1] != '-' && a.drop(1).all { it.isLetter() } -> {
                    for (c in a.drop(1)) out += "-$c"
                }

                else -> {
                    out += a
                }
            }
        }
        return out
    }

    /** Recursively reorder object keys alphabetically (for `-S`). */
    private fun sortKeysDeep(v: JsonValue): JsonValue =
        when (v) {
            is JsonObject -> {
                JsonObject(
                    v.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to sortKeysDeep(it.value) },
                )
            }

            is JsonArray -> {
                JsonArray(v.map { sortKeysDeep(it) })
            }

            else -> {
                v
            }
        }

    /** jq's falsiness for `-e`: only `null` and `false` are falsy. */
    private fun isFalsy(v: JsonValue?): Boolean =
        v is JsonNull || (v is JsonPrimitive && !v.isString && v.content == "false")
}
