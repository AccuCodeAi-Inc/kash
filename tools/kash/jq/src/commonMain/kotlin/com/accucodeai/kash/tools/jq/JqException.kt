package com.accucodeai.kash.tools.jq

public sealed class JqException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public class JqParseError(
    message: String,
) : JqException(message)

public class JqRuntimeError(
    message: String,
) : JqException(message)
