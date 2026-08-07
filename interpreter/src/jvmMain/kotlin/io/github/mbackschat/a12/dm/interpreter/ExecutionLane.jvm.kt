package io.github.mbackschat.a12.dm.interpreter

/** The current thread. `threadId()` is the non-deprecated identity accessor on the pinned JDK. */
internal actual fun currentExecutionLane(): Long = Thread.currentThread().threadId()
