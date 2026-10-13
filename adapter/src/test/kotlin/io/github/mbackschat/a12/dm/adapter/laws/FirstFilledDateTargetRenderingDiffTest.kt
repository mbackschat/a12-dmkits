package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `FirstFilledValue` Date computation stores the selected Date in the TARGET's declared format, whatever the
 * sources spell (KERNEL-FINDINGS KF550, a12-kernel-lean SPEC-2026-10-13-01): dotted sources into an ISO target,
 * ISO into dotted, a full Date or a year-month into a year-only target, and a yearless source completed with the
 * Base Year. A fixed list and a starred column are both asked, and every document compares both kernel
 * strategies and the interpreter; the pinned outcomes keep the comparison from being vacuous.
 *
 * Own domain (an insurance claim's reported and confirmed dates).
 */
class FirstFilledDateTargetRenderingDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("claim-first-dates").locale("en_US").baseYear(2024)
            .rootGroup("Claim") { c -> c
                .dateField("ReportedIso") { it.format(ISO) }.dateField("ConfirmedIso") { it.format(ISO) }
                .dateField("ReportedDot") { it.format(DOTTED) }.dateField("ConfirmedDot") { it.format(DOTTED) }
                .dateField("ReportedMonth") { it.format("yyyy-MM") }.dateField("ConfirmedMonth") { it.format("yyyy-MM") }
                .dateField("ReportedDay") { it.format("MM-dd") }.dateField("ConfirmedDay") { it.format("MM-dd") }
                .dateField("DotFromIso") { it.format(DOTTED) }.dateField("IsoFromDot") { it.format(ISO) }
                .dateField("YearFromFull") { it.format("yyyy") }.dateField("YearFromMonth") { it.format("yyyy") }
                .dateField("IsoFromDay") { it.format(ISO) }.dateField("FirstVisitDot") { it.format(DOTTED) }
                .group("Visits") { v -> v.repeatable(3).dateField("VisitOn") { it.format(ISO) } } }
            .build().toJson())
        val claim = session.group("/Claim")
        fun pair(name: String) = Dsl.firstFilledDate(claim.field("Reported$name"), claim.field("Confirmed$name"))
        val selections = listOf(
            "DotFromIso" to pair("Iso"), "IsoFromDot" to pair("Dot"), "YearFromFull" to pair("Iso"),
            "YearFromMonth" to pair("Month"), "IsoFromDay" to pair("Day"),
            "FirstVisitDot" to Dsl.firstFilledDate(claim.flatten("Visits*/VisitOn")),
        )
        for ((target, selection) in selections) claim.addComputation(claim.field(target)) {
            it.name("Compute$target").calc(selection.expression())
                .message(Dsl.en("The first known claim date.")).build()
        }
        session.toJson()
    }

    private fun claim(vararg fields: Pair<String, String>, visits: List<String> = emptyList()) =
        """{"Claim":{""" + (fields.map { (k, v) -> "\"$k\":\"$v\"" } +
            listOf("\"Visits\":[" + visits.joinToString(",") { """{"VisitOn":"$it"}""" } + "]")).joinToString(",") + "}}"

    private val documents = linkedMapOf(
        "every first source filled" to claim("ReportedIso" to "2024-03-05", "ReportedDot" to "05.03.2024",
            "ReportedMonth" to "2024-03", "ReportedDay" to "03-05", visits = listOf("2024-02-01", "2024-02-02")),
        "every first source empty" to claim("ConfirmedIso" to "2023-11-30", "ConfirmedDot" to "30.11.2023",
            "ConfirmedMonth" to "2023-11", "ConfirmedDay" to "11-30", visits = listOf("", "2023-10-09")),
        "every first source malformed" to claim("ReportedIso" to "2024-13-01", "ConfirmedIso" to "2024-03-05",
            "ReportedDot" to "32.01.2024", "ConfirmedDot" to "05.03.2024", "ReportedMonth" to "2024-13",
            "ConfirmedMonth" to "2024-03", "ReportedDay" to "13-01", "ConfirmedDay" to "03-05",
            *SEEDS, visits = listOf("2024-02-30", "2024-02-02")),
        "nothing filled" to claim(*SEEDS),
        "a valid first source before a malformed one" to claim("ReportedIso" to "2024-03-05",
            "ConfirmedIso" to "2024-13-01", "ReportedDot" to "05.03.2024", "ConfirmedDot" to "32.01.2024",
            "ReportedMonth" to "2024-03", "ConfirmedMonth" to "2024-13", "ReportedDay" to "03-05",
            "ConfirmedDay" to "13-01", visits = listOf("2024-02-01", "2024-02-30")),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** The target's format decides the stored text: each crossed pair renders in the target's own spelling. */
    @Test
    fun theTargetFormatRendersTheSelectedDate() {
        assertEquals(mapOf(
            "DotFromIso" to "VALUE|05.03.2024", "IsoFromDot" to "VALUE|2024-03-05", "YearFromFull" to "VALUE|2024",
            "YearFromMonth" to "VALUE|2024", "IsoFromDay" to "VALUE|2024-03-05", "FirstVisitDot" to "VALUE|01.02.2024",
        ), outcomes("every first source filled"))
    }

    /** An empty first source falls through to the next, and a yearless one still takes the Base Year. */
    @Test
    fun anEmptyFirstSourceFallsThrough() {
        assertEquals(mapOf(
            "DotFromIso" to "VALUE|30.11.2023", "IsoFromDot" to "VALUE|2023-11-30", "YearFromFull" to "VALUE|2023",
            "YearFromMonth" to "VALUE|2023", "IsoFromDay" to "VALUE|2024-11-30", "FirstVisitDot" to "VALUE|09.10.2023",
        ), outcomes("every first source empty"))
    }

    /**
     * A malformed first source is reached and poisons the selection, so the seeded target clears although a valid
     * second source follows; with nothing filled the selection is not given and clears it too.
     */
    @Test
    fun aMalformedFirstSourceClearsTheSeededTarget() {
        assertEquals(TARGETS.associateWith { "CLEARED" }, outcomes("every first source malformed"))
        assertEquals(TARGETS.associateWith { "CLEARED" }, outcomes("nothing filled"))
    }

    /** The walk stops at the first filled source: a malformed later source is never read, so nothing is poisoned. */
    @Test
    fun theWalkStopsAtTheFirstFilledSource() {
        assertEquals(outcomes("every first source filled"), outcomes("a valid first source before a malformed one"))
    }

    private fun outcomes(document: String): Map<String, String> =
        DocumentDifferential.kernel(model, documents.getValue(document), dynamic = true).outcomes
            .mapKeys { (key, _) -> key.substringAfterLast('/').substringBefore('[') }
            .filterKeys { it in TARGETS }

    private companion object {
        const val ISO = "yyyy-MM-dd"
        const val DOTTED = "dd.MM.yyyy"
        /** Earlier values in every target, so a clearing computation is observable. */
        val SEEDS = arrayOf("DotFromIso" to "01.01.2020", "IsoFromDot" to "2020-01-01", "YearFromFull" to "2020",
            "YearFromMonth" to "2020", "IsoFromDay" to "2020-01-01", "FirstVisitDot" to "01.01.2020")
        val TARGETS = setOf("DotFromIso", "IsoFromDot", "YearFromFull", "YearFromMonth", "IsoFromDay", "FirstVisitDot")
    }
}
