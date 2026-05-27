package com.accucodeai.kash.tools.sed

/** Thrown for malformed sed scripts (bad address, unterminated `s///`, etc.). */
public class SedScriptError(
    message: String,
) : RuntimeException(message)
