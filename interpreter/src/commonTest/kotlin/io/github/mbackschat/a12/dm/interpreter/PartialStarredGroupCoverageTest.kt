package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** Portable lock for the kernel measurements in PartialStarredGroupCoverageDiffTest (SPEC20/21). */
class PartialStarredGroupCoverageTest {
    @Test
    fun missingPositionsNeedCoverageButAnIncludedRowDecidesPresence() {
        val root = "/Shipment"
        val batches = "$root/Batches"
        val parcels = "$batches/Parcels"
        val reference = "$root/Reference"
        val code = "$batches/Code"
        val model = ModelDef(
            fields = listOf(FieldDef(reference, Kind.STRING), FieldDef(code, Kind.STRING), FieldDef("$parcels/Quantity", Kind.NUMBER)),
            rules = listOf("ALL" to "Batches*/Parcels*", "SELECTED" to "[Batches/Parcels* For \"A\"]").flatMap { (name, operand) ->
                listOf("NONE" to "NoGroupFilled", "SOME" to "AtLeastOneGroupFilled").map { (suffix, operator) ->
                    RuleDef("$root/${name}_$suffix", root, "FieldFilled(Reference) And $operator($operand)", "${name}_$suffix", "../Reference")
                }
            },
            repeatableGroups = setOf(batches, parcels), declaredReps = mapOf(batches to 2, parcels to 2),
            indexFields = setOf(code), groupPaths = setOf(root, batches, parcels),
        )
        val interpreter = DmInterpreter(model)
        val prerequisites = setOf(RelevantEntity.anyRep(reference), RelevantEntity.anyRep(code))
        fun slot(parent: Int, row: Int) = RelevantEntity(parcels, listOf(0, parent, row))
        val selections = listOf(
            "uncovered" to emptySet(), "first" to setOf(slot(1, 1)),
            "concrete" to setOf(slot(1, 1), slot(1, 2)), "wildcard" to setOf(slot(0, 1), slot(0, 2)),
        )
        for (shape in listOf("absent", "empty", "row")) {
            val content = when (shape) {
                "absent" -> ""
                "empty" -> """, "Batches":[{"Code":"A"}]"""
                else -> """, "Batches":[{"Code":"A","Parcels":[{}]}]"""
            }
            val document = model.readDocumentJson("""{"Shipment":{"Reference":"ready"$content}}""")
            for ((selection, relevant) in selections) {
                val expected = when {
                    shape == "row" && selection != "uncovered" -> setOf("ALL_SOME|VALUE", "SELECTED_SOME|VALUE")
                    selection == "wildcard" -> setOf("ALL_NONE|OMISSION", "SELECTED_NONE|OMISSION")
                    shape == "empty" && selection == "concrete" -> setOf("SELECTED_NONE|OMISSION")
                    else -> emptySet()
                }
                val result = interpreter.validatePart(document, prerequisites + relevant)
                assertEquals(emptyList(), result.unsupported, "$shape/$selection")
                assertEquals(expected, result.messages.map { "${it.code}|${it.type}" }.toSet(), "$shape/$selection")
            }
        }
    }
}
