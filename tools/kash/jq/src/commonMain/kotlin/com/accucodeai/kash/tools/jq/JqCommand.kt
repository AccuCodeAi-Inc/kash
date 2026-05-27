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
import com.accucodeai.kash.json.JsonValue
import com.accucodeai.kash.json.KashJson
import com.accucodeai.kash.json.jsonArray
import com.accucodeai.kash.json.jsonNull

/**
 * Wraps the [Jq] engine as a kash [Command]. Streams results to stdout one
 * per line — `jq -n '1,2,3' | head -n 1` emits the first value and the
 * downstream close breaks the loop on the next write.
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
        var slurp = false
        var filter: String? = null

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-n" || a == "--null-input" -> {
                    nullInput = true
                }

                a == "-r" || a == "--raw-output" -> {
                    rawOutput = true
                }

                a == "-c" || a == "--compact-output" -> {
                    Unit
                }

                a == "-s" || a == "--slurp" -> {
                    slurp = true
                }

                a == "--" -> {
                    if (i + 1 < args.size) filter = args[i + 1]
                    i = args.size
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("jq: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                filter == null -> {
                    filter = a
                }

                else -> {
                    ctx.stderr.writeUtf8("jq: reading from files is not supported yet; pipe via stdin\n")
                    return CommandResult(exitCode = 2)
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

        val inputs: List<JsonValue> =
            when {
                nullInput -> {
                    listOf(jsonNull())
                }

                else -> {
                    val text = ctx.stdin.readUtf8Text().trim()
                    if (text.isEmpty()) {
                        if (slurp) listOf(jsonArray(emptyList())) else emptyList()
                    } else if (slurp) {
                        listOf(jsonArray(KashJson.parseStream(text).toList()))
                    } else {
                        KashJson.parseStream(text).toList()
                    }
                }
            }

        for (input in inputs) {
            try {
                for (result in program.apply(input)) {
                    ctx.stdout.writeLine(Jq.format(result, raw = rawOutput, pretty = false))
                }
            } catch (e: JqRuntimeError) {
                ctx.stderr.writeUtf8("jq: ${e.message}\n")
                return CommandResult(exitCode = 5)
            }
        }
        return CommandResult()
    }
}
