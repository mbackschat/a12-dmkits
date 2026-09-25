package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * Which infix arithmetic symbols A12 admits between two operands of a condition.
 *
 * <p><b>Why this grid exists: a mutation check found a parser widening no population reached.</b> The
 * checker's parser admits five infix arithmetic symbols, and removing {@code /} from that set left every
 * test in the estate green — the catalog corpus exercises {@code + - *} and nothing carries a division or an
 * exponent, so two of the five rested on a reading of the grammar rather than on a measurement. A widening
 * is the safe direction for this checker, but an unmeasured one is still a guess, and a guess nothing
 * exercises is indistinguishable from dead code.
 *
 * <p>The rows are deliberately one symbol apart on one carrier: the same two NUMBER fields, the same
 * comparison, the same fixture, so the only variable is the symbol. {@code %} is the liveness control — it
 * shows the probe can still produce a rejection, without which a grid of admissions cannot tell "A12 admits
 * these five" from "this probe admits everything".
 */
class ConditionArithmeticSymbolLawsTest {

    @Test
    void whichInfixArithmeticSymbolsA12AdmitsBetweenTwoOperands() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("no arithmetic at all — the control", verdict("[BaseFee] > 1"));
        actual.put("plus", verdict("[BaseFee] + [EffectiveFee] > 1"));
        actual.put("minus", verdict("[BaseFee] - [EffectiveFee] > 1"));
        actual.put("times", verdict("[BaseFee] * [EffectiveFee] > 1"));
        actual.put("divided by", verdict("[BaseFee] / [EffectiveFee] > 1"));
        actual.put("to the power of a field", verdict("[BaseFee] ^ [EffectiveFee] > 1"));
        actual.put("to the power of a literal", verdict("[BaseFee] ^ 2 > 1"));
        actual.put("percent — the liveness control", verdict("[BaseFee] % [EffectiveFee] > 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("no arithmetic at all — the control", KernelLaws.ADMITTED);
        expected.put("plus", KernelLaws.ADMITTED);
        expected.put("minus", KernelLaws.ADMITTED);
        expected.put("times", KernelLaws.ADMITTED);
        expected.put("divided by", KernelLaws.ADMITTED);
        // A SEMANTIC code, and that is what makes this row evidence FOR the parser widening rather than
        // against it: `^` reached the check that the exponent be a number, so the text parsed. The literal
        // row below is what separates "A12 refuses `^`" from "A12 refuses a FIELD as the exponent".
        expected.put("to the power of a field", "MVK_INVALID_NUMBER_FOR_EXP");
        expected.put("to the power of a literal", KernelLaws.ADMITTED);
        expected.put("percent — the liveness control", "MVK_LEXER_STANDARD_ERROR");
        assertEquals(expected, actual, "which infix arithmetic symbols A12 admits");
    }

    /**
     * A leading minus signs a number literal and nothing else. The grammar spells a signed constant
     * ({@code commonKonstante : minus='-'? ZiffernFolge …}) and has no unary minus, so a sign ahead of a
     * reference, a braced operation, a call or a second sign is a token the parser cannot use. The
     * subtracted literal is the row that keeps the sign apart from the infix minus beside it.
     */
    @Test
    void aSignStandsOnlyOnANumberLiteral() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a signed literal — the control", verdict("[BaseFee] > -1"));
        actual.put("a signed literal subtracted", verdict("[BaseFee] - -1 > 1"));
        actual.put("a signed reference", verdict("-[BaseFee] < 0"));
        actual.put("a signed reference on the right", verdict("[BaseFee] > -[EffectiveFee]"));
        actual.put("a signed braced operation", verdict("[BaseFee] > -{[EffectiveFee] + 1}"));
        actual.put("a signed call", verdict("[BaseFee] > -Abs([EffectiveFee])"));
        actual.put("a sign twice", verdict("[BaseFee] > --1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a signed literal — the control", KernelLaws.ADMITTED);
        expected.put("a signed literal subtracted", KernelLaws.ADMITTED);
        expected.put("a signed reference", "MVK_UNEXPECTED_TOKEN");
        expected.put("a signed reference on the right", "MVK_UNEXPECTED_TOKEN");
        expected.put("a signed braced operation", "MVK_UNEXPECTED_TOKEN");
        expected.put("a signed call", "MVK_UNEXPECTED_TOKEN");
        expected.put("a sign twice", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "what a leading minus may sign");
    }

    private static String verdict(String condition) {
        return KernelLaws.verdictOf(ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription/Billing",
                "ArithmeticProbe", "../BaseFee", "PROBE", condition, "ERROR", List.of(en("x"), de("y"))));
    }
}
