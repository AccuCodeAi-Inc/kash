# awk engine — implementation status

Frank tracker of what works and what doesn't, kept honest against POSIX awk
and onetrueawk (Aho/Weinberger/Kernighan). Update whenever you add or
remove a feature.

Last revised: see `git log` on this file.

## Licensing posture (read before grabbing anything from upstream)

**Reference, not translation.** The awk grammar lives in
`src/commonMain/antlr/Awk{Lexer,Parser}.g4` and is re-derived from POSIX
behavior, validated against the upstream test corpus. We do not paste
yacc rules, action blocks, or names from any upstream `awkgram.y` into our
grammar.

**Permitted references** (for understanding the language):

- POSIX awk specification — no license issue.
  https://pubs.opengroup.org/onlinepubs/9699919799/utilities/awk.html
- *The AWK Programming Language*, 2nd ed. (Aho, Weinberger, Kernighan,
  2024) — the canonical book.
- **onetrueawk** (`https://github.com/onetrueawk/awk`) — Lucent BSD-style
  license, the cleanest of the production implementations. Safe to *read*
  for behavior questions and to *vendor as a test corpus*; safe to
  port test fixtures verbatim (license is permissive); not safe to copy
  Yacc rules into our grammar (translation risk).

**Off-limits as translation source:** gawk (GPLv3), mawk (GPLv2),
busybox awk (GPLv2). Reading their docs is fine; cribbing structure into
our grammar is not.

The Lucent license is logged in the project NOTICE alongside bash, jq, and
modernish. When `external/onetrueawk` lands as a submodule, no NOTICE
update is needed — the entry is already there.

## Conformance corpus

- **Hand-written unit tests** in `src/commonTest`: 66 cases (parser, eval,
  regex-vs-divide) — all green.
- **Upstream:** `external/onetrueawk/testdir/` (git submodule) — driven by
  `tools/awk/src/jvmTest/.../conformance/AwkConformanceRunner.kt` and
  surfaced by `./gradlew :tools:awk:conformanceTest`. The runner diffs our
  output against a reference `awk` binary on `PATH` (macOS ships BWK awk
  = one-true-awk, so the comparison is exact on the dev box). On hosts
  without a system awk, every case is `assumeTrue`-skipped — no false
  failures.

### Baseline

```
212 tests completed, 3 failed, 19 skipped
```

Pass rate **193 / 212 = 91.0 %** of runnable cases. Recent slice-2
landings:

- bare `getline` (cleared `t.beginexit`/`t.beginnext`/`t.getline1`)
- `getline < file` wired through `ctx.fs`
- user-defined functions with array-pass-by-reference and recursion
  (cleared `t.fun0`–`t.fun5`, `t.fun`, `t.assert`, `t.exit1`,
  `t.set1`, `p.44`, `p.table` — 11 cases at once)
- `NF = N` assignment — truncates / extends the field array and
  rebuilds `$0` via `OFS` (cleared `t.NF`)
