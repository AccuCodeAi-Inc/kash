package com.accucodeai.kash.tools.uname

import java.net.InetAddress

internal actual fun unameInfo(): UnameInfo {
    val osName = System.getProperty("os.name") ?: "unknown"
    // Normalize the JVM's os.name into the kernel name `uname -s` reports.
    val sysname =
        when {
            osName.startsWith("Mac", ignoreCase = true) -> "Darwin"
            osName.startsWith("Windows", ignoreCase = true) -> "Windows_NT"
            else -> osName
        }

    val nodename =
        try {
            InetAddress.getLocalHost().hostName
        } catch (_: Throwable) {
            "localhost"
        }

    val release = System.getProperty("os.version") ?: "unknown"

    val arch = System.getProperty("os.arch") ?: "unknown"
    // os.arch uses JVM names; map to the values uname's `-m` conventionally reports.
    val machine =
        when (arch) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "arm64"
            else -> arch
        }

    // `-o`: GNU prints "GNU/Linux" on Linux; elsewhere it mirrors the kernel name.
    val operatingSystem = if (sysname == "Linux") "GNU/Linux" else sysname

    // The JVM exposes no portable processor / hardware-platform string, so we
    // report "unknown" — which `-a` then omits, matching GNU coreutils.
    return UnameInfo(
        sysname = sysname,
        nodename = nodename,
        release = release,
        version = "unknown",
        machine = machine,
        processor = "unknown",
        hardwarePlatform = "unknown",
        operatingSystem = operatingSystem,
    )
}
