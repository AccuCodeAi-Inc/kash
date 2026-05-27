package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8DelimitedOrNull
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.commands.processReadBackslashes
import com.accucodeai.kash.commands.splitForRead
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.time.Duration.Companion.milliseconds

/**
 * POSIX `read [-r] [-u FD] [-d DELIM] [-a NAME] [VAR ...]`. Reads one
 * record from stdin (or fd `-u`), splits on `$IFS`, assigns fields to
 * the named variables. Bash extensions implemented:
 *   `-d DELIM` — finish on the first byte of DELIM instead of newline.
 *               Empty DELIM means NUL (`-d ''` / `-d $'\0'`).
 *   `-a NAME`  — write every IFS-split field into indexed array NAME
 *               starting at index 0; positional VAR args are ignored.
 *
 * Lives as an intrinsic (rather than a registered Command) because
 * `-a` needs to mutate [indexedArrays], which the Command API doesn't
 * expose. Reuses the existing splitForRead/processReadBackslashes
 * helpers from `commands/Builtins.kt`.
 */
internal suspend fun Interpreter.runReadIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var rawMode = false
    var fd: Int? = null
    var delim: Byte = '\n'.code.toByte()
    var arrayName: String? = null
    var nChars: Int? = null
    var prompt: String? = null
    var timeoutSec: Double? = null
    val vars = mutableListOf<String>()
    // Parallel to [vars]: the index into [args] each var name came from, so
    // we can consult [currentArrayRefArgs] (was-it-an-unquoted-array-reference)
    // to decide single-key subscript parsing under assoc_expand_once.
    val varArgIdx = mutableListOf<Int>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--" -> {
                for (j in (i + 1) until args.size) {
                    vars += args[j]
                    varArgIdx += j
                }
                i = args.size
                break
            }

            a.startsWith("-") && a.length > 1 -> {
                var k = 1
                while (k < a.length) {
                    when (a[k]) {
                        'r' -> {
                            rawMode = true
                        }

                        'u' -> {
                            val tail = a.substring(k + 1)
                            val nStr =
                                if (tail.isNotEmpty()) {
                                    tail
                                } else {
                                    i++
                                    args.getOrNull(i) ?: run {
                                        stdio.stderr.writeUtf8("read: -u: option requires an argument\n")
                                        return 2
                                    }
                                }
                            fd = nStr.toIntOrNull() ?: run {
                                stdio.stderr.writeUtf8("read: $nStr: invalid file descriptor specification\n")
                                return 1
                            }
                            k = a.length
                        }

                        'd' -> {
                            // `-d X` takes the first byte of the operand;
                            // `-d ''` (empty) means NUL. Operand can be
                            // glued (`-dX`) or the next arg (`-d X`).
                            val tail = a.substring(k + 1)
                            val d =
                                if (tail.isNotEmpty()) {
                                    tail
                                } else {
                                    i++
                                    args.getOrNull(i) ?: run {
                                        stdio.stderr.writeUtf8("read: -d: option requires an argument\n")
                                        return 2
                                    }
                                }
                            delim = if (d.isEmpty()) 0 else d[0].code.toByte()
                            k = a.length
                        }

                        'a' -> {
                            val tail = a.substring(k + 1)
                            arrayName =
                                if (tail.isNotEmpty()) {
                                    tail
                                } else {
                                    i++
                                    args.getOrNull(i) ?: run {
                                        stdio.stderr.writeUtf8("read: -a: option requires an argument\n")
                                        return 2
                                    }
                                }
                            k = a.length
                        }

                        'n', 'N' -> {
                            val tail = a.substring(k + 1)
                            val nStr =
                                if (tail.isNotEmpty()) {
                                    tail
                                } else {
                                    i++
                                    args.getOrNull(i) ?: run {
                                        stdio.stderr.writeUtf8("read: -${a[k]}: option requires an argument\n")
                                        return 2
                                    }
                                }
                            nChars = nStr.toIntOrNull() ?: run {
                                stdio.stderr.writeUtf8("read: $nStr: invalid number\n")
                                return 1
                            }
                            k = a.length
                        }

                        'p' -> {
                            val tail = a.substring(k + 1)
                            prompt =
                                if (tail.isNotEmpty()) {
                                    tail
                                } else {
                                    i++
                                    args.getOrNull(i) ?: run {
                                        stdio.stderr.writeUtf8("read: -p: option requires an argument\n")
                                        return 2
                                    }
                                }
                            k = a.length
                        }

                        't' -> {
                            val tail = a.substring(k + 1)
                            val tStr =
                                if (tail.isNotEmpty()) {
                                    tail
                                } else {
                                    i++
                                    args.getOrNull(i) ?: run {
                                        stdio.stderr.writeUtf8("read: -t: option requires an argument\n")
                                        return 2
                                    }
                                }
                            timeoutSec = tStr.toDoubleOrNull() ?: run {
                                stdio.stderr.writeUtf8("read: $tStr: invalid timeout specification\n")
                                return 1
                            }
                            k = a.length
                        }

                        else -> {
                            stdio.stderr.writeUtf8("read: -${a[k]}: invalid option\n")
                            return 2
                        }
                    }
                    k++
                }
            }

            else -> {
                vars += a
                varArgIdx += i
            }
        }
        i++
    }
    if (arrayName == null && vars.isEmpty()) vars += "REPLY"
    // `read -a NAME` requires NAME to be (or become) an indexed array.
    // If NAME already exists as an associative array, bash refuses with
    // "read: NAME: not an indexed array" before touching stdin.
    if (arrayName != null && varTable.find(arrayName)?.isAssoc == true) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}read: $arrayName: not an indexed array\n")
        return 2
    }

    val src: SuspendSource =
        if (fd == null) {
            stdio.stdin
        } else {
            val entry = process.fdTable[fd]
            if (entry?.ofd?.source == null) {
                stdio.stderr.writeUtf8("read: $fd: invalid file descriptor: Bad file descriptor\n")
                return 1
            }
            entry.ofd.source!!
        }

    // `-p PROMPT`: write to stderr only when stdin is a tty (bash compat).
    if (prompt != null && stdio.stdinIsTty && fd == null) {
        stdio.stderr.writeUtf8(prompt)
    }

    suspend fun doRead(): String? =
        if (nChars != null) {
            readUpToNCharsOrDelim(src, nChars, delim)
        } else if (delim == '\n'.code.toByte()) {
            src.readUtf8LineOrNull()
        } else {
            src.readUtf8DelimitedOrNull(delim)
        }

    // EOF returns 1 even if some data was read; the field assignments
    // still fire so `read -d '' x; echo "$x"` captures the partial.
    // On *immediate* EOF (no data at all), bash clears the named
    // variables to empty strings before returning 1.
    // `-t TIMEOUT`: if elapsed before any data, bash exits >128.
    val rawLine =
        if (timeoutSec != null) {
            val ms = (timeoutSec * 1000.0).toLong().coerceAtLeast(0L)
            // Box doRead's `String?` so we can tell timeout (outer null) from EOF
            // (inner null) without collapsing them via the natural `String??`.
            val boxed = withTimeoutOrNull(ms.milliseconds) { Boxed(doRead()) }
            if (boxed == null) {
                if (arrayName != null) {
                    indexedArrays[arrayName] = mutableMapOf()
                    env.remove(arrayName)
                    varTable.findOrCreate(arrayName).attrs += VarAttr.Indexed
                } else {
                    for (varName in vars) env[varName] = ""
                }
                return 142
            }
            boxed.value
        } else {
            doRead()
        } ?: run {
            if (arrayName != null) {
                indexedArrays[arrayName] = mutableMapOf()
                env.remove(arrayName)
                varTable.findOrCreate(arrayName).attrs += VarAttr.Indexed
            } else {
                for (varName in vars) env[varName] = ""
            }
            return 1
        }
    val line = if (rawMode) rawLine else processReadBackslashes(rawLine)

    val ifs = env["IFS"] ?: " \t\n"

    if (arrayName != null) {
        // Bash: `-a` discards prior contents of the named array and
        // assigns every split field starting at index 0. Unlike named-
        // var read (which always assigns N variables, possibly with
        // empty trailing slot), array read drops the trailing empty
        // slot from splitForRead — bash doesn't materialize an empty
        // element past the last real field.
        val raw = splitForRead(line, ifs, Int.MAX_VALUE)
        val fields =
            if (raw.isNotEmpty() && raw.last().isEmpty() && raw.size > 1) raw.dropLast(1) else raw
        val arr = mutableMapOf<Int, String>()
        for ((idx, f) in fields.withIndex()) arr[idx] = f
        indexedArrays[arrayName] = arr
        env.remove(arrayName)
        varTable.findOrCreate(arrayName).attrs += VarAttr.Indexed
        return 0
    }

    val fields = splitForRead(line, ifs, vars.size)
    for ((idx, varName) in vars.withIndex()) {
        val v = fields.getOrNull(idx) ?: ""
        // bash allows subscripted target names: `read x[1] y[2]` reads
        // one field into each element. Detect `NAME[sub]` and route to
        // the indexed/assoc element write; bare names go through env.
        val lb = varName.indexOf('[')
        val rb = varName.lastIndexOf(']')
        // Bash's subscript parser is quote-aware: in `read NAME[sub]`,
        // the `]` that closes the subscript is the FIRST unquoted `]`
        // after `[`, not the last. So `read A[]]` (subscript is `]`)
        // looks like `A[]` plus trailing `]` to bash — rejected as
        // "A[]]: not a valid identifier". We approximate by checking
        // that the subscript range has no inner `]` (i.e. last == first
        // among the candidates), and conservatively reject the case
        // where the FIRST `]` after `[` is followed by more characters
        // — that's the bash-failing shape.
        //
        // EXCEPTION (greedy single-key): when this target came from an
        // unquoted array-reference word AND assoc_expand_once is set AND the
        // base is an associative array, the post-expansion subscript is a
        // single literal key spanning to the LAST `]` — so `read A[$rk]`
        // with `rk=']'` stores key `]`. The quoted/non-reference forms keep
        // the strict parse above and stay rejected.
        val greedyAssoc =
            lb > 0 && rb == varName.length - 1 &&
                isGreedyAssocRef(varName.substring(0, lb), varArgIdx[idx])
        val firstRb = if (lb > 0) varName.indexOf(']', lb + 1) else -1
        if (lb > 0 && firstRb > 0 && firstRb != rb && !greedyAssoc) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}read: `$varName': not a valid identifier\n")
            return 1
        }
        if (lb > 0 && rb == varName.length - 1) {
            val base = varName.substring(0, lb)
            val sub = varName.substring(lb + 1, rb)
            // Bash's identifier validation rejects unbalanced quote chars
            // in the subscript: `read 'a[80'\''s]'` expands the subscript
            // to `80's`, then bash sees the unmatched `'` and emits
            //   read: `a[80's]': not a valid identifier
            // Same shape for `"` and backslash-escaped chars. Verified
            // bash 5.3.
            //
            // This only fires when `assoc_expand_once` is OFF (the default).
            // With the shopt OFF bash word-expands the arg, then re-expands
            // the subscript a SECOND time inside read; the apostrophe in the
            // already-expanded `80's` is now an unbalanced quote -> invalid.
            // With the shopt ON the subscript is expanded exactly once and
            // `80's` is a perfectly valid key, so the element is stored.
            val singles = sub.count { it == '\'' }
            val doubles = sub.count { it == '"' }
            if ((singles % 2 != 0 || doubles % 2 != 0) &&
                shoptOptions["assoc_expand_once"] != true
            ) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}read: `$varName': not a valid identifier\n")
                return 1
            }
            val isAssoc = varTable.find(base)?.isAssoc == true
            if (isAssoc) {
                setAssocElement(base, sub, v, append = false)
            } else {
                // `array_expand_once` (and bash 5.x security baseline):
                // a subscript text containing literal `$(...)` after the
                // first expansion pass must NOT be re-evaluated — that
                // would let `read a["\$subscript"]` execute embedded
                // command substitutions on a value the user didn't
                // intend as shell code. Bash diagnoses this as an
                // arithmetic syntax error on the literal subscript text.
                if ("\$(" in sub) {
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}$sub: arithmetic syntax error: operand expected (error token is \"$sub\")\n",
                    )
                    return 1
                }
                val arithIdx =
                    sub.toIntOrNull() ?: try {
                        evalArithRaw(sub.ifBlank { "0" }).toInt()
                    } catch (_: Throwable) {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}read: `$varName': not a valid identifier\n")
                        return 1
                    }
                setIndexedElement(base, arithIdx, v, append = false)
            }
        } else {
            env[varName] = v
        }
    }
    return 0
}

private class Boxed<T>(
    val value: T,
)

/**
 * Pull bytes one at a time until [n] decoded code units have been collected, the
 * delimiter is seen, or EOF. Returns the accumulated string (possibly empty if
 * the very first byte was [delim]); null only on immediate EOF with no bytes.
 */
private suspend fun readUpToNCharsOrDelim(
    src: SuspendSource,
    n: Int,
    delim: Byte,
): String? {
    val chunk = Buffer()
    val accum = Buffer()
    var any = false
    var produced = 0
    while (produced < n) {
        val nread = src.readAtMostTo(chunk, 1L)
        if (nread == -1L) break
        any = true
        val b = chunk.readByte()
        if (b == delim) return accum.readString()
        accum.writeByte(b)
        // High-bit-bytes are multi-byte UTF-8 continuations: count only on a
        // leading byte (0xxxxxxx or 11xxxxxx) so `-n N` counts characters, not bytes.
        val u = b.toInt() and 0xFF
        if (u < 0x80 || u >= 0xC0) produced++
    }
    return if (any) accum.readString() else null
}
