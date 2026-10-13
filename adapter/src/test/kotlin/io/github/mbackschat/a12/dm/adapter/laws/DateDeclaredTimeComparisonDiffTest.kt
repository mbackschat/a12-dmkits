package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A date literal compared against a TIME-kind field declared as a date (KERNEL-FINDINGS KF211): the literal is a
 * date because its counterpart's declared FORMAT is one, whatever the counterpart's kind. A DATE-kind twin with the
 * same format is the control, so a literal typed by the kind shows as the two rows disagreeing.
 *
 * Own domain (an insurance claim's incident date).
 */
class DateDeclaredTimeComparisonDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("claim-date-declared-time").locale("en_US")
            .rootGroup("Claim") { c -> c.timeField("Incident") { it.format(DOTTED) }.dateField("Reported") { it.format(DOTTED) } }
            .build().toJson())
        val claim = session.group("/Claim")
        for (name in listOf("Incident", "Reported")) {
            val field = claim.date(name)
            claim.addRule {
                it.name("${name}BeforeCutoff").code("${name}BeforeCutoff").errorField(field)
                    .`when`(field.before(Dsl.dateValue("01.01.2024"))).message(Dsl.en("Before the cutoff.")).build()
            }
            claim.addRule {
                it.name("${name}OnDay").code("${name}OnDay").errorField(field)
                    .`when`(field.eq(Dsl.dateValue("05.03.2023"))).message(Dsl.en("On the reference day.")).build()
            }
        }
        session.toJson()
    }

    private val documents = linkedMapOf(
        "the reference day" to """{"Claim":{"Incident":"05.03.2023","Reported":"05.03.2023"}}""",
        "after the cutoff" to """{"Claim":{"Incident":"05.03.2025","Reported":"05.03.2025"}}""",
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** The kernel's verdicts: the TIME-kind field compares as a date, exactly like its DATE-kind twin. */
    @Test
    fun theDeclaredFormatTypesTheLiteral() {
        fun fired(document: String) =
            DocumentDifferential.kernelMessages(model, documents.getValue(document), true).map { it.code }.toSortedSet()
        assertEquals(mapOf(
            "the reference day" to sortedSetOf("IncidentBeforeCutoff", "IncidentOnDay", "ReportedBeforeCutoff", "ReportedOnDay"),
            "after the cutoff" to sortedSetOf<String>(),
        ), documents.keys.associateWith(::fired))
    }

    private companion object {
        const val DOTTED = "dd.MM.yyyy"
    }
}
