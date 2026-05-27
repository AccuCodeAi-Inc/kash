package com.accucodeai.kash.tools.dd

/**
 * Parsed `dd` operands. `dd` takes `name=value` pairs rather than POSIX
 * short flags. Unspecified numeric operands default per POSIX: ibs/obs=512,
 * cbs=0, skip/seek/count=null (no limit).
 */
public data class DdOperands(
    val input: String? = null,
    val output: String? = null,
    val ibs: Long = 512L,
    val obs: Long = 512L,
    val cbs: Long = 0L,
    val skip: Long = 0L,
    val seek: Long = 0L,
    val count: Long? = null,
    val conv: Set<DdConvFlag> = emptySet(),
    val iflag: Set<DdIoFlag> = emptySet(),
    val oflag: Set<DdIoFlag> = emptySet(),
    val status: DdStatus = DdStatus.DEFAULT,
)

public enum class DdConvFlag {
    LCASE,
    UCASE,
    SWAB,
    BLOCK,
    UNBLOCK,
    SYNC,
    NOERROR,
    NOTRUNC,
    ASCII,
    EBCDIC,
}

public enum class DdIoFlag {
    APPEND,
    NONBLOCK,
    SYNC,
    FULLBLOCK,
}

public enum class DdStatus {
    DEFAULT,
    NONE,
    NOXFER,
    PROGRESS,
}

public class DdOperandException(
    message: String,
) : RuntimeException(message)

/** Parse the full `dd` argv list into [DdOperands]. */
public fun parseDdOperands(args: List<String>): DdOperands {
    var ops = DdOperands()
    for (arg in args) {
        if (arg == "--help" || arg == "--version") {
            // handled by caller; parser just leaves these alone
            throw DdOperandException("__meta__:$arg")
        }
        val eq = arg.indexOf('=')
        if (eq <= 0) throw DdOperandException("unrecognized operand '$arg'")
        val name = arg.substring(0, eq)
        val value = arg.substring(eq + 1)
        ops =
            when (name) {
                "if" -> {
                    ops.copy(input = value)
                }

                "of" -> {
                    ops.copy(output = value)
                }

                "ibs" -> {
                    ops.copy(ibs = requirePositive(name, parseSize(value)))
                }

                "obs" -> {
                    ops.copy(obs = requirePositive(name, parseSize(value)))
                }

                "bs" -> {
                    val n = requirePositive(name, parseSize(value))
                    ops.copy(ibs = n, obs = n)
                }

                "cbs" -> {
                    ops.copy(cbs = requireNonNegative(name, parseSize(value)))
                }

                "skip" -> {
                    ops.copy(skip = requireNonNegative(name, parseSize(value)))
                }

                "seek" -> {
                    ops.copy(seek = requireNonNegative(name, parseSize(value)))
                }

                "count" -> {
                    ops.copy(count = requireNonNegative(name, parseSize(value)))
                }

                "conv" -> {
                    ops.copy(conv = ops.conv + parseConvList(value))
                }

                "iflag" -> {
                    ops.copy(iflag = ops.iflag + parseIoFlagList(value))
                }

                "oflag" -> {
                    ops.copy(oflag = ops.oflag + parseIoFlagList(value))
                }

                "status" -> {
                    ops.copy(status = parseStatus(value))
                }

                else -> {
                    throw DdOperandException("unrecognized operand '$name'")
                }
            }
    }
    return ops
}

private fun requirePositive(
    name: String,
    v: Long,
): Long {
    if (v <= 0) throw DdOperandException("invalid number for '$name': must be > 0")
    return v
}

private fun requireNonNegative(
    name: String,
    v: Long,
): Long {
    if (v < 0) throw DdOperandException("invalid number for '$name': must be >= 0")
    return v
}

/**
 * Parse a `dd` numeric value. Accepts:
 *  - bare integer (decimal, octal `0NNN`, hex `0xNN`)
 *  - suffix `c`=1, `w`=2, `b`=512
 *  - binary suffixes `k`/`K`=1024, `M`=1024^2, `G`=1024^3, `T`=1024^4
 *  - decimal suffixes `kB`=1000, `MB`=1000^2, `GB`=1000^3, `TB`=1000^4
 *  - product `NxM` (recursively parsed) yielding N*M
 */
