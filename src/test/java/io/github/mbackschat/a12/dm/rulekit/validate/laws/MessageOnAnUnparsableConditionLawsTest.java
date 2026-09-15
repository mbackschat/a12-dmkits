package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §13 — a message costs a rule whose CONDITION does not parse NOTHING, on six shapes.
 *
 * <p><b>Written to measure the opposite, and the absence is the more useful fact.</b> {@code RuleParser}
 * parses the condition a second time whenever any message text {@code contains("$")} and composes the
 * unprefixed {@code INVALID_CONDITION} when that parse yields nothing, so a rule with a broken condition and
 * a parameter in its message looked like it should draw an extra class. Measured on six unparsable
 * conditions — a trailing name, an unbalanced parenthesis, a bare name, an empty text, punctuation alone and
 * an unknown operator — the verdict is the condition's parse class and nothing more, whether the message
 * carries a parameter, an escaped dollar, or no dollar at all.
 *
 * <p><b>What that licenses in a checker.</b> Every message family in {@code :consistency} skips a
 * declaration whose DSL it could not read, and this grid is what says that skip under-reports nothing: A12
 * adds no message class there either. Without it the skip is a hope.
 *
 * <p><b>Recall limit.</b> Six authored shapes, not an enumeration of unparsable conditions — and the second
 * parse runs under {@code ExpandMode.EXPAND} with a swallowing reporter, so a shape that defeats it while
 * the first parse survives may exist. The route may also need the ExtMP/H settings this estate does not
 * author. So this is "not reachable from these six", never "unreachable".
 *
 * <p>Own domain (a claims policy).
 */
class MessageOnAnUnparsableConditionLawsTest {

    private static final String POLICY = DocumentModelDraft.named("claims-unparsable").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .stringField("Adjuster", f -> { }))
            .build().toJson();

    /** Two NAME tokens with no operator between them — a text A12's condition grammar cannot reduce. */
    private static final String UNPARSABLE = "FieldFilled(Reference) Reference";

    private static final String ADMITTED_CONDITION = "FieldFilled(Reference)";

    private static final String TOKEN = "MVK_UNEXPECTED_TOKEN";

    @Test
    void aMessageAddsNoClassWhereTheConditionDoesNotParse() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("a plain message — the control", verdict(UNPARSABLE, "see nothing"));
        verdicts.put("a message naming a parameter", verdict(UNPARSABLE, "see $Reference$"));
        verdicts.put("a message whose only dollars are an ESCAPE", verdict(UNPARSABLE, "cost 5$$"));
        verdicts.put("the same parameter on a condition that parses",
                verdict(ADMITTED_CONDITION, "see $Reference$"));
        verdicts.put("an unbalanced parenthesis", verdict("FieldFilled(", "see $Reference$"));
        verdicts.put("a bare name", verdict("Reference", "see $Reference$"));
        verdicts.put("an empty condition", verdict("", "see $Reference$"));
        verdicts.put("punctuation alone", verdict("((", "see $Reference$"));
        verdicts.put("an unknown operator", verdict("NoSuchOperator(Reference)", "see $Reference$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain message — the control", TOKEN);
        // The two rows the second parse would have shown up in: the parameter one, and the escaped-dollar
        // one that `RuleParser`'s own comment says it "falsely flags" as a parameter. Both agree with the
        // control, so the message text does not reach a verdict here at all.
        expected.put("a message naming a parameter", TOKEN);
        expected.put("a message whose only dollars are an ESCAPE", TOKEN);
        expected.put("the same parameter on a condition that parses", KernelLaws.ADMITTED);
        // Five further unparsable shapes, each with the parameter message: the class is the condition's in
        // every one. The empty text is the only row that draws more than one, and both of its extras are
        // about the condition being absent rather than about the message.
        expected.put("an unbalanced parenthesis", "MVK_INCOMPLETE_INPUT");
        expected.put("a bare name", TOKEN);
        expected.put("an empty condition", "MVK_CONDITION_MISSING,MVK_INCOMPLETE_INPUT,(no code: Rule with "
                + "id '/Policy/ProbeRule': The error condition is empty.)");
        expected.put("punctuation alone", "MVK_INCOMPLETE_INPUT");
        expected.put("an unknown operator", TOKEN);
        assertEquals(expected, verdicts, "a message adds no class where the condition does not parse");
    }

    private static String verdict(String condition, String messageText) {
        return KernelLaws.messageVerdictOnARefusedCondition(POLICY, "/Policy", "../Reference", condition,
                "en_US", messageText);
    }
}
