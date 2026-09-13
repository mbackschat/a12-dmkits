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
 * Which combinations of a declared string type's own parameters A12 admits.
 *
 * <p>Five codes, one topic: what a {@code StringType} may declare alongside {@code noValueValidation}, and
 * the two length laws beside it. They are measured together because measuring one of a co-requirement pair
 * alone produces a law that is false — the lesson the rounding pair already cost
 * ({@code ComputationRoundingLawsTest}).
 *
 * <p><b>Three of these are also client-side refusals in {@code FieldTypeSpec.StringSpec}'s constructor</b>,
 * and a refusal we invent can be wrong in the direction nothing detects, because the kernel is never asked.
 * That is what the rows below give it a basis for; where a row shows the kit is LOOSER, that is delegation
 * and safe.
 *
 * <p>The wire name of the line-break flag is {@code lineBreaksPermitted}, taken from the serializer's own
 * mixin rather than from {@code StringTypeImpl}'s field, which spells it {@code linebreaksPermitted} — two
 * spellings one letter apart, and the row that carries the flag would silently measure nothing if the wrong
 * one were used. The {@code noValueValidation} row beside it is the control that proves the flag ARRIVES.
 *
 * <p>Own domain (the shared order fixture), on {@code /Order/CustomerName}.
 */
class StringTypeParameterLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String NO_VALUE_NEEDS_LINE_BREAKS = "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK";
    private static final String PARAMS_INCONSISTENT = "MVK_STRING_TYPE_PARAMS_INCONSISTENT";
    private static final String NO_VALUE_AND_HINTS = "MVK_NO_VAL_AND_HINT_LISTS";
    private static final String LINE_BREAKS_AND_LENGTH_ONE = "MVK_LINE_BREAK_AND_MAX_LENGTH_1_INVALID";
    private static final String MIN_ABOVE_MAX = "MVK_MIN_LENGTH_BIGGER_MAX_LENGTH";

    /** The order fixture with {@code CustomerName} carrying the {@code StringType} body [json] spells. */
    private static String storedModelWith(String json) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ObjectNode fieldType = MAPPER.createObjectNode();
                    fieldType.put("type", "StringType");
                    fieldType.set("StringType", (ObjectNode) MAPPER.readTree(json));
                    ((ObjectNode) element.path("Field")).set("fieldType", fieldType);
                    return root.toString();
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /** A pattern with the error text A12 requires beside it, so no row here measures that law instead. */
    private static final String PATTERN =
            "\"pattern\":\"[A-Z]+\",\"errorMessage\":[{\"locale\":\"en_US\",\"text\":\"letters only\"}]";

    private static final String HINT_LIST = "\"hintList\":[{\"locale\":\"en_US\",\"values\":[\"AB\"]}]";

    @Test
    void whatAStringTypeMayDeclareAlongsideNoValueValidation() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("an ordinary string type — the control", "{\"maxLength\":40}");
        rows.put("no-value validation alone", "{\"noValueValidation\":true}");
        rows.put("no-value validation with line breaks", "{\"noValueValidation\":true,"
                + "\"lineBreaksPermitted\":true}");
        rows.put("…and a pattern", "{\"noValueValidation\":true,\"lineBreaksPermitted\":true," + PATTERN + "}");
        rows.put("…and a minimum length",
                "{\"noValueValidation\":true,\"lineBreaksPermitted\":true,\"minLength\":2}");
        rows.put("…and a maximum length",
                "{\"noValueValidation\":true,\"lineBreaksPermitted\":true,\"maxLength\":40}");
        rows.put("…and a hint list",
                "{\"noValueValidation\":true,\"lineBreaksPermitted\":true," + HINT_LIST + "}");

        // The same three members WITHOUT no-value validation, so each row above is attributed to the pair
        // rather than to the member alone.
        rows.put("a pattern on its own", "{" + PATTERN + "}");
        rows.put("a minimum length on its own", "{\"minLength\":2}");
        rows.put("a hint list on its own", "{" + HINT_LIST + "}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) -> actual.put(row, KernelLaws.verdictOf(storedModelWith(json))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary string type — the control", KernelLaws.ADMITTED);
        expected.put("no-value validation alone", NO_VALUE_NEEDS_LINE_BREAKS);
        expected.put("no-value validation with line breaks", KernelLaws.ADMITTED);
        expected.put("…and a pattern", PARAMS_INCONSISTENT);
        expected.put("…and a minimum length", PARAMS_INCONSISTENT);
        expected.put("…and a maximum length", KernelLaws.ADMITTED);
        expected.put("…and a hint list", NO_VALUE_AND_HINTS);
        expected.put("a pattern on its own", KernelLaws.ADMITTED);
        expected.put("a minimum length on its own", KernelLaws.ADMITTED);
        expected.put("a hint list on its own", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what a string type may declare alongside no-value validation");
    }

    /**
     * The shapes a CHECKER has to decide and the grids above leave open.
     *
     * <p>Each row is a judgement an implementation must make anyway — whether the sentinel counts as a
     * declared minimum for the exclusion law, whether an empty hint list is a hint list, and whether two
     * violations on one type both surface — so each is measured rather than chosen.
     */
    @Test
    void theShapesBetweenTheRows() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("no-value validation with line breaks and an UNDEFINED minimum",
                "{\"noValueValidation\":true,\"lineBreaksPermitted\":true,\"minLength\":-1}");
        rows.put("no-value validation with line breaks and an EMPTY hint list",
                "{\"noValueValidation\":true,\"lineBreaksPermitted\":true,\"hintList\":[]}");
        rows.put("line breaks with an UNDEFINED maximum",
                "{\"lineBreaksPermitted\":true,\"maxLength\":-1}");
        rows.put("no-value validation WITHOUT line breaks and with a pattern",
                "{\"noValueValidation\":true," + PATTERN + "}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) -> actual.put(row, KernelLaws.verdictOf(storedModelWith(json))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("no-value validation with line breaks and an UNDEFINED minimum", KernelLaws.ADMITTED);
        expected.put("no-value validation with line breaks and an EMPTY hint list", KernelLaws.ADMITTED);
        expected.put("line breaks with an UNDEFINED maximum", KernelLaws.ADMITTED);
        // Both fire: the exclusion law and the line-break requirement are independent, so a checker owes
        // two findings for this one type rather than stopping at the first.
        expected.put("no-value validation WITHOUT line breaks and with a pattern",
                PARAMS_INCONSISTENT + "," + NO_VALUE_NEEDS_LINE_BREAKS);
        assertEquals(expected, actual, "the string-type parameter shapes a checker has to decide");
    }

    /** The order fixture with {@code CustomerName} carrying a {@code CustomFieldType} body. */
    private static String storedCustomTypeWith(String json) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ObjectNode fieldType = MAPPER.createObjectNode();
                    fieldType.put("type", "CustomFieldType");
                    fieldType.set("CustomFieldType", (ObjectNode) MAPPER.readTree(json));
                    ((ObjectNode) element.path("Field")).set("fieldType", fieldType);
                    return root.toString();
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /**
     * The SIBLING carrier of the same two length members, measured rather than assumed.
     *
     * <p>{@code FieldTypeSpec.CustomSpec} carries the identical {@code minLength}/{@code maxLength} pair and
     * the identical refusals, and a custom type is a different kernel type with a different check — so the
     * string type's rows are not evidence about it. This is the sibling case the scope-transfer rule names:
     * reuse needs no new branch, so nothing prompts the question.
     */
    @Test
    void theSameTwoMembersOnACustomFieldType() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("an ordinary custom type — the control",
                "{\"name\":\"iban\",\"minLength\":2,\"maxLength\":34}");
        rows.put("a NEGATIVE minimum", "{\"name\":\"iban\",\"minLength\":-1,\"maxLength\":34}");
        rows.put("a real minimum with a NEGATIVE maximum",
                "{\"name\":\"iban\",\"minLength\":2,\"maxLength\":-1}");
        rows.put("a minimum above the maximum", "{\"name\":\"iban\",\"minLength\":5,\"maxLength\":3}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) -> actual.put(row, KernelLaws.verdictOf(storedCustomTypeWith(json))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary custom type — the control", KernelLaws.ADMITTED);
        expected.put("a NEGATIVE minimum", KernelLaws.ADMITTED);
        expected.put("a real minimum with a NEGATIVE maximum", KernelLaws.ADMITTED);
        // NOT the string type's coded verdict: on this carrier the inverted range stops A12 BUILDING the
        // model, so there is no consistency finding to report at all. The sentinel rows agree; this one does
        // not, which is why the carrier was measured instead of inheriting the sibling's rows.
        expected.put("a minimum above the maximum", KernelLaws.NOT_BUILDABLE);
        assertEquals(expected, actual, "the same two length members on a custom field type");
    }

    @Test
    void theTwoLengthLawsAndTheLineBreakOneBesideThem() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("a minimum above the maximum", "{\"minLength\":5,\"maxLength\":3}");
        rows.put("a minimum EQUAL to the maximum — the boundary", "{\"minLength\":3,\"maxLength\":3}");
        // -1 is A12's OWN "undefined" sentinel (`IFieldType.UNDEFINED`), so these rows measure what a model
        // spelling an unset bound explicitly does — not an exotic input.
        rows.put("a NEGATIVE minimum", "{\"minLength\":-1,\"maxLength\":3}");
        rows.put("a NEGATIVE maximum", "{\"maxLength\":-1}");
        rows.put("a real minimum with a NEGATIVE maximum", "{\"minLength\":2,\"maxLength\":-1}");
        rows.put("line breaks with a maximum of one", "{\"lineBreaksPermitted\":true,\"maxLength\":1}");
        rows.put("a maximum of one on its own", "{\"maxLength\":1}");
        rows.put("line breaks with a maximum of two", "{\"lineBreaksPermitted\":true,\"maxLength\":2}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) -> actual.put(row, KernelLaws.verdictOf(storedModelWith(json))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a minimum above the maximum", MIN_ABOVE_MAX);
        expected.put("a minimum EQUAL to the maximum — the boundary", KernelLaws.ADMITTED);
        expected.put("a NEGATIVE minimum", KernelLaws.ADMITTED);
        expected.put("a NEGATIVE maximum", KernelLaws.ADMITTED);
        expected.put("a real minimum with a NEGATIVE maximum", KernelLaws.ADMITTED);
        expected.put("line breaks with a maximum of one", LINE_BREAKS_AND_LENGTH_ONE);
        expected.put("a maximum of one on its own", KernelLaws.ADMITTED);
        expected.put("line breaks with a maximum of two", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a string type's two length laws and the line-break one beside them");
    }
}
