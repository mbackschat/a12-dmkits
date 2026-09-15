package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * KERNEL-FINDINGS §7 — the four "value" laws of a string type's HINT LIST, on the carrier they are about.
 *
 * <p>[MVK-LEDGER] carried {@code MVK_VALUE_INVALID}, {@code MVK_VALUES_DUPLICATE},
 * {@code MVK_VALUE_TOO_LONG} and {@code MVK_VALUE_TOO_SHORT} as {@code n/a-structural}, each citing
 * [KF321](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf321) — which measured an enum VALUE TOKEN: repeated, empty, spaced, 300
 * characters long. Every row of that measurement is right and none of them is about these four codes, whose
 * own rows already named the carrier: <b>a hint list</b>. Measuring a law on the carrier next to the one it
 * names is the scope transfer that needs no new branch and so prompts no question, and here it closed four
 * rows as unreachable while the carrier had never been asked.
 *
 * <p><b>Three of the four fire, and the fourth splits off with a named predicate.</b> A repeated hint value,
 * one past {@code maxLength} and one below {@code minLength} each draw their class, so three ledger rows were
 * closed as unreachable while nothing had asked the carrier. {@code MVK_VALUE_INVALID} is the exception:
 * {@code CheckConfiguration} registers {@code invalidValueInHintList} as {@code CheckHintList}, which resolves
 * the SIBLING {@code pattern} of the {@code StringType} above the value and refuses a hint value the pattern
 * does not match — and that arm stays silent on a stored hint list under three attacked spellings. So the row
 * keeps its disposition and gains a predicate: what is unreachable is a specific, readable check, not an
 * unknown one. Why the arm is silent is NOT established here; the metamodel path the condition computes
 * ({@code …/StringType/HintLists/Values/value}, with the pattern's id derived by dropping two index levels)
 * is where a mechanism would have to be measured, and this grid does not measure it.
 *
 * <p>Own domain (the shared order fixture), on {@code /Order/CustomerName} — a plain root field no other
 * declaration names, so a verdict here is about the type and not about a role the field plays.
 */
class HintListValueLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A pattern with the error text A12 requires beside it, so no row measures that law instead. */
    private static final String PATTERN =
            "\"pattern\":\"[A-Z]+\",\"errorMessage\":[{\"locale\":\"en_US\",\"text\":\"letters only\"}]";

    /** The order fixture with {@code CustomerName} retyped as the string type [body] spells. */
    private static String verdict(String body) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ((ObjectNode) element.path("Field")).set("fieldType", MAPPER.readTree(
                            "{\"type\":\"StringType\",\"StringType\":{" + body + "}}"));
                    return KernelLaws.verdictOf(root.toString());
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the body must parse", unreadable);
        }
    }

    private static String hints(String locale, String... values) {
        return "\"hintList\":[{\"locale\":\"" + locale + "\",\"values\":[\""
                + String.join("\",\"", values) + "\"]}]";
    }

    @Test
    void whichHintValueLawsAStoredStringTypeReaches() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a hint list with no other parameter — the control", verdict(hints("en_US", "AB", "cd")));
        actual.put("a pattern with a hint value that MATCHES it",
                verdict(PATTERN + "," + hints("en_US", "AB")));
        actual.put("a pattern with a hint value that does not",
                verdict(PATTERN + "," + hints("en_US", "ab")));
        actual.put("the same hint value twice", verdict(hints("en_US", "AB", "AB")));
        actual.put("a hint value longer than the maximum",
                verdict("\"maxLength\":3," + hints("en_US", "ABCD")));
        actual.put("the same value at the maximum — the control",
                verdict("\"maxLength\":4," + hints("en_US", "ABCD")));
        actual.put("a hint value shorter than the minimum",
                verdict("\"minLength\":3," + hints("en_US", "AB")));
        actual.put("the same value at the minimum — the control",
                verdict("\"minLength\":2," + hints("en_US", "AB")));
        actual.put("an EMPTY hint value with no bound declared", verdict(hints("en_US", "")));
        actual.put("a hint value for an UNDECLARED locale that breaks the pattern",
                verdict(PATTERN + "," + hints("de_DE", "ab")));
        actual.put("a pattern with an EMPTY hint value, which `[A-Z]+` also refuses",
                verdict(PATTERN + "," + hints("en_US", "")));
        actual.put("a pattern with three hint values, one of them bad",
                verdict(PATTERN + "," + hints("en_US", "AB", "cd", "EF")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a hint list with no other parameter — the control", KernelLaws.ADMITTED);
        expected.put("a pattern with a hint value that MATCHES it", KernelLaws.ADMITTED);
        // The pattern arm of the custom condition does NOT fire on a stored hint list, three spellings
        // attacked: a lower-case value, an empty one the same pattern refuses, and one bad value among
        // three good. So `MVK_VALUE_INVALID` has a named predicate and no reachable carrier here.
        expected.put("a pattern with a hint value that does not", KernelLaws.ADMITTED);
        expected.put("the same hint value twice", "MVK_VALUES_DUPLICATE");
        expected.put("a hint value longer than the maximum", "MVK_VALUE_TOO_LONG");
        expected.put("the same value at the maximum — the control", KernelLaws.ADMITTED);
        expected.put("a hint value shorter than the minimum", "MVK_VALUE_TOO_SHORT");
        expected.put("the same value at the minimum — the control", KernelLaws.ADMITTED);
        expected.put("an EMPTY hint value with no bound declared", KernelLaws.ADMITTED);
        expected.put("a hint value for an UNDECLARED locale that breaks the pattern", KernelLaws.ADMITTED);
        expected.put("a pattern with an EMPTY hint value, which `[A-Z]+` also refuses", KernelLaws.ADMITTED);
        expected.put("a pattern with three hint values, one of them bad", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which hint-value laws a stored string type reaches");
    }

    /**
     * How MANY findings the three live laws raise, whose locale they judge, and what the sentinel does.
     *
     * <p>The grid above reports DISTINCT classes, so it cannot see multiplicity — and every one of these is a
     * decision a checker has to encode rather than infer: report per offending value or per list, judge the
     * locales the model declares or every entry present, and read {@code -1} as a bound or as A12's "unset".
     */
    @Test
    void howManyFindingsAndWhichLocaleTheLengthLawsJudge() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("TWO values past the maximum", raised("\"maxLength\":3," + hints("en_US", "ABCD", "EFGH")));
        actual.put("one value past the maximum and a duplicate pair",
                raised("\"maxLength\":3," + hints("en_US", "ABCD", "EF", "EF")));
        actual.put("the same value three times", raised(hints("en_US", "AB", "AB", "AB")));
        actual.put("a long value for an UNDECLARED locale",
                raised("\"maxLength\":3," + hints("de_DE", "ABCD")));
        actual.put("a duplicate pair for an UNDECLARED locale", raised(hints("de_DE", "AB", "AB")));
        actual.put("the same value once in EACH locale entry",
                raised("\"hintList\":[{\"locale\":\"en_US\",\"values\":[\"AB\"]},"
                        + "{\"locale\":\"de_DE\",\"values\":[\"AB\"]}]"));
        actual.put("a long value with the maximum at the `-1` sentinel",
                raised("\"maxLength\":-1," + hints("en_US", "ABCD")));
        actual.put("TWO distinct values each duplicated in one list",
                raised(hints("en_US", "AB", "AB", "CD", "CD")));
        actual.put("a long value beside `noValueValidation`, which refuses the list itself",
                raised("\"noValueValidation\":true,\"lineBreaksPermitted\":true,\"maxLength\":3,"
                        + hints("en_US", "ABCD")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("TWO values past the maximum", "MVK_VALUE_TOO_LONG x2");
        expected.put("one value past the maximum and a duplicate pair",
                "MVK_VALUE_TOO_LONG x1,MVK_VALUES_DUPLICATE x1");
        expected.put("the same value three times", "MVK_VALUES_DUPLICATE x1");
        // BOTH laws judge only the locales the model DECLARES, so an entry for another locale is not
        // examined at all — the same boundary the enum display-value gate has. Predicted the other way and
        // measured: a checker judging every entry present would refuse two models A12 takes.
        expected.put("a long value for an UNDECLARED locale", KernelLaws.ADMITTED);
        expected.put("a duplicate pair for an UNDECLARED locale", KernelLaws.ADMITTED);
        // …and the cross-locale row cannot discriminate while the undeclared locale is unexamined: it is
        // kept because a reader would otherwise take the uniqueness law for a model-wide one.
        expected.put("the same value once in EACH locale entry", KernelLaws.ADMITTED);
        expected.put("a long value with the maximum at the `-1` sentinel", KernelLaws.ADMITTED);
        // The uniqueness law counts duplicated VALUES and not lists, which one duplicated value cannot say.
        expected.put("TWO distinct values each duplicated in one list", "MVK_VALUES_DUPLICATE x2");
        // …and the list-level refusal does not pre-empt the value laws: A12 reports both.
        expected.put("a long value beside `noValueValidation`, which refuses the list itself",
                "MVK_VALUE_TOO_LONG x1,MVK_NO_VAL_AND_HINT_LISTS x1");
        assertEquals(expected, actual, "how many findings, and which locale the length laws judge");
    }

    /** The classes A12 raised with their multiplicities, so a row can state a count rather than a set. */
    private static String raised(String body) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ((ObjectNode) element.path("Field")).set("fieldType", MAPPER.readTree(
                            "{\"type\":\"StringType\",\"StringType\":{" + body + "}}"));
                }
            }
            io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(root.toString());
            return KernelLaws.ADMITTED;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the body must parse", unreadable);
        } catch (io.github.mbackschat.a12.dm.core.ModelConsistencyException rejected) {
            Map<String, Integer> counted = new LinkedHashMap<>();
            for (var notification : rejected.notifications()) {
                String message = notification.message().trim();
                counted.merge(message.matches(".*\\[\\w+]$")
                        ? message.replaceAll(".*\\[(\\w+)]$", "$1")
                        : "(codeless)", 1, Integer::sum);
            }
            return counted.entrySet().stream()
                    .map(entry -> entry.getKey() + " x" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(","));
        }
    }
}
