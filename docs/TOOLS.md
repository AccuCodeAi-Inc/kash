# Tools Roadmap

What kash ships today, what's missing, and the rough order to fill it in.

## Registration model

All commands — intrinsics, builtins, and tools — live in **one
[`CommandRegistry`](../api/src/commonMain/kotlin/com/accucodeai/kash/api/CommandRegistry.kt)**.
The interpreter, `type` / `command -v`, and any future `enable -n NAME`
read from this single catalog. No DI, no annotation processor — every
tool subsystem exposes a plain `val xxxCommands: List<CommandSpec>` and
the entry point concatenates them.

To add a new tool:

```kotlin
// tools/posix/grep/src/commonMain/kotlin/.../GrepCommand.kt
public class GrepCommand : Command, CommandSpec {
    override val name = "grep"
    override val kind = CommandKind.TOOL
    override val tags = setOf(CommandTag.POSIX)
    override val command get() = this

    override suspend fun run(args: List<String>, ctx: CommandContext): CommandResult {
        // Streaming I/O — read line-by-line from ctx.stdin, write matches to
        // ctx.stdout as you go. Closing downstream raises BrokenPipeException
        // on the next write (our SIGPIPE), so `producer | grep foo | head -n 1`
        // terminates promptly.
        while (true) {
            val line = ctx.stdin.readUtf8LineOrNull() ?: break
            if (matches(line, args)) ctx.stdout.writeLine(line)
        }
        return CommandResult()
    }
}

// tools/posix/grep/src/commonMain/kotlin/.../GrepModule.kt
public val grepCommands: List<CommandSpec> = listOf(GrepCommand())
```

Tools live under one of three trees by stability tier:
`tools/posix/<name>/` for utilities required by POSIX XCU §4,
`tools/ext/<name>/` for non-POSIX but de-facto-universal helpers
(`base64`, `seq`, `which`, …), `tools/kash/<name>/` for kash-only
extensions (`jq`, `python3`, the shell itself).

Each tree has a subsystem-level aggregator that concatenates every per-tool
list — [`PosixToolsModule.kt`](../tools/posix/posix-module/src/commonMain/kotlin/com/accucodeai/kash/tools/posix/PosixToolsModule.kt)
(exposes `posixCommands`), `ExtToolsModule.kt` (`extCommands`),
`KashToolsModule.kt` (`kashCommands`). When you add a tool, add an
`import …grepCommands` and `+ grepCommands` to the relevant aggregator,
then `implementation(project(":tools:posix:grep"))` to the aggregator's
`build.gradle.kts`.

[`Kash.kt`](../kash/src/commonMain/kotlin/com/accucodeai/kash/Kash.kt)
calls `defaultCommandSpecs()` (= `posixCommands + extCommands + kashCommands`)
for the bare `Kash()` entrypoint. App-level entry points
([`KashAppModule.kt`](../kash-app/src/jvmMain/kotlin/com/accucodeai/kash/app/KashAppModule.kt)
on JVM, [`KashAppWebModule.kt`](../kash-app-web/src/wasmJsMain/kotlin/com/accucodeai/kash/app/KashAppWebModule.kt)
on wasmJs) extend that with anything that needs runtime state — currently
just `python3Commands(engine)` where `engine` is `GraalPyEngine()` on JVM
and `PyodideEngine()` on wasmJs.

Done — `grep` is reachable from kash scripts, picked up by `type`, and
obeys `isSpecial`/POSIX semantics if you set them.

### Streaming I/O contract

[`CommandContext`](../api/src/commonMain/kotlin/com/accucodeai/kash/api/Command.kt)
gives every command a `SuspendSource` `stdin` and two `SuspendSink`s
(`stdout`, `stderr`). The shapes mirror `kotlinx.io.RawSink`/`RawSource` so a
tool body looks the same — the methods are just `suspend`, and back-pressure
parks the *coroutine* not the dispatcher thread. Multi-stage pipelines run
each command in its own coroutine connected by an
[`AsyncPipe`](../api/src/commonMain/kotlin/com/accucodeai/kash/api/io/AsyncPipe.kt)
— so `yes | head -n 5` terminates instead of producing forever (downstream
closes its source on exit; producer trips `BrokenPipeException` on its next
write). Use the suspend helpers in
[`SuspendIo.kt`](../api/src/commonMain/kotlin/com/accucodeai/kash/api/io/SuspendIo.kt):

