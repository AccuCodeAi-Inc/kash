package com.accucodeai.kash.completion

import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.terminal.Candidate
import com.accucodeai.kash.api.terminal.Completer
import com.accucodeai.kash.api.terminal.CompletionResult
import com.accucodeai.kash.api.util.matchGlob
import com.accucodeai.kash.api.util.splitPath
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.interpreter.Interpreter
import com.accucodeai.kash.intrinsics.IntrinsicCatalog
import com.accucodeai.kash.parser.RESERVED_WORDS

/**
 * Default shell-aware [Completer]. Produces:
 *  - Command-name candidates (intrinsics ∪ registry tools ∪ functions ∪
 *    aliases ∪ PATH binaries) when the active token is the first word of
 *    a (sub)command.
 *  - Variable-name candidates (from [Interpreter.env]) when the token
 *    starts with `$` or `${`.
 *  - Filename candidates against [FileSystem.list] when the token is an
 *    argument or a path-shaped command (one containing `/`). Directory
 *    entries are tagged with `trailing = "/"` so the editor doesn't append
 *    a space the user is about to overwrite.
 *
 * Tilde: a leading `~/` is resolved to `$HOME` for the filesystem lookup
 * but is preserved verbatim in the inserted text — matches bash's default
 * (no tilde expansion on completion when `direxpand` is off).
 *
 * Read-only: never mutates interpreter or filesystem state. All FS calls
 * are guarded against I/O exceptions and degrade to "no candidates".
 */
