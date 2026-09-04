package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
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

    /** The one document every row evaluates, built for the kernel and the interpreter from the same values. */
    private fun documentsFor(def: ModelDef): Pair<RuntimeLaws.Doc, Document> {
        val kDoc = RuntimeLaws.doc().group("/Policy", 1).field(TOTAL, "999", 1, 1)
        val iDoc = def.newDocument().group("/Policy", 1).field(TOTAL, "999", 1, 1)
        PREMIUMS.forEachIndexed { index, premium ->
            val row = index + 1
            kDoc.group(ENDORSEMENTS, 1, row).field(PREMIUM, premium, 1, row, 1)
            iDoc.group(ENDORSEMENTS, 1, row).field(PREMIUM, premium, 1, row, 1)
        }
        return kDoc to iDoc
    }

    private fun modelWith(condition: Condition): String =
        RuntimeLaws.withRule(base, "/Policy", CODE, "../SettlementTotal", CODE, DslRenderer().render(condition))

    /** Fires on all three engines or on none; returns that shared verdict. */
    private fun triFires(condition: Condition): Boolean {
        val rendered = DslRenderer().render(condition)
        val kModel = modelWith(condition)
        val def = ModelLoader.load(kModel)
        val (kDoc, iDoc) = documentsFor(def)

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

    /**
     * **The operand's star binds the FILTER, not the RULE** — one message, at the non-iterating host's own
     * field, over a three-row list.
     *
     * **Why the boolean rows above cannot answer this.** `triFires` reads *whether* the code appears, which is
     * identical for a rule that fires once and a rule that fires per row; a12-kernel-lean's reply asked the
     * seam question in exactly that gap ("what does a non-iterating rule DO with a `CurrentRepetition`
     * operand"), and the honest answer was that the count had never been measured. Reading the fired-code list
     * per engine settles it, and the same list carries the attachment for free — the pointer says which cell
     * the message lands on, so "once" and "once per row collapsed by dedup" are distinguishable too, which a
     * count alone would not be.
     *
     * **The three-row list is the discriminator.** With one row, once and per-row agree; the filter here admits
     * TWO of three rows, so a rule that iterated its operand's star would produce two messages.
     */
    @Test
    fun theStarredOperandDoesNotMakeTheRuleIterate() {
        assertEquals(listOf("/Policy[1]/SettlementTotal"), triMessagePointers(filteredSumIs(50)),
            "two of three rows survive the filter, and the rule still reports once at its own error field")
    }

    /**
     * Every engine's message pointers for the rule's own code, asserted identical across the three.
     *
     * <p>The kernel's generated-Java arm is read through `validationSignaturesSorted` (`code|type|pointer`) and
     * projected to the pointer, so both kernel arms and the interpreter are compared on the one axis this claim
     * is about. Neither list is deduplicated: a set would erase the very repetition being measured.
     */
    private fun triMessagePointers(condition: Condition): List<String> {
        val rendered = DslRenderer().render(condition)
        val kModel = modelWith(condition)
        val def = ModelLoader.load(kModel)
        val (kDoc, iDoc) = documentsFor(def)

        val groovyDynamic = RuntimeLaws.messages(kModel, kDoc)
            .filter { it.code() == CODE }.map { it.field() }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val javaStatic = engine.validationSignaturesSorted(engine.instance(kDoc))
            .filter { it.startsWith("$CODE|") }.map { it.substringAfterLast('|') }.sorted()
        val interpreter = DmInterpreter(def).validateFull(iDoc)
            .filter { it.code == CODE }.map { it.errorPath }.sorted()

        assertEquals(groovyDynamic, javaStatic, "$rendered — groovy-dynamic vs generated Java")
        assertEquals(groovyDynamic, interpreter, "$rendered — kernel vs interpreter")
        return groovyDynamic
    }
}