- `SuspendSink.writeUtf8(s)` / `writeLine(s)` / `writeBytes(b)` / `transferFrom(src)`
- `SuspendSource.readUtf8Text()` / `readAllBytes()` / `readUtf8LineOrNull()`

The sync helpers on `RawSink`/`RawSource` in
[`SinkExtensions.kt`](../api/src/commonMain/kotlin/com/accucodeai/kash/api/io/SinkExtensions.kt)
are retained for tools that build a local `Buffer` synchronously before
flushing through a `SuspendSink`.

Each write auto-flushes so output reaches downstream immediately. Closing
the read end of a pipe raises `BrokenPipeException` on the next write —
that's our SIGPIPE.

### Filesystem & mount contributions

Every command receives `ctx.fs: FileSystem` and operates against kash's
virtual filesystem. The session's `fs` is typically a
[`MountedFileSystem`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/MountedFileSystem.kt) —
a router over labeled mounts. See [`docs/MOUNTS.md`](MOUNTS.md) for the
label semantics; the short version is that mounts declare a
[`FsLabel`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/FsLabel.kt)
(`USER`, `ENGINE_CACHE`, `HOST_BORROW`, `EPHEMERAL`) so future snapshot
tooling knows what to serialize.

**If a tool needs scratch space or a host-backed cache** — like
`:tools:kash:python3-graalpy` does for GraalPy's extracted stdlib — declare it
at a known mount point and document the label. See
[`GraalPyEngine.CACHE_MOUNT_POINT`](../tools/kash/python3-graalpy/src/jvmMain/kotlin/com/accucodeai/kash/tools/python3/graalpy/GraalPyEngine.kt)
for the pattern: build the mount yourself per-invocation if `ctx.fs` doesn't
already include it, expose the path as a public constant so consumers and
snapshot tools can find it.

Tools without storage needs do nothing — `ctx.fs` already works.

### Interactive output and coloring

**kash's primary consumer is LLMs.** Every tool's default output must be
plain bytes — no ANSI escapes, no colors, no terminal control sequences.
Scripts, pipes, and `kash -c "…"` invocations get byte-identical output to
whatever the conformance fixtures expect. Coloring is a *secondary*
convenience for the interactive REPL, and the cost of getting it wrong
(byte drift in LLM stdout) is much higher than the cost of a plain REPL.

**Rules for tools that emit color:**

1. **Default to `ColorMode.NEVER`.** A bare invocation must produce zero
   ANSI bytes. The interactive REPL opts users back in via aliases shipped
   in [`Kash.DEFAULT_KASHRC`](../kash/src/commonMain/kotlin/com/accucodeai/kash/Kash.kt)
   (`alias ls='ls --color=auto'`); tools never assume an interactive
   posture themselves.

2. **Accept `--color[=WHEN]`** (`auto`/`always`/`never`, with `tty`/`yes`/`no`
   as aliases). Parse via `ColorMode.parse(value)` from
   [`Ansi.kt`](../api/src/commonMain/kotlin/com/accucodeai/kash/api/ansi/Ansi.kt).
   Bare `--color` means `auto`.

3. **Wrap output via `Ansi.stylerFor(ctx, mode = opts.color)`** — never
   write `[…m` strings directly. The styler returns a no-op when
   color is off, so callers don't branch:

   ```kotlin
   val styler = Ansi.stylerFor(ctx, mode = opts.color)
   ctx.stdout.writeLine(styler.style(name, Sgr.BOLD, Sgr.FG_BLUE))
   ```

   `stylerFor` already honors precedence: explicit mode → `NO_COLOR` →
   `TERM=dumb` → `CLICOLOR_FORCE` → `CLICOLOR=0` → `ctx.stdoutIsTty`.
   Nothing else for the tool to check.

4. **Env-driven palettes:** if the tool has a `$GREP_COLORS`/`$LS_COLORS`-
   style env var, parse it from `ctx.env[VAR]` into a struct keyed by
   role, then call `styler.style(text, sgrString)` with the raw SGR body
   (`"01;31"`). See [`LsColors`](../tools/posix/ls/src/commonMain/kotlin/com/accucodeai/kash/tools/ls/LsColors.kt)
   and [`GrepColors`](../tools/posix/grep/src/commonMain/kotlin/com/accucodeai/kash/tools/grep/GrepColors.kt)
   for the pattern: tight `data class`, factory `parse(raw: String?)`, GNU
   defaults filled in for any unset key, malformed entries skipped silently.

