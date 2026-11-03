package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The kit-side reading of a {@code boolean} model-config member, against the table the Kernel gave
 * {@code rulekit.validate.laws.ShortNameFlagSpellingLawsTest} (kernel 30.8.1, 2026-09-12, KERNEL-FINDINGS KF289;
 * re-measured on kernel 31.1.1, 2026-11-02, where the empty, blank and {@code null} spellings turned UNREADABLE).
 *
 * <p><b>Every row is the Kernel's answer, not a design choice</b> (the padded and null-text rows are KF569's,
 * measured on both carriers), which is what makes this a conformance
 * lock rather than a formatting preference. Two families diverge from the {@code asBoolean} reading every
 * site here used until 2026-09-12, and both change what a consumer is told: a capitalized {@code "True"} is
 * ON to A12 and OFF to {@code asBoolean}, silently disabling the model-wide short-name tier so a bare
 * reference A12 resolves reads as undeclared; and a fractional number makes the model UNREADABLE where
 * {@code asBoolean} truncates and answers — affirmatively for {@code 1.5}.
 */
class ConfigFlagTest {

    private static final List<String> SPELLINGS = List.of(
            "true", "false", "\"true\"", "\"True\"", "\"TRUE\"", "\"false\"", "\"False\"", "\"FALSE\"",
            "\"yes\"", "\"1\"", "\"\"", "1", "2", "-1", "0", "-0", "0.5", "1.5", "{}", "[]", "null",
            "\" true\"", "\"\\ttrue\"", "\"\\u00A0true\"", "\" \"", "\"null\"", "\"NULL\"", "1.0", "1e0");

    @Test
    void everySpellingIsReadTheWayA12ReadsIt() {
        Map<String, ConfigFlag> actual = new LinkedHashMap<>();
        for (String spelling : SPELLINGS) {
            actual.put(spelling, ConfigFlag.ofJsonText(spelling));
        }
        actual.put("absent", ConfigFlag.ofJsonText(null));

        Map<String, ConfigFlag> expected = new LinkedHashMap<>();
        expected.put("true", ConfigFlag.ON);
        expected.put("false", ConfigFlag.OFF);
        expected.put("\"true\"", ConfigFlag.ON);
        expected.put("\"True\"", ConfigFlag.ON);
        expected.put("\"TRUE\"", ConfigFlag.ON);
        expected.put("\"false\"", ConfigFlag.OFF);
        expected.put("\"False\"", ConfigFlag.OFF);
        expected.put("\"FALSE\"", ConfigFlag.OFF);
        expected.put("\"yes\"", ConfigFlag.UNREADABLE);
        expected.put("\"1\"", ConfigFlag.UNREADABLE);
        expected.put("\"\"", ConfigFlag.UNREADABLE);
        expected.put("1", ConfigFlag.ON);
        expected.put("2", ConfigFlag.ON);
        expected.put("-1", ConfigFlag.ON);
        expected.put("0", ConfigFlag.OFF);
        // Measured on `StringType.noValueValidation` (KF569): `-0` reads as false.
        expected.put("-0", ConfigFlag.OFF);
        expected.put("0.5", ConfigFlag.UNREADABLE);
        expected.put("1.5", ConfigFlag.UNREADABLE);
        expected.put("{}", ConfigFlag.UNREADABLE);
        expected.put("[]", ConfigFlag.UNREADABLE);
        expected.put("null", ConfigFlag.UNREADABLE);
        // A string is trimmed as Java trims and decoded first (KF569); the empty, blank and `null` texts refuse
        // the reader on kernel 31.1.1, as does a JSON `null`.
        expected.put("\" true\"", ConfigFlag.ON);
        expected.put("\"\\ttrue\"", ConfigFlag.ON);
        expected.put("\"\\u00A0true\"", ConfigFlag.UNREADABLE);
        expected.put("\" \"", ConfigFlag.UNREADABLE);
        expected.put("\"null\"", ConfigFlag.UNREADABLE);
        expected.put("\"NULL\"", ConfigFlag.UNREADABLE);
        expected.put("1.0", ConfigFlag.UNREADABLE);
        expected.put("1e0", ConfigFlag.UNREADABLE);
        expected.put("absent", ConfigFlag.OFF);
        assertEquals(expected, actual, "how each spelling of a boolean model-config member is read");
    }

    /**
     * An integer wider than any machine type still answers, because the rule reads the literal's TEXT.
     *
     * <p>A reading that converted first would overflow into the unreadable branch for a model A12 loads —
     * unreachable from the table above, where every row fits an {@code int}.
     */
    @Test
    void anIntegerTooLargeForAnyWidthIsStillAnInteger() {
        assertEquals(ConfigFlag.ON, ConfigFlag.ofJsonText("9".repeat(26)));
        assertEquals(ConfigFlag.OFF, ConfigFlag.ofJsonText("0".repeat(26)));
    }

    /**
     * A member as the model reader parsed it keeps the kind of token written: A12 reads only an integer token
     * ({@code adapter.laws.ModelTextReadingDiffTest.aModelLevelBooleanMemberReadsOnlyAnIntegerToken}), while the
     * parsed decimal prints {@code 1e0} as {@code 1} and {@code 1.0e1} as {@code 10}, so judging the printed text
     * would read both as set for a model A12 does not load.
     */
    @Test
    void aParsedMemberIsReadByTheTokenWrittenNotItsPrintedValue() throws Exception {
        Map<String, ConfigFlag> actual = new LinkedHashMap<>();
        for (String token : List.of("1", "0", "-0", "1e0", "0e0", "1.0e1", "0.1e1", "1.0", "1e1", "-0.0")) {
            actual.put(token, ConfigFlag.ofMember(ModelReader.MAPPER.readTree("{\"m\":" + token + "}").path("m")));
        }
        Map<String, ConfigFlag> expected = new LinkedHashMap<>();
        expected.put("1", ConfigFlag.ON);
        expected.put("0", ConfigFlag.OFF);
        expected.put("-0", ConfigFlag.OFF);
        for (String token : List.of("1e0", "0e0", "1.0e1", "0.1e1", "1.0", "1e1", "-0.0")) {
            expected.put(token, ConfigFlag.UNREADABLE);
        }
        assertEquals(expected, actual);
    }
}
