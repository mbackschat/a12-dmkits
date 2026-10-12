package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.eval.AggregateMemoKey
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.IndexColumn
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.Suppression

/** Whether a field/group instance `(path, reps)` is relevant — full validation: always; partial: in the
 *  relevant set. Gates which rules run and which referenced fields are UNKNOWN. See [DmInterpreter.validatePart]. */
internal typealias Relevance = (String, List<Int>) -> Boolean

/** The full-validation relevance: every instance is relevant. */
internal val ALL_RELEVANT: Relevance = { _, _ -> true }

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
    /** The ORIGINAL caller document — [doc] BEFORE any index-default staging (`doc.withComputed`). Equal to
     *  [doc] when nothing was staged. Rule EVALUATION reads the staged [doc]; a `$field.value$` message
     *  interpolation reads THIS one, so a staged index cell renders as if empty — the kernel's split between
     *  the transient staging cache and `getDaten().getValueForDisplay` (IF221). */
    val callerDoc: Document,
    val locale: String,
    val isRelevant: Relevance,
    /** Partial only: [isRelevant] with every level from the given bound on treated as a wildcard — the
     *  rule-run gate's query, since a level BELOW the rule's iteration element was never bound and the kernel
     *  does not require the relevant set to name it ([IF268]). Null in full validation. */
    private val relevantWithin: ((String, List<Int>, Int) -> Boolean)?,
    val dup: Set<Pair<String, List<Int>>>,
    /** The call-invariant [Interpreter] config — built ONCE per pass (formerly once per rule; identical
     *  content, strictly fewer allocations) and shared by every per-row interpreter of the call. */
    val config: EvalConfig,
    private val formalResults: FormalResultCache,
    /** Lazy, call-local complete external formal-invalid addresses for a reached CustomCondition. */
    private val formallyIncorrect: () -> Set<CustomConditionFieldPointer> = { emptySet() },
    val parallelSelection: ParallelRuleSelection? = null,
    messageIndexDefaults: () -> Map<Triple<String, List<Int>, String>, List<Int>> = { emptyMap() },
) {
    private val messageDefaults by lazy(LazyThreadSafetyMode.NONE, messageIndexDefaults)

    /** Message identity is separate from value availability; an invalid singleton remains unreadable. */
    fun selectedMessageRepetitions(ctx: EvalContext, target: String, indexField: String, key: String): List<Int>? {
        ctx.indexPointerRepetitions(target, indexField, key, config.indexIdentityAdmitted)?.let { return it }
        if (partial) return null
        val parent = ctx.repsOf(indexField).dropLast(2)
        val selected = messageDefaults[Triple(indexField, parent, key)] ?: return null
        return selected.dropLast(1) + ctx.repsOf(target).drop(parent.size + 1)
    }

    val partial: Boolean = config.cellRelevant != null
    private val aggregateMemo = HashMap<AggregateMemoKey, Val>()

    /** The call's relevance-scoped index columns ([Interpreter.indexColumnOf]). Empty and unread in a FULL
     *  validation, where the document's own column memo already answers every gate. */
    private val indexColumns = HashMap<Pair<String, List<Int>>, IndexColumn>()
    private val invalidOperand: (EvalContext, String) -> Boolean = { reading, path ->
        val reps = reading.repsOf(path)
        !isRelevant(path, reps) || formalErrorAt(path, reps, reading) != null
    }
    private val resolveIndexColumn: (Pair<String, List<Int>>, () -> IndexColumn) -> IndexColumn =
        { key, build -> indexColumns.getOrPut(key, build) }

    /**
     * The "unknown-operand" code of the field instance at [path] in [ctx], or null when the field is usable.
     * The single source for "is this operand unknown?" (fed to the [Interpreter] → `Val.Unknown`, and to
     * [firstSuppressor] → the suppressor name), covering all three kernel causes: a present value's **format**,
     * an empty **required** value, and a **duplicate index** value — a duplicate-index field is unknown for any
     * rule referencing it, branch-scoped exactly like a formal error (verified, KERNEL-FINDINGS §4 supplement).
     * Memoized in the pass's [FormalResultCache]; the uncached check is [AutoChecks.computeFormalResultAt].
     */
    fun formalErrorAt(path: String, reps: List<Int>, ctx: EvalContext): String? {
        return formalResults.failureAt(path, reps, ctx)?.code
    }

    /** The stored-cell admission gate used before an index value is projected into a join key. Relevance is the
     * first, short-circuiting stage so an excluded custom cell is neither validated nor admitted. */
    fun admitsIndexIdentity(path: String, reps: List<Int>, raw: String): Boolean =
        isRelevant(path, reps) && formalResults.admitsIdentityAt(path, reps, raw)

    /**
     * Whether the rule whose error field is [errorPath] runs for the row [ctx] — the kernel executes it only
     * when the error field is relevant, but the levels the iteration never BOUND count as wildcards: a rule
     * that does not iterate `Items` yet reports on `/Order/Items/Tag` runs when ANY `Items` row's Tag is
     * relevant, and reports at the default first repetition. A rule that DOES iterate `Items` keeps exact
     * per-row gating. The bound count comes from [EvalContext.boundLevelsOf] — from the context, never from
     * the rule's declared group, because iteration is reference-driven ([IF268];
     * `PartialRuleGateScopeDiffTest`). Full validation: always.
     */
    fun runsForRow(errorPath: String, ctx: EvalContext): Boolean {
        val reps = ctx.repsOf(errorPath)
        val within = relevantWithin ?: return isRelevant(errorPath, reps)
        return within(errorPath, reps, ctx.boundLevelsOf(errorPath))
    }

    /** The first referenced field that is "unknown" in [ctx] (malformed value, required-empty, or duplicate index), else null. */
    fun firstSuppressor(refs: List<String>, ctx: EvalContext): Suppression? =
        refs.firstNotNullOfOrNull { path ->
            val reps = ctx.repsOf(path)
            if (!isRelevant(path, reps)) null
            else formalErrorAt(path, reps, ctx)?.let { Suppression(path, it) }
        }

    /**
     * The per-row [Interpreter] used to evaluate a rule condition at [ctx] — the single construction point for
     * [DmInterpreter]'s row outcomes (the whole condition). [config] is the call-invariant bundle; the per-row
     * inputs are supplied here: the error-field pointer, the `invalid` gate by which a formally-invalid / (in
     * partial) non-relevant referenced field resolves to `Val.Unknown`, and the optional [rnuOutcome] cache seam
     * for a rule whose condition contains a `RepetitionNotUnique` leaf (IF149; null for a normal rule).
     */
    fun interpreterFor(ctx: EvalContext, errorPath: String,
            rnuOutcome: ((EvalContext) -> MsgType?)? = null): Interpreter =
        Interpreter(ctx, config, CustomConditionFieldPointer(errorPath, ctx.repsOf(errorPath)),
            // The pass-owned gate takes the reading context so Having still judges its own row (IF319).
            invalid = invalidOperand,
            rnuOutcome = rnuOutcome,
            customFormallyIncorrect = formallyIncorrect)
            .sharingAggregateMemo(aggregateMemo)
            .sharingIndexColumns(resolveIndexColumn)
}
