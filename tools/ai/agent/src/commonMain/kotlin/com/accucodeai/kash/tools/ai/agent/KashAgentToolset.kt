package com.accucodeai.kash.tools.ai.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.magic.KMagic
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

/**
 * Key under which [AgentSession.markMalformedToolCallArgs] stashes the raw,
 * unparseable arguments string a model emitted, so it survives Koog's message
 * assembly as valid JSON. The tools' [SimpleTool.decodeArgs] overrides detect
 * it and raise a precise, recoverable "your arguments were not valid JSON"
 * error (echoing what the model sent) instead of the generic
 * missing-fields/`{}` confusion. See [rejectIfMalformedArgs].
 */
internal const val MALFORMED_ARGS_KEY = "__kash_malformed_args__"

/** Cap on how much of the raw malformed args we echo back (per side). */
internal const val MALFORMED_ARGS_ECHO_LIMIT = 400

/**
 * If [rawArgs] is the malformed-args sentinel, throw a recoverable error that
 * tells the model exactly what went wrong and how to fix it — the surfaced
 * tool result becomes "Tool with name '<name>' failed to parse arguments due
 * to the error: <this message>". No-op for genuine arguments. [schemaHint] is
 * a one-line example of the valid shape; [extra] appends tool-specific advice.
 */
private fun rejectIfMalformedArgs(
    rawArgs: JSONObject,
    toolName: String,
    schemaHint: String,
    extra: String = "",
) {
    val raw = (rawArgs.entries[MALFORMED_ARGS_KEY] as? JSONPrimitive)?.contentOrNull ?: return
    val shown = if (raw.isBlank()) "(empty — no arguments were sent)" else "`$raw`"
    throw IllegalArgumentException(
        "the arguments you sent were not valid JSON, so $toolName did NOT run and nothing changed. " +
            "You sent: $shown. Resend the call once as a single valid JSON object, e.g. $schemaHint." +
            (if (extra.isEmpty()) "" else " $extra"),
    )
}

/**
 * An image `read_file` recognized, queued for injection into the model's
 * next user message. Tool-result messages are text-only on the OpenAI wire
 * format (LM Studio / Ollama), so the bytes can't ride back as the tool's
 * return value — [AgentSession] drains these and appends them as a vision
 * user-part before the following LLM call.
 */
internal class PendingImage(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
)

/**
 * The kash-aware tools, expressed as Koog [SimpleTool]s for the graph-based
 * agent. Each bridges to the persistent [KashAgentShell] (for `shell_exec`)
 * or directly to the VM filesystem ([CommandContext.fs]). Tool results are
 * plain strings — Koog appends them to the prompt as the tool-result
 * message and our [AgentSession] EventHandler renders them inline.
 *
 * Mirrors the behavior of the pre-graph hand-dispatched tools: cwd-relative
 * path resolution against the subshell, UTF-8 refusal in `read_file`,
 * output truncation.
 */
