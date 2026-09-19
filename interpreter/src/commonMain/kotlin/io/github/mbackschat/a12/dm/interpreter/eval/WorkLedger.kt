package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * A deterministic tally of the work one evaluation call does — the gate a clock cannot carry.
 *
 * **Why a count and not a clock (IG152, measured 2026-09-19).** NFR #1 bars a >5 % regression against a fixed
 * baseline, and no quantity this estate TIMES can hold that bar. Over the 30 committed run files, 66 keys
 * appear in at least three runs and all 66 spread more than 5 % run-to-run, 28.8 % at the median key; the
 * margin, whose noise genuinely does cancel because both engines run in one JVM on the same vCPUs, halves
 * that and still leaves ZERO of 68 rows inside 5 %. A count has no spread, so it is barred at zero instead of
 * five — which is precisely the blind band NFR #1 left open, where a uniform sub-1.65x regression passes every
 * permanent gate. It is the arrangement already accepted for the checker, where `Sweep.comparisons` decides a
 * growth invariant deterministically on every carrier rather than by calibration.
 *
 * **What it deliberately does NOT see: cost per unit.** These are volumes of work, not its price. A change
 * that does identical work in more cycles moves nothing here. Allocation is the estate's instrument for that
 * half and is already anchored (the 48 heap rows of the NFR #1 standing anchor), so what remains uncovered by
 * both is same-work-same-allocation-more-cycles.
 *
 * **Both counters are needed, because either alone is blind to the other's regression.** [ruleRows] moves when
 * a pass visits more rows and does not move when a row's condition is walked twice; [conditionNodes] is the
 * exact mirror. Production pays a null branch: the ledger is absent unless a guard installed one.
 */
internal class WorkLedger {

    /** Condition nodes evaluated, counted at the one dispatch every node of every rule passes through. */
    var conditionNodes: Long = 0
        private set

    /**
     * Value-expression nodes evaluated.
     *
     * Separate from [conditionNodes] because the two paths do not overlap: a computation resolves an `Expr`
     * and never reaches the condition dispatch, so counting conditions alone reports a compute-only workload
     * as doing no work at all.
     */
    var expressionNodes: Long = 0
        private set

    /**
     * (rule, row) instantiations of VALIDATION, which counting nodes cannot separate out — a pass that visits
     * more rows and one that walks a row's condition twice are indistinguishable by node count alone.
     *
     * The compute path has no counterpart here on purpose: its per-instance iteration shows up in
     * [expressionNodes], which is exactly proportional to the instances evaluated, so a second carrier for the
     * same fact would be the duplication this estate rejects rather than new information.
     */
    var ruleRows: Long = 0
        private set

    fun conditionNode() {
        conditionNodes++
    }

    fun expressionNode() {
        expressionNodes++
    }

    fun rowsEvaluated(rows: Int) {
        ruleRows += rows
    }
}
