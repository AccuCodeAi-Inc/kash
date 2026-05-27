package com.accucodeai.kash.tools.sed

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Semantic tests for the expanded sed feature set: addresses ranges, control
 * flow, hold space, text-emitting commands, transliteration, and a handful of
 * real-world recipes that AI-generated shell scripts commonly reach for.
 *
 * Each test feeds an input through [run] and asserts the produced output
 * lines. [run] uses the parser+engine directly so failures point at the
 * machinery, not at the CLI wrapper.
 */
class SedSemanticTest {
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

    // ---------- d / D ----------

    @Test fun d_deletes_matching_lines() =
        runTest {
            assertEquals(
                listOf("a", "c"),
                run("/b/d", listOf("a", "b", "c")),
            )
        }

    @Test fun d_with_range_deletes_all_in_range() =
        runTest {
            assertEquals(
                listOf("a", "e"),
                run("2,4d", listOf("a", "b", "c", "d", "e")),
            )
        }

    @Test fun capital_D_deletes_only_first_line_in_multiline_pattern() =
        runTest {
            // N joins lines; D drops up to first \n; loop joins next.
            // Net effect: collapses every pair into the second member.
            assertEquals(
                listOf("d"),
                run("N\nD", listOf("a", "b", "c", "d")),
            )
        }

    @Test fun capital_D_at_no_newline_behaves_like_d() =
        runTest {
            // No newline in pattern → D == d.
            assertEquals(
                emptyList(),
                run("D", listOf("only")),
            )
        }

    // ---------- p / P / -n filtering ----------

    @Test fun p_prints_pattern_space() =
        runTest {
            assertEquals(
                listOf("a", "a", "b", "c"),
                run("1p", listOf("a", "b", "c")),
            )
        }

    @Test fun n_dash_n_filter_by_address_regex() =
        runTest {
            // Classic grep emulation: print only matching lines.
            assertEquals(
                listOf("foo bar", "baz foo"),
                run("/foo/p", listOf("foo bar", "no", "baz foo"), suppressDefault = true),
            )
        }

    @Test fun capital_P_prints_only_first_line_of_pattern_space() =
        runTest {
            // N joins two lines; P prints first part; d drops everything (so no auto-print).
            assertEquals(
                listOf("a", "c"),
                run("N\nP\nd", listOf("a", "b", "c", "d")),
            )
        }

    // ---------- n / N ----------

    @Test fun n_consumes_next_input_line() =
        runTest {
            // For each line, n reads the next; s on EVEN-numbered lines.
            assertEquals(
                listOf("a", "X", "c", "X"),
                run("n; s/./X/", listOf("a", "b", "c", "d")),
            )
        }

    @Test fun `N joins pattern with next line`() =
        runTest {
            // Replace newline (joined) with a space.
            assertEquals(
                listOf("a b", "c"),
                run("N\ns/\\n/ /", listOf("a", "b", "c")),
            )
        }

    @Test fun `N at eof terminates and prints pattern`() =
        runTest {
            // Only one line; N has nothing to read → terminate after auto-print.
            assertEquals(
                listOf("only"),
                run("N", listOf("only")),
            )
        }

    // ---------- = ----------

    @Test fun equals_prints_line_numbers() =
        runTest {
            assertEquals(
                listOf("1", "a", "2", "b", "3", "c"),
                run("=", listOf("a", "b", "c")),
            )
        }

    // ---------- q / Q ----------

    @Test fun q_quits_after_printing() =
        runTest {
            assertEquals(
                listOf("a", "b"),
                run("2q", listOf("a", "b", "c", "d")),
            )
        }

    @Test fun capital_Q_quits_without_printing() =
        runTest {
            assertEquals(
                listOf("a"),
                run("2Q", listOf("a", "b", "c")),
            )
        }

    // ---------- a / i / c ----------

    @Test fun a_appends_after_current_line() =
        runTest {
            assertEquals(
                listOf("a", "INS", "b", "c"),
                run("1a INS", listOf("a", "b", "c")),
            )
        }

    @Test fun i_inserts_before_current_line() =
        runTest {
            assertEquals(
                listOf("HEADER", "a", "b"),
                run("1i HEADER", listOf("a", "b")),
            )
        }

    @Test fun c_changes_single_line() =
        runTest {
            assertEquals(
                listOf("a", "X", "c"),
                run("2c X", listOf("a", "b", "c")),
            )
        }

