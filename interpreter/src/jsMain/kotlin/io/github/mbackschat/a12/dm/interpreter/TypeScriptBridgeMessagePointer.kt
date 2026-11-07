@file:OptIn(ExperimentalJsExport::class)

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer as CorePartiallyKnown

/**
 * One parsed message pointer — A12's `PartiallyKnownDocumentMultiPointer` shape, `fullName` plus indexes, and the
 * missed key a possibly-causing entity carries: a string or a number, as A12's TypeScript runtime types it.
 */
@JsExport
class InterpreterBridgeParsedPartiallyKnownPointer internal constructor(
    val fullName: String,
    val repetitionIndexes: Array<Int>,
    val semanticIndexValueForUnknownIndexGroup: Any?,
)

@JsExport
class InterpreterBridgePartiallyKnownPointerResult internal constructor(
    val value: InterpreterBridgeParsedPartiallyKnownPointer?,
    val rejection: String?,
)

/**
 * Builds a **message** pointer — `PartiallyKnownDocumentMultiPointer.of(fullName, repetitions)`.
 *
 * There is deliberately no `format`/`parse` beside it: A12 defines no pointer string for this type, so the
 * bridge carries the owner's value and the owner's operations only.
 */
@JsExport
fun interpreterBridgeA12PartiallyKnownOf(
    fullName: String,
    repetitions: Array<Int>,
): InterpreterBridgePartiallyKnownPointerResult =
    try {
        val built = CorePartiallyKnown.of(fullName, repetitions.asPortableIntegers())
        InterpreterBridgePartiallyKnownPointerResult(bridgePartiallyKnown(built), null)
    } catch (rejected: IllegalArgumentException) {
        InterpreterBridgePartiallyKnownPointerResult(null, rejected.message.orEmpty())
    }

/** The one crossing of a message pointer, so a built one and a finding's one cannot take different shapes. */
internal fun bridgePartiallyKnown(pointer: CorePartiallyKnown) = InterpreterBridgeParsedPartiallyKnownPointer(
    pointer.fullName,
    pointer.repetitionIndexes.toTypedArray(),
    pointer.semanticIndexValueForUnknownIndexGroup,
)

/**
 * `PartiallyKnownDocumentMultiPointer.toDocumentPointer()` — the exact pointer when the message pointer is one,
 * and a `null` [InterpreterBridgePointerParseResult.value] with no rejection when it legitimately is not.
 *
 * The ROOT raises rather than answering, which is the owner's measured behaviour, so it arrives as a rejection.
 */
@JsExport
fun interpreterBridgeA12PartiallyKnownPointerToExact(
    fullName: String,
    repetitions: Array<Int>,
): InterpreterBridgePointerParseResult =
    try {
        val exact = CorePartiallyKnown.of(fullName, repetitions.asPortableIntegers()).toExactPointer()
        InterpreterBridgePointerParseResult(exact?.let(::bridgePointer), null)
    } catch (rejected: IllegalArgumentException) {
        InterpreterBridgePointerParseResult(null, rejected.message.orEmpty())
    }

/** `DocumentMultiPointer.WILDCARD` — all repetitions of a level, legal at any part of a message pointer. */
@JsExport
fun interpreterBridgeA12PartiallyKnownWildcard(): Int = CorePartiallyKnown.WILDCARD

/** `PartiallyKnownDocumentMultiPointer.UNKNOWN` (`IIdentifier.UNKNOWN`) — a level that resolved to no row. */
@JsExport
fun interpreterBridgeA12PartiallyKnownUnknown(): Int = CorePartiallyKnown.UNKNOWN

/** `IIdentifier.PARALLEL_ITERATION` — a parallel level A12's runtime can leave on a message pointer's references. */
@JsExport
fun interpreterBridgeA12PartiallyKnownParallelIteration(): Int = CorePartiallyKnown.PARALLEL_ITERATION
