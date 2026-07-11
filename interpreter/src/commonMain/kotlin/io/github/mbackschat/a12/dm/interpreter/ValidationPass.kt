package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.Suppression

/** Whether a field/group instance `(path, reps)` is relevant — full validation: always; partial: in the
 *  relevant set. Gates which rules run and which referenced fields are UNKNOWN. See [DmInterpreter.validatePart]. */
internal typealias Relevance = (String, List<Int>) -> Boolean

/** The full-validation relevance: every instance is relevant. */
internal val ALL_RELEVANT: Relevance = { _, _ -> true }

/** Per-validation memo of [ValidationPass.formalErrorAt] keyed by `(path, reps)` → the field instance's
 *  unknown code (format / mandatory / duplicate-index) or null when usable. The document is immutable during a
 *  validation, so a field instance's status is stable — computing it once per distinct instance replaces the
 *  per-operand, per-row recompute (measured: `formalErrorAt` was ~56% of `validateFull` before this memo).
 *  Created per validate call (never shared across calls), so the reused engine stays thread-safe. */
private typealias FormalCache = MutableMap<String, MutableMap<List<Int>, String?>>   // path → reps → code (split to drop the Pair key, hot in firstSuppressor)

/**
 * The per-CALL half of the I2 eval-state seam (IF117): everything ONE validate/testRule call used to thread
 * as ~8 parameters through every rule-path signature — the document, the locale, the relevance gate, the
 * duplicate-index cells (computed once per call), the once-per-call [EvalConfig], and the per-call
 * formal-status memo — plus the per-row services derived from them ([interpreterFor], [formalErrorAt],
 * [firstSuppressor]). The rule loop, the `RepetitionNotUnique` emitter, and the row-outcome machinery all
 * hold the pass. It holds the call-invariant [AutoChecks] oracle; nothing calls back into [DmInterpreter].
 */
internal class ValidationPass(
    val doc: Document,
    val locale: String,
    val isRelevant: Relevance,
    val dup: Set<Pair<String, List<Int>>>,
    /** The call-invariant [Interpreter] config — built ONCE per pass (formerly once per rule; identical
     *  content, strictly fewer allocations) and shared by every per-row interpreter of the call. */
    val config: EvalConfig,
    private val checks: AutoChecks,
) {
    /** The formal-status memo across ALL rules of this pass (the doc is immutable during a validation). */
    private val cache: FormalCache = HashMap()

    /**
     * The "unknown-operand" code of the field instance at [path] in [ctx], or null when the field is usable.
     * The single source for "is this operand unknown?" (fed to the [Interpreter] → `Val.Unknown`, and to
     * [firstSuppressor] → the suppressor name), covering all three kernel causes: a present value's **format**,
     * an empty **required** value, and a **duplicate index** value — a duplicate-index field is unknown for any
     * rule referencing it, branch-scoped exactly like a formal error (verified, KERNEL-FINDINGS §4 supplement).
     * Memoized in the pass's cache; the uncached check is [AutoChecks.computeFormalErrorAt].
     */
    fun formalErrorAt(path: String, reps: List<Int>, ctx: EvalContext): String? {
        val inner = cache.getOrPut(path) { HashMap() }
        inner[reps]?.let { return it }
        if (inner.containsKey(reps)) return null   // memoized "usable" (null) — distinct from "not yet computed"
        val v = checks.computeFormalErrorAt(path, reps, ctx, dup)
        inner[reps] = v
        return v
    }

    /** The first referenced field that is "unknown" in [ctx] (malformed value, required-empty, or duplicate index), else null. */
    fun firstSuppressor(refs: List<String>, ctx: EvalContext): Suppression? =
        refs.firstNotNullOfOrNull { path -> formalErrorAt(path, ctx.repsOf(path), ctx)?.let { Suppression(path, it) } }

    /**
     * The per-row [Interpreter] used to evaluate a rule condition at [ctx] — the single construction point
     * shared by [DmInterpreter]'s row outcomes (the whole condition) and the `RepetitionNotUnique` guard
     * conjunct. [config] is the call-invariant bundle; the two per-row inputs are supplied here: the
     * error-field pointer, and the `invalid` gate by which a formally-invalid / (in partial) non-relevant
     * referenced field resolves to `Val.Unknown`.
     */
    fun interpreterFor(ctx: EvalContext, errorPath: String): Interpreter =
        Interpreter(ctx, config, FieldPointer(errorPath, ctx.repsOf(errorPath)),
            invalid = { path ->
                val reps = ctx.repsOf(path)   // computed once, reused for the formal check AND relevance
                formalErrorAt(path, reps, ctx) != null || !isRelevant(path, reps)
            })
}
