package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Which class A12 names for a condition that ENDS on an operator, when another operator stands right before it.
 *
 * <p>A single trailing operator leaves A12's parser out of input: {@code MVK_INCOMPLETE_INPUT}
 * ({@code KERNEL-FINDINGS KF300}). A second one in front of it is a token the parser meets while it still expects
 * an operand, so it stops there and never reaches the end, as {@code ConditionMultipleDefectLawsTest} measured
 * for {@code … And And}. These rows ask that of the other pairs, and ask the one shape where the second token
 * can START an operand: a {@code -} after a comparison begins a signed number, so the parser does run out —
 * and it answers {@code MVK_EXPECTED_TOKEN_NOT_FOUND}, the class of text ending where exactly one token may
 * follow, rather than {@code MVK_INCOMPLETE_INPUT}.
 *
 * <p>Every row is a string because the subject is malformed syntax, which no typed condition can carry.
 * Own domain (a claims policy).
 */
class DanglingOperatorPairLawsTest {

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Premium", n -> { }))
            .build().toJson();

    private static String onReference(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference", condition);
    }

    private static String onPremium(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Premium", condition);
    }

    @Test
    void aSecondOperatorBeforeTheEndIsTheTokenTheParserStopsAt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one trailing connective, the control", onReference("FieldFilled(Reference) And"));
        actual.put("And, then Or", onReference("FieldFilled(Reference) And Or"));
        actual.put("Or, then And", onReference("FieldFilled(Reference) Or And"));
        actual.put("three connectives", onReference("FieldFilled(Reference) And And And"));
        actual.put("a comment after the pair", onReference("FieldFilled(Reference) And And ;; later"));
        actual.put("the lower-case pair", onReference("FieldFilled(Reference) and and"));
        actual.put("one trailing comparison, the control", onPremium("[Premium] >"));
        actual.put("a comparison, then a connective", onPremium("[Premium] > And"));
        actual.put("a connective, then a comparison", onPremium("[Premium] > 1 And >"));
        actual.put("a comparison, then a multiplication", onPremium("[Premium] > *"));
        actual.put("a comparison, then a minus", onPremium("[Premium] > -"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one trailing connective, the control", "MVK_INCOMPLETE_INPUT");
        expected.put("And, then Or", "MVK_UNEXPECTED_TOKEN");
        expected.put("Or, then And", "MVK_UNEXPECTED_TOKEN");
        expected.put("three connectives", "MVK_UNEXPECTED_TOKEN");
        expected.put("a comment after the pair", "MVK_UNEXPECTED_TOKEN");
        expected.put("the lower-case pair", "MVK_UNEXPECTED_TOKEN");
        expected.put("one trailing comparison, the control", "MVK_INCOMPLETE_INPUT");
        expected.put("a comparison, then a connective", "MVK_UNEXPECTED_TOKEN");
        expected.put("a connective, then a comparison", "MVK_UNEXPECTED_TOKEN");
        expected.put("a comparison, then a multiplication", "MVK_UNEXPECTED_TOKEN");
        // A sign stands on a number and nothing else, so the parser runs out waiting for that one token:
        // the class of a missing closing bracket, not of a missing operand.
        expected.put("a comparison, then a minus", "MVK_EXPECTED_TOKEN_NOT_FOUND");
        assertEquals(expected, actual, "which class a text ending on two operators draws");
    }
}