internal class KashAgentToolset(
    private val ctx: CommandContext,
    private val shell: KashAgentShell,
    /**
     * Hook for out-of-band tool rendering — currently the `write_file` diff —
     * delivered as one complete block (so it's a single write, not one per
     * line). Defaults to printing straight to stdout, which lands it inline
     * between the tool's indicator and result in the append-only transcript.
     */
    private val emit: suspend (String) -> Unit = { ctx.stdout.writeUtf8(it) },
    /**
     * Sink for an image `read_file` recognized. [AgentSession] queues it and
     * injects it as a vision user-part before the next LLM call; the default
     * no-op drops it (tests / non-vision setups that only assert the ack).
     */
    private val onImage: (PendingImage) -> Unit = {},
) {
    fun registry(): ToolRegistry =
        ToolRegistry {
            tool(ShellExec())
            tool(ReadFile())
            tool(WriteFile())
        }

    @Serializable
    data class ShellArgs(
        @property:LLMDescription("The shell command line to execute.")
        val command: String,
    )

    inner class ShellExec :
        SimpleTool<ShellArgs>(
            argsType = typeToken<ShellArgs>(),
            name = "shell_exec",
            description =
                "Run a command in the persistent non-interactive kash shell. Returns combined " +
                    "stdout+stderr followed by an `exit: N` line. State (cwd, env, functions, " +
                    "aliases) carries across calls within this session.",
        ) {
        override fun decodeArgs(
            rawArgs: JSONObject,
            serializer: JSONSerializer,
        ): ShellArgs {
            rejectIfMalformedArgs(rawArgs, name, """{"command":"ls -la"}""")
            return super.decodeArgs(rawArgs, serializer)
        }

        override suspend fun execute(args: ShellArgs): String {
            val r = shell.execute(args.command)
            val sb = StringBuilder()
            if (r.stdout.isNotEmpty()) sb.append(r.stdout)
            if (r.stderr.isNotEmpty()) {
                if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
                sb.append(r.stderr)
            }
            if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
            sb.append("exit: ${r.exitCode}\n")
            return truncate(sb.toString())
        }
    }

    @Serializable
    data class PathArgs(
        @property:LLMDescription("Absolute or cwd-relative path.")
        val path: String,
    )

    inner class ReadFile :
        SimpleTool<PathArgs>(
            argsType = typeToken<PathArgs>(),
            name = "read_file",
            description =
                "Read a file from the kash virtual filesystem. Text files return their UTF-8 " +
                    "content. Image files (PNG, JPEG, GIF, WebP, …) are shown to you directly as a " +
                    "picture on the next step — read_file an image when you need to see it.",
        ) {
        override fun decodeArgs(
            rawArgs: JSONObject,
            serializer: JSONSerializer,
        ): PathArgs {
            rejectIfMalformedArgs(rawArgs, name, """{"path":"src/main.kt"}""")
            return super.decodeArgs(rawArgs, serializer)
        }

        override suspend fun execute(args: PathArgs): String {
            val resolved = resolve(args.path)
            val fs = ctx.fs
            if (!fs.exists(resolved)) return "error: file not found: ${args.path}"
            if (fs.isDirectory(resolved)) return "error: is a directory: ${args.path}"
            val bytes = fs.readBytes(resolved)
            // Content-sniff before the text check: an image is valid bytes but
            // not UTF-8, so it would otherwise hit the binary-refusal branch.
            val prefix = if (bytes.size > KMagic.PEEK_BYTES) bytes.copyOf(KMagic.PEEK_BYTES) else bytes
            val mime = KMagic.identify(prefix).mime
            if (mime.startsWith("image/")) {
                if (bytes.size > MAX_IMAGE_BYTES) {
                    return "error: image is ${bytes.size} bytes (limit ${MAX_IMAGE_BYTES}); " +
                        "too large to view. Resize or crop it first with shell_exec."
                }
                val name = resolved.substringAfterLast('/')
                onImage(PendingImage(bytes, mime, name))
                return "ok: loaded $name ($mime, ${bytes.size} bytes) — image shown below."
            }
            if (!looksUtf8Text(bytes)) {
                return "error: file is not valid UTF-8 text. " +
                    "Use shell_exec with the appropriate tool (file, xxd, base64 -d, openssl, …)."
            }
            return truncate(bytes.decodeToString())
        }
    }

    @Serializable
    data class WriteArgs(
        @property:LLMDescription("Absolute or cwd-relative path.")
        val path: String,
        @property:LLMDescription("Full UTF-8 content to write.")
        val content: String,
    )

    inner class WriteFile :
        SimpleTool<WriteArgs>(
            argsType = typeToken<WriteArgs>(),
            name = "write_file",
            description = "Write a UTF-8 text file. Overwrites if it exists.",
        ) {
        override fun decodeArgs(
            rawArgs: JSONObject,
            serializer: JSONSerializer,
        ): WriteArgs {
            rejectIfMalformedArgs(
                rawArgs,
                name,
                """{"path":"foo.sh","content":"#!/bin/bash\n…"}""",
                extra =
                    "If the content is large or hard to encode as one JSON string, write it via shell_exec " +
                        "with a heredoc instead: cat > foo.sh <<'EOF' … EOF.",
            )
            return super.decodeArgs(rawArgs, serializer)
        }

        override suspend fun execute(args: WriteArgs): String {
            val resolved = resolve(args.path)
            // Capture prior content (null when creating) so we can render a
            // colorized unified diff to the terminal — the diff header
            // carries the path + ±counts, so the returned string stays terse.
            val before: String? =
                try {
                    if (ctx.fs.exists(resolved) && !ctx.fs.isDirectory(resolved)) {
                        ctx.fs.readBytes(resolved).decodeToString()
                    } else {
                        null
                    }
                } catch (_: Throwable) {
                    null
                }
            ctx.fs.writeBytes(resolved, args.content.encodeToByteArray())
            try {
                // Render the diff into a buffer and emit it as ONE block.
                val buf = StringBuilder()
                val collect =
                    object : SuspendSink {
                        override suspend fun write(
                            source: Buffer,
                            byteCount: Long,
                        ) {
                            buf.append(source.readByteArray(byteCount.toInt()).decodeToString())
                        }

                        override suspend fun flush() {}

                        override fun close() {}
                    }
                DiffRenderer.render(
                    out = collect,
                    path = args.path,
                    before = before,
                    after = args.content,
                    glyphs = agentGlyphs,
                    palette =
                        DiffRenderer.Palette(
                            reset = ANSI_RESET,
                            dim = ANSI_DIM,
                            red = ANSI_RED,
                            green = ANSI_GREEN,
                        ),
                )
                emit(buf.toString())
            } catch (_: Throwable) {
                // Rendering is best-effort; the write already succeeded.
            }
            return "ok: wrote ${args.content.length} bytes to ${args.path}"
        }
    }

    private fun resolve(path: String): String {
        if (path.startsWith("/")) return path
        val cwd = shell.cwd ?: ctx.cwd
        return if (cwd.endsWith("/")) "$cwd$path" else "$cwd/$path"
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 16_000

        // Cap on an image we'll hand to the vision model. Base64 inflates by
        // ~4/3 and lands in the prompt verbatim, so a few MB is plenty before
        // it starts dominating the context window.
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

        // ANSI for the write_file diff palette (ESC via unicode escape so
        // the raw 0x1B byte survives source round-trips).
        const val ANSI_RESET = "[0m"
        const val ANSI_DIM = "[2m"
        const val ANSI_RED = "[31m"
        const val ANSI_GREEN = "[32m"

        fun truncate(s: String): String =
            if (s.length <= MAX_OUTPUT_CHARS) {
                s
            } else {
                s.take(MAX_OUTPUT_CHARS) + "\n[…${s.length - MAX_OUTPUT_CHARS} more chars truncated]"
            }

        /** Strict-ish UTF-8 validation — fence binary blobs out of read_file. */
        fun looksUtf8Text(bytes: ByteArray): Boolean {
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                when {
                    b == 0x00 -> {
                        return false
                    }

                    b < 0x20 && b != 9 && b != 10 && b != 12 && b != 13 -> {
                        return false
                    }

                    b < 0x80 -> {
                        i++
                    }

                    b < 0xC2 -> {
                        return false
                    }

                    b < 0xE0 -> {
                        if (i + 1 >= bytes.size || (bytes[i + 1].toInt() and 0xC0) != 0x80) return false
                        i += 2
                    }

                    b < 0xF0 -> {
                        if (i + 2 >= bytes.size) return false
                        if ((bytes[i + 1].toInt() and 0xC0) != 0x80 || (bytes[i + 2].toInt() and 0xC0) != 0x80) {
                            return false
                        }
                        i += 3
                    }

                    b < 0xF5 -> {
                        if (i + 3 >= bytes.size) return false
                        if ((bytes[i + 1].toInt() and 0xC0) != 0x80 ||
                            (bytes[i + 2].toInt() and 0xC0) != 0x80 ||
                            (bytes[i + 3].toInt() and 0xC0) != 0x80
                        ) {
                            return false
                        }
                        i += 4
                    }

                    else -> {
                        return false
                    }
                }
            }
            return true
        }
    }
}
