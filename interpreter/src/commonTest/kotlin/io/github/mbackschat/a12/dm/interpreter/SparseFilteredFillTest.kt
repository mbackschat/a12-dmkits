package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** The five traversal/stop controls are independently predicted by SparseFilteredFillDiffTest. */
class SparseFilteredFillTest {
    @Test
    fun sevenPredicatesKeepTheirPassPoliciesAndDecidingPrefixes() {
        val root = "/Dispatch"
        val rows = "$root/Runs/Parcels"
        val operators = listOf("AllFieldsFilled", "NoFieldFilled", "AtLeastOneFieldFilled", "MoreThanOneFieldFilled",
            "NotAllFieldsFilled", "NotExactlyOneFieldFilled", "FieldsNotCollectivelyFilled")
        val selected = "Runs*/Parcels*/Charge Having (FieldFilled(Runs/Parcels/Permit) Or ([Runs/Parcels/RecordedAt] == \"29.02.1500T10:00:00\"))"
        val model = ModelDef(
            fields = (listOf("$root/Anchor", "$rows/Permit", "$rows/Charge") + operators.map { "$root/$it" })
                .map { FieldDef(it, Kind.NUMBER, scale = 0) } + FieldDef("$rows/RecordedAt", Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
            rules = operators.map { RuleDef("$root/${it}Rule", root, "FieldFilled(Anchor) And $it($selected)", it, "../Anchor") },
            computations = operators.map { ComputationDef("$root/$it", root, listOf(ComputationAlternative(null, "1")),
                commonPrecondition = "$it($selected)") },
            groupPaths = setOf(root, "$root/Runs", rows), declaredReps = mapOf("$root/Runs" to 2, rows to 51),
            timeZone = ModelTimeZone.EUROPE_BERLIN,
        )
        val signatures = listOf("PFTFPFP", "FPPPTPP", "FPTPTPT", "PFTTPTP", "FPPPTPP")
        for ((case, signature) in signatures.withIndex()) {
            val width = when (case) { 0 -> 1; 1, 3 -> 50; else -> 51 }
            val first = List(width) { "{\"Permit\":1${if (case == 3) ",\"Charge\":5" else ""}}" }.joinToString(",")
            val seeds = operators.joinToString(",") { "\"$it\":99" }
            val doc = model.readDocumentJson("""{"Dispatch":{"Anchor":1,$seeds,"Runs":[{"Parcels":[$first]},
                {"Parcels":[{"Permit":1,"Charge":5}${if (case == 4) ",{}" else ""}]}]}}""")
            val engine = DmInterpreter(model)
            val messages = engine.validateFull(doc)
            val results = engine.compute(doc).associateBy { it.path.substringAfterLast('/') }
            for ((index, operator) in operators.withIndex()) {
                val expected = signature[index]
                assertEquals(when (expected) { 'T' -> listOf(MsgType.OMISSION); 'P' -> listOf(MsgType.VALUE); else -> emptyList() },
                    messages.filter { it.code == operator }.map { it.type }, "$case/$operator validation")
                val result = results.getValue(operator)
                assertEquals(if (expected == 'T') ComputeOutcome.VALUE else ComputeOutcome.CLEARED, result.outcome, "$case/$operator computation")
                if (expected == 'T') assertEquals("1", result.value.toString())
            }
        }
    }
}