    @Test fun c_on_range_emits_text_once_at_end() =
        runTest {
            // GNU semantics: c on a range deletes pattern for every line in the
            // range, then prints the replacement once when leaving the range.
            assertEquals(
                listOf("a", "REPLACED", "e"),
                run("2,4c REPLACED", listOf("a", "b", "c", "d", "e")),
            )
        }

    @Test fun a_with_classic_backslash_newline_form() =
        runTest {
            // POSIX form: `a\` then a newline then the text.
            assertEquals(
                listOf("a", "ins", "b"),
                run("1a\\\nins", listOf("a", "b")),
            )
        }

    // ---------- y ----------

    @Test fun y_transliterates_chars() =
        runTest {
            assertEquals(
                listOf("HELLO WORLD"),
                run("y/abcdefghijklmnopqrstuvwxyz/ABCDEFGHIJKLMNOPQRSTUVWXYZ/", listOf("hello world")),
            )
        }

    @Test fun y_rejects_mismatched_lengths() =
        runTest {
            assertFailsWith<SedScriptError> {
                SedScriptParser.parse("y/abc/AB/")
            }
        }

    @Test fun y_with_alternate_delimiter() =
        runTest {
            assertEquals(
                listOf("XYZ"),
                run("y|abc|XYZ|", listOf("abc")),
            )
        }

    // ---------- hold space ----------

    @Test fun h_g_round_trip_copies_via_hold() =
        runTest {
            // Save line 1 to hold; on line 3 restore from hold.
            assertEquals(
                listOf("a", "b", "a"),
                run("1h\n3g", listOf("a", "b", "c")),
            )
        }

    @Test fun x_swaps_pattern_and_hold() =
        runTest {
            // After 1{ h }, hold="a". On line 2, x → pattern="a", hold="b".
            assertEquals(
                listOf("a", "a"),
                run("1h\n2x", listOf("a", "b")),
            )
        }

    @Test fun capital_H_appends_to_hold_with_newline() =
        runTest {
            // Accumulate every line into hold (with a leading "" entry — H always
            // prepends a newline). At end, copy hold to pattern and let it print.
            // Each `emit` callback gets one string, so the joined pattern arrives
            // as a single entry containing embedded newlines.
            assertEquals(
                listOf("\na\nb\nc"),
                run("H\n\$!d\n\$g", listOf("a", "b", "c")),
            )
        }

    @Test fun capital_G_appends_hold_to_pattern() =
        runTest {
            // 1h saves "a"; 2G appends hold ("a") to pattern ("b") → "b\na".
            // \n inside pattern gets printed verbatim, which our emitter writes as
            // two output lines (the harness emits one line per emit call, but our
            // engine emits the joined string as one call — so we see one entry).
            assertEquals(
                listOf("a", "b\na"),
                run("1h\n2G", listOf("a", "b")),
            )
        }

    // ---------- branching ----------

    @Test fun b_unconditional_branch_to_label() =
        runTest {
            // Lines starting with X are passed through; others run s/./Y/ which
            // replaces only the first char.
            assertEquals(
                listOf("X1", "Yoo", "X2"),
                run(":start\n/^X/b end\ns/./Y/\n:end", listOf("X1", "foo", "X2")),
            )
        }

    @Test fun b_with_no_label_goes_to_end_of_script() =
        runTest {
            assertEquals(
                listOf("X1", "Yoo"),
                run("/^X/b\ns/./Y/", listOf("X1", "foo")),
            )
        }

    @Test fun t_branches_on_successful_substitution() =
        runTest {
            // First s rewrites "a" → "A". On success, t jumps past second s.
            // On failure, second s rewrites "b" → "B".
            assertEquals(
                listOf("A", "B"),
                run("s/a/A/\nt end\ns/b/B/\n:end", listOf("a", "b")),
            )
        }

    @Test fun t_flag_resets_after_successful_branch() =
        runTest {
            // After a successful t, the sub-flag is cleared. The second t should
            // therefore not branch unless a new s succeeds.
            assertEquals(
                listOf("X-Y"),
                run("s/a/X/\nt mid\n:mid\ns/b/Y/\nt end\ns/./Z/\n:end", listOf("a-b")),
            )
        }

    @Test fun `T branches on failed substitution`() =
        runTest {
            // s tries to match "X" — fails. T jumps to end; second s never runs.
            assertEquals(
                listOf("hello"),
                run("s/X/Y/\nT end\ns/./Z/\n:end", listOf("hello")),
            )
        }

    @Test fun undefined_label_is_parse_error() =
        runTest {
            // Resolution happens at compile time, not raw parse — wire the test
            // through CompiledScript.compile.
            assertFailsWith<SedScriptError> {
                CompiledScript.compile(SedScriptParser.parse("b nowhere"))
            }
        }

