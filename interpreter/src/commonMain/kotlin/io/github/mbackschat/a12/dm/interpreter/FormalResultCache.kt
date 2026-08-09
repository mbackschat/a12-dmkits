package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.FormalErrorCode
import io.github.mbackschat.a12.dm.interpreter.eval.INDEX_DEFAULT_SUPPRESSED
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer

/** The complete result of formally checking one cell. */
internal sealed interface FormalResult {
    data object Valid : FormalResult
    data class Invalid(val failure: FormalFailure) : FormalResult
    data class Unsupported(val reason: String) : FormalResult
}

/** A formal failure retains the metadata needed by both message emission and rule/computation suppression. */
internal sealed interface FormalFailure {
    val code: String

    data class Format(val error: FormalErrorCode) : FormalFailure {
        override val code: String get() = error.id
    }

    data class Custom(val rejection: CustomTypeRejection) : FormalFailure {
        override val code: String get() = rejection.code
    }

    data class Generated(override val code: String) : FormalFailure
}

/**
 * One call-local cache for the value-owned half of formal admission: repetition bounds, built-in format and
 * constraints, and a registered custom field type. Duplicate-index and required-empty failures are deliberately
 * outside it because they are relations over cells rather than properties of one stored value.
 *
 * The duplicate-index prepass and [FormalResultCache] share this cache. Besides avoiding repeated parsing, this
 * preserves the custom-field-type contract of one invocation per reached concrete value.
 */
internal class ValueAdmissionCache(
    private val checks: AutoChecks,
    private val locale: String,
) {
    private data class Key(val path: String, val reps: List<Int>, val raw: String)

    private val results = HashMap<Key, FormalResult>()

    fun resultAt(path: String, reps: List<Int>, raw: String): FormalResult =
        results.getOrPut(Key(path, reps, raw)) {
            checks.computeValueAdmissionAt(path, reps, raw, locale)
        }

    /** The duplicate prepass must not turn the interpreter's tolerant unsupported-cell result into a whole-call
     * exception. Unsupported values retain the previous lenient identity behavior; the ordinary formal pass still
     * reports them unsupported, and a rule that actually reads one keeps its loud per-rule unsupported result. */
    fun participatesInIdentityAt(path: String, reps: List<Int>, raw: String): Boolean =
        resultAt(path, reps, raw) !is FormalResult.Invalid
}

/**
 * One call-local formal-result cache shared by message emission and every operand read. The key includes the
 * current raw value because a computation's overlay can change a cell during the call; `(path, reps)` alone
 * would retain a stale pre-overlay result. The cache is never shared across public calls.
 */
internal class FormalResultCache(
    private val checks: AutoChecks,
    private val locale: String,
    private val duplicates: Set<Pair<String, List<Int>>>,
    /** PARTIAL validation's silently suppressed eligible index-default cells (SPEC-2026-07-23-14): each
     *  reads as formally invalid with the message-less [INDEX_DEFAULT_SUPPRESSED] marker — no generated
     *  check, dependent rules see UNKNOWN — and is an invalid pointer from the start (the kernel marks the
     *  containing groups erroneous EAGERLY, before any rule runs). Empty outside partial validation. */
    private val unavailable: Set<Pair<String, List<Int>>> = emptySet(),
    private val groupFilled: (EvalContext, String) -> Boolean = { ctx, path -> ctx.groupFilled(path) },
    private val valueAdmissions: ValueAdmissionCache = ValueAdmissionCache(checks, locale),
) {
    private data class Key(val path: String, val reps: List<Int>, val raw: String?)

    private val results = HashMap<Key, FormalResult>()
    private val invalidPointers =
        unavailable.mapTo(HashSet()) { (path, reps) -> CustomConditionFieldPointer(path, reps) }

    fun resultAt(path: String, reps: List<Int>, ctx: EvalContext): FormalResult {
        val raw = ctx.displayValue(path)
        return resultAt(path, reps, raw, ctx)
    }

    /** Filled-cell emission already carries [raw], so it can populate the same cache without reconstructing a row context. */
    fun resultAt(path: String, reps: List<Int>, raw: String): FormalResult =
        resultAt(path, reps, raw, null)

    private fun resultAt(path: String, reps: List<Int>, raw: String?, ctx: EvalContext?): FormalResult {
        if (path to reps in unavailable) {
            return FormalResult.Invalid(FormalFailure.Generated(INDEX_DEFAULT_SUPPRESSED))
        }
        val key = Key(path, reps, raw)
        val result = results.getOrPut(key) {
            checks.computeFormalResultAt(path, reps, raw, ctx, duplicates, valueAdmissions, groupFilled)
        }
        if (result is FormalResult.Invalid) invalidPointers += CustomConditionFieldPointer(path, reps)
        return result
    }

    /** Invalid addresses reached by the validation pre-pass; no declared-empty Cartesian expansion. */
    fun invalidPointers(): Set<CustomConditionFieldPointer> = invalidPointers

    /** Whether a cell has one concrete invalid code. Unlike [failureAt], an unsupported result is not a failure
     * and remains available to callers that implement the interpreter's documented lenient fallback. */
    fun hasInvalidCodeAt(path: String, reps: List<Int>, ctx: EvalContext, code: String): Boolean =
        (resultAt(path, reps, ctx) as? FormalResult.Invalid)?.failure?.code == code

    fun failureAt(path: String, reps: List<Int>, ctx: EvalContext): FormalFailure? =
        when (val result = resultAt(path, reps, ctx)) {
            FormalResult.Valid -> null
            is FormalResult.Invalid -> result.failure
            is FormalResult.Unsupported -> throw UnsupportedConstructException(result.reason)
        }

    /** Compute-scan twin of [failureAt]: an identity-preserving scan already carries the exact raw value and
     *  repetitions, so no synthetic row context is needed for a filled cell. */
    fun failureAt(path: String, reps: List<Int>, raw: String): FormalFailure? =
        when (val result = resultAt(path, reps, raw)) {
            FormalResult.Valid -> null
            is FormalResult.Invalid -> result.failure
            is FormalResult.Unsupported -> throw UnsupportedConstructException(result.reason)
        }

    /** Whether a filled stored value may enter an identity relation. This is deliberately value-owned admission
     * only: duplicate-index failure is the NEXT stage and must still see every admitted peer in order to classify
     * the collision. Partial-call unavailability stays an admission failure. An unregistered custom type follows
     * the interpreter API's documented lenient fallback, while the ordinary formal pass reports it unsupported. */
    fun admitsIdentityAt(path: String, reps: List<Int>, raw: String): Boolean =
        path to reps !in unavailable && valueAdmissions.participatesInIdentityAt(path, reps, raw)
}
