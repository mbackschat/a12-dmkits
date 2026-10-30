package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Inside one computation a comparison and a sub-day difference read the exact instant a shift keeps across Berlin's
 * fall-back overlap (KERNEL-FINDINGS KF504; a12-kernel-lean SPEC-2026-10-30-10): from `2024-10-27T01:30`,
 * `AddHours(Start, 1)` and `AddHours(Start, 2)` both render `02:30`, yet the first is before the second, differs from
 * a stored `02:30`, and lies 60 minutes from it. KF504's `l1` row measured the kept instant on a chained shift only.
 *
 * The controls are the same day from `00:30` beside a stored `01:30`, where no shift lands in the repeated hour, and
 * a summer day. Every numeric target is seeded `9`, so a cleared precondition shows. Every document compares dynamic
 * Groovy, static Java and the interpreter; the outcomes are pinned from dynamic Groovy. Own domain (a loss
 * adjuster's site visit).
 */
class OverlapInstantInComputationDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("visit-overlap").locale("en_US")
            .rootGroup("Visit") { v ->
                listOf("Start", "Logged").forEach { v.dateTimeField(it) { f -> f.format(ISO_DT) } }
                TARGETS.forEach { v.numberField(it) { f -> f.fractionDigits(0) } }
                v
            }.build().toJson())
        session.config().timeZone("Europe/Berlin")
        val visit = session.group("/Visit")
        val start = visit.dateTime("Start")
        val logged = visit.dateTime("Logged")
        val firstHour = Dsl.addHours(start, 1)
        val secondHour = Dsl.addHours(start, 2)
        fun flag(target: String, precondition: Condition) = visit.addComputation(visit.num(target)) {
            it.name("${target}Comp").`when`(precondition).calc(Dsl.number(1)).message(Dsl.en("Check $target.")).build()
        }
        fun compute(target: String, calc: NumberExpr) = visit.addComputation(visit.num(target)) {
            it.name("${target}Comp").calc(calc).message(Dsl.en("Check $target.")).build()
        }
        flag("Ordered", firstHour.before(secondHour))
        flag("Matches", firstHour.eq(logged))
        flag("Differs", firstHour.ne(logged))
        compute("Days", Dsl.differenceInDays(start, Dsl.addHours(start, 24)))
        compute("Minutes", Dsl.differenceInMinutes(firstHour, secondHour))
        visit.addRule {
            it.name("loggedAtFirstHour").code("LOGGED_AT_FIRST_HOUR").errorField(logged).`when`(firstHour.eq(logged))
                .message(Dsl.en("The visit was logged an hour after its start.")).build()
        }
        session.toJson()
    }

    private fun visit(start: String, logged: String): String =
        """{"Visit":{"Start":"$start","Logged":"$logged",${TARGETS.joinToString(",") { "\"$it\":9" }}}}"""

    private val documents = linkedMapOf(
        "overlap" to visit("2024-10-27T01:30:00", "2024-10-27T02:30:00"),
        "before the overlap" to visit("2024-10-27T00:30:00", "2024-10-27T01:30:00"),
        "summer" to visit("2024-06-01T01:30:00", "2024-06-01T02:30:00"),
    )

    @Test
    fun a12AndThePortableCheckerAdmitTheModel() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
        assertEquals(emptyList<String>(), rejectingCodes(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() = DocumentDifferential.assertAgree(model, documents)

    /** Both shifts render `02:30` in the overlap, yet they are ordered, 60 minutes apart, and unequal to a stored `02:30`. */
    @Test
    fun theComputationReadsTheKeptInstant() {
        assertEquals(mapOf("Ordered" to "VALUE|1", "Matches" to "CLEARED", "Differs" to "VALUE|1",
            "Days" to "VALUE|0", "Minutes" to "VALUE|60"), outcomes("overlap"))
        assertEquals(emptyList<String>(), firings("overlap"))
    }

    @Test
    fun theControlsSeeEquality() {
        assertEquals(mapOf("Ordered" to "VALUE|1", "Matches" to "VALUE|1", "Differs" to "CLEARED",
            "Days" to "VALUE|0", "Minutes" to "VALUE|60"), outcomes("before the overlap"))
        assertEquals(mapOf("Ordered" to "VALUE|1", "Matches" to "VALUE|1", "Differs" to "CLEARED",
            "Days" to "VALUE|1", "Minutes" to "VALUE|60"), outcomes("summer"))
        assertEquals(listOf("LOGGED_AT_FIRST_HOUR"), firings("before the overlap"))
        assertEquals(listOf("LOGGED_AT_FIRST_HOUR"), firings("summer"))
    }

    private fun observed(document: String) = DocumentDifferential.kernel(model, documents.getValue(document), dynamic = true)

    private fun outcomes(document: String) = observed(document).outcomes.mapKeys { (key, _) -> key.substringAfterLast('/') }

    private fun firings(document: String) = observed(document).messages.map { it.code }.filter { it == "LOGGED_AT_FIRST_HOUR" }

    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"
        val TARGETS = listOf("Ordered", "Matches", "Differs", "Days", "Minutes")
    }
}
