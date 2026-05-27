package com.accucodeai.kash.tools.make

import com.accucodeai.kash.api.ShellRunner
import com.accucodeai.kash.api.io.readUtf8Text
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real-world Makefile recipes: each test feeds a small but realistic
 * Makefile through the full pipeline (parse → macro env → DAG → executor)
 * and asserts the externally observable behavior. These are the cases an
 * AI-generated shell script will actually hit.
 */
class MakeRecipeTest {
    @Test fun `classic build with dep then clean phony`() =
        runTest {
            val src =
                """
                CC = cc
                CFLAGS = -O2

                all: hello

                hello: hello.c
                	${'$'}(CC) ${'$'}(CFLAGS) -o ${'$'}@ ${'$'}<

                .PHONY: clean all
                clean:
                	rm -f hello
                """.trimIndent() + "\n"
            val (ctx, _, _, fs, log) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to (src to 100L),
                            "hello.c" to ("int main(){}\n" to 200L),
                        ),
                )
            val rc1 = MakeCommand().run(emptyList(), ctx)
            assertEquals(0, rc1.exitCode)
            assertTrue(fs.exists("/work/hello"))
            assertEquals(1, log.size)
            assertTrue("cc -O2 -o hello hello.c" in log[0])

            // Clean phony target
            val (ctx2, _, _, fs2, log2) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to (src to 100L),
                            "hello.c" to ("int main(){}\n" to 200L),
                            "hello" to ("compiled\n" to 300L),
                        ),
                )
            val rc2 = MakeCommand().run(listOf("clean"), ctx2)
            assertEquals(0, rc2.exitCode)
            assertEquals(listOf("rm -f hello"), log2)
        }

    @Test fun `incremental rebuild only rebuilds stale objects`() =
        runTest {
            val src =
                """
                OBJS = a.o b.o
                all: app
                app: ${'$'}(OBJS)
                	cc -o ${'$'}@ ${'$'}^
                %.o: %.c
                	cc -c -o ${'$'}@ ${'$'}<
                """.trimIndent() + "\n"
            val (ctx, _, _, _, log) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to (src to 100L),
                            "a.c" to ("a\n" to 500L),
                            "b.c" to ("b\n" to 200L),
                            "a.o" to ("old\n" to 300L),
                            "b.o" to ("ok\n" to 300L),
                            "app" to ("app\n" to 400L),
                        ),
                )
            val rc = MakeCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode)
            // a.c (mtime 500) newer than a.o (300) → a.o rebuilds.
            // b.c (200) older than b.o (300) → b.o skipped.
            // a.o regen makes app stale → app rebuilds.
            assertEquals(2, log.size)
            assertTrue(log.any { it.contains("a.c") })
            assertFalse(log.any { it == "cc -c -o b.o b.c" })
            assertTrue(log.any { it.startsWith("cc -o app") })
        }

    @Test fun `cli macro override and append cooperate`() =
        runTest {
            val src =
                """
                CFLAGS = -O2
                CFLAGS += -Wall
                .PHONY: show
                show:
                	echo ${'$'}(CFLAGS)
                """.trimIndent() + "\n"
            val (ctx, out, _, _, _) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("CFLAGS=-g", "show"), ctx)
            assertEquals(0, rc.exitCode)
            val outStr = out.readUtf8Text()
            assertTrue("-g" in outStr)
        }

    @Test fun `pattern rule with substitution and auto vars`() =
        runTest {
            val src =
                """
                SRCS = foo.c bar.c
                OBJS = ${'$'}(SRCS:.c=.o)
                .PHONY: all
                all: ${'$'}(OBJS)
                %.o: %.c
                	cc -c -o ${'$'}@ ${'$'}<
                """.trimIndent() + "\n"
            val (ctx, _, _, _, log) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to (src to 100L),
                            "foo.c" to ("c\n" to 200L),
                            "bar.c" to ("c\n" to 200L),
                        ),
                )
            val rc = MakeCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(2, log.size)
            assertTrue(log.any { it == "cc -c -o foo.o foo.c" })
            assertTrue(log.any { it == "cc -c -o bar.o bar.c" })
        }

    @Test fun `nested ifeq with macro expansion`() =
        runTest {
            val src =
                """
                OS = linux
                ifeq (${'$'}(OS),linux)
                EXT = .so
                else
                ifeq (${'$'}(OS),darwin)
                EXT = .dylib
                else
                EXT = .dll
                endif
                endif
                .PHONY: show
                show:
                	echo ${'$'}(EXT)
                """.trimIndent() + "\n"
            val (ctx, out, _, _, _) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("show"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue(".so" in out.readUtf8Text())
        }

    @Test fun `define multiline macro echoed back`() =
        runTest {
            val src =
                """
                define GREETING
                hello
                world
                endef
                .PHONY: g
                g:
                	echo ${'$'}(GREETING)
                """.trimIndent() + "\n"
            val (ctx, _, _, _, log) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("g"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(1, log.size)
            assertTrue("hello" in log[0] && "world" in log[0])
        }

    @Test fun `dry-run prints recipes without executing`() =
        runTest {
            val src =
                """
                .PHONY: x
                x:
                	echo would-do-something
                """.trimIndent() + "\n"
            val (ctx, out, _, _, log) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("-n", "x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(0, log.size)
            assertTrue("echo would-do-something" in out.readUtf8Text())
        }

    @Test fun `recursive make-through-shellrunner via dollar MAKE`() =
        runTest {
            val src = ".PHONY: x\nx:\n\techo \$(MAKE) version\n"
            val (ctx, _, _, _, log) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(listOf("echo make version"), log)
        }

    @Test fun `nested function call subst plus patsubst`() =
        runTest {
            val src =
                """
                FILES = a.c b.h c.c
                CFILES = ${'$'}(filter %.c,${'$'}(FILES))
                OFILES = ${'$'}(patsubst %.c,build/%.o,${'$'}(CFILES))
                .PHONY: show
                show:
                	echo ${'$'}(OFILES)
                """.trimIndent() + "\n"
            val (ctx, out, _, _, _) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("show"), ctx)
            assertEquals(0, rc.exitCode)
            val outStr = out.readUtf8Text()
            assertTrue("build/a.o" in outStr && "build/c.o" in outStr)
            assertFalse("b.h" in outStr)
        }

    @Test fun `chained dep rebuilds propagate up`() =
        runTest {
            val src =
                """
                final: mid
                	cp mid final
                mid: src
                	cp src mid
                """.trimIndent() + "\n"
            val (ctx, _, _, fs, log) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to (src to 100L),
                            "src" to ("seed\n" to 500L),
                            "mid" to ("old\n" to 200L),
                            "final" to ("old\n" to 300L),
                        ),
                )
            val rc = MakeCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(2, log.size)
            assertTrue(fs.exists("/work/final"))
        }

    @Test fun `silent option suppresses all echo`() =
        runTest {
            val src =
                """
                .PHONY: x y
                x:
                	echo x-cmd
                y:
                	echo y-cmd
                """.trimIndent() + "\n"
            val (ctx, out, _, _, _) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("-s", "x", "y"), ctx)
            assertEquals(0, rc.exitCode)
            val outStr = out.readUtf8Text()
            assertFalse("echo x-cmd" in outStr)
            assertFalse("echo y-cmd" in outStr)
        }

    @Test fun `shell-assignment captures stdout`() =
        runTest {
            val src =
                """
                NAME != echo my-name
                .PHONY: show
                show:
                	echo ${'$'}(NAME)
                """.trimIndent() + "\n"
            val (ctx, out, _, _, _) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("show"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue("my-name" in out.readUtf8Text())
        }

    @Test fun `failing recipe stops the build by default`() =
        runTest {
            val failingRunner =
                ShellRunner { inv -> if ("FAIL" in inv.script) 1 else 0 }
            val src =
                """
                .PHONY: x y
                all: x y
                x:
                	FAIL
                y:
                	echo y
                """.trimIndent() + "\n"
            val (ctx, _, err, _, _) =
                makeCtx(
                    files = mapOf("Makefile" to (src to 100L)),
                    customRunner = failingRunner,
                )
            val rc = MakeCommand().run(listOf("all"), ctx)
            assertEquals(2, rc.exitCode)
            val errStr = err.readUtf8Text()
            assertTrue("[x] Error 1" in errStr || "*** [x]" in errStr)
        }
}
