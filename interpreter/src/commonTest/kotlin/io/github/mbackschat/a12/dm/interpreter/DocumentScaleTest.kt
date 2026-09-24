package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentConfig
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Correctness at scale over the REAL general [Document] (the production path), NOT the spike's map-backed
 * `RepDoc`. It exercises the #28 indexing at 10 000 rows on JVM **and** JS (Node): a quadratic `Document`
 * (O(P) per field lookup, O(P²) build) made this shape impossible on JS until the index build's O(N²) `!in`
 * dedup was removed — a single cold JS pass then took >2 s at this N and ~14 s at 20k, against ~100 ms now.
 * Correctness here = the row count we computed; kernel parity is the `:adapter` job.
 *
 * **It asserts no elapsed time** (2026-08-13, IF295). It used to carry `elapsed < 20.seconds` per case, and that
 * bound was neither a performance requirement nor a working liveness net: on JS the Mocha per-test cap then stood
 * an order of magnitude below it (2 s at the time), and on the JVM the same regression is measured
 * properly — as a paired N/4N growth ratio under isolation — by `:adapter`'s routine tier, which carries 19
 * sub-quadratic scenarios including the general per-row one. A wall-clock assertion here would have added a
 * clock-dependent verdict to a lane that forks tests in parallel, without covering anything those two do not.
 * No clock is read here at all. A census exemption is keyed by FILE, so an exempted file hides every clock use
 * in it — including a reintroduced bound, which is exactly what this one used to carry.
 */
class DocumentScaleTest {

    private val qty = "/Perf/Lines/Qty"
    private val amount = "/Perf/Lines/Amount"
    private val kinds = mapOf(qty to Kind.NUMBER, amount to Kind.NUMBER)
    private val fields = listOf(FieldDef(qty, Kind.NUMBER), FieldDef(amount, Kind.NUMBER))

    /** A Document of [n] `/Perf/Lines` rows: row i gets `Qty = i % 100` and `Amount = 1`. */
    private fun doc(n: Int): Document {
        val d = Document(DocumentConfig(kinds, declaredReps = mapOf("/Perf/Lines" to n)))
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
        val fired = interp.validateFull(d).count { it.code == "HQ" }
        assertEquals(n / 100 * 49, fired, "rows with Qty in 51..99 fire (49 per 100)")
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
        val fired = interp.validateFull(d).count { it.code == "TP" }
        assertEquals(1, fired, "Sum(1 × $n) > 0 fires once at /Perf")
    }
}