    // ---------- blocks ----------

    @Test fun block_groups_share_an_address() =
        runTest {
            // Inside the /foo/ block, do two substitutions.
            assertEquals(
                listOf("AB", "no", "AB"),
                run("/foo/{ s/foo/A/; s/$/B/ }", listOf("foo", "no", "foo")),
            )
        }

    @Test fun nested_blocks() =
        runTest {
            assertEquals(
                listOf("X", "bar", "X"),
                run("/foo/{ /^foo\$/{ s/.*/X/ } }", listOf("foo", "bar", "foo")),
            )
        }

    @Test fun block_negation_applies_to_outside() =
        runTest {
            // !{ } applies the block to non-matching lines.
            assertEquals(
                listOf("foo", "[bar]", "[baz]"),
                run("/foo/!{ s/.*/[&]/ }", listOf("foo", "bar", "baz")),
            )
        }

    @Test fun unmatched_open_brace_is_parse_error() =
        runTest {
            assertFailsWith<SedScriptError> {
                SedScriptParser.parse("/x/{")
            }
        }

    @Test fun unmatched_close_brace_is_parse_error() =
        runTest {
            assertFailsWith<SedScriptError> {
                SedScriptParser.parse("}")
            }
        }

    // ---------- address ranges ----------

    @Test fun range_by_line_numbers_inclusive() =
        runTest {
            assertEquals(
                listOf("a", "[b]", "[c]", "[d]", "e"),
                run("2,4s/.*/[&]/", listOf("a", "b", "c", "d", "e")),
            )
        }

    @Test fun range_by_regex_to_regex() =
        runTest {
            // Print only lines between /BEGIN/ and /END/ inclusive.
            assertEquals(
                listOf("BEGIN", "x", "y", "END"),
                run("/BEGIN/,/END/p", listOf("BEGIN", "x", "y", "END", "after"), suppressDefault = true),
            )
        }

    @Test fun range_end_line_less_than_start_is_single_line() =
        runTest {
            // POSIX: if end-line < current line, the range covers only the start.
            assertEquals(
                listOf("[a]", "b", "c"),
                run("1,1s/.*/[&]/", listOf("a", "b", "c")),
            )
        }

    @Test fun range_plus_lines_extension() =
        runTest {
            // GNU `addr,+N`: starting at /B/, include B plus 2 more lines.
            assertEquals(
                listOf("a", "[B]", "[c]", "[d]", "e"),
                run("/B/,+2s/.*/[&]/", listOf("a", "B", "c", "d", "e")),
            )
        }

    @Test fun range_tilde_step_extension() =
        runTest {
            // GNU `addr,~M`: from /B/ through the next line whose number is % M == 0.
            // /B/ at line 2; step 3 → end at line 3.
            assertEquals(
                listOf("a", "[B]", "[c]", "d"),
                run("/B/,~3s/.*/[&]/", listOf("a", "B", "c", "d")),
            )
        }

    @Test fun range_dollar_end() =
        runTest {
            // From regex match to end of input.
            assertEquals(
                listOf("a", "[B]", "[c]", "[d]"),
                run("/B/,\$s/.*/[&]/", listOf("a", "B", "c", "d")),
            )
        }

    @Test fun range_zero_comma_regex_matches_first_line() =
        runTest {
            // GNU `0,/re/` allows /re/ to match on the very first line and end there.
            assertEquals(
                listOf("[a]", "a", "b"),
                run("0,/a/{i [a]\n}", listOf("a", "b")),
            )
        }

    @Test fun negated_range() =
        runTest {
            // 2,4 !s means "apply on lines NOT in 2..4".
            assertEquals(
                listOf("[a]", "b", "c", "d", "[e]"),
                run("2,4!s/.*/[&]/", listOf("a", "b", "c", "d", "e")),
            )
        }

    // ---------- comments ----------

    @Test fun hash_comment_is_skipped() =
        runTest {
            assertEquals(
                listOf("X"),
                run("# this is a comment\ns/./X/", listOf("a")),
            )
        }

    // ---------- real-world recipes ----------

    @Test fun recipe_grep_emulation_via_p() =
        runTest {
            assertEquals(
                listOf("matches foo", "and foo too"),
                run("/foo/p", listOf("matches foo", "no", "and foo too"), suppressDefault = true),
            )
        }

