package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §8 — the gate chain A12 runs over a `->` CATEGORY reference.
 *
 * <p>{@code CheckEntityUtils.checkCategory} carries it in one method: the referenced entity must be an
 * Enumeration ({@code MVK_NO_ENUMERATION}), then the name after the arrow must be one the enum DECLARES
 * ({@code MVK_CATEGORY_UNKOWN}). Neither class has a measurement in this estate, and the checker's reference
 * scan deliberately steps over the name after `->` — so an undeclared category is invisible to it today.
 *
 * <p>Own domain (insurance).
 */
class CategoryReferenceGateOrderLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-categories").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH"))
                    .group("Insured", i -> i.stringField("InsuredName", f -> { })))
            .build().toJson();

    /** One probe, with the error field held constant so every row differs in the arrow's operands alone. */
    private static String arrow(String probe) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference",
                "FieldFilled(Reference) And " + probe);
    }

    @Test
    void whichGateAnswersOnACategoryReference() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a declared category — the control", arrow("[Tier -> Band] == \"LOW\""));
        actual.put("an UNDECLARED category name", arrow("[Tier -> NoSuchBand] == \"LOW\""));
        actual.put("an arrow on a STRING field", arrow("[Reference -> Band] == \"LOW\""));
        actual.put("an arrow on a GROUP", arrow("[Insured -> Band] == \"LOW\""));
        actual.put("an arrow on an UNRESOLVABLE name", arrow("[NoSuchField -> Band] == \"LOW\""));
        // The category's own VALUE domain is a separate law from the category NAME, and the checker must
        // not confuse them: an undeclared classification is a different class from an undeclared category.
        actual.put("a declared category, undeclared classification", arrow("[Tier -> Band] == \"NOPE\""));

        assertEquals(Map.of(), actual);
    }
}
