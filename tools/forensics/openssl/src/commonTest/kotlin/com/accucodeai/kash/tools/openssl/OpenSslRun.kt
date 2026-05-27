package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString

internal data class OpenSslRun(
    val exit: Int,
    val out: String,
    val err: String,
)

internal suspend fun runOpenssl(
    args: List<String>,
    stdin: String = "",
    fs: FileSystem = NullFs(),
    cwd: String = "/work",
    env: MutableMap<String, String> = mutableMapOf(),
): OpenSslRun {
    val outB = Buffer()
    val errB = Buffer()
    val inB = Buffer().apply { writeString(stdin) }
    val ctx =
        bareCommandContext(
            fs = fs,
            cwd = cwd,
            env = env,
            stdin = inB.asSuspendSource(),
            stdout = outB.asSuspendSink(),
            stderr = errB.asSuspendSink(),
        )
    val res = OpenSslCommand().run(args, ctx)
    return OpenSslRun(res.exitCode, outB.readString(), errB.readString())
}
