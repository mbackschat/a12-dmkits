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

/** Portable controls for the independently predicted SparseMembershipTraversalDiffTest grid. */
class SparseMembershipTraversalTest {
    @Test
    fun membershipKeepsBothSidePoliciesAndPrefixStops() {
        val root = "/Dispatch"
        val rows = "$root/Runs/Parcels"
        val operators = listOf("AtLeastOneFieldValueIncludedInValueList", "NoFieldValueIncludedInValueList", "NotAllFieldValuesIncludedInValueList")
        val targets = (0..1).flatMap { kind -> (0..2).flatMap { side -> operators.indices.map { op -> "K${kind}S${side}P$op" } } }
        val having = "Having (FieldFilled(Runs/Parcels/Permit) Or ([Runs/Parcels/RecordedAt] == \"31.03.2024T02:30:00\"))"
        val conditions = (0..1).flatMap { kind ->
            val selected = "Runs*/Parcels*/${if (kind == 0) "Charge" else "Label"} $having"
            val approved = if (kind == 0) "Approved" else "Token"
            val empty = if (kind == 0) "EmptyNumber" else "EmptyString"
            (0..2).flatMap { side -> operators.mapIndexed { op, name ->
                val args = when (side) {
                    0 -> "$selected In $approved"
                    1 -> "$approved In $selected"
                    else -> if (op == 2) "$empty In $selected" else "$selected In $empty"
                }
                "$name($args)"
            } }
        }
        val model = ModelDef(
            fields = (listOf("$root/Anchor", "$root/Approved", "$root/EmptyNumber", "$rows/Permit", "$rows/Charge") + targets.map { "$root/$it" })
                .map { FieldDef(it, Kind.NUMBER, scale = 0) } + listOf("$root/Token", "$root/EmptyString", "$rows/Label").map { FieldDef(it, Kind.STRING) } +
                FieldDef("$rows/RecordedAt", Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
            rules = targets.zip(conditions).map { (target, condition) -> RuleDef("$root/${target}Rule", root,
                "FieldFilled(Anchor) And $condition", target, "../Anchor") },
            computations = targets.zip(conditions).map { (target, condition) -> ComputationDef("$root/$target", root,
                listOf(ComputationAlternative(null, "1")), commonPrecondition = condition) },
            groupPaths = setOf(root, "$root/Runs", rows), declaredReps = mapOf("$root/Runs" to 2, rows to 51),
            timeZone = ModelTimeZone.EUROPE_BERLIN,
        )
        val signatures = listOf("TFFTFFFTF", "PPPPPPFPF", "TPPTPPFPF", "TFPPPPFPF", "PPPPPPFPF", "PPTPPPFPF")
        for ((case, signature) in signatures.withIndex()) {
            val width = when (case) { 0 -> 1; 2, 4 -> 51; else -> 50 }
            val first = List(width) { "{\"Permit\":1${if (case == 3 || case == 5) ",\"Charge\":5,\"Label\":\"five\"" else ""}}" }.joinToString(",")
            val seeds = targets.joinToString(",") { "\"$it\":99" }
            val doc = model.readDocumentJson("""{"Dispatch":{"Anchor":1,"Approved":${if (case == 5) 9 else 5},"Token":"${if (case == 5) "nine" else "five"}",$seeds,
                "Runs":[{"Parcels":[$first]},{"Parcels":[{"Permit":1,"Charge":5,"Label":"five"}${if (case == 4) ",{}" else ""}]}]}}""")
            val engine = DmInterpreter(model)
            val messages = engine.validateFull(doc)
            val results = engine.compute(doc).associateBy { it.path.substringAfterLast('/') }
            for ((i, target) in targets.withIndex()) {
                val expected = signature[i % 9]
                assertEquals(when (expected) { 'T' -> listOf(MsgType.OMISSION); 'P' -> listOf(MsgType.VALUE); else -> emptyList() },
                    messages.filter { it.code == target }.map { it.type }, "$case/$target validation")
                val result = results.getValue(target)
                assertEquals(if (expected == 'T') ComputeOutcome.VALUE else ComputeOutcome.CLEARED, result.outcome, "$case/$target computation")
                if (expected == 'T') assertEquals("1", result.value.toString())
            }
        }
    }
}
