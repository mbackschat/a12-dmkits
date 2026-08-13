package io.github.mbackschat.a12.dm.adapter.perf

import java.util.Locale

/**
 * **The cross-engine early-warning band** ([IF292], which closed IG121). The comparative gates fire only at PARITY — the
 * interpreter must beat both kernel strategies — so they say nothing while a row's margin falls from `20x` to
 * `1.1x`. This carries a per-row floor between the two, and reports a row that drops under it while still winning.
 *
 * **It only ever warns, and that is a measured conclusion rather than caution.** Backtesting candidate floors over
 * the committed series showed that no band reaches zero historical firings: the record contains genuinely
 * contended runs whose *kernel* arms rose 18–39 %, and a margin alone cannot separate "the interpreter regressed"
 * from "this run was contended". A band that fails the build would therefore break the standing rule that the
 * nightly must never fail on a false positive, so the escalation is the one [PerfAnchors] already uses for shared
 * hardware: say it loudly, record it, and let a re-measurement decide.
 *
 * **Why the floor is per row and sized from that row's own history.** Margins across the estate span `2x`–`21x`, so
 * a single global band is impossible — a floor safe for `CO-correlation` is unreachable for `B1-per-row`. Two
 * earlier attempts were built and falsified: a flat constant (which fires on the tightest row from the first run)
 * and a floor derived from the spread between a run's interleaved pairs (which is dispersed by construction and is
 * not the row's run-to-run noise). What works is `k` robust deviations below the row's own median margin, computed
 * over the committed series' CURRENT level — tight where a row is quiet, tolerant where it genuinely wanders.
 *
 * The file is authored by `EMIT_FLOORS=1 BAND_SIGMA=<k> scripts/perf-trend.sh --ratio`, which is the SAME
 * computation the backtest prints, so what ships is what was measured. Refreshing it is a deliberate reviewable
 * diff, exactly like the absolute anchor.
 *
 * **A row whose floor would land at or below parity is deliberately ABSENT.** Six of the 68 measured rows have too
 * little headroom to band at all; emitting `0.73x` for one of them would advertise a warning the parity gate makes
 * unreachable. Absence here means "not banded", never "banded at zero".
 */
internal object PerfMarginFloors {

    private const val RESOURCE = "/perf/margin-floors.json"

    /** `observation key -> floor margin`, for this runner class only. Empty when the class has no block. */
    private val floors: Map<String, Double> by lazy {
        val stream = PerfMarginFloors::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyMap()
        val root = stream.use { PerfObservations.MAPPER.readTree(it) }
        val block = root.path(PerfEnvironment.key)
        if (block.isMissingNode) emptyMap()
        else block.path("floors").properties().associate { (key, value) -> key to value.asDouble() }
    }

    /** The multiple the committed floors were derived at, for the message — a floor is meaningless without it. */
    private val sigma: Double? by lazy {
        val stream = PerfMarginFloors::class.java.getResourceAsStream(RESOURCE) ?: return@lazy null
        val root = stream.use { PerfObservations.MAPPER.readTree(it) }
        root.path(PerfEnvironment.key).path("sigma").takeIf { it.isNumber }?.asDouble()
    }

    /**
     * Judge one engine arm's margin, record it, and warn when it is short. [marginKey] is the KERNEL arm's
     * observation key, which is how the floor file is indexed.
     */
    fun report(marginKey: ObservationKey, margin: Double) {
        val floor = floors[marginKey.text]
        val verdict = MarginBand.judge(margin, floor)
        PerfObservations.recordMargin(marginKey, margin, floor, verdict.status)
        if (verdict == MarginBand.Verdict.WARN) {
            println(
                "[perf][WARN] margin ${"%.2f".format(Locale.ROOT, margin)}x is below this row's floor " +
                    "${"%.2f".format(Locale.ROOT, floor)}x (${sigma?.let { "%.0f".format(Locale.ROOT, it) } ?: "?"} robust deviations below " +
                    "its committed median) while still beating the kernel: ${marginKey.text}. " +
                    "Not a failure — re-measure before reading it as a regression.",
            )
        }
    }
}

/**
 * The band's decision, separated from the resource file and from any timing so the rule itself is checked on
 * scripted numbers ([PerfMarginBandTest]) rather than only by a timed run — the sibling of `AnchorBudget` and
 * `PairedAcceptance`.
 */
internal object MarginBand {

    enum class Verdict(val status: String) {
        /** At or below parity. The comparative gate owns this and has already failed; the band must not double-report. */
        LOST("lost"),

        /** No committed floor for this row — a new lane, a refreshed fingerprint, or a row with too little headroom. */
        UNBANDED("unbanded"),

        /** Beating the kernel, but by less than this row's committed floor. */
        WARN("warn"),

        OK("ok"),
    }

    /**
     * [margin] is `kernel_median / interpreter_median`, so parity is `1.0` and bigger is more headroom.
     *
     * The order of the three guards is the contract. Parity is checked FIRST because the comparative gate already
     * fails there and a second complaint about the same number is noise. A missing floor is `UNBANDED`, never a
     * silent `OK`: a refreshed scenario fingerprint changes the key, and a band that quietly reports success for a
     * row it no longer covers is the way this bar would disappear without anyone noticing.
     */
    fun judge(margin: Double, floor: Double?): Verdict = when {
        margin <= 1.0 -> Verdict.LOST
        floor == null -> Verdict.UNBANDED
        margin < floor -> Verdict.WARN
        else -> Verdict.OK
    }
}
