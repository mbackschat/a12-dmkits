package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The DATE sibling of KERNEL-FINDINGS KF540's computed-range row: does a dependent read a computed date as computed,
 * or as its yearless target stores it? A full-Date source cannot reach a yearless target (`MVK_INVALID_COMPARE_TO_DATE`),
 * so a yearless New Year's Eve is shifted by a day across the year end into a `dd.MM.` target, and the dependent
 * reads that target's year. Only a Base Year admits that read: without one `YearFromDate` over a yearless date is
 * `MVK_WRONG_DATE_FORMAT_FOR_OP`.
 *
 * Own domain (a policy's renewal reminder).
 */
class ComputedDateStoredReadDiffTest {

    private fun model(baseYear: Int): String {
        val authored = DocumentModelDraft.named("renewal-reminder-$baseYear").locale("en_US")
            .rootGroup("Policy") { p ->
                p.dateField("Renewal") { it.format("dd.MM.") }
                p.dateField("Reminder") { it.format("dd.MM.") }
                p.numberField("ReminderYear") { it.fractionDigits(0) }
            }.build().toJson()
        val document = ModelDocument.read(authored).setTimeZone("UTC")
        document.setBaseYear(baseYear)
        val base = document.toJson()
        val m = BoundModel.ofJson(base)
        return listOf(
            m.computation(m.date("/Policy/Reminder")).name("ReminderComp")
                .calc(Dsl.addDays(m.date("/Policy/Renewal"), 1).expression()).message(Dsl.en("Set the reminder.")).build(),
            m.computation(m.num("/Policy/ReminderYear")).name("ReminderYearComp")
                .calc(Dsl.yearFromDate(m.date("/Policy/Reminder"))).message(Dsl.en("Read the reminder.")).build(),
        ).fold(base) { json, computation -> TypedRules.inject(json, computation) }
    }

    private val documents = linkedMapOf("a renewal on New Year's Eve" to """{"Policy":{"Renewal":"31.12.","ReminderYear":"9"}}""")

    @Test
    fun allThreeEnginesAgree() {
        DocumentDifferential.assertAgree(model(2024), documents)
    }

    @Test
    fun theDependentReadsTheStoredReminder() {
        fun read(baseYear: Int) = DocumentDifferential.kernel(model(baseYear), documents.values.single(), dynamic = true)
            .outcomes.filterKeys { it.endsWith("Reminder") || it.endsWith("ReminderYear") }
        assertEquals(mapOf("Reminder" to "VALUE|01.01.", "ReminderYear" to "VALUE|2024"), read(2024))
    }
}
