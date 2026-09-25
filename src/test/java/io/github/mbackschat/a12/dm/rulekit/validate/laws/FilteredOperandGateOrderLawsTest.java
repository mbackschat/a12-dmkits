package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — the complete gate chain A12 runs over a `Having` clause's OWN operand.
 *
 * <p>{@code CheckEntitySpecImpl.checkFilterCondition} carries the whole chain in source order, which is why
 * this is one grid rather than four containment assertions spread across files: the operand must be a field,
 * then must be starred, then must carry no semantic index, and only then is the filter BODY judged. Three of
 * those four classes have no measurement in this estate.
 *
 * <p>Own domain (insurance).
 */
class FilteredOperandGateOrderLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-filter").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .group("Insured", i -> i.stringField("InsuredName", f -> { }))
                    .group("Claims", g -> g.repeatable(20).indexField("ClaimRef")
                            .stringField("ClaimRef", f -> { })
                            .numberField("Amount", f -> f.fractionDigits(2))))
            .build().toJson();

    /** One probe, with the error field held constant so every row differs in the filtered operand alone. */
    private static String filtered(String probe) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference",
                "FieldFilled(Reference) And " + probe);
    }

    @Test
    void whichGateAnswersOnAFilteredOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a starred field operand — the control",
                filtered("Sum(Claims*/Amount Having [Claims/ClaimRef] == \"X\") > 0"));
        actual.put("an UNSTARRED field operand",
                filtered("Sum(Claims/Amount Having [Claims/ClaimRef] == \"X\") > 0"));
        actual.put("a starred GROUP operand",
                filtered("NumberOfFilledGroups(Claims* Having [Claims/ClaimRef] == \"X\") > 0"));
        actual.put("an operand carrying a SEMANTIC INDEX",
                filtered("Sum(Claims*/Amount For Reference Having [Claims/ClaimRef] == \"X\") > 0"));
        actual.put("a semantic index with NO star",
                filtered("Sum(Claims/Amount For Reference Having [Claims/ClaimRef] == \"X\") > 0"));
        actual.put("the indexed operand with NO filter — the control for whose filter it is",
                filtered("Sum(Claims*/Amount For Reference) > 0"));
        actual.put("a GROUP operand with no star either",
                filtered("NumberOfFilledGroups(Claims Having [Claims/ClaimRef] == \"X\") > 0"));
        actual.put("BOTH defects at once — a group operand carrying a semantic index",
                filtered("NumberOfFilledGroups(Claims* For Reference Having [Claims/ClaimRef] == \"X\") > 0"));
        actual.put("a disallowed construct in the filter BODY",
                filtered("Sum(Claims*/Amount Having RepetitionNotUnique(Claims/ClaimRef)) > 0"));
        actual.put("a disallowed construct BESIDE an in-scope conjunct",
                filtered("Sum(Claims*/Amount Having ([Claims/ClaimRef] == \"X\" "
                        + "And RepetitionNotUnique(Claims/ClaimRef))) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a starred field operand — the control", KernelLaws.ADMITTED);
        // The ITERATION gate outruns `checkHasWildCards`, so a plainly unstarred filtered field is NOT
        // `MVK_WILDCARDS_REQUIRED` — which is what the code's name predicts and the measurement denies.
        expected.put("an UNSTARRED field operand", "MVK_NO_WILDCARD");
        // `checkIsField` runs first in the chain and the star is not a variable for it: both rows agree.
        expected.put("a starred GROUP operand", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a GROUP operand with no star either", "MVK_NO_GROUPS_ALLOWED");
        // Both draw the class `checkHasWildCards` raises — for the filtered entity the control below
        // identifies as the key, not the indexed field.
        expected.put("an operand carrying a SEMANTIC INDEX", "MVK_WILDCARDS_REQUIRED");
        expected.put("a semantic index with NO star", "MVK_WILDCARDS_REQUIRED");
        // What makes the indexed rows above a statement about WHOSE filter it is. Without the filter the
        // index draws its own class, raised once the key is read. A filter belonging to the value would be
        // read only after that key, so it could not move the verdict; the filtered rows answering the
        // filter's class instead means the key took the `Having` — the grammar's key is an `entitySpec`,
        // which takes one — and the filtered entity is the unstarred key `Reference`.
        expected.put("the indexed operand with NO filter — the control for whose filter it is",
                "MVK_SEMANTIC_INDEX_AND_WILDCARD");
        // Predicted from source order as the kind class, `checkIsField` preceding `checkHasWildCards`. The
        // prediction took the GROUP for the filtered entity; the filtered entity is the key `Reference`, a
        // field, so the written order holds and the wildcard class is its answer.
        expected.put("BOTH defects at once — a group operand carrying a semantic index", "MVK_WILDCARDS_REQUIRED");
        expected.put("a disallowed construct in the filter BODY", "MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION");
        // The conjunct only helps the iteration gate before it, so the construct class survives it — the row
        // that stops a checker from transferring the sole-construct measurement to the conjoined shape
        // silently, since the two differ in what the gate BEFORE this one sees.
        expected.put("a disallowed construct BESIDE an in-scope conjunct",
                "MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION");

        assertEquals(expected, actual);
    }

    /**
     * Whose filter a `Having` written after a semantic-index key is. The grammar's key is an {@code entitySpec}
     * or a string literal, and only the first takes a filter of its own, so each key kind is its own row.
     */
    @Test
    void whoseFilterAHavingAfterAnIndexKeyIs() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a string key, starred operand",
                filtered("Sum(Claims*/Amount For \"X\" Having [Claims/ClaimRef] == \"X\") > 0"));
        actual.put("a string key, unstarred operand",
                filtered("Sum(Claims/Amount For \"X\" Having [Claims/ClaimRef] == \"X\") > 0"));
        actual.put("a string key, unstarred operand, no filter — its control",
                filtered("Sum(Claims/Amount For \"X\") > 0"));
        actual.put("a group key",
                filtered("Sum(Claims*/Amount For Insured Having [Insured/InsuredName] == \"X\") > 0"));
        actual.put("a starred key",
                filtered("Sum(Claims*/Amount For Claims*/ClaimRef Having [Claims/ClaimRef] == \"X\") > 0"));
        actual.put("a starred key, no filter — its control",
                filtered("Sum(Claims*/Amount For Claims*/ClaimRef) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        // A string key takes no filter, so the operand does: starred, the index gate is raised at the key,
        // ahead of the filter; unstarred, the filter's chain refuses it — and without the filter the same
        // operand reaches the aggregate's arity gate instead, so it is the filter that answers.
        expected.put("a string key, starred operand", "MVK_SEMANTIC_INDEX_AND_WILDCARD");
        expected.put("a string key, unstarred operand", "MVK_WILDCARDS_REQUIRED");
        expected.put("a string key, unstarred operand, no filter — its control", "MVK_PARAMSIZE_INVALIDN");
        // An entity key takes it: a group key fails the chain's kind gate, and a starred key passes the
        // chain, which leaves the index gate on the starred key to answer, as it does unfiltered.
        expected.put("a group key", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a starred key", "MVK_SEMANTIC_INDEX_WITH_WILDCARD");
        expected.put("a starred key, no filter — its control", "MVK_SEMANTIC_INDEX_WITH_WILDCARD");
        assertEquals(expected, actual, "whose filter a Having after an index key is");
    }
}
