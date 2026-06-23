package io.github.mbackschat.a12.dm.interpreter

/**
 * Signals a construct the interpreter does not model yet (an unknown operator, or a deliberately
 * external one like `CustomCondition`). It is the engine's "this is not lost, just not interpretable
 * here" signal: [DmInterpreter.validate] catches it per rule and reports the rule as *unsupported*
 * rather than crashing the whole document — mirroring the kernel-adapter's tolerant `Opaque` read.
 * Genuine bugs (a missing field kind, a malformed AST) throw other exceptions and are NOT swallowed.
 */
class UnsupportedConstructException(message: String) : RuntimeException(message)
