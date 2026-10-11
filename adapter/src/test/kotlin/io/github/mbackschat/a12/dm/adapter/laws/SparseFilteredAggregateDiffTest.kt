package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Cache widths, phase-visible occupancy and kept-successor order are independent axes (SPEC-2026-10-10-02). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SparseFilteredAggregateDiffTest {
    private enum class Foreign { UNREAD, READ, DEEP }
    private val models = Foreign.entries.associateWith(::model)

    private fun model(foreign: Foreign): String = underUtc {
        val draft = DocumentModelDraft.named("dispatch-sparse-filter").locale("en_US")
            .rootGroup("Dispatch") { root ->
                for (name in listOf("Total", "Filled", "Strict", "First", "ForeignCount"))
                    root.numberField(name) { it.fractionDigits(0) }
                root.group("Runs") { runs ->
                    runs.repeatable(2).numberField("Subtotal") { it.fractionDigits(0) }
                        .group("Parcels") { parcels ->
                            parcels.repeatable(51).numberField("Permit") { it.fractionDigits(0) }
                                .numberField("Charge") { it.fractionDigits(0) }
                                .dateTimeField("RecordedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                        }
                }
                root.group("Returns") { returns ->
                    returns.repeatable(2).group("Labels") { it.repeatable(51).stringField("Note") { } }
                        .group("Box") { box -> box.group("Labels") { it.repeatable(51).stringField("Note") { } } }
                }
            }.build().toJson()
        val edit = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val root = edit.group("/Dispatch")
        val runs = edit.group("/Dispatch/Runs")
        val parcels = edit.group("/Dispatch/Runs/Parcels")
        val permit = parcels.num("Permit")
        // Static admission under UTC; the model's Berlin zone makes this missing-slot read fail (KF528).
        val poison = parcels.dateTime("RecordedAt").eq(Dsl.dateTimeValue("31.03.2024T02:30:00"))
        val presence = permit.filled().or(poison)
        val strict = permit.eq(1).or(permit.notFilled().and(poison))
        val values = root.flatten("Runs*/Parcels*/Charge")
        val operations: Map<String, NumberExpr> = linkedMapOf(
            "Total" to Dsl.sum(values.having(presence)),
            "Filled" to Dsl.numberOfFilledFields(values.having(presence)),
            "Strict" to Dsl.sum(values.having(strict)),
            "First" to Dsl.firstFilledNumber(values.having(presence)),
        )
        var json = edit.toJson()
        for ((name, operation) in operations) json = TypedRules.inject(json, root.computation(root.num(name)) {
            it.name(name + "Comp").calc(operation).message(Dsl.en("Check dispatch charges.")).build()
        })
        json = TypedRules.inject(json, runs.computation(runs.num("Subtotal")) {
            it.name("SubtotalComp").calc(Dsl.sum(runs.flatten("Parcels*/Charge").having(presence)))
                .message(Dsl.en("Check this run.")).build()
        })
        if (foreign != Foreign.UNREAD) json = TypedRules.inject(json, root.computation(root.num("ForeignCount")) {
            val path = when (foreign) {
                Foreign.READ -> "Returns*/Labels*/Note"
                Foreign.DEEP -> "Returns*/Box/Labels*/Note"
                Foreign.UNREAD -> error("No foreign reader")
            }
            it.name("ForeignCountComp").calc(Dsl.numberOfFilledFields(root.flatten(path)))
                .message(Dsl.en("Check return labels.")).build()
        })
        json
    }

    private fun parcel(permit: Int = 1, charge: Int? = null) =
        """{"Permit":$permit,"RecordedAt":"2024-03-31T01:30:00"${charge?.let { ",\"Charge\":$it" } ?: ""}}"""

    private fun document(width: Int = 51, permit: Int = 1, firstPermit: Int = 1,
                         blank: Boolean = false, foreign: Boolean = false, deep: Boolean = false,
                         early: Boolean = false, poisonSuccessor: Boolean = false): String {
        val first = List(width) { n ->
            if (poisonSuccessor && n == 1) "{}" else parcel(firstPermit, if (early && n == 0) 5 else null)
        }
        val tail = listOf(parcel(permit, 5)) + if (blank) listOf("{}") else emptyList()
        val labels = """"Labels":[{"Note":"A"},{"Note":"B"}]"""
        val returns = if (foreign) """, "Returns":[{}, {${if (deep) "\"Box\":{$labels}" else labels}}]""" else ""
        return """{"Dispatch":{"Total":99,"Filled":99,"Strict":99,"First":99,"ForeignCount":99,
            "Runs":[{"Subtotal":99,"Parcels":[${first.joinToString(",")}]},
                    {"Subtotal":99,"Parcels":[${tail.joinToString(",")}]}]$returns}}"""
    }

    @Test
    fun sparseAggregatesRespectCacheWidthOccupancyAndKeptCursor() = underUtc {
        data class Case(val id: String, val json: String, val foreign: Foreign = Foreign.UNREAD,
                        val total: String = "VALUE|5", val filled: String = "VALUE|1",
                        val strict: String = total, val first: String = "CLEARED")
        val cases = listOf(
            Case("98 candidates", document(49), total = "CLEARED", filled = "CLEARED"),
            Case("100 candidates", document(50), total = "CLEARED", filled = "CLEARED"),
            Case("102 candidates", document()),
            Case("reset rejected", document(permit = 2), strict = "CLEARED"),
            Case("initial rejected", document(permit = 2, firstPermit = 2), strict = "CLEARED"),
            Case("blank is occupied", document(blank = true), total = "CLEARED", filled = "CLEARED"),
            Case("foreign unread", document(foreign = true)),
            Case("foreign read", document(foreign = true), Foreign.READ, "CLEARED", "CLEARED"),
            Case("deeper foreign read", document(foreign = true, deep = true), Foreign.DEEP),
            Case("early payload", document(early = true), total = "VALUE|10", filled = "VALUE|2", first = "VALUE|5"),
            Case("immediate successor failure", document(early = true, poisonSuccessor = true), total = "CLEARED", filled = "CLEARED"),
        )
        val mismatches = mutableListOf<String>()
        for (case in cases) {
            val model = models.getValue(case.foreign)
            val dynamic = DocumentDifferential.kernel(model, case.json, dynamic = true)
            val expected = mapOf("Total" to case.total, "Filled" to case.filled, "Strict" to case.strict,
                "First" to case.first, "Runs[2]/Subtotal" to "CLEARED")
            assertEquals(expected, dynamic.outcomes.filterKeys { it in expected }, case.id)
            for (name in listOf("Total", "Filled", "Strict")) {
                val failed = expected.getValue(name) == "CLEARED" || case.id == "foreign unread"
                assertEquals(listOf(if (failed) "VALUE_ERROR" else "OMISSION_ERROR"),
                    dynamic.messages.filter { it.code == name + "Comp" }.map { it.type }, "${case.id}/$name validation")
            }
            assertEquals(dynamic, DocumentDifferential.kernel(model, case.json, dynamic = false), case.id)
            val actual = DocumentDifferential.interpreter(model, case.json)
            if (dynamic != actual) mismatches += "${case.id}: expected=$dynamic actual=$actual"
        }
        assertEquals(emptyList<String>(), mismatches)
    }
}
