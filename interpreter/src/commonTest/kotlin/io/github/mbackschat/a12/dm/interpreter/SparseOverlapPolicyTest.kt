package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.keptRowsWithSuccessorLookahead
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** The exact/yearless and dense/sparse controls are kernel-owned by SparseOverlapPolicyDiffTest. */
class SparseOverlapPolicyTest {
    @Test
    fun callerFilterCallbackOwnsTheKeepDecision() {
        val model = ModelDef(fields = listOf(FieldDef("/Bookings/Permit", Kind.NUMBER)), rules = emptyList(),
            groupPaths = setOf("/Bookings"))
        val row = model.readDocumentJson("""{"Bookings":{}}""").contextsFor("/Bookings").single()
        val filter = Cond.Pred("FieldFilled", listOf(Expr.Field("/Bookings/Permit")))
        val interpreter = Interpreter(row)
        assertEquals(emptyList(), interpreter.keptRowsWithSuccessorLookahead(listOf(row), filter).toList())
        assertEquals(listOf(row), interpreter.keptRowsWithSuccessorLookahead(listOf(row), filter, keeps = { true }).toList())
    }

    @Test
    fun scalarAndPairOverlapKeepDifferentTraversalPolicies() {
        val root = "/Bookings"
        val parent = "$root/Locations"
        val rows = "$parent/Periods"
        val having = "Having (FieldFilled(Locations/Periods/Permit) Or ([Locations/Periods/Stamp] == \"31.03.2024T02:30:00\"))"
        for ((format, window) in listOf("yyyy-MM-dd" to "2025-05-01/2025-05-12", "MM-dd" to "05-01/05-12")) {
            val model = ModelDef(
                fields = listOf("$root/Reservation", "$rows/Pair", "$rows/Selected").map {
                    FieldDef(it, Kind.DATE_RANGE, format = format, rangeSeparator = "/")
                } + listOf("$root/Any", "$root/One", "$rows/Permit").map { FieldDef(it, Kind.NUMBER, scale = 0) } +
                    FieldDef("$rows/Stamp", Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
                rules = emptyList(),
                computations = listOf(
                    "Any" to "DateRangesOverlap(Locations*/Periods*/Pair $having)",
                    "One" to "AtLeastOneDateRangeOverlaps(Reservation In Locations*/Periods*/Selected $having)",
                ).map { (target, guard) -> ComputationDef("$root/$target", root,
                    listOf(ComputationAlternative(null, "1")), commonPrecondition = guard) },
                declaredReps = mapOf(parent to 2, rows to 51),
                groupPaths = setOf(root, parent, rows),
                timeZone = ModelTimeZone.EUROPE_BERLIN,
            )
            for ((width, blank) in listOf(1 to false, 50 to false, 51 to false, 51 to true)) {
                val first = List(width) { n -> """{"Permit":1${if (n == 0) ",\"Pair\":\"$window\"" else ""}}""" }.joinToString(",")
                val doc = model.readDocumentJson("""{"Bookings":{"Reservation":"$window","Any":99,"One":99,
                    "Locations":[{"Periods":[$first]},{"Periods":[{"Permit":1,"Pair":"$window","Selected":"$window"}${if (blank) ",{}" else ""}]}]}}""")
                val expected = mapOf("Any" to if (width == 1) "VALUE|1" else "CLEARED",
                    "One" to if (width == 1 || width == 51 && !blank) "VALUE|1" else "CLEARED")
                val actual = DmInterpreter(model).compute(doc).associate { it.path.substringAfterLast('/') to when (it.outcome) {
                    ComputeOutcome.VALUE -> "VALUE|${it.value}"
                    ComputeOutcome.CLEARED -> "CLEARED"
                    ComputeOutcome.ERRORED -> "ERRORED"
                } }
                assertEquals(expected, actual, "$format/$width/$blank")
            }
        }
    }
}
