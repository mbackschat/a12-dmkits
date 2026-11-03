package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * KERNEL-FINDINGS KF670 — what kernel 31.1.1 asks of {@code SumOfTerms}' TERM, and where the operator composes. The
 * iteration-entity half is {@link SumOfTermsIterationEntityLawsTest}.
 *
 * <p>Every refusal row is paired with an admitted row that differs from it in one element of the term, so a row
 * cannot be refused for a reason it does not name. Own domain (insurance claims).
 */
class SumOfTermsTermLawsTest {

    private static final String POLICY = ModelDrafts.policyClaims().toJson();

    private static String atPolicy(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Holder", "FieldFilled(Holder) And " + condition);
    }

    @Test
    void theTermIsANumberThatIteratesOnlyTheIterationEntitysPath() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("control: one field of the iteration entity",
                atPolicy("SumOfTerms(Claims*, [Claims/Amount]) > 0"));
        actual.put("a string term", atPolicy("SumOfTerms(Claims*, [Claims/Status]) > 0"));
        actual.put("a term that also reads an unrelated repeatable group",
                atPolicy("SumOfTerms(Claims*, [Claims/Amount] + [Vehicles/Value]) > 0"));
        actual.put("a term that also reads a non-repeatable group",
                atPolicy("SumOfTerms(Claims*, [Claims/Amount] + [Address/Zip]) > 0"));
        actual.put("a term with a semantic index",
                atPolicy("SumOfTerms(Claims*, [Claims/Amount] + [Claims/Amount For \"C1\"]) > 0"));
        actual.put("a term read through the outer marker", atPolicy("SumOfTerms(Claims*, [$Claims/Amount]) > 0"));
        actual.put("a term holding a filtered aggregate the filter's own check admits", atPolicy(
                "SumOfTerms(Claims*, [Claims/Amount] + NumberOfFilledFields(Vehicles*/Value Having [Vehicles/Value] > 1)) > 0"));
        actual.put("a term holding a filtered aggregate the filter's own check refuses", atPolicy(
                "SumOfTerms(Claims*, NumberOfFilledGroups(Claims/Parts* Having [Claims/Parts/Qty] > 1)) > 0"));
        actual.put("a term holding the same aggregate unfiltered", atPolicy(
                "SumOfTerms(Claims*, [Claims/Amount] + NumberOfFilledFields(Vehicles*/Value)) > 0"));
        actual.put("a term holding an unfiltered aggregate below the wildcard",
                atPolicy("SumOfTerms(Claims*, Sum(Claims/Parts*/Qty)) > 0"));
        actual.put("a term holding a nested SumOfTerms",
                atPolicy("SumOfTerms(Claims*, SumOfTerms(Claims/Parts*, [Claims/Parts/Qty])) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("control: one field of the iteration entity", KernelLaws.ADMITTED);
        expected.put("a string term", "MVK_NO_NUMBER");
        expected.put("a term that also reads an unrelated repeatable group", "MVK_INVALID_ITERATION_IN_SET_EXPRESSION");
        expected.put("a term that also reads a non-repeatable group", KernelLaws.ADMITTED);
        expected.put("a term with a semantic index", "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
        expected.put("a term read through the outer marker", "MVK_INVALID_OUTER_ITERATION");
        expected.put("a term holding a filtered aggregate the filter's own check admits",
                "MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION");
        expected.put("a term holding a filtered aggregate the filter's own check refuses", "MVK_NO_WILDCARD");
        expected.put("a term holding the same aggregate unfiltered", KernelLaws.ADMITTED);
        expected.put("a term holding an unfiltered aggregate below the wildcard", KernelLaws.ADMITTED);
        expected.put("a term holding a nested SumOfTerms", "MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION");
        assertEquals(expected, actual);
    }

    @Test
    void aNestedIterationEntityIsIteratedAtItsLowestLevel() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("both levels starred, the term iterates both",
                atPolicy("SumOfTerms(Claims*/Parts*, [Claims/Parts/Qty] * [Claims/Parts/Price]) > 0"));
        actual.put("both levels starred, the term iterates only the upper",
                atPolicy("SumOfTerms(Claims*/Parts*, [Claims/Amount]) > 0"));
        actual.put("the upper level iterated by the rule",
                KernelLaws.verdictModel(POLICY, "/Policy", "../Claims/Amount",
                        "FieldFilled(Claims/Amount) And SumOfTerms(Claims/Parts*, [Claims/Parts/Qty]) > 10"));
        actual.put("the upper level neither starred nor iterated by the rule",
                atPolicy("SumOfTerms(Claims/Parts*, [Claims/Parts/Qty]) > 10"));
        actual.put("a starred non-repeatable group", atPolicy("SumOfTerms(Address*, [Address/Zip]) > 0"));
        actual.put("the star above a repeatable group left unstarred",
                atPolicy("SumOfTerms(Claims*/Parts, [Claims/Parts/Qty]) > 0"));
        actual.put("the star above a non-repeatable group",
                atPolicy("SumOfTerms(Claims*/Assessment, [Claims/Assessment/Score]) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both levels starred, the term iterates both", KernelLaws.ADMITTED);
        expected.put("both levels starred, the term iterates only the upper", "MVK_NO_ITERATION_FOR_LOWEST_WILDCARD");
        expected.put("the upper level iterated by the rule", KernelLaws.ADMITTED);
        expected.put("the upper level neither starred nor iterated by the rule", "MVK_NO_WILDCARD");
        expected.put("a starred non-repeatable group", "MVK_INVALID_WILDCARD");
        expected.put("the star above a repeatable group left unstarred", "MVK_NO_WILDCARD");
        expected.put("the star above a non-repeatable group", "MVK_GROUP_NOT_REPEATABLE");
        assertEquals(expected, actual);
    }

    /**
     * Whether a read inside the term, or inside the iteration's filter, counts as the rule's own reference to its
     * error field. The error field {@code Total} is read nowhere else, so the verdict is that reference's alone: a
     * term read never counts and refuses the {@code $} marker outright, while a filter read counts once marked, as
     * in any filter body (KF488). The iteration group is the rule's own reference: an error field inside it that no
     * term reads is admitted.
     */
    @Test
    void theTermAndTheFilterEachReferenceTheErrorField() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the error field read in the term", KernelLaws.verdictModel(POLICY, "/Policy", "../Total",
                "SumOfTerms(Claims*, [Claims/Amount] + [Total]) > 0"));
        actual.put("the error field read in the term, marked", KernelLaws.verdictModel(POLICY, "/Policy", "../Total",
                "SumOfTerms(Claims*, [Claims/Amount] + [$Total]) > 0"));
        actual.put("the error field read in the filter", KernelLaws.verdictModel(POLICY, "/Policy", "../Total",
                "SumOfTerms(Claims* Having [Claims/Amount] > [Total], [Claims/Amount]) > 0"));
        actual.put("the error field read in the filter, marked", KernelLaws.verdictModel(POLICY, "/Policy", "../Total",
                "SumOfTerms(Claims* Having [Claims/Amount] > [$Total], [Claims/Amount]) > 0"));
        actual.put("control: the error field read nowhere", KernelLaws.verdictModel(POLICY, "/Policy", "../Total",
                "SumOfTerms(Claims*, [Claims/Amount]) > 0"));
        actual.put("control: the error field read beside the sum", KernelLaws.verdictModel(POLICY, "/Policy",
                "../Total", "FieldFilled(Total) And SumOfTerms(Claims*, [Claims/Amount]) > 0"));
        actual.put("an error field inside the iteration group, read by no term", KernelLaws.verdictModel(POLICY, "/Policy",
                "../Claims/Status", "SumOfTerms(Claims*, [Claims/Amount]) > 0"));
        actual.put("an error field inside the iteration group, read beside the sum", KernelLaws.verdictModel(POLICY,
                "/Policy", "../Claims/Status", "FieldFilled(Claims/Status) And SumOfTerms(Claims/Parts*, [Claims/Parts/Qty]) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the error field read in the term", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("the error field read in the term, marked", "MVK_INVALID_OUTER_ITERATION");
        expected.put("the error field read in the filter", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("the error field read in the filter, marked", KernelLaws.ADMITTED);
        expected.put("control: the error field read nowhere", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("control: the error field read beside the sum", KernelLaws.ADMITTED);
        expected.put("an error field inside the iteration group, read by no term", KernelLaws.ADMITTED);
        expected.put("an error field inside the iteration group, read beside the sum", KernelLaws.ADMITTED);
        assertEquals(expected, actual);
    }

    /**
     * The arms of the term walk the grid above does not reach: a semantic index on the iteration entity, a level
     * the term iterates below a star it writes itself, and the error field outside the iteration group or around
     * a nested one. The error-field rows separate "the iteration group is the reference" from "a repeatable error
     * field is exempt", and show the group referenced is the one the entity NAMES, its lowest level.
     */
    @Test
    void theRemainingArmsOfTheIterationAndTermWalk() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a semantic index on the iteration entity",
                atPolicy("SumOfTerms(Claims* For \"C1\", [Claims/Amount]) > 0"));
        actual.put("an iterated level below a star the term writes", atPolicy(
                "SumOfTerms(Claims*/Parts*, [Claims/Parts/Qty] + Sum(Claims*/Parts/Price)) > 0"));
        actual.put("the error field in another repeatable group", KernelLaws.verdictModel(POLICY, "/Policy",
                "../Vehicles/Value", "SumOfTerms(Claims*, [Claims/Amount]) > 0"));
        actual.put("the error field in the upper level of a nested iteration", KernelLaws.verdictModel(POLICY,
                "/Policy", "../Claims/Status", "SumOfTerms(Claims*/Parts*, [Claims/Parts/Qty]) > 0"));
        actual.put("the error field in the lower level of a nested iteration", KernelLaws.verdictModel(POLICY,
                "/Policy", "../Claims/Parts/Price", "SumOfTerms(Claims*/Parts*, [Claims/Parts/Qty]) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a semantic index on the iteration entity", "MVK_SEMANTIC_INDEX_AND_WILDCARD");
        expected.put("an iterated level below a star the term writes", "MVK_NO_WILDCARD");
        expected.put("the error field in another repeatable group", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("the error field in the upper level of a nested iteration", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("the error field in the lower level of a nested iteration", KernelLaws.ADMITTED);
        assertEquals(expected, actual);
    }

    @Test
    void aComputationMayAssignATermSum() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a computation of a term sum", KernelLaws.computationVerdict(POLICY, new DraftComputation("/Policy",
                "TotalOfClaims", "../Total", null,
                List.of(new DraftComputation.Alternative(null, "SumOfTerms(Claims*, [Claims/Amount] * 2)")))));

        assertEquals(Map.of("a computation of a term sum", KernelLaws.ADMITTED), actual);
    }
}
