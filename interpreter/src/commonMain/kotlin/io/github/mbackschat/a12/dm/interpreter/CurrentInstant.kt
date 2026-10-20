package io.github.mbackschat.a12.dm.interpreter

/**
 * The platform's current instant in epoch milliseconds, the instant A12 reads `Today` and `Now` from when no
 * `currentDateForTest` is set. `commonMain` cannot read a platform clock, so each target supplies it.
 */
internal expect fun currentEpochMillis(): Long
