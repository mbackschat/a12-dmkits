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
 * A12's condition and message-parameter lexers read digits and whitespace from ASCII alone (KF575).
 *
 * <p>Both grammars spell their sets out ({@code fragment DIGIT: '0'..'9'}, a five-character {@code WS}), while a
 * quoted Number in a Document converts any digit {@code java.math.BigDecimal} reads (KF564). The rows separate
 * the two: a digit A12 converts in a value is refused as a literal. Each grid moves one character on one
 * carrier, and its ASCII row is the control that shows the probe admits at all.
 */
class NonAsciiLexemeLawsTest {

    @Test
    void aConditionNumberLiteralIsAsciiDigitsOnly() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("ASCII 3 — the control", condition("[BaseFee] > 3"));
        actual.put("Arabic-Indic three", condition("[BaseFee] > ٣"));
        actual.put("fullwidth five", condition("[BaseFee] > ５"));
        actual.put("ASCII then Arabic-Indic", condition("[BaseFee] > 1٣"));
        actual.put("Arabic-Indic fraction digit", condition("[BaseFee] > 3.٥"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ASCII 3 — the control", KernelLaws.ADMITTED);
        expected.put("Arabic-Indic three", "MVK_LEXER_STANDARD_ERROR");
        expected.put("fullwidth five", "MVK_LEXER_STANDARD_ERROR");
        expected.put("ASCII then Arabic-Indic", "MVK_LEXER_STANDARD_ERROR");
        expected.put("Arabic-Indic fraction digit", "MVK_LEXER_STANDARD_ERROR");
        assertEquals(expected, actual, "which digits a condition literal admits");
    }

    @Test
    void aMessageParameterGapIsAsciiWhitespaceOnly() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("space — the control", message("fee $ BaseFee.value$"));
        actual.put("no-break space", message("fee $\u00A0BaseFee.value$"));
        actual.put("em space", message("fee $\u2003BaseFee.value$"));
        actual.put("vertical tab", message("fee $\u000BBaseFee.value$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("space — the control", KernelLaws.ADMITTED);
        expected.put("no-break space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("em space", "MVK_LEXER_STANDARD_ERROR");
        // Refused one gate earlier: the message text's own control-character check fires before the lexer.
        expected.put("vertical tab", "INVALID_CHARACTERS");
        assertEquals(expected, actual, "which whitespace a message parameter admits");
    }

    private static String condition(String condition) {
        return rule(condition, "x");
    }

    private static String message(String text) {
        return rule("[BaseFee] > 1", text);
    }

    private static String rule(String condition, String englishText) {
        return KernelLaws.verdictOf(ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription/Billing",
                "LexemeProbe", "../BaseFee", "PROBE", condition, "ERROR", List.of(en(englishText), de("y"))));
    }
}
