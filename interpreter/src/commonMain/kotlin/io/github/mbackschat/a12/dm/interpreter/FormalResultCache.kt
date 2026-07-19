package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.FormalErrorCode
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection

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
 * One call-local formal-result cache shared by message emission and every operand read. The key includes the
 * current raw value because a computation's overlay can change a cell during the call; `(path, reps)` alone
 * would retain a stale pre-overlay result. The cache is never shared across public calls.
 */
internal class FormalResultCache(
    private val checks: AutoChecks,
    private val locale: String,
    private val duplicates: Set<Pair<String, List<Int>>>,
) {
    private data class Key(val path: String, val reps: List<Int>, val raw: String?)

    private val results = HashMap<Key, FormalResult>()

    fun resultAt(path: String, reps: List<Int>, ctx: EvalContext): FormalResult {
        val raw = ctx.displayValue(path)
        return resultAt(path, reps, raw, ctx)
    }

    /** Filled-cell emission already carries [raw], so it can populate the same cache without reconstructing a row context. */
    fun resultAt(path: String, reps: List<Int>, raw: String): FormalResult =
        resultAt(path, reps, raw, null)

    private fun resultAt(path: String, reps: List<Int>, raw: String?, ctx: EvalContext?): FormalResult {
        val key = Key(path, reps, raw)
        return results.getOrPut(key) {
            checks.computeFormalResultAt(path, reps, raw, ctx, duplicates, locale)
        }
    }

    fun failureAt(path: String, reps: List<Int>, ctx: EvalContext): FormalFailure? =
        when (val result = resultAt(path, reps, ctx)) {
            FormalResult.Valid -> null
            is FormalResult.Invalid -> result.failure
            is FormalResult.Unsupported -> throw UnsupportedConstructException(result.reason)
        }
}
