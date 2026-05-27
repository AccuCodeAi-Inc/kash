package com.accucodeai.kash.interpreter

/**
 * Base for hot-path control-flow exceptions (break/continue/return/abort).
 * On JVM the actual overrides `fillInStackTrace()` as a no-op so these
 * throws don't pay the (significant) stack-walk cost. On wasmJs there is
 * no stack-trace machinery to opt out of, so the actual is empty.
 */
internal expect open class FastControlFlowThrowable() : RuntimeException
