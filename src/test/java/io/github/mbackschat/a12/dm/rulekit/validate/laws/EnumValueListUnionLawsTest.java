package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §8 — the enum field-list value-list quantifiers admit a literal from the UNION of the
 * selected members' domains (a direct enum's stored values plus a category access's category values), a
 * direct ref and a category ref on the same field are distinct, an exact duplicate is rejected, and the
 * entity-valued form gates same-base-kind plus combined-reference uniqueness without a containment check
 * (SPEC-2026-07-22-05). The order fixture's {@code Priority} (STANDARD/EXPRESS/OVERNIGHT, category
 * SpeedClass = NORMAL/FAST/FAST) and {@code RushLevel} (1/2/3) are two enums with DISJOINT domains.
 */
class EnumValueListUnionLawsTest {

    // --- literal value list: UNION admission -------------------------------------------------------

    @Test
    void aLiteralFromEitherDisjointDomainIsAccepted() {
        KernelLaws.acceptsOrder("/Order", "../Priority",
                "AtLeastOneFieldValueIncludedInValueList(Priority, RushLevel In \"STANDARD\", \"1\")");
    }

    @Test
    void aLiteralInNeitherDomainIsRejected() {
        KernelLaws.rejectsOrder("/Order", "../Priority",
                "AtLeastOneFieldValueIncludedInValueList(Priority, RushLevel In \"ZZZ\")",
                "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY");
    }

    @Test
    void aCategoryValueIsAdmittedViaTheCategoryDomain() {
        KernelLaws.acceptsOrder("/Order", "../Priority",
                "NoFieldValueIncludedInValueList(Priority -> SpeedClass In \"FAST\")");
    }

    // --- exact-reference rules ---------------------------------------------------------------------

    @Test
    void anExactDuplicateReferenceIsRejected() {
        KernelLaws.rejectsOrder("/Order", "../Priority",
                "AtLeastOneFieldValueIncludedInValueList(Priority, Priority In \"STANDARD\")",
                "MVK_DUPLICATE_PARAM1");
    }

    @Test
    void aDirectRefAndACategoryRefOnTheSameFieldAreDistinct() {
        KernelLaws.acceptsOrder("/Order", "../Priority",
                "AtLeastOneFieldValueIncludedInValueList(Priority, Priority -> SpeedClass In \"STANDARD\", \"FAST\")");
    }

    @Test
    void aStringFieldListAlsoRejectsAnExactDuplicate() {   // sibling sweep across the family
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "NoFieldValueIncludedInValueList(CustomerName, CustomerName In \"x\")", "MVK_DUPLICATE_PARAM1");
    }

    // --- entity-valued (field-valued right side) ---------------------------------------------------

    @Test
    void entityValuedRejectsMixedBaseKinds() {
        KernelLaws.rejectsOrder("/Order", "../Priority",
                "AtLeastOneFieldValueIncludedInValueList(Priority In Quantity)", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void entityValuedRejectsACombinedDuplicate() {
        KernelLaws.rejectsOrder("/Order", "../Priority",
                "AtLeastOneFieldValueIncludedInValueList(Priority In Priority)", "MVK_DUPLICATE_PARAM1");
    }

    @Test
    void entityValuedAcceptsDisjointEnumsWithoutContainment() {
        KernelLaws.acceptsOrder("/Order", "../Priority",
                "AtLeastOneFieldValueIncludedInValueList(Priority In RushLevel)");
    }
}