public class ShellCompleter(
    private val interpreter: Interpreter,
    private val registry: CommandRegistry,
    private val fs: FileSystem,
) : Completer {
    override suspend fun complete(
        line: String,
        cursor: Int,
    ): CompletionResult {
        val tok = tokenAtCursor(line, cursor)
        val partial = tok.text

        // Variable completion: $FOO or ${FOO (closing brace auto-added).
        if (partial.startsWith("$")) {
            val braceForm = partial.startsWith("\${")
            val varPrefix = if (braceForm) partial.substring(2) else partial.substring(1)
            // Reject if the var-name region already contains a closing brace
            // or other shell metacharacter — that's no longer a name to
            // complete.
            if (varPrefix.all { it == '_' || it.isLetterOrDigit() }) {
                val names =
                    interpreter.env.keys
                        .filter { it.startsWith(varPrefix) }
                        .sorted()
                if (names.isNotEmpty()) {
                    val candidates =
                        names.map { name ->
                            if (braceForm) {
                                Candidate(text = "\${$name}", trailing = " ")
                            } else {
                                Candidate(text = "$$name", trailing = " ")
                            }
                        }
                    return CompletionResult.of(tok.start, tok.end, candidates)
                }
                return CompletionResult.Empty
            }
        }

        // Filename-shaped: contains `/` or starts with `~/` or `./` → always
        // filename completion regardless of command-vs-arg position. e.g.
        // `./scr<TAB>` should complete to `./script` even in command pos.
        val isPathShaped = '/' in partial || partial.startsWith("~/") || partial == "~"

        if (tok.isCommandPosition && !isPathShaped) {
            return completeCommandName(partial, tok.start, tok.end)
        }

        // Argument position: consult a `complete`-registered spec for the
        // command being completed before falling back to filename completion.
        val commandName = commandWordOf(line, tok.start)
        val spec = commandName?.let { interpreter.completeSpecs[it] } ?: interpreter.completeDefault
        if (spec != null) {
            val r = completeFromSpec(spec, partial, tok.start, tok.end)
            if (r != null) return r
            // Spec produced no matches: bash's `bashdefault` option says fall
            // through to default completion; otherwise return Empty.
            if (CompleteOption.BashDefault !in spec.options &&
                CompleteOption.Default !in spec.options
            ) {
                return CompletionResult.Empty
            }
        }

        return completeFilename(partial, tok.start, tok.end)
    }

    /**
     * Build candidates from a registered [CompleteSpec]. Returns null if the
     * spec produced zero candidates and the caller should consider falling
     * back to the default heuristic. Returns an empty [CompletionResult]
     * when the spec is authoritative (no bashdefault) and had no matches —
     * the caller should respect that.
     */
    private fun completeFromSpec(
        spec: CompleteSpec,
        partial: String,
        start: Int,
        end: Int,
    ): CompletionResult? {
        val raw = mutableListOf<String>()
        for (act in spec.actions) raw += enumerateActionForCompleter(act)
        spec.wordlist?.let { wl ->
            raw += wl.split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        spec.glob?.let { g ->
            if ('/' !in g) {
                val entries =
                    try {
                        fs.list(interpreter.cwd)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                raw += entries.filter { matchGlob(g, it) }
            }
        }

        // -X filter: drop matches; leading `!` inverts.
        val filtered =
            spec.filter?.let { pat ->
                val negate = pat.startsWith("!")
                val p = if (negate) pat.substring(1) else pat
                raw.filter { s ->
                    val m = matchGlob(p, s)
                    if (negate) m else !m
                }
            } ?: raw

        val matches =
            filtered
                .filter { it.startsWith(partial) }
                .distinct()
                .sorted()

        if (matches.isEmpty()) return null

        // Decoration: prefix, suffix.
        val decorated =
            matches.map { m ->
                (spec.prefix ?: "") + m + (spec.suffix ?: "")
            }
        // -o nospace overrides the editor's trailing space.
        val trailing = if (CompleteOption.NoSpace in spec.options) "" else " "
        val candidates = decorated.map { Candidate(text = it, trailing = trailing) }
        return CompletionResult.of(start, end, candidates)
    }

    /**
     * Enumerate raw candidate strings for one action. Subset of
     * `IntrinsicsCompgen.enumerate` — shared logic could be hoisted later
     * once the shapes are obviously identical.
     */
    private fun enumerateActionForCompleter(action: CompleteAction): List<String> =
        when (action) {
            CompleteAction.Alias -> {
                interpreter.aliases.keys.sorted()
            }

            CompleteAction.Builtin -> {
                IntrinsicCatalog.names.sorted()
            }

            CompleteAction.Function -> {
                interpreter.functions.keys.sorted()
            }

            CompleteAction.Variable, CompleteAction.Export -> {
                interpreter.env.keys.sorted()
            }

            CompleteAction.Keyword -> {
                RESERVED_WORDS.sorted()
            }

            CompleteAction.Directory -> {
                listDirEntries(interpreter.cwd, dirsOnly = true)
            }

            CompleteAction.File -> {
                listDirEntries(interpreter.cwd, dirsOnly = false)
            }

            CompleteAction.Command -> {
                val names = mutableSetOf<String>()
                names += IntrinsicCatalog.names
                names += registry.names()
                names += interpreter.functions.keys
                names += interpreter.aliases.keys
                names.sorted()
            }

            else -> {
                emptyList()
            }
        }

    private fun listDirEntries(
        dir: String,
        dirsOnly: Boolean,
    ): List<String> {
        val entries =
            try {
                fs.list(dir)
            } catch (_: Throwable) {
                return emptyList()
            }
        val out = mutableListOf<String>()
        for (entry in entries) {
            val full = if (dir == "/") "/$entry" else "$dir/$entry"
            val isDir =
                try {
                    fs.isDirectory(full)
                } catch (_: Throwable) {
                    false
                }
            if (dirsOnly && !isDir) continue
            out += entry
        }
        return out.sorted()
    }

    /**
     * The first word of the (sub)command containing the token at [tokStart].
     * Walks backward from [tokStart] over whitespace, then over the previous
     * word (the command name in this segment), and returns its text. Returns
     * null if we cross a command separator (`;`, `|`, `&`, newline, `(`,
     * `{`) before finding a word — meaning the token *is* in command
     * position or there's nothing to associate with.
     */
    private fun commandWordOf(
        line: String,
        tokStart: Int,
    ): String? {
        var i = tokStart - 1
        // Skip whitespace.
        while (i >= 0 && line[i].isWhitespace() && line[i] != '\n') i--
        // Walk back to the start of a command-segment, collecting words.
        val words = mutableListOf<String>()
        var wordEnd = i + 1
        while (i >= 0) {
            val ch = line[i]
            if (ch == ';' || ch == '|' || ch == '&' || ch == '\n' || ch == '(' || ch == '{') {
                if (wordEnd > i + 1) words += line.substring(i + 1, wordEnd).trim()
                break
            }
            if (ch.isWhitespace()) {
                if (wordEnd > i + 1) words += line.substring(i + 1, wordEnd).trim()
                while (i >= 0 && line[i].isWhitespace() && line[i] != '\n') i--
                wordEnd = i + 1
                continue
            }
            i--
        }
        if (i < 0 && wordEnd > 0) {
            words += line.substring(0, wordEnd).trim()
        }
        // The command name is the last (innermost) word we found while
        // walking backward — which is the first word of the simple command.
        return words.lastOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun completeCommandName(
        partial: String,
        start: Int,
        end: Int,
    ): CompletionResult {
        val names = mutableSetOf<String>()
        names += IntrinsicCatalog.names.filter { it.startsWith(partial) }
        names += registry.names().filter { it.startsWith(partial) }
        // Function/alias tables are mutable on Interpreter — read once.
        names += interpreter.functions.keys.filter { it.startsWith(partial) }
        names += interpreter.aliases.keys.filter { it.startsWith(partial) }

        // PATH walk for external executables. Skip non-existent dirs silently.
        for (dir in splitPath(interpreter.env["PATH"])) {
            val resolvedDir = if (dir.isEmpty()) interpreter.cwd else dir
            val entries =
                try {
                    fs.list(resolvedDir)
                } catch (_: Throwable) {
                    continue
                }
            for (entry in entries) {
                if (!entry.startsWith(partial)) continue
                val full = if (resolvedDir == "/") "/$entry" else "$resolvedDir/$entry"
                val isFile =
                    try {
                        fs.exists(full) && !fs.isDirectory(full)
                    } catch (_: Throwable) {
                        false
                    }
                if (isFile) names += entry
            }
        }

        val sorted = names.toList().sorted()
        return CompletionResult.of(
            replaceStart = start,
            replaceEnd = end,
            candidates = sorted.map { Candidate(text = it, trailing = " ") },
        )
    }

    private fun completeFilename(
        partial: String,
        start: Int,
        end: Int,
    ): CompletionResult {
        // Split partial into (dirPart, basePrefix). dirPart is the slice
        // up to and including the last `/` (or empty if no slash); basePrefix
        // is what we filter directory entries by.
        val lastSlash = partial.lastIndexOf('/')
        val dirPart: String
        val basePrefix: String
        if (lastSlash < 0) {
            dirPart = ""
            basePrefix = partial
        } else {
            dirPart = partial.substring(0, lastSlash + 1)
            basePrefix = partial.substring(lastSlash + 1)
        }

        // Resolve dirPart against cwd / tilde to get a real FS path. Keep
        // dirPart unchanged in the inserted text — completion preserves the
        // user's preferred reference style (relative, ~, absolute).
        val home = interpreter.env["HOME"] ?: "/"
        val resolvedDir: String =
            when {
                dirPart.isEmpty() -> interpreter.cwd
                dirPart == "~/" -> home
                dirPart.startsWith("~/") -> Paths.resolve(home, dirPart.substring(2))
                dirPart.startsWith("/") -> dirPart
                else -> Paths.resolve(interpreter.cwd, dirPart)
            }

        val entries =
            try {
                fs.list(resolvedDir)
            } catch (_: Throwable) {
                return CompletionResult.Empty
            }

        val candidates = mutableListOf<Candidate>()
        for (entry in entries) {
            if (!entry.startsWith(basePrefix)) continue
            // Skip dotfiles unless the user typed a leading dot — bash default.
            if (entry.startsWith(".") && !basePrefix.startsWith(".")) continue
            val full = if (resolvedDir == "/") "/$entry" else "$resolvedDir/$entry"
            val isDir =
                try {
                    fs.isDirectory(full)
                } catch (_: Throwable) {
                    false
                }
            // Backslash-escape shell metacharacters in the basename so a file
            // named `my file.txt` becomes `my\ file.txt` once inserted —
            // otherwise the resulting command line would split it into two
            // arguments. We escape only the basename; dirPart is left as
            // typed (preserves leading `./`, `~/`, `/foo/`, etc.).
            val text = dirPart + shellEscape(entry)
            val trailing = if (isDir) "/" else " "
            candidates += Candidate(text = text, trailing = trailing)
        }
        candidates.sortBy { it.text }
        return CompletionResult.of(start, end, candidates)
    }

    /**
     * Backslash-escape shell metacharacters in [name] so it round-trips
     * through the parser as one literal word. Matches bash's default
     * (`-o filenames`) escape set.
     */
    private fun shellEscape(name: String): String {
        val sb = StringBuilder(name.length)
        for (c in name) {
            if (c in METACHARS) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private companion object {
        // Chars that need backslash-escaping inside an unquoted word. Mirrors
        // bash's `quote_filename` in bashline.c — whitespace, shell syntax
        // chars, and glob metacharacters.
        val METACHARS: Set<Char> =
            setOf(
                ' ',
                '\t',
                '\n',
                '\'',
                '"',
                '\\',
                '$',
                '`',
                '!',
                ';',
                '&',
                '|',
                '(',
                ')',
                '<',
                '>',
                '*',
                '?',
                '[',
                ']',
                '{',
                '}',
                '#',
                '~',
            )
    }
}

/**
 * Lexical token under [cursor]: where it starts, where it ends, the
 * substring itself, and whether it sits in command position (i.e. the
 * first word of a simple command — preceded only by whitespace, `;`,
 * `&`, `|`, `&&`, `||`, `(`, `{`, or newline).
 *
 * Top-level so unit tests don't need to construct a [ShellCompleter] (with
 * its Interpreter dependency) just to exercise the lexical logic.
 *
 * Quoting: we cut tokens on unquoted whitespace and unquoted shell
 * separators. We don't try to be smart about partially-quoted partials
 * (e.g. `ls "foo<TAB>`) for v1 — that token includes the leading `"` and
 * the FS lookup will simply not find it; bash handles this via compspec
 * polish that's not worth replicating yet.
 */
internal fun tokenAtCursor(
    line: String,
    cursor: Int,
): Token {
    var start = cursor
    while (start > 0) {
        val ch = line[start - 1]
        if (ch.isWhitespace() || ch in TOKEN_BREAK) break
        start--
    }
    val end = cursor
    val text = line.substring(start, end)

    // Command position: scan backward from `start - 1` over whitespace;
    // the previous non-whitespace char must be a command-separator
    // (start-of-string, `;`, `&`, `|`, `(`, `{`, newline).
    var i = start - 1
    while (i >= 0 && line[i].isWhitespace() && line[i] != '\n') i--
    val isCommandPosition = i < 0 || line[i] in COMMAND_PREDECESSORS

    return Token(start = start, end = end, text = text, isCommandPosition = isCommandPosition)
}

internal data class Token(
    val start: Int,
    val end: Int,
    val text: String,
    val isCommandPosition: Boolean,
)

private val TOKEN_BREAK: Set<Char> = setOf(';', '&', '|', '(', ')', '{', '}', '<', '>', '\n')
private val COMMAND_PREDECESSORS: Set<Char> = setOf(';', '&', '|', '(', '{', '\n')
