package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A computed value stored into a temporal target whose declared FORMAT belongs to another kind (KERNEL-FINDINGS
 * KF222, KF550, IF397): the format string, not the declared kind, renders the stored text. A `FirstFilledValue`
 * Date goes into TIME-kind targets declared ISO and dotted, with a DATE-kind dotted target as the control, so a
 * store keyed on the kind shows as the two dotted rows disagreeing; constants cross the kinds both ways.
 *
 * Own domain (an insurance claim's reported and confirmed dates).
 */
class FirstFilledDateCrossKindTargetDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("claim-first-dates-cross-kind").locale("en_US")
            .rootGroup("Claim") { c -> c
                .dateField("Reported") { it.format(ISO) }.dateField("Confirmed") { it.format(ISO) }
                .timeField("TimeIso") { it.format(ISO) }.timeField("TimeDot") { it.format(DOTTED) }
                .dateField("DateDot") { it.format(DOTTED) }
                .timeField("ConstIso") { it.format(ISO) }.timeField("ConstDot") { it.format(DOTTED) }
                .dateField("ConstClock") { it.format("HH:mm:ss") }
                .dateField("Chained") { it.format(ISO) } }
            .build().toJson())
        val claim = session.group("/Claim")
        for (target in TARGETS.take(3)) claim.addComputation(claim.field(target)) {
            it.name("Compute$target").calc(Dsl.firstFilledDate(claim.field("Reported"), claim.field("Confirmed")).expression())
                .message(Dsl.en("The first known claim date.")).build()
        }
        // Constants, whose literal text is the subject (KF222's cross-kind stores): a dotted date into a TIME field
        // declared ISO and one declared dotted, and a clock into a DATE field declared as a clock. Last, a downstream
        // computation reads the computed TIME-kind dotted cell back through the cascade's overlay; it is text because
        // `Dsl.firstFilledDate` refuses a TIME-kind operand whatever its declared format (DEV-API-GAPS DG128).
        listOf("ConstIso" to "\"05.03.2024\"", "ConstDot" to "\"05.03.2024\"", "ConstClock" to "\"12:30:00\"",
            "Chained" to "FirstFilledValue(TimeDot, DateDot)")
            .fold(session.toJson()) { json, (target, literal) ->
                RuntimeLaws.withComputation(json, "/Claim", "Assign$target", "../$target", literal)
            }
    }

    private val documents = linkedMapOf(
        "the first source filled" to """{"Claim":{"Reported":"2024-03-05","Confirmed":"2023-11-30"}}""",
        "the first source empty" to """{"Claim":{"Confirmed":"2023-11-30"}}""",
        "nothing filled" to """{"Claim":{"TimeIso":"2020-01-01","TimeDot":"01.01.2020","DateDot":"01.01.2020"}}""",
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    /** The portable checker admits the model too: it judges the cross-kind targets by their format, as A12 does. */
    @Test
    fun thePortableCheckerAdmitsTheModel() {
        assertEquals(emptyList<String>(), check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** Both kinds render the selected Date in the declared format. */
    @Test
    fun theDeclaredFormatRendersOnEitherKind() {
        assertEquals(mapOf("TimeIso" to "VALUE|2024-03-05", "TimeDot" to "VALUE|05.03.2024", "DateDot" to "VALUE|05.03.2024",
            "ConstIso" to "VALUE|2024-03-05", "ConstDot" to "VALUE|05.03.2024", "ConstClock" to "VALUE|12:30:00",
            "Chained" to "VALUE|2024-03-05"),
            outcomes("the first source filled"))
        assertEquals(mapOf("TimeIso" to "VALUE|2023-11-30", "TimeDot" to "VALUE|30.11.2023", "DateDot" to "VALUE|30.11.2023"),
            outcomes("the first source empty").filterKeys { it in TARGETS.take(3) })
    }

    private fun outcomes(document: String): Map<String, String> =
        DocumentDifferential.kernel(model, documents.getValue(document), dynamic = true).outcomes
            .mapKeys { (key, _) -> key.substringAfterLast('/').substringBefore('[') }
            .filterKeys { it in TARGETS }

    private companion object {
        const val ISO = "yyyy-MM-dd"
        const val DOTTED = "dd.MM.yyyy"
        val TARGETS = listOf("TimeIso", "TimeDot", "DateDot", "ConstIso", "ConstDot", "ConstClock", "Chained")
    }
}
