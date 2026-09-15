package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * KERNEL-FINDINGS §13 — the ORDER of the {@code .value} suffix's two gates, and what pre-empts both.
 *
 * <p>Each gate is measured on its own elsewhere: the suffix needs a reference the condition does not read
 * through a star alone ({@code PARAM_VALUE_WITH_STARREFERENCE}, {@link RuleMessageParameterLawsTest}) and a
 * field that is not declared {@code noValueValidation} ({@code INVALID_NO_VALUE_VALIDATION},
 * {@link RuleMessageAdmissionGateLawsTest}). Neither class says which answers when BOTH hold, and a checker
 * reproducing the pair has to pick one — so the row that carries this class is the fourth.
 *
 * <p>Its own fixture rather than a row bolted onto either of those, because the shape needs a
 * value-validation-free field INSIDE a repeatable group, and adding one to either shared model would move a
 * variable under every row already measured there. Own domain (retail ordering).
 */
class MessageValueSuffixGateOrderLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String GROUP = "/Order";
    private static final String ERROR_FIELD = "../Reference";

    private static final String ORDER = DocumentModelDraft.named("order-value-suffix").locale("en_US")
            .rootGroup("Order", o -> o
                    .stringField("Reference", f -> { })
                    .stringField("Notes", f -> f.noValueValidation().lineBreaks())
                    .group("Lines", l -> l.repeatable(9)
                            .stringField("Sku", f -> { })
                            .stringField("Comment", f -> f.noValueValidation().lineBreaks())))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(ORDER);

    /** Reads `Reference` alone, so a parameter naming anything else is outside the condition. */
    private static final String READS_REFERENCE = render(Dsl.fieldFilled(M.field("/Order/Reference")));

    /** …and the value-validation-free root field beside it, leaving its DECLARATION the only gate left. */
    private static final String READS_NOTES = render(Dsl.fieldFilled(M.field("/Order/Reference"))
            .and(Dsl.fieldFilled(M.field("/Order/Notes"))));

    /** Reads the row field through a STAR only — the operand shape the suffix's reference gate refuses. */
    private static final String READS_STARRED_SKU = render(Dsl.fieldFilled(M.field("/Order/Reference"))
            .and(Dsl.atLeastOneFieldFilled(M.flatten("/Order/Lines*/Sku"))));

    /** The same star, over the field that ALSO carries `noValueValidation` — where both gates hold. */
    private static final String READS_STARRED_COMMENT = render(Dsl.fieldFilled(M.field("/Order/Reference"))
            .and(Dsl.atLeastOneFieldFilled(M.flatten("/Order/Lines*/Comment"))));

    private static String render(Condition condition) {
        return new DslRenderer().render(condition);
    }

    private static String verdict(String condition, String message) {
        return KernelLaws.messageVerdict(ORDER, GROUP, ERROR_FIELD, condition, "en_US", message);
    }

    @Test
    void whichOfTheValueSuffixesTwoGatesAnswersWhenBothHold() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the suffix on an ordinary read field — the control",
                verdict(READS_REFERENCE, "see $Reference.value$"));
        actual.put("the PLAIN form on the value-validation-free field — the control",
                verdict(READS_NOTES, "see $Notes$"));
        actual.put("the suffix on a value-validation-free field", verdict(READS_NOTES, "see $Notes.value$"));
        actual.put("the suffix on a field the condition reads through a STAR",
                verdict(READS_STARRED_SKU, "see $Lines/Sku.value$"));
        actual.put("the suffix where BOTH gates hold",
                verdict(READS_STARRED_COMMENT, "see $Lines/Comment.value$"));
        actual.put("the suffix on a field the condition does not read at all",
                verdict(READS_REFERENCE, "see $Notes.value$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the suffix on an ordinary read field — the control", ADMITTED);
        expected.put("the PLAIN form on the value-validation-free field — the control", ADMITTED);
        expected.put("the suffix on a value-validation-free field", "INVALID_NO_VALUE_VALIDATION");
        expected.put("the suffix on a field the condition reads through a STAR", "PARAM_VALUE_WITH_STARREFERENCE");
        // THE ROW THIS CLASS EXISTS FOR. Both gates hold on one parameter, so a checker must know which A12
        // names — reporting the other would be the wrong class on a model it correctly refuses.
        expected.put("the suffix where BOTH gates hold", "PARAM_VALUE_WITH_STARREFERENCE");
        // …and MEMBERSHIP pre-empts them both, which is what keeps a suffix family from judging a parameter
        // whose field the condition never read.
        expected.put("the suffix on a field the condition does not read at all", "INVALID_FIELD");
        assertEquals(expected, actual, "which of the value suffix's two gates answers when both hold");
    }
}
