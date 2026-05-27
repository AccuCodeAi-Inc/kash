package com.accucodeai.kash.conformance

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `gcc` / `cc` stub for the conformance harness. The bash test corpus
 * builds tiny C helpers in a few places — `glob-bracket.tests` compiles
 * a `fnmatch.c` into a `fnmatch` executable, for example. We can't run
 * a real C toolchain, but we can intercept the compile and write a
 * shell-script shim at the `-o` target that dispatches to a kash command
 * of the same basename. Mirrors what the test EXPECTS to happen: after
 * the compile, `$WORK_DIR/fnmatch arg1 arg2` invokes a binary whose
 * behavior matches `fnmatch(3)` — which our [FnmatchCommand] provides.
 *
 * Args we honor: `-o <output>` and the trailing source-file positional.
 * Everything else is ignored. Exit 0; no diagnostics. If no `-o` is
 * given, the conventional `a.out` is created in the cwd.
 */
public class GccStubCommand :
    Command,
    CommandSpec {
    override val name: String = "gcc"
    override val aliases: List<String> = listOf("cc")
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        // Extract `-o <output>`. Default to `a.out` per cc(1).
        var out = "a.out"
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "-o" && i + 1 < args.size) {
                out = args[i + 1]
                i += 2
            } else {
                i++
            }
        }
        // Derive the kash-command name from the output filename so the
        // resulting shim dispatches to (e.g.) `fnmatch` when -o is
        // `fnmatch` or `./fnmatch`. Strip directory prefix.
        val base = out.substringAfterLast('/')
        // Resolve `out` to an absolute FS path. If it's not absolute,
        // anchor it at the current cwd (which the test sets to its
        // WORK_DIR via `cd $WORK_DIR`).
        val target = if (out.startsWith('/')) out else "${ctx.process.cwd}/$out"
        val shim = "#!/tmp/bash\nexec $base \"\$@\"\n"
        try {
            ctx.process.fs.writeBytes(target, shim.encodeToByteArray())
        } catch (t: Throwable) {
            ctx.stderr.writeUtf8("gcc: failed to write $target: ${t.message ?: t::class.simpleName}\n")
            return CommandResult(exitCode = 1)
        }
        return CommandResult(exitCode = 0)
    }
}
