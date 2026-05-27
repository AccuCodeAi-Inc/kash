package com.accucodeai.kash.api.binfmt

/**
 * Refuses to execute native (host-OS) binaries with a friendly diagnostic.
 *
 * kash is a JVM-hosted VM with no `mmap`/`execve` plumbing for host binaries:
 * an ELF/Mach-O/PE file in the [com.accucodeai.kash.fs.FileSystem] is data,
 * not code. Without this handler the shell-script fallback would try to
 * parse the binary's magic bytes as a kash script and emit confusing
 * syntax errors. Instead we sniff the magic, claim the format, and exit
 * 126 with `"$path: kash is a VM and does not execute native code (would
 * have run as $format)"`.
 *
 * Priority 30: runs after the shebang and kash-tool fast paths (so `#!`
 * scripts and synthetic ToolsFs entries take precedence) and before any
 * userspace CommandSpec-contributed handlers or the shell-script fallback.
 */
public class BinfmtNativeReject : BinfmtHandler {
    override val name: String = "native-reject"
    override val priority: Int = 30

    override suspend fun tryExec(req: ExecRequest): ExecOutcome {
        val format = sniff(req.headPeek) ?: return ExecOutcome.NotMine
        return ExecOutcome.Refused(
            exitCode = 126,
            message = "${req.path}: kash is a VM and does not execute native code (would have run as $format)",
        )
    }

    private fun sniff(head: ByteArray): String? {
        if (head.size < 4) {
            // PE has a 2-byte magic so accept that too.
            if (head.size >= 2 && head[0] == 0x4D.toByte() && head[1] == 0x5A.toByte()) return "PE/COFF (Windows)"
            return null
        }
        val b0 = head[0]
        val b1 = head[1]
        val b2 = head[2]
        val b3 = head[3]
        // ELF: 7F 45 4C 46
        if (b0 == 0x7F.toByte() && b1 == 0x45.toByte() && b2 == 0x4C.toByte() && b3 == 0x46.toByte()) {
            return "ELF (Linux/BSD)"
        }
        // Mach-O 32/64 (both endians) and fat binaries
        if (b0 == 0xCF.toByte() && b1 == 0xFA.toByte() && b2 == 0xED.toByte() &&
            b3 == 0xFE.toByte()
        ) {
            return "Mach-O 64-bit"
        }
        if (b0 == 0xCE.toByte() && b1 == 0xFA.toByte() && b2 == 0xED.toByte() &&
            b3 == 0xFE.toByte()
        ) {
            return "Mach-O 32-bit"
        }
        if (b0 == 0xFE.toByte() && b1 == 0xED.toByte() && b2 == 0xFA.toByte() &&
            b3 == 0xCF.toByte()
        ) {
            return "Mach-O 64-bit (BE)"
        }
        if (b0 == 0xFE.toByte() && b1 == 0xED.toByte() && b2 == 0xFA.toByte() &&
            b3 == 0xCE.toByte()
        ) {
            return "Mach-O 32-bit (BE)"
        }
        if (b0 == 0xCA.toByte() && b1 == 0xFE.toByte() && b2 == 0xBA.toByte() &&
            b3 == 0xBE.toByte()
        ) {
            return "Mach-O universal (fat)"
        }
        if (b0 == 0xBE.toByte() && b1 == 0xBA.toByte() && b2 == 0xFE.toByte() &&
            b3 == 0xCA.toByte()
        ) {
            return "Mach-O universal (fat, BE)"
        }
        // PE: 'MZ' DOS header
        if (b0 == 0x4D.toByte() && b1 == 0x5A.toByte()) return "PE/COFF (Windows)"
        return null
    }
}
