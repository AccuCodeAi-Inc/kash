package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio

// POSIX `umask`. Reads or writes the shell's file-creation mask.

/**
 * POSIX `umask [-S] [mask]`:
 *   - Bare `umask` prints the current mask in octal as a zero-padded 4-digit
 *     value (`0022`).
 *   - `umask -S` prints the symbolic equivalent of `~mask` — i.e. the
 *     permissions a newly-created file *would* get (`u=rwx,g=rx,o=rx`).
 *   - `umask MASK` sets the mask. MASK is either an octal value (`022`,
 *     `0o22`, `27`) or a symbolic clause list (`u=rwx,g=rx,o=`).
 */
internal suspend fun Interpreter.runUmaskIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var symbolicOutput = false
    var reusableOutput = false
    var i = 0
    while (i < args.size && args[i].startsWith("-") && args[i] != "-") {
        when (val a = args[i]) {
            "--" -> {
                i++
                break
            }

            "-S" -> {
                symbolicOutput = true
            }

            "-p" -> {
                // GNU `umask -p`: when printing (no operand), emit as a
                // re-readable `umask <mask>` command. The `-S` flag still
                // controls whether the payload is octal or symbolic.
                reusableOutput = true
            }

            else -> {
                stdio.stderr.writeUtf8("umask: $a: invalid option\n")
                return 2
            }
        }
        i++
    }
    val operand = args.drop(i).firstOrNull()
    if (operand == null) {
        // Print
        val payload = if (symbolicOutput) formatSymbolic(umask) else formatOctal(umask)
        if (reusableOutput) {
            stdio.stdout.writeUtf8("umask${if (symbolicOutput) " -S" else ""} $payload\n")
        } else {
            stdio.stdout.writeUtf8(payload + "\n")
        }
        return 0
    }
    val newMask = parseMask(operand, umask)
    if (newMask == null) {
        stdio.stderr.writeUtf8("umask: $operand: invalid mode\n")
        return 1
    }
    umask = newMask and 0xFFF
    return 0
}

private fun formatOctal(mask: Int): String {
    // 4 digits to make the leading zero unambiguous, matching bash.
    val s = mask.toString(8)
    return "0".repeat((4 - s.length).coerceAtLeast(0)) + s
}

/**
 * Render the *permissions a freshly-created file gets* (`~mask`, masked to
 * the rwx bits) as `u=...,g=...,o=...`. Bash's `umask -S` format.
 */
private fun formatSymbolic(mask: Int): String {
    val perms = mask.inv() and 0b111_111_111

    fun trio(shift: Int): String {
        val bits = (perms shr shift) and 0b111
        val sb = StringBuilder()
        if (bits and 0b100 != 0) sb.append('r')
        if (bits and 0b010 != 0) sb.append('w')
        if (bits and 0b001 != 0) sb.append('x')
        return sb.toString()
    }
    return "u=${trio(6)},g=${trio(3)},o=${trio(0)}"
}

/**
 * Parse [operand] as either:
 *   - Octal: optional leading `0`, `0o`, `0O`, then digits 0–7. Up to 4
 *     digits (high bit ignored beyond 12).
 *   - Symbolic clauses: `who op perm[,who op perm]…` where `who ∈ {u,g,o,a}`,
 *     `op ∈ {+,-,=}`, `perm` is any subset of `rwxXstugo` (we honor `rwx`
 *     and treat the others as ignored). Each clause modifies a working copy
 *     of [current]; we return the *new mask* (i.e. the bits to FORBID).
 *
 * Returns null on parse failure.
 */
private fun parseMask(
    operand: String,
    current: Int,
): Int? {
    if (operand.isEmpty()) return null
    // Octal: looks like all digits 0-7 (with optional 0/0o prefix).
    val maybeOctal =
        when {
            operand.startsWith("0o") || operand.startsWith("0O") -> operand.substring(2)
            else -> operand
        }
    if (maybeOctal.isNotEmpty() && maybeOctal.all { it in '0'..'7' }) {
        return maybeOctal.toIntOrNull(8)?.and(0xFFF)
    }
    // Symbolic. Start from `current` (POSIX: clauses modify the EXISTING
    // mask, they don't reset). We work in PERMISSION space (allowed bits =
    // ~mask) because the symbolic ops describe permissions, then invert at
    // the end.
    var perms = current.inv() and 0b111_111_111
    for (clause in operand.split(',')) {
        if (clause.isEmpty()) return null
        var k = 0
        // Parse who-list. Empty → 'a' (POSIX).
        var who = 0
        while (k < clause.length && clause[k] in "ugoa") {
            who =
                who or
                when (clause[k]) {
                    'u' -> 0b111_000_000
                    'g' -> 0b000_111_000
                    'o' -> 0b000_000_111
                    'a' -> 0b111_111_111
                    else -> 0
                }
            k++
        }
        if (who == 0) who = 0b111_111_111
        if (k >= clause.length) return null
        val op = clause[k]
        k++
        if (op !in "+-=") return null
        // Parse perm. Empty allowed (means "no bits" — for `=`, that clears).
        var perm = 0
        while (k < clause.length) {
            when (clause[k]) {
                'r' -> {
                    perm = perm or 0b100_100_100
                }

                'w' -> {
                    perm = perm or 0b010_010_010
                }

                'x' -> {
                    perm = perm or 0b001_001_001
                }

                'X', 's', 't', 'u', 'g', 'o' -> { /* recognized, not modeled */ }

                else -> {
                    return null
                }
            }
            k++
        }
        val masked = perm and who
        perms =
            when (op) {
                '+' -> perms or masked
                '-' -> perms and masked.inv()
                '=' -> (perms and who.inv()) or masked
                else -> perms
            }
    }
    return perms.inv() and 0b111_111_111
}
