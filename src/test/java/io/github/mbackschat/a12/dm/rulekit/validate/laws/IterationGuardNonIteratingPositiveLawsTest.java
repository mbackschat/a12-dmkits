package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — a positive that iterates at no level guards no negative. A guard must reference an
 * iteration, so a presence or comparison over a field or group with no repeatable ancestor leaves a conjoined
 * negative refused, while the unstarred read of a sibling repeatable group still admits it
 * ({@code IterationGuardAdmissionRoutesLawsTest}). The parallel twin is
 * {@code ParallelGuardConnectiveLawsTest}'s non-iterating row.
 */
class IterationGuardNonIteratingPositiveLawsTest {

    private static final String NEG = "MVK_NEG_CONDITION_IN_ITERATION";
    private static final String NEGATIVE = " And FieldNotFilled(/Order/Items/Sku)";

    private static final String MODEL = DocumentModelDraft.named("iteration-non-iterating-guard").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Customer", c -> c.field("Name", FieldKind.STRING))
                    .group("Items", i -> i.repeatable(20)
                            .field("Sku", FieldKind.STRING)
                            .field("Count", FieldKind.NUMBER))
                    .group("Notes", n -> n.repeatable(20)
                            .field("Text", FieldKind.STRING)))
            .build().toJson();

    @Test
    void aPositiveAtNoRepeatableLevelDoesNotGuard() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("same-level guard — the admitted control", verdict("FieldFilled(/Order/Items/Count)"));
        actual.put("unkeyed sibling repeatable group's field", verdict("FieldFilled(/Order/Notes/Text)"));
        actual.put("root field presence", verdict("FieldFilled(/Order/Reference)"));
        actual.put("root field comparison", verdict("[/Order/Reference] == \"A-1\""));
        actual.put("non-repeating group presence", verdict("GroupFilled(/Order/Customer)"));
        actual.put("field of a non-repeating group", verdict("FieldFilled(/Order/Customer/Name)"));
        actual.put("root group presence", verdict("GroupFilled(/Order)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("same-level guard — the admitted control", KernelLaws.ADMITTED);
        // Not a guard verdict: two repeatable groups sharing no index field cannot be paired, so the read is
        // refused before any guard is judged (KERNEL-FINDINGS KF428).
        expected.put("unkeyed sibling repeatable group's field", "MVK_NO_WILDCARD");
        expected.put("root field presence", NEG);
        expected.put("root field comparison", NEG);
        expected.put("non-repeating group presence", NEG);
        expected.put("field of a non-repeating group", NEG);
        expected.put("root group presence", NEG);
        assertEquals(expected, actual, "which positives guard a single-level negative");
    }

    private static String verdict(String positive) {
        return KernelLaws.verdictModel(MODEL, "/Order/Items", "../Sku", positive + NEGATIVE);
    }
}
