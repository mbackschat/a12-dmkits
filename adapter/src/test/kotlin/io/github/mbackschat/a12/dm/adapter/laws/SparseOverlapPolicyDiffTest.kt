package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Scalar-list overlap opts into sparse traversal; any-pair overlap and value counts stay dense (SPEC-2026-10-10-02). */
class SparseOverlapPolicyDiffTest {
    private val targets = listOf("OneExact", "OneAnnual", "AnyExact", "AnyAnnual", "Numbers", "Modes")
    private val model = underUtc {
        val draft = DocumentModelDraft.named("booking-overlap-policy").locale("en_US")
            .rootGroup("Bookings") { root ->
                root.dateRangeField("Reservation") { it.format("yyyy-MM-dd").separator("/") }
                root.dateRangeField("AnnualReservation") { it.format("MM-dd").separator("/") }
                for (target in targets) root.numberField(target) { it.fractionDigits(0) }
                root.group("Locations") { locations -> locations.repeatable(2).group("Periods") { periods ->
                    periods.repeatable(51).numberField("Permit") { it.fractionDigits(0) }.stringField("Mode") { }
                        .dateTimeField("RecordedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                    for (name in listOf("PairWindow", "ListWindow"))
                        periods.dateRangeField(name) { it.format("yyyy-MM-dd").separator("/") }
                    for (name in listOf("PairAnnual", "ListAnnual"))
                        periods.dateRangeField(name) { it.format("MM-dd").separator("/") }
                } }
            }.build().toJson()
        val edit = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val root = edit.group("/Bookings")
        val periods = edit.group("/Bookings/Locations/Periods")
        val filter = periods.num("Permit").filled().or(periods.dateTime("RecordedAt").eq(Dsl.dateTimeValue("29.02.1500T10:00:00")))
        fun kept(field: String) = root.flatten("Locations*/Periods*/$field").having(filter)
        val guards = linkedMapOf(
            "OneExact" to Dsl.atLeastOneDateRangeOverlaps(root.dateRange("Reservation").field(), kept("ListWindow")),
            "OneAnnual" to Dsl.atLeastOneDateRangeOverlaps(root.dateRange("AnnualReservation").field(), kept("ListAnnual")),
            "AnyExact" to Dsl.dateRangesOverlap(kept("PairWindow")),
            "AnyAnnual" to Dsl.dateRangesOverlap(kept("PairAnnual")),
        )
        var json = edit.toJson()
        for ((name, guard) in guards) json = TypedRules.inject(json, root.computation(root.num(name)) {
            it.name(name + "Comp").commonPrecondition(guard).calc(NumberLiteral.of("1"))
                .message(Dsl.en("Check the booking windows.")).build()
        })
        for ((name, operation) in listOf("Numbers" to Dsl.numberOfValueInFields(1L, kept("Permit")),
                "Modes" to Dsl.numberOfValueInFields("reserved", kept("Mode")))) {
            json = TypedRules.inject(json, root.computation(root.num(name)) {
                it.name(name + "Comp").calc(operation).message(Dsl.en("Check reserved periods.")).build()
            })
        }
        json
    }

    private fun document(width: Int, blank: Boolean): String {
        val pair = """"PairWindow":"2025-05-01/2025-05-12","PairAnnual":"05-01/05-12""""
        val selected = """"ListWindow":"2025-05-01/2025-05-12","ListAnnual":"05-01/05-12""""
        val values = """"Permit":1,"Mode":"reserved","RecordedAt":"2024-03-31T01:30:00""""
        val first = List(width) { n -> "{$values${if (n == 0) ",$pair" else ""}}" }.joinToString(",")
        val seeds = targets.joinToString(",") { "\"$it\":99" }
        return """{"Bookings":{$seeds,"Reservation":"2025-05-04/2025-05-06","AnnualReservation":"05-04/05-06",
            "Locations":[{"Periods":[$first]},{"Periods":[{$values,$pair,$selected}${if (blank) ",{}" else ""}]}]}}"""
    }

    @Test
    fun scalarOverlapUsesSparseSelectionWhilePairOverlapAndValueCountsStayDense() = underUtc {
        val mismatches = mutableListOf<String>()
        for ((width, blank) in listOf(1 to false, 50 to false, 51 to false, 51 to true)) {
            val id = "width=$width blank=$blank"
            val json = document(width, blank)
            val expected = targets.associateWith { target -> when {
                width == 1 -> if (target in listOf("Numbers", "Modes")) "VALUE|2" else "VALUE|1"
                width == 51 && !blank && target.startsWith("One") -> "VALUE|1"
                else -> "CLEARED"
            } }
            val kernel = DocumentDifferential.kernel(model, json, dynamic = true)
            assertEquals(expected, kernel.outcomes, id)
            for ((target, outcome) in expected) assertEquals(listOf(if (outcome == "CLEARED") "VALUE_ERROR" else "OMISSION_ERROR"),
                kernel.messages.filter { it.code == target + "Comp" }.map { it.type }, "$id/$target validation")
            assertEquals(kernel, DocumentDifferential.kernel(model, json, dynamic = false), id)
            val actual = DocumentDifferential.interpreter(model, json)
            if (kernel != actual) mismatches += "$id: expected=$kernel actual=$actual"
        }
        assertEquals(emptyList<String>(), mismatches)
    }
}