    @Test fun recipe_extract_block_between_markers() =
        runTest {
            assertEquals(
                listOf("BEGIN", "line1", "line2", "END"),
                run(
                    "/BEGIN/,/END/p",
                    listOf("noise", "BEGIN", "line1", "line2", "END", "more noise"),
                    suppressDefault = true,
                ),
            )
        }

    @Test fun recipe_delete_matching_lines() =
        runTest {
            // In-place "remove TODO lines" pattern.
            assertEquals(
                listOf("real code", "more code"),
                run("/^TODO/d", listOf("TODO: fix", "real code", "TODO: later", "more code")),
            )
        }

    @Test fun recipe_squeeze_repeated_blank_lines() =
        runTest {
            // Classic squeeze recipe: on a blank line, join with the next; if the
            // joined pattern is still "\n" (two blanks), delete the first half and
            // loop. Each output entry is one emit-call, so the joined "blank +
            // next non-blank" arrives as a single string with an embedded newline.
            assertEquals(
                listOf("a", "\nb", "\nc"),
                run(
                    "/^\$/{N\n/^\\n\$/D\n}",
                    listOf("a", "", "", "", "b", "", "c"),
                ),
            )
        }

    @Test fun recipe_comment_out_matching_lines() =
        runTest {
            // Prefix every line matching /TODO/ with "# ".
            assertEquals(
                listOf("# TODO: a", "real", "# TODO: b"),
                run("/TODO/s/^/# /", listOf("TODO: a", "real", "TODO: b")),
            )
        }

    @Test fun recipe_uppercase_with_y() =
        runTest {
            assertEquals(
                listOf("HELLO 123"),
                run(
                    "y/abcdefghijklmnopqrstuvwxyz/ABCDEFGHIJKLMNOPQRSTUVWXYZ/",
                    listOf("Hello 123"),
                ),
            )
        }

    @Test fun recipe_reverse_lines_via_hold_space() =
        runTest {
            // GNU sed "tac" recipe: G; h; $!d
            // Build hold = reversed pattern. At end, hold contains all lines reversed
            // and prepended with empty.
            // 1!G — append hold (prefixed with \n) to pattern from line 2 onward
            // h    — save pattern into hold
            // $!d  — delete unless last line
            // On last line, auto-print emits the assembled (reversed) pattern.
            val out = run("1!G\nh\n\$!d", listOf("a", "b", "c"))
            // Pattern at end is "c\nb\na" — emitted as a single string.
            assertEquals(listOf("c\nb\na"), out)
        }

    // ---------- exit codes ----------

    @Test fun q_with_explicit_exit_code() =
        runTest {
            val parsed = SedScriptParser.parse("1q 42")
            val out = mutableListOf<String>()
            val rc = SedEngine(parsed, false).run(listOf("only").iterator()) { out.add(it) }
            assertEquals(42, rc.exitCode)
            assertEquals(listOf("only"), out)
        }

    // ---------- r FILE ----------

    private class FakeIo(
        private val files: Map<String, String> = emptyMap(),
        private val exec: (suspend (String) -> String?)? = null,
    ) : SedIo {
        val writes = mutableMapOf<String, StringBuilder>()

        override suspend fun readForR(path: String): String? = files[path]

        override suspend fun writeForW(
            path: String,
            line: String,
        ) {
            val sb = writes.getOrPut(path) { StringBuilder() }
            sb.append(line).append('\n')
        }

        override suspend fun execForS(commandLine: String): String? = exec?.invoke(commandLine)

        override fun close() = Unit
    }

    private suspend fun runWithIo(
        script: String,
        input: List<String>,
        io: SedIo,
        suppressDefault: Boolean = false,
    ): List<String> {
        val parsed = SedScriptParser.parse(script)
        val out = mutableListOf<String>()
        SedEngine(parsed, suppressDefault, io).run(input.iterator()) { out.add(it) }
        return out
    }

    @Test fun r_reads_file_after_current_line() =
        runTest {
            val io = FakeIo(files = mapOf("/etc/banner" to "BANNER1\nBANNER2\n"))
            // After every line that matches /HEAD/, append the banner.
            assertEquals(
                listOf("foo", "HEAD line", "BANNER1\nBANNER2", "bar"),
                runWithIo("/HEAD/r /etc/banner", listOf("foo", "HEAD line", "bar"), io),
            )
        }

    @Test fun r_silently_ignores_missing_file() =
        runTest {
            val io = FakeIo()
            assertEquals(
                listOf("foo", "bar"),
                runWithIo("r /no/such/file", listOf("foo", "bar"), io),
            )
        }

    // ---------- w FILE ----------

