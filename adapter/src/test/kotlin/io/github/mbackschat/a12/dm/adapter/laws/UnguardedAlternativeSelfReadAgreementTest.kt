package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.portable
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.Alt
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.computation
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.premium
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.EnumFieldBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which alternatives' reads of the computed field A12 judges once a computation has several (KERNEL-FINDINGS
 * KF181), held against the kernel and the portable checker on the carriers where the checker answers.
 *
 * The discriminators are the alternative COUNT against the presence of a guarded alternative: two unguarded
 * alternatives (`two-unguarded…`) separate "from two alternatives on" from "beside a guarded one", and the lone
 * unguarded row is the walked control. The plain rows run on a number target with the scale gate suppressed and
 * on a String target, which has no scale gate, so the class is not the gate's absence. The projected rows read
 * the target through its enum category and show the narrowing does not reach that class: it is reported in every
 * arrangement and answers ahead of the precondition class.
 *
 * Own domain (an insurance cover's plan choice).
 */
class UnguardedAlternativeSelfReadAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>)

    private val premium = premium()

    private val cover = DocumentModelDraft.named("cover-plan-choice").locale("en_US")
        .rootGroup("Cover") { c -> c
            .enumField("ChosenPlan") { plan(it) }.enumField("OfferedPlan") { plan(it) }.stringField("Holder") { }
        }.build().toJson()

    private fun plan(e: EnumFieldBuilder) =
        e.value("BASIC").value("PLUS").category("PlanLevel", "BASIC", "PLUS")

    fun rows(): List<Row> = buildList {
        fun number(id: String, vararg alts: Alt, classes: List<String>) =
            add(Row(id, computation(premium, "/Premium", "Total", alts.toList(), SUPPRESSED), classes))
        fun text(id: String, vararg alts: Alt, classes: List<String>) =
            add(Row(id, computation(premium, "/Premium", "Note", alts.toList()), classes))
        fun plan(id: String, vararg alts: Alt, classes: List<String>) =
            add(Row(id, computation(cover, "/Cover", "ChosenPlan", alts.toList()), classes))

        number("number-lone", Alt(null, "[Total]"), classes = listOf(SELF_READ))
        number("number-two-unguarded", Alt(null, "[Total]"), Alt(null, "[Base]"), classes = listOf(EMPTY))
        number("number-two-unguarded-reversed", Alt(null, "[Base]"), Alt(null, "[Total]"), classes = listOf(EMPTY))
        number("number-two-unguarded-both", Alt(null, "[Total]"), Alt(null, "[Total]"), classes = listOf(EMPTY))
        number("number-unguarded-then-guarded", Alt(null, "[Total]"), Alt("FieldFilled(Broker)", "[Total]"),
            classes = listOf(EMPTY, SELF_READ))
        text("text-lone", Alt(null, "[Note]"), classes = listOf(SELF_READ))
        text("text-two-unguarded", Alt(null, "[Note]"), Alt(null, "[Note2]"), classes = listOf(EMPTY))
        text("text-unguarded-then-guarded", Alt(null, "[Note]"), Alt("FieldFilled(Broker)", "[Note2]"),
            classes = listOf(EMPTY))
        plan("projected-lone", Alt(null, CHOSEN), classes = listOf(PROJECTED))
        plan("projected-two-unguarded", Alt(null, CHOSEN), Alt(null, OFFERED), classes = listOf(PROJECTED))
        plan("projected-two-unguarded-reversed", Alt(null, OFFERED), Alt(null, CHOSEN), classes = listOf(PROJECTED))
        plan("projected-unguarded-then-guarded", Alt(null, CHOSEN), Alt(GUARD, OFFERED), classes = listOf(PROJECTED))
        plan("projected-guarded-then-unguarded", Alt(GUARD, OFFERED), Alt(null, CHOSEN), classes = listOf(PROJECTED))
        plan("plain-two-unguarded", Alt(null, "[/Cover/ChosenPlan]"), Alt(null, OFFERED), classes = listOf(EMPTY))
    }

    @Test
    fun severalAlternativesLeaveAnUnguardedOnesPlainReadUnjudged() {
        val rows = rows()
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "observations retain independent identities")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel classes")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to portable(it.model) }, "portable classes")
    }

    private companion object {
        val SUPPRESSED = listOf("MVK_INVALID_COMPARE_DEC_PLACES")
        const val SELF_READ = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"
        const val EMPTY = "MVK_ERROR_EMPTY_PRECONDITION"
        const val PROJECTED = "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD"
        const val CHOSEN = "[/Cover/ChosenPlan -> PlanLevel]"
        const val OFFERED = "[/Cover/OfferedPlan -> PlanLevel]"
        const val GUARD = "FieldFilled(Holder)"
    }
}
