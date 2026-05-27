package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.splitPath
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.intrinsics.IntrinsicCatalog
import com.accucodeai.kash.intrinsics.IntrinsicEntry

// Command resolution (POSIX §2.9.1.1) + script loading extracted from Interpreter.

/**
 * Sealed result of [resolveCommand]. Mirrors the [POSIX command-search
 * order](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_09_01_01):
 * special builtin / function / PATH-resolved utility (synthetic
 * [com.accucodeai.kash.fs.ToolsFs] entry) / PATH-resolved script.
 */
internal sealed class Resolved {
    class Builtin(
        val spec: CommandSpec,
        /** `name` for special builtins, the located path for utilities. */
        val displayPath: String,
    ) : Resolved()

    class Function(
        val body: com.accucodeai.kash.ast.FunctionDef,
    ) : Resolved()

    class Script(
        val path: String,
    ) : Resolved()

    class Intrinsic(
        val entry: IntrinsicEntry,
    ) : Resolved()
}

internal fun Interpreter.invalidateHashCache() {
    hashCache.clear()
    hashHits.clear()
    syncBashCmds()
}

internal fun Interpreter.resolveCommand(name: String): Resolved? {
    // POSIX §2.9.1.1 step 1.a: special builtins bypass functions/PATH.
    // Bash deviates from POSIX outside posix mode and resolves functions
    // FIRST — `f() { echo hi; }; break` runs the function override even
    // though `break` is a special builtin. We honor bash's order unless
    // posix mode is on.
    val direct = registry[name]
    if (posixModeRuntime) {
        IntrinsicCatalog.byName[name]?.let { entry ->
            if (entry.isSpecial && name !in disabledIntrinsics) return Resolved.Intrinsic(entry)
        }
        if (direct != null && direct.isSpecial) {
            return Resolved.Builtin(direct, displayPath = name)
        }
    }
    // Step 1.b: shell functions.
    functions[name]?.let { return Resolved.Function(it) }
    // Non-posix: special-builtin/intrinsic lookup falls through here
    // AFTER the function check — gives functions the precedence bash
    // applies outside posix mode.
    if (!posixModeRuntime) {
        IntrinsicCatalog.byName[name]?.let { entry ->
            if (entry.isSpecial && name !in disabledIntrinsics) return Resolved.Intrinsic(entry)
        }
        if (direct != null && direct.isSpecial) {
            return Resolved.Builtin(direct, displayPath = name)
        }
    }
    // Step 1.c (POSIX): regular built-ins. Regular intrinsics live here —
    // skipped when suppressed via `enable -n NAME` so the PATH walk takes
    // over (matches bash: `enable -n test; test -f x` runs /usr/bin/test).
    IntrinsicCatalog.byName[name]?.let { entry ->
        if (name !in disabledIntrinsics) return Resolved.Intrinsic(entry)
    }
    // Regular (non-special) built-ins also live in the [registry], but
    // only the ones bash itself ships as builtins should beat the PATH
    // walk — `echo`, `printf`, `pwd`, etc. POSIX utilities that bash
    // does NOT shadow with a builtin (`cat`, `ls`, `grep`, …) must fall
    // through to PATH so `type cat` reports `/bin/cat` the way bash does.
    // The [CommandTag.BASH_BUILTIN] tag marks the bash-builtin subset.
    if (direct != null && com.accucodeai.kash.api.CommandTag.BASH_BUILTIN in direct.tags) {
        return Resolved.Builtin(direct, displayPath = name)
    }
    // Step 1.d: path-qualified names skip PATH walk. Resolve against
    // cwd first so `./echo` and `bin/foo` work in addition to absolute paths.
    if ('/' in name) return resolveFsPath(Paths.resolve(cwd, name))
    // Hash cache (POSIX §hash). Increment the hit counter on each cache
    // read (not on the populating PATH walk above) so the `hash` listing's
    // "hits" column reflects post-population reads only.
    hashCache[name]?.let {
        hashHits[name] = (hashHits[name] ?: 0) + 1
        return it
    }
    // Steps 1.e / 1.f: PATH walk.
    for (dir in splitPath(env["PATH"])) {
        val candidate = if (dir.isEmpty()) Paths.resolve(cwd, name) else "$dir/$name"
        val exists =
            try {
                fs.exists(candidate) && !fs.isDirectory(candidate)
            } catch (_: Throwable) {
                false
            }
        if (!exists) continue
        val r = resolveFsPath(candidate) ?: continue
        // bash convention: `hash` only tracks utilities reached via PATH —
        // not intrinsics or in-process built-ins, which the shell knows
        // about by name. Cache only kind=TOOL or real scripts.
        if (shouldCache(r)) {
            hashCache[name] = r
            // Bash exposes the hash table live as `BASH_CMDS`, so a command
            // hashed by ordinary resolution must be immediately visible to
            // `${BASH_CMDS[name]}` — not only after a `hash` builtin call.
            // Mirror [syncBashCmds]'s path mapping; insert incrementally so
            // user-written entries aren't clobbered (read order is re-derived
            // by hash bucket, so insertion order here is irrelevant).
            assocArrays["BASH_CMDS"]?.put(name, if (r is Resolved.Script) r.path else name)
        }
        return r
    }
    return null
}

