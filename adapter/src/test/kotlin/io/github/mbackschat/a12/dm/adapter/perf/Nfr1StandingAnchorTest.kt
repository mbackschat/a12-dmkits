package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NFR #1's standing anchor — the per-row 5 % bar that replaced the migration rule instead of letting it lapse.
 *
 * **Why a second anchor beside `anchors.json`, which is the question a reviewer should ask first.** That one
 * holds absolute TIMINGS at [PerfBounds.ANCHOR_TOLERANCE] (≈1.665×) because a wall clock on a shared machine
 * cannot carry a tighter bar. That tolerance is precisely the blind band this anchor exists to close: the
 * portable-consistency proposal measured that nothing in the estate fails on a uniform regression below ~1.65×
 * and most bars need 10×–1000×, so two migration phases at +4.9 % per row would satisfy every checkpoint and
 * then sit permanently invisible. A 5 % bar is only meaningful on a quantity that does not move with load, so
 * this anchor carries **bytes and never durations**, and says so rather than implying coverage it lacks.
 *
 * **What it deliberately does NOT claim.** H4's row set also names the timed and native collectors. Those keep
 * the absolute anchor, and this file's `coveredCollectors` lists exactly the three it does hold, so a reader
 * cannot mistake partial coverage for total. Extending it is a measurement at the baseline commit, never an
 * edit: changing a value here re-bases the bar, which the proposal reserves to the owner.
 */
class Nfr1StandingAnchorTest {

    private companion object {
        val METRICS = setOf("retainedBytes", "allocatedBytes")

        /** The conditions that move a heap median, so an anchor missing any of them cannot claim comparability. */
        val PINNED_CONDITIONS = setOf(
            "warmup", "repetitions", "scales", "runtimeName", "runtimeMajor", "maxHeapBytes", "allocationMethod",
        )
    }

    @Test
    fun theAnchorIsTheBaselineCommitsOwnMeasurement() {
        val anchor = Nfr1StandingAnchor.load()

        assertEquals(
            "c8e3b99795fe9c2ce3e9db32632a6dd8f6537043", anchor.baselineCommit,
            "the standing anchor names a different baseline than the proposal's restart commit, so the bar is " +
                "no longer the one NFR #1 specifies",
        )
        assertEquals(5.0, anchor.maxRegressionPercent, 0.0001, "NFR #1's allowance is 5% per row")
        assertTrue(
            anchor.rows.size >= 66,
            "the anchor carries ${anchor.rows.size} rows, fewer than the 66 captured at the baseline — a " +
                "truncated anchor silently stops guarding the rows it dropped, which is the one failure mode " +
                "a byte comparison cannot notice on its own",
        )
    }

    /**
     * The vacuity control, and the reason the comparison is a function rather than a loop inside the assertion.
     *
     * A bar that never fires reads exactly like a bar nothing breaches. Both directions are driven, because a
     * comparison with the wrong inequality passes the positive control alone.
     */
    @Test
    fun theBarFiresJustAboveTheAllowanceAndNotJustBelowIt() {
        val anchor = Nfr1StandingAnchor.load()
        val row = anchor.rows.keys.first()
        val baseline = anchor.rows.getValue(row)

        val justUnder = anchor.breaches(mapOf(row to (baseline * 1.049).toLong()))
        val justOver = anchor.breaches(mapOf(row to (baseline * 1.051).toLong()))

        assertTrue(justUnder.isEmpty(), "a +4.9% row breached a 5% allowance, so the bar is too strict to hold")
        assertTrue(
            justOver.isNotEmpty(),
            "a +5.1% row did not breach the 5% allowance — the bar cannot fail, so every green reading it " +
                "has ever produced proves nothing",
        )
    }

    /**
     * A footprint median is only comparable to one taken under the same conditions, so the anchor records them
     * and the gate refuses a run that differs.
     *
     * The Node major is the case worth naming, because nobody chooses it: the Kotlin/JS toolchain downloads its
     * own runtime, so a plugin upgrade would move every Node heap row with no interpreter source change and the
     * bar would report it as a code regression — or, if it moved the other way, as a pass.
     */
    @Test
    fun everyFootprintCollectorPinsTheConditionsItsMedianWasTakenUnder() {
        val anchor = Nfr1StandingAnchor.load()

        listOf(Nfr1Collector.JVM_FOOTPRINT, Nfr1Collector.NODE_FOOTPRINT).forEach { collector ->
            val identity = anchor.capture[collector.id]
            assertTrue(
                identity != null && identity.fields.keys.containsAll(PINNED_CONDITIONS),
                "${collector.id} has no pinned measurement identity covering $PINNED_CONDITIONS, so a run under " +
                    "other conditions would be compared against it and reported as a percentage: $identity",
            )
        }
        assertEquals(
            "22", anchor.capture.getValue(Nfr1Collector.NODE_FOOTPRINT.id).fields["runtimeMajor"],
            "the Node baseline's runtime major is the field this pin exists for; if it changed, the rows were " +
                "re-measured and that is a re-base",
        )
    }

