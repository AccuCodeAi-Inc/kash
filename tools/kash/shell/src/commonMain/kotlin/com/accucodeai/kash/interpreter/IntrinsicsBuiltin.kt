package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.intrinsics.IntrinsicCatalog

/**
 * Bash extension [`builtin`](https://www.gnu.org/software/bash/manual/html_node/Bash-Builtins.html):
 * execute the named shell builtin, bypassing function and alias lookup
 * (and never falling back to PATH).
 *
 * Used in scripts and completion plumbing to call a builtin even when a
 * function with the same name has shadowed it — e.g. `builtin echo "$@"`
 * runs the real echo even if the user redefined `echo` as a function.
 *
 * Exit status: whatever the dispatched builtin returns. If the named
 * thing isn't a builtin, exit 1 with a "not a shell builtin" diagnostic.
 */
internal suspend fun Interpreter.runBuiltinIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (args.isEmpty()) return 0
    val name = args[0]
    val rest = args.drop(1)
    if (IntrinsicCatalog.byName[name] != null) {
        return runIntrinsic(name, rest, stdio) ?: 0
    }
    // kash implements some bash builtins as registry tools rather than
    // intrinsics — `pwd`, `echo`, `printf`, `cd`, `test`, `[`, `true`,
    // `false`, `read`, etc. Spawning a full child process just to run
    // `builtin pwd` is heavyweight, so we inline the trivial cases here
    // and let the rest fall through to the "not a builtin" diagnostic.
    when (name) {
        "pwd" -> {
            stdio.stdout.writeUtf8("$cwd\n")
            return 0
        }

        "true" -> {
            return 0
        }

        "false" -> {
            return 1
        }

        "echo" -> {
            stdio.stdout.writeUtf8(rest.joinToString(" ") + "\n")
            return 0
        }
    }
    // If the registry has a BUILTIN-kind spec for this name (not a TOOL),
    // bash semantics say `builtin NAME` should invoke it bypassing the
    // function/alias layer. We synthesize a SimpleCommand and let the
    // resolver dispatch the spec like normal — this catches `cd`, `test`,
    // `[`, `read`, etc. that we ship as registry builtins.
    val spec = registry[name]
    if (spec != null && spec.kind == com.accucodeai.kash.api.CommandKind.BUILTIN) {
        return runResolvedSpec(spec, name, rest, emptyMap(), stdio)
    }
    stdio.stderr.writeUtf8("${shellDiagPrefix()}builtin: $name: not a shell builtin\n")
    return 1
}
