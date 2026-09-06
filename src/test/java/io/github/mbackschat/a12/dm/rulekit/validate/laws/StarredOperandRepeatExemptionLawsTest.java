package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — <b>the exact-duplicate gate reads the FORM of a reference, not the field</b>: a starred
 * occurrence is a repetition-wide scan, so two of them are two independent reads and repeat legally.
 *
 * <p><b>Our catalog says this on seven operators and not on two.</b> {@code Sum}, {@code MaxValue},
 * {@code MinValue}, {@code NumberOfDifferentValues}, {@code NumberOfFilledFields},
 * {@code NumberOfFilledGroups} and {@code FirstFilledValue} all spell out "a wildcarded (starred) operand may
 * repeat". The value-list family says only "no exact reference may repeat", and
 * {@code NumberOfValueInFields} says "a field may not be listed more than once" — both of which, read as
 * written, forbid a form the kernel admits. The inconsistency is internal to one shipped artifact, which is
 * what makes it a defect rather than a difference of emphasis.
 *
 * <p><b>The distinct-reference control is what makes the admissions readable.</b> Without a row proving the
 * two-operand shape is live at all on each carrier, "the starred repeat is admitted" and "this operator never
 * reaches the duplicate gate" are the same observation. The direct-duplicate row supplies the other side: it
 * shows the gate is armed on the same carrier in the same model.
 *
 * <p>Reported by a12-kernel-lean (2026-09-06) on four carriers; measured here on the two whose catalog wording
 * is wrong, since our published text is what carries the defect. Their same-day corroboration from the
 * DateRange keyed-reference direction — the same key repeats and refuses, a different key is admitted, so the
 * exact identity is the (field, key) pair — is their report and is not re-derived here.
 *
 * <p>Own domain (an e-commerce order).
 */
class StarredOperandRepeatExemptionLawsTest {

    private static final String GROUP = "/Order";
    private static final String ERROR_REL = "../Marker";
    private static final String DUPLICATE = "MVK_DUPLICATE_PARAM1";

    private static final String ORDER = DocumentModelDraft.named("order-duplicates").locale("en_US")
            .rootGroup("Order", o -> o
                    .stringField("Marker", s -> { })
                    .stringField("Code", s -> { })
                    .stringField("AltCode", s -> { })
                    .numberField("Total", n -> n.fractionDigits(0))
                    .numberField("AltTotal", n -> n.fractionDigits(0))
                    .group("Items", i -> i.repeatable(20)
                            .stringField("Sku", s -> { })
                            .numberField("Count", n -> n.fractionDigits(0))))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(ORDER, GROUP, ERROR_REL, "FieldFilled(Marker) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /**
     * The value-list carrier, whose catalog constraint reads "no exact reference may repeat" with no exemption.
     */
    @Test
    void aStarredReferenceMayRepeatInAValueListWhereADirectOneMayNot() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("direct reference repeated",
                verdict("AtLeastOneFieldValueIncludedInValueList(Code, Code In \"a\", \"b\")"));
        actual.put("STARRED reference repeated",
                verdict("AtLeastOneFieldValueIncludedInValueList(Items*/Sku, Items*/Sku In \"a\", \"b\")"));
        actual.put("two distinct direct references — liveness control",
                verdict("AtLeastOneFieldValueIncludedInValueList(Code, AltCode In \"a\", \"b\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("direct reference repeated", DUPLICATE);
        expected.put("STARRED reference repeated", KernelLaws.ADMITTED);
        expected.put("two distinct direct references — liveness control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the gate reads the reference FORM; a star is not an exact reference");
    }

    /**
     * The second carrier our catalog overstates, and it has its own grammar — the constant leads, the fields
     * follow the {@code In}. Measured separately rather than carried from the value list, since a shared
     * wording is not a shared gate.
     */
    @Test
    void aStarredReferenceMayRepeatInNumberOfValueInFields() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("direct reference repeated", verdict("NumberOfValueInFields(0 In Total, Total) > 0"));
        actual.put("STARRED reference repeated",
                verdict("NumberOfValueInFields(0 In Items*/Count, Items*/Count) > 0"));
        actual.put("two distinct direct references — liveness control",
                verdict("NumberOfValueInFields(0 In Total, AltTotal) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("direct reference repeated", DUPLICATE);
        expected.put("STARRED reference repeated", KernelLaws.ADMITTED);
        expected.put("two distinct direct references — liveness control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "measured on this carrier, not carried from the value list's rows");
    }
}