    /** The identity comparison reports the field that differs, and says nothing when the conditions match. */
    @Test
    fun theIdentityComparisonNamesTheConditionThatDiffers() {
        val baseline = Nfr1MeasurementIdentity(mapOf("runtimeMajor" to "22", "warmup" to "2"))

        assertEquals(emptyList<String>(), baseline.diff(baseline), "identical conditions must compare equal")
        assertEquals(
            listOf("runtimeMajor: 22 ≠ 24"),
            baseline.diff(Nfr1MeasurementIdentity(mapOf("runtimeMajor" to "24", "warmup" to "2"))),
            "the diff must name the field and both values — a bare 'conditions differ' leaves the reader to " +
                "hunt for which one",
        )
    }

    /**
     * `scripts/interpreter-footprint.sh` reads the anchor's base scale back out of these keys rather than
     * restating it, so that the script and the anchor cannot disagree — which makes the key SHAPE load-bearing
     * outside Kotlin. A reshaped key would leave that parse returning nothing; the script refuses on empty, and
     * this is the half that says what the shape is.
     */
    @Test
    fun everyFootprintRowIsAddressedTheWayThisEstateParsesIt() {
        val anchor = Nfr1StandingAnchor.load()
        val footprintRows = anchor.rowsFor(Nfr1Collector.JVM_FOOTPRINT.id) + anchor.rowsFor(Nfr1Collector.NODE_FOOTPRINT.id)

        val malformed = footprintRows.keys.filterNot { row ->
            val segments = row.split('/')
            segments.size == 4 && segments[2].toLongOrNull() != null && segments[3] in METRICS
        }

        assertTrue(
            malformed.isEmpty(),
            "a footprint row is not <collector>/<scenario>/<scale>/<metric> with a numeric scale, so the base " +
                "scale the footprint script reads out of these keys can no longer be found: $malformed",
        )
    }

    /**
     * The delivered-size rows address their asset by placeholder, and the reader refuses without a version to
     * expand it — rather than looking for a file literally named `<version>`, finding none, and reporting all
     * eighteen rows as an absence that reads like a release gone wrong.
     */
    @Test
    fun theDeliveredSizeReaderRefusesWhenNoVersionExpandsThePlaceholder() {
        val anchor = Nfr1StandingAnchor.load()
        val addressed = anchor.rowsFor(Nfr1Collector.DELIVERED_SIZE.id).keys

        assertTrue(
            addressed.all { it.contains(Nfr1Collector.VERSION_PLACEHOLDER) },
            "a delivered-size row names a literal version, so the next release will read as a vanished row " +
                "rather than as the unchanged artifact it is: " +
                addressed.filterNot { it.contains(Nfr1Collector.VERSION_PLACEHOLDER) },
        )
        val refusal = assertThrows(IllegalStateException::class.java) {
            Nfr1Collector.DELIVERED_SIZE.read(File("/nonexistent"), anchor, version = null)
        }
        assertTrue(
            refusal.message.orEmpty().contains(Nfr1Collector.VERSION_VARIABLE),
            "the refusal must name the variable that fixes it; was: ${refusal.message}",
        )
    }

    /**
     * An unknown row is reported rather than ignored, because NFR #1's evidence rule is that missing or
     * inconclusive evidence FAILS. Silently skipping a row the anchor does not know would turn a renamed
     * scenario into permanent green.
     */
    @Test
    fun aRowTheAnchorDoesNotKnowIsReportedRatherThanSkipped() {
        val anchor = Nfr1StandingAnchor.load()

        val unknown = anchor.unknownRows(mapOf("jvm-footprint/invented/1/retainedBytes" to 1L))

        assertEquals(
            listOf("jvm-footprint/invented/1/retainedBytes"), unknown,
            "a measurement the anchor has no baseline for was not reported, so a renamed or added row would " +
                "pass unmeasured",
        )
    }
}
