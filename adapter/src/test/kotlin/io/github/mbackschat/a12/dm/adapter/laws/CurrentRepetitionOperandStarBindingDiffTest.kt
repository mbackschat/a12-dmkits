package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the RUNTIME half of a12-kernel-lean's
 * `SPEC-2026-09-04-03`: an operand's own star binds an UNMARKED `CurrentRepetition`, so the filter reads the
 * candidate's own coordinate even though the rule's host does not iterate.
 *
 * **Why the runtime tier is a separate claim from the admission.** The accept/reject rows are terminal at the
 * kernel's consistency check and are locked by `rulekit.validate.laws.CurrentRepetitionBindingSourceLawsTest`.
 * Admission says nothing about evaluation, and their entry's `consumer-consequence` names exactly the failure a
 * compiling consumer makes here: one binding source rejects, or mis-binds, a legal model. This repository IS such
 * a consumer — `PreparedEvaluationPlan` audits a filter with `outerAllowed = true` and the interpreter resolves
 * `CurrentRepetition` from the enclosing evaluation context — so whether the coordinate our filter reads is the
 * candidate's own is a measurement, not an inference from the audit's shape.
 *
 * **The oracle is the VALUE, not a boolean.** Each row compares the filtered `Sum` against an exact total, so a
 * mis-binding is not merely detected but identified: over premiums `10, 20, 30` the correct filtered sum for
 * `CurrentRepetition(Endorsements) > 1` is **50**; a filter silently ignored gives **60**; a filter that survives
 * no candidate gives **0**. All three are measured, so a green row cannot be satisfied by the two rival readings.
 *
 * **The controls.** The unfiltered aggregate pins 60 from the same host, so the star and the aggregate are known
 * to work there independently of the filter. And each row runs on both kernel strategies as well as the
 * interpreter, because a divergence between the two kernel arms would make "the kernel" an ambiguous oracle for
 * the rest.
 *
 * Own domain: an insurance policy carrying endorsements, each with a premium.
 */
class CurrentRepetitionOperandStarBindingDiffTest {

    private companion object {
        const val TOTAL = "/Policy/SettlementTotal"
        const val ENDORSEMENTS = "/Policy/Endorsements"
        const val PREMIUM = "/Policy/Endorsements/Premium"
        const val CODE = "CR"
        val PREMIUMS = listOf("10", "20", "30")
    }

    /** A NONREPEATABLE host carrying the error field, and one repeatable subgroup an operand can star. */
    private val base = DocumentModelDraft.named("policybind").locale("en_US")
        .rootGroup("Policy") { policy ->
            policy.numberField("SettlementTotal") { it.fractionDigits(0, 0) }
            policy.group("Endorsements") { endorsements ->
                endorsements.repeatable(9).numberField("Premium") { it.fractionDigits(0, 0) }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    /** `Sum(the flatten of Endorsements* then Premium Having CurrentRepetition(Endorsements) > 1) == total`. */
    private fun filteredSumIs(total: Long): Condition =
        Dsl.sum(m.flatten("/Policy/Endorsements*/Premium")
            .having(Dsl.currentRepetition(m.group(ENDORSEMENTS)).gt(1)))
            .eq(total)
            .and(Dsl.fieldFilled(m.field(TOTAL)))

    private fun unfilteredSumIs(total: Long): Condition =
        Dsl.sum(m.flatten("/Policy/Endorsements*/Premium")).eq(total)
            .and(Dsl.fieldFilled(m.field(TOTAL)))

    /** Fires on all three engines or on none; returns that shared verdict. */
    private fun triFires(condition: Condition): Boolean {
        val rendered = DslRenderer().render(condition)
        val kModel = RuntimeLaws.withRule(base, "/Policy", CODE, "../SettlementTotal", CODE, rendered)
        val def = ModelLoader.load(kModel)

        val kDoc = RuntimeLaws.doc().group("/Policy", 1).field(TOTAL, "999", 1, 1)
        val iDoc = def.newDocument().group("/Policy", 1).field(TOTAL, "999", 1, 1)
        PREMIUMS.forEachIndexed { index, premium ->
            val row = index + 1
            kDoc.group(ENDORSEMENTS, 1, row).field(PREMIUM, premium, 1, row, 1)
            iDoc.group(ENDORSEMENTS, 1, row).field(PREMIUM, premium, 1, row, 1)
        }

        val groovyDynamic = RuntimeLaws.fires(kModel, CODE, kDoc)
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val javaStatic = engine.firedCodesSorted(engine.instance(kDoc)).contains(CODE)
        val interpreter = DmInterpreter(def).validateFull(iDoc).any { it.code == CODE }

        assertEquals(groovyDynamic, javaStatic, "$rendered — groovy-dynamic vs generated Java")
        assertEquals(groovyDynamic, interpreter, "$rendered — kernel vs interpreter")
        return groovyDynamic
    }

    @Test
    fun theStarredOperandsFilterReadsTheCandidatesOwnCoordinate() {
        assertEquals(true, triFires(filteredSumIs(50)),
            "rows 2 and 3 survive CurrentRepetition(Endorsements) > 1 — the star binds the unmarked operand")
    }

    @Test
    fun anIgnoredFilterWouldHaveSummedEveryRow() {
        assertEquals(false, triFires(filteredSumIs(60)),
            "60 is the unfiltered total — a filter read as always-true would fire here")
    }

    @Test
    fun aFilterSurvivedByNoCandidateWouldHaveSummedNothing() {
        assertEquals(false, triFires(filteredSumIs(0)),
            "0 is the empty fold — an unbound coordinate that excluded every row would fire here")
    }

    @Test
    fun theUnfilteredAggregateIsTheLiveControlFromTheSameHost() {
        assertEquals(true, triFires(unfilteredSumIs(60)),
            "the star and the aggregate work from this non-iterating host independently of the filter")
    }
}
