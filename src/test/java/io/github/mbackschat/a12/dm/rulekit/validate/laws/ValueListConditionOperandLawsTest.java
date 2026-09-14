package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §8 — <b>what the four field-list-against-value-list conditions demand of each list, and the
 * one of the four that is a different construct wearing the same grammar</b>.
 *
 * <p>{@code CheckFeldListeUndWerteListenBedingungImpl.check} walks one shape for all four operators and then
 * branches on the operator token twice: once on the FIELD list, where
 * {@code AtLeastOneDateRangeOverlaps} demands exactly one plain field instead of the same-base-type list the
 * other three take, and once on the VALUE list, where the same operator refuses a written-out list outright.
 *
 * <p><b>The enum domain check reads the UNION over the whole field list</b>, so a constant valid for the
 * second field alone is admitted — which a reader checking each field separately would refuse.
 *
 * <p><b>…and a CATEGORY access swaps the domain rather than widening it</b>: a field read through
 * {@code -> Band} contributes its category's tokens and stops contributing its own values, so the
 * enumeration's own value is then refused. The grid holds one enumeration and varies only the access.
 *
 * <p>Own domain (an insurance policy).
 */
class ValueListConditionOperandLawsTest {

    private static final String GROUP = "/Policy";
    private static final String ERROR_REL = "../PolicyNo";

    private static final String BAD_RANGE_OPERAND = "MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON";
    private static final String BAD_CONSTANT = "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY";

