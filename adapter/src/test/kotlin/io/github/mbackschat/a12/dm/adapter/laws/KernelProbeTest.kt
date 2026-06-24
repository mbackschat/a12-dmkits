package io.github.mbackschat.a12.dm.adapter.laws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises + documents [KernelProbe] — the probe-first harness. It probes an ALREADY-implemented operator
 * (`FirstFilledValue`) across the standard row battery and asserts the kernel behaviors the operator was built
 * to match, so the harness stays exercised (anti-rot) and this test doubles as the usage example. When probing
 * a NEW operator: copy this shape, drop in the condition + battery, read the printed `report`, then implement.
 */
class KernelProbeTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")

    @Test
    fun probesFirstFilledValueAcrossTheRowBattery() {
        val scenarios = KernelProbe.rowPatterns(valid = "5", malformed = "abc").map { (name, rows) ->
            name to KernelProbe.rowsDoc("/Cart", "/Cart/Lines", ITEM, QTY, rows)
        }
        val cond = "FirstFilledValue(Lines*/Qty) > 3"
        // Printed to the test's stdout (the test HTML report) — the thing to READ when probing a new operator.
        println(KernelProbe.report(fuzz, "/Cart", "FF", "../Lines/Qty", cond, scenarios))

        val byName = KernelProbe.probe(fuzz, "/Cart", "FF", "../Lines/Qty", cond, scenarios).associateBy { it.scenario }
        assertEquals(scenarios.size, byName.size, "one outcome per scenario")

        // The first SPECIFIED value decides; empties are skipped; a malformed first-filled suppresses the rule.
        assertFalse(byName.getValue("0 rows").fires, "no rows → no first value → not fire")
        assertTrue(byName.getValue("1 valid").fires, "first filled 5 > 3 → fire")
        assertFalse(byName.getValue("1 empty").fires, "empty row → no first value → not fire")
        assertTrue(byName.getValue("empty, valid").fires, "skip the empty row → first filled 5 > 3 → fire")
        assertFalse(byName.getValue("1 malformed").fires, "malformed first-filled → suppressed → not fire")
        assertTrue(byName.getValue("1 malformed").codes.any { it.startsWith("zahl") }, "malformed value → a formal code fires")
        assertFalse(byName.getValue("malformed, valid").fires, "the first filled value is malformed → suppressed")
    }

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
    }
}
