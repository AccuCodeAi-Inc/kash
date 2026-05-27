# Security Policy

Kash exposes a session-level **sandbox policy** that governs what host
resources tools may reach. The policy is set when a `KashSession` is
constructed and is inherited by every command, subshell, and forked
interpreter for the lifetime of that session.

Policies are **one-way**: a child shell may tighten the policy for its
own descendants but may never relax it. There is no intrinsic that
loosens the posture mid-session.

The policy enum lives at
[`SandboxPolicy.kt`](../api/src/commonMain/kotlin/com/accucodeai/kash/api/sandbox/SandboxPolicy.kt).

## Levels

Ordered from most permissive to most restrictive. Each level is at
least as strict as every level above it.

### `TRUSTED` (default)

No additional restrictions imposed by kash. Tools use their own
defaults. Suitable for running your own scripts on your own machine.

- Python via `python3-graalpy` runs with `HostAccess.NONE`, no native
  access, no subprocess, no host env, and kash's polyglot filesystem
  adapter — but no Truffle-level sandbox enforcement.
- Host-FS mounts (e.g. the GraalPy engine cache backed by `HostFs`) are
  permitted.

### `CONSTRAINED`

Best-effort restriction with policy validation but no native isolation.
Works on any GraalVM Community runtime.

- Python additionally applies `Context.Builder.sandbox(CONSTRAINED)`,
  opting into Truffle's policy-validation checks so configuration drift
  is caught early.
- Not a hard security boundary on Community runtimes — a determined
  attacker may still escape via JVM reflection, Truffle bugs, or
  unfixed CVEs in the polyglot runtime.
- Host-FS mounts still permitted.

### `UNTRUSTED`

Hard isolation requested. Python applies
`Context.Builder.sandbox(UNTRUSTED)` plus resource limits.

- The native-heap isolation this implies ships only in **Oracle
  GraalVM**. On Community runtimes the context build
  call throws and the tool refuses to start with a clear diagnostic
- Host-FS mounts still permitted (the cache mount the engine needs is
  itself host-FS-backed).

### `SAFE`

Strictest posture. Refuses any operation that requires host-FS access
or that runs foreign code.

- `python3` (every engine) refuses to start and exits 126 with a clear
  diagnostic. Both `execute()` and the interactive REPL are gated.
- The GraalPy engine cache mount that would otherwise be provisioned
  via `HostFs` is never created under SAFE because the engine never
  initializes.
- The shell itself continues to run against its mounted virtual
  filesystem; only host-FS-backed extensions are gated off.

## Network policy

Separately from the sandbox policy, every `KashMachine` carries a
`NetworkPolicy` that gates outbound HTTP(S) requests made by
network-aware tools (today: `curl`, via `KashKtorClient`). The policy
lives at
[`NetworkPolicy.kt`](../api/src/commonMain/kotlin/com/accucodeai/kash/api/sandbox/NetworkPolicy.kt).

### Levels

- `NetworkPolicy.None` (default) — no restriction. Every request is
  permitted. Both `kash-app` (JVM) and `kash-app-web` (wasmJs) ship
  this default.
- `NetworkPolicy.DenyAll` — refuses every request before any wire I/O.
- `NetworkPolicy.Allowlist(hostPatterns, allowedPorts?, allowedSchemes?)`
  — exact host, `.example.com` subdomain wildcard, or `*` plus optional
  port/scheme restrictions.

### Scope

- The policy is enforced inside `KashKtorClient.execute()` on a
  per-request basis. The check fires *before* DNS lookup or connection
  setup.
- On JVM, **each redirect hop is re-screened** against the policy and
  `Authorization` / `Cookie` / `Proxy-Authorization` are stripped on
  cross-origin redirects (CVE-2022-27776 mitigation).
- On wasmJs, only the initial URL is re-screened. When
  `HttpRequest.followRedirects = true`, intermediate hops are handed
  off to the browser's `fetch(..., redirect: "follow")` and are NOT
  re-checked. If you need a redirect to be policy-gated in the
  browser, leave `followRedirects = false` and walk the chain
  manually.

### Limits on the wasmJs (browser) target

Some headers are forbidden by the Fetch spec and cannot be set or
suppressed by tools — the browser always sends its own values for
`User-Agent`, `Accept-Encoding`, `Accept-Language`, `Cookie`,
`Connection`, `Content-Length`, `Host`, `Origin`, `Referer`, and the
`Sec-*` / `Proxy-*` families. Cross-origin requests are additionally
subject to CORS — the browser may refuse the response even when the
policy permits the request. This is working as intended, but Kash
allows for setting a proxy if you so desire.

### Limits inside Python (`python3`)

