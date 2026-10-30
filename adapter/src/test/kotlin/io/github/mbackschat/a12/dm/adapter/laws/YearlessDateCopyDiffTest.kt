package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A yearless Date field copied as it is into a year-bearing Date target (KERNEL-FINDINGS KF550, a12-kernel-lean
 * SPEC-2026-10-30-02): A12 admits the copy only under a Base Year, and the Base Year then supplies the year, the
 * target's format renders the result, and a month alone completes its day to the first. A day the Base Year makes
 * unreal, a malformed source and an absent one each clear the seeded target.
 *
 * Five yearless spellings each copied into four year-bearing targets, under Base Years 2023 and 2024. Every document
 * compares dynamic Groovy, static Java and the interpreter; the outcomes are pinned from dynamic Groovy so the
 * comparison cannot pass by all three agreeing on nothing. Own domain (an insurance policy's renewal day).
 */
class YearlessDateCopyDiffTest {

    private fun model(baseYear: Int): String {
        val draft = DocumentModelDraft.named("policy-renewal").locale("en_US").baseYear(baseYear)
        val session = EditSession.read(draft.rootGroup("Renewal") { r ->
            SOURCES.forEach { (source, format) -> r.dateField(source) { it.format(format) } }
            for (source in SOURCES.keys) for ((target, format) in TARGETS) r.dateField("$source$target") { it.format(format) }
            r
        }.build().toJson())
        val renewal = session.group("/Renewal")
        for (source in SOURCES.keys) for (target in TARGETS.keys) {
            renewal.addComputation(renewal.date("$source$target")) {
                it.name("Copy$source$target").calc(renewal.date(source).expression())
                    .message(Dsl.en("Check the renewal date.")).build()
            }
        }
        return session.toJson()
    }

    private val byYear = mapOf(2023 to model(2023), 2024 to model(2024))

    /** The 2023 model with its Base Year removed, differing in that member alone. */
    private val withoutBaseYear = (ObjectMapper().readTree(byYear.getValue(2023)) as ObjectNode)
        .also { (it.at("/content/modelInfo") as ObjectNode).remove("baseYear") }.toString()

    /** Every target seeded, so a clearing copy is observable, then the five sources as given. */
    private fun renewal(vararg sources: String): String {
        val seeds = SOURCES.keys.flatMap { s -> TARGETS.map { (t, _) -> "\"$s$t\":\"${SEED.getValue(t)}\"" } }
        val given = SOURCES.keys.zip(sources.toList()).filter { (_, v) -> v.isNotEmpty() }.map { (s, v) -> "\"$s\":\"$v\"" }
        return """{"Renewal":{${(seeds + given).joinToString(",")}}}"""
    }

    private val documents = linkedMapOf(
        2023 to "June eleventh" to renewal("06-11", "11.06.", "0611", "1106", "06"),
        2024 to "March fifth" to renewal("03-05", "05.03.", "0305", "0503", "03"),
        2024 to "a leap day" to renewal("02-29", "29.02.", "0229", "2902", "02"),
        2023 to "a leap day" to renewal("02-29", "29.02.", "0229", "2902", "02"),
        2023 to "malformed" to renewal("13-45", "45.13.", "1345", "4513", "13"),
        2024 to "absent" to renewal("", "", "", "", ""),
    )

    /** Without a Base Year A12 refuses every one of the twenty copies, whatever components either side has. */
    @Test
    fun withoutABaseYearEveryCopyIsRefused() {
        assertEquals(List(20) { "MVK_INVALID_COMPARE_TO_DATE" }, MessageParameterProbes.kernel(withoutBaseYear))
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(byYear.getValue(2023)))
    }

    /** The portable checker names A12's class on every copy of the twin and nothing under the Base Year. */
    @Test
    fun thePortableCheckerJudgesEveryCopyAsA12Does() {
        assertEquals(List(20) { "MVK_INVALID_COMPARE_TO_DATE" }, rejectingCodes(withoutBaseYear))
        assertEquals(emptyList<String>(), rejectingCodes(byYear.getValue(2023)))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        for ((year, model) in byYear) {
            DocumentDifferential.assertAgree(model, documents.filterKeys { it.first == year }.mapKeys { (key, _) -> key.second })
        }
    }

    /** The Base Year supplies the year and the target renders it; a month alone completes to the first. */
    @Test
    fun theBaseYearCompletesTheDayInTheTargetsFormat() {
        assertEquals(expected("2023", "06", "11"), outcomes(2023, "June eleventh"))
        assertEquals(expected("2024", "03", "05"), outcomes(2024, "March fifth"))
        assertEquals(expected("2024", "02", "29"), outcomes(2024, "a leap day"))
    }

    /** A day the Base Year makes unreal clears every target it feeds; the month alone still stores its first. */
    @Test
    fun aDayTheBaseYearMakesUnrealClearsItsTargets() {
        val cleared = expected("2023", "02", "01").mapValues { (target, value) -> if (target.startsWith("Month")) value else CLEARED }
        assertEquals(cleared, outcomes(2023, "a leap day"))
    }

    @Test
    fun aMalformedOrAbsentSourceClearsItsTargets() {
        val all = expected("2023", "01", "01").mapValues { CLEARED }
        assertEquals(all, outcomes(2023, "malformed"))
        assertEquals(all, outcomes(2024, "absent"))
    }

    /** Each target's text for a source completed to [year]-[month]-[day], the month-only source to the first. */
    private fun expected(year: String, month: String, day: String): Map<String, String> =
        SOURCES.keys.flatMap { source ->
            val d = if (source == "Month") "01" else day
            TARGETS.keys.map { target ->
                "$source$target" to "VALUE|" + when (target) {
                    "Iso" -> "$year-$month-$d"
                    "Dotted" -> "$d.$month.$year"
                    "YearMonth" -> "$year-$month"
                    else -> year
                }
            }
        }.toMap()

    private fun outcomes(year: Int, document: String): Map<String, String> =
        DocumentDifferential.kernel(byYear.getValue(year), documents.getValue(year to document), dynamic = true).outcomes
            .mapKeys { (key, _) -> key.substringAfterLast('/').substringBefore('[') }

    private companion object {
        /** The five yearless spellings, each the source of four copies. */
        val SOURCES = linkedMapOf("Dash" to "MM-dd", "Dot" to "dd.MM.", "Compact" to "MMdd", "DayFirst" to "ddMM", "Month" to "MM")
        val TARGETS = linkedMapOf("Iso" to "yyyy-MM-dd", "Dotted" to "dd.MM.yyyy", "YearMonth" to "yyyy-MM", "Year" to "yyyy")
        val SEED = mapOf("Iso" to "2020-01-01", "Dotted" to "01.01.2020", "YearMonth" to "2020-01", "Year" to "2020")
        const val CLEARED = "CLEARED"
    }
}
