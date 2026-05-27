# Conformance workflow

How to add a bash feature to Kash, prove it works against upstream's own tests, and shrink the xfail list.

## Layout

```
external/bash/tests/           # GPL-3 submodule. ~83 *.tests / *.right pairs.
external/modernish/...         # Submodule. Conformance corpus, harness TBD.
src/jvmTest/kotlin/.../conformance/
  ScriptPairRunner.kt          # Discovery + diff engine.
  BashTestPairRunner.kt        # @TestFactory over bash pairs.
  ModernishConformanceRunner.kt
src/jvmTest/resources/
  bash-tests-xfail.txt         # Names of pairs Kash is allowed to fail.
  modernish-xfail.txt
```

Gradle tasks:

- `./gradlew jvmTest` — handwritten Kotlin tests only. Fast. Tag `conformance` is excluded.
- `./gradlew conformanceTest` — runs the upstream corpus. Slow (~2 min). xfail-tolerant.

## One-time setup

```sh
git submodule update --init --recursive
```

If you skip this, `checkSubmodules` fails with the right instructions.

## The loop

### 1. Pick a target

Open `src/jvmTest/resources/bash-tests-xfail.txt`. Each line names a `<basename>.tests` file under `external/bash/tests/`. Pick one — start with something narrow (`alias`, `tilde`, `appendop`, `set-e`) rather than `extglob`/`nquote`.

Read the upstream pair to see what the test actually exercises:

```sh
less external/bash/tests/alias.tests
less external/bash/tests/alias.right
```

`*.sub` helper files in the same directory are sourced by some tests — `ScriptPairRunner` mirrors the whole dir into `InMemoryFs` before each run, so `source foo.sub` resolves.

### 2. See the current diff

Run just that one factory entry to see what Kash actually outputs:

```sh
./gradlew conformanceTest --tests "*BashTestPairRunner*bash/alias*" --info
```

Or temporarily comment the basename out of `bash-tests-xfail.txt` and run the suite — the failure message includes a unified diff with expected vs actual stdout+stderr (truncated to 2 KB; bump the limit in `ScriptPairRunner.buildDiff` if you need more).

### 3. Implement / fix

Edit `src/commonMain/kotlin/com/accucodeai/kash/` — usually `interpreter/Interpreter.kt`, `parser/Parser.kt`, `parser/Lexer.kt`, or `commands/Builtins.kt`. Add focused handwritten tests under `src/commonTest/kotlin/` for the specific behavior you're adding — those tests are your fast feedback loop, the conformance suite is the *acceptance* check.

### 4. Verify locally

```sh
./gradlew jvmTest             # handwritten tests
./gradlew conformanceTest     # corpus
```

When `conformanceTest` says the test you targeted now passes, the runner will fail loudly because the basename is still in xfail:

```
UNEXPECTED PASS: alias is listed as xfail but produced matching output.
Remove it from the xfail file.
```

That's the signal.

### 5. Remove from xfail

Delete the basename's line from `src/jvmTest/resources/bash-tests-xfail.txt`. Re-run `conformanceTest`. It should be green.

Commit the implementation change and the xfail removal together — that way the xfail file is always an accurate ledger of "what we don't yet do."

### 6. Watch for collateral damage

A change in the interpreter often un-breaks tests you weren't targeting. If `conformanceTest` complains about *other* `UNEXPECTED PASS` lines, remove those entries too in the same commit. Free wins.

Conversely, if a previously-passing pair regresses, the suite fails normally with a diff — fix it before merging. Do not "fix" regressions by adding the name back to xfail.

## Adding a new pair

If upstream bash adds a new `<name>.tests`/`<name>.right` pair (after a submodule bump), `BashTestPairRunner` picks it up automatically. If Kash fails it on first sight, add the basename to `bash-tests-xfail.txt` with a short `#` comment explaining what's missing — same as any other xfail.

## Modernish

Currently every `.t` file is reported as skipped. The `.t` format isn't a standalone script; it expects the modernish harness (`run.sh` + `bin/modernish`) loaded first. When Kash is far enough along to host the harness, replace the `assumeTrue(false, …)` body in `ModernishConformanceRunner.modernishTests` with the same diff approach used for bash — likely diffing against a captured `run.sh` baseline.

## Bumping submodules

```sh
cd external/bash && git fetch && git checkout <new-tag> && cd ../..
git add external/bash
./gradlew conformanceTest      # see what changed
```

Treat any new failures the same way: either fix Kash, or add the basename to xfail with a note.

## Anti-patterns

- **Don't transpose bash tests into Kotlin.** That's exactly what the runner exists to avoid. Handwritten tests should target *units* of Kash (the parser, an expansion rule), not feature behavior that the upstream corpus already covers.
- **Don't silence a regression by adding to xfail.** xfail is for things we haven't built yet. Regressions are bugs.
- **Don't edit `external/`.** It's a submodule — local edits get lost on the next `submodule update`. If a test is broken-by-design (and that does happen in bash's tests/), skip it in xfail with a comment, don't patch upstream.
