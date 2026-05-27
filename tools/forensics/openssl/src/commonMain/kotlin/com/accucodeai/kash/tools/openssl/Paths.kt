package com.accucodeai.kash.tools.openssl

internal fun resolvePath(
    cwd: String,
    path: String,
): String = if (path.startsWith("/")) path else "$cwd/$path"
