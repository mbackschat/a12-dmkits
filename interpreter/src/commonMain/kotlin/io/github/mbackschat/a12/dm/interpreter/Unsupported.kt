package io.github.mbackschat.a12.dm.interpreter

/**
 * Signals a construct the interpreter does not model yet (an unknown operator, or a deliberately
 * external one like `CustomCondition`). It is the engine's "this is not lost, just not interpretable
 * here" signal: [DmInterpreter.validate] catches it per rule and reports the rule as *unsupported*
 * rather than crashing the whole document — mirroring the kernel-adapter's tolerant `Opaque` read.
 * Genuine bugs (a missing field kind, a malformed AST) throw other exceptions and are NOT swallowed.
 */
class UnsupportedConstructException(message: String) : RuntimeException(message)

/**
 * Signals a MODEL the interpreter cannot evaluate because its `modelConfig.timeZone` is outside the
 * interpreter's supported set (`UTC`/`GMT`/`Europe/Berlin`). Unlike [UnsupportedConstructException] (a
 * per-rule "unsupported" verdict), this is a whole-model refusal raised at model preparation — the kernel
 * accepts a wider Java `TimeZone` id domain than the kernel-free interpreter models (a documented scope
 * limit, IG83), so a valid model can carry a zone we deliberately do not evaluate. It is a LOUD refusal
 * (never a silent degrade); a CLI consumer maps it to a corrective refusal rather than an internal error.
 */
class UnsupportedModelTimeZoneException(
    val timeZoneId: String?,
    message: String,
) : RuntimeException(message) {
    constructor(message: String) : this(null, message)
}

/**
 * Signals that an explicitly present `modelConfig.timeZone` is not a valid configuration value. This is
 * distinct from [UnsupportedModelTimeZoneException]: absent means the UTC default, an invalid explicit
 * value must be rejected, and an otherwise valid host time-zone id may still be outside the interpreter's
 * supported set. The common loader can identify null/blank values; its JVM consumer classifies the wider
 * Java id domain before calling it.
 */
class InvalidModelTimeZoneException(
    val timeZoneId: String?,
    message: String,
) : IllegalArgumentException(message) {
    constructor(message: String) : this(null, message)
}
