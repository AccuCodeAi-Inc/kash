package com.accucodeai.kash.tools.ai.agent.tools

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.AccessKind
import com.accucodeai.kash.fs.FileAccess
import com.accucodeai.kash.fs.mutations
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

/**
 * Render a unified-diff block for [path] (`before` → `after`) into [emit]
 * as one string, using the agent's glyphs and ANSI palette. `before == null`
 * renders a "new file" block. Best-effort: a render failure is swallowed
 * (the write it visualizes has already happened).
 */
internal suspend fun renderDiffBlock(
    emit: suspend (String) -> Unit,
    path: String,
    before: String?,
    after: String,
) {
    try {
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
            after = after,
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
}

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
    private val ctx: CommandContext,
    private val shell: KashAgentShell,
    /** Where edit diffs render — same convention as [WriteFileTool.emit]. */
    private val emit: suspend (String) -> Unit = { ctx.stdout.writeUtf8(it) },
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
        // Visualize any files the command edited, between this tool's
        // indicator and its result — same transcript slot write_file uses.
        // The model already sees the command output, so the diffs are for the
        // human; the tool result stays the raw stdout/stderr + exit line.
        renderShellEdits(emit, ctx, r.touched)
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

/** Cap on the number of edited files we'll render a diff for per command. */
internal const val MAX_SHELL_EDITS: Int = 20

/**
 * Render before→after diffs for the files a `shell_exec` command mutated,
 * driven by the [FileAccess] stream's [mutations][com.accucodeai.kash.fs.mutations]
 * (deduped to the first touch per path, which carries the true pre-command
 * `before`). `after` is the file's current content, read here.
 *
 * Per path: a CREATE renders as a new-file block; a WRITE with known text
 * `before` renders a unified diff (skipped when unchanged); a deletion renders
 * a one-line note; binary or before-unavailable cases degrade to an honest
 * note rather than a misleading diff. Pure metadata/symlink touches are
 * ignored. Best-effort: render failures are swallowed.
 */
internal suspend fun renderShellEdits(
    emit: suspend (String) -> Unit,
    ctx: CommandContext,
    touched: List<FileAccess>,
) {
    val mutations = touched.mutations()
    var shown = 0
    for (m in mutations) {
        if (m.kind == AccessKind.META || m.kind == AccessKind.SYMLINK) continue
        if (shown >= MAX_SHELL_EDITS) {
            val remaining = mutations.count { it.kind != AccessKind.META && it.kind != AccessKind.SYMLINK } - shown
            if (remaining > 0) emit(shellEditNote("… and $remaining more file(s) changed"))
            break
        }
        val path = m.path
        val afterBytes =
            try {
                if (ctx.fs.exists(path) && !ctx.fs.isDirectory(path)) ctx.fs.readBytes(path) else null
            } catch (_: Throwable) {
                null
            }
        if (afterBytes == null) {
            // Gone after the command, or unreadable → a deletion.
            if (m.kind == AccessKind.DELETE) {
                emit(shellEditNote("deleted: $path"))
                shown++
            }
            continue
        }
        if (!looksUtf8Text(afterBytes)) {
            emit(shellEditNote("wrote: $path (binary, ${afterBytes.size} bytes)"))
            shown++
            continue
        }
        val after = afterBytes.decodeToString()
        // before == null renders a "new file" block; unknownBefore means a
        // WRITE whose prior content we couldn't capture (read failed, binary,
        // or a zero-write truncate) — degrade to a note, never a fake diff.
        val beforeBytes = m.before
        val before: String?
        val unknownBefore: Boolean
        when {
            m.kind == AccessKind.CREATE -> {
                before = null
                unknownBefore = false
            }

            beforeBytes == null || !looksUtf8Text(beforeBytes) -> {
                before = null
                unknownBefore = true
            }

            else -> {
                before = beforeBytes.decodeToString()
                unknownBefore = false
            }
        }
        when {
            unknownBefore -> {
                emit(shellEditNote("modified: $path (${afterBytes.size} bytes; diff unavailable)"))
            }

            before == after -> {}

            // no net content change (e.g. rewrite-in-place)
            else -> {
                renderDiffBlock(emit, path, before, after)
            }
        }
        shown++
    }
}

/** A dim, single-line note for a shell edit we can't render as a full diff. */
private fun shellEditNote(text: String): String = "$ANSI_DIM$text$ANSI_RESET\n"

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
        // Render the diff as ONE block, so it appears as a single write
        // between the tool indicator and its result.
        renderDiffBlock(emit, path, before, content)
        return "ok: wrote ${content.length} bytes to $path"
    }
}

