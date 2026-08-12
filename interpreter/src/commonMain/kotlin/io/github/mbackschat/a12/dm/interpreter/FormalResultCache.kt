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
 * One call-local cache for the value-owned half of formal admission: built-in format and constraints, and a
 * declared custom field type. Duplicate-index and required-empty failures are deliberately outside it because they
 * are relations over cells rather than properties of one stored value, and so is the **repetition-bound** check,
 * which [resultAt] applies before either map (it depends on the coordinates, so keeping it inside would force the
 * cell into the key — IF284).
 *
 * The duplicate-index prepass and [FormalResultCache] share this cache. Besides avoiding repeated parsing, it
 * preserves the custom-field-type contract of one invocation **per cell** for a code-based validator, which is why
 * those fields keep a cell-shaped key while everything else shares by value.
 */
internal class ValueAdmissionCache(
    private val checks: AutoChecks,
    private val locale: String,
    /**
     * Whether the coordinate-dependent gate below has to run for this call's document
     * ([AutoChecks.overRepetitionGateNeeded], computed once per call). When it does not — the ordinary document —
     * the gate cannot produce a result for ANY cell, so it is skipped outright rather than re-answered per
     * consultation. **Deliberately has no default:** every construction site is per-call and knows the answer, so
     * a defaulted `true` would only let a missed wiring site compile silently into the old behaviour instead of
     * failing the build.
     */
    val overRepetitionGateNeeded: Boolean,
) {
    /**
     * `(path, raw)` — **not** the cell. What this memoizes is a function of the field and the value, so every cell
     * holding that value shares one entry and the key is two Strings whose hashes the platform has already cached.
     *
     * The repetition list used to ride in here, and it cost more than the check it protected: hashing the key walked
     * the list on every lookup and comparing it walked the list again, so `HashMap` mechanics plus `ArrayList`/`Key`
     * equality were 79 % of the time inside this family against 2.6 % in the work being cached.
     */
    private data class ValueKey(val path: String, val raw: String)

    /**
     * `(path, reps, raw)` — the cell, kept for the one field kind whose admission is NOT a pure function of the
     * value: a code-based [io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType] is a consumer callback
     * invoked once per CELL (IF150/IF152), so two cells holding the same value must each consult it. Sharing them
     * changes an observable count, which is why this is a second map rather than one cheaper key.
     */
    private data class CellKey(val path: String, val reps: List<Int>, val raw: String)

    private val byValue = HashMap<ValueKey, FormalResult>()
    private val byCell = HashMap<CellKey, FormalResult>()

    fun resultAt(path: String, reps: List<Int>, raw: String): FormalResult {
        // Cell-shaped, so it stays out of the memo — and it must run FIRST, because an over-limit cell is exempt
        // from every value check (see AutoChecks.overRepetitionResultAt). Guarded by the document-level
        // precondition for the same reason `dup`/`unavailable` are guarded by `isNotEmpty()`: the gate walks a
        // path's bounds through a map probe BEFORE it can answer "no", and on an ordinary document the answer is
        // "no" for every cell (IG127.1).
        if (overRepetitionGateNeeded) checks.overRepetitionResultAt(path, reps)?.let { return it }
        return if (checks.hasCodeBasedValidatorAt(path)) {
            byCell.getOrPut(CellKey(path, reps, raw)) { checks.computeValueAdmissionAt(path, raw, locale) }
        } else {
            byValue.getOrPut(ValueKey(path, raw)) { checks.computeValueAdmissionAt(path, raw, locale) }
        }
    }

    /** The duplicate prepass must not turn the interpreter's tolerant unsupported-cell result into a whole-call
     * exception. Unsupported values retain the previous lenient identity behavior; the ordinary formal pass still
     * reports them unsupported, and a rule that actually reads one keeps its loud per-rule unsupported result. */
    fun participatesInIdentityAt(path: String, reps: List<Int>, raw: String): Boolean =
        resultAt(path, reps, raw) !is FormalResult.Invalid
}

/**
 * One call-local formal-result cache shared by message emission and every operand read. Never shared across public
 * calls.
 *
 * **A FILLED cell is not memoized here at all** (IF284): its result is a bound walk, a hit in the value-keyed
 * admission memo below, and one set membership — cheaper than the key that would cache it. That also disposes of the
 * hazard this doc used to describe: the key carried the current raw value "because a computation's overlay can change
 * a cell during the call", and recomputing is a stronger answer to the same problem than keying around it. Only the
 * EMPTY branch keeps a memo, on `(path, reps)`, because `requiredEmpty` reads the row context rather than a value.
 * That memo's key is byte-for-byte the one it had before — `raw` was always `null` in the entries it held — and it
 * therefore inherits the pre-existing property that a mid-call change to the *group's* filled-ness is not re-read.
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
    private val valueAdmissions: ValueAdmissionCache,
) {
    /**
     * The EMPTY branch's key only. A filled cell is not memoized here at all — see [resultAt] — so `raw` was
     * always `null` in the entries this map ever held, and dropping it changes nothing it could distinguish.
     */
    private data class Key(val path: String, val reps: List<Int>)

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
        // Empty outside PARTIAL validation, and `path to reps` allocates a Pair and hashes the repetition list
        // before an empty set can answer "no" — so the emptiness is checked first, on the path every call takes.
        if (unavailable.isNotEmpty() && path to reps in unavailable) {
            return FormalResult.Invalid(FormalFailure.Generated(INDEX_DEFAULT_SUPPRESSED))
        }
        // A FILLED cell is not memoized here: its result is a walk over the path's declared bounds, a hit in the
        // value-keyed admission memo, and one set membership — cheaper than the key that would memoize it. Only the
        // EMPTY branch keeps a memo, because `requiredEmpty` reads the row context rather than a cached value.
        val result = if (raw != null) {
            checks.computeFormalResultAt(path, reps, raw, ctx, duplicates, valueAdmissions, groupFilled)
        } else {
            results.getOrPut(Key(path, reps)) {
                checks.computeFormalResultAt(path, reps, null, ctx, duplicates, valueAdmissions, groupFilled)
            }
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
        (unavailable.isEmpty() || path to reps !in unavailable) &&
            valueAdmissions.participatesInIdentityAt(path, reps, raw)
}
