package com.accucodeai.kash.tools.ai.agent.tools

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.magic.KMagic
import com.accucodeai.kash.tools.ai.agent.AgentAttachments
import com.accucodeai.kash.tools.ai.agent.DiffRenderer
import com.accucodeai.kash.tools.ai.agent.KashAgentShell
import com.accucodeai.kash.tools.ai.agent.agentGlyphs
import com.accucodeai.kash.tools.ai.agent.openai.ContentPart
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The three concrete tools every kash agent session exposes —
// [ShellExecTool], [ReadFileTool], [WriteFileTool] — plus the small shared
// helpers below. Ported from the prior Koog `SimpleTool` plumbing; behavior
// identical except that arg-parse errors throw [IllegalArgumentException]
// with a precise message, which the session feeds back to the model as the
// tool's result (no more sentinel-wrapped malformed-args hack).

/** Cap on the per-tool-result text we'll send back to the model. */
internal const val MAX_OUTPUT_CHARS: Int = 16_000

/** Cap on an image we'll hand to the vision model (5 MiB raw). */
internal const val MAX_IMAGE_BYTES: Int = 5 * 1024 * 1024

// ANSI for the write_file diff palette. Unicode escape so the raw 0x1B
// byte survives source round-trips.
internal const val ANSI_RESET: String = "[0m"
internal const val ANSI_DIM: String = "[2m"
internal const val ANSI_RED: String = "[31m"
internal const val ANSI_GREEN: String = "[32m"

internal fun truncate(s: String): String =
    if (s.length <= MAX_OUTPUT_CHARS) {
        s
    } else {
        s.take(MAX_OUTPUT_CHARS) + "\n[…${s.length - MAX_OUTPUT_CHARS} more chars truncated]"
    }

