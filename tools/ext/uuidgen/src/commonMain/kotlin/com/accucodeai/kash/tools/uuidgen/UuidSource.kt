package com.accucodeai.kash.tools.uuidgen

/** Platform-specific UUID generators. Lowercase 8-4-4-4-12, no braces. */
internal expect fun newRandomUuid(): String

/** Time-based (v1) UUID. Implementations may fall back to v4 if unsupported. */
internal expect fun newTimeUuid(): String
