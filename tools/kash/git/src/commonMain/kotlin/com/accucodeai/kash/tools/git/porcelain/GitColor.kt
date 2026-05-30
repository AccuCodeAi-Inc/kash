package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.ansi.AnsiStyler
import com.accucodeai.kash.api.ansi.ColorMode
import com.accucodeai.kash.api.ansi.Sgr
import com.accucodeai.kash.tools.git.GitRepo

/**
 * Shared color plumbing for the git subcommands. Resolution order:
 *
 *  1. an explicit `--color[=<when>]` / `--no-color` on the command line, else
 *  2. `color.<cmd>` then `color.ui` from config (an explicit opt-in), else
 *  3. **off** — kash's house rule (docs/TOOLS.md): every tool defaults to
 *     `ColorMode.NEVER` so a bare invocation emits zero ANSI bytes. The
 *     interactive REPL opts back in via `alias git='git --color=auto'` in
 *     `Kash.DEFAULT_KASHRC`. This deliberately diverges from git's native
 *     `color.ui=auto` default, because kash's primary consumer is LLMs and
 *     byte-clean stdout matters more than a colored pipe.
 *
 * `AUTO` (from a flag or `color.ui=auto`) is then resolved by [Ansi.stylerFor],
 * which honors `NO_COLOR` / `TERM=dumb` / `CLICOLOR*` and the stdout tty flag.
 */
internal suspend fun gitColorStyler(
    ctx: CommandContext,
    repo: GitRepo?,
    cmd: String,
    explicit: ColorMode?,
    overrides: Map<String, String> = emptyMap(),
): AnsiStyler = Ansi.stylerFor(ctx, Ansi.Stream.STDOUT, explicit ?: configColorMode(repo, cmd, overrides))

private suspend fun configColorMode(
    repo: GitRepo?,
    cmd: String,
    overrides: Map<String, String>,
): ColorMode {
    // `git -c color.<cmd>=…` / `-c color.ui=…` win over .git/config.
    val v =
        overrides["color.$cmd"]
            ?: overrides["color.ui"]
            ?: run {
                if (repo == null) return ColorMode.NEVER
                val cfg = readGitConfig(repo)
                cfg["color"]?.get(cmd) ?: cfg["color"]?.get("ui")
            }
    // kash defaults to no color; only an explicit `always`/`auto` opt-in
    // (config or alias) turns it on. A bare `true` is auto, like git. A
    // missing value, `never`/`false`, or anything unrecognized stays off.
    return when (v?.trim()?.lowercase()) {
        "always" -> ColorMode.ALWAYS
        "auto", "true" -> ColorMode.AUTO
        else -> ColorMode.NEVER
    }
}

/**
 * Colorize a complete unified-diff text with git's default `color.diff`
 * palette: metadata bold, `@@` hunk headers cyan, added lines green, removed
 * lines red. Lines are matched by their leading byte, so `---`/`+++` (meta)
 * are handled before the generic `-`/`+` body lines. A no-op when [styler] is
 * off, so callers can wrap unconditionally.
 */
internal fun colorizeDiff(
    text: String,
    styler: AnsiStyler,
): String {
    if (!styler.on || text.isEmpty()) return text
    return text.split("\n").joinToString("\n") { line -> colorizeDiffLine(line, styler) }
}

private fun colorizeDiffLine(
    line: String,
    styler: AnsiStyler,
): String =
    when {
        line.isEmpty() -> line

        line.startsWith("@@") -> styler.style(line, Sgr.FG_CYAN)

        line.startsWith("+++") || line.startsWith("---") -> styler.style(line, Sgr.BOLD)

        line.startsWith("diff ") ||
            line.startsWith("index ") ||
            line.startsWith("old mode") ||
            line.startsWith("new mode") ||
            line.startsWith("new file") ||
            line.startsWith("deleted file") ||
            line.startsWith("similarity index") ||
            line.startsWith("dissimilarity index") ||
            line.startsWith("rename ") ||
            line.startsWith("copy ") -> styler.style(line, Sgr.BOLD)

        line.startsWith("+") -> styler.style(line, Sgr.FG_GREEN)

        line.startsWith("-") -> styler.style(line, Sgr.FG_RED)

        else -> line
    }
