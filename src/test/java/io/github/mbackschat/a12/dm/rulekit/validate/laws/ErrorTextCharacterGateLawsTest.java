package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * Which characters A12's two error-text gates refuse, on a rule's message and on a field type's own message.
 *
 * <p>Both carriers call the same two tests (source-read, kernel {@code 30.8.1}): {@code
 * FehlertextUtil.checkLineSeparators}, which refuses LF, CR, U+2028 and U+2029, and {@code
 * checkControlCharacters}, which refuses {@code \p{C}}. The rule's checker asks them first; the field's asks the
 * UTF-16 encoding before them, so a lone surrogate is a different class on each carrier. Every {@code \p{C}}
 * prediction was executed on the pinned JDK 21.0.12 before it was put to the kernel (2026-09-26), since the
 * category of an unassigned code point belongs to that JDK's Unicode version.
 */
class ErrorTextCharacterGateLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    private static final String READS = "FieldFilled(Reference) And FieldFilled(Code)";

    /** A model whose {@code Code} field carries a pattern and {@code fieldMessage} as that pattern's message. */
    private static String model(String fieldMessage) {
        return DocumentModelDraft.named("claims-error-texts").locale("en_US")
                .rootGroup("Claim", c -> c
                        .stringField("Reference", f -> { })
                        .stringField("Code", f -> f.pattern("[A-Z]+", fieldMessage)))
                .build().toJson();
    }

    private static String verdict(String fieldMessage, String ruleMessage) {
        List<io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic> diagnostics = VALIDATOR.check(
                model(fieldMessage), new DraftRule("/Claim", "ProbeRule", "../Reference", READS, "PROBE", "ERROR",
                        List.of(new LocalizedText("en_US", ruleMessage)))).diagnostics();
        return diagnostics.isEmpty() ? KernelLaws.ADMITTED : diagnostics.stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(","));
    }

    private static final String LINE_BREAKS = "Only values without line breaks are allowed.";
    private static final String UNSUPPORTED = "The field contains one or several unsupported signs.";

    private static String codeless(String kind, String name, String sentence) {
        return "(no code: " + kind + " with path '/Claim/" + name + "': " + sentence + ")";
    }

    private static String onTheRule(String text) {
        return verdict("capitals only", text);
    }

    private static String onTheField(String text) {
        return verdict(text, "see the reference");
    }

    @Test
    void whichCharactersEachGateRefusesOnEachCarrier() {
        Map<String, String> characters = new LinkedHashMap<>();
        characters.put("plain", "plain");
        characters.put("U+2028 LINE SEPARATOR", "a\u2028b");
        characters.put("U+2029 PARAGRAPH SEPARATOR", "a\u2029b");
        characters.put("a line feed", "a\nb");
        characters.put("a tab", "a\u0009b");
        characters.put("U+0085 NEXT LINE", "a\u0085b");
        characters.put("U+200B ZERO WIDTH SPACE", "a\u200Bb");
        characters.put("U+00AD SOFT HYPHEN", "a\u00ADb");
        characters.put("U+FEFF ZERO WIDTH NO-BREAK SPACE", "a\uFEFFb");
        characters.put("U+200E LEFT-TO-RIGHT MARK", "a\u200Eb");
        characters.put("U+E000 private use", "a\uE000b");
        characters.put("U+0378 unassigned", "a\u0378b");
        characters.put("U+00A0 NO-BREAK SPACE", "a\u00A0b");
        characters.put("a lone high surrogate", "a\uD83Db");
        characters.put("a supplementary private-use character", "a\uDB80\uDC00b");
        characters.put("a supplementary character outside \\p{C}", "a\uD83D\uDE00b");
        characters.put("U+E0001 LANGUAGE TAG, a supplementary format character", "a\uDB40\uDC01b");
        characters.put("U+2028 beside a tab", "a\u0009b\u2028c");

        Map<String, String> actual = new LinkedHashMap<>();
        characters.forEach((row, text) -> {
            actual.put("rule: " + row, onTheRule(text));
            actual.put("field: " + row, onTheField(text));
        });
        Map<String, String> expected = new LinkedHashMap<>();
        characters.keySet().forEach(row -> {
            expected.put("rule: " + row, "INVALID_CHARACTERS");
            expected.put("field: " + row, "INVALID_CHARACTERS");
        });
        for (String admitted : List.of("plain", "U+00A0 NO-BREAK SPACE")) {
            expected.put("rule: " + admitted, KernelLaws.ADMITTED);
            expected.put("field: " + admitted, KernelLaws.ADMITTED);
        }
        // The line-break test is the first asked and counts the two Unicode separators, which are not \p{C};
        // only a line FEED also draws the metamodel's own codeless sentence.
        for (String separator : List.of("U+2028 LINE SEPARATOR", "U+2029 PARAGRAPH SEPARATOR", "U+2028 beside a tab")) {
            expected.put("rule: " + separator, "INVALID_LINEBREAK");
            expected.put("field: " + separator, "INVALID_LINEBREAK");
        }
        expected.put("rule: a line feed", codeless("Rule", "ProbeRule", LINE_BREAKS) + ",INVALID_LINEBREAK");
        expected.put("field: a line feed", "INVALID_LINEBREAK," + codeless("Field", "Code", LINE_BREAKS));
        // Beside a surrogate the charset refusal of KERNEL-FINDINGS KF489 stands too. On the field the
        // encoding is asked first, and UTF-16 cannot encode a lone surrogate.
        expected.put("rule: a lone high surrogate", codeless("Rule", "ProbeRule", UNSUPPORTED) + ",INVALID_CHARACTERS");
        expected.put("field: a lone high surrogate", "WRONG_ENCODING," + codeless("Field", "Code", UNSUPPORTED));
        expected.put("rule: a supplementary private-use character",
                codeless("Rule", "ProbeRule", UNSUPPORTED) + ",INVALID_CHARACTERS");
        expected.put("field: a supplementary private-use character",
                "INVALID_CHARACTERS," + codeless("Field", "Code", UNSUPPORTED));
        // \p{C} is asked of a CODE POINT: the pair of a supplementary character outside it is not two
        // surrogates to that test, so only the charset refusal stands.
        expected.put("rule: a supplementary character outside \\p{C}", codeless("Rule", "ProbeRule", UNSUPPORTED));
        expected.put("field: a supplementary character outside \\p{C}", codeless("Field", "Code", UNSUPPORTED));
        expected.put("rule: U+E0001 LANGUAGE TAG, a supplementary format character",
                codeless("Rule", "ProbeRule", UNSUPPORTED) + ",INVALID_CHARACTERS");
        expected.put("field: U+E0001 LANGUAGE TAG, a supplementary format character",
                "INVALID_CHARACTERS," + codeless("Field", "Code", UNSUPPORTED));
        assertEquals(expected, actual, "the two error-text gates, on both carriers");
    }

    /**
     * The characters the ISO blocks miss withhold the parameter classes too, since the rule's checker stops at
     * its first failing gate. KERNEL-FINDINGS KF343 measured that for a tab; these rows carry it over to the
     * separators and format characters, which a checker keyed on the old set would read straight past.
     */
    @Test
    void theWiderSetWithholdsTheParameterClassesToo() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an unknown field alone — the control", onTheRule("see $NoSuchField$"));
        actual.put("an unknown field beside a format character", onTheRule("see $NoSuchField$ a\u200Bb"));
        actual.put("an unknown field beside a line separator", onTheRule("see $NoSuchField$ a\u2028b"));
        actual.put("an unclosed parameter beside a format character", onTheRule("see $Reference a\u200Bb"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an unknown field alone — the control", "INVALID_ENTITY");
        expected.put("an unknown field beside a format character", "INVALID_CHARACTERS");
        expected.put("an unknown field beside a line separator", "INVALID_LINEBREAK");
        expected.put("an unclosed parameter beside a format character", "INVALID_CHARACTERS");
        assertEquals(expected, actual, "the character gates withhold the parameter classes");
    }
}
