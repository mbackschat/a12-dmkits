package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS KF470 — which {@code SumOfProducts} operands reach the operator's own four gates.
 *
 * <p>{@link SumOfProductsLawsTest} measures pairs inside the rule's declaring group, where every repeatable
 * level is below it. These rows move the rule to a fixed sibling, to the error field's own group and into the
 * repeatable level itself, so a level the rule binds and one it does not are both on the operand's path. A
 * pair whose unbound levels are all starred reaches the gates wherever its fields lie, and a star on a level
 * the rule binds is counted by them; a pair that leaves an unbound level unstarred meets the read first.
 *
 * <p>Own domain (an e-commerce fulfilment sheet).
 */
class SumOfProductsOperandReachLawsTest {

    private static final String SHEET = DocumentModelDraft.named("fulfilment-products").locale("en_US")
            .rootGroup("Fulfilment", f -> f
                    .group("Summary", s -> s.numberField("Result", n -> n.fractionDigits(0)))
                    .group("Ledger", l -> l.numberField("Total", n -> n.fractionDigits(0)))
                    .group("Shipments", sh -> sh.repeatable(2)
                            .group("Parcels", p -> p.repeatable(2)
                                    .numberField("Weight", n -> n.fractionDigits(0))
                                    .numberField("Quantity", n -> n.fractionDigits(0))
                                    .stringField("Label", s -> { }))))
            .build().toJson();

    private static final String EVERY = "/Fulfilment/Shipments*/Parcels*";
    private static final String OUTER_UNSTARRED = "/Fulfilment/Shipments/Parcels*";

    @Test
    void aPairWhoseUnboundLevelsAreStarredReachesTheGatesWhereverItsFieldsLie() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Ledger | " + EVERY + "/Weight, " + EVERY + "/Quantity", "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED");
        rows.put("Ledger | " + EVERY + "/Weight, " + EVERY + "/Label", "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED");
        rows.put("Ledger | " + EVERY + "/Weight, /Fulfilment/Ledger/Total", "MVK_DIFFERENT_GROUPS");
        rows.put("Ledger | " + EVERY + "/Weight Having [/Fulfilment/Shipments/Parcels/Quantity] > 0, " + EVERY
                + "/Quantity", "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED");
        rows.put("Summary | " + EVERY + "/Weight, " + EVERY + "/Quantity", "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED");
        // The rule binds `Shipments`, and a star on it is still a second star.
        rows.put("Shipments | " + EVERY + "/Weight, " + EVERY + "/Quantity",
                "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED");
        measured(rows);
    }

    /** The controls: a read of a level nothing binds, or a row read into a target outside the rule group. */
    @Test
    void aPairThatReadsARowMeetsTheReadFirst() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Shipments | Parcels*/Weight, Parcels*/Quantity", "MVK_ERROR_FIELD_NOT_IN_RULEGROUP");
        rows.put("Ledger | " + OUTER_UNSTARRED + "/Weight, " + OUTER_UNSTARRED + "/Quantity",
                "MVK_ERROR_FIELD_NOT_IN_RULEGROUP");
        rows.put("Summary | " + OUTER_UNSTARRED + "/Weight, " + OUTER_UNSTARRED + "/Quantity", "MVK_NO_WILDCARD");
        rows.put("Summary | " + EVERY + "/Weight, " + OUTER_UNSTARRED + "/Quantity", "MVK_NO_WILDCARD");
        rows.put("Summary | /Fulfilment/Shipments*/Parcels/Weight, /Fulfilment/Shipments*/Parcels/Quantity",
                "MVK_NO_WILDCARD");
        // The first operand reads a `Shipments` row and the second stars two levels: the read is raised first.
        rows.put("Shipments | Parcels*/Weight, " + EVERY + "/Quantity", "MVK_ERROR_FIELD_NOT_IN_RULEGROUP");
        measured(rows);
    }

    private static void measured(Map<String, String> rows) {
        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(row -> {
            String[] cell = row.split(" \\| ", 2);
            actual.put(row, KernelLaws.verdictModel(SHEET, "/Fulfilment/" + cell[0], "../../Summary/Result",
                    "FieldFilled(/Fulfilment/Summary/Result) And SumOfProducts(" + cell[1] + ") > 0"));
        });
        assertEquals(rows, actual, "which SumOfProducts operands reach the operator's own gates");
    }
}