public fun parseSize(raw: String): Long {
    if (raw.isEmpty()) throw DdOperandException("empty numeric value")
    // Product form: split on 'x' (every part must itself parse).
    // Hex literals (`0xNN`) use 'x' as a base prefix, not a multiplication
    // separator — exclude them here.
    val isHexLiteral = raw.startsWith("0x") || raw.startsWith("0X")
    if (!isHexLiteral && raw.contains('x')) {
        var product = 1L
        for (part in raw.split('x')) {
            if (part.isEmpty()) throw DdOperandException("invalid number '$raw'")
            product = checkedMul(product, parseSizeSingle(part), raw)
        }
        return product
    }
    return parseSizeSingle(raw)
}

private fun parseSizeSingle(raw: String): Long {
    // Hex numbers (`0xNN`) have no suffix in `dd` — strip none.
    val isHex =
        raw.startsWith("0x") || raw.startsWith("0X") ||
            (raw.startsWith("-") && (raw.startsWith("-0x") || raw.startsWith("-0X")))
    var i = raw.length
    if (!isHex) {
        while (i > 0 && raw[i - 1].isLetter()) i--
    }
    val numPart = raw.substring(0, i)
    val suffix = raw.substring(i)
    if (numPart.isEmpty()) throw DdOperandException("invalid number '$raw'")
    val base: Long =
        try {
            when {
                numPart.startsWith("0x") || numPart.startsWith("0X") -> numPart.substring(2).toLong(16)
                numPart.length > 1 && numPart.startsWith("0") -> numPart.toLong(8)
                else -> numPart.toLong()
            }
        } catch (_: NumberFormatException) {
            throw DdOperandException("invalid number '$raw'")
        }
    val mult: Long =
        when (suffix) {
            "" -> 1L
            "c" -> 1L
            "w" -> 2L
            "b" -> 512L
            "k", "K" -> 1024L
            "M" -> 1024L * 1024L
            "G" -> 1024L * 1024L * 1024L
            "T" -> 1024L * 1024L * 1024L * 1024L
            "kB", "KB" -> 1000L
            "MB" -> 1000L * 1000L
            "GB" -> 1000L * 1000L * 1000L
            "TB" -> 1000L * 1000L * 1000L * 1000L
            else -> throw DdOperandException("invalid suffix '$suffix' in '$raw'")
        }
    return checkedMul(base, mult, raw)
}

private fun checkedMul(
    a: Long,
    b: Long,
    raw: String,
): Long {
    if (a == 0L || b == 0L) return 0L
    val r = a * b
    if (r / b != a) throw DdOperandException("number overflow: '$raw'")
    return r
}

private fun parseConvList(value: String): Set<DdConvFlag> {
    if (value.isEmpty()) return emptySet()
    val set = mutableSetOf<DdConvFlag>()
    for (item in value.split(',')) {
        if (item.isEmpty()) continue
        val flag =
            when (item) {
                "lcase" -> DdConvFlag.LCASE
                "ucase" -> DdConvFlag.UCASE
                "swab" -> DdConvFlag.SWAB
                "block" -> DdConvFlag.BLOCK
                "unblock" -> DdConvFlag.UNBLOCK
                "sync" -> DdConvFlag.SYNC
                "noerror" -> DdConvFlag.NOERROR
                "notrunc" -> DdConvFlag.NOTRUNC
                "ascii" -> DdConvFlag.ASCII
                "ebcdic" -> DdConvFlag.EBCDIC
                else -> throw DdOperandException("invalid conversion '$item'")
            }
        set += flag
    }
    if (DdConvFlag.LCASE in set && DdConvFlag.UCASE in set) {
        throw DdOperandException("conv=lcase and conv=ucase are mutually exclusive")
    }
    if (DdConvFlag.BLOCK in set && DdConvFlag.UNBLOCK in set) {
        throw DdOperandException("conv=block and conv=unblock are mutually exclusive")
    }
    return set
}

private fun parseIoFlagList(value: String): Set<DdIoFlag> {
    if (value.isEmpty()) return emptySet()
    val set = mutableSetOf<DdIoFlag>()
    for (item in value.split(',')) {
        if (item.isEmpty()) continue
        val flag =
            when (item) {
                "append" -> DdIoFlag.APPEND
                "nonblock" -> DdIoFlag.NONBLOCK
                "sync" -> DdIoFlag.SYNC
                "fullblock" -> DdIoFlag.FULLBLOCK
                else -> throw DdOperandException("invalid flag '$item'")
            }
        set += flag
    }
    return set
}

private fun parseStatus(value: String): DdStatus =
    when (value) {
        "none" -> DdStatus.NONE
        "noxfer" -> DdStatus.NOXFER
        "progress" -> DdStatus.PROGRESS
        else -> throw DdOperandException("invalid status '$value'")
    }
