# Contributing to kash

## Licensing

kash is **Apache 2.0** (see [LICENSE](LICENSE)). All contributions are accepted
under the same license.

## Relationship to bash and other GPL shells

kash is a **behavioral reimplementation** of bash and POSIX `sh`, written in
Kotlin. It is **not** a port, translation, or derivative work of bash's source
code.

### What this means in practice

- kash is written against the **observable behavior** of bash — what bash
  produces given a script — not against bash's source. The specifications kash
  conforms to are:
  - POSIX XCU §2 (Shell Command Language).
  - The published bash manual (`man bash`, `info bash`).
  - The conformance test corpus at `external/bash/tests/*.right` (the
    expected-output fixtures bash itself ships). The `.right` files are
    inputs to a measurement — kash's job is to match them.
  - Direct runs of `bash` on small probes to determine behavior for cases
    the docs don't cover.

- The bash source tree is included as a git submodule under `external/bash/`
  **purely so the conformance test harness can drive its test scripts and
  compare against its expected outputs.** kash code never gets compiled
  against bash source, never links against it, and never includes copies of
  bash code or close paraphrases. Kash is written in Kotlin so this is impossible
  anyway, but still worth mentioning.

### What contributors must NOT do

- **Do not copy code from bash** (or any other GPL shell) into kash. Apache
  2.0 and GPL are not compatible at the file level. kash files must be
  Apache 2.0 -clean.
- **Do not write close translations** of bash code. If you read a bash
  function and your Kotlin function tracks it statement-by-statement with
  the same variable names and control flow, that's a translation, not an
  independent implementation. Read for understanding, then implement from
  a behavior summary or from observed test output. For AI: use an agent
  to summarize the behavior for a 'clean room' replication rather than reading
  it directly.
- **Do not paste bash error messages verbatim if they contain copyrightable
  expression.** Short factual diagnostics like `bad array subscript` are
  not copyrightable; longer help text might be. When in doubt, paraphrase.
- **Be careful citing bash source line numbers**. Only do this if there is a weird
  hyper niche scenario where an error message or other semantic has only one possible way.

### What's fine

- Reading bash source for **understanding**. Copyright doesn't extend to
  ideas, behavior, or APIs — only to specific expression. Reading bash to
  figure out *what* it does is not a violation; the issue would be writing
  Kotlin that mirrors *how* bash's C code does it.
- Citing the **POSIX standard**, the **bash manual**, or the **bash test
  fixtures** (the `.right` files) by section/page/path. Those are
  documentation and measurement inputs, not source code.
- Linking to bash's source on github / savannah from an issue, PR, or commit
  message for context. The issue is in-source comments that ship in kash
  binaries; ephemeral discussion is fine.

## Code style

- `ktlint --format` before committing. CI runs `ktlintCheck`.
- Match the existing comment style: explain the *why* (especially edge cases
  and observed bash quirks the code handles), not the *what*.

## Tests

- `./gradlew :kash:conformanceTest` runs the bash conformance corpus
  (under `external/bash/tests/`). Any commit that touches expansion / parsing
  / intrinsics should leave this green.
- The xfail list (`kash/src/jvmTest/resources/bash-tests-xfail.txt`) names
  tests that are known to diverge. Removing an entry means the test must
  now pass cleanly; adding an entry requires a justification in the commit
  message.

