package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §1 — the SIBLING sweep of the value-list family: the three members whose duplicate arm
 * nothing has measured, and the two spellings each one has.
 *
 * <p>{@code EnumValueListUnionLawsTest} and {@code DuplicateOverlapCarrierSweepLawsTest} between them measure
 * {@code AtLeastOneFieldValueIncludedInValueList}, {@code NoFieldValueIncludedInValueList} and the scalar
 * {@code FieldValueIncludedInValueList}. That leaves {@code NotAllFieldValuesIncludedInValueList}, the scalar
 * {@code FieldValueNotIncludedInValueList}, and {@code AtLeastOneDateRangeOverlaps} — the last of which is the
 * one member with no literal spelling at all, so its duplicate is never a literal-list one: it is either
 * across the two halves or within the value list, and both loci are measured below.
 *
 * <p><b>The scalar negative twin takes ONE spelling, and by refusal rather than by choice.</b> A literal
 * carries no reference identity, so a literal value list admits no duplicate at all — the missing cell is
 * forced, not unvaried, which is why only the field-read spelling appears for it below.
 *
 * <p><b>The two spellings matter because they are two carriers.</b> A literal value list
 * ({@code f1, f2 In "a"}) gates the field list alone; a field-READ value list ({@code f1 In f2}) gates the two
 * lists as one union, so a reference repeated across the halves is a duplicate. Same operator id, different
 * denominators — which is why each row below names its spelling.
 */
class ValueListQuantifierDuplicateSiblingLawsTest {

    private static final String P1 = "MVK_DUPLICATE_PARAM1";

