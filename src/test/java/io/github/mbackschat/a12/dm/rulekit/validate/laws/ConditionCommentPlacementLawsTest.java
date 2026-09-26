package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS [KF76], [KF77] — <b>a {@code ;;} comment is a token the grammar places, not trivia the lexer
 * skips.</b> The condition lexer sends only whitespace to its hidden channel; {@code Comment} reaches the
 * parser, and the grammar admits it only at the edges of a condition branch and of an operation's primary. So
 * a comment where no such edge is, inside a predicate's or a rounding call's argument list, is a token the
 * parser cannot use.
 *
 * <p>The refused rows are the discriminators: a reading that skips comments as trivia admits every row here.
 * The admitted rows are the controls that show the comment itself is well formed.
 *
 * <p>Concrete syntax is the subject, so the rows are condition text; no typed node carries a comment's
 * position. Own domain (a motor policy).
 */
class ConditionCommentPlacementLawsTest {

    private static final String POLICY = DocumentModelDraft.named("comment-policy").locale("en_US")
            .rootGroup("Policy", p -> p.field("Code", FieldKind.STRING).field("Premium", FieldKind.NUMBER))
            .build().toJson();

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Code", condition);
    }

    @Test
    void aCommentStandsOnlyAtABranchOrAnOperandEdge() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("before a branch", verdict("FieldFilled(Code) And ;;c\n FieldFilled(Premium)"));
        actual.put("after a branch", verdict("FieldFilled(Code) ;;c\n And FieldFilled(Premium)"));
        actual.put("before an arithmetic operand", verdict("FieldFilled(Code) And [Premium] + ;;c\n 1 > 0"));
        actual.put("inside a predicate's argument list", verdict("FieldFilled(;;c\n Code)"));
        actual.put("before a place count", verdict("FieldFilled(Code) And RoundUpValue(Premium, ;;c\n 0) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("before a branch", KernelLaws.ADMITTED);
        expected.put("after a branch", KernelLaws.ADMITTED);
        expected.put("before an arithmetic operand", KernelLaws.ADMITTED);
        expected.put("inside a predicate's argument list", "MVK_UNEXPECTED_TOKEN");
        expected.put("before a place count", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "where a comment may stand");
    }
}
