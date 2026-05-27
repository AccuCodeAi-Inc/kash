package com.accucodeai.kash.tools.tsort

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound

/**
 * Result of topological sort.
 *
 * @property order Linearization. Always contains every node observed, even when a cycle was broken.
 * @property cycle One node from each cycle that was broken (best-effort identifier for diagnostics);
 *                 empty when the input is acyclic.
 */
public data class TsortResult(
    val order: List<String>,
    val cycle: List<String>,
)

/**
 * Kahn's algorithm with insertion-order tie-break. On cycle, breaks by picking the
 * earliest-inserted remaining node (POSIX permits any choice; this one is stable).
 */
public fun topologicalSort(
    nodes: List<String>,
    edges: List<Pair<String, String>>,
): TsortResult {
    // Use insertion order maps so tie-breaks are stable.
    val outgoing = LinkedHashMap<String, LinkedHashSet<String>>()
    val inDegree = LinkedHashMap<String, Int>()
    for (n in nodes) {
        outgoing.getOrPut(n) { LinkedHashSet() }
        inDegree.getOrPut(n) { 0 }
    }
    for ((from, to) in edges) {
        val added = outgoing.getOrPut(from) { LinkedHashSet() }.add(to)
        outgoing.getOrPut(to) { LinkedHashSet() }
        inDegree.getOrPut(from) { 0 }
        val prev = inDegree.getOrPut(to) { 0 }
        if (added) inDegree[to] = prev + 1
    }

    val order = mutableListOf<String>()
    val cycleReports = mutableListOf<String>()
    val remaining = LinkedHashSet(inDegree.keys)

    while (remaining.isNotEmpty()) {
        // Find the first remaining node with in-degree 0 in insertion order.
        var zero: String? = null
        for (n in remaining) {
            if (inDegree[n] == 0) {
                zero = n
                break
            }
        }
        if (zero == null) {
            // Cycle: break by picking the earliest remaining node.
            val pick = remaining.first()
            cycleReports += pick
            inDegree[pick] = 0
            continue
        }
        order += zero
        remaining.remove(zero)
        for (t in outgoing[zero] ?: emptySet()) {
            if (t in remaining) {
                inDegree[t] = (inDegree[t] ?: 0) - 1
            }
        }
    }
    return TsortResult(order, cycleReports)
}

/**
 * POSIX `tsort` — topological sort of partial-ordering input.
 *
 * Input format: whitespace-separated pairs of node names per record; a pair
 * `A B` denotes the precedence A < B (i.e. A must come before B in output).
 * A self-pair `A A` records the node without an ordering constraint.
 *
 * Operands: zero or one FILE (default stdin; `-` also means stdin).
 *
 * On cycle: warn on stderr and continue with a stable break (POSIX permits
 * any choice). Exit code is 1 if a cycle was detected, 0 on clean acyclic
 * input, 2 on usage/IO error.
 */
public class TsortCommand :
    Command,
    CommandSpec {
    override val name: String = "tsort"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val files = mutableListOf<String>()
        var endOfOpts = false
        for (a in args) {
            if (endOfOpts) {
                files += a
            } else if (a == "--") {
                endOfOpts = true
            } else if (a == "-h" || a == "--help") {
                ctx.stdout.writeUtf8("Usage: tsort [FILE]\n")
                return CommandResult()
            } else if (a == "-V" || a == "--version") {
                ctx.stdout.writeUtf8("tsort (kash)\n")
                return CommandResult()
            } else if (a == "-") {
                files += a
            } else if (a.startsWith("-") && a.length > 1) {
                ctx.stderr.writeUtf8("tsort: unrecognized option: $a\n")
                return CommandResult(exitCode = 2)
            } else {
                files += a
            }
        }
        if (files.size > 1) {
            ctx.stderr.writeUtf8("tsort: extra operand '${files[1]}'\n")
            return CommandResult(exitCode = 2)
        }

        val tokens = mutableListOf<String>()
        val src: SuspendSource =
            if (files.isEmpty() || files[0] == "-") {
                ctx.stdin
            } else {
                val path = resolvePath(ctx.process.cwd, files[0])
                try {
                    ctx.process.fs.source(path)
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("tsort: cannot read: ${files[0]}: No such file or directory\n")
                    return CommandResult(exitCode = 1)
                }
            }
        try {
            while (true) {
                val line = src.readUtf8LineOrNull() ?: break
                for (tok in line.split(' ', '\t').filter { it.isNotEmpty() }) {
                    tokens += tok
                }
            }
        } finally {
            if (files.isNotEmpty() && files[0] != "-") src.close()
        }

        if (tokens.size % 2 != 0) {
            ctx.stderr.writeUtf8("tsort: input contains an odd number of tokens\n")
            return CommandResult(exitCode = 1)
        }

        // Track first-seen insertion order for nodes.
        val nodes = LinkedHashSet<String>()
        val edges = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < tokens.size) {
            val a = tokens[i]
            val b = tokens[i + 1]
            nodes += a
            nodes += b
            if (a != b) edges += (a to b)
            i += 2
        }

        val result = topologicalSort(nodes.toList(), edges)
        if (result.cycle.isNotEmpty()) {
            ctx.stderr.writeUtf8("tsort: input contains a loop:\n")
            for (n in result.cycle) {
                ctx.stderr.writeUtf8("tsort: $n\n")
            }
        }
        for (n in result.order) {
            ctx.stdout.writeLine(n)
        }
        return CommandResult(exitCode = if (result.cycle.isEmpty()) 0 else 1)
    }

    private fun resolvePath(
        cwd: String,
        path: String,
    ): String {
        val joined = if (path.startsWith("/")) path else "$cwd/$path"
        val parts = mutableListOf<String>()
        for (segment in joined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return "/" + parts.joinToString("/")
    }
}
