package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The **against-kernel** performance differential (INTERPRETER-PERFORMANCE.md): the SAME real instance is
 * evaluated by both the pure interpreter (`DmInterpreter` + the general `Document`) and the real kernel
 * (`RuntimeLaws.KernelEngine`, built once), over a high-repeatability **nested** group model (`perf.dm.json`).
 * Each scenario asserts **correctness first** (interp fired-codes == kernel) then times only `validateFull`
 * (generous warmup, since the kernel's per-call time JIT-warms downward over ~a dozen calls) and prints the
 * interp/kernel ratio. Group-heavy by design: a per-row rule over N Lines (B1) and a per-sub rule over N·M
 * nested Sub rows (B3).
 *
 * **Fairness caveat — read the ratio with care.** This is NOT apples-to-apples: the kernel's `validateFull`
 * does strictly MORE per call than the interpreter currently does — requiredness (`mandatoryField`),
 * formal-checking *every* field, building richer `IMessage` pointers — and a 1-rule model overweights that
 * fixed pipeline overhead. So "interp faster" partly means "interp does less (for now)". The honest read:
 * the interpreter is **competitive-to-faster on these scenarios**, not "Nx faster" as a speed verdict; a true
 * comparison needs feature parity (requiredness etc.). The bounds are **generous gates** (catch an O(N²)
 * regression, tolerate CI variance), not SLAs. Pure engine, no CLI.
 */
class AgainstKernelPerfTest {

    private val perf = RuntimeLaws.model("models/perf.dm.json")

    private val lineKey = "/Perf/Lines/LineKey"
    private val qty = "/Perf/Lines/Qty"
    private val amount = "/Perf/Lines/Amount"
    private val subKey = "/Perf/Lines/Subs/SubKey"
    private val cost = "/Perf/Lines/Subs/Cost"

    private val kinds = mapOf(
        lineKey to Kind.STRING, qty to Kind.NUMBER, amount to Kind.NUMBER,
        subKey to Kind.STRING, cost to Kind.NUMBER,
    )
    private val fields = kinds.map { (p, k) -> FieldDef(p, k) }
    private val repeatable = setOf("/Perf/Lines", "/Perf/Lines/Subs")

    private fun interp(cond: String, group: String, code: String, errorRel: String) =
        DmInterpreter(ModelDef(fields, listOf(RuleDef("$group/R", group, cond, code, errorRel)), repeatableGroups = repeatable))

    /** Median wall-clock (ms) of [block] over [iters] timed runs after [warmup] discarded runs. The warmup
     *  must be generous: the kernel's per-call time DECREASES monotonically over the first ~dozen calls (JVM
     *  JIT warming the Groovy-compiled rules — NOT per-call codegen, which is cached), so too few warmups
     *  *overstates* the kernel's cost. */
    private fun medianMs(warmup: Int, iters: Int, block: () -> Unit): Double {
        repeat(warmup) { block() }
        val times = DoubleArray(iters) { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }
        return times.sorted()[iters / 2]
    }

    private companion object {
        const val WARMUP = 15
        const val ITERS = 10
        // The ratio is NOT a fair apples-to-apples speed comparison: the kernel's validateFull does strictly
        // MORE per call (requiredness/`mandatoryField`, formal-checking every field, richer IMessage pointers)
        // that the interpreter does not yet do, and a 1-rule model overweights that fixed pipeline overhead.
        const val CAVEAT = "(NOT apples-to-apples: kernel does more — requiredness/full-formal/richer messages)"
    }

    @Test
    fun perRowRule_B1() {
        val n = 3_000
        val cond = "FieldFilled(Qty) And [Qty] > 50"
        val model = RuntimeLaws.withRule(perf, "/Perf/Lines", "HighQty", "../Qty", "HQ", cond)

        val kEngine = RuntimeLaws.kernelEngine(model)
        val kDoc = RuntimeLaws.doc().group("/Perf", 1)
        val iDoc = Document(kinds, mapOf("/Perf/Lines" to n))
        for (i in 1..n) {
            kDoc.group("/Perf/Lines", 1, i).field(lineKey, "L$i", 1, i, 1)
                .field(qty, (i % 100).toString(), 1, i, 1).field(amount, "1", 1, i, 1)
            iDoc.field(lineKey, "L$i", 1, i, 1).field(qty, (i % 100).toString(), 1, i, 1).field(amount, "1", 1, i, 1)
        }
        val kInstance = kEngine.instance(kDoc)
        val interp = interp(cond, "/Perf/Lines", "HQ", "../Qty")

        assertEquals(kEngine.firedCodes(kInstance), interp.validate(iDoc).messages.map { it.code }.toSet(),
            "interpreter and kernel must agree on which rules fire")

        val kMs = medianMs(WARMUP, ITERS) { kEngine.firedCodes(kInstance) }
        val iMs = medianMs(WARMUP, ITERS) { interp.validateFull(iDoc) }
        println("[perf] B1 per-row N=$n  interp=${"%.1f".format(iMs)}ms kernel=${"%.1f".format(kMs)}ms ratio=${"%.2f".format(iMs / kMs)} $CAVEAT")
        assertTrue(iMs < 30_000, "B1 interpreter scaling regression: ${iMs}ms for $n rows")
    }

    @Test
    fun nestedPerSubRule_B3() {
        val lines = 400
        val subsPer = 5   // 2000 nested Sub rows
        val cond = "FieldFilled(Cost) And [Cost] > 50"
        val model = RuntimeLaws.withRule(perf, "/Perf/Lines/Subs", "HighCost", "../Cost", "HC", cond)

        val kEngine = RuntimeLaws.kernelEngine(model)
        val kDoc = RuntimeLaws.doc().group("/Perf", 1)
        val iDoc = Document(kinds, mapOf("/Perf/Lines" to lines, "/Perf/Lines/Subs" to subsPer))
        // Fill every field (incl. the index fields LineKey/SubKey, and Qty/Amount) so only the injected rule
        // can fire — an unfilled mandatory/index field would make the kernel emit `mandatoryField`, a
        // requiredness-validation dimension the interpreter does not yet model (recorded as a known gap).
        for (i in 1..lines) {
            kDoc.group("/Perf/Lines", 1, i).field(lineKey, "L$i", 1, i, 1)
                .field(qty, "1", 1, i, 1).field(amount, "1", 1, i, 1)
            iDoc.field(lineKey, "L$i", 1, i, 1).field(qty, "1", 1, i, 1).field(amount, "1", 1, i, 1)
            for (j in 1..subsPer) {
                kDoc.group("/Perf/Lines/Subs", 1, i, j).field(subKey, "S$i-$j", 1, i, j, 1)
                    .field(cost, ((i * j) % 100).toString(), 1, i, j, 1)
                iDoc.field(subKey, "S$i-$j", 1, i, j, 1).field(cost, ((i * j) % 100).toString(), 1, i, j, 1)
            }
        }
        val kInstance = kEngine.instance(kDoc)
        val interp = interp(cond, "/Perf/Lines/Subs", "HC", "../Cost")

        assertEquals(kEngine.firedCodes(kInstance), interp.validate(iDoc).messages.map { it.code }.toSet(),
            "interpreter and kernel must agree on the nested per-sub rule")

        val kMs = medianMs(WARMUP, ITERS) { kEngine.firedCodes(kInstance) }
        val iMs = medianMs(WARMUP, ITERS) { interp.validateFull(iDoc) }
        println("[perf] B3 nested per-sub lines=$lines×subs=$subsPer  interp=${"%.1f".format(iMs)}ms kernel=${"%.1f".format(kMs)}ms ratio=${"%.2f".format(iMs / kMs)} $CAVEAT")
        assertTrue(iMs < 30_000, "B3 interpreter scaling regression: ${iMs}ms")
    }
}