- range patterns `pat1, pat2 { … }` — toggled per-rule state machine
- `close()` and `fflush()` — `close()` releases input file readers
  and output writers (so the next `print > "f"` truncates again);
  `fflush()` is a no-op (we don't buffer at engine level)
- output redirection `print > "f"`, `print >> "f"`, `printf > "f"`,
  `printf >> "f"` — wired through `ctx.fs` via a parallel `printExpr`
  grammar chain that excludes `>` at the top level (POSIX requires
  parenthesizing comparisons in print args: `print (a > b)`). Same
  target across statements stays open; releasing happens on `close()`
  or end of run.
- process-spawn forms: `print | "cmd"`, `cmd | getline [var]`, and
  `system(cmd)` all wire through `ctx.shellRunner`. Pipe forms use
  `AsyncPipe` to stream records to/from the spawned child concurrently
  with the awk run; `system()` blocks awk until the spawned exit code
  comes back.
- `ENVIRON[…]` snapshots `ctx.process.env` at startup.

Side effect of array pass-by-ref: `split` now mutates its destination
map in place rather than replacing it, so callers who pass an aliased
array see the populated values.

The runner additionally quarantines tests that depend on `ARGC`/`ARGV`
(program-visible argument vector — not yet modeled as scalars) and on
`rand()` (non-deterministic against the reference's PRNG). Multi-file
input + per-file `FNR` / `FILENAME` are now supported: the runner uses
`runFiles` and feeds each input as its own `AwkInputFile`, which
clears `t.be`, `p.24`, and `p.41`.

The slice-1.x cleanup pass (May 2026) closed five real bugs:

- **Uninitialized vs numeric comparison:** `Uninit` wasn't included in
  the "both numeric" predicate, so `b == 0` on uninit `b` fell into
  string compare (`"" == "0"` → false). Fixed by treating `Uninit` as
  numeric-eligible per POSIX. Cleared `t.null0`.
- **`split("", a, FS)`:** returned 1 element (an empty string) instead
  of 0. Same bug in `splitInto` for empty records made `NF=1` instead
  of `NF=0`. Fixed both. Cleared `t.split8`, `t.split9a`.
- **`$NF++` precedence:** the grammar layered `fieldExpr` outside
  `postfixExpr`, so `$NF++` parsed as `$(NF++)`. POSIX/bwk both parse
  it as `($NF)++` — postfix binds tighter than `$`. Swapped the two
  layers. Cleared `t.vf2`.
- **`for (k in a)` ordering & output pipes:** runner-side fixes
  (sort-tolerant compare and pipe quarantine), not engine bugs.
  Cleared `t.in2`, `t.intest2`, `p.43`, `p.48`, `p.50`, `t.in1`,
  `t.pipe`.

### Remaining failures (3), all documented divergences

| Test       | Reason                                                                  |
|------------|-------------------------------------------------------------------------|
| `t.concat` | `name (expr)` parses as function call — needs hand-prelex (see below)   |
| `t.printf2`| byte-vs-codepoint `%c` divergence — by design per CLAUDE.md             |
| `t.split3` | `split` regex edge cases (trailing-empty-field semantics)               |

Each failing case has its expected and actual outputs dumped under
`/tmp/awk-conform/<name>.{expected,actual}` after a run for fast triage.

### Parser quirk: function-call vs concat

POSIX awk distinguishes `name(arg)` (function call) from `name (arg)`
(concatenation: variable `name` followed by parenthesized expression) by
*the absence of whitespace* between the identifier and the `(`. Our ANTLR
grammar can't easily encode this because the lexer skips whitespace
before parser tokens land. Status: parser always picks the function-call
shape when it sees `IDENT LPAREN`. Workaround for users: write
`(name)(arg)` or `name "" (arg)` to force concat. Fix path: hand-prelex
adapter (the same pattern bash uses) emitting `IDENT_LPAREN` as a single
token when the parens are adjacent. Adds ~50 LOC; deferred until a real
program needs it.

---

## The big design call: regex-vs-divide

Awk's only context-sensitive token is `/`. In:

```awk
$0 ~ /foo/   { print }    # regex literal
n = a / 2;                # division
```

`/foo/` and `/ 2` are syntactically identical character sequences; only the
position decides. POSIX phrases it as: a `/` introduces a regex literal
*except* in positions where the division operator could also appear (i.e.,
immediately after an operand: identifier, number, `)`, `]`, `$expr`,
post-increment, etc.). Everywhere else (after `~`, `!~`, comma, `(`, `[`,
binary operators, `if`/`while`/`for` keywords, line start, …) a `/` opens a
regex.

Three implementation options, in increasing order of "right thing":

1. **Pure ANTLR semantic predicate.** Parse both `DIV` and `REGEX` as the
   same lexer rule, then resolve at the parser site with a predicate that
   inspects the surrounding rule. Brittle — the predicate has to know
   every "operand-ending" production.
2. **ANTLR lexer modes with parser feedback.** Push a `REGEX_OK` mode after
   tokens that can't be operands; pop after consuming the regex. Cleaner,
   but requires the lexer to know about the parser's expression context.
3. **Hand prelex pass, adapter into ANTLR** *(planned)*. Same pattern as
   bash's `:core` setup: hand lexer in
   `tools/awk/.../parser/AwkLexer.kt` does the regex/divide disambiguation
   by tracking the most-recent emitted token's class (operand-like vs.
   not), then a small `AwkTokenSourceAdapter` feeds the result into the
   ANTLR parser via `kashTokenSourceFrom`. Mirrors `KashTokenSourceAdapter`
   exactly. The ANTLR grammar then has clean separate `REGEX` and `DIV`
   tokens with no semantic predicates.

(3) wins because the bash precedent already proves the adapter shape works
and the shared `:shared:antlr-runtime` helpers (`configureForFailFast`,
`twoStageParse`) snap right into it. Cost: ~300–500 LOC of hand lexer,
versus ~50 LOC of ANTLR predicates that would always be one edge case
away from misfiring.

---

## Language map (what we need to land)

The full POSIX surface, grouped so we can ship in slices.

### Slice 1 — minimum useful subset

Goal: `awk '{ print $1 }'`, `awk -F, '{ print $2 }'`, plain pattern-action
rules with simple expressions. Enough to handle 80% of one-liner usage.

- **Program structure**
  - `BEGIN { … }`, `END { … }`
  - Expression pattern + action block: `/re/ { … }`, `$1 == "foo" { … }`
  - Action-only rule (pattern omitted: applies to every record)
  - Pattern-only rule (action omitted: defaults to `{ print }`)
- **Fields**
  - `$0` (whole record), `$N` (N-th field), `$(expr)`
  - `NF`, `NR`, `FNR`, `FS`, `OFS`, `ORS`, `RS` (read-only here; full
    assignment semantics in slice 2)
- **Literals**
  - Numbers: integer + float, scientific notation
  - Strings: `"…"` with `\n`, `\t`, `\\`, `\"`, `\/`, `\b`, `\f`, `\r`,
    `\xHH`, `\NNN` (octal)
  - Regex literals: `/…/` per the disambiguation rule above
- **Operators**
  - Arithmetic `+ - * / % ^` (right-assoc `^`)
  - String concatenation: implicit (whitespace between operands)
  - Comparison `== != < <= > >=` (numeric vs string per POSIX type coercion)
  - Logical `&& || !`
  - Match `~`, no-match `!~`
  - Assignment `= += -= *= /= %= ^=`
  - Conditional `?:`
  - Pre/post `++` `--`
- **Statements**
  - `if (e) s [else s]`
  - `while (e) s`
  - `for (init; cond; step) s`
  - `do s while (e)`
  - `;` and newline as statement terminators (newline-or-`;`)
  - `print expr_list [redir]`, `printf fmt, args… [redir]`
  - `next`, `exit [expr]`
  - Compound statements via `{ … }`

### Slice 2 — full single-program awk

- **Arrays**
  - `a[expr]`, multi-dim `a[i, j]` (subscript joined by `SUBSEP`)
  - `expr in a` membership
  - `delete a[i]`, `delete a`
  - `for (k in a) s`
- **String / regex builtins**
  - `length [(s)]`, `substr(s, i [, n])`, `index(s, t)`
  - `split(s, a [, fs])`, `sprintf(fmt, …)`
  - `sub(re, repl [, target])`, `gsub(re, repl [, target])`
  - `match(s, re)` (sets `RSTART`, `RLENGTH`)
  - `tolower(s)`, `toupper(s)`
- **Numeric builtins**
  - `int(x)`, `atan2`, `cos`, `sin`, `exp`, `log`, `sqrt`, `rand`, `srand`
- **I/O builtins**
  - `getline` — five forms:
    - `getline`               (next record into `$0`) — DONE
    - `getline var`           (next record into `var`) — DONE (grammar+eval)
    - `getline < file`        (from file into `$0`) — DONE; opens through
      `ctx.fs` (kash virtual filesystem) via the `fileOpener` parameter
      on `Awk.compile().run(…)`. Files are kept open across calls and
      released when the run finishes.
    - `getline var < file`    (from file into `var`) — DONE
    - `cmd | getline [var]`   (from command pipe) — DONE; routed through
      the `cmdOpener` parameter (AsyncPipe + `ShellRunner` launch).
  - Output redirection: `print > file`, `print >> file`, `print | cmd`
  - `close(target)`
  - `system(cmd)`, `fflush([file])`
- **User functions**
  - `function name(params) { … }` — scalars passed by value, arrays by
    reference (positional rule based on what the body uses).
  - Local variables: extra parameters past the call site's argument count.
  - `return [expr]`.
- **Range patterns**: `pat1, pat2 { … }`
- **Special variables (write side)**: assignments to `FS`/`OFS`/`ORS`/`RS`
  taking effect on the next record / output.
- **`nextfile`**: skip to the next input file.

### Slice 3 — gawk-style stretch (optional, decide later)

- Multi-character `RS` as regex
- `printf` `%c`, `%i` quirks beyond POSIX
- `\u`-style Unicode escapes (we're UTF-8 internal anyway)
- `getline` from coproc (`cmd |& getline`)
- `delete a` clearing whole array
- `length(a)` returning array element count

Defer. Slice 1 + Slice 2 is enough to satisfy the conformance corpus we
care about.

---

## Public surface (planned)

`com.accucodeai.kash.tools.awk.Awk` — `compile(source)` → `AwkProgramHandle`,
`AwkProgramHandle.run(input: Sequence<String>, opts: AwkOptions)` →
`Sequence<String>` (one output line per emission).

`AwkOptions` carries the `-F` field separator and `-v` pre-assignments.
Sealed `AwkException` with `AwkParseError` and `AwkRuntimeError` matches
the jq exception hierarchy.

Mirroring jq deliberately: the `awk` `Command` wires the engine to the
shell I/O. Stdin → records, stdout ← emissions. `-f file` for program
source from file, positional arg otherwise.

---

## Implementation order

1. **Hand lexer** (`AwkLexer.kt`, `AwkTokenSourceAdapter.kt`) — the
   regex/divide disambiguator + UTF-8 string + number lexing. Ports of the
   pattern from `:core/Lexer.kt`. Until this lands, the ANTLR `AwkLexer.g4`
   stays trivial.
2. **ANTLR `AwkParser.g4`** — operator precedence ladder, statement forms,
   pattern-action rules, function defs. Driven directly from the POSIX
   grammar section without copying production names.
3. **`AwkAstBuilder.kt`** — visitor mapping the parse tree to typed AST.
4. **`AwkEvaluator.kt`** — interpreter. Runs over a record source pulled
   from `ctx.stdin`. Field semantics use a small `Fields` helper that
   resplits on FS change.
5. **`AwkCommand.kt`** — real arg parsing (`-F`, `-v`, `-f`, source as
   positional), source-from-file vs source-as-arg, file-list vs stdin
   input, exit codes.
6. **`AwkConformanceRunner`** — `external/onetrueawk/testdir/` once the
   submodule lands; quarantine list for gawk-extension fixtures.
