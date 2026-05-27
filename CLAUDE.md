# kash

## Encoding: UTF-8 only

kash is UTF-8 internally, not byte-oriented. We target JVM 25, which is
UTF-8 by default — no `-Dfile.encoding=UTF-8` plumbing required. All shell
values flow through Kotlin `String` (UTF-16 in memory, written out as UTF-8
by `writeUtf8`).

The only divergence this forces is on **high-bit codepoints**: bash in
C-locale emits `\xDE` (`$'\xDE'`) as the literal byte 0xDE; kash treats
`\xDE` as Unicode codepoint U+00DE which UTF-8-encodes to `0xC3 0x9E` (two
bytes). The same goes for `$'\x{HH...}'`. That single mismatch is by
design; the `intl` conformance test is the one place it shows up and is
xfailed for that reason.

**Everything else is fair game** — including ASCII-range `$'...'` ANSI-C
escapes (`\01`, `\07`, `\'`, `\"`, `\a`, etc.), heredoc-body literals,
`od -c` rendering, single-quote-inside-default-value parsing, and so on.
If a conformance test fails on one of those, don't reach for a normalize
rule — fix the underlying behavior. The "skip byte-vs-codepoint" carve-out
applies ONLY to non-ASCII bytes (>= 0x80).

## Conformance tests

The bash test corpus lives in `external/bash/tests/` (git submodule). Each
test is a `<name>.tests` script paired with a `<name>.right` expected-output
fixture. The harness is `BashTestPairRunner` (under `:kash`'s jvmTest), and
its xfail list is `kash/src/jvmTest/resources/bash-tests-xfail.txt`.

Run:

```
./gradlew :kash:conformanceTest
```

The runner prints one line per test prefixed `CONFORM PASS|NEWPASS|XFAIL|FAIL`.
A test listed in the xfail file that *starts* matching fails LOUDLY with
`UNEXPECTED PASS` — that's the signal to update the xfail list.

To scout the next quickest-win xfail before touching anything, run the
diagnostic helper:

```
./gradlew :kash:conformanceTest --tests "*XfailDiagnostics*" -Dkash.diag.focus=<name> -i --rerun-tasks
```

It prints a Levenshtein-line-distance table over every xfail (`XFAIL-DIFF
<N>  first-diff-line=<L>  <name>`) so you can pick a small-diff target,
and with `kash.diag.focus=<name>` dumps the full expected/actual diff for
that one test inline.

To make an xfail test pass:

1. Remove its name from `bash-tests-xfail.txt`.
2. Run `./gradlew :kash:conformanceTest --tests "*BashTestPairRunner*" -i`.
3. The failure shows expected vs actual; diff drives the fix.
4. Re-run until PASS.

To see the AST a script parses to (for debugging), call
`Parser(script).parseScript()` and `Json { prettyPrint = true }.encodeToString(ast)`.

Sibling corpora wired up the same way: `external/modernish/lib/modernish/tst`
(ModernishConformanceRunner) and `external/jq/tests` (JqConformanceRunner).
The `conformanceTest` task runs all three.

Testing Hint: Run `cleanTest` instead of `--rerun-tasks`. `--rerun-tasks` reruns all tasks,
including build tasks which can be slow. Only do `--rerun-tasks` if you encounter `ClassNotFound` exceptions.

## GPL3

Bash is GPL3 (and other similar GNU licenses) Kash is Apache. Read `CONTRIBUTING.md`
