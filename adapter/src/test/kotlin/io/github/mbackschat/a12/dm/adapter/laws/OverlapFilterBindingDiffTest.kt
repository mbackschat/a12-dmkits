package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Disjoint ranges and unique per-row values expose a falsely unconditional `$` filter. */
class OverlapFilterBindingDiffTest {
    @Test
    fun overlapAndUniquenessCaptureTheOuterRowAcrossBothPhases() {
        val targets = listOf("Plain", "Excluded", "Included", "Pair", "UniqueExcluded", "UniqueIncluded")
        val mismatches = mutableListOf<String>()
        for (indexed in listOf(false, true)) {
            val draft = DocumentModelDraft.named("rental-filter-binding").locale("en_US")
                .rootGroup("Lease") { root -> root.group("Periods") { rows ->
                    rows.stringField("Key") { }.numberField("Charge") { it.fractionDigits(0) }
                    if (indexed) rows.indexField("Key")
                    rows.repeatable(3).dateRangeField("Window") { it.format("yyyy-MM-dd").separator("/") }
                    for (target in targets) rows.numberField(target) { it.fractionDigits(0) }
                } }.build().toJson()
            val edit = EditSession.read(draft)
            val periods = edit.group("/Lease/Periods")
            val windows = edit.group("/Lease").flatten("Periods*/Window")
            val current = Dsl.currentRepetition(periods.ref())
            val outer = Dsl.currentRepetition(periods.ref(), true)
            val excluded = windows.having(current.ne(outer))
            val included = windows.having(current.eq(outer))
            val window = periods.dateRange("Window")
            val charges = edit.group("/Lease").flatten("Periods*/Charge")
            val conditions = listOf(Dsl.atLeastOneDateRangeOverlaps(window.field(), windows),
                Dsl.atLeastOneDateRangeOverlaps(window.field(), excluded),
                Dsl.atLeastOneDateRangeOverlaps(window.field(), included),
                Dsl.dateRangesOverlap(window.field(), excluded),
                Dsl.fieldValuesNotUnique(charges.having(current.ne(outer))),
                Dsl.fieldValuesNotUnique(charges.having(current.eq(outer))))
            var model = edit.toJson()
            for ((target, condition) in targets.zip(conditions)) {
                model = TypedRules.inject(model, periods.rule {
                    it.name(target + "Rule").code(target).errorField(window).`when`(window.filled().and(condition))
                        .message(Dsl.en("Check rental periods.")).build()
                })
                model = TypedRules.inject(model, periods.computation(periods.num(target)) {
                    it.name(target + "Comp").commonPrecondition(condition).calc(NumberLiteral.of("1"))
                        .message(Dsl.en("Check rental periods.")).build()
                })
            }
            val seeds = targets.joinToString(",") { "\"$it\":9" }
            for (mixed in listOf(true, false)) {
                val rows = listOf("2025-01-01/2025-01-10", if (mixed) "2025-01-05/2025-01-15" else "2025-02-01/2025-02-10",
                    "2025-03-01/2025-03-10").mapIndexed { index, value -> """{"Key":"K$index","Charge":${if (index == 2) 2 else 1},"Window":"$value",$seeds}""" }.joinToString(",")
                val document = """{"Lease":{"Periods":[$rows]}}"""
                val kernel = DocumentDifferential.kernel(model, document, dynamic = true)
                for (target in targets) {
                    val fired = when (target) {
                        "Excluded" -> if (mixed) listOf(1, 2) else emptyList()
                        "Pair" -> if (mixed) listOf(1, 2, 3) else emptyList()
                        "UniqueExcluded" -> listOf(3)
                        "UniqueIncluded" -> emptyList()
                        else -> listOf(1, 2, 3)
                    }
                    assertEquals(fired.map { "/Lease[1]/Periods[$it]/Window" },
                        kernel.messages.filter { it.code == target }.map { it.pointer }.sorted(), "$indexed/$mixed/$target validation rows")
                    for (row in 1..3) {
                        val path = "Periods${if (row == 1) "" else "[$row]"}/$target"
                        val computed = when (target) {
                            "Excluded" -> mixed && row != 3
                            "Pair" -> mixed
                            "UniqueExcluded" -> row == 3
                            "UniqueIncluded" -> false
                            else -> true
                        }
                        assertEquals(if (computed) "VALUE|1" else "CLEARED", kernel.outcomes[path], "$indexed/$mixed/$target/$row computation")
                    }
                }
                assertEquals(emptyList<MessageSignature>(), kernel.operandErrors)
                assertEquals(kernel, DocumentDifferential.kernel(model, document, dynamic = false), "indexed=$indexed mixed=$mixed")
                val actual = DocumentDifferential.interpreter(model, document)
                if (actual != kernel) mismatches += "indexed=$indexed mixed=$mixed: expected=$kernel actual=$actual"
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }
}
