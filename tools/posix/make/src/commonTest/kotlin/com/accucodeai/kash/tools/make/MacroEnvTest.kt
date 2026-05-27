package com.accucodeai.kash.tools.make

import kotlin.test.Test
import kotlin.test.assertEquals

class MacroEnvTest {
    @Test fun `recursive expands at lookup`() {
        val e = MacroEnv()
        e.assign("FOO", MacroFlavor.RECURSIVE, "\$(BAR)", MacroOrigin.FILE)
        e.assign("BAR", MacroFlavor.RECURSIVE, "value", MacroOrigin.FILE)
        assertEquals("value", e.expand("\$(FOO)"))
    }

    @Test fun `immediate expands at assignment`() {
        val e = MacroEnv()
        e.assign("BAR", MacroFlavor.RECURSIVE, "one", MacroOrigin.FILE)
        e.assign("FOO", MacroFlavor.IMMEDIATE, "\$(BAR)", MacroOrigin.FILE)
        e.assign("BAR", MacroFlavor.RECURSIVE, "two", MacroOrigin.FILE)
        assertEquals("one", e.expand("\$(FOO)"))
    }

    @Test fun `double dollar yields literal dollar`() {
        val e = MacroEnv()
        assertEquals("\$X", e.expand("\$\$X"))
    }

    @Test fun `nested expansion`() {
        val e = MacroEnv()
        e.assign("X", MacroFlavor.RECURSIVE, "FOO", MacroOrigin.FILE)
        e.assign("FOO", MacroFlavor.RECURSIVE, "hit", MacroOrigin.FILE)
        assertEquals("hit", e.expand("\$(\$(X))"))
    }

    @Test fun `single char var ref`() {
        val e = MacroEnv()
        e.assign("X", MacroFlavor.RECURSIVE, "hi", MacroOrigin.FILE)
        assertEquals("hi", e.expand("\$X"))
    }

    @Test fun `brace and paren equivalence`() {
        val e = MacroEnv()
        e.assign("FOO", MacroFlavor.RECURSIVE, "bar", MacroOrigin.FILE)
        assertEquals(e.expand("\$(FOO)"), e.expand("\${FOO}"))
    }

    @Test fun `suffix substitution`() {
        val e = MacroEnv()
        e.assign("SRC", MacroFlavor.IMMEDIATE, "a.c b.c c.c", MacroOrigin.FILE)
        assertEquals("a.o b.o c.o", e.expand("\$(SRC:.c=.o)"))
    }

    @Test fun `pattern substitution`() {
        val e = MacroEnv()
        e.assign("FILES", MacroFlavor.IMMEDIATE, "src/a.c src/b.c", MacroOrigin.FILE)
        assertEquals("out/a.o out/b.o", e.expand("\$(FILES:src/%.c=out/%.o)"))
    }

    @Test fun `conditional assign skipped if set`() {
        val e = MacroEnv()
        e.assign("X", MacroFlavor.RECURSIVE, "first", MacroOrigin.FILE)
        e.assign("X", MacroFlavor.CONDITIONAL, "second", MacroOrigin.FILE)
        assertEquals("first", e.expand("\$(X)"))
    }

    @Test fun `append on recursive concatenates`() {
        val e = MacroEnv()
        e.assign("CFLAGS", MacroFlavor.RECURSIVE, "-O2", MacroOrigin.FILE)
        e.assign("CFLAGS", MacroFlavor.APPEND, "-Wall", MacroOrigin.FILE)
        assertEquals("-O2 -Wall", e.expand("\$(CFLAGS)"))
    }

    @Test fun `subst function`() {
        val e = MacroEnv()
        assertEquals("foo-bar-baz", e.expand("\$(subst .,-,foo.bar.baz)"))
    }

    @Test fun `patsubst function`() {
        val e = MacroEnv()
        assertEquals("a.o b.o", e.expand("\$(patsubst %.c,%.o,a.c b.c)"))
    }

    @Test fun `words and word`() {
        val e = MacroEnv()
        assertEquals("3", e.expand("\$(words a b c)"))
        assertEquals("b", e.expand("\$(word 2,a b c)"))
    }

    @Test fun `firstword and lastword`() {
        val e = MacroEnv()
        assertEquals("a", e.expand("\$(firstword a b c)"))
        assertEquals("c", e.expand("\$(lastword a b c)"))
    }

    @Test fun `dir and notdir`() {
        val e = MacroEnv()
        assertEquals("src/", e.expand("\$(dir src/foo.c)"))
        assertEquals("foo.c", e.expand("\$(notdir src/foo.c)"))
    }

    @Test fun `basename and suffix`() {
        val e = MacroEnv()
        assertEquals("src/foo", e.expand("\$(basename src/foo.c)"))
        assertEquals(".c", e.expand("\$(suffix src/foo.c)"))
    }

    @Test fun `addprefix and addsuffix`() {
        val e = MacroEnv()
        assertEquals("p-a p-b", e.expand("\$(addprefix p-,a b)"))
        assertEquals("a.x b.x", e.expand("\$(addsuffix .x,a b)"))
    }

    @Test fun `filter and filter-out`() {
        val e = MacroEnv()
        assertEquals("a.c b.c", e.expand("\$(filter %.c,a.c b.c c.h)"))
        assertEquals("c.h", e.expand("\$(filter-out %.c,a.c b.c c.h)"))
    }

    @Test fun `if function`() {
        val e = MacroEnv()
        assertEquals("yes", e.expand("\$(if x,yes,no)"))
        assertEquals("no", e.expand("\$(if ,yes,no)"))
    }

    @Test fun `strip collapses whitespace`() {
        val e = MacroEnv()
        assertEquals("a b c", e.expand("\$(strip   a   b   c  )"))
    }

    @Test fun `origin and flavor`() {
        val e = MacroEnv()
        e.assign("F", MacroFlavor.RECURSIVE, "v", MacroOrigin.FILE)
        assertEquals("file", e.expand("\$(origin F)"))
        assertEquals("undefined", e.expand("\$(origin NEVER_SET)"))
        assertEquals("recursive", e.expand("\$(flavor F)"))
    }

    @Test fun `command-line outranks file`() {
        val e = MacroEnv()
        e.assign("X", MacroFlavor.RECURSIVE, "cli", MacroOrigin.COMMAND_LINE)
        e.assign("X", MacroFlavor.RECURSIVE, "file", MacroOrigin.FILE)
        assertEquals("cli", e.expand("\$(X)"))
    }
}
