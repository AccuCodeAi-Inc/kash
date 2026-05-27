package com.accucodeai.kash.tools.openssl

import dev.whyoleg.cryptography.CryptographyProvider

/**
 * Platform-specific CryptographyProvider for the openssl tool.
 *
 * JVM → `CryptographyProvider.JDK` (JCA-backed).
 * wasmJs → `CryptographyProvider.WebCrypto` (SubtleCrypto-backed).
 *
 * Explicit rather than `CryptographyProvider.Default` so the choice is
 * deterministic and doesn't depend on ServiceLoader/classpath ordering.
 */
internal expect fun cryptographyProvider(): CryptographyProvider