    /**
     * Two FIXED date-range fields plus a starred one, which {@code order.dm.json} cannot supply: it carries
     * exactly one fixed range ({@code CoverageWindow}), so "repeated inside the value list" and "repeated
     * across the two lists" would be the same condition there and neither row could isolate the other. A
     * starred operand is exempt from the exact arm, so a starred-only fixture measures the exemption and never
     * the rule.
     */
    private static final String COVERAGE = DocumentModelDraft.named("coverage").locale("en_US")
            .rootGroup("Policy", root -> root
                    .stringField("Reference", f -> { })
                    .group("Cover", g -> g
                            .dateRangeField("PolicyWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                            .dateRangeField("ExtensionWindow", r -> r.format("dd.MM.yyyy").separator("-")))
                    .group("Periods", g -> g.repeatable(20)
                            .dateRangeField("Window", r -> r.format("dd.MM.yyyy").separator("-"))))
            .build().toJson();

    /** What a row reads off a refusal: its code, or the reference the kernel blames. */
    private enum Witness { CODE, NAMED }

    private static String verdict(String json, String group, String errorField, String condition) {
        return verdict(json, group, errorField, condition, Witness.CODE);
    }

    private static String verdict(String json, String group, String errorField, String condition,
            Witness witness) {
        var result = KernelLaws.checkModel(json, group, errorField, condition);
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        if (witness == Witness.NAMED) {
            // The message names the offending reference in single quotes; the code alone cannot say WHICH of
            // two competing collisions won, which is the whole question here.
            var matcher = java.util.regex.Pattern.compile("'([^']*)' was defined more than once")
                    .matcher(result.diagnostics().getFirst().summary());
            return matcher.find() ? matcher.group(1) : result.diagnostics().getFirst().summary();
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(Collectors.joining("+"));
    }

    private static String order(String condition) {
        return verdict(Fixtures.orderJson(), "/Order", "../CustomerName",
                "FieldFilled(CustomerName) And " + condition);
    }

    private static String policy(String condition) {
        return verdict(COVERAGE, "/Policy", "../Reference", "FieldFilled(Reference) And " + condition);
    }

    /** One table, so a kernel shift shows every cell rather than aborting at the first. */
    @Test
    void everyValueListQuantifierAnswersTheDuplicateArmOnBothSpellings() {
        var actual = new LinkedHashMap<String, String>();

        // --- NotAllFieldValuesIncludedInValueList: both spellings.
        actual.put("NotAll | literal list, field repeated in the field list",
                order("NotAllFieldValuesIncludedInValueList(CustomerName, CustomerName In \"x\")"));
        actual.put("NotAll | entity list, field repeated ACROSS the two lists",
                order("NotAllFieldValuesIncludedInValueList(CustomerName In CustomerName)"));

        // --- The scalar negative twin, whose positive form is measured and whose polarity flip is not.
        actual.put("scalar Not | subject repeated inside its own value list",
                order("FieldValueNotIncludedInValueList(CustomerName, [CustomerName])"));

        // --- AtLeastOneDateRangeOverlaps: entity-only, so the cross-list duplicate is its whole rule.
        actual.put("AtLeastOneDateRangeOverlaps | range repeated ACROSS the two lists",
                policy("AtLeastOneDateRangeOverlaps(Cover/PolicyWindow In Cover/PolicyWindow)"));
        actual.put("AtLeastOneDateRangeOverlaps | two distinct ranges (the liveness control)",
                policy("AtLeastOneDateRangeOverlaps(Cover/PolicyWindow In Cover/ExtensionWindow)"));
        actual.put("AtLeastOneDateRangeOverlaps | the SAME starred range on both sides",
                policy("AtLeastOneDateRangeOverlaps(Periods*/Window In Periods*/Window)"));
        // Which side does that refuse? The subject or the star anywhere? Two rows discriminate.
        actual.put("AtLeastOneDateRangeOverlaps | starred SUBJECT, fixed value",
                policy("AtLeastOneDateRangeOverlaps(Periods*/Window In Cover/PolicyWindow)"));
        actual.put("AtLeastOneDateRangeOverlaps | fixed subject, starred VALUE",
                policy("AtLeastOneDateRangeOverlaps(Cover/PolicyWindow In Periods*/Window)"));
        // The exemption's one authorable locus on this operator: repeated INSIDE the value list, subject fixed.
        actual.put("AtLeastOneDateRangeOverlaps | fixed range repeated inside the value list",
                policy("AtLeastOneDateRangeOverlaps(Cover/PolicyWindow In Cover/ExtensionWindow,"
                        + " Cover/ExtensionWindow)"));
        actual.put("AtLeastOneDateRangeOverlaps | STARRED range repeated inside the value list",
                policy("AtLeastOneDateRangeOverlaps(Cover/PolicyWindow In Periods*/Window, Periods*/Window)"));

        // WHICH collision the kernel names, when the subject's and the value list's compete. Authored by hand
        // in both orders, because the client gate refuses the shape by design and the point is precisely that
        // authored position does not decide it.
        actual.put("scalar | competing collisions, subject's authored LAST", verdict(Fixtures.orderJson(),
                "/Order", "../CustomerName", "FieldFilled(CustomerName) And FieldValueIncludedInValueList("
                        + "ProductCode, [ShippingAddress/City], [ShippingAddress/City], [ProductCode])",
                Witness.NAMED));
        actual.put("scalar | competing collisions, subject's authored FIRST", verdict(Fixtures.orderJson(),
                "/Order", "../CustomerName", "FieldFilled(CustomerName) And FieldValueIncludedInValueList("
                        + "ProductCode, [ProductCode], [ShippingAddress/City], [ShippingAddress/City])",
                Witness.NAMED));

        var expected = new LinkedHashMap<String, String>();
        expected.put("NotAll | literal list, field repeated in the field list", P1);
        expected.put("NotAll | entity list, field repeated ACROSS the two lists", P1);
        expected.put("scalar Not | subject repeated inside its own value list", P1);
        expected.put("AtLeastOneDateRangeOverlaps | range repeated ACROSS the two lists", P1);
        expected.put("AtLeastOneDateRangeOverlaps | two distinct ranges (the liveness control)",
                KernelLaws.ADMITTED);
        expected.put("AtLeastOneDateRangeOverlaps | the SAME starred range on both sides",
                "MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON");
        expected.put("AtLeastOneDateRangeOverlaps | starred SUBJECT, fixed value",
                "MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON");
        expected.put("AtLeastOneDateRangeOverlaps | fixed subject, starred VALUE", KernelLaws.ADMITTED);
        expected.put("AtLeastOneDateRangeOverlaps | fixed range repeated inside the value list", P1);
        expected.put("AtLeastOneDateRangeOverlaps | STARRED range repeated inside the value list",
                KernelLaws.ADMITTED);

        // The value list resolves before the subject, so the subject's own collision is never the witness
        // while a list collision stands — and authored order does not change it.
        expected.put("scalar | competing collisions, subject's authored LAST", "ShippingAddress/City");
        expected.put("scalar | competing collisions, subject's authored FIRST", "ShippingAddress/City");

        assertEquals(expected, actual, "each id's duplicate arm is claimed in its catalog entry; a cell that "
                + "moved is the kernel disagreeing with that claim");
    }

}