    private static final String POLICY = DocumentModelDraft.named("policy-value-lists").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("PolicyNo", f -> { })
                    .stringField("Holder", f -> { })
                    .numberField("Premium", f -> f.fractionDigits(2))
                    .dateRangeField("CoverageWindow", f -> f.format("yyyy-MM-dd").separator("/"))
                    .dateRangeField("RenewalWindow", f -> f.format("yyyy-MM-dd").separator("/"))
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS"))
                    .enumField("OtherTier", e -> e.value("PLUS").value("PREMIUM"))
                    .enumField("BandedTier", e -> e.value("BASIC").value("PLUS").category("Band", "X", "Y"))
                    .group("Broker", g -> g.stringField("BrokerName", f -> { }))
                    .group("Items", g -> g.repeatable(20)
                            .stringField("ItemCode", f -> { })
                            .dateRangeField("ItemCoverage", f -> f.format("yyyy-MM-dd").separator("/"))))
            .build().toJson();

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(POLICY, GROUP, ERROR_REL, "FieldFilled(PolicyNo) And " + condition);
    }

    /** The date-range operator's own FIELD-list shape: exactly one plain field, never a list. */
    @Test
    void theDateRangeOperatorDemandsExactlyOnePlainFieldOnItsLeft() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one date-range field", verdict("AtLeastOneDateRangeOverlaps(CoverageWindow In Items*/ItemCoverage)"));
        actual.put("two date-range fields",
                verdict("AtLeastOneDateRangeOverlaps(CoverageWindow, RenewalWindow In Items*/ItemCoverage)"));
        actual.put("a group", verdict("AtLeastOneDateRangeOverlaps(Broker In Items*/ItemCoverage)"));
        actual.put("a starred field",
                verdict("AtLeastOneDateRangeOverlaps(Items*/ItemCoverage In CoverageWindow)"));
        actual.put("a written-out value list", verdict("AtLeastOneDateRangeOverlaps(CoverageWindow In \"x\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one date-range field", KernelLaws.ADMITTED);
        expected.put("two date-range fields", BAD_RANGE_OPERAND);
        expected.put("a group", BAD_RANGE_OPERAND);
        expected.put("a starred field", BAD_RANGE_OPERAND);
        // A second branch on the same operator token, on the VALUE list rather than the field one.
        expected.put("a written-out value list", "MVK_NO_CONST");
        assertEquals(expected, actual, "the date-range operator's field list is one plain field, and nothing else");
    }

    /** The control: the same three shapes under an operator that takes a list. */
    @Test
    void theOtherThreeOperatorsTakeAListWhereTheDateRangeOneRefusesIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two text fields", verdict("NoFieldValueIncludedInValueList(Holder, PolicyNo In \"x\")"));
        actual.put("a group, which expands to its fields",
                verdict("NoFieldValueIncludedInValueList(Broker In \"x\")"));
        actual.put("a starred field", verdict("NoFieldValueIncludedInValueList(Items*/ItemCode In \"x\")"));
        actual.put("two texts under the at-least-one spelling",
                verdict("AtLeastOneFieldValueIncludedInValueList(Holder, PolicyNo In \"x\")"));
        actual.put("two texts under the not-all spelling",
                verdict("NotAllFieldValuesIncludedInValueList(Holder, PolicyNo In \"x\")"));
        actual.put("a number field", verdict("NoFieldValueIncludedInValueList(Premium In \"1\")"));
        actual.put("a text beside a number", verdict("NoFieldValueIncludedInValueList(Holder, Premium In \"x\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two text fields", KernelLaws.ADMITTED);
        expected.put("a group, which expands to its fields", KernelLaws.ADMITTED);
        expected.put("a starred field", KernelLaws.ADMITTED);
        expected.put("two texts under the at-least-one spelling", KernelLaws.ADMITTED);
        expected.put("two texts under the not-all spelling", KernelLaws.ADMITTED);
        expected.put("a number field", "MVK_INVALID_TYPES_FOR_COMPARISON");
        expected.put("a text beside a number", "MVK_VARYING_TYPES_NOT_ALLOWED");
        assertEquals(expected, actual, "the list-taking operators, and the two classes their field list draws");
    }

    /** The enum domain: the UNION over the field list, and what a category access does to it. */
    @Test
    void theEnumDomainIsTheUnionOverTheFieldListAndACategorySwapsIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a declared value", verdict("NoFieldValueIncludedInValueList(Tier In \"BASIC\")"));
        actual.put("an undeclared value", verdict("NoFieldValueIncludedInValueList(Tier In \"GOLD\")"));
        actual.put("a value declared only by the SECOND field",
                verdict("NoFieldValueIncludedInValueList(Tier, OtherTier In \"PREMIUM\")"));
        actual.put("a value declared by neither",
                verdict("NoFieldValueIncludedInValueList(Tier, OtherTier In \"GOLD\")"));
        actual.put("a category token under a category access",
                verdict("NoFieldValueIncludedInValueList(BandedTier -> Band In \"X\")"));
        actual.put("the enumeration's OWN value under a category access",
                verdict("NoFieldValueIncludedInValueList(BandedTier -> Band In \"BASIC\")"));
        actual.put("the same value with no category access",
                verdict("NoFieldValueIncludedInValueList(BandedTier In \"BASIC\")"));
        actual.put("any value over a TEXT field", verdict("NoFieldValueIncludedInValueList(Holder In \"GOLD\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a declared value", KernelLaws.ADMITTED);
        expected.put("an undeclared value", BAD_CONSTANT);
        // The union is what decides, so a constant no single field admits on its own still passes.
        expected.put("a value declared only by the SECOND field", KernelLaws.ADMITTED);
        expected.put("a value declared by neither", BAD_CONSTANT);
        expected.put("a category token under a category access", KernelLaws.ADMITTED);
        // The access SWAPS the domain rather than widening it, which these two rows separate.
        expected.put("the enumeration's OWN value under a category access", BAD_CONSTANT);
        expected.put("the same value with no category access", KernelLaws.ADMITTED);
        expected.put("any value over a TEXT field", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the enum domain the written-out value list is checked against");
    }

    /**
     * The FIELD list's two gates are asked before the VALUE list's type is looked at, which is what decides
     * the class when the two lists disagree AND the field list is of a kind the operator refuses.
     *
     * <p>A reader merging the two lists into one same-type walk gets the first row wrong — it would answer
     * with the disagreement class where A12 answers with the field list's own.
     */
    @Test
    void theFieldListsOwnGatesAreAskedBeforeTheValueListsType() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a date range as the FIELD list, a text opposite",
                verdict("NoFieldValueIncludedInValueList(CoverageWindow In Holder)"));
        actual.put("a text as the FIELD list, a date range opposite",
                verdict("NoFieldValueIncludedInValueList(Holder In CoverageWindow)"));
        actual.put("one plain text under the date-range operator",
                verdict("AtLeastOneDateRangeOverlaps(Holder In Items*/ItemCoverage)"));
        actual.put("a MIXED field list under the date-range operator",
                verdict("AtLeastOneDateRangeOverlaps(Holder, Premium In CoverageWindow)"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The field list's own kind gate answers; the value list's type is never reached.
        expected.put("a date range as the FIELD list, a text opposite", "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
        expected.put("a text as the FIELD list, a date range opposite", "MVK_VARYING_TYPES_NOT_ALLOWED");
        // The shape gate passes on one plain field, so the value list's type match is what answers.
        expected.put("one plain text under the date-range operator", "MVK_VARYING_TYPES_NOT_ALLOWED");
        // The same-type gate runs for ALL FOUR operators, ahead of the branch on the operator token, so the
        // date-range arm draws it too — its own shape class is never reached on a mixed list.
        expected.put("a MIXED field list under the date-range operator", "MVK_VARYING_TYPES_NOT_ALLOWED");
        assertEquals(expected, actual, "the field list is judged before the value list it is compared with");
    }
}
