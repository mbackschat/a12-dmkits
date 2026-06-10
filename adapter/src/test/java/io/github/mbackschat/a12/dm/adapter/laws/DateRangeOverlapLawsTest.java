package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Behavioral law — <b>date ranges are closed intervals: touching ranges overlap</b>
 * (RT-SEMANTICS-LEDGER: {@code RuntimeController} dispatch tail; source {@code DatumBereich.ueberlappt}
 * — "both the start and the end date belong to the range", overlap ⇔ {@code end1 >= start2 && end2 >= start1}).
 *
 * <p>The trap: back-to-back periods that share a boundary day — a coverage window ending 31.01 and
 * an item coverage starting 31.01 — <b>do</b> overlap. A modeller wanting seamless, non-overlapping
 * periods must make ranges end the day <em>before</em> the next one starts.
 */
class DateRangeOverlapLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");

    private static String model() {
        return RuntimeLaws.withRule(ORDER, "/Order", "CoverageClash", "../CustomerName", "COVERAGE_CLASH",
                "FieldFilled(CustomerName) And DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)");
    }

    private static RuntimeLaws.Doc doc(String window, String itemCoverage) {
        return RuntimeLaws.doc().group("/Order", 1)
                .field("/Order/CustomerName", "Acme", 1, 1)
                .field("/Order/CoverageWindow", window, 1, 1)
                .group("/Order/Items", 1, 1)
                .field("/Order/Items/ItemCoverage", itemCoverage, 1, 1, 1);
    }

    @Test
    void touchingRangesOverlap() {
        // the window ends on the very day the item coverage starts — closed intervals ⇒ overlap
        assertTrue(RuntimeLaws.fires(model(), "COVERAGE_CLASH",
                doc("01.01.2024-31.01.2024", "31.01.2024-28.02.2024")),
                "ranges sharing one boundary day overlap — start and end both belong to the range");
    }

    @Test
    void backToBackRangesDoNot() {
        // the item coverage starts the day AFTER the window ends — no shared day, no overlap
        assertFalse(RuntimeLaws.fires(model(), "COVERAGE_CLASH",
                doc("01.01.2024-31.01.2024", "01.02.2024-28.02.2024")),
                "adjacent-day ranges do not overlap — seamless periods need next-day starts");
    }

    @Test
    void containedRangesOverlap() {
        assertTrue(RuntimeLaws.fires(model(), "COVERAGE_CLASH",
                doc("01.01.2024-31.12.2024", "01.06.2024-30.06.2024")),
                "a contained range overlaps");
    }
}