internal fun Interpreter.shouldCache(r: Resolved): Boolean =
    when (r) {
        is Resolved.Builtin -> r.spec.kind == CommandKind.TOOL
        is Resolved.Script -> true
        is Resolved.Function -> false
        is Resolved.Intrinsic -> false
    }

internal fun Interpreter.resolveFsPath(path: String): Resolved? {
    if (!fs.exists(path) || fs.isDirectory(path)) return null
    // Synthetic FS short-circuit (ToolsFs).
    fs.commandSpec(path)?.let { return Resolved.Builtin(it, displayPath = path) }
    return Resolved.Script(path)
}

internal suspend fun Interpreter.runScript(
    path: String,
    args: List<String>,
    inlineEnv: Map<String, String>,
    stdio: Stdio,
): Int {
    val bytes =
        try {
            fs.readBytes(path)
        } catch (e: Throwable) {
            stdio.stderr.writeUtf8("$path: ${e.message ?: "cannot read"}\n")
            return 126
        }
    // All script-vs-binary dispatch is owned by the VM's binfmt handler
    // chain. The default chain (see DefaultKashMachine) installs:
    //   - native-reject — refuses ELF/Mach-O/PE with a friendly diagnostic,
    //   - shell-convention — the universal terminator: shebang → basename
    //     via utilityRunner, else run as a kash shell script via shellRunner.
    // Userspace handlers (binfmt_misc-style) registered via
    // `machine.binfmt.register(...)` slot in between by priority.
    //
    // ShellInvocation already isolates env/cwd/positional in a subshell;
    // we mirror the inline-env semantics by temporarily merging inlineEnv
    // into the caller's env for the duration of the call. POSIX §2.9.1.1
    // step 1.e's "no-shebang → /bin/sh" fallback lives in the shell-
    // convention handler now.
    val headPeek = if (bytes.size <= 128) bytes else bytes.copyOfRange(0, 128)
    // Snapshot static env only — see InterpreterExpand.kt's
    // evalCommandSubstitution for why dynamic specials are excluded.
    val savedEnv =
        env.keys
            .filter { varTable.find(it)?.isDynamic != true }
            .associateWith { env[it] ?: "" }
    return try {
        if (inlineEnv.isNotEmpty()) env.putAll(inlineEnv)
        machine.execFile(
            com.accucodeai.kash.api.binfmt.ExecRequest(
                path = path,
                argv = listOf(path) + args,
                env = env,
                inlineEnv = inlineEnv,
                stdin = stdio.stdin,
                stdout = stdio.stdout,
                stderr = stdio.stderr,
                parent = process,
                machine = machine,
                headPeek = headPeek,
                shellRunner = makeShellRunner(callerStderr = stdio.stderr),
                utilityRunner = makeUtilityRunner(),
            ),
        )
    } finally {
        // Restore the static-env snapshot. Iterate current static env
        // and drop names that weren't in the snapshot, then write back
        // any differing values. Dynamic specials (RANDOM, etc.) aren't
        // captured here and aren't subject to restore.
        val nowStatic = env.keys.filter { varTable.find(it)?.isDynamic != true }
        for (k in nowStatic) if (k !in savedEnv) env.remove(k)
        for ((k, v) in savedEnv) if (env[k] != v) env[k] = v
    }
}
