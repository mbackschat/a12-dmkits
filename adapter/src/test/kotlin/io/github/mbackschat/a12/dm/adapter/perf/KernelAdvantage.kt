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

    /** Independent re-measurements a breaching growth estimate must survive. Odd, so the median is one of them. */
    private const val GROWTH_ATTEMPTS = 3

    /** One scale's paired reading — the same logical document through both engines. */
    class Reading(val n: Int, val interpreterMs: Double, val kernelMs: Double) {
        /** Kernel time over interpreter time: higher is better, and [MIN_KERNEL_ADVANTAGE] is the floor. */
        val advantage: Double get() = kernelMs / interpreterMs
    }

    /**
     * MEDIAN ms per call after [WARMUP] priming calls, so codegen and JIT are warm outside the timed region.
     *
     * **Median, not mean** (2026-08-13). This timed `repeats` calls in one region and divided, which is a mean, and
     * a mean is dragged by a single contended slice — the exact reading the rest of this estate switched away from,
     * and its own `pairedGrowth` says so. Measured over three sequential isolated gate runs, the two lanes fed by
     * this helper had the widest run-to-run spread of all 64 anchored keys (`computeField` 1.89x on
     * `[3.13, 5.84, 3.09]`, `applyComputations` 1.82x) — a spread wider than the 1.65x anchor tolerance itself,
     * so an anchored lane was one unlucky run from a false red. One outlier of five now moves the median by
     * nothing instead of by a fifth of the total.
     */
    fun timeMs(warmup: Int = WARMUP, repeats: Int = REPEATS, body: () -> Unit): Double {
        repeat(warmup) { body() }
        val samples = DoubleArray(repeats) {
            val start = System.nanoTime()
            body()
            (System.nanoTime() - start) / 1e6
        }
        samples.sort()
        return if (repeats % 2 == 1) samples[repeats / 2] else (samples[repeats / 2 - 1] + samples[repeats / 2]) / 2
    }

    fun read(n: Int, interpreter: () -> Unit, kernel: () -> Unit): Reading =
        Reading(n, timeMs(body = interpreter), timeMs(body = kernel))

    /**
     * Print both readings, then assert the two bars. [label] names the timed entry point; the scale factor is
     * derived from the readings' own [Reading.n], so a caller cannot state a factor its samples do not have.
     *
     * [identityAt] is required rather than optional because it is what puts these readings into the committed
     * record. Both bars below are **relative** — one compares the interpreter to itself at two scales, the other
     * to the kernel in the same run — and a relative bar is by construction blind to a uniform slowdown that moves
     * both arms together. Anchoring the interpreter's absolute medians is what closes that, and it did not happen
     * here until 2026-08-13 because the record-and-arm step existed only inside one other class (IF296).
     */
    fun assertBeatsKernelAndScales(
        label: String,
        smallN: Int,
        largeN: Int,
        identityAt: (Int) -> String,
        readAt: (Int) -> Reading,
    ) {
        var small = readAt(smallN)
        var large = readAt(largeN)
        require(large.n > small.n) { "$label: the large reading must be at a bigger scale than the small one" }
        val factor = large.n.toDouble() / small.n
        val bar = factor * NEAR_LINEAR_SLACK

        // RE-MEASURED BEFORE FAILING, like every other growth floor in this estate. This one was the exception,
        // and it cost a false red the first time the tier ran under real load: `computeField` reported 6.74x
        // against a 6.0x bar inside a full gate at load ~9, then passed at 4.4x in isolation on the same code
        // (2026-08-13). A contended slice inflates one wall-clock reading and does not reproduce; a per-call cost
        // that grows with the document does. The acceptance rule is the MEDIAN of the attempts, never
        // "any attempt below the bar" — retry-until-pass would hide a real breach the more it was retried.
        var growth = large.interpreterMs / small.interpreterMs.coerceAtLeast(0.001)
        if (growth >= bar) {
            val attempts = mutableListOf(Triple(growth, small, large))
            while (attempts.size < GROWTH_ATTEMPTS) {
                val retrySmall = readAt(smallN)
                val retryLarge = readAt(largeN)
                attempts += Triple(
                    retryLarge.interpreterMs / retrySmall.interpreterMs.coerceAtLeast(0.001),
                    retrySmall, retryLarge,
                )
            }
            val accepted = attempts.sortedBy { it.first }[attempts.size / 2]
            growth = accepted.first
            small = accepted.second
            large = accepted.third
            println(
                "[perf] %s growth re-measured %d× after a first estimate of %.2f×; accepted the median %.2f×"
                    .format(Locale.ROOT, label, GROWTH_ATTEMPTS, attempts.first().first, growth))
        }

        for (r in listOf(small, large)) println(
            "[perf] %s@%d  interp=%.1fms kernel=%.1fms advantage=%.2f×"
                .format(Locale.ROOT, label, r.n, r.interpreterMs, r.kernelMs, r.advantage))
        println("[perf] %s growth %.2f× for %.0f× rows (linear≈%.0f)".format(Locale.ROOT, label, growth, factor, factor))

        assertTrue(growth < bar) {
            "$label scales super-linearly: ${"%.2f".format(Locale.ROOT, growth)}× for ${"%.0f".format(Locale.ROOT, factor)}× rows " +
                "(linear≈${"%.0f".format(Locale.ROOT, factor)}), the median of $GROWTH_ATTEMPTS independent " +
                "re-measurements. A per-call cost that grows with the document turns an " +
                "entry point quadratic exactly where the scenario sweep cannot see it."
        }
        for (r in listOf(small, large)) assertTrue(r.advantage >= MIN_KERNEL_ADVANTAGE) {
            "$label must beat the kernel by ≥${MIN_KERNEL_ADVANTAGE}× at ${r.n} rows, measured " +
                "${"%.2f".format(Locale.ROOT, r.advantage)}× (interp=${"%.1f".format(Locale.ROOT, r.interpreterMs)}ms " +
                "kernel=${"%.1f".format(Locale.ROOT, r.kernelMs)}ms)"
        }
        // Recorded LAST, so a run that breached a bar above still fails on the bar rather than on a missing anchor.
        // Wall clock, because that is what `timeMs` reads. The re-measurement repeats the caller's own read at the
        // same scale — [readAt] is taken as a factory rather than two finished readings precisely so this exists;
        // an anchor that "re-measures" by handing back the value it already had would turn the retry into a no-op.
        //
        // BOTH endpoints record before either is allowed to fail: `assertWithinBudget` rethrows on an unanchored
        // key, so failing at the first one would leave the second unrecorded and unauthorable, making a new lane
        // cost two full gate runs to arm instead of one.
        var failure: Throwable? = null
        for (r in listOf(small, large)) {
            try {
                PerfAnchors.assertWithinBudget(
                    ObservationKey(
                        lane = label,
                        identityLabel = identityAt(r.n),
                        n = r.n,
                        engine = "interpreter",
                        clock = "wall",
                    ),
                    r.interpreterMs,
                ) { readAt(r.n).interpreterMs }
            } catch (unanchored: UnanchoredObservationException) {
                if (failure == null) failure = unanchored else failure.addSuppressed(unanchored)
            }
        }
        failure?.let { throw it }
    }
}
