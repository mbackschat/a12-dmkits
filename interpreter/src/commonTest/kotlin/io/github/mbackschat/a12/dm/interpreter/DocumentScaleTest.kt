package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Scaling benchmark over the REAL general [Document] (the production path), NOT the spike's map-backed
 * `RepDoc` — the honest counterpart of [InterpreterMultiplatformTest.scalesToManyRepetitionRows]
 * (INTERPRETER-PERFORMANCE.md). It is the regression guard for the #28 indexing: a quadratic `Document`
 * (O(P) per field lookup, O(P²) build) cannot evaluate this under the bound — it would take many seconds /
 * blow up — so this test trips on a reintroduced O(N²). Pure engine, kernel-free, runs on JVM **and** JS
 * (Node). **10k real-Document rows is the JS-scaling guard**: it was *impossible* on JS until the index
 * build's O(N²) `!in` dedup was removed (a single cold JS pass at this N took >2 s — the Mocha framework cap —
 * and ~14 s at 20k), and is now ~100 ms on JS. (We hold at 10k, not higher, only for CI headroom under that
 * hard 2000 ms per-test cap.) Correctness here = the row count we computed; kernel parity is the `:adapter` job.
 *
 * NOT the against-kernel perf differential (that lives in `:adapter`, needs the kernel + a build-once
 * pipeline — the next #29 increment). This slice establishes the harness + proves the real `Document` scales.
 */
class DocumentScaleTest {

    private val qty = "/Perf/Lines/Qty"
    private val amount = "/Perf/Lines/Amount"
    private val kinds = mapOf(qty to Kind.NUMBER, amount to Kind.NUMBER)
    private val fields = listOf(FieldDef(qty, Kind.NUMBER), FieldDef(amount, Kind.NUMBER))

    /** A Document of [n] `/Perf/Lines` rows: row i gets `Qty = i % 100` and `Amount = 1`. */
    private fun doc(n: Int): Document {
        val d = Document(kinds, declaredReps = mapOf("/Perf/Lines" to n))
        for (i in 1..n) {
            d.field(qty, (i % 100).toString(), 1, i, 1)
            d.field(amount, "1", 1, i, 1)
        }
        return d
    }

    @Test
    fun perRowRuleScalesOverManyRows() {
        val n = 10_000
        // A per-row rule iterating /Perf/Lines; fires on the rows with Qty in 51..99 (49 per 100).
        val model = ModelDef(
            fields,
            listOf(RuleDef("/Perf/Lines/HighQty", "/Perf/Lines", "FieldFilled(Qty) And [Qty] > 50", "HQ", "../Qty")),
            repeatableGroups = setOf("/Perf/Lines"),
        )
        val interp = DmInterpreter(model)
        val d = doc(n)
        var fired = 0
        val elapsed = measureTime { fired = interp.validateFull(d).count { it.code == "HQ" } }
        assertEquals(n / 100 * 49, fired, "rows with Qty in 51..99 fire (49 per 100)")
        println("[interpreter] per-row rule over $n real-Document rows in $elapsed")
        // O(N) indexed Document is milliseconds; a reintroduced O(N²) would blow far past this (generous
        // headroom for JS/Node + CI variance).
        assertTrue(elapsed < 20.seconds, "scaling regression: $n rows took $elapsed")
    }

    @Test
    fun starAggregateScalesOverManyRows() {
        val n = 10_000
        // An aggregate rule at /Perf summing Amount across every Lines row — exercises star collection at scale.
        val model = ModelDef(
            fields,
            listOf(RuleDef("/Perf/TotalPositive", "/Perf", "Sum(Lines*/Amount) > 0", "TP", "../Lines")),
            repeatableGroups = setOf("/Perf/Lines"),
        )
        val interp = DmInterpreter(model)
        val d = doc(n)
        var fired = 0
        val elapsed = measureTime { fired = interp.validateFull(d).count { it.code == "TP" } }
        assertEquals(1, fired, "Sum(1 × $n) > 0 fires once at /Perf")
        println("[interpreter] Sum aggregate over $n real-Document rows in $elapsed")
        assertTrue(elapsed < 20.seconds, "aggregate scaling regression: $n rows took $elapsed")
    }
}
