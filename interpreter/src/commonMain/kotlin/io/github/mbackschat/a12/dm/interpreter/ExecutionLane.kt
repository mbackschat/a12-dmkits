package io.github.mbackschat.a12.dm.interpreter

/**
 * An opaque identity for the execution lane currently running interpreter code.
 *
 * It exists for ONE purpose: an [Interpreter] is not thread-safe, and sharing one across request threads used to
 * corrupt silently rather than fail. Comparing lanes turns that misuse into a loud refusal. It is a DETECTOR, not
 * a lock — the interpreter does not become thread-safe by having it.
 *
 * JVM returns the current thread's identity. JS has one execution lane per isolate, so its identity is constant
 * and the check can never fire there; a multi-isolate host gives each isolate its own object graph anyway.
 */
internal expect fun currentExecutionLane(): Long