/**
 * Read an optional boolean field from a tool's [JsonObject] args. Returns
 * [default] when the key is absent; throws [IllegalArgumentException] with a
 * model-readable message when present but not a JSON boolean.
 */
internal fun optionalBoolean(
    args: JsonObject,
    key: String,
    toolName: String,
    default: Boolean,
): Boolean {
    val v = args[key] ?: return default
    val prim =
        (v as? JsonPrimitive)
            ?: throw IllegalArgumentException(
                "$toolName: argument `$key` must be a boolean, got ${v::class.simpleName}.",
            )
    return when (prim.content) {
        "true" -> true

        "false" -> false

        else -> throw IllegalArgumentException(
            "$toolName: argument `$key` must be `true` or `false`, got: ${prim.content}",
        )
    }
}

internal class EditFileTool(
    private val ctx: CommandContext,
    private val shell: KashAgentShell,
    private val emit: suspend (String) -> Unit = { ctx.stdout.writeUtf8(it) },
) : Tool {
    override val name: String = "edit_file"
    override val description: String =
        "Edit a UTF-8 text file by replacing an exact string. `old_string` must match the " +
            "file content verbatim (including indentation and newlines). By default it must " +
            "match EXACTLY ONCE — if it appears multiple times the edit fails, so include " +
            "enough surrounding context to make the match unique. Set `replace_all: true` to " +
            "replace every occurrence instead. Cheaper and safer than write_file for changing " +
            "part of an existing file — prefer it over rewriting the whole file."
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
                        "old_string",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Exact text to replace."))
                        },
                    )
                    put(
                        "new_string",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Replacement text."))
                        },
                    )
                    put(
                        "replace_all",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Replace every occurrence instead of requiring a unique match. Default false.",
                                ),
                            )
                        },
                    )
                },
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("old_string"))
                    add(JsonPrimitive("new_string"))
                },
            )
        }

    override suspend fun execute(args: JsonObject): String {
        val path = requireString(args, "path", name)
        val oldString = requireString(args, "old_string", name)
        val newString = requireString(args, "new_string", name)
        val replaceAll = optionalBoolean(args, "replace_all", name, default = false)
        if (oldString == newString) {
            return "error: old_string and new_string are identical — nothing to change."
        }
        val resolved = resolvePath(shell, ctx, path)
        val fs = ctx.fs
        if (!fs.exists(resolved)) return "error: file not found: $path"
        if (fs.isDirectory(resolved)) return "error: is a directory: $path"
        val bytes = fs.readBytes(resolved)
        if (!looksUtf8Text(bytes)) {
            return "error: file is not valid UTF-8 text; edit_file only works on text files."
        }
        val before = bytes.decodeToString()
        val occurrences = before.countOccurrences(oldString)
        if (occurrences == 0) {
            return "error: old_string not found in $path. It must match the file content " +
                "verbatim, including whitespace and newlines."
        }
        if (occurrences > 1 && !replaceAll) {
            return "error: old_string matches $occurrences times in $path. Add surrounding " +
                "context to make it unique, or pass `replace_all: true` to replace all."
        }
        val after =
            if (replaceAll) {
                before.replace(oldString, newString)
            } else {
                before.replaceFirst(oldString, newString)
            }
        fs.writeBytes(resolved, after.encodeToByteArray())
        renderDiffBlock(emit, path, before, after)
        val n = if (replaceAll) occurrences else 1
        return "ok: replaced $n occurrence${if (n == 1) "" else "s"} in $path"
    }
}

/** Count non-overlapping occurrences of [needle] in this string. */
private fun String.countOccurrences(needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var from = 0
    while (true) {
        val idx = indexOf(needle, from)
        if (idx < 0) break
        count++
        from = idx + needle.length
    }
    return count
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
 * Build the default [ToolRegistry] for one agent session. Wires the tools to
 * the supplied shell + filesystem + image-pending callback.
 */
internal fun defaultToolRegistry(
    ctx: CommandContext,
    shell: KashAgentShell,
    onImage: (PendingImage) -> Unit,
): ToolRegistry =
    ToolRegistry(
        listOf(
            ShellExecTool(ctx, shell),
            ReadFileTool(ctx, shell, onImage),
            WriteFileTool(ctx, shell),
            EditFileTool(ctx, shell),
        ),
    )

/**
 * Convert a [PendingImage] to the corresponding OpenAI vision content part,
 * delegating to [AgentAttachments.Companion.imagePart] so both ingestion
 * paths emit byte-for-byte identical wire shapes.
 */
internal fun PendingImage.toContentPart(): ContentPart.Image = AgentAttachments.imagePart(bytes, mimeType)
