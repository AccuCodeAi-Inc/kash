# jq engine — implementation status

A frank list of what works and what doesn't, kept honest against
upstream jq (jqlang/jq). Update whenever you add or remove a feature.

Last revised: see `git log` on this file.

## Conformance corpus

- **Hand-written:** `src/jvmTest/resources/jq/corpus/basics.test` — all pass.
- **Upstream:** `external/jq/tests/jq.test` runs under `./gradlew conformanceTest`.
  Cases whose filter mentions a quarantined feature substring are skipped via
  JUnit `Assumptions`; see `JqConformanceRunner.quarantineSubstrings` for the
  current set.

## Public surface

`com.accucodeai.kash.tools.jq.Jq` — `run`, `compile`, `format` (raw + pretty).
`JqOptions(args)` for `--arg`/`--argjson`-style bindings. Exceptions: sealed
`JqException` with `JqParseError` and `JqRuntimeError` subclasses.

JSON is `com.accucodeai.kash.json.JsonValue` (alias of kotlinx-serialization's
`JsonElement`); helpers in `com.accucodeai.kash.json`.

---

## Implemented

### Filters / syntax
- Identity `.`, recursive descent `..`
- Field access `.a`, `.a.b`, optional `.a?`
- Index `.[i]`, slice `.[a:b]`, iterate `.[]`, optional variants
- Pipe `|`, comma `,`, parens
- Literals: numbers, strings, `true`, `false`, `null`, array `[…]`, object `{…}`
- Object shorthand `{a, b}`, dynamic keys `{(k): v}`, `{$v}`
- String interpolation `"foo \(.bar)"` (nested expressions, escapes)
- Arithmetic `+ - * / %` (cartesian over multi-output operands)
- Comparison `== != < <= > >=` and total order across types
- Logical `and` / `or` / `not`
- Alternative `//`
- `if-then-elif-else-end`
- `try / catch`, postfix `?`
- `error`, `error(msg)`
- `as $x | …` value binding
- `reduce src as $x (init; update)`
- `foreach src as $x (init; update [; extract])`
- Negation `-expr`

### Assignment / paths
- `path = value`, `path |= transform`
- Sugared `+=`, `-=`, `*=`, `/=`, `%=`, `//=`
- `path(f)`, `paths`, `paths(f)`, `leaf_paths`
- `getpath(p)`, `setpath(p; v)`, `del(p)`
- Path-producing interpretation for `.`, `..`, field, index, iterate, pipe,
  comma, optional, try, if, and `select(f)`

### User-defined functions
- `def name: body; rest`
- `def name(f; g): body; rest` — filter parameters (call-by-name / lazy)
- `def name($v): body; rest` — value parameters (eager, cartesian over outputs)
- Mixed filter + value params in one signature
- Self-recursion (factorial etc.)
- Lexical scoping; nested defs shadow outer
- Arity-based overloading: `def f: 1; def f(x): 2;`

### Regex (RE2/J on JVM — linear time, ReDoS-safe)
- `test(re)`, `test(re; flags)`
- `match(re)`, `match(re; flags)`  (`g` flag emits all matches)
- `capture(re)`, `capture(re; flags)` — named captures as an object
- `scan(re)` — stream of matches (string or capture-array)
- `sub(re; repl)`, `sub(re; repl; flags)`
- `gsub(re; repl)`, `gsub(re; repl; flags)`
- Replacement is a jq filter; named captures bind as `$name`
- Flags: `i`, `m`, `s`, `g`, `n` (no-op), `x` (rejected — RE2 lacks support)

### Builtins (≈ 60)
**Inspection**: `length`, `utf8bytelength`, `keys`, `keys_unsorted`, `values`,
`type`, `has`, `in`, `contains`, `paths`, `leaf_paths`

**Collections**: `to_entries`, `from_entries`, `with_entries`, `map`,
`map_values`, `select`, `add`, `any`, `all`, `reverse`, `sort`, `sort_by`,
`group_by`, `unique`, `unique_by`, `min`, `max`, `min_by`, `max_by`,
`range/1`, `range/2`, `range/3`, `first/0`, `first/1`, `last/0`, `last/1`,
`nth(n; f)`, `limit(n; f)`, `walk(f)`, `recurse/0`, `recurse_down/0`,
`recurse(f)`, `until(cond; upd)`, `while(cond; upd)`

**Strings**: `tostring`, `tonumber`, `tojson`, `fromjson`, `ascii_downcase`,
`ascii_upcase`, `startswith`, `endswith`, `ltrimstr`, `rtrimstr`, `split`,
`join`, `explode`-via-builtins (no), `not`, `empty`

