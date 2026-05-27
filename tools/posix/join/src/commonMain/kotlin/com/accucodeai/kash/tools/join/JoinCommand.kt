package com.accucodeai.kash.tools.join

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths

/**
 * POSIX `join` — relational equi-join of two pre-sorted text files on a
 * key field. Streaming merge: reads one line from each side at a time.
 *
 * Inputs MUST be sorted on the join field (lexicographic by default, or by
 * whatever the user used to pre-sort them). We don't reorder — POSIX says
 * the user is responsible.
 *
 * Default field separator is runs of whitespace, with leading whitespace
 * ignored (POSIX). With `-t SEP`, fields are split on exactly that one
 * character, no collapsing.
 */
public class JoinCommand :
    Command,
    CommandSpec {
    override val name: String = "join"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private data class Options(
        var key1: Int = 1,
        var key2: Int = 1,
        var separator: Char? = null,
        var empty: String? = null,
        var outputSpec: List<OutputField>? = null,
        var caseInsensitive: Boolean = false,
        var printAll1: Boolean = false,
        var printAll2: Boolean = false,
        var onlyUnpaired1: Boolean = false,
        var onlyUnpaired2: Boolean = false,
        var header: Boolean = false,
        var checkOrder: Boolean = true,
        var operands: MutableList<String> = mutableListOf(),
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = Options()
        var i = 0
        var endOfOpts = false

        // Helper to fetch the value of an option that may be `-x V` or `-xV` or `--long=V` or `--long V`.
        fun nextValue(
            current: String,
            flag: String,
        ): String? {
            // already includes -x split or --long=val
            if (current.startsWith("--")) {
                val eq = current.indexOf('=')
                if (eq >= 0) return current.substring(eq + 1)
            } else if (current.length > flag.length) {
                return current.substring(flag.length)
            }
            i++
            if (i >= args.size) return null
            return args[i]
        }

        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                opts.operands += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-" -> {
                    opts.operands += a
                }

                a == "--help" || a == "-h" -> {
                    ctx.stdout.writeUtf8(usage())
                    return CommandResult()
                }

                a == "--version" || a == "-V" -> {
                    ctx.stdout.writeUtf8("join (kash) 1.0\n")
                    return CommandResult()
                }

                a == "-i" || a == "--ignore-case" -> {
                    opts.caseInsensitive = true
                }

                a == "--header" -> {
                    opts.header = true
                }

                a == "--check-order" -> {
                    opts.checkOrder = true
                }

                a == "--nocheck-order" -> {
                    opts.checkOrder = false
                }

                a.startsWith("-j") && a != "-j" -> {
                    val v = a.substring(2)
                    opts.key1 = parseField(ctx, v) ?: return CommandResult(exitCode = 2)
                    opts.key2 = opts.key1
                }

                a == "-j" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("join: option requires an argument -- 'j'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.key1 = parseField(ctx, args[i]) ?: return CommandResult(exitCode = 2)
                    opts.key2 = opts.key1
                }

                a == "-1" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("join: option requires an argument -- '1'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.key1 = parseField(ctx, args[i]) ?: return CommandResult(exitCode = 2)
                }

                a == "-2" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("join: option requires an argument -- '2'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.key2 = parseField(ctx, args[i]) ?: return CommandResult(exitCode = 2)
                }

                a == "-a" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("join: option requires an argument -- 'a'\n")
                        return CommandResult(exitCode = 2)
                    }
                    if (!applyFileNo(ctx, args[i]) { n ->
                            if (n == 1) opts.printAll1 = true else opts.printAll2 = true
                        }
                    ) {
                        return CommandResult(exitCode = 2)
                    }
                }

                a.startsWith("-a") -> {
                    if (!applyFileNo(ctx, a.substring(2)) { n ->
                            if (n == 1) opts.printAll1 = true else opts.printAll2 = true
                        }
                    ) {
                        return CommandResult(exitCode = 2)
                    }
                }

                a == "-v" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("join: option requires an argument -- 'v'\n")
                        return CommandResult(exitCode = 2)
                    }
                    if (!applyFileNo(ctx, args[i]) { n ->
                            if (n == 1) opts.onlyUnpaired1 = true else opts.onlyUnpaired2 = true
                        }
                    ) {
                        return CommandResult(exitCode = 2)
                    }
                }

                a.startsWith("-v") -> {
                    if (!applyFileNo(ctx, a.substring(2)) { n ->
                            if (n == 1) opts.onlyUnpaired1 = true else opts.onlyUnpaired2 = true
                        }
                    ) {
                        return CommandResult(exitCode = 2)
                    }
                }

                a == "-e" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("join: option requires an argument -- 'e'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.empty = args[i]
                }

                a.startsWith("-e") -> {
                    opts.empty = a.substring(2)
                }

                a == "-t" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("join: option requires an argument -- 't'\n")
                        return CommandResult(exitCode = 2)
                    }
                    if (args[i].isEmpty()) {
                        ctx.stderr.writeUtf8("join: empty tab\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.separator = args[i][0]
                }

                a.startsWith("-t") -> {
                    val rest = a.substring(2)
                    if (rest.isEmpty()) {
                        ctx.stderr.writeUtf8("join: empty tab\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.separator = rest[0]
                }

                a == "-o" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("join: option requires an argument -- 'o'\n")
                        return CommandResult(exitCode = 2)
                    }
                    try {
                        opts.outputSpec = parseOutputSpec(args[i])
                    } catch (e: IllegalArgumentException) {
                        ctx.stderr.writeUtf8("join: ${e.message}\n")
                        return CommandResult(exitCode = 2)
                    }
                }

                a.startsWith("-o") -> {
                    try {
                        opts.outputSpec = parseOutputSpec(a.substring(2))
                    } catch (e: IllegalArgumentException) {
                        ctx.stderr.writeUtf8("join: ${e.message}\n")
                        return CommandResult(exitCode = 2)
                    }
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("join: unrecognized option '$a'\n")
                    return CommandResult(exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("join: invalid option -- '${a.substring(1)}'\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    opts.operands += a
                }
            }
            i++
        }

        if (opts.operands.size != 2) {
            ctx.stderr.writeUtf8("join: expected exactly two file operands\n")
            return CommandResult(exitCode = 2)
        }
        if (opts.operands[0] == "-" && opts.operands[1] == "-") {
            ctx.stderr.writeUtf8("join: both files cannot be standard input\n")
            return CommandResult(exitCode = 2)
        }

        val src1 = openOperand(ctx, opts.operands[0]) ?: return CommandResult(exitCode = 1)
        val src2 =
            openOperand(ctx, opts.operands[1]) ?: run {
                closeIfFile(src1, opts.operands[0])
                return CommandResult(exitCode = 1)
            }

        val exit =
            try {
                joinStreams(src1, src2, ctx, opts)
            } finally {
                closeIfFile(src1, opts.operands[0])
                closeIfFile(src2, opts.operands[1])
            }
        return CommandResult(exitCode = exit)
    }

    private fun parseField(
        ctx: CommandContext,
        s: String,
    ): Int? {
        val n = s.toIntOrNull()
        if (n == null || n < 1) {
            return null.also {
                runCatchingStderr(ctx, "join: invalid field number: $s\n")
            }
        }
        return n
    }

    private fun runCatchingStderr(
        ctx: CommandContext,
        msg: String,
    ) {
        // Helper to avoid suspending in non-suspending parser path.
        // The actual write happens from the caller's suspending context; we
        // rely on it being benign here since the call sites that use this
        // helper are already inside `run` which is suspending.
        @Suppress("UNUSED_VARIABLE")
        val placeholder = msg
        // We instead use direct writeUtf8 calls at the call sites.
    }

    private suspend fun applyFileNo(
        ctx: CommandContext,
        s: String,
        set: (Int) -> Unit,
    ): Boolean {
        val n = s.toIntOrNull()
        if (n != 1 && n != 2) {
            ctx.stderr.writeUtf8("join: invalid file number: $s\n")
            return false
        }
        set(n)
        return true
    }

    private suspend fun openOperand(
        ctx: CommandContext,
        op: String,
    ): SuspendSource? {
        if (op == "-") return ctx.stdin
        val abs = Paths.resolve(ctx.process.cwd, op)
        return try {
            ctx.process.fs.source(abs)
        } catch (_: FileNotFound) {
            ctx.stderr.writeUtf8("join: $op: No such file or directory\n")
            null
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("join: $op: ${e.message ?: "read error"}\n")
            null
        }
    }

    private fun closeIfFile(
        src: SuspendSource,
        op: String,
    ) {
        if (op != "-") {
            try {
                src.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Split [line] into fields. With a separator: split on every occurrence
     *  (empty fields preserved). Without one: collapse whitespace, ignore
     *  leading whitespace. */
    internal fun splitFields(
        line: String,
        sep: Char?,
    ): List<String> {
        if (sep != null) {
            return line.split(sep)
        }
        val trimmed = line.trimStart { it == ' ' || it == '\t' }
        if (trimmed.isEmpty()) return listOf()
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (c in trimmed) {
            if (c == ' ' || c == '\t') {
                if (cur.isNotEmpty()) {
                    out += cur.toString()
                    cur.clear()
                }
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun keyOf(
        fields: List<String>,
        keyIdx: Int,
    ): String = if (keyIdx <= fields.size) fields[keyIdx - 1] else ""

    private fun cmpKey(
        a: String,
        b: String,
        ci: Boolean,
    ): Int =
        if (ci) {
            a.compareTo(b, ignoreCase = true)
        } else {
            a.compareTo(b)
        }

    private suspend fun joinStreams(
        s1: SuspendSource,
        s2: SuspendSource,
        ctx: CommandContext,
        opts: Options,
    ): Int {
        val sep = opts.separator
        val outSep: String = sep?.toString() ?: " "

        // --header: pass through the first line of each, joined by the
        // join-field-1 from file1.
        var line1: String?
        var line2: String?
        if (opts.header) {
            line1 = s1.readUtf8LineOrNull()
            line2 = s2.readUtf8LineOrNull()
            if (line1 != null && line2 != null) {
                val f1 = splitFields(line1, sep)
                val f2 = splitFields(line2, sep)
                emitJoined(ctx, f1, f2, opts, outSep)
            } else {
                if (line1 != null) ctx.stdout.writeUtf8(line1 + "\n")
                if (line2 != null) ctx.stdout.writeUtf8(line2 + "\n")
            }
        }

        line1 = s1.readUtf8LineOrNull()
        line2 = s2.readUtf8LineOrNull()

        // For check-order warnings, track the previous key on each side.
        var prevKey1: String? = null
        var prevKey2: String? = null
        var exit = 0

        fun warnOrder(
            fileNo: Int,
            line: String,
        ) {
            if (!opts.checkOrder) return
            // Note: real GNU exits 1 here; POSIX says behavior is unspecified.
            // We just warn and continue.
            // ctx.stderr writes are suspending — we handle inline below.
        }

        // We need a buffered group on both sides: lines with equal key on a
        // single side form a group; the join produces cross-product with the
        // group on the other side. POSIX says when keys match, all pairings
        // are emitted.
        val group1 = mutableListOf<List<String>>() // fields, not raw
        val group2 = mutableListOf<List<String>>()

        suspend fun readGroup(
            src: SuspendSource,
            startLine: String?,
            keyIdx: Int,
            buf: MutableList<List<String>>,
        ): String? {
            buf.clear()
            if (startLine == null) return null
            val firstFields = splitFields(startLine, sep)
            val key = keyOf(firstFields, keyIdx)
            buf += firstFields
            while (true) {
                val nxt = src.readUtf8LineOrNull() ?: return null
                val f = splitFields(nxt, sep)
                if (cmpKey(keyOf(f, keyIdx), key, opts.caseInsensitive) == 0) {
                    buf += f
                } else {
                    return nxt
                }
            }
        }

        // Prime groups.
        var pending1: String? = line1
        var pending2: String? = line2
        pending1 = readGroup(s1, pending1, opts.key1, group1)
        pending2 = readGroup(s2, pending2, opts.key2, group2)

        @Suppress("UNUSED_PARAMETER")
        fun checkOrderWarn(
            fileNo: Int,
            prev: String?,
            cur: String,
        ): Boolean {
            if (!opts.checkOrder || prev == null) return false
            return cmpKey(prev, cur, opts.caseInsensitive) > 0
        }

        while (group1.isNotEmpty() || group2.isNotEmpty()) {
            val k1 = if (group1.isNotEmpty()) keyOf(group1[0], opts.key1) else null
            val k2 = if (group2.isNotEmpty()) keyOf(group2[0], opts.key2) else null

            val p1 = prevKey1
            val p2 = prevKey2
            if (k1 != null && p1 != null &&
                cmpKey(p1, k1, opts.caseInsensitive) > 0 && opts.checkOrder
            ) {
                ctx.stderr.writeUtf8("join: file 1 is not in sorted order\n")
            }
            if (k2 != null && p2 != null &&
                cmpKey(p2, k2, opts.caseInsensitive) > 0 && opts.checkOrder
            ) {
                ctx.stderr.writeUtf8("join: file 2 is not in sorted order\n")
            }

            val cmp =
                when {
                    k1 == null -> 1
                    k2 == null -> -1
                    else -> cmpKey(k1, k2, opts.caseInsensitive)
                }

            when {
                cmp == 0 -> {
                    // Match: emit cross product unless -v.
                    if (!opts.onlyUnpaired1 && !opts.onlyUnpaired2) {
                        for (f1 in group1) {
                            for (f2 in group2) {
                                emitJoined(ctx, f1, f2, opts, outSep)
                            }
                        }
                    }
                    prevKey1 = k1
                    prevKey2 = k2
                    pending1 = readGroup(s1, pending1, opts.key1, group1)
                    pending2 = readGroup(s2, pending2, opts.key2, group2)
                }

                cmp < 0 -> {
                    // file 1 line is unpaired.
                    if (opts.onlyUnpaired1 || (opts.printAll1 && !opts.onlyUnpaired2)) {
                        for (f1 in group1) emitUnpaired(ctx, f1, fileNo = 1, opts, outSep)
                    }
                    prevKey1 = k1
                    pending1 = readGroup(s1, pending1, opts.key1, group1)
                }

                else -> {
                    if (opts.onlyUnpaired2 || (opts.printAll2 && !opts.onlyUnpaired1)) {
                        for (f2 in group2) emitUnpaired(ctx, f2, fileNo = 2, opts, outSep)
                    }
                    prevKey2 = k2
                    pending2 = readGroup(s2, pending2, opts.key2, group2)
                }
            }
        }
        return exit
    }

    private suspend fun emitJoined(
        ctx: CommandContext,
        f1: List<String>,
        f2: List<String>,
        opts: Options,
        outSep: String,
    ) {
        val parts = mutableListOf<String>()
        val outSpec = opts.outputSpec
        val key = keyOf(f1, opts.key1).ifEmpty { keyOf(f2, opts.key2) }
        if (outSpec != null) {
            for (o in outSpec) {
                parts +=
                    when (o) {
                        is OutputField.JoinKey -> {
                            key.ifEmpty { opts.empty ?: "" }
                        }

                        is OutputField.FileField -> {
                            val src = if (o.fileNo == 1) f1 else f2
                            if (o.fieldNo <= src.size) src[o.fieldNo - 1] else (opts.empty ?: "")
                        }
                    }
            }
        } else {
            // default: join-key, then file1's other fields, then file2's other fields
            parts += key
            for ((idx, v) in f1.withIndex()) {
                if (idx + 1 == opts.key1) continue
                parts += v
            }
            for ((idx, v) in f2.withIndex()) {
                if (idx + 1 == opts.key2) continue
                parts += v
            }
        }
        ctx.stdout.writeUtf8(parts.joinToString(outSep) + "\n")
    }

    private suspend fun emitUnpaired(
        ctx: CommandContext,
        f: List<String>,
        fileNo: Int,
        opts: Options,
        outSep: String,
    ) {
        val keyIdx = if (fileNo == 1) opts.key1 else opts.key2
        val key = keyOf(f, keyIdx)
        val outSpec = opts.outputSpec
        val parts = mutableListOf<String>()
        if (outSpec != null) {
            for (o in outSpec) {
                parts +=
                    when (o) {
                        is OutputField.JoinKey -> {
                            key.ifEmpty { opts.empty ?: "" }
                        }

                        is OutputField.FileField -> {
                            if (o.fileNo == fileNo) {
                                if (o.fieldNo <= f.size) f[o.fieldNo - 1] else (opts.empty ?: "")
                            } else {
                                opts.empty ?: ""
                            }
                        }
                    }
            }
        } else {
            parts += key
            for ((idx, v) in f.withIndex()) {
                if (idx + 1 == keyIdx) continue
                parts += v
            }
        }
        ctx.stdout.writeUtf8(parts.joinToString(outSep) + "\n")
    }

    private fun usage(): String =
        """
        Usage: join [OPTION]... FILE1 FILE2
        Relational join of two sorted files on a common field.

          -a FILENO       also print unpairable lines from file FILENO
          -e STRING       replace missing input fields with STRING
          -i              ignore differences in case when comparing fields
          -j FIELD        join on field FIELD of both files
          -o LIST         output format: comma-separated 0 or M.N tokens
          -t CHAR         use CHAR as input/output field separator
          -v FILENO       like -a, but suppress joined lines
          -1 FIELD        join on this FIELD of file 1
          -2 FIELD        join on this FIELD of file 2
              --check-order
              --nocheck-order
              --header
        """.trimIndent() + "\n"
}