5. **Don't read host env directly.** Always go through `ctx.env`. The REPL
   forwards a curated allowlist (`NO_COLOR`, `TERM`, `LS_COLORS`,
   `GREP_COLORS`, …) via `Main.kt`; tests and embedders can preload an
   empty env to guarantee plain output. A tool that calls
   `System.getenv()` directly bypasses both layers.

6. **`.kashrc` sourcing inside the interactive REPL is the only mechanism
   that turns color on for humans.** kash-app's `Main` seeds
   [`Kash.DEFAULT_KASHRC`](../kash/src/commonMain/kotlin/com/accucodeai/kash/Kash.kt)
   onto the VFS on first run; [`KashShellCommand.runInteractive`](../tools/kash/shell/src/commonMain/kotlin/com/accucodeai/kash/tools/kash/KashShellCommand.kt)
   sources `$HOME/.kashrc` at REPL start. Non-interactive sessions
   (`kash -c`, script files, stdin pipe) never invoke this path, so
   default output stays plain.

**Test posture:** unit tests build a `CommandContext` with `stdoutIsTty=false`
by default, so AUTO produces plain output and color tests must use
`--color=always` (or explicitly construct a tty context). This keeps
fixture files free of escape bytes. See
[`LsCommandTest`](../tools/posix/ls/src/commonTest/kotlin/com/accucodeai/kash/tools/ls/LsCommandTest.kt)
and [`GrepCommandTest`](../tools/posix/grep/src/commonTest/kotlin/com/accucodeai/kash/tools/grep/GrepCommandTest.kt)
for examples.

## Labels

Throughout this document:

