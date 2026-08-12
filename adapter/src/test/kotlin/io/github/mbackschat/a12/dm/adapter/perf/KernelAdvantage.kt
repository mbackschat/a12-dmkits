package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.MIN_KERNEL_ADVANTAGE
import io.github.mbackschat.a12.dm.adapter.perf.PerfBounds.NEAR_LINEAR_SLACK
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The shared assertion for a perf guard that times **one interpreter entry point against its kernel counterpart
 * directly**, rather than through the [PerfInventory] scenario sweep: the interpreter must not fall below the
 * [PerfBounds.MIN_KERNEL_ADVANTAGE] floor at any measured scale, and its growth between the two scales must stay
 * near-linear. The floor is conservative and one-sided — clearing it is the minimum, never the goal, and the
 * guarded entries are expected to stay far above it (see that constant's contract).
 *
 * Direct rather than inventory-derived because these guards time an entry point [PerfOp] does not model, and
 * the inventory's rows carry reviewed content fingerprints that a new operation axis would invalidate. The
 * scenarios themselves are still the reviewed ones ([PerfCatalog]), so the model/document under test is shared
 * with the sweep and cannot drift from it.
 *
 * Reported against the KERNEL, never against a previous interpreter number: a newly measured entry point has no
 * prior of its own, and the project's target is to beat the kernel rather than to avoid regressing ourselves.
 */
internal object KernelAdvantage {

    private const val WARMUP = 3
    private const val REPEATS = 5

    /** One scale's paired reading — the same logical document through both engines. */
    class Reading(val n: Int, val interpreterMs: Double, val kernelMs: Double) {
        /** Kernel time over interpreter time: higher is better, and [MIN_KERNEL_ADVANTAGE] is the floor. */
        val advantage: Double get() = kernelMs / interpreterMs
    }

    /** Mean ms per call after [WARMUP] priming calls, so codegen and JIT are warm outside the timed region. */
    fun timeMs(warmup: Int = WARMUP, repeats: Int = REPEATS, body: () -> Unit): Double {
        repeat(warmup) { body() }
        val start = System.nanoTime()
        repeat(repeats) { body() }
        return (System.nanoTime() - start) / 1e6 / repeats
    }

    fun read(n: Int, interpreter: () -> Unit, kernel: () -> Unit): Reading =
        Reading(n, timeMs(body = interpreter), timeMs(body = kernel))

    /**
     * Print both readings, then assert the two bars. [label] names the timed entry point; the scale factor is
     * derived from the readings' own [Reading.n], so a caller cannot state a factor its samples do not have.
     */
    fun assertBeatsKernelAndScales(label: String, small: Reading, large: Reading) {
        require(large.n > small.n) { "$label: the large reading must be at a bigger scale than the small one" }
        val factor = large.n.toDouble() / small.n
        val growth = large.interpreterMs / small.interpreterMs.coerceAtLeast(0.001)

        for (r in listOf(small, large)) println(
            "[perf] %s@%d  interp=%.1fms kernel=%.1fms advantage=%.2f×"
                .format(Locale.ROOT, label, r.n, r.interpreterMs, r.kernelMs, r.advantage))
        println("[perf] %s growth %.2f× for %.0f× rows (linear≈%.0f)".format(Locale.ROOT, label, growth, factor, factor))

        assertTrue(growth < factor * NEAR_LINEAR_SLACK) {
            "$label scales super-linearly: ${"%.2f".format(Locale.ROOT, growth)}× for ${"%.0f".format(Locale.ROOT, factor)}× rows " +
                "(linear≈${"%.0f".format(Locale.ROOT, factor)}). A per-call cost that grows with the document turns an " +
                "entry point quadratic exactly where the scenario sweep cannot see it."
        }
        for (r in listOf(small, large)) assertTrue(r.advantage >= MIN_KERNEL_ADVANTAGE) {
            "$label must beat the kernel by ≥${MIN_KERNEL_ADVANTAGE}× at ${r.n} rows, measured " +
                "${"%.2f".format(Locale.ROOT, r.advantage)}× (interp=${"%.1f".format(Locale.ROOT, r.interpreterMs)}ms " +
                "kernel=${"%.1f".format(Locale.ROOT, r.kernelMs)}ms)"
        }
    }
}
