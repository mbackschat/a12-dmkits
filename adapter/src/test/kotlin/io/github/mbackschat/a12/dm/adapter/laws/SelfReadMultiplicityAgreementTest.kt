package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.portable
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.Alt
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.computation
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.premium
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.EnumFieldBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How many times A12 reports a computation's read of its own target, and where among its other alternative
 * classes (KERNEL-FINDINGS KF181), held against the kernel and the portable checker.
 *
 * The count rows separate "one class per judged read" from "one per computation": two reads in one operation,
 * one in the precondition and one in the operation, one in the common precondition. The order rows separate
 * "in walk order" from "grouped by class": a guarded self-read authored before the first unguarded alternative
 * reports ahead of `MVK_ERROR_EMPTY_PRECONDITION`, one authored after it behind. The number rows suppress the
 * scale gate, which otherwise answers first, and the String rows have none. The projected rows are the
 * control that the projected class stays once per computation.
 *
 * Own domain (a premium and an insurance cover's plan choice).
 */
class SelfReadMultiplicityAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>)

    private val premium = premium()

    private val cover = DocumentModelDraft.named("cover-plan-choice").locale("en_US")
        .rootGroup("Cover") { c -> c
            .enumField("ChosenPlan") { plan(it) }.enumField("OfferedPlan") { plan(it) }.stringField("Holder") { }
        }.build().toJson()

    private fun plan(e: EnumFieldBuilder) = e.value("BASIC").value("PLUS").category("PlanLevel", "BASIC", "PLUS")

    private fun withCommon(common: String, alternatives: List<Alt>) = ModelWriter.addComputation(premium,
        DraftComputation.builder().groupPath("/Premium").name("ProbeComp").computedFieldRelPath("../Total")
            .commonPrecondition(common)
            .alternatives(alternatives.map { DraftComputation.Alternative(it.precondition, it.operation) })
            .errorCodesToSuppress(SUPPRESSED).build(), listOf(Dsl.en("Premium recomputed.")))

    fun rows(): List<Row> = buildList {
        fun number(id: String, vararg alts: Alt, classes: List<String>) =
            add(Row(id, computation(premium, "/Premium", "Total", alts.toList(), SUPPRESSED), classes))
        fun text(id: String, vararg alts: Alt, classes: List<String>) =
            add(Row(id, computation(premium, "/Premium", "Note", alts.toList()), classes))
        fun plan(id: String, vararg alts: Alt, classes: List<String>) =
            add(Row(id, computation(cover, "/Cover", "ChosenPlan", alts.toList()), classes))

        number("count-sum", Alt(null, "[Total] + [Total]"), classes = listOf(REF, REF))
        number("count-extremum", Alt(null, "Max([Total], [Total])"), classes = listOf(REF, REF))
        number("count-wrapped", Alt(null, "Abs([Total]) + Abs([Total])"), classes = listOf(REF, REF))
        number("count-precondition-and-operation", Alt("FieldFilled(Total)", "[Total]"), classes = listOf(REF, REF))
        number("count-three", Alt("FieldFilled(Total)", "[Total] + [Total]"), classes = listOf(REF, REF, REF))
        number("count-precondition-twice", Alt("FieldFilled(Total) or FieldFilled(Total)", "[Base]"), classes = listOf(REF, REF))
        number("count-two-guards", Alt("FieldFilled(Total)", "[Base]"), Alt("FieldFilled(Total)", "[Fee]"), classes = listOf(REF, REF))
        number("count-comparison", Alt("[Total] > 0", "[Base]"), classes = listOf(REF))
        number("count-comparison-sum", Alt("[Total] + [Total] > 0", "[Base]"), classes = listOf(REF, REF))
        number("count-list-and-read", Alt(null, "Sum(Total, Base) + [Total]"), classes = listOf(REF, REF))
        number("count-filled-list-and-read", Alt(null, "NumberOfFilledFields(Total, Base) + [Total]"), classes = listOf(REF, REF))
        number("order-read-then-unguarded", Alt("FieldFilled(Agent)", "[Total]"), Alt(null, "[Base]"), classes = listOf(REF, EMPTY))
        number("order-around-unguarded", Alt("FieldFilled(Agent)", "[Total]"), Alt(null, "[Base]"),
            Alt("FieldFilled(Broker)", "[Total]"), classes = listOf(REF, EMPTY, REF))
        number("order-two-unguarded-after", Alt("FieldFilled(Agent)", "[Total]"), Alt(null, "[Base]"), Alt(null, "[Fee]"),
            classes = listOf(REF, EMPTY))
        number("order-unguarded-first", Alt(null, "[Base]"), Alt("FieldFilled(Agent)", "[Total]"), Alt(null, "[Fee]"),
            classes = listOf(EMPTY, REF))
        number("order-two-reads-then-unguarded", Alt("FieldFilled(Total)", "[Total]"), Alt(null, "[Base]"),
            classes = listOf(REF, REF, EMPTY))
        number("order-three-reads-last", Alt("FieldFilled(Agent)", "[Base]"), Alt(null, "[Fee]"),
            Alt("FieldFilled(Total)", "[Total] + [Total]"), classes = listOf(EMPTY, REF, REF, REF))
        add(Row("common-and-operation", withCommon("FieldFilled(Total)", listOf(Alt(null, "[Total]"))), listOf(REF, REF)))
        add(Row("common-alone", withCommon("FieldFilled(Total)", listOf(Alt(null, "[Base]"))), listOf(REF)))
        add(Row("common-first", withCommon("FieldFilled(Total)", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt(null, "[Base]"))),
            listOf(REF, REF, EMPTY)))
        text("text-count", Alt(null, "[Note] + [Note]"), classes = listOf(REF, REF))
        text("text-precondition-and-operation", Alt("FieldFilled(Note)", "[Note]"), classes = listOf(REF, REF))
        text("text-order", Alt("FieldFilled(Agent)", "[Note]"), Alt(null, "[Note2]"), classes = listOf(REF, EMPTY))
        text("text-order-around", Alt("FieldFilled(Agent)", "[Note] + [Note]"), Alt(null, "[Note2]"),
            Alt("FieldFilled(Note)", "[Note2]"), classes = listOf(REF, REF, EMPTY, REF))
        plan("projected-two-alternatives", Alt("FieldFilled(Holder)", CHOSEN), Alt("FieldFilled(OfferedPlan)", CHOSEN),
            classes = listOf(PROJECTED))
        plan("projected-beside-plain", Alt("FieldFilled(Holder)", CHOSEN), Alt("FieldFilled(OfferedPlan)", "[/Cover/ChosenPlan]"),
            classes = listOf(PROJECTED))
        plan("projected-beside-plain-guard", Alt("FieldFilled(ChosenPlan)", CHOSEN), classes = listOf(PROJECTED))
    }

    @Test
    fun eachJudgedReadIsReportedInTheOrderTheAlternativesAreWalked() {
        val rows = rows()
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "observations retain independent identities")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel classes")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to portable(it.model) }, "portable classes")
    }

    private companion object {
        val SUPPRESSED = listOf("MVK_INVALID_COMPARE_DEC_PLACES")
        const val REF = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"
        const val EMPTY = "MVK_ERROR_EMPTY_PRECONDITION"
        const val PROJECTED = "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD"
        const val CHOSEN = "[/Cover/ChosenPlan -> PlanLevel]"
    }
}
