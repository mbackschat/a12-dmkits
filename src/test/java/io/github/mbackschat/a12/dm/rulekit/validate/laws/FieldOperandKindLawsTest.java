package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — which FIELD predicates refuse a GROUP operand, and which expand it instead.
 *
 * <p>The mirror of {@code GroupListOperandKindLawsTest}: A12's {@code CheckEntityUtils} carries the pair
 * {@code checkIsField} / {@code checkIsGroup}, raising {@code MVK_NO_GROUPS_ALLOWED} and
 * {@code MVK_NO_FIELDS_ALLOWED}. Only the second half has ever been measured here, and KF327's lesson
 * transfers directly: the carrier set is NOT derivable from what the operators are called. A12's own
 * helpers expand a group operand to its field list for some checks ({@code EntityUtils.expandToFieldList}),
 * so "the name says field" predicts nothing.
 *
 * <p>Own domain (insurance).
 */
class FieldOperandKindLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-operand-kind").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .group("Insured", i -> i
                            .stringField("InsuredName", f -> { })
                            .stringField("InsuredCity", f -> { }))
                    .group("Claims", g -> g.repeatable(20)
                            .stringField("ClaimRef", f -> { })
                            .numberField("Amount", f -> f.fractionDigits(2))
                            .group("Assessor", a -> a.stringField("AssessorName", f -> { }))))
            .build().toJson();

    /** One probe, with the error field held constant so every row differs in the operand alone. */
    private static String probe(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference", condition);
    }

    @Test
    void whichFieldCarriersRefuseAGroupOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("FieldFilled", probe("FieldFilled(Reference) And FieldFilled(Insured)"));
        actual.put("FieldNotFilled", probe("FieldFilled(Reference) And FieldNotFilled(Insured)"));
        actual.put("AllFieldsFilled", probe("AllFieldsFilled(Reference, Insured)"));
        actual.put("NoFieldFilled", probe("NoFieldFilled(Reference, Insured)"));
        actual.put("AtLeastOneFieldFilled", probe("AtLeastOneFieldFilled(Reference, Insured)"));
        actual.put("NotAllFieldsFilled", probe("NotAllFieldsFilled(Reference, Insured)"));
        actual.put("FieldsNotCollectivelyFilled", probe("FieldsNotCollectivelyFilled(Reference, Insured)"));
        actual.put("NotExactlyOneFieldFilled", probe("NotExactlyOneFieldFilled(Reference, Insured)"));
        actual.put("MoreThanOneFieldFilled", probe("MoreThanOneFieldFilled(Reference, Insured)"));
        actual.put("NumberOfFilledFields", probe("NumberOfFilledFields(Reference, Insured) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The two SCALAR carriers are the whole refusing set. Both take exactly one entity and have nothing
        // to expand it to, which is the shape `checkIsField` guards.
        expected.put("FieldFilled", "MVK_NO_GROUPS_ALLOWED");
        expected.put("FieldNotFilled", "MVK_NO_GROUPS_ALLOWED");
        // Every LIST carrier expands the group to its fields and quantifies over them, so the operand is
        // legal. A family assembled from the operators' names would refuse these eight — the same defect
        // KF327 records in the other direction, where the name would have refused five.
        expected.put("AllFieldsFilled", KernelLaws.ADMITTED);
        expected.put("NoFieldFilled", KernelLaws.ADMITTED);
        expected.put("AtLeastOneFieldFilled", KernelLaws.ADMITTED);
        expected.put("NotAllFieldsFilled", KernelLaws.ADMITTED);
        expected.put("FieldsNotCollectivelyFilled", KernelLaws.ADMITTED);
        expected.put("NotExactlyOneFieldFilled", KernelLaws.ADMITTED);
        expected.put("MoreThanOneFieldFilled", KernelLaws.ADMITTED);
        expected.put("NumberOfFilledFields", KernelLaws.ADMITTED);

        assertEquals(expected, actual);
    }

    @Test
    void whatElseMovesTheVerdict() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a FIELD operand — the control", probe("FieldFilled(Reference) And FieldFilled(Insured/InsuredName)"));
        actual.put("a REPEATABLE group, unstarred", probe("FieldFilled(Reference) And FieldFilled(Claims)"));
        actual.put("a REPEATABLE group, STARRED", probe("FieldFilled(Reference) And FieldFilled(Claims*)"));
        actual.put("the ROOT group", probe("FieldFilled(Reference) And FieldFilled(/Policy)"));
        actual.put("a group operand in a LIST carrier, starred",
                probe("AtLeastOneFieldFilled(Reference, Claims*)"));
        actual.put("a STAR on a group that does not repeat",
                probe("FieldFilled(Reference) And FieldFilled(Insured*)"));
        actual.put("a nested group below a correctly starred level",
                probe("FieldFilled(Reference) And FieldFilled(Claims*/Assessor)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a FIELD operand — the control", KernelLaws.ADMITTED);
        // Both wildcard gates outrun the kind gate, in both directions: a repetition read without a star,
        // and a star on a level that does not repeat. So the kind class is only reachable where the
        // wildcards are already RIGHT, which is the bound a checker family has to honour to stay truthful.
        expected.put("a REPEATABLE group, unstarred", "MVK_NO_WILDCARD");
        expected.put("a REPEATABLE group, STARRED", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a STAR on a group that does not repeat", "MVK_INVALID_WILDCARD");
        // The root gate also outruns it, so the root group is a second silence rather than a second class.
        expected.put("the ROOT group", "MVK_ROOT_GROUP_REFERENCED");
        expected.put("a group operand in a LIST carrier, starred", KernelLaws.ADMITTED);
        expected.put("a nested group below a correctly starred level", "MVK_NO_GROUPS_ALLOWED");

        assertEquals(expected, actual);
    }
}
