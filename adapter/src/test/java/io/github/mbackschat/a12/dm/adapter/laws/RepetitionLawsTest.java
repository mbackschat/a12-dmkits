package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Behavioral law — <b>repetition, iteration, and aggregation</b> (KERNEL-SEMANTICS §9), the kernel's
 * most distinctive and most-mis-authored semantics. Two facets are locked here against the real runtime
 * over a repeatable group (the order model's {@code Items}, error field reaching <em>through</em> it):
 *
 * <ul>
 *   <li><b>Per-row iteration</b> — a rule whose error field is repeatably referenced is evaluated
 *       <em>once per row present</em>, and each message attaches to <em>that specific row</em>.</li>
 *   <li><b>Aggregation of missing rows</b> — {@code Sum} substitutes {@code 0} for an empty field
 *       (so empties neither error nor suppress the aggregate); {@code MaxValue} ignores empties; an
 *       all-empty number list yields {@code 0}.</li>
 * </ul>
 */
class RepetitionLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");

    // rule in /Order whose error field /Order/Items/Count reaches through repeatable Items → iterates per row
    private static final String PER_ROW = RuntimeLaws.withRule(ORDER, "/Order", "CountCap", "../Items/Count",
            "COUNT_HIGH", "FieldFilled(Items/Count) And [Items/Count] > 50");

    /** Three Items rows with the given Count values (null = empty). */
    private static RuntimeLaws.Doc items(String c1, String c2, String c3) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1);
        String[] counts = {c1, c2, c3};
        for (int i = 0; i < counts.length; i++) {
            int r = i + 1;
            d.group("/Order/Items", 1, r).field("/Order/Items/Sku", String.valueOf((char) ('A' + i)), 1, r, 1);
            if (counts[i] != null) {
                d.field("/Order/Items/Count", counts[i], 1, r, 1);
            } else {
                d.emptyField("/Order/Items/Count", 1, r, 1);
            }
        }
        return d;
    }

    @Test
    void aRuleIteratesOncePerRowAndTheMessageAttachesToTheOffendingRow() {
        // counts 10 / 80 / 90 → only rows 2 and 3 violate "> 50"
        List<RuntimeLaws.Msg> msgs = RuntimeLaws.messages(PER_ROW, items("10", "80", "90")).stream()
                .filter(m -> m.code().equals("COUNT_HIGH")).toList();

        assertEquals(2, msgs.size(), () -> "fires once per offending row, got: " + msgs);
        assertTrue(msgs.stream().anyMatch(m -> m.errorField().contains("[1, 2, 1]")),
                () -> "a message attaches to row 2: " + msgs);
        assertTrue(msgs.stream().anyMatch(m -> m.errorField().contains("[1, 3, 1]")),
                () -> "a message attaches to row 3: " + msgs);
    }

    @Test
    void aRuleOverRepeatableDataDoesNotFireWhenEveryRowComplies() {
        assertEquals(false, RuntimeLaws.fires(PER_ROW, "COUNT_HIGH", items("10", "20", "30")),
                "no row exceeds 50, so the per-row rule fires nowhere");
    }

    @Test
    void sumSubstitutesZeroForEmptyRows() {
        String sum80 = RuntimeLaws.withRule(ORDER, "/Order", "Sum80", "../CustomerName", "SUM_80",
                "FieldFilled(CustomerName) And Sum(Items*/Count) == 80");
        assertTrue(RuntimeLaws.fires(sum80, "SUM_80", items("40", null, "40")),
                "an empty row contributes 0, so Sum(40, _, 40) == 80");

        String sum0 = RuntimeLaws.withRule(ORDER, "/Order", "Sum0", "../CustomerName", "SUM_0",
                "FieldFilled(CustomerName) And Sum(Items*/Count) == 0");
        assertTrue(RuntimeLaws.fires(sum0, "SUM_0", items(null, null, null)),
                "a Sum over an all-empty number list is 0");
    }

    @Test
    void maxIgnoresEmptyRows() {
        String max40 = RuntimeLaws.withRule(ORDER, "/Order", "Max40", "../CustomerName", "MAX_40",
                "FieldFilled(CustomerName) And MaxValue(Items*/Count) == 40");
        assertTrue(RuntimeLaws.fires(max40, "MAX_40", items("40", null, "10")),
                "MaxValue ignores the empty row, so Max(40, _, 10) == 40");
    }

    @Test
    void groupFilledIsSatisfiedByACreatedRowEvenWithoutData() {
        // "filled" means the ROW EXISTS, not that it has data — GroupFilled(Items) fires for a created
        // row whose Count is empty, but not when there is no Items row at all
        String model = RuntimeLaws.withRule(ORDER, "/Order", "RowExists", "../Items/Count", "ROW_EXISTS",
                "GroupFilled(Items)");

        RuntimeLaws.Doc rowExists = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1)
                .group("/Order/Items", 1, 1).field("/Order/Items/Sku", "A", 1, 1, 1)
                .emptyField("/Order/Items/Count", 1, 1, 1);
        RuntimeLaws.Doc noRow = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1);

        assertTrue(RuntimeLaws.fires(model, "ROW_EXISTS", rowExists),
                "GroupFilled fires for a created row (its Count is empty — 'filled' = the row exists)");
        assertEquals(false, RuntimeLaws.fires(model, "ROW_EXISTS", noRow),
                "GroupFilled does not fire when no Items row exists");
    }
}
