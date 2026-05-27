package com.accucodeai.kash.tools.make

import com.accucodeai.kash.api.ShellRunner
import com.accucodeai.kash.api.io.readUtf8Text
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MakeCommandTest {
    @Test fun `version flag exits zero`() =
        runTest {
            val (ctx, out, _, _, _) = makeCtx()
            val rc = MakeCommand().run(listOf("--version"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue("kash make" in out.readUtf8Text())
        }

    @Test fun `stale target rebuilds`() =
        runTest {
            val (ctx, _, _, fs, log) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to ("hello: hello.c\n\tcc -o hello hello.c\n" to 100L),
                            "hello.c" to ("int main(){}\n" to 200L),
                            "hello" to ("old\n" to 50L),
                        ),
                )
            val rc = MakeCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(1, log.size)
            assertEquals("cc -o hello hello.c", log[0])
            assertTrue(fs.exists("/work/hello"))
        }

    @Test fun `fresh target is skipped`() =
        runTest {
            val (ctx, _, _, _, log) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to ("hello: hello.c\n\tcc -o hello hello.c\n" to 100L),
                            "hello.c" to ("c\n" to 200L),
                            "hello" to ("ok\n" to 300L),
                        ),
                )
            val rc = MakeCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(0, log.size)
        }

    @Test fun `phony target always runs`() =
        runTest {
            val (ctx, _, _, _, log) =
                makeCtx(
                    files = mapOf("Makefile" to (".PHONY: clean\nclean:\n\trm -f junk\n" to 100L)),
                )
            val rc = MakeCommand().run(listOf("clean"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(listOf("rm -f junk"), log)
        }

    @Test fun `pattern rule builds multiple objects`() =
        runTest {
            val src =
                """
                all: a.o b.o
                %.o: %.c
                	cc -c -o ${'$'}@ ${'$'}<
                """.trimIndent() + "\n"
            val (ctx, _, _, fs, log) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to (src to 100L),
                            "a.c" to ("a\n" to 200L),
                            "b.c" to ("b\n" to 200L),
                        ),
                )
            val rc = MakeCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(2, log.size)
            assertTrue(log.any { it.contains("a.o") && it.contains("a.c") })
            assertTrue(log.any { it.contains("b.o") && it.contains("b.c") })
        }

    @Test fun `dry run does not invoke recipes`() =
        runTest {
            val (ctx, _, _, _, log) =
                makeCtx(
                    files = mapOf("Makefile" to (".PHONY: all\nall:\n\techo hi\n" to 100L)),
                )
            val rc = MakeCommand().run(listOf("-n", "all"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(0, log.size)
        }

    @Test fun `keep going after independent target failure`() =
        runTest {
            val invoked = mutableListOf<String>()
            val failingRunner =
                ShellRunner { inv ->
                    invoked += inv.script
                    if ("FAIL" in inv.script) 1 else 0
                }
            val src =
                """
                .PHONY: a b c all
                all: a b c
                a:
                	echo a
                b:
                	FAIL
                c:
                	echo c
                """.trimIndent() + "\n"
            val (ctx, _, err, _, _) =
                makeCtx(
                    files = mapOf("Makefile" to (src to 100L)),
                    customRunner = failingRunner,
                )
            val rc = MakeCommand().run(listOf("-k", "all"), ctx)
            assertEquals(2, rc.exitCode)
            val errStr = err.readUtf8Text()
            assertTrue("Error 1" in errStr || "*** [b]" in errStr || invoked.size >= 3)
        }

    @Test fun `silent prefix suppresses echo`() =
        runTest {
            val (ctx, out, _, _, _) =
                makeCtx(
                    files = mapOf("Makefile" to (".PHONY: x\nx:\n\t@echo hi\n" to 100L)),
                )
            val rc = MakeCommand().run(listOf("x"), ctx)
            assertEquals(0, rc.exitCode)
            assertFalse("@echo" in out.readUtf8Text())
        }

    @Test fun `ignore-error prefix tolerates failure`() =
        runTest {
            val src =
                """
                .PHONY: x
                x:
                	-FAIL
                	echo ok
                """.trimIndent() + "\n"
            val invoked = mutableListOf<String>()
            val failingRunner =
                ShellRunner { inv ->
                    invoked += inv.script
                    if ("FAIL" in inv.script) 1 else 0
                }
            val (ctx, _, _, _, _) =
                makeCtx(
                    files = mapOf("Makefile" to (src to 100L)),
                    customRunner = failingRunner,
                )
            val rc = MakeCommand().run(listOf("x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(2, invoked.size)
        }

    @Test fun `include errors on missing file`() =
        runTest {
            val (ctx, _, err, _, _) =
                makeCtx(files = mapOf("Makefile" to ("include nope.mk\n" to 100L)))
            val rc = MakeCommand().run(listOf("anything"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue("nope.mk" in err.readUtf8Text())
        }

    @Test fun `dash-include succeeds when file missing`() =
        runTest {
            val src = "-include nope.mk\n.PHONY: x\nx:\n\techo hi\n"
            val (ctx, _, _, _, log) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(1, log.size)
        }

    @Test fun `ifeq gates assignment`() =
        runTest {
            val src =
                """
                MODE = debug
                ifeq (${'$'}(MODE),debug)
                FLAGS = -g
                else
                FLAGS = -O2
                endif
                .PHONY: x
                x:
                	echo ${'$'}(FLAGS)
                """.trimIndent() + "\n"
            val (ctx, out, _, _, _) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("x"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue("-g" in out.readUtf8Text())
        }

    @Test fun `auto vars at-sign and lt`() =
        runTest {
            val src =
                """
                target: dep1 dep2
                	echo ${'$'}@ from ${'$'}<
                """.trimIndent() + "\n"
            val (ctx, _, _, _, log) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to (src to 100L),
                            "dep1" to ("x" to 100L),
                            "dep2" to ("y" to 100L),
                        ),
                )
            val rc = MakeCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(listOf("echo target from dep1"), log)
        }

    @Test fun `auto vars caret all-deps`() =
        runTest {
            val src = "target: a b c\n\techo \$^\n"
            val (ctx, _, _, _, log) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to (src to 100L),
                            "a" to ("" to 100L),
                            "b" to ("" to 100L),
                            "c" to ("" to 100L),
                        ),
                )
            val rc = MakeCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(listOf("echo a b c"), log)
        }

    @Test fun `cli macro overrides file`() =
        runTest {
            val src = "X = file\n.PHONY: t\nt:\n\techo \$(X)\n"
            val (ctx, out, _, _, _) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("X=cli", "t"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue("cli" in out.readUtf8Text())
        }

    @Test fun `default target is first non-special`() =
        runTest {
            val src =
                """
                .PHONY: a b
                a:
                	echo a
                b:
                	echo b
                """.trimIndent() + "\n"
            val (ctx, _, _, _, log) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(listOf("echo a"), log)
        }

    @Test fun `recipe combined prefixes silent and ignore`() =
        runTest {
            val src = ".PHONY: x\nx:\n\t@-FAIL\n"
            val failingRunner = ShellRunner { inv -> if ("FAIL" in inv.script) 1 else 0 }
            val (ctx, out, _, _, _) =
                makeCtx(
                    files = mapOf("Makefile" to (src to 100L)),
                    customRunner = failingRunner,
                )
            val rc = MakeCommand().run(listOf("x"), ctx)
            assertEquals(0, rc.exitCode)
            assertFalse("@" in out.readUtf8Text())
        }

    @Test fun `-C uses given directory`() =
        runTest {
            val (ctx, _, _, _, log) =
                makeCtx(
                    files =
                        mapOf(
                            "/work/sub/Makefile" to (".PHONY: x\nx:\n\techo sub\n" to 100L),
                        ),
                )
            val rc = MakeCommand().run(listOf("-C", "sub", "x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(listOf("echo sub"), log)
        }

    @Test fun `-f reads given file`() =
        runTest {
            val (ctx, _, _, _, log) =
                makeCtx(
                    files = mapOf("custom.mk" to (".PHONY: x\nx:\n\techo custom\n" to 100L)),
                )
            val rc = MakeCommand().run(listOf("-f", "custom.mk", "x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(listOf("echo custom"), log)
        }

    @Test fun `no rule for target reports error`() =
        runTest {
            val (ctx, _, err, _, _) =
                makeCtx(files = mapOf("Makefile" to ("A = 1\n" to 100L)))
            val rc = MakeCommand().run(listOf("nope"), ctx)
            assertEquals(2, rc.exitCode)
            val errStr = err.readUtf8Text()
            assertTrue("nope" in errStr)
        }

    @Test fun `bare include of mk file`() =
        runTest {
            val src = "include sub.mk\n.PHONY: x\nx:\n\techo \$(FROM_INC)\n"
            val (ctx, out, _, _, _) =
                makeCtx(
                    files =
                        mapOf(
                            "Makefile" to (src to 100L),
                            "sub.mk" to ("FROM_INC = included\n" to 100L),
                        ),
                )
            val rc = MakeCommand().run(listOf("x"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue("included" in out.readUtf8Text())
        }

    @Test fun `inline semicolon recipe`() =
        runTest {
            val src = ".PHONY: x\nx: ; echo inline\n"
            val (ctx, _, _, _, log) =
                makeCtx(files = mapOf("Makefile" to (src to 100L)))
            val rc = MakeCommand().run(listOf("x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(listOf("echo inline"), log)
        }
}
