package io.github.mbackschat.a12.dm.adapter.perf

import java.util.Locale
import kotlin.math.ceil

/**
 * What all three of H5's cold arms share, extracted once the third copy arrived.
 *
 * The arms measure the same quantity on JVM, plain Node and in a browser, so their reporting, their property
 * plumbing and their `key=value` child protocol are identical by construction, while their timing sources and
 * their observation records genuinely differ. Only the first group lives here; [CheckerColdStartPerfTest],
 * [CheckerColdNodePerfTest] and [CheckerColdBrowserPerfTest] keep their own records, because merging shapes
 * that differ would cost more clarity than the duplication did. The expected verdict is shared in SUBSTANCE
 * and split in spelling, for the reason given on the two constants below.
 *
 * **The third copy is what earned this, and the accretion is the lesson.** Two copies looked like coincidence;
 * the third arrived with a fourth helper duplicated between only two of them, which is how a set of "small
 * identical helpers" stops being reviewable. It accreted behind green tests exactly as the standing rule warns.
 */
internal object ColdLane {

    /**
     * The verdict every cold arm's synthesized workload draws, pinned rather than assumed — in the spelling the
     * arm's own surface gives it.
     *
     * Inconclusive and incomplete on all three, because the checker does not yet implement every family the
     * model exercises. **Which verdict it is matters far less than that it is checked at all:** an empty
     * rejecting set is also what a report with no findings whatsoever looks like, so a rejecting-only preflight
     * passes on a report that analysed nothing — the hole this pair closes on each arm.
     *
     * **Two constants rather than one, and deliberately.** The JS arms read a serialized report, so they get
     * the WIRE spelling; the JVM arm holds the report object, whose `ConsistencyStatus` members are
     * `data object`s with compiler-generated names. `ReportJson.wireName` is private, and its own comment says
     * the mapping is written out precisely so nothing reflects over the class — so restating that mapping in a
     * perf lane to make all three print one string would be a second statement of a shape the checker owns.
     * Each arm pins what its surface actually says instead.
     */
    val EXPECTED_WIRE_SHAPE = "INCONCLUSIVE" to false

    /** The same verdict as [EXPECTED_WIRE_SHAPE], as the report OBJECT states it. */
    val EXPECTED_OBJECT_SHAPE = "Inconclusive" to false

    /** The cold boundary's floor on observations per scale; a smaller run is a different measurement. */
    const val MINIMUM_OBSERVATIONS = 50

    /**
     * Nearest-rank p95 with the median beside it, the pair the cold boundary asks for.
     *
     * The median says what the boundary usually costs and the p95 says what a user occasionally waits for; a
     * cold lane over independent processes or contexts has a long right tail by construction, so either number
     * alone describes a different machine. Nearest rank needs no interpolation and therefore always names an
     * observation that actually happened.
     */
    fun report(tag: String, quantity: String, programs: Int, millis: List<Double>) {
        val sorted = millis.sorted()
        val p95 = sorted[(ceil(0.95 * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
        PerfHarness.progress(
            "[$tag] $quantity programs=$programs n=${sorted.size} " +
                "median=${format(sorted[sorted.size / 2])} p95=${format(p95)} " +
                "min=${format(sorted.first())} max=${format(sorted.last())} ms",
        )
    }

    /** The same report for an arm whose child clocks in nanoseconds; the conversion is monotone, so p95 agrees. */
    fun reportNanos(tag: String, quantity: String, programs: Int, nanos: List<Long>) =
        report(tag, quantity, programs, nanos.map { it / 1_000_000.0 })

    /** Two decimals, root locale — a decimal comma in a progress line has broken a parse here before. */
    fun format(value: Double): String = "%.2f".format(Locale.ROOT, value)

    fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "-D$name=<value> is required" }

    /**
     * A boolean that refuses anything else, because `String.toBoolean()` would not.
     *
     * Kotlin maps every string but a case-insensitive `"true"` to `false`, and `false` is the value these lanes
     * EXPECT for `complete` — so `undefined`, a renamed field or any garbage would land on the expected side and
     * pass silently. A null check covers an absent key; only this covers a meaningless value.
     */
    fun strictBoolean(raw: String): Boolean = when (raw) {
        "true" -> true
        "false" -> false
        else -> error("a cold child printed complete=$raw, which is not a boolean")
    }

    /**
     * The `key=value` lines a cold child prints, as a map.
     *
     * Splitting on the FIRST `=` only, because a value may contain one: a rejecting finding's address or a
     * label is not guaranteed to be free of it, and a greedy split would silently drop the tail.
     */
    fun printedFields(output: String): Map<String, String> = output.lineSequence()
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
        .associate { (key, value) -> key to value }
}
