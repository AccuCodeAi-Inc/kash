package com.accucodeai.kash.tools.grep

import kotlinx.coroutines.test.runTest
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrepCommandTest {
    @Test fun simple_match_from_stdin() =
        runTest {
            val (ctx, out, _) = ctxFor("foo\nbar\nfoobar\n")
            val rc = GrepCommandSpec().run(listOf("foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("foo\nfoobar\n", out.readString())
        }

    @Test fun no_match_exits_one() =
        runTest {
            val (ctx, out, _) = ctxFor("alpha\nbeta\n")
            val rc = GrepCommandSpec().run(listOf("zzz"), ctx)
            assertEquals(1, rc.exitCode)
            assertEquals("", out.readString())
        }

    @Test fun missing_pattern_is_usage_error() =
        runTest {
            val (ctx, _, err) = ctxFor("hi\n")
            val rc = GrepCommandSpec().run(emptyList(), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().startsWith("grep: "))
        }

    @Test fun unknown_option_is_usage_error() =
        runTest {
            val (ctx, _, err) = ctxFor("hi\n")
            val rc = GrepCommandSpec().run(listOf("-Z", "pat"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().contains("unknown option"))
        }

    @Test fun ignore_case() =
        runTest {
            val (ctx, out, _) = ctxFor("Hello\nWORLD\nhello world\n")
            val rc = GrepCommandSpec().run(listOf("-i", "hello"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("Hello\nhello world\n", out.readString())
        }

    @Test fun whole_line_dash_x_matches_only_full_line() =
        runTest {
            val (ctx, out, _) = ctxFor("foo\nfoobar\nbar\nfoo\n")
            val rc = GrepCommandSpec().run(listOf("-x", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("foo\nfoo\n", out.readString())
        }

    @Test fun whole_line_dash_x_with_ere_alternation() =
        runTest {
            val (ctx, out, _) = ctxFor("alpha\nbeta\nalphabeta\ngamma\n")
            // -x: alpha OR beta on its own. alphabeta should NOT match.
            val rc = GrepCommandSpec().run(listOf("-Ex", "alpha|beta"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("alpha\nbeta\n", out.readString())
        }

    @Test fun whole_line_dash_x_no_match_exits_one() =
        runTest {
            val (ctx, out, _) = ctxFor("foobar\nbarfoo\n")
            val rc = GrepCommandSpec().run(listOf("-x", "foo"), ctx)
            assertEquals(1, rc.exitCode)
            assertEquals("", out.readString())
        }

    @Test fun whole_line_long_form_line_regexp() =
        runTest {
            val (ctx, out, _) = ctxFor("x\nxy\n")
            val rc = GrepCommandSpec().run(listOf("--line-regexp", "x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("x\n", out.readString())
        }

    @Test fun invert_match() =
        runTest {
            val (ctx, out, _) = ctxFor("a\nb\nc\n")
            val rc = GrepCommandSpec().run(listOf("-v", "b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("a\nc\n", out.readString())
        }

    @Test fun line_numbers() =
        runTest {
            val (ctx, out, _) = ctxFor("x\ny\nx\n")
            val rc = GrepCommandSpec().run(listOf("-n", "x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("1:x\n3:x\n", out.readString())
        }

    @Test fun count_only_single_file_no_prefix() =
        runTest {
            val (ctx, out, _) = ctxFor("a\nb\na\na\n")
            val rc = GrepCommandSpec().run(listOf("-c", "a"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("3\n", out.readString())
        }

    @Test fun quiet_suppresses_output_but_sets_exit() =
        runTest {
            val (ctx, out, _) = ctxFor("foo\nbar\n")
            val rc = GrepCommandSpec().run(listOf("-q", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("", out.readString())
        }

    @Test fun quiet_no_match_exit_one() =
        runTest {
            val (ctx, _, _) = ctxFor("foo\nbar\n")
            val rc = GrepCommandSpec().run(listOf("-q", "zzz"), ctx)
            assertEquals(1, rc.exitCode)
        }

    @Test fun multiple_files_show_filename_prefix() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/a", "hello\nworld\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/b", "hello bob\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("hello", "/a", "/b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("/a:hello\n/b:hello bob\n", out.readString())
        }

    @Test fun dash_h_suppresses_filename_prefix() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/a", "x\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/b", "x\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-h", "x", "/a", "/b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("x\nx\n", out.readString())
        }

    @Test fun dash_H_forces_filename_prefix_for_one_file() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/a", "x\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-H", "x", "/a"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("/a:x\n", out.readString())
        }

    @Test fun files_with_matches_lists_filename_once() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/a", "hit\nhit\nhit\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/b", "miss\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-l", "hit", "/a", "/b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("/a\n", out.readString())
        }

    @Test fun multiple_patterns_via_e() =
        runTest {
            val (ctx, out, _) = ctxFor("apple\nbanana\ncherry\ndate\n")
            val rc = GrepCommandSpec().run(listOf("-e", "apple", "-e", "cherry"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("apple\ncherry\n", out.readString())
        }

    @Test fun patterns_from_f_file() =
        runTest {
            val (ctx, out, _) = ctxFor("alpha\nbeta\ngamma\ndelta\n")
            ctx.process.fs.writeBytes("/pats", "alpha\ngamma\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-f", "/pats"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("alpha\ngamma\n", out.readString())
        }

    @Test fun missing_input_file_is_error() =
        runTest {
            val (ctx, _, err) = ctxFor("")
            val rc = GrepCommandSpec().run(listOf("x", "/nope"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().contains("No such file"))
        }

    @Test fun dash_s_suppresses_missing_file_error() =
        runTest {
            val (ctx, _, err) = ctxFor("")
            val rc = GrepCommandSpec().run(listOf("-s", "x", "/nope"), ctx)
            assertEquals(2, rc.exitCode)
            assertEquals("", err.readString())
        }

    @Test fun directory_without_r_is_an_error() =
        runTest {
            val (ctx, _, err) = ctxFor("")
            ctx.process.fs.mkdirs("/d")
            val rc = GrepCommandSpec().run(listOf("x", "/d"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().contains("Is a directory"))
        }

    @Test fun recursive_walks_into_dirs() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/d/a.txt", "needle\nstraw\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/d/sub/b.txt", "needle in subdir\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-r", "needle", "/d"), ctx)
            assertEquals(0, rc.exitCode)
            val text = out.readString()
            assertTrue(text.contains("/d/a.txt:needle"), "got: $text")
            assertTrue(text.contains("/d/sub/b.txt:needle in subdir"), "got: $text")
        }

    @Test fun dash_dash_ends_options() =
        runTest {
            val (ctx, out, _) = ctxFor("-v\nfoo\n")
            val rc = GrepCommandSpec().run(listOf("--", "-v"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("-v\n", out.readString())
        }

    @Test fun bre_default_treats_parens_as_literal() =
        runTest {
            val (ctx, out, _) = ctxFor("a(b)\nab\n")
            val rc = GrepCommandSpec().run(listOf("(b)"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("a(b)\n", out.readString())
        }

    @Test fun bre_escaped_paren_is_a_group() =
        runTest {
            val (ctx, out, _) = ctxFor("ab\nac\n")
            // \(b\|c\) → matches both
            val rc = GrepCommandSpec().run(listOf("a\\(b\\|c\\)"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("ab\nac\n", out.readString())
        }

    @Test fun ere_via_dash_E_uses_extended_syntax() =
        runTest {
            val (ctx, out, _) = ctxFor("ab\nac\n")
            val rc = GrepCommandSpec().run(listOf("-E", "a(b|c)"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("ab\nac\n", out.readString())
        }

    @Test fun fixed_via_dash_F_treats_regex_meta_as_literal() =
        runTest {
            val (ctx, out, _) = ctxFor("a.b\naxb\n")
            val rc = GrepCommandSpec().run(listOf("-F", "a.b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("a.b\n", out.readString())
        }

    @Test fun egrep_default_is_ere() =
        runTest {
            val (ctx, out, _) = ctxFor("ab\nac\n")
            val rc = EgrepCommandSpec().run(listOf("a(b|c)"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("ab\nac\n", out.readString())
        }

    @Test fun fgrep_default_is_fixed() =
        runTest {
            val (ctx, out, _) = ctxFor("a.b\naxb\n")
            val rc = FgrepCommandSpec().run(listOf("a.b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("a.b\n", out.readString())
        }

    @Test fun count_with_multiple_files_prefixes_each() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/a", "x\nx\ny\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/b", "x\ny\ny\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-c", "x", "/a", "/b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("/a:2\n/b:1\n", out.readString())
        }

    @Test fun clustered_short_options() =
        runTest {
            val (ctx, out, _) = ctxFor("foo\nFOO\nbar\n")
            // -i -n combined, plus pattern as positional.
            val rc = GrepCommandSpec().run(listOf("-in", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("1:foo\n2:FOO\n", out.readString())
        }

    @Test fun dash_e_inline_argument() =
        runTest {
            val (ctx, out, _) = ctxFor("foo\nbar\n")
            val rc = GrepCommandSpec().run(listOf("-efoo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("foo\n", out.readString())
        }

    @Test fun stdin_when_dash_is_a_filename() =
        runTest {
            val (ctx, out, _) = ctxFor("alpha\nbeta\n")
            val rc = GrepCommandSpec().run(listOf("alpha", "-"), ctx)
            assertEquals(0, rc.exitCode)
            // Single source so no filename prefix.
            assertEquals("alpha\n", out.readString())
        }

    @Test fun empty_pattern_matches_every_line() =
        runTest {
            val (ctx, out, _) = ctxFor("a\nb\n")
            val rc = GrepCommandSpec().run(listOf("-e", ""), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("a\nb\n", out.readString())
        }

    @Test fun anchor_caret_works() =
        runTest {
            val (ctx, out, _) = ctxFor("foo\nXfoo\nfoobar\n")
            val rc = GrepCommandSpec().run(listOf("^foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("foo\nfoobar\n", out.readString())
        }

    @Test fun anchor_dollar_works() =
        runTest {
            val (ctx, out, _) = ctxFor("foo\nfooX\nbarfoo\n")
            val rc = GrepCommandSpec().run(listOf("foo$"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("foo\nbarfoo\n", out.readString())
        }

    @Test fun character_class() =
        runTest {
            val (ctx, out, _) = ctxFor("a1\nb2\nc3\nfoo\n")
            val rc = GrepCommandSpec().run(listOf("[0-9]"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("a1\nb2\nc3\n", out.readString())
        }

    // ESC + '[' — unicode-escaped so the source file has no literal control bytes.
    private val esc = "\u001B["

    @Test fun color_always_wraps_match() =
        runTest {
            val (ctx, out, _) = ctxFor("hello world\n")
            val rc = GrepCommandSpec().run(listOf("--color=always", "world"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("hello ${esc}01;31mworld${esc}0m\n", out.readString())
        }

    @Test fun color_always_wraps_multiple_matches_on_one_line() =
        runTest {
            val (ctx, out, _) = ctxFor("foo bar foo baz\n")
            val rc = GrepCommandSpec().run(listOf("--color=always", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(
                "${esc}01;31mfoo${esc}0m bar ${esc}01;31mfoo${esc}0m baz\n",
                out.readString(),
            )
        }

    @Test fun color_always_with_line_numbers_colors_separators() =
        runTest {
            val (ctx, out, _) = ctxFor("a\nfoo\nb\n")
            val rc = GrepCommandSpec().run(listOf("--color=always", "-n", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(
                "${esc}32m2${esc}0m${esc}36m:${esc}0m${esc}01;31mfoo${esc}0m\n",
                out.readString(),
            )
        }

    @Test fun color_default_is_plain() =
        runTest {
            // Default = NEVER. Scripts and pipes stay byte-identical; the
            // REPL aliases `grep --color=auto` for interactive use.
            val (ctx, out, _) = ctxFor("hello\n")
            val rc = GrepCommandSpec().run(listOf("hello"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("hello\n", out.readString())
        }

    @Test fun color_never_strips_even_when_explicit() =
        runTest {
            val (ctx, out, _) = ctxFor("hello world\n")
            val rc = GrepCommandSpec().run(listOf("--color=never", "world"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("hello world\n", out.readString())
        }

    @Test fun color_invert_does_not_highlight() =
        runTest {
            // `-v` flips match semantics; GNU grep skips highlight in that mode.
            val (ctx, out, _) = ctxFor("foo\nbar\n")
            val rc = GrepCommandSpec().run(listOf("--color=always", "-v", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("bar\n", out.readString())
        }

    @Test fun color_rejects_bad_value() =
        runTest {
            val (ctx, _, err) = ctxFor("x\n")
            val rc = GrepCommandSpec().run(listOf("--color=banana", "x"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue("banana" in err.readString())
        }

    @Test fun grep_colors_env_overrides_match_color() =
        runTest {
            val (ctx, out, _) = ctxFor("hello\n")
            ctx.process.env["GREP_COLORS"] = "ms=01;33"
            val rc = GrepCommandSpec().run(listOf("--color=always", "hello"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("${esc}01;33mhello${esc}0m\n", out.readString())
        }

    @Test fun grep_colors_env_overrides_filename_and_separator() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/a", "hit\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/b", "hit\n".encodeToByteArray())
            ctx.process.env["GREP_COLORS"] = "fn=34:se=90:ms=01;33"
            val rc = GrepCommandSpec().run(listOf("--color=always", "hit", "/a", "/b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(
                "${esc}34m/a${esc}0m${esc}90m:${esc}0m${esc}01;33mhit${esc}0m\n" +
                    "${esc}34m/b${esc}0m${esc}90m:${esc}0m${esc}01;33mhit${esc}0m\n",
                out.readString(),
            )
        }

    // ---------- -o / --only-matching ----------

    @Test fun only_matching_prints_each_match_per_line() =
        runTest {
            val (ctx, out, _) = ctxFor("foo bar foo baz\nno hit here\nfoofoo\n")
            val rc = GrepCommandSpec().run(listOf("-o", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("foo\nfoo\nfoo\nfoo\n", out.readString())
        }

    @Test fun only_matching_with_line_numbers_repeats_lineno() =
        runTest {
            val (ctx, out, _) = ctxFor("foo foo\nbar\nfoo\n")
            val rc = GrepCommandSpec().run(listOf("-on", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("1:foo\n1:foo\n3:foo\n", out.readString())
        }

    @Test fun only_matching_with_ere_alternation() =
        runTest {
            val (ctx, out, _) = ctxFor("alpha beta gamma\n")
            val rc = GrepCommandSpec().run(listOf("-Eo", "alpha|gamma"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("alpha\ngamma\n", out.readString())
        }

    @Test fun only_matching_no_match_exits_one() =
        runTest {
            val (ctx, out, _) = ctxFor("nothing here\n")
            val rc = GrepCommandSpec().run(listOf("-o", "zzz"), ctx)
            assertEquals(1, rc.exitCode)
            assertEquals("", out.readString())
        }

    // ---------- -w / --word-regexp ----------

    @Test fun word_regexp_requires_word_boundaries() =
        runTest {
            val (ctx, out, _) = ctxFor("foo\nfoobar\nbarfoo\nfoo bar\n")
            val rc = GrepCommandSpec().run(listOf("-w", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("foo\nfoo bar\n", out.readString())
        }

    @Test fun word_regexp_long_form() =
        runTest {
            val (ctx, out, _) = ctxFor("cat\nconcat\n")
            val rc = GrepCommandSpec().run(listOf("--word-regexp", "cat"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("cat\n", out.readString())
        }

    @Test fun word_regexp_with_ere() =
        runTest {
            val (ctx, out, _) = ctxFor("hot pot\nhotpot\nhot\npothot\n")
            val rc = GrepCommandSpec().run(listOf("-Ew", "hot|pot"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("hot pot\nhot\n", out.readString())
        }

    // ---------- -m / --max-count ----------

    @Test fun max_count_stops_after_n_matches() =
        runTest {
            val (ctx, out, _) = ctxFor("x\nx\nx\nx\nx\n")
            val rc = GrepCommandSpec().run(listOf("-m", "2", "x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("x\nx\n", out.readString())
        }

    @Test fun max_count_inline_arg() =
        runTest {
            val (ctx, out, _) = ctxFor("a\na\na\n")
            val rc = GrepCommandSpec().run(listOf("-m1", "a"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("a\n", out.readString())
        }

    @Test fun max_count_long_form() =
        runTest {
            val (ctx, out, _) = ctxFor("y\ny\ny\n")
            val rc = GrepCommandSpec().run(listOf("--max-count=2", "y"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("y\ny\n", out.readString())
        }

    @Test fun max_count_with_count_only_caps_count() =
        runTest {
            val (ctx, out, _) = ctxFor("z\nz\nz\nz\n")
            val rc = GrepCommandSpec().run(listOf("-c", "-m", "2", "z"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("2\n", out.readString())
        }

    // ---------- -L / --files-without-match ----------

    @Test fun files_without_match_prints_only_misses() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/a", "hit\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/b", "miss\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-L", "hit", "/a", "/b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("/b\n", out.readString())
        }

    @Test fun files_without_match_long_form() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/x", "nothing here\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("--files-without-match", "needle", "/x"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("/x\n", out.readString())
        }

    @Test fun files_without_match_when_all_match_prints_nothing() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/a", "hit\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-L", "hit", "/a"), ctx)
            // Exit 1: no -L output produced.
            assertEquals(1, rc.exitCode)
            assertEquals("", out.readString())
        }

    // ---------- -A / -B / -C ----------

    @Test fun after_context_prints_n_lines_after() =
        runTest {
            val (ctx, out, _) = ctxFor("a\nMATCH\nb\nc\nd\n")
            val rc = GrepCommandSpec().run(listOf("-A", "2", "MATCH"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("MATCH\nb\nc\n", out.readString())
        }

    @Test fun before_context_prints_n_lines_before() =
        runTest {
            val (ctx, out, _) = ctxFor("a\nb\nc\nMATCH\nd\n")
            val rc = GrepCommandSpec().run(listOf("-B", "2", "MATCH"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("b\nc\nMATCH\n", out.readString())
        }

    @Test fun context_both_sides() =
        runTest {
            val (ctx, out, _) = ctxFor("a\nb\nMATCH\nc\nd\n")
            val rc = GrepCommandSpec().run(listOf("-C", "1", "MATCH"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("b\nMATCH\nc\n", out.readString())
        }

    @Test fun context_inline_arg() =
        runTest {
            val (ctx, out, _) = ctxFor("x\nM\ny\n")
            val rc = GrepCommandSpec().run(listOf("-A1", "M"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("M\ny\n", out.readString())
        }

    @Test fun context_long_form() =
        runTest {
            val (ctx, out, _) = ctxFor("a\nMATCH\nb\n")
            val rc = GrepCommandSpec().run(listOf("--after-context=1", "MATCH"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("MATCH\nb\n", out.readString())
        }

    @Test fun context_separator_between_non_adjacent_groups() =
        runTest {
            val (ctx, out, _) = ctxFor("x\nMATCH\ny\na\nb\nMATCH\nc\n")
            val rc = GrepCommandSpec().run(listOf("-A", "1", "MATCH"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("MATCH\ny\n--\nMATCH\nc\n", out.readString())
        }

    @Test fun context_overlap_merges_groups() =
        runTest {
            // Two matches one line apart, -A1 -B1 → contexts overlap; no `--`.
            val (ctx, out, _) = ctxFor("a\nMATCH\nb\nMATCH\nc\n")
            val rc = GrepCommandSpec().run(listOf("-C", "1", "MATCH"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("a\nMATCH\nb\nMATCH\nc\n", out.readString())
        }

    @Test fun context_with_line_numbers_uses_dash_for_context() =
        runTest {
            val (ctx, out, _) = ctxFor("a\nMATCH\nb\n")
            val rc = GrepCommandSpec().run(listOf("-n", "-C", "1", "MATCH"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("1-a\n2:MATCH\n3-b\n", out.readString())
        }

    @Test fun max_count_with_after_context_drains() =
        runTest {
            // After hitting -m1, -A2 still drains.
            val (ctx, out, _) = ctxFor("M\na\nb\nM\nc\n")
            val rc = GrepCommandSpec().run(listOf("-m", "1", "-A", "2", "M"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("M\na\nb\n", out.readString())
        }

    // ---------- --include / --exclude ----------

    @Test fun include_filters_files_in_recursive_walk() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/d/a.txt", "needle\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/d/b.log", "needle\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-r", "--include=*.txt", "needle", "/d"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("/d/a.txt:needle\n", out.readString())
        }

    @Test fun exclude_filters_files_in_recursive_walk() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/d/a.txt", "needle\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/d/b.log", "needle\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-r", "--exclude=*.log", "needle", "/d"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("/d/a.txt:needle\n", out.readString())
        }

    @Test fun multiple_include_globs_accumulate() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/d/a.txt", "x\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/d/b.md", "x\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/d/c.log", "x\n".encodeToByteArray())
            val rc =
                GrepCommandSpec().run(
                    listOf("-r", "--include=*.txt", "--include=*.md", "x", "/d"),
                    ctx,
                )
            assertEquals(0, rc.exitCode)
            val s = out.readString()
            assertTrue(s.contains("/d/a.txt:x"), "got: $s")
            assertTrue(s.contains("/d/b.md:x"), "got: $s")
            assertTrue(!s.contains("c.log"), "got: $s")
        }

    // ---------- -P / --perl-regexp (compat alias) ----------

    @Test fun perl_regexp_accepted_and_uses_ere_syntax() =
        runTest {
            val (ctx, out, _) = ctxFor("ab\nac\n")
            val rc = GrepCommandSpec().run(listOf("-P", "a(b|c)"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("ab\nac\n", out.readString())
        }

    @Test fun perl_regexp_lookahead_errors_with_helpful_hint() =
        runTest {
            val (ctx, _, err) = ctxFor("abc\n")
            val rc = GrepCommandSpec().run(listOf("-P", "foo(?=bar)"), ctx)
            assertEquals(2, rc.exitCode)
            val msg = err.readString()
            assertTrue("RE2" in msg, "expected RE2-backend hint, got: $msg")
        }

    // ---------- --exclude-dir / --include-dir ----------

    @Test fun exclude_dir_prunes_subtree_in_recursive_walk() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/d/a.txt", "needle\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/d/node_modules/junk.txt", "needle\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/d/sub/b.txt", "needle\n".encodeToByteArray())
            val rc =
                GrepCommandSpec().run(
                    listOf("-r", "--exclude-dir=node_modules", "needle", "/d"),
                    ctx,
                )
            assertEquals(0, rc.exitCode)
            val s = out.readString()
            assertTrue("/d/a.txt:needle" in s, "got: $s")
            assertTrue("/d/sub/b.txt:needle" in s, "got: $s")
            assertTrue("node_modules" !in s, "got: $s")
        }

    @Test fun exclude_dir_with_glob_pattern() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/d/keep/a.txt", "needle\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/d/.git/x", "needle\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/d/.cache/y", "needle\n".encodeToByteArray())
            val rc =
                GrepCommandSpec().run(
                    listOf("-r", "--exclude-dir=.*", "needle", "/d"),
                    ctx,
                )
            assertEquals(0, rc.exitCode)
            val s = out.readString()
            assertTrue("/d/keep/a.txt:needle" in s, "got: $s")
            assertTrue(".git" !in s, "got: $s")
            assertTrue(".cache" !in s, "got: $s")
        }

    @Test fun exclude_dir_does_not_filter_root_argument() =
        runTest {
            // User explicitly passes /node_modules as the search root — even
            // though --exclude-dir=node_modules names it, the root itself is
            // never pruned (GNU behavior).
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/node_modules/a.txt", "needle\n".encodeToByteArray())
            val rc =
                GrepCommandSpec().run(
                    listOf("-r", "--exclude-dir=node_modules", "needle", "/node_modules"),
                    ctx,
                )
            assertEquals(0, rc.exitCode)
            assertTrue("needle" in out.readString())
        }

    @Test fun include_dir_only_descends_matching_dirs() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/d/src/a.txt", "needle\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/d/build/junk.txt", "needle\n".encodeToByteArray())
            val rc =
                GrepCommandSpec().run(
                    listOf("-r", "--include-dir=src", "needle", "/d"),
                    ctx,
                )
            assertEquals(0, rc.exitCode)
            val s = out.readString()
            assertTrue("/d/src/a.txt:needle" in s, "got: $s")
            assertTrue("build" !in s, "got: $s")
        }

    // ---------- binary file handling ----------

    @Test fun binary_default_prints_marker_line() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            // Embed a NUL — make the file look binary.
            val bytes = "needle\nblah${0.toChar()}\n".encodeToByteArray()
            ctx.process.fs.writeBytes("/b", bytes)
            val rc = GrepCommandSpec().run(listOf("needle", "/b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("Binary file /b matches\n", out.readString())
        }

    @Test fun binary_with_dash_a_treats_as_text() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            val bytes = "needle\nblah${0.toChar()}\n".encodeToByteArray()
            ctx.process.fs.writeBytes("/b", bytes)
            val rc = GrepCommandSpec().run(listOf("-a", "needle", "/b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("needle\n", out.readString())
        }

    @Test fun binary_with_dash_I_skips_file() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            val bytes = "needle\nblah${0.toChar()}\n".encodeToByteArray()
            ctx.process.fs.writeBytes("/b", bytes)
            val rc = GrepCommandSpec().run(listOf("-I", "needle", "/b"), ctx)
            // -I treats it as having no match.
            assertEquals(1, rc.exitCode)
            assertEquals("", out.readString())
        }

    @Test fun binary_files_long_form_text() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            val bytes = "hit\n${0.toChar()}\n".encodeToByteArray()
            ctx.process.fs.writeBytes("/b", bytes)
            val rc = GrepCommandSpec().run(listOf("--binary-files=text", "hit", "/b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("hit\n", out.readString())
        }

    @Test fun binary_files_long_form_without_match() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/b", "hit\n${0.toChar()}\n".encodeToByteArray())
            val rc =
                GrepCommandSpec().run(listOf("--binary-files=without-match", "hit", "/b"), ctx)
            assertEquals(1, rc.exitCode)
            assertEquals("", out.readString())
        }

    @Test fun binary_files_rejects_bad_value() =
        runTest {
            val (ctx, _, err) = ctxFor("hit\n")
            val rc = GrepCommandSpec().run(listOf("--binary-files=banana", "hit"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue("banana" in err.readString())
        }

    @Test fun binary_text_file_unaffected_by_default_mode() =
        runTest {
            // No NUL bytes → not flagged binary; emit lines normally.
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/b", "needle\nother\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("needle", "/b"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("needle\n", out.readString())
        }

    // ---------- -z / --null-data ----------

    @Test fun null_data_splits_input_on_nul_and_terminates_output_with_nul() =
        runTest {
            // Three NUL-separated records; two contain "foo".
            val (ctx, out, _) = ctxFor("foo\u0000barfoo\u0000baz\u0000")
            val rc = GrepCommandSpec().run(listOf("-z", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("foo\u0000barfoo\u0000", out.readString())
        }

    @Test fun null_data_record_may_span_newlines() =
        runTest {
            // A record is delimited by NUL, so an embedded newline is data —
            // the whole "alpha\nbeta" record matches on "beta".
            val (ctx, out, _) = ctxFor("alpha\nbeta\u0000gamma\u0000")
            val rc = GrepCommandSpec().run(listOf("-z", "beta"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("alpha\nbeta\u0000", out.readString())
        }

    @Test fun null_data_long_form() =
        runTest {
            val (ctx, out, _) = ctxFor("one\u0000two\u0000")
            val rc = GrepCommandSpec().run(listOf("--null-data", "two"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("two\u0000", out.readString())
        }

    @Test fun null_data_no_trailing_delimiter_still_yields_last_record() =
        runTest {
            // Last record has no trailing NUL; it's still returned at EOF and,
            // when matched, emitted NUL-terminated.
            val (ctx, out, _) = ctxFor("aaa\u0000bbb")
            val rc = GrepCommandSpec().run(listOf("-z", "bbb"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("bbb\u0000", out.readString())
        }

    @Test fun null_data_disables_binary_nul_heuristic_for_files() =
        runTest {
            // Under -z, NUL is the record delimiter, so a file full of NULs is
            // NOT flagged binary — matching records print normally instead of
            // the "Binary file matches" marker.
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/f", "needle\u0000other\u0000".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-z", "needle", "/f"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("needle\u0000", out.readString())
        }

    // ---------- -d / --directories ----------

    @Test fun dash_d_skip_drops_directory_operand_silently() =
        runTest {
            val (ctx, out, err) = ctxFor("")
            // Writing into /d makes /d a directory.
            ctx.process.fs.writeBytes("/d/a.txt", "needle\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-d", "skip", "needle", "/d"), ctx)
            // The only operand was a dir; skipped -> nothing searched, no error.
            assertEquals(1, rc.exitCode)
            assertEquals("", out.readString())
            assertEquals("", err.readString())
        }

    @Test fun dash_d_skip_still_searches_file_operands() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/d/a.txt", "x\n".encodeToByteArray())
            ctx.process.fs.writeBytes("/f", "needle\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-d", "skip", "needle", "/d", "/f"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("/f:needle\n", out.readString())
        }

    @Test fun dash_d_recurse_behaves_like_dash_r() =
        runTest {
            val (ctx, out, _) = ctxFor("")
            ctx.process.fs.writeBytes("/d/a.txt", "needle\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-d", "recurse", "needle", "/d"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue(out.readString().contains("/d/a.txt:needle"))
        }

    @Test fun dash_d_read_reports_is_a_directory() =
        runTest {
            val (ctx, _, err) = ctxFor("")
            ctx.process.fs.writeBytes("/d/a.txt", "x\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("-d", "read", "needle", "/d"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().contains("Is a directory"))
        }

    @Test fun dash_d_long_form_skip() =
        runTest {
            val (ctx, _, err) = ctxFor("")
            ctx.process.fs.writeBytes("/d/a.txt", "needle\n".encodeToByteArray())
            val rc = GrepCommandSpec().run(listOf("--directories=skip", "needle", "/d"), ctx)
            assertEquals(1, rc.exitCode)
            assertEquals("", err.readString())
        }

    @Test fun dash_d_rejects_bad_value() =
        runTest {
            val (ctx, _, err) = ctxFor("hi\n")
            val rc = GrepCommandSpec().run(listOf("-d", "banana", "x"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().contains("banana"))
        }
}
