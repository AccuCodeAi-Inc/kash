package com.accucodeai.kash.tools.sed

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SedEngineTest {
    private suspend fun run(
        script: String,
        input: List<String>,
        suppressDefault: Boolean = false,
    ): List<String> {
        val parsed = SedScriptParser.parse(script)
        val out = mutableListOf<String>()
        SedEngine(parsed, suppressDefault).run(input.iterator()) { out.add(it) }
        return out
    }

    @Test fun simple_substitution() =
        runTest {
            // No `g` flag: only the first match per line is replaced.
            assertEquals(
                listOf("hello WORLD", "WORLD world"),
                run("s/world/WORLD/", listOf("hello world", "world world")),
            )
        }

    @Test fun global_substitution() =
        runTest {
            assertEquals(
                listOf("X X X"),
                run("s/a/X/g", listOf("a a a")),
            )
        }

    @Test fun first_match_only_when_no_g() =
        runTest {
            assertEquals(
                listOf("X a a"),
                run("s/a/X/", listOf("a a a")),
            )
        }

    @Test fun ampersand_in_replacement() =
        runTest {
            assertEquals(
                listOf("[foo]"),
                run("s/foo/[&]/", listOf("foo")),
            )
        }

    @Test fun escaped_ampersand_is_literal() =
        runTest {
            assertEquals(
                listOf("a&b"),
                run("""s/X/a\&b/""", listOf("X")),
            )
        }

    @Test fun backreferences() =
        runTest {
            assertEquals(
                listOf("bar foo"),
                // BRE form: groups are `\(...\)`.
                run("""s/\(foo\) \(bar\)/\2 \1/""", listOf("foo bar")),
            )
        }

    @Test fun case_insensitive_flag() =
        runTest {
            assertEquals(
                listOf("[FOO] [BaR]"),
                run("s/foo/[FOO]/i; s/bar/[BaR]/i", listOf("Foo Bar")),
            )
        }

    @Test fun line_number_address() =
        runTest {
            assertEquals(
                listOf("a", "B", "c"),
                run("2s/b/B/", listOf("a", "b", "c")),
            )
        }

    @Test fun last_line_address() =
        runTest {
            assertEquals(
                listOf("a", "b", "C"),
                run("\$s/c/C/", listOf("a", "b", "c")),
            )
        }

    @Test fun regex_address() =
        runTest {
            assertEquals(
                listOf("hello", "WORLD", "goodbye"),
                run("/world/s/world/WORLD/", listOf("hello", "world", "goodbye")),
            )
        }

    @Test fun negated_address() =
        runTest {
            // `2!` => apply on every line EXCEPT line 2.
            assertEquals(
                listOf("X", "b", "X"),
                run("2!s/./X/", listOf("a", "b", "c")),
            )
        }

    @Test fun suppress_default_with_p_flag() =
        runTest {
            // -n with `p` flag on `s` prints only matched lines.
            assertEquals(
                listOf("HELLO"),
                run("s/hello/HELLO/p", listOf("hello", "world"), suppressDefault = true),
            )
        }

    @Test fun multiple_commands_via_semicolon() =
        runTest {
            assertEquals(
                listOf("XY"),
                run("s/a/X/; s/b/Y/", listOf("ab")),
            )
        }

    @Test fun multiple_commands_via_newline() =
        runTest {
            assertEquals(
                listOf("XY"),
                run("s/a/X/\ns/b/Y/", listOf("ab")),
            )
        }

    @Test fun nth_match_only() =
        runTest {
            // Replace only the 2nd match.
            assertEquals(
                listOf("a X a"),
                run("s/a/X/2", listOf("a a a")),
            )
        }

    @Test fun unknown_command_errors() =
        runTest {
            assertFailsWith<SedScriptError> {
                SedScriptParser.parse("Z")
            }
        }

    @Test fun alternative_delimiter_in_substitute() =
        runTest {
            // POSIX: any char other than `\` or newline may delimit `s`.
            assertEquals(
                listOf("/etc/local"),
                run("s|/usr|/etc|", listOf("/usr/local")),
            )
        }

    @Test fun escape_sequences_in_replacement() =
        runTest {
            assertEquals(
                listOf("a\tb"),
                run("""s/X/\t/""", listOf("aXb")),
            )
        }

    @Test fun empty_input_yields_no_output() =
        runTest {
            assertEquals(emptyList(), run("s/x/y/", emptyList()))
        }
}
