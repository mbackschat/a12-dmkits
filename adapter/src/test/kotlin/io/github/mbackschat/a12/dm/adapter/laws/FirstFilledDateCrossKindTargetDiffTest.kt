package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelAdapter
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A computed value stored into a temporal target whose declared FORMAT belongs to another kind (KERNEL-FINDINGS
 * KF222, KF550, IF397): the format string, not the declared kind, renders the stored text. A `FirstFilledValue`
 * Date goes into TIME-kind targets declared ISO and dotted, with a DATE-kind dotted target as the control, so a
 * store keyed on the kind shows as the two dotted rows disagreeing; constants cross the kinds both ways. The same
 * selection also goes into a DATE_TIME-kind target declared as a date; a DATE-kind target declared as a date-time
 * cannot take it at all, which [aDateTimeDeclaredDateTargetRefusesTheSelection] holds.
 *
 * Own domain (an insurance claim's reported and confirmed dates).
 */
class FirstFilledDateCrossKindTargetDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("claim-first-dates-cross-kind").locale("en_US")
            .rootGroup("Claim") { c -> c
                .dateField("Reported") { it.format(ISO) }.dateField("Confirmed") { it.format(ISO) }
                .timeField("TimeIso") { it.format(ISO) }.timeField("TimeDot") { it.format(DOTTED) }
                .dateField("DateDot") { it.format(DOTTED) }.dateField("Alternate") { it.format(DOTTED) }
                .dateTimeField("StampDot") { it.format(DOTTED) }
                .timeField("ConstIso") { it.format(ISO) }.timeField("ConstDot") { it.format(DOTTED) }
                .dateField("ConstClock") { it.format("HH:mm:ss") }
                .dateField("Chained") { it.format(ISO) }
                .dateField("FirstSeen") { it.format(DOTTED) }.dateField("FirstNoted") { it.format(DOTTED) }
                .group("Visits") { v -> v.repeatable(3).timeField("SeenOn") { it.format(DOTTED) } }
                .group("Review") { r -> r.timeField("NotedOn") { it.format(DOTTED) } } }
            .build().toJson())
        val claim = session.group("/Claim")
        for (target in TARGETS.take(3) + CROSS_KIND) claim.addComputation(claim.date(target)) {
            it.name("Compute$target").calc(Dsl.firstFilledDate(claim.date("Reported"), claim.date("Confirmed")))
                .message(Dsl.en("The first known claim date.")).build()
        }
        // A downstream computation reads the computed TIME-kind dotted cell back, through the cascade's overlay. Its
        // fallback holds another date, so the result shows whether the TIME-kind cell was read at all.
        claim.addComputation(claim.date("Chained")) {
            it.name("ComputeChained").calc(Dsl.firstFilledDate(claim.date("TimeDot"), claim.date("Alternate")))
                .message(Dsl.en("The first known claim date, read back.")).build()
        }
        // A rule comparing against the same selection, so the comparison side of the reader is asked too.
        claim.addRule {
            it.name("ReportedAfterFirst").code("REPORTED_AFTER_FIRST").errorField(claim.date("Reported"))
                .`when`(claim.date("Reported").after(Dsl.firstFilledDate(claim.date("TimeDot"), claim.date("Alternate"))))
                .message(Dsl.en("The report follows the first known date.")).build()
        }
        // Constants, whose literal text is the subject (KF222's cross-kind stores): a dotted date into a TIME field
        // declared ISO and one declared dotted, and a clock into a DATE field declared as a clock. The starred list
        // and the group of TIME-kind cells declared as dates are raw too: the typed surface refuses both, although
        // A12 admits them (DEV-API-GAPS DG129).
        listOf("ConstIso" to "\"05.03.2024\"", "ConstDot" to "\"05.03.2024\"", "ConstClock" to "\"12:30:00\"",
            "FirstSeen" to "FirstFilledValue(Visits*/SeenOn)", "FirstNoted" to "FirstFilledValue(Review)")
            .fold(session.toJson()) { json, (target, literal) ->
                RuntimeLaws.withComputation(json, "/Claim", "Assign$target", "../$target", literal)
            }
    }

    private val documents = linkedMapOf(
        "the first source filled" to """{"Claim":{"Reported":"2024-03-05","Confirmed":"2023-11-30","Alternate":"15.06.2022",
            "Visits":[{},{"SeenOn":"20.02.2021"}],"Review":{"NotedOn":"11.11.2021"}}}""",
        "the first source empty" to """{"Claim":{"Confirmed":"2023-11-30"}}""",
        "nothing filled" to
            """{"Claim":{"TimeIso":"2020-01-01","TimeDot":"01.01.2020","DateDot":"01.01.2020","Alternate":"15.06.2022"}}""",
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

    /** The adapter reads both Date selections back typed: a TIME-kind dotted operand is a Date (DG128). */
    @Test
    fun theChainedSelectionReadsBackTyped() {
        val adapter = KernelAdapter.ofJson(model)
        val read = adapter.readComputation("/Claim/ComputeChained")
        assertTrue(read is ReadComputation.Converted, "the chained Date selection reads typed, got: $read")
        val rule = adapter.read("/Claim/ReportedAfterFirst")
        assertTrue(rule is ReadResult.Converted, "the compared Date selection reads typed, got: $rule")
    }

    /** A TIME-kind target declared as a date takes a Date selection, so it reads back typed like the DATE control. */
    @Test
    fun everyDateDeclaredTargetReadsBackTyped() {
        val adapter = KernelAdapter.ofJson(model)
        val computations = TARGETS.take(3).map { "Compute$it" }
        assertEquals(computations.associateWith { true },
            computations.associateWith { adapter.readComputation("/Claim/$it") is ReadComputation.Converted })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * Both kinds render the selected Date in the declared format, and the chained selection reads the TIME-kind
     * cell: the computed `05.03.2024` where it was stored, the fallback's `15.06.2022` where the cell was cleared.
     */
    @Test
    fun theDeclaredFormatRendersOnEitherKind() {
        assertEquals(mapOf("TimeIso" to "VALUE|2024-03-05", "TimeDot" to "VALUE|05.03.2024", "DateDot" to "VALUE|05.03.2024",
            "ConstIso" to "VALUE|2024-03-05", "ConstDot" to "VALUE|05.03.2024", "ConstClock" to "VALUE|12:30:00",
            "Chained" to "VALUE|2024-03-05", "FirstSeen" to "VALUE|20.02.2021", "FirstNoted" to "VALUE|11.11.2021"),
            outcomes("the first source filled"))
        assertEquals(mapOf("TimeIso" to "VALUE|2023-11-30", "TimeDot" to "VALUE|30.11.2023", "DateDot" to "VALUE|30.11.2023"),
            outcomes("the first source empty").filterKeys { it in TARGETS.take(3) })
        assertEquals("VALUE|2022-06-15", outcomes("nothing filled")["Chained"])
    }

    /** A DATE_TIME-kind target declared as a date takes the selection in its declared format (KF550). */
    @Test
    fun aDateDeclaredDateTimeTargetRendersTheDeclaredFormat() {
        val observed = documents.keys.associateWith { outcomes(it, CROSS_KIND) }
        assertEquals(EXPECTED_CROSS_KIND, observed)
    }

    /**
     * The other crossing is refused at admission: a Date selection is not a "fitting date" for a DATE-kind target
     * declared `yyyy-MM-dd'T'HH:mm:ss`, so no document ever stores into one (KF550). The portable checker is asked the
     * same model.
     */
    @Test
    fun aDateTimeDeclaredDateTargetRefusesTheSelection() {
        // Raw, because the typed session asks A12 first and refuses to add it.
        val refused = RuntimeLaws.withComputation(DocumentModelDraft.named("claim-first-date-stamp").locale("en_US")
            .rootGroup("Claim") { c -> c
                .dateField("Reported") { it.format(ISO) }.dateField("Confirmed") { it.format(ISO) }
                .dateField("DateStamp") { it.format(STAMP) } }
            .build().toJson(), "/Claim", "ComputeDateStamp", "../DateStamp", "FirstFilledValue(Reported, Confirmed)")
        val kernel = MessageParameterProbes.kernel(refused)
        val portable = check(ModelSource(refused)).findings.filter { it.justifiesRejection }.map { it.code }
        assertTrue(kernel.any { "MVK_INVALID_COMPARE_TO_DATE" in it }, "A12: $kernel")
        assertEquals(listOf("MVK_INVALID_COMPARE_TO_DATE"), portable, "the portable checker")
    }

    private fun outcomes(document: String, targets: List<String> = TARGETS): Map<String, String> =
        DocumentDifferential.kernel(model, documents.getValue(document), dynamic = true).outcomes
            .mapKeys { (key, _) -> key.substringAfterLast('/').substringBefore('[') }
            .filterKeys { it in targets }

    private companion object {
        const val ISO = "yyyy-MM-dd"
        const val DOTTED = "dd.MM.yyyy"
        const val STAMP = "yyyy-MM-dd'T'HH:mm:ss"
        val CROSS_KIND = listOf("StampDot")
        val EXPECTED_CROSS_KIND = mapOf("the first source filled" to mapOf("StampDot" to "VALUE|05.03.2024"),
            "the first source empty" to mapOf("StampDot" to "VALUE|30.11.2023"), "nothing filled" to emptyMap())
        val TARGETS = listOf("TimeIso", "TimeDot", "DateDot", "ConstIso", "ConstDot", "ConstClock", "Chained", "FirstSeen",
            "FirstNoted")
    }
}
