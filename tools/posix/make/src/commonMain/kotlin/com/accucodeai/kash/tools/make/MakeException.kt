package com.accucodeai.kash.tools.make

public class MakeParseError(
    message: String,
    public val line: Int = 0,
) : RuntimeException(message)

public class MakeRuntimeError(
    message: String,
) : RuntimeException(message)
