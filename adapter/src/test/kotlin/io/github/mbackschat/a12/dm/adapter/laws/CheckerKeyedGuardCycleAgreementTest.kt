package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two computations writing `Lines/Total` and `Lines/Qty`, each guarded alike, for whether the guard is a read of the
 * other's target and so closes a cycle (KERNEL-FINDINGS KF605).
 *
 * `GroupFilled` over the group holding both targets is no such read, and A12 treats a keyed selection of that group
 * the same way: `GroupFilled(Lines For "a")` admits the pair as `GroupFilled(Lines)` does. The cross-read pair is the
 * positive control that the cycle gate is live on this carrier.
 *
 * Own domain: an order's lines and the warehouse picks that serve them, each keyed by its own number.
 */
class CheckerKeyedGuardCycleAgreementTest {

    private val validator = RuleValidator()

    private fun model(guards: Map<String, String>): String = guards.entries.fold(KeyedOrderModel.json) { json, (target, guard) ->
        validator.inject(json, DraftComputation("/Order", "${target}Comp", "../Lines/$target", null,
            listOf(DraftComputation.Alternative(guard, "1"))))
    }

    private fun kernel(json: String) = KernelConsistencyOracle().checkConsistency(json, ReferenceResolvers.selfContained())
        .map { it.message().trim().let { m -> Regex("\\[(\\w+)]$").find(m)?.groupValues?.get(1) ?: "codeless: $m" } }
        .sorted()

    private fun ours(json: String) = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.sorted()

    private val verdicts by lazy { ROWS.mapValues { (_, guards) -> model(guards).let { kernel(it) to ours(it) } } }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.keys.associateWith { A12.getValue(it) }, verdicts.mapValues { it.value.first })
    }

    @Test
    fun theCheckerNamesA12sClassOnEveryRow() {
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private companion object {
        const val CYCLE = "MVK_ERROR_CALC_CYCLE"

        fun alike(guard: String) = mapOf("Total" to guard, "Qty" to guard)

        val ROWS: Map<String, Map<String, String>> = linkedMapOf(
            "the containing group" to alike("GroupFilled(Lines)"),
            "the containing group, selected" to alike("GroupFilled(Lines For \"a\")"),
            "another group, selected" to alike("GroupFilled(Picks For \"a\")"),
            "a key the index refuses" to alike("GroupFilled(Lines For Lines/Qty)"),
            "each reading the other's target" to mapOf("Total" to "FieldFilled(Lines/Qty)", "Qty" to "FieldFilled(Lines/Total)"),
        )

        val A12: Map<String, List<String>> = mapOf(
            "the containing group" to listOf(),
            "the containing group, selected" to listOf(),
            "another group, selected" to listOf(),
            "a key the index refuses" to listOf("MVK_INDEX_TYPE_INCONSISTENT", "MVK_INDEX_TYPE_INCONSISTENT"),
            "each reading the other's target" to listOf(CYCLE),
        )
    }
}
