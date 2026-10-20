package io.github.mbackschat.a12.dm.interpreter

import kotlin.js.Date

internal actual fun currentEpochMillis(): Long = Date.now().toLong()
