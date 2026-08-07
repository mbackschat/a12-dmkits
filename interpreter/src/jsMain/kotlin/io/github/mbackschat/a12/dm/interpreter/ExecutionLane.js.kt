package io.github.mbackschat.a12.dm.interpreter

/** One execution lane per isolate, so the identity is constant and the concurrency check never fires. */
internal actual fun currentExecutionLane(): Long = 0L