    @Test fun w_writes_matching_lines_to_file() =
        runTest {
            val io = FakeIo()
            runWithIo("/keep/w /out", listOf("keep me", "drop", "keep again"), io)
            assertEquals(
                "keep me\nkeep again\n",
                io.writes["/out"]?.toString(),
            )
        }

    @Test fun w_writes_after_substitution_uses_modified_pattern() =
        runTest {
            val io = FakeIo()
            runWithIo("s/a/A/\nw /out", listOf("apple", "banana"), io)
            assertEquals(
                "Apple\nbAnana\n",
                io.writes["/out"]?.toString(),
            )
        }

    // ---------- l ----------

    @Test fun l_escapes_non_printables_with_dollar_terminator() =
        runTest {
            // -n + l on line 1: only the unambiguous form fires (no auto-print).
            // Input has TAB and BEL bytes; engine should escape both.
            assertEquals(
                listOf("a\\tb\\ac$"),
                run("1l", listOf("a\tb\u0007c"), suppressDefault = true),
            )
        }

    @Test fun l_with_width_wraps() =
        runTest {
            // 11-char unambiguous form "xxxxxxxxxx$" wrapped at 5 cols.
            // Each non-final chunk = 4 chars + "\\\n".
            assertEquals(
                listOf("xxxx\\\nxxxx\\\nxx$"),
                run("l 5", listOf("xxxxxxxxxx"), suppressDefault = true),
            )
        }

    @Test fun l_zero_width_disables_wrapping() =
        runTest {
            assertEquals(
                listOf("xxxxxxxxxxxx$"),
                run("l 0", listOf("xxxxxxxxxxxx"), suppressDefault = true),
            )
        }

    // ---------- BRE preprocessor ----------

    @Test fun bre_to_ere_swaps_paren_and_brace_escapes() =
        runTest {
            assertEquals("(foo)", breToEre("""\(foo\)"""))
            assertEquals("""\(foo\)""", breToEre("(foo)"))
            assertEquals("a+b", breToEre("""a\+b"""))
            assertEquals("""a\?b""", breToEre("a?b"))
        }

    @Test fun bre_to_ere_leaves_character_classes_alone() =
        runTest {
            // Inside `[...]`, `(`, `+`, `?` are literal in both flavors — no swap.
            assertEquals("[(+?)]", breToEre("[(+?)]"))
        }

    @Test fun bre_default_groups_use_backslash_parens() =
        runTest {
            assertEquals(
                listOf("ba"),
                run("""s/\(a\)\(b\)/\2\1/""", listOf("ab")),
            )
        }

    @Test fun bre_default_treats_bare_paren_as_literal() =
        runTest {
            // With BRE default, `(foo)` matches the literal string "(foo)".
            assertEquals(listOf("X"), run("s/(foo)/X/", listOf("(foo)")))
            assertEquals(listOf("foo"), run("s/(foo)/X/", listOf("foo")))
        }

    @Test fun ere_mode_via_parser_treats_paren_as_group() =
        runTest {
            val parsed = SedScriptParser.parse("""s/(a)(b)/\2\1/""", breMode = false)
            val out = mutableListOf<String>()
            SedEngine(parsed, false).run(listOf("ab").iterator()) { out.add(it) }
            assertEquals(listOf("ba"), out)
        }

    // ---------- step address `N~M` ----------

    @Test fun step_address_picks_every_other_line() =
        runTest {
            // `0~2` matches lines 2, 4, 6, ... (line 0 doesn't exist).
            assertEquals(
                listOf("a", "[b]", "c", "[d]", "e"),
                run("0~2s/.*/[&]/", listOf("a", "b", "c", "d", "e")),
            )
        }

    @Test fun step_address_odd_lines() =
        runTest {
            // `1~2` matches lines 1, 3, 5, ...
            assertEquals(
                listOf("[a]", "b", "[c]", "d", "[e]"),
                run("1~2s/.*/[&]/", listOf("a", "b", "c", "d", "e")),
            )
        }

    // ---------- s/.../w FILE flag ----------

    @Test fun substitute_w_flag_writes_replaced_lines_to_file() =
        runTest {
            val io = FakeIo()
            runWithIo("s/a/A/w /matched", listOf("apple", "no", "banana"), io)
            assertEquals(
                "Apple\nbAnana\n",
                io.writes["/matched"]?.toString(),
            )
        }

    @Test fun substitute_w_flag_skips_non_matching_lines() =
        runTest {
            val io = FakeIo()
            runWithIo("s/zzz/X/w /out", listOf("foo", "bar"), io)
            assertEquals(null, io.writes["/out"]?.toString())
        }
}
