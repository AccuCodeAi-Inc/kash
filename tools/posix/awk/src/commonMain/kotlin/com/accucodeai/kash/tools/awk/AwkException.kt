package com.accucodeai.kash.tools.awk

public sealed class AwkException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public class AwkParseError(
    message: String,
) : AwkException(message)

public class AwkRuntimeError(
    message: String,
) : AwkException(message)
