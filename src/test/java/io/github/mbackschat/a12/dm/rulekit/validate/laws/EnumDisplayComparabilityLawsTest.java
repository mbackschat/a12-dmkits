package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §8 — on kernel 31.1.1 an enumeration's display labels decide no comparison, on any written arm
 * (KERNEL-FINDINGS KF655).
 *
 * <p>{@link EnumComparabilityLawsTest} locks the core pairs. This grid covers the shapes kernel 30.8.1's gate read
 * differently, and each would expose a surviving remnant of it: a category access, a string-valued function, an
 * inequality, the operands' order, and the token/label conflicts in both directions. On 30.8.1 the text gate read
 * how the partner was WRITTEN ({@code isFeldWertOp}), and a category access skipped the gate.
 *
 * <p>Own domain (insurance policy tiers).
 */
class EnumDisplayComparabilityLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-enum-display").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Holder", f -> { })
                    .enumField("Plain", e -> e.value("BASIC").value("PLUS"))
                    .enumField("PlainTwin", e -> e.value("BASIC").value("PLUS"))
                    // Identity labels: display-bearing by presence, textless by A12's own test.
                    .enumField("Mirrored", e -> e
                            .value("BASIC", l -> l.label("en_US", "BASIC"))
                            .value("PLUS", l -> l.label("en_US", "PLUS")))
                    .enumField("Labelled", e -> e
                            .value("BASIC", l -> l.label("en_US", "Basic cover"))
                            .value("PLUS", l -> l.label("en_US", "Plus cover")))
                    .enumField("LabelledTwin", e -> e
                            .value("BASIC", l -> l.label("en_US", "Basic cover"))
                            .value("PLUS", l -> l.label("en_US", "Plus cover")))
                    // One shared STORED token carrying two different texts.
                    .enumField("TokenConflict", e -> e
                            .value("BASIC", l -> l.label("en_US", "Budget cover"))
                            .value("PLUS", l -> l.label("en_US", "Plus cover")))
                    // One shared DISPLAY text standing for two different stored tokens.
                    .enumField("DisplayConflict", e -> e
                            .value("ECONOMY", l -> l.label("en_US", "Basic cover"))
                            .value("PLUS", l -> l.label("en_US", "Plus cover")))
                    // Conflicts with `Labelled` on the token BASIC, and carries a category as well.
                    .enumField("Categorized", e -> e
                            .value("BASIC", l -> l.label("en_US", "Budget cover"))
                            .value("PLUS", l -> l.label("en_US", "Plus cover"))
                            .category("Band", "LOW", "HIGH")))
            .build().toJson();

    /**
     * One comparison, with the rule's error field named in the guard so no row measures
     * {@code MVK_ERROR_FIELD_NOT_REFERENCED} instead of this gate.
     */
    private static String compare(String left, String right, String... filled) {
        // The guard lists each field once: A12 refuses a repeated entity with `MVK_DUPLICATE_PARAM1`, and a
        // row measuring that would say nothing about the gate under test.
        Set<String> named = new LinkedHashSet<>();
        named.add("Holder");
        named.addAll(List.of(filled));
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Holder",
                "AllFieldsFilled(" + String.join(", ", named) + ") And " + left + " == " + right);
    }

    private static String fields(String left, String right) {
        return compare("[" + left + "]", "[" + right + "]", left, right);
    }

    @Test
    void everyEnumerationPairCompares() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two textless enumerations", fields("Plain", "PlainTwin"));
        actual.put("identity labels against a textless enumeration", fields("Mirrored", "Plain"));
        actual.put("two labelled enumerations agreeing on every value", fields("Labelled", "LabelledTwin"));
        actual.put("a labelled enumeration against a textless one", fields("Labelled", "Plain"));
        actual.put("a shared STORED token carrying two texts", fields("Labelled", "TokenConflict"));
        actual.put("a shared DISPLAY text standing for two tokens", fields("Labelled", "DisplayConflict"));
        actual.put("a conflicting pair read through a CATEGORY",
                compare("[Labelled]", "[Categorized -> Band]", "Labelled", "Categorized"));
        actual.put("the same pair read without the category", fields("Labelled", "Categorized"));

        Map<String, String> expected = new LinkedHashMap<>();
        actual.keySet().forEach(row -> expected.put(row, KernelLaws.ADMITTED));
        assertEquals(expected, actual, "every enumeration pair compares");
    }

    @Test
    void aLabelledEnumerationMeetsATextOnEveryArm() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a textless enumeration against a string field", fields("Plain", "Holder"));
        actual.put("identity labels against a string field", fields("Mirrored", "Holder"));
        actual.put("a labelled enumeration against a string field", fields("Labelled", "Holder"));
        actual.put("the same with the string side written first", fields("Holder", "Labelled"));
        actual.put("the same pair compared for INEQUALITY",
                KernelLaws.verdictModel(POLICY, "/Policy", "../Holder",
                        "AllFieldsFilled(Holder, Labelled) And [Labelled] != [Holder]"));
        actual.put("a labelled enumeration against a string-valued FUNCTION of that field",
                compare("[Labelled]", "RangeAsString(Holder, 1, 3)", "Labelled"));
        actual.put("a labelled enumeration read through a CATEGORY against a string field",
                compare("[Categorized -> Band]", "[Holder]", "Categorized"));

        Map<String, String> expected = new LinkedHashMap<>();
        actual.keySet().forEach(row -> expected.put(row, KernelLaws.ADMITTED));
        assertEquals(expected, actual, "a labelled enumeration meets a text on every arm");
    }
}
