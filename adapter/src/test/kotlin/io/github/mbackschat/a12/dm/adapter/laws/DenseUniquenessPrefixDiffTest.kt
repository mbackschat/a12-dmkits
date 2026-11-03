package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC02: dense selection and one kept successor precede the duplicate decision in both phases. */
class DenseUniquenessPrefixDiffTest {
    private enum class Pass { Conditional, Early, Late }
    private val targets = listOf("Charge", "ShippedOn").flatMap { field -> Pass.entries.map { field + it.name } }
    private val model = underUtc {
        val draft = DocumentModelDraft.named("dispatch-duplicates").locale("en_US")
            .rootGroup("Dispatch") { root ->
                for (name in listOf("Anchor") + targets.flatMap { listOf(it, "After$it") })
                    root.numberField(name) { it.fractionDigits(0) }
                root.group("Runs") { runs -> runs.repeatable(2).group("Parcels") { rows ->
                    rows.repeatable(51).numberField("Permit") { it.fractionDigits(0) }
                        .numberField("Charge") { it.fractionDigits(0) }
                        .dateField("ShippedOn") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) }
                        .dateTimeField("RecordedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                } }
            }.build().toJson()
        val edit = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val root = edit.group("/Dispatch")
        val rows = edit.group("/Dispatch/Runs/Parcels")
        val safe = rows.num("Permit").filled()
        val poison = rows.dateTime("RecordedAt").eq(Dsl.dateTimeValue("29.02.1500T10:00:00"))
        var json = edit.toJson()
        for (field in listOf("Charge", "ShippedOn")) {
            val values = root.flatten("Runs*/Parcels*/$field")
            for (pass in Pass.entries) {
                val target = root.num(field + pass.name)
                val condition = when (pass) {
                    Pass.Conditional -> Dsl.fieldValuesNotUnique(values.having(safe.or(poison)))
                    Pass.Early -> Dsl.fieldValuesNotUnique(values.having(safe), values.having(poison))
                    Pass.Late -> Dsl.fieldValuesNotUnique(values.having(poison), values.having(safe))
                }
                val name = field + pass.name
                json = TypedRules.inject(json, root.rule { it.name(name + "Rule").code(name).errorField(root.num("Anchor"))
                    .`when`(root.num("Anchor").filled().and(condition)).message(Dsl.en("Check dispatch duplicates.")).build() })
                json = TypedRules.inject(json, root.computation(target) { it.name(name + "Comp")
                    .commonPrecondition(condition).calc(NumberLiteral.of("1")).message(Dsl.en("Check dispatch duplicates.")).build() })
                json = TypedRules.inject(json, root.computation(root.num("After$name")) { it.name("After${name}Comp")
                    .commonPrecondition(target.filled().or(target.notFilled())).calc(NumberLiteral.of("1"))
                    .message(Dsl.en("Check dispatch result.")).build() })
            }
        }
        json
    }

    private fun document(case: Int): String {
        val width = when (case) { 0 -> 1; 1 -> 50; else -> 51 }
        fun row(payload: Boolean) = """{"Permit":1${if (payload) ",\"Charge\":7,\"ShippedOn\":\"2024-06-00\"" else ""}}"""
        val first = List(width) { index ->
            if ((case == 4 && index == 2) || (case == 5 && index == 3)) "{}"
            else row(if (case >= 3) index < 2 else index == width - 1)
        }.joinToString(",")
        val seeds = targets.flatMap { listOf(it, "After$it") }.joinToString(",") { "\"$it\":99" }
        return """{"Dispatch":{"Anchor":1,$seeds,"Runs":[{"Parcels":[$first]},{"Parcels":[${row(true)}]}]}}"""
    }

    @Test
    fun denseDuplicateSearchStopsAfterOneKeptSuccessor() = underUtc {
        val differences = mutableListOf<String>()
        for (case in 0..5) {
            val json = document(case)
            val kernel = DocumentDifferential.kernel(model, json, dynamic = true)
            for (field in listOf("Charge", "ShippedOn")) for (pass in Pass.entries) {
                val name = field + pass.name
                val fires = when (pass) { Pass.Conditional -> case in listOf(0, 3, 5); Pass.Early -> true; Pass.Late -> false }
                assertEquals(listOf(if (fires) "OMISSION_ERROR" else "VALUE_ERROR"),
                    kernel.messages.filter { it.code == name }.map { it.type }, "$case/$name validation")
                for (target in listOf(name, "After$name")) assertEquals(if (fires) "VALUE|1" else "CLEARED",
                    kernel.outcomes[target], "$case/$target computation")
                assertEquals(!fires, kernel.operandErrors.any { it.code == "berechnungsWertFehler" && it.pointer.endsWith("/$name") }, "$case/$name poison")
            }
            assertEquals(kernel, DocumentDifferential.kernel(model, json, dynamic = false), "case=$case strategies")
            val actual = DocumentDifferential.interpreter(model, json)
            if (actual != kernel) differences += "case=$case: expected=$kernel actual=$actual"
        }
        assertEquals(emptyList<String>(), differences)
    }
}
