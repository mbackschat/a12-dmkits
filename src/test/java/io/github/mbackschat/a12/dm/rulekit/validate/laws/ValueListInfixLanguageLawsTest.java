package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Which spellings of the two value-list infixes A12 parses, in each condition language.
 *
 * <p>The grammar's {@code inOp: 'In'|'IN'|'in'} and {@code toOp: 'Zu'|'ZU'|'zu'} are TOKEN TYPES, and the
 * lexer assigns them per language from {@code LexerTerminals_en}/{@code _de} ({@code setTypeInLexer}). The
 * English table carries {@code In} and maps {@code To} onto the {@code Zu} tokens; the German one carries
 * {@code In} and {@code Zu} and no {@code To}. Read from source that predicts: {@code In} parses in both
 * languages, and the other language's {@code Zu}/{@code To} is an ordinary name, so the entity before it is
 * followed by a token the grammar cannot use. This class is the measurement; the prediction is not.
 *
 * <p>The native rows are the positive control: they draw {@code MVK_TABLE_ENUM_EXPECTED}, a class raised
 * only once the production has closed, so the refusal of the foreign rows is the parse and not the operand.
 * The two languages are the same model with only the condition language changed, so a row moves one
 * variable.
 */
class ValueListInfixLanguageLawsTest {

    private static final String POLICY_EN = DocumentModelDraft.named("infix-policy").locale("en_US")
            .rootGroup("Policy", p -> p.field("Tier", FieldKind.STRING).field("Premium", FieldKind.STRING))
            .build().toJson();

    private static final String POLICY_DE = ModelDocument.read(POLICY_EN).setConditionLanguage("de_DE").toJson();

    private static final List<String> PARSED_TO_THE_OPERAND_CHECK = List.of("MVK_TABLE_ENUM_EXPECTED");
    private static final List<String> REFUSED_AT_THE_PARSER = List.of("MVK_UNEXPECTED_TOKEN");

    @Test
    void inParsesInBothLanguages() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        for (String spelling : List.of("In", "IN", "in")) {
            actual.put("EN " + spelling, codes(POLICY_EN, "NoFieldValueIncludedInValueList(Tier " + spelling + " Premium)"));
            actual.put("DE " + spelling, codes(POLICY_DE, "KeinFeldWertInWerteListe(Tier " + spelling + " Premium)"));
        }
        Map<String, List<String>> expected = new LinkedHashMap<>();
        actual.keySet().forEach(row -> expected.put(row, List.of()));
        assertEquals(expected, actual, "In in both condition languages");
    }

    @Test
    void theToInfixParsesOnlyInTheLanguageThatSpellsIt() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        Map<String, List<String>> expected = new LinkedHashMap<>();
        for (String spelling : List.of("To", "TO", "to")) {
            put(actual, expected, "EN " + spelling, codes(POLICY_EN, notConsistent("ValueNotConsistent", spelling)),
                    PARSED_TO_THE_OPERAND_CHECK);
            put(actual, expected, "DE " + spelling, codes(POLICY_DE, notConsistent("WertNichtKonsistent", spelling)),
                    REFUSED_AT_THE_PARSER);
        }
        for (String spelling : List.of("Zu", "ZU", "zu")) {
            put(actual, expected, "DE " + spelling, codes(POLICY_DE, notConsistent("WertNichtKonsistent", spelling)),
                    PARSED_TO_THE_OPERAND_CHECK);
            put(actual, expected, "EN " + spelling, codes(POLICY_EN, notConsistent("ValueNotConsistent", spelling)),
                    REFUSED_AT_THE_PARSER);
        }
        assertEquals(expected, actual, "each language's own To/Zu parses, the other's is a stray name");
    }

    private static String notConsistent(String operator, String spelling) {
        return operator + "(Tier " + spelling + " Premium, \"gold\")";
    }

    private static void put(Map<String, List<String>> actual, Map<String, List<String>> expected, String row,
                            List<String> observed, List<String> predicted) {
        actual.put(row, observed);
        expected.put(row, predicted);
    }

    private static List<String> codes(String model, String condition) {
        return new RuleValidator().check(model, new DraftRule("/Policy", "InfixRule", "../Tier", condition, "PROBE",
                        "ERROR"))
                .diagnostics().stream().flatMap(d -> d.code().stream()).toList();
    }
}
