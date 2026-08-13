package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The early-warning band's DECISION, on scripted numbers ([IF292]). The rule is separated from the resource
 * file and from any timing for the reason `PerfAnchorBudgetTest` exists: a bar checked only by a timed run is
 * checked only when it happens to fire, and this one is designed never to fail a build, so nothing else would
 * exercise it at all.
 */
class PerfMarginBandTest {

    @Test
    fun aMarginBelowItsRowsFloorWarnsWhileStillBeatingTheKernel() {
        assertEquals(MarginBand.Verdict.WARN, MarginBand.judge(margin = 3.0, floor = 5.0))
    }

    @Test
    fun aMarginAboveItsFloorIsSilent() {
        assertEquals(MarginBand.Verdict.OK, MarginBand.judge(margin = 7.5, floor = 5.0))
    }

    /**
     * **Parity is checked before the floor**, so a row the comparative gate has already failed is not complained
     * about twice. Scripted with a floor the margin is also under, which is the only input that can tell the order
     * of the two guards apart: judged floor-first this reads `WARN`.
     */
    @Test
    fun aLostRowIsTheComparativeGatesToReportNotTheBands() {
        assertEquals(MarginBand.Verdict.LOST, MarginBand.judge(margin = 0.8, floor = 5.0))
        assertEquals(MarginBand.Verdict.LOST, MarginBand.judge(margin = 1.0, floor = 5.0),
            "exact parity is a loss for this gate — the interpreter must BEAT both strategies")
    }

    /**
     * **A row with no committed floor reports `unbanded`, never `ok`.** A refreshed scenario fingerprint changes
     * the observation key, and six rows are deliberately absent because their floor would land at or below parity;
     * reporting either as a pass is how this bar would silently stop covering anything.
     */
    @Test
    fun aRowWithNoCommittedFloorSaysSoRatherThanPassing() {
        assertEquals(MarginBand.Verdict.UNBANDED, MarginBand.judge(margin = 7.5, floor = null))
        assertEquals(MarginBand.Verdict.UNBANDED, MarginBand.judge(margin = 1.02, floor = null),
            "a barely-winning unbanded row must not read as OK either")
    }

    /** The status strings are what lands in the committed record, so they are part of the contract. */
    @Test
    fun everyVerdictCarriesTheStatusTheRecordWillHold() {
        assertEquals(
            listOf("lost", "unbanded", "warn", "ok"),
            MarginBand.Verdict.entries.map { it.status },
        )
    }
}
