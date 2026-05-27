package com.accucodeai.kash.interpreter

internal actual open class FastControlFlowThrowable actual constructor() : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}
