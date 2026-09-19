package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The lane that actually holds a measured run against [Nfr1StandingAnchor] — NFR #1's enforcement locus.
 *
 * **Why it is env-driven rather than always-on.** The quantities it judges do not exist until a lane produces
 * them: the footprint raws come from the opt-in `interpreterFootprint` forks and the delivered sizes from an
 * assembled release mirror. Running this on every `:adapter:test` would therefore mean either measuring nothing
 * (a bar that cannot fail) or forcing a multi-minute measurement into the inner loop. So the gate follows the
 * estate's existing shape for exactly this problem — `PerfAnchors.LANE_PROPERTY` — and announces the skip
 * instead of performing it silently.
 *
 * **What it refuses.** A named collector whose input directory is unset or absent fails rather than passing,
 * because NFR #1 states that missing or inconclusive evidence fails; so does a row the anchor holds but the run
 * did not produce, and a row the run produced that the anchor never saw. The one thing it does NOT do is judge
 * an improvement: the bar is one-sided by construction, and a shrinking artifact needs no permission.
 */
class Nfr1StandingAnchorGateTest {

    @Test
    fun everyCollectorTheLaneNamesStaysWithinItsStandingAnchor() {
        val requested = System.getenv(COLLECTORS_VARIABLE)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (requested.isNullOrEmpty()) {
            println("[nfr1] standing anchor not asserted (\$$COLLECTORS_VARIABLE unset): this is not the gate lane")
            return
        }

        val anchor = Nfr1StandingAnchor.load()
        val failures = mutableListOf<String>()
        requested.map(Nfr1Collector::of).forEach { collector ->
            val directory = System.getenv(collector.inputVariable)
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
            if (directory == null || !directory.isDirectory) {
                failures += "${collector.id}: \$${collector.inputVariable} names no readable directory " +
                    "(${directory?.path ?: "unset"}), so the run produced no evidence to judge"
                return@forEach
            }

            // Checked BEFORE any row is compared. A median taken at a different warmup, heap or runtime major is
            // a different quantity, so comparing it would still produce a percentage — and a percentage gets
            // believed. Better to refuse than to report a number nobody can act on.
            val expected = anchor.capture[collector.id]
            val actual = collector.identityOf(directory)
            if (expected != null && actual != null) {
                expected.diff(actual).forEach {
                    failures += "${collector.id}: this run was not measured under the baseline's conditions — $it"
                }
            }

            val measured = collector.read(directory, anchor, System.getenv(Nfr1Collector.VERSION_VARIABLE))
            anchor.missingRows(collector.id, measured).forEach {
                failures += "${collector.id}: the anchor holds $it but this run did not measure it"
            }
            anchor.unknownRows(measured.filterKeys { it.startsWith("${collector.id}/") }).forEach {
                failures += "${collector.id}: this run measured $it, which the anchor has no baseline for — " +
                    "capture it at the baseline commit rather than letting it pass unjudged"
            }
            anchor.breaches(measured).forEach { failures += "${collector.id}: $it" }
            // Announced on the asserting path too, not only on the skip. An absent skip notice is weak evidence
            // that the bar ran — it is equally consistent with the notice being swallowed — so the lane prints
            // what it judged and how many rows it judged it against.
            println("[nfr1] ${collector.id}: judged ${measured.size} rows against ${anchor.baselineCommit.take(8)}")
        }

        assertTrue(
            failures.isEmpty(),
            "NFR #1's standing anchor (baseline ${anchor.baselineCommit}, " +
                "+${anchor.maxRegressionPercent}% per row, no compensation between rows) is breached:\n" +
                failures.joinToString("\n") { "  - $it" },
        )
    }

    /**
     * The collector set is not silently narrowed.
     *
     * The anchor is a JSON file, so a row block can be dropped from it without any code changing and without any
     * other assertion noticing — the remaining rows would still pass. Tying the enum to the file in this
     * direction means a collector can only leave the bar by leaving the code too.
     */
    @Test
    fun everyKnownCollectorStillCarriesRowsInTheCommittedAnchor() {
        val anchor = Nfr1StandingAnchor.load()

        Nfr1Collector.entries.forEach { collector ->
            assertTrue(
                anchor.rowsFor(collector.id).isNotEmpty(),
                "${collector.id} is a standing-anchor collector but ${Nfr1StandingAnchor.RESOURCE} holds no row " +
                    "for it, so the gate would judge it green without measuring anything",
            )
        }
    }

    private companion object {
        const val COLLECTORS_VARIABLE = "NFR1_COLLECTORS"
    }
}
