package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * Behavioral law: <b>where a cross-row aggregate's error field comes from decides when the rule can fire.</b>
 * The kernel requires the error field in the condition ({@code MVK_ERROR_FIELD_NOT_REFERENCED}), and an
 * aggregate with no cap to compare against has no natural top-level field to name.
 *
 * <p>Two ways to satisfy that check are locked here, because the skill teaches the choice. A
 * {@code FieldFilled(<unrelated field>)} conjunct passes the check and silently disables the rule whenever that
 * field is empty: the 2026-11-08 probe of task 08 shipped an overlap rule guarded on the venue name, so
 * overlapping bookings went unreported until a name was entered. An error field inside the aggregated row,
 * named by the starred operand itself, fires regardless of any other field.
 */
class AggregateErrorFieldLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final BoundModel M = BoundModel.ofJson(ORDER);

    /** The cross-row overlap check, with the error field on an unrelated top-level field it guards. */
    private static final String GUARDED = RuntimeLaws.withRule(ORDER, "/Order", "ItemClash", "../CustomerName",
            "ITEM_CLASH", M.field("/Order/CustomerName").filled()
                    .and(Dsl.dateRangesOverlap(M.flatten("/Order/Items*/ItemCoverage"))));

    /** The same check, with the error field on the aggregated in-row field. */
    private static final String IN_ROW = RuntimeLaws.withRule(ORDER, "/Order/Items", "ItemClash", "../ItemCoverage",
            "ITEM_CLASH", Dsl.dateRangesOverlap(M.flatten("/Order/Items*/ItemCoverage")));

    /** Two item rows whose coverages overlap in June; the customer name is filled only when given. */
    private static RuntimeLaws.Doc overlapping(String customer) {
        RuntimeLaws.Doc doc = RuntimeLaws.doc().group("/Order", 1)
                .group("/Order/Items", 1, 1)
                .field("/Order/Items/ItemCoverage", "01.01.2024-30.06.2024", 1, 1, 1)
                .group("/Order/Items", 1, 2)
                .field("/Order/Items/ItemCoverage", "01.06.2024-31.12.2024", 1, 2, 1);
        return customer == null ? doc : doc.field("/Order/CustomerName", customer, 1, 1);
    }

    @Test
    void bothPlacementsAreAdmitted() {
        assertEquals(List.of(), RuntimeLaws.consistencyMessages(GUARDED));
        assertEquals(List.of(), RuntimeLaws.consistencyMessages(IN_ROW));
    }

    @Test
    void aGuardOnAnUnrelatedFieldFiresOnlyOnceThatFieldIsFilled() {
        assertTrue(RuntimeLaws.fires(GUARDED, "ITEM_CLASH", overlapping("Acme")),
                "positive control: with the customer named, the overlap fires");
        assertFalse(RuntimeLaws.fires(GUARDED, "ITEM_CLASH", overlapping(null)),
                "the same overlap goes unreported while the unrelated guard field is empty");
    }

    @Test
    void anInRowErrorFieldFiresWithoutAnyOtherField() {
        assertTrue(RuntimeLaws.fires(IN_ROW, "ITEM_CLASH", overlapping(null)));
    }
}