**Math**: `floor`, `ceil`, `fabs`, `sqrt`

### Public API niceties
- `Jq.format(v, raw, pretty)` — implements `-r` and pretty-print at the
  output boundary. No CLI yet (see "Not implemented" below).
- `JqOptions.args` populates `$name` variables (`--arg` / `--argjson`).

---

## Not implemented

### Big ticket
- **No `jq` shell builtin in kash.** Engine is reachable from Kotlin only.
  Wiring `commands/Builtins.kt` to expose `jq` with argv parsing (`-r`, `-c`,
  `-s`, `-n`, `-R`, `--arg`, `--argjson`, `--slurpfile`, `--rawfile`) is its
  own task.
- **`inputs` / `input`** — pull additional inputs mid-filter. Required for
  `-n` (null input) and `-s` (slurp) CLI semantics. The engine takes a single
  input; multi-input streaming hasn't been designed yet.
- **Number precision.** All arithmetic flows through `Double`. Big integers
  (`> 2^53`) lose precision. Upstream jq uses decimal-aware numerics now.

### Formats / encodings
- `@csv`, `@tsv`, `@json`, `@uri`, `@base64`, `@base64d`, `@sh`, `@html`,
  `@text`. Lexer tokenizes `@name` but no builtin/parser handling yet.

### Modules / I/O
- `import`, `include`, modules
- `input_filename`, `$__loc__`
- `env`, `$ENV`
- `halt`, `halt_error`, `stderr`, `debug`
- `getpath` corner cases on missing intermediates (verify with corpus pass)

### Date / time
- `now`, `localtime`, `gmtime`, `mktime`
- `fromdateiso8601`, `todateiso8601`
- `strftime`, `strptime`, `dateadd`, `datesub`, `date`

### Math
- `log`, `exp`, `pow`, `log10`, `log2`, `sin`, `cos`, `tan`, `asin`, `acos`,
  `atan`, `atan2`, `sinh`, `cosh`, `tanh`, `nan`, `infinite`, `isnan`,
  `isinfinite`, `isnormal`

### Strings
- `explode` / `implode`
- `splits(re; flags)` — stream form of split (regex-based)
- `ascii` (codepoint to char)
- `@base32` / `@base32d`

### Regex extensions
- Backreferences (`\1` in patterns) — RE2 doesn't support them
- Lookbehind, possessive quantifiers — RE2 doesn't support them
- These are conscious tradeoffs for the linear-time guarantee. Patterns
  needing them throw `JqRuntimeError` at compile time.

### SQL-ish builtins (newer jq)
- `INDEX(f)`, `INDEX(stream; idx)`
- `IN(stream)`, `IN(source; stream)`
- `GROUP_BY(f)`, `UNIQUE_BY(f)`

### Misc
- Streaming output (`--stream`)
- `getpath` with non-existent paths returning `null` — verify all corners
- `truncate_stream`, `fromstream`, `tostream`
- `limit` with negative `n`
- `splits(re)` regex form (we have non-regex `split(sep)`)

---

## Known semantic quirks

- **`select(f)` with multi-output `f`** emits the input once per truthy
  output. Matches upstream jq's textbook definition (`if f then . else
  empty end`), but surprising if `f` produces multiple booleans.
- **String multiplication** by zero/negative returns `null` (jq's behavior).
- **Object key ordering** preserves insertion order via `LinkedHashMap`
  except for `keys` (sorted) vs `keys_unsorted`.

---

## Architecture notes

- Pure pull-based `Sequence<JsonValue>` end-to-end. No coroutines required.
- `JqContext` is immutable; new bindings produce new contexts.
- Function bindings live in `ctx.funcs: Map<String, FunctionSlot>` where
  `FunctionSlot` is a mutable wrapper so a `def` can refer to itself for
  recursion without a fixed-point dance.
- Filter parameters are `FilterThunk(expr, callerCtx)` — call-by-name. Value
  parameters evaluate eagerly in the caller's environment.
- Regex compiles per call site (no cache) but RE2/J is linear-time.
- All AST and eval internals are `internal`. Only `Jq`, `JqOptions`,
  `JqProgram`, `JqException` (+ subclasses) are `public`.

## Independence invariant

The `tools/jq/` package depends only on `json/` and stdlib. It must not
import `kash.parser`, `kash.interpreter`, `kash.ast`, `kash.commands`, or
`kash.fs`. This is enforced by `ToolsIsolationTest` (jvmTest).
