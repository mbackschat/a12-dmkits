package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §8 — a string CONSTANT put against an Enumeration, on both carriers that admit one.
 *
 * <p>Two kernel methods own this and they answer with DIFFERENT classes, which is the whole reason the grid
 * covers both: {@code CheckVergleichsBedingungImpl.checkEnumValue} for a comparison, and
 * {@code CheckEntityListenUtils.checkEnumType} for a value list — the latter splitting again on whether the
 * reference is categorized. A family assembled from one carrier would name the other's class.
 *
 * <p>Own domain (insurance).
 */
class EnumConstantDomainLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-enum-constants").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .numberField("Premium", f -> f.fractionDigits(2))
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH"))
                    .enumField("Segment", e -> e.value("RETAIL").value("CORPORATE")))
            .build().toJson();

    private static final String COMPARISON = "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON";

    /** One probe, with the error field held constant so every row differs in the constant alone. */
    private static String constant(String probe) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference",
                "FieldFilled(Reference) And " + probe);
    }

    @Test
    void whichValueListCarriersRunTheDomainCheck() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("FieldValueIncludedInValueList",
                constant("FieldValueIncludedInValueList(Tier, \"BASIC\", \"NOPE\")"));
        actual.put("FieldValueNotIncludedInValueList",
                constant("FieldValueNotIncludedInValueList(Tier, \"BASIC\", \"NOPE\")"));
        actual.put("AtLeastOneFieldValueIncludedInValueList",
                constant("AtLeastOneFieldValueIncludedInValueList(Tier, Segment In \"NOPE\")"));
        actual.put("NoFieldValueIncludedInValueList",
                constant("NoFieldValueIncludedInValueList(Tier, Segment In \"NOPE\")"));
        actual.put("NotAllFieldValuesIncludedInValueList",
                constant("NotAllFieldValuesIncludedInValueList(Tier, Segment In \"NOPE\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The two SCALAR predicates check the literal against ONE reference's domain.
        expected.put("FieldValueIncludedInValueList", "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION");
        expected.put("FieldValueNotIncludedInValueList", "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION");
        // The three QUANTIFIERS take several members and check against the UNION of their domains, which is
        // a fourth class — so the carrier decides the class here too, and a family that widened from the
        // scalars by analogy would name the scalars' class on all three.
        expected.put("AtLeastOneFieldValueIncludedInValueList",
                "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY");
        expected.put("NoFieldValueIncludedInValueList",
                "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY");
        expected.put("NotAllFieldValuesIncludedInValueList",
                "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY");

        assertEquals(expected, actual);
    }

    @Test
    void whichClassAConstantOutsideTheDomainDraws() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a COMPARISON against a declared value — the control", constant("[Tier] == \"BASIC\""));
        actual.put("a COMPARISON against an undeclared value", constant("[Tier] == \"NOPE\""));
        actual.put("a categorized COMPARISON, declared classification",
                constant("[Tier -> Band] == \"LOW\""));
        actual.put("a categorized COMPARISON, undeclared classification",
                constant("[Tier -> Band] == \"NOPE\""));
        actual.put("a VALUE LIST of declared values — the control",
                constant("FieldValueIncludedInValueList(Tier, \"BASIC\", \"PLUS\")"));
        actual.put("a VALUE LIST carrying an undeclared value",
                constant("FieldValueIncludedInValueList(Tier, \"BASIC\", \"NOPE\")"));
        actual.put("a categorized VALUE LIST carrying an undeclared classification",
                constant("FieldValueIncludedInValueList(Tier -> Band, \"LOW\", \"NOPE\")"));
        actual.put("a STRING field in a value list — no domain to be outside of",
                constant("FieldValueIncludedInValueList(Reference, \"anything\")"));
        actual.put("a NUMBER field in a value list",
                constant("FieldValueIncludedInValueList(Premium, \"anything\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a COMPARISON against a declared value — the control", KernelLaws.ADMITTED);
        expected.put("a COMPARISON against an undeclared value", COMPARISON);
        expected.put("a categorized COMPARISON, declared classification", KernelLaws.ADMITTED);
        // The category does NOT split the comparison carrier — both draw the same class, where the value
        // list splits into two. So the carrier decides the class and the category decides it only there.
        expected.put("a categorized COMPARISON, undeclared classification", COMPARISON);
        expected.put("a VALUE LIST of declared values — the control", KernelLaws.ADMITTED);
        expected.put("a VALUE LIST carrying an undeclared value", "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION");
        expected.put("a categorized VALUE LIST carrying an undeclared classification",
                "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_CATEGORY");
        // A string field has no domain, so its list is admitted whatever it carries — `isLikeString` is a
        // `continue` in A12's own loop, and a family reading "a literal must be a declared value" without
        // this row would refuse every string value list in the estate.
        expected.put("a STRING field in a value list — no domain to be outside of", KernelLaws.ADMITTED);
        // PREDICTED WRONG from the same loop, which falls through to `MVK_NO_STRING_OR_ENUM`: a comparison
        // gate answers ahead of it, so the kind class that loop raises is not reachable by this shape.
        expected.put("a NUMBER field in a value list", "MVK_INVALID_TYPES_FOR_COMPARISON");

        assertEquals(expected, actual);
    }
}
