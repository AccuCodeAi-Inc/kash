package com.accucodeai.kash.tools.make

import com.accucodeai.kash.tools.make.parser.parseMakefile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class MakefileParserTest {
    @Test fun `simple assignment recursive`() {
        val mf = parseMakefile("FOO = bar baz\n")
        val a = mf.statements.single() as Assignment
        assertEquals("FOO", a.name)
        assertEquals(MacroFlavor.RECURSIVE, a.flavor)
        assertEquals("bar baz", a.value)
    }

    @Test fun `assignment flavors`() {
        val src =
            """
            A = 1
            B := 2
            C ::= 3
            D :::= 4
            E ?= 5
            F += 6
            G != 7
            """.trimIndent()
        val mf = parseMakefile(src + "\n")
        val flavors = mf.statements.filterIsInstance<Assignment>().associate { it.name to it.flavor }
        assertEquals(MacroFlavor.RECURSIVE, flavors["A"])
        assertEquals(MacroFlavor.IMMEDIATE, flavors["B"])
        assertEquals(MacroFlavor.IMMEDIATE, flavors["C"])
        assertEquals(MacroFlavor.IMMEDIATE_TRIPLE, flavors["D"])
        assertEquals(MacroFlavor.CONDITIONAL, flavors["E"])
        assertEquals(MacroFlavor.APPEND, flavors["F"])
        assertEquals(MacroFlavor.SHELL, flavors["G"])
    }

    @Test fun `rule with single recipe line`() {
        val mf = parseMakefile("hello: hello.c\n\tcc -o hello hello.c\n")
        val r = mf.statements.single() as RuleStmt
        assertEquals(listOf("hello"), r.targets)
        assertEquals(listOf("hello.c"), r.prereqs)
        assertEquals(listOf("cc -o hello hello.c"), r.recipes)
    }

    @Test fun `rule with multiple recipe lines`() {
        val mf = parseMakefile("foo:\n\tcmd1\n\tcmd2\n\tcmd3\n")
        val r = mf.statements.single() as RuleStmt
        assertEquals(listOf("cmd1", "cmd2", "cmd3"), r.recipes)
    }

    @Test fun `rule with semicolon inline recipe`() {
        val mf = parseMakefile("foo: bar; echo hi\n")
        val r = mf.statements.single() as RuleStmt
        assertEquals("echo hi", r.inlineRecipe)
    }

    @Test fun `pattern rule detection`() {
        val mf = parseMakefile("%.o: %.c\n\tcc -c \$<\n")
        val r = mf.statements.single() as RuleStmt
        assertTrue(r.isPattern)
    }

    @Test fun `multi-target rule`() {
        val mf = parseMakefile("a b c: dep\n\techo \$@\n")
        val r = mf.statements.single() as RuleStmt
        assertEquals(listOf("a", "b", "c"), r.targets)
    }

    @Test fun `comments are skipped`() {
        val mf = parseMakefile("# a comment\nA = 1  # trailing\nB = 2\n")
        val assigns = mf.statements.filterIsInstance<Assignment>().map { it.name }
        assertEquals(listOf("A", "B"), assigns)
    }

    @Test fun `blank lines are tolerated`() {
        val mf = parseMakefile("\n\nA = 1\n\n\nB = 2\n\n")
        assertEquals(2, mf.statements.size)
    }

    @Test fun `include directive`() {
        val mf = parseMakefile("include foo.mk bar.mk\n")
        val inc = mf.statements.single() as IncludeStmt
        assertEquals(listOf("foo.mk", "bar.mk"), inc.paths)
        assertEquals(false, inc.optional)
    }

    @Test fun `dash include is optional`() {
        val mf = parseMakefile("-include opt.mk\n")
        val inc = mf.statements.single() as IncludeStmt
        assertTrue(inc.optional)
    }

    @Test fun `ifeq conditional`() {
        val src =
            """
            ifeq (a,a)
            A = yes
            else
            A = no
            endif
            """.trimIndent() + "\n"
        val mf = parseMakefile(src)
        val cond = mf.statements.single() as ConditionalStmt
        assertEquals(1, cond.branches.size)
        assertEquals(CondKind.IFEQ, cond.branches[0].kind)
        assertEquals(1, cond.branches[0].body.size)
        assertEquals(1, cond.elseBody?.size)
    }

    @Test fun `ifdef ifndef`() {
        val src = "ifdef FOO\nA = 1\nendif\nifndef BAR\nB = 2\nendif\n"
        val mf = parseMakefile(src)
        val c1 = mf.statements[0] as ConditionalStmt
        val c2 = mf.statements[1] as ConditionalStmt
        assertEquals(CondKind.IFDEF, c1.branches[0].kind)
        assertEquals(CondKind.IFNDEF, c2.branches[0].kind)
    }

    @Test fun `define endef multi-line`() {
        val src = "define BODY\nline one\nline two\nendef\n"
        val mf = parseMakefile(src)
        val d = mf.statements.single() as DefineStmt
        assertEquals("BODY", d.name)
        assertTrue("line one" in d.body && "line two" in d.body)
    }

    @Test fun `export with assignment`() {
        val mf = parseMakefile("export FOO = bar\n")
        val e = mf.statements.single() as ExportStmt
        assertEquals(listOf("FOO"), e.names)
        assertEquals("bar", e.embedded?.value)
    }

    @Test fun `backslash line continuation joins`() {
        val mf = parseMakefile("FOO = a \\\nb \\\nc\n")
        val a = mf.statements.single() as Assignment
        assertTrue("a" in a.value && "b" in a.value && "c" in a.value)
    }

    @Test fun `phony as ordinary target`() {
        val mf = parseMakefile(".PHONY: clean\nclean:\n\trm -f *.o\n")
        assertEquals(2, mf.statements.size)
    }

    @Test fun `unknown directive errors on conditional misuse`() {
        try {
            parseMakefile("else\n")
            fail("should have thrown")
        } catch (_: MakeParseError) {
        }
    }
}
