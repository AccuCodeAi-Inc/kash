package com.accucodeai.kash.tools.openssl

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

internal actual fun cryptographyProvider(): CryptographyProvider = CryptographyProvider.JDK
