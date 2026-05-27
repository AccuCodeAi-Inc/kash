package com.accucodeai.kash.tools.make

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.ShellInvocation
import com.accucodeai.kash.api.ShellRunner
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.io.Buffer
import kotlinx.io.writeString

internal data class TestCtx(
    val ctx: CommandContext,
    val out: Buffer,
    val err: Buffer,
    val fs: InMemoryFs,
    val log: MutableList<String>,
)

internal suspend fun makeCtx(
    files: Map<String, Pair<String, Long>> = emptyMap(),
    stdin: String = "",
    env: MutableMap<String, String> = mutableMapOf(),
    cwd: String = "/work",
    customRunner: ShellRunner? = null,
): TestCtx {
    val log = mutableListOf<String>()
    var clockNow = 1_000L
    val fs = InMemoryFs(clock = { clockNow })
    fs.mkdirs(cwd)
    for ((path, payload) in files) {
        val (content, mtime) = payload
        val abs = if (path.startsWith("/")) path else "$cwd/$path"
        clockNow = mtime
        fs.writeBytesSync(abs, content.encodeToByteArray())
        fs.setMtime(abs, mtime)
    }
    clockNow = 9_999_999L
    val out = Buffer()
    val err = Buffer()
    val inBuf = Buffer().also { if (stdin.isNotEmpty()) it.writeString(stdin) }
    val runner =
        customRunner ?: ShellRunner { inv ->
            log += inv.script
            // Default success runner with a tiny "shell": handle `echo X >file` and `touch FILE`.
            handleFakeShell(inv, fs, cwd, log)
        }
    val ctx =
        bareCommandContext(
            fs = fs,
            env = env,
            cwd = cwd,
            stdin = inBuf.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            shellRunner = runner,
        )
    return TestCtx(ctx, out, err, fs, log)
}

private suspend fun InMemoryFs.writeBytesSync(
    path: String,
    bytes: ByteArray,
) {
    writeBytes(path, bytes)
}

internal suspend fun handleFakeShell(
    inv: ShellInvocation,
    fs: InMemoryFs,
    cwd: String,
    log: MutableList<String>,
): Int {
    val script = inv.script.trim()
    // Very small shell-emulation good enough for the make tests.
    // Support: echo TEXT, echo TEXT > FILE, : (no-op), false, true,
    //   touch FILE, cat FILE, cp A B, rm -f F..., printf FMT ARGS
    val tokens = tokenize(script)
    if (tokens.isEmpty()) return 0
    when (tokens[0]) {
        ":", "true" -> {
            return 0
        }

        "false" -> {
            return 1
        }

        "echo" -> {
            val rest = tokens.drop(1)
            val gtIdx = rest.indexOf(">")
            if (gtIdx >= 0 && gtIdx + 1 < rest.size) {
                val msg = rest.subList(0, gtIdx).joinToString(" ")
                val path = resolve(cwd, rest[gtIdx + 1])
                fs.writeBytes(path, (msg + "\n").encodeToByteArray())
                return 0
            }
            inv.stdout.writeUtf8(rest.joinToString(" ") + "\n")
            return 0
        }

        "touch" -> {
            for (t in tokens.drop(1)) {
                val abs = resolve(cwd, t)
                if (!fs.exists(abs)) fs.writeBytes(abs, ByteArray(0))
                fs.setMtime(abs, 9_999_999L)
            }
            return 0
        }

        "rm" -> {
            for (t in tokens.drop(1).filterNot { it.startsWith("-") }) {
                val abs = resolve(cwd, t)
                if (fs.exists(abs)) fs.remove(abs)
            }
            return 0
        }

        "cat" -> {
            for (t in tokens.drop(1)) {
                val abs = resolve(cwd, t)
                if (fs.exists(abs)) inv.stdout.writeUtf8(fs.readBytes(abs).decodeToString())
            }
            return 0
        }

        "cp" -> {
            if (tokens.size >= 3) {
                val src = resolve(cwd, tokens[1])
                val dst = resolve(cwd, tokens[2])
                fs.writeBytes(dst, fs.readBytes(src))
                return 0
            }
            return 1
        }

        "cc" -> {
            // mimic compiler: `cc -o OUT IN` writes OUT
            val outIdx = tokens.indexOf("-o")
            if (outIdx >= 0 && outIdx + 1 < tokens.size) {
                val outPath = resolve(cwd, tokens[outIdx + 1])
                fs.writeBytes(outPath, "compiled\n".encodeToByteArray())
                fs.setMtime(outPath, 9_999_999L)
            }
            return 0
        }

        "exit" -> {
            return tokens.getOrNull(1)?.toIntOrNull() ?: 0
        }

        else -> {
            // Unknown command — just succeed to keep tests resilient.
            return 0
        }
    }
}

private fun tokenize(s: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inSingle = false
    var inDouble = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            !inSingle && !inDouble && c.isWhitespace() -> {
                if (sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.clear()
                }
            }

            c == '\'' && !inDouble -> {
                inSingle = !inSingle
            }

            c == '"' && !inSingle -> {
                inDouble = !inDouble
            }

            c == '\\' && i + 1 < s.length -> {
                sb.append(s[i + 1])
                i++
            }

            else -> {
                sb.append(c)
            }
        }
        i++
    }
    if (sb.isNotEmpty()) out += sb.toString()
    return out
}

private fun resolve(
    cwd: String,
    p: String,
): String = if (p.startsWith("/")) p else "$cwd/$p"