/** Strict-ish UTF-8 validation — fence binary blobs out of read_file. */
internal fun looksUtf8Text(bytes: ByteArray): Boolean {
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

/**
 * Extract a required string field from a tool's [JsonObject] args. Throws
 * [IllegalArgumentException] with a model-readable message on miss / wrong
 * type — the message becomes the tool's result, prompting the model to
 * retry with the right shape.
 */
internal fun requireString(
    args: JsonObject,
    key: String,
    toolName: String,
): String {
    val v =
        args[key]
            ?: throw IllegalArgumentException(
                "$toolName: missing required argument `$key`. Send `$key` as a JSON string.",
            )
    val prim =
        (v as? JsonPrimitive)
            ?: throw IllegalArgumentException(
                "$toolName: argument `$key` must be a string, got ${v::class.simpleName}.",
            )
    if (!prim.isString) {
        throw IllegalArgumentException(
            "$toolName: argument `$key` must be a JSON string (quoted), got: ${prim.content}",
        )
    }
    return prim.content
}

internal class ShellExecTool(
    private val shell: KashAgentShell,
) : Tool {
    override val name: String = "shell_exec"
    override val description: String =
        "Run a command in the persistent non-interactive kash shell. Returns combined " +
            "stdout+stderr followed by an `exit: N` line. State (cwd, env, functions, " +
            "aliases) carries across calls within this session."
    override val paramsSchema: JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "command",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("The shell command line to execute."))
                        },
                    )
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("command")) })
        }

    override suspend fun execute(args: JsonObject): String {
        val cmd = requireString(args, "command", name)
        val r = shell.execute(cmd)
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

/**
 * One image `read_file` recognized this turn, queued for injection as a
 * vision content part on the next user message — a tool-result message is
 * text-only on the OpenAI wire format, so the bytes ride back via the
 * following user turn instead.
 */
internal data class PendingImage(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
)

internal class ReadFileTool(
    private val ctx: CommandContext,
    private val shell: KashAgentShell,
    private val onImage: (PendingImage) -> Unit,
) : Tool {
    override val name: String = "read_file"
    override val description: String =
        "Read a file from the kash virtual filesystem. Text files return their UTF-8 " +
            "content. Image files (PNG, JPEG, GIF, WebP, …) are shown to you directly as a " +
            "picture on the next step — read_file an image when you need to see it."
    override val paramsSchema: JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Absolute or cwd-relative path."))
                        },
                    )
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("path")) })
        }

    override suspend fun execute(args: JsonObject): String {
        val path = requireString(args, "path", name)
        val resolved = resolvePath(shell, ctx, path)
        val fs = ctx.fs
        if (!fs.exists(resolved)) return "error: file not found: $path"
        if (fs.isDirectory(resolved)) return "error: is a directory: $path"
        val bytes = fs.readBytes(resolved)
        // Content-sniff before the text check: an image is valid bytes but
        // not UTF-8, so it would otherwise hit the binary-refusal branch.
        val prefix = if (bytes.size > KMagic.PEEK_BYTES) bytes.copyOf(KMagic.PEEK_BYTES) else bytes
        val mime = KMagic.identify(prefix).mime
        if (mime.startsWith("image/")) {
            if (bytes.size > MAX_IMAGE_BYTES) {
                return "error: image is ${bytes.size} bytes (limit $MAX_IMAGE_BYTES); " +
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

internal class WriteFileTool(
    private val ctx: CommandContext,
    private val shell: KashAgentShell,
    /**
     * Where to render the diff. Defaults to stdout, which lands the block
     * between this tool's indicator and its result in the append-only
     * transcript (the renderer prints the indicator, executes the tool —
     * which emits this diff — then prints the result line).
     */
    private val emit: suspend (String) -> Unit = { ctx.stdout.writeUtf8(it) },
) : Tool {
    override val name: String = "write_file"
    override val description: String = "Write a UTF-8 text file. Overwrites if it exists."
    override val paramsSchema: JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Absolute or cwd-relative path."))
                        },
                    )
                    put(
                        "content",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Full UTF-8 content to write."))
                        },
                    )
                },
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("content"))
                },
            )
        }

    override suspend fun execute(args: JsonObject): String {
        val path = requireString(args, "path", name)
        val content = requireString(args, "content", name)
        val resolved = resolvePath(shell, ctx, path)
        // Capture prior content (null when creating) so we can render a
        // colorized unified diff — the diff header carries the path + ±
        // counts, so the returned string stays terse.
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
        ctx.fs.writeBytes(resolved, content.encodeToByteArray())
        try {
            // Render the diff into a buffer and emit it as ONE block, so it
            // appears as a single write between indicator and result.
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
                path = path,
                before = before,
                after = content,
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
        return "ok: wrote ${content.length} bytes to $path"
    }
}

/** Resolve a tool-supplied path against the subshell's cwd (else the ctx's). */
internal fun resolvePath(
    shell: KashAgentShell,
    ctx: CommandContext,
    path: String,
): String {
    if (path.startsWith("/")) return path
    val cwd = shell.cwd ?: ctx.cwd
    return if (cwd.endsWith("/")) "$cwd$path" else "$cwd/$path"
}

/**
 * Build the default [ToolRegistry] for one agent session. Wires the three
 * tools to the supplied shell + filesystem + image-pending callback.
 */
internal fun defaultToolRegistry(
    ctx: CommandContext,
    shell: KashAgentShell,
    onImage: (PendingImage) -> Unit,
): ToolRegistry =
    ToolRegistry(
        listOf(
            ShellExecTool(shell),
            ReadFileTool(ctx, shell, onImage),
            WriteFileTool(ctx, shell),
        ),
    )

/**
 * Convert a [PendingImage] to the corresponding OpenAI vision content part,
 * delegating to [AgentAttachments.Companion.imagePart] so both ingestion
 * paths emit byte-for-byte identical wire shapes.
 */
internal fun PendingImage.toContentPart(): ContentPart.Image = AgentAttachments.imagePart(bytes, mimeType)
