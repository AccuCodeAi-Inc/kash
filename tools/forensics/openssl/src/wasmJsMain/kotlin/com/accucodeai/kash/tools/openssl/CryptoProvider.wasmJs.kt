package com.accucodeai.kash.tools.openssl

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.webcrypto.WebCrypto

internal actual fun cryptographyProvider(): CryptographyProvider = CryptographyProvider.WebCrypto
