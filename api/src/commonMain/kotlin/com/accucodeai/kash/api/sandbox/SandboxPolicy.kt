package com.accucodeai.kash.api.sandbox

/**
 * Session-level capability gate that tools consult to choose between
 * "trusted" and "sandboxed" execution paths. Pure code — like
 * [com.accucodeai.kash.api.user.UserDatabase], it is NOT serialized in
 * snapshots and must be re-supplied at session restore.
 *
 * - [TRUSTED] is the existing hardened default (`HostAccess.NONE`,
 *   no native, no proc, no env, custom polyglot FS). Suitable for
 *   running your own scripts.
 * - [CONSTRAINED] additionally applies `Context.Builder.sandbox(
 *   SandboxPolicy.CONSTRAINED)`, opting into Truffle's policy
 *   enforcement so configuration drift is caught early. Works on any
 *   GraalVM Community runtime.
 * - [UNTRUSTED] applies `Context.Builder.sandbox(SandboxPolicy.UNTRUSTED)`
 *   plus resource limits. The actual native-heap isolation it implies
 *   ships only in **Oracle GraalVM** (formerly "EE"); on Community
 *   runtimes the build call throws, and the tool refuses to start with
 *   a clear diagnostic rather than silently falling back.
 * - [SAFE] refuses to start the engine at all. The cache mount that
 *   `python3-graalpy` provisions via [com.accucodeai.kash.fs.HostFs] is
 *   skipped along with the polyglot context.
 */
public enum class SandboxPolicy {
    /** No additional restrictions imposed by kash. Tools use their own defaults. */
    TRUSTED,

    /**
     * Best-effort restriction without hard enforcement: tools opt in to
     * safer configs and Truffle's policy checks. Works on any GraalVM
     * Community runtime. Not a security boundary on community runtimes —
     * a determined attacker may escape via JVM reflection or Truffle bugs.
     */
    CONSTRAINED,

    /**
     * Strong enforcement requested. For consumers like `python3-graalpy`
     * this requires Oracle GraalVM polyglot artifacts at runtime; on
     * Community runtimes the consumer refuses to start (per session-owner
     * decision: explicit failure over silent downgrade).
     */
    UNTRUSTED,

    /**
     * Hard refusal posture. Stricter than [UNTRUSTED]: language engines
     * that need any host-FS access at all (e.g. `python3-graalpy`'s
     * on-disk engine cache backed by [com.accucodeai.kash.fs.HostFs])
     * refuse to start. The shell itself still runs against its mounted
     * virtual filesystem; only host-FS-backed extensions are gated off.
     *
     * Intended for environments that must guarantee no foreign code
     * executes and no host paths are read or written by kash tools.
     */
    SAFE,
    ;

    /**
     * True if [other] is at least as strict as this policy. Used by
     * subshells / forks that may only *tighten* the policy.
     */
    public fun atLeastAsStrictAs(other: SandboxPolicy): Boolean = this.ordinal >= other.ordinal
}
