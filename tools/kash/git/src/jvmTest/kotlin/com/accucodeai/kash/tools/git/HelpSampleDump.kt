package com.accucodeai.kash.tools.git

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.junit.jupiter.api.Test

/**
 * Not a real test — dumps the help output to stdout once during the
 * JVM test run so a maintainer can eyeball formatting after touching
 * the help registry. Always passes; reads better than capturing
 * massive snapshots into the test sources.
 */
class HelpSampleDump {
    @Test fun dumpHelpOverviewAndOneSub() {
        runTest {
            val out1 = Buffer()
            val ctx1 =
                bareCommandContext(
                    fs = InMemoryFs(),
                    env = mutableMapOf(),
                    cwd = "/",
                    stdin = Buffer().asSuspendSource(),
                    stdout = out1.asSuspendSink(),
                    stderr = Buffer().asSuspendSink(),
                )
            GitCommand().run(listOf("help"), ctx1)
            println("===== git help =====")
            println(out1.readString())

            val out2 = Buffer()
            val ctx2 =
                bareCommandContext(
                    fs = InMemoryFs(),
                    env = mutableMapOf(),
                    cwd = "/",
                    stdin = Buffer().asSuspendSource(),
                    stdout = out2.asSuspendSink(),
                    stderr = Buffer().asSuspendSink(),
                )
            GitCommand().run(listOf("help", "merge"), ctx2)
            println("===== git help merge =====")
            println(out2.readString())
        }
    }
}