- 🅂 **POSIX special builtin** — must obey the [special-builtin
  semantics](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_14)
  (lookup beats user functions, prefix-assignments persist, errors abort a
  non-interactive script). See also the [bash manual](
  https://www.gnu.org/software/bash/manual/html_node/Special-Builtins.html).
- 🅁 **POSIX regular builtin** — required by [POSIX XCU §1.6](
  https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap01.html#tag_18_06)
  ("Built-In Utilities"). Regular semantics — assignment-scoped, errors don't abort.
- 🅄 **POSIX standard utility** — external in `bash` but every script reaches
  for it. Defined in [POSIX XCU §4](
  https://pubs.opengroup.org/onlinepubs/9699919799/utilities/contents.html).
- 🄱 **Bash extension** — not in POSIX but widely used; `declare`, `local`, `[[`, etc.
- 🅃 **Tool** — kash-only convenience (`jq`, future `yq`).

## Currently implemented

**Builtins** ([`:tools:kash:shell/.../commands/`](../tools/kash/shell/src/commonMain/kotlin/com/accucodeai/kash/commands/) — one class per file):
🅄 `echo`, 🅄 `printf`, 🅄 `true`, 🅄 `false`, 🅁 `pwd`, 🅁 `cd`, 🅄 `cat`,
🅁 `read`, 🅄 `test`, 🅄 `[`, 🅄 `sh` (via `ctx.shellRunner`), 🄱 `recho`.

**Intrinsics** (interpreter-owned; specs in [`:tools:kash:shell/.../intrinsics/IntrinsicCatalog.kt`](../tools/kash/shell/src/commonMain/kotlin/com/accucodeai/kash/intrinsics/IntrinsicCatalog.kt)):

| Label | Names |
|---|---|
| 🅂 special | `:` · `.` / `source` · `break` · `continue` · `eval` · `exec` · `exit` · `export` · `readonly` · `return` · `set` · `shift` · `times` · `trap` · `unset` |
| 🅁 regular | `alias` · `bg` · `command` · `fg` · `getopts` · `hash` · `jobs` · `kill` · `type` · `umask` · `unalias` · `wait` |
| 🄱 bash ext | `declare` · `local` · `shopt` · `typeset` |

**Tools** (`:tools/*`):
- POSIX standard utilities (`tools/posix/`): `awk` (partial), `basename`, `bc`, `cal`, `cat`, `chmod`, `cksum`, `cmp`, `comm`, `cp`, `csplit`, `cut`, `date`, `dd`, `df`, `dirname`, `du`, `echo`, `env`, `expand`, `expr`, `find`, `fold`, `getconf`, `getopt`, `grep`, `head`, `id`, `join`, `ln`, `logname`, `ls`, `make`, `mkdir`, `mkfifo`, `mktemp`, `mv`, `nice`, `nl`, `nohup`, `od`, `paste`, `pathchk`, `pgrep`, `pkill`, `printf`, `ps`, `readlink`, `realpath`, `rm`, `rmdir`, `sed`, `sh`, `shuf`, `sleep`, `sort`, `split`, `stat`, `sum`, `tail`, `tee`, `test` (incl. `[`), `touch`, `tput`, `tr`, `tree`, `true`/`false`, `tsort`, `tty`, `unexpand`, `uniq`, `wc`, `xargs`.
- De-facto utilities (`tools/ext/`): `base64`, `bzip2`, `clear`, `column`, `cpio`, `curl`, `gzip`, `lz4`, `md5sum`/`sha1sum`/`sha224sum`/`sha256sum`/`sha384sum`/`sha512sum`, `pax`, `printenv`, `readlink`, `reset`, `rev`, `seq`, `shasum`, `stat`, `tac`, `tar`, `uuidgen`, `which`, `whoami`, `xz`, `yes`, `zip`, `zstd`.
- kash-only (`tools/kash/`): 🅃 `ed`, `fzf`, `git`, `git-http`, `git-jgit`, `jq`, `less`, `nano`, `python3`, `python3-graalpy`, `python3-pyodide`, `vi`.

POSIX special-builtin semantics are implemented: prefix-assignment
persistence, lookup precedence over user functions, and abort-on-error in
non-interactive shells. See [`Interpreter.runResolvedSpec`](../tools/kash/shell/src/commonMain/kotlin/com/accucodeai/kash/interpreter/Interpreter.kt).

---

## P0 — Required for non-trivial scripts

These are reached for in almost every bash script. Without them, conformance tests trivially fail.

| Tool | Label | Why | Notes |
|---|---|---|---|
| `[[` | 🄱 | Bash conditional expression | Parser-level, not a command — distinct from `[`. Needed for `=~`, `&&`, `\|\|`, pattern match. |
| `export` | 🅂 ✓ | Env propagation | Already implicit in `ctx.env`. |
| `unset` | 🅂 ✓ | Variable/function removal | Pairs with `export`. |
| `set` | 🅂 ✓ | Shell options + positional params | `-e`, `-u`, `-o pipefail`, `--`. Touches interpreter state. |
| `shift` | 🅂 ✓ | Positional param rotation | Done. |
| `local` / `declare` / `typeset` | 🄱 ✓ | Function-scoped vars | Required for any non-toy function. |
| `eval` | 🅂 ✓ | Re-parse and execute a string | Hooks into the parser. |
| `exit` | 🅂 ✓ | Terminate with status | Interpreter signal — throws `ScriptAbortException` caught at top-level `run()`; EXIT trap still fires. |
| `return` | 🅂 ✓ | Function return | Interpreter signal. |
| `:` (colon) | 🅂 ✓ | No-op | Done. |
| `.` / `source` | 🅂 ✓ | Load a script | Full impl in `IntrinsicsSourceEval.kt` — PATH search, positional-param swap, streaming parse with bash-format diagnostics. |
| `exec` | 🅂 ✓ | Redirect persistently | Redirection-only form (`exec >FILE`, `exec 2>&1`). Process-replacement form returns 127 with diagnostic (kash has no fork/exec). |
| `trap` | 🅂 ✓ | Signal/EXIT handlers | `trap` / `trap -p [SIG]` / `trap '' SIG` / `trap - SIG` / `trap 'cmd' SIG`. EXIT fires from `run()`'s finally. INT/TERM/HUP cancel background-job coroutines; foreground async signals need a REPL signal source. |
| `times` | 🅂 ✓ | Print user/sys time | Wall-clock since shell start as "user" line, zeros elsewhere — kash has no per-process CPU accounting in commonMain. Format matches bash. |
| `getopts` | 🅁 ✓ | POSIX option parsing | Pure-string impl in `InterpreterIntrinsics.kt`. Mutates `OPTIND`/`OPTARG`/named var; supports clustered options and leading-`:` silent mode. |
| `alias` / `unalias` | 🅁 ✓ | Name aliasing | Implemented (`IntrinsicsAlias.kt`). |
| `hash` | 🅁 ✓ | Command lookup cache | Stub (no PATH executable cache yet). |
| `umask` | 🅁 ✓ | File-mode mask | Bare prints `0022`, `-S` symbolic, octal + symbolic set. Applied by `>FILE` redirections and the `touch`/`mkdir`/`tee`/`cp` tools via `ctx.umask`; `mkdir -m MODE` overrides the mask per POSIX. |
| `wait` | 🅁 ✓ | Wait for background jobs | Implemented in [`IntrinsicsJobControl.kt`](../tools/kash/shell/src/commonMain/kotlin/com/accucodeai/kash/interpreter/IntrinsicsJobControl.kt). Bare `wait` returns 0 after draining all jobs; `wait %N` / `wait $pid` waits for a specific job; unknown spec → "no such job". |

## P1 — Common text/file utilities

These are external in real bash but most kash users will expect them in-process. POSIX-required ones are 🅄; the rest are widespread *de-facto* utilities. Order roughly by how often scripts reach for them.

| Tool | Label | Notes |
|---|---|---|
| `grep` / `egrep` / `fgrep` | 🅄 ✓ | Linear-time regex via `shared/regex` (no ReDoS). POSIX BRE default (internal BRE→ERE preprocessor), `-E` ERE, `-F` fixed strings; `egrep`/`fgrep` register as separate specs that default to `-E`/`-F`. Flags: `-i`, `-v`, `-n`, `-l`, `-c`, `-q`, `-h`, `-H`, `-e`, `-f`, `-r`/`-R`, `-s`, `-x`, `--color[=WHEN]` (default `never`; honors `$GREP_COLORS` ms/mc/fn/ln/se), `--`, plus long forms (`--ignore-case`, `--line-regexp`, `--regexp=…`, `--file=…`, …). Streaming line-by-line; `-r` walks `ctx.fs`. Exit codes 0/1/2 per POSIX. **Full POSIX option parity.** Deferred (GNU/BSD extensions): `-w`, `-A`/`-B`/`-C` context, `-o`, `-L`, `--include`/`--exclude`, binary detection, `sl`/`cx`/`bn` GREP_COLORS keys — `:tools:posix:grep`. |
| `sed` | 🅄 ✓ | POSIX-default BRE (`-E`/`-r` opts into ERE). Substitution flags `g`/`p`/`i`/Nth/`w FILE`. Commands `d`/`D`/`p`/`P`/`n`/`N`/`=`/`q`/`Q`/`a`/`i`/`c`/`y`/`r`/`w`/`l`/`h`/`H`/`g`/`G`/`x`; labels with `b`/`t`/`T`; `{ }` blocks. Addresses incl. ranges `a1,a2`, `a,+N`, `a,~M`, `0,/re/`, step `N~M`; `!` negation. CLI `-n`/`-e`/`-f`/`-i[SUFFIX]` (backup-suffix supported)/`-s`/`-z`/`-E`. Remaining gaps: `s///e` (exec), regex-start re-entry inside its own range — `:tools:posix:sed`. |
| `awk` | 🅄 ✓ | Mostly POSIX. Field-splitting (`$0`/`$1`/…/`$NF`), `BEGIN`/`END`, pattern-action pairs incl. regex `/…/`, arithmetic + string built-ins, associative arrays, `print`/`printf`, `getline` from stdin / files / commands, `print > / >> file`, `print \| cmd`, `cmd \| getline`, `system()`, multi-file inputs with `FNR`/`FILENAME` + `nextfile`, `ENVIRON`, `-f` program files. Stream engine is suspending end-to-end. Remaining gaps in `tools/posix/awk/STATUS.md` — `:tools:posix:awk`. |
| `tr` | 🅄 ✓ | Char translate/squeeze/delete — `:tools:posix:tr`. |
| `cut` | 🅄 ✓ | `-d`, `-f`, `-c` — `:tools:posix:cut`. |
| `sort` | 🅄 ✓ | `-n`, `-r`, `-u`, `-k F[.C][,F[.C]]`, `-t`, bundled short flags. In-memory v1; per-key flag modifiers and `-c/-m/-o/-s` deferred — `:tools:posix:sort`. |
| `uniq` | 🅄 ✓ | `-c`, `-d`, `-u` — `:tools:posix:uniq`. |
| `head` / `tail` | 🅄 ✓ | `-n`, `-c` (no `-f`) — `:tools:posix:head`, `:tools:posix:tail`. |
| `wc` | 🅄 ✓ | `-l`, `-w`, `-c` — `:tools:posix:wc`. |
| `tee` | 🅄 ✓ | Stdout + file fan-out; streams stdin in 8 KiB chunks to all sinks. Flags `-a` (append), `-i` (accepted, no-op — kash has no signals), `--`. Open errors on individual files leave the rest going; broken stdout pipe does not stop file writes — `:tools:posix:tee`. |
| `xargs` | 🅄 ✓ | `-n`, `-I REPLSTR`, `-0`, `-r`. Invokes utility via `ctx.utilityRunner` (execvp-style — no shell). Exit codes 0/123/126/127 per POSIX — `:tools:posix:xargs`. |
| `find` | 🅄 ✓ | `-name` (basename glob), `-type f/d`, `-maxdepth`/`-mindepth`, `-print`/`-print0`, `-exec UTIL {} ;`, `-exec UTIL {} +`, `!`/`-not`. Operators are implicit AND. Use `-exec sh -c '…' \;` for full shell — `:tools:posix:find`. |
| `ls` | 🅄 ✓ | `-1`/`-a`/`-A`/`-d`/`-F`/`-h`/`-l`/`-R`/`-r`/`-S`/`-t`, `--color[=WHEN]` (default `never`; honors `$LS_COLORS` type keys + `*.ext` globs). `-a` synthesizes `.`/`..` since the VFS doesn't return them from `listStat`. POSIX-format mode strings, KB block totals, `MMM DD HH:MM`/`MMM DD  YYYY` mtime split. Deferred: `LS_COLORS` permission combos (`tw`/`ow`/`st`/`su`/`sg`), column-wrapped output, `--time-style` — `:tools:posix:ls`. |
| `mkdir` | 🅄 ✓ | `-p` (create parents, ignore existing), `-m MODE` applied via `chmod` after create (octal; symbolic accepted but not applied) — `:tools:posix:mkdir`. |
| `rmdir` | 🅄 ✓ | `-p` (also remove empty parents, stops at first non-empty), refuses non-empty dirs — `:tools:posix:rmdir`. |
| `rm` | 🅄 ✓ | `-r`/`-R`, `-f` (ignore missing, never prompt), `-i` (accepted; no TTY model yet so behaves as default in non-interactive contexts), `-d` (empty dir), refuses `/` without `--no-preserve-root` — `:tools:posix:rm`. |
| `touch` | 🅄 ✓ | Creates empty file if missing; `-c` (no-create), `-a`/`-m` (mtime; atime not modeled), `-r REF` (copy ref's mtime), `-d STRING` (ISO-8601 / `@<seconds>`), `-t [[CC]YY]MMDDhhmm[.SS]` (UTC). Read-only mounts reject with "Read-only file system" — `:tools:posix:touch`. |
| `cp` / `mv` / `ln` / `chmod` | 🅄 ✓ | All shipped — `:tools:posix:{cp,mv,ln,chmod}`. |
| `stat` | 🅄 ✓ | `-c FORMAT` with `%n`/`%s`/`%F` real; `%a`/`%U`/`%G`/`%Y` stubbed as `?` until `FileSystem` gains a `metadata(path)` API — `:tools:ext:stat`. |
| `basename` | 🅄 ✓ | POSIX `basename STRING [SUFFIX]`, GNU `-a`/`-s SUFFIX`/`--suffix=`/`-z`. Pure string ops — `:tools:posix:basename`. |
| `dirname` | 🅄 ✓ | POSIX `dirname STRING...` (multi-operand accepted), GNU `-z`. Pure string ops — `:tools:posix:dirname`. |
| `env` | 🅄 ✓ | Print env, `-i`/`-u NAME`/`-0`, and run UTIL with modified env via `ctx.utilityRunner` — `:tools:posix:env`. |
| `which` | non-POSIX ✓ | Inspects the command registry (POSIX prefers `command -v`) — `:tools:ext:which`. |
| `expr` | 🅄 ✓ | Arith + string ops. Mostly superseded by `$((...))` and `[[` — `:tools:posix:expr`. |
| `seq` | non-POSIX ✓ | `seq N`, `seq A B`, `seq A S B` with float step, `-s SEP`, `-w` — `:tools:ext:seq`. |
| `sleep` | 🅄 ✓ | Coroutine-aware (`kotlinx.coroutines.delay`); fractional seconds, `s`/`m`/`h`/`d` suffixes, multi-operand summation — `:tools:posix:sleep`. |
| `date` | 🅄 ✓ | Full POSIX format set (`%a %A %b %B %c %C %d %D %e %h %H %I %j %m %M %n %p %r %S %t %T %u %U %V %w %W %x %X %y %Y %Z %%`) plus GNU `%F %s %N %z`. Options: `-u`, `-d STRING` (ISO 8601 + `@epoch`), `-I[FMT]`, `-R`, `--help`, `--version`. Reads `ctx.process.machine.clock` so tests pin time. Non-UTC TZ falls back to UTC (full TZ-rules parsing deferred) — `:tools:posix:date`. |
| `nl` | 🅄 ✓ | `-b`/`-h`/`-f` (`a`/`t`/`n`/`pBRE`), `-d`/`-i`/`-l`/`-n`/`-p`/`-s`/`-v`/`-w`, logical-page delimiters — `:tools:posix:nl`. |
| `paste` | 🅄 ✓ | `-d LIST` (cycled), `-s` serial mode, multi-`-` stdin — `:tools:posix:paste`. |
| `fold` | 🅄 ✓ | `-w WIDTH` (incl. legacy `-N`), `-b` byte mode, `-s` word-wrap — `:tools:posix:fold`. |
| `comm` | 🅄 ✓ | `-1`/`-2`/`-3` column suppression — `:tools:posix:comm`. |
| `tac` | non-POSIX ✓ | `-b`/`-r`/`-s STR` — `:tools:ext:tac`. |
| `rev` | non-POSIX ✓ | Codepoint-safe reverse (UTF-8 incl. surrogate pairs) — `:tools:ext:rev`. |
| `column` | non-POSIX ✓ | `-t`/`-s`/`-o`/`-N`/`-x`, table + grid mode, honors `$COLUMNS` — `:tools:ext:column`. |
| `join` | 🅄 ✓ | Relational join on a key field — `:tools:posix:join`. |

## P1.5 — Build / workflow tools

| Tool | Label | Notes |
|---|---|---|
| `make` | 🅄 ✓ | POSIX 2024 macros (`=`/`:=`/`::=`/`:::=`/`?=`/`+=`/`!=`), nested expansion, `$(VAR:suf=rep)` and `%`-pattern substitution; auto-vars `$@`/`$<`/`$^`/`$+`/`$?`/`$*` plus `D`/`F` variants; explicit + pattern + suffix-as-pattern rules; recipe prefixes `@`/`-`/`+`; special targets `.PHONY`/`.SILENT`/`.IGNORE`/`.PRECIOUS` real and `.SUFFIXES`/`.DEFAULT`/`.POSIX`/`.NOTPARALLEL`/`.WAIT`/`.SECONDARY`/`.INTERMEDIATE` accepted; directives `include`/`-include`/`sinclude`, full `ifeq`/`ifneq`/`ifdef`/`ifndef`/`else`/`endif` nesting, `define`/`endef`, `export`/`unexport`/`override`; the GNU function set `subst`/`patsubst`/`filter`/`strip`/`dir`/`notdir`/`basename`/`suffix`/`addprefix`/`addsuffix`/`word*`/`findstring`/`if`/`or`/`and`/`origin`/`flavor`; CLI `-f`/`-C`/`-k`/`-S`/`-n`/`-s`/`-i`/`-e`/`-r`/`-R`/`-p`/`-q`/`-t`/`-j` (serial v1 — `-j N>1` warns and falls back); default file search `GNUmakefile`/`makefile`/`Makefile`. Recipes execute through `ctx.shellRunner` — full kash semantics, no host shell-out. ANTLR-based parser. Deferred: real `-j` parallelism, double-colon rules, `vpath`, `$(eval/call/foreach)`, secondary expansion, archive `lib(member)`, target-specific assignments — `:tools:posix:make`. |

## P2 — Data / format tools

`jq` is already in. Round out the data layer.

| Tool | Notes |
|---|---|
| `yq` | YAML — most natural fit alongside jq. Not yet implemented. |
| `base64` ✓ | Encode/decode, `-d`, `-w COLS` (default 76), `-i` ignore-garbage, binary round-trip via `java.util.Base64` — `:tools:ext:base64`. |
| `shasum` ✓ | SHA-1/224/256/384/512 via `MessageDigest`, `-a`, `-b`, `-c` (checksum verify), streamed in 8 KiB chunks. JVM-only today — `:tools:ext:shasum`. |
| `md5sum` / `sha1sum` / `sha224sum` / `sha256sum` / `sha384sum` / `sha512sum` ✓ | Per-algorithm wrappers around the shasum engine; GNU CLI (`-b`/`-t`/`-c`/`--tag`). MD5 added to the JVM digest map. Co-located in `:tools:ext:shasum`. |
| `od` ✓ / `xxd` / `hexdump` | Binary inspection. `od` shipped (`:tools:posix:od`); `xxd`/`hexdump` not yet. |
| `tar` ✓ / `cpio` ✓ / `pax` ✓ | POSIX archive utilities — `:tools:ext:{tar,cpio,pax}`. |
| `gzip` ✓ / `bzip2` ✓ / `xz` ✓ / `zstd` ✓ / `lz4` ✓ / `zip` ✓ | Compression — `:tools:ext:{gzip,bzip2,xz,zstd,lz4,zip}`. |
| `uuidgen` ✓ | v4 default (`-r`), real v1 (`-t`) with timestamp + node + clock-seq, `-n COUNT` — `:tools:ext:uuidgen`. |

## P3 — Network / system (stub or skip)

These touch the host in ways a hermetic shell shouldn't pretend to support. Decide per-tool whether to implement, stub, or refuse.

- `curl` ✓ — shipped (`:tools:ext:curl`). `wget` — useful but a security surface. Probably opt-in.
- `ssh`, `scp`, `rsync`, `nc` — likely out of scope.
- `kill` 🅄 ✓ (intrinsic), `ps` ✓ / `pgrep` ✓ / `pkill` ✓ — shipped (`:tools:posix:{ps,pgrep,pkill}`). `top` — depends on a process model kash may not fully have.
- `bg` / `fg` / `jobs` 🅁 / `wait` 🅁 — all shipped (`IntrinsicsJobControl.kt`). `fc` (command-history editor) deferred until we model interactive history.
- `df` ✓ / `du` ✓ — shipped (`:tools:posix:{df,du}`). `free`, `uptime` — host-level, mostly out of scope.
- `nice` ✓ / `nohup` ✓ — shipped (`:tools:posix:{nice,nohup}`); both are mostly no-ops in-process but accept the POSIX CLI surface for portability.
- `ulimit` 🄱 ✓ — bash-extension intrinsic (`IntrinsicsUlimit.kt`). Full `[-SHa] [-cdfklmnpstuv [LIMIT]]` CLI, per-process limits stored on `KashProcess.rlimits`. Real enforcement on `NPROC` (`-u`, gated at the fork seam in `DefaultKashProcess.fork`) and `NOFILE` (`-n`, gated in the shared fd allocator — honored by `{NAME}>file`, coproc pipes, and `<(...)`/`>(...)` procsub; procsub fds auto-reclaimed per enclosing command so tight loops don't leak under a low `NOFILE`). Other letters parse and store but have no consumer yet — scaffolding for future enforcement.
- `newgrp` 🅁 — POSIX regular builtin gated on host privileges; stub.

## P4 — Won't implement

Package managers and compilers stay out of scope — scripts that invoke
these can shell out to the host if kash exposes a passthrough.

Interactive editors were initially in this bucket but ended up worth
shipping for the REPL: `nano` ✓, `vi` ✓, `ed` ✓, `less` ✓ — all under
`:tools:kash:{nano,vi,ed,less}`. `more` still skipped; `less` covers it.

---

## Suggested ordering

1. ✓ **POSIX special-builtins** all shipped: `:`, `.`/`source`, `break`, `continue`, `eval`, `exec`, `exit`, `export`, `readonly`, `return`, `set`, `shift`, `times`, `trap`, `unset`. The remaining 🅂 gap is foreground async signal delivery (REPL signal source) — script-level EXIT works.
2. ✓ **POSIX regular builtins** (🅁) shipped: `getopts`, `alias`/`unalias`, `hash` (stub), `umask` (full FS plumbing), `bg`/`fg`/`jobs`/`wait` (via [`IntrinsicsJobControl.kt`](../tools/kash/shell/src/commonMain/kotlin/com/accucodeai/kash/interpreter/IntrinsicsJobControl.kt)). `fc` (command-history editor) deferred until we model interactive history.
3. **P1 small-wins** (🅄 streaming utilities) — `wc`, `tee`, `tr`, `cut`, `head`, `tail`, `seq`, `basename`, `dirname`, `sleep`, `mkdir`/`rm`/`touch`. Each is an afternoon; all benefit from broken-pipe semantics already wired in.
4. **Heavyweights**: `grep`, `sed`, `find`, `sort`, `xargs`. Each is its own sub-project; budget accordingly. Reuse the jq regex engine for `grep` and `sed`.
5. **`awk`** — mostly POSIX (file/stdin/cmd `getline`, `system()`, multi-file `FNR`/`FILENAME`/`nextfile`, `ENVIRON`, `-f` scripts); remaining work tracked in `tools/posix/awk/STATUS.md`.
6. **P2 as demanded**, **P3 deliberately**, **P4 never**.

## Coverage check

Cross-reference against `src/jvmTest/resources/bash-tests-xfail.txt` and `modernish-xfail.txt` — the missing-tool failures there are the empirical priority signal. If a P2/P3 tool blocks many xfails, promote it.
