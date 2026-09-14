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
 * KERNEL-FINDINGS §8 — when two enumerations may be compared, and when an enumeration may meet a text.
 *
 * <p>{@link EnumComparabilityLawsTest} locks the law's core as ten single verdicts. This grid measures what a
 * reproduction additionally needs and what no assertion there could see: which LOCALES the map comparison
 * runs over, what a category access does to it, and what the text gate actually reads on the text side.
 *
 * <p>Three of its rows are the discriminators a reading of {@code CheckVergleichsBedingungImpl} does not
 * give. The shared-locale set is an INTERSECTION, so two labelled enumerations whose labels live in
 * different locales are never compared at all. A category access on either side skips the whole gate, which
 * makes a conflicting pair admitted. And the text gate is spelled {@code isFeldWertOp} — it reads how the
 * partner is WRITTEN, not what type it has — so a labelled enumeration meets a string-valued function
 * happily and refuses the same field read bare.
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
                    // Labelled only for an UNDECLARED locale, which A12 admits — so it is display-bearing
                    // and shares no display locale with any of the others.
                    .enumField("ForeignLabelled", e -> e
                            .value("BASIC", l -> l.label("fr_FR", "Budget cover"))
                            .value("PLUS", l -> l.label("fr_FR", "Plus cover")))
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
    void whenTwoEnumerationsMayBeCompared() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two textless enumerations — the control", fields("Plain", "PlainTwin"));
        actual.put("identity labels against a textless enumeration", fields("Mirrored", "Plain"));
        actual.put("two labelled enumerations agreeing on every value", fields("Labelled", "LabelledTwin"));
        actual.put("a labelled enumeration against a textless one", fields("Labelled", "Plain"));
        actual.put("a shared STORED token carrying two texts", fields("Labelled", "TokenConflict"));
        actual.put("a shared DISPLAY text standing for two tokens", fields("Labelled", "DisplayConflict"));
        actual.put("a labelled enumeration against one labelled only for an UNDECLARED locale",
                fields("Labelled", "ForeignLabelled"));
        actual.put("that undeclared-locale enumeration against a TEXTLESS one",
                fields("ForeignLabelled", "Plain"));
        actual.put("a conflicting pair read through a CATEGORY",
                compare("[Labelled]", "[Categorized -> Band]", "Labelled", "Categorized"));
        actual.put("the same pair read without the category", fields("Labelled", "Categorized"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two textless enumerations — the control", KernelLaws.ADMITTED);
        expected.put("identity labels against a textless enumeration", KernelLaws.ADMITTED);
        expected.put("two labelled enumerations agreeing on every value", KernelLaws.ADMITTED);
        expected.put("a labelled enumeration against a textless one", "MVK_INVALID_COMPARE_ENUM_TO_STRING");
        expected.put("a shared STORED token carrying two texts", "MVK_INVALID_COMPARE_ENUM_TO_ENUM");
        expected.put("a shared DISPLAY text standing for two tokens", "MVK_INVALID_COMPARE_ENUM_TO_ENUM");
        // A label written for a locale the model does not declare does not make an enumeration
        // display-bearing at all, so this pair is the display/textless mismatch rather than a map comparison
        // — and the next row is what separates "textless" from "conflicting".
        expected.put("a labelled enumeration against one labelled only for an UNDECLARED locale",
                "MVK_INVALID_COMPARE_ENUM_TO_STRING");
        expected.put("that undeclared-locale enumeration against a TEXTLESS one", KernelLaws.ADMITTED);
        // A category on EITHER side returns before the display machinery runs, so the identical conflict is
        // admitted through the projection and refused without it.
        expected.put("a conflicting pair read through a CATEGORY", KernelLaws.ADMITTED);
        expected.put("the same pair read without the category", "MVK_INVALID_COMPARE_ENUM_TO_ENUM");
        assertEquals(expected, actual, "when two enumerations may be compared");
    }

    @Test
    void whatTheTextGateReadsOnTheTextSide() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a textless enumeration against a string field — the control", fields("Plain", "Holder"));
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
        expected.put("a textless enumeration against a string field — the control", KernelLaws.ADMITTED);
        expected.put("identity labels against a string field", KernelLaws.ADMITTED);
        expected.put("a labelled enumeration against a string field", "MVK_INVALID_COMPARE_ENUM_TO_STRING");
        expected.put("the same with the string side written first", "MVK_INVALID_COMPARE_ENUM_TO_STRING");
        expected.put("the same pair compared for INEQUALITY", "MVK_INVALID_COMPARE_ENUM_TO_STRING");
        // `isFeldWertOp` reads how the partner is WRITTEN, not what type it carries — so the same field
        // behind a string-valued function does not trip the gate its bare read does.
        expected.put("a labelled enumeration against a string-valued FUNCTION of that field",
                KernelLaws.ADMITTED);
        expected.put("a labelled enumeration read through a CATEGORY against a string field",
                KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what the text gate reads on the text side");
    }
}