Enforcing a `NetworkPolicy` against arbitrary Python code is a
sandboxing problem, not a wrapper problem: a script that `import
socket; socket.socket()` (or `import urllib.request`, `import
requests`, etc.) reaches the network through whatever sockets the
underlying interpreter has access to. There is no soft wrapper inside
the interpreter that can be made airtight — anything below the
sandbox boundary can route around it.

Kash therefore enforces a strict contract:

> If `KashMachine.networkPolicy` is anything other than
> `NetworkPolicy.None`, the `python3` engine refuses to start unless
> the session is running at `SandboxPolicy.UNTRUSTED`. The engine
> exits 126 with the diagnostic:
> `python3: NetworkPolicy other than None requires sandbox=UNTRUSTED;
> got sandbox=<level>.`

The reason this works only at `UNTRUSTED`:

- **GraalPy (JVM)** — `SandboxPolicy.UNTRUSTED` activates Truffle's
  sandbox validation, which denies host sockets at the polyglot
  layer. `TRUSTED` and `CONSTRAINED` either don't gate sockets at all
  or only partially. (`UNTRUSTED` itself ships only on Oracle GraalVM
  — on Community runtimes the engine refuses to start regardless.
  So in practice, non-None `NetworkPolicy` + GraalPy is Oracle-only.)
- **Pyodide (wasmJs)** — Pyodide doesn't honor the `SandboxPolicy`
  tiers at all (the wasm + browser tab IS the sandbox). For
  consistency the engine applies the same contract: non-None
  `NetworkPolicy` requires `UNTRUSTED`, otherwise it refuses to
  start. Stdlib `socket`/`urllib` do not work in the browser anyway;
  the only effective path is `pyodide.http.pyfetch` → browser
  `fetch`, with the usual CORS / forbidden-header caveats.

Per-host `Allowlist` is not exposed inside Python in either backend
— `IOAccess` is all-or-nothing for sockets. If you need scoped HTTP
from Python, the supported path is: enable `NetworkPolicy.None`,
gate at the host level (firewall, `ProxySelector`, or hand the
script a `KashKtorClient`-backed adapter through a polyglot
function).

You can work around these limitations using a broader scope network policy

### What is NOT a network policy boundary

- Direct JGit transport (`tools/kash/git-jgit`) speaks its own wire
  protocol and currently consults its adapter-level network policy,
  not `KashMachine.networkPolicy` — see the JGit adapter's
  `networkPolicy` field for the override semantics.
- Anything an embedded language extension does via its own host
  bridge (GraalPy `socket`, future engines). See above.

## Recommended Arch


The recommended arch is like this

```
|-----------|                |-----------|
|           |                |           |
|  Trusted  |                |    Kash   |
|    Pod    | --- HTTP --->  |    Pod    |
|           |                |           |
|-----------|                |-----------|
```

Where a trusted service reaches out to an untrusted 'kash pod'.

You have two options for how this pod is run.

1. gVisor or Kata
   - If gVisor or Kata is used then you can use any JDK to run kash with `SandboxPolicy.CONSTRAINED`
   - Use real K8s NetworkPolicy with `NetworkPolicy.None` for kash
2. No gVisor or Kata
   - Use GraalVM with `SandboxPolicy.UNTRUSTED` (which will wrap python3 in a similar manner to gVisor/Kata)
   - Use real K8s NetworkPolicy with `NetworkPolicy.None` for kash

In both cases the pod must be unprivileged
- If an attacker escapes the JVM/Truffle sandbox, the surrounding container
  is the next (and last) trust boundary. A privileged pod has no boundary left.
- Network requests can't reach unauthenticated services on the cluster
  (metadata service, kubelet, neighboring pods).
- `privileged: false`, `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, etc...


_Is this better than Firecracker?_ Yes. The Kash Pod can still serve a large number of agents in parallel.

----

You can also run Kata within a trusted pod with a limited feature set.

However, this arch is not recommended mainly because it's easier to 'hold it wrong'.
- You must use `SandboxPolicy.SAFE` (this will disable python3)
- You must use `NetworkPolicy.Allowlist` or `NetworkPolicy.DenyAll`
- You must be extra careful with any special adapters or custom commands you add.


## Reporting vulnerabilities

If you believe you have found a security issue in kash (a sandbox
escape, an unintended host-FS read/write, a way to relax a tighter
policy from within a child shell, etc.), please open a GitHub security
advisory rather than a public issue.

## Out of scope

- Bugs that require a `TRUSTED` or `CONSTRAINED` session
- Bugs that require `NetworkPolicy.None` or `NetworkPolicy.Allowlist`
- Non JVM targets such as Kotlin/Native, Kotlin/JS, etc... Are out of scope. I.e. fetch behavior in browser
- Speculative Execution
- Exploits which require interactive shells.
- Denial of service. DOS protection is `best effort` in Kash, but is not considered a vulnerability.
- Noisy Neighbor (i.e. one KashMachine affecting another in same JVM instance)
