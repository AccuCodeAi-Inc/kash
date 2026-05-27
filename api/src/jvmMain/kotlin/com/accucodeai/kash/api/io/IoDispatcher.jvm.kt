package com.accucodeai.kash.api.io

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

internal actual val ioDispatcher: CoroutineContext = Dispatchers.IO
