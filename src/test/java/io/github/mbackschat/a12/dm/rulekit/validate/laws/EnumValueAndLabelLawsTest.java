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
 * A declared enumeration's VALUE tokens and their per-locale labels.
 *
 * <p>{@code EnumCategoryAlignmentLawsTest} covers the category pairing beside these; this covers the values
 * themselves, which no lock reaches. It also gives a measured basis to the one client-side refusal in the
 * area: {@code DocumentModelDraft} refuses a partially-labelled enumeration at author time, citing
 * {@code MVK_COUNT_OF_VALUES_INVALID} from a comment rather than from a run.
 *
 * <p>Own domain (the shared order fixture), on {@code /Order/CustomerName}, retyped as an enumeration.
 */
class EnumValueAndLabelLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The code for a label set that is started and not finished.
     *
     * <p>Its name reads as "both kinds of value are declared", which is not what fires it. What the rows
     * below measure is: <b>once ANY value carries an entry for ANY DECLARED locale, every value must carry a
     * non-blank text for every declared locale.</b> That is stated as the observable law and no mechanism is
     * claimed for it — the two rows that pin it are "a label for an undeclared locale", admitted because no
     * declared locale has an entry at all, and "every label text blank", refused although no locale is
     * answered, because the entries are there.
     */
    private static final String DISPLAY_SHORT = "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES";

    /**
     * …and the code for values that do not AGREE on carrying a label at all, which is reported INSTEAD.
     *
     * <p>An empty {@code label} array counts as unlabelled, so it draws this rather than the code above even
     * though the locale is equally unanswered. The two are separated by whether the disagreement is between
     * VALUES or within a value's locale coverage.
     */
    private static final String COUNT_INVALID = "MVK_COUNT_OF_VALUES_INVALID";

    /** The order fixture with {@code CustomerName} retyped as the enumeration body [json] spells. */
    private static String storedModelWith(String json, String... locales) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            var declared = ((ObjectNode) root.path("header")).putArray("locales");
            for (String locale : locales.length == 0 ? new String[] {"en_US"} : locales) {
                declared.addObject().put("code", locale);
            }
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ObjectNode fieldType = MAPPER.createObjectNode();
                    fieldType.put("type", "EnumerationType");
                    fieldType.set("EnumerationType", (ObjectNode) MAPPER.readTree(json));
                    ((ObjectNode) element.path("Field")).set("fieldType", fieldType);
                    return root.toString();
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    private static String label(String locale, String text) {
        return "\"label\":[{\"locale\":\"" + locale + "\",\"text\":\"" + text + "\"}]";
    }

    @Test
    void theValueTokensThemselves() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("two ordinary values — the control",
                "{\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}]}");
        rows.put("the same value twice", "{\"values\":[{\"value\":\"BASIC\"},{\"value\":\"BASIC\"}]}");
        rows.put("a single value", "{\"values\":[{\"value\":\"BASIC\"}]}");
        rows.put("an EMPTY value token", "{\"values\":[{\"value\":\"\"},{\"value\":\"PLUS\"}]}");
        rows.put("a value token with a space", "{\"values\":[{\"value\":\"TWO WORDS\"},{\"value\":\"PLUS\"}]}");
        rows.put("a very long value token",
                "{\"values\":[{\"value\":\"" + "X".repeat(300) + "\"},{\"value\":\"PLUS\"}]}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) -> actual.put(row, KernelLaws.verdictOf(storedModelWith(json))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two ordinary values — the control", KernelLaws.ADMITTED);
        expected.put("the same value twice", "MVK_INTERNAL_VALUES_DUPLICATE");
        expected.put("a single value", KernelLaws.ADMITTED);
        expected.put("an EMPTY value token", KernelLaws.ADMITTED);
        expected.put("a value token with a space", KernelLaws.ADMITTED);
        expected.put("a very long value token", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a declared enumeration's value tokens");
    }

    @Test
    void thePerLocaleLabelsBesideThem() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("no labels at all — the control",
                "{\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}]}");
        rows.put("every value labelled for the declared locale",
                "{\"values\":[{\"value\":\"BASIC\"," + label("en_US", "Basic") + "},"
                        + "{\"value\":\"PLUS\"," + label("en_US", "Plus") + "}]}");
        rows.put("only ONE value labelled",
                "{\"values\":[{\"value\":\"BASIC\"," + label("en_US", "Basic") + "},"
                        + "{\"value\":\"PLUS\"}]}");
        rows.put("a label for an UNDECLARED locale",
                "{\"values\":[{\"value\":\"BASIC\"," + label("de_DE", "Einfach") + "},"
                        + "{\"value\":\"PLUS\"," + label("de_DE", "Plus") + "}]}");
        rows.put("an EMPTY label text",
                "{\"values\":[{\"value\":\"BASIC\"," + label("en_US", "") + "},"
                        + "{\"value\":\"PLUS\"," + label("en_US", "Plus") + "}]}");
        rows.put("TWO labels for the same locale on one value",
                "{\"values\":[{\"value\":\"BASIC\",\"label\":[{\"locale\":\"en_US\",\"text\":\"Basic\"},"
                        + "{\"locale\":\"en_US\",\"text\":\"Entry\"}]},"
                        + "{\"value\":\"PLUS\"," + label("en_US", "Plus") + "}]}");

        // The rows that separate the two codes. Values must AGREE on carrying a `label` entry (else
        // COUNT_INVALID), and a labelled value then needs a non-blank text for every declared locale (else
        // DISPLAY_SHORT): an empty label ARRAY is an unlabelled value and all-blank texts are a complete-but-empty
        // locale. On kernel 31.1.1 a label for an UNDECLARED locale is a label too (KERNEL-FINDINGS KF655).
        rows.put("an EMPTY label ARRAY on one value",
                "{\"values\":[{\"value\":\"BASIC\",\"label\":[]},"
                        + "{\"value\":\"PLUS\"," + label("en_US", "Plus") + "}]}");
        rows.put("every label text blank",
                "{\"values\":[{\"value\":\"BASIC\"," + label("en_US", "") + "},"
                        + "{\"value\":\"PLUS\"," + label("en_US", "") + "}]}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) -> actual.put(row, KernelLaws.verdictOf(storedModelWith(json))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("no labels at all — the control", KernelLaws.ADMITTED);
        expected.put("every value labelled for the declared locale", KernelLaws.ADMITTED);
        expected.put("only ONE value labelled", COUNT_INVALID);
        expected.put("a label for an UNDECLARED locale", DISPLAY_SHORT);
        expected.put("an EMPTY label text", DISPLAY_SHORT);
        expected.put("TWO labels for the same locale on one value", KernelLaws.ADMITTED);
        expected.put("an EMPTY label ARRAY on one value", COUNT_INVALID);
        expected.put("every label text blank", DISPLAY_SHORT);
        assertEquals(expected, actual, "a declared enumeration's per-locale labels");
    }

    /**
     * The label TEXTS against each other, which is a law of its own and a law within ONE language.
     *
     * <p>[MVK-LEDGER] carries {@code MVK_DISPLAY_VALUES_DUPLICATE} as a structural law with no measurement.
     * A12 writes one per-language block per declared locale and asks each block's texts to be distinct, so
     * the discriminating row is the same text used in two DIFFERENT locales — which a checker keyed on the
     * texts alone would refuse.
     */
    @Test
    void theLabelTextsMustBeDistinctWithinOneLanguage() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("distinct labels — the control", KernelLaws.verdictOf(storedModelWith(
                "{\"values\":[{\"value\":\"BASIC\"," + label("en_US", "Basic") + "},"
                        + "{\"value\":\"PLUS\"," + label("en_US", "Plus") + "}]}")));
        actual.put("two values sharing one label text", KernelLaws.verdictOf(storedModelWith(
                "{\"values\":[{\"value\":\"BASIC\"," + label("en_US", "Same") + "},"
                        + "{\"value\":\"PLUS\"," + label("en_US", "Same") + "}]}")));
        actual.put("the same text in two DIFFERENT languages", KernelLaws.verdictOf(storedModelWith(
                "{\"values\":[{\"value\":\"BASIC\",\"label\":[{\"locale\":\"en_US\",\"text\":\"Same\"},"
                        + "{\"locale\":\"de_DE\",\"text\":\"Einfach\"}]},"
                        + "{\"value\":\"PLUS\",\"label\":[{\"locale\":\"en_US\",\"text\":\"Plus\"},"
                        + "{\"locale\":\"de_DE\",\"text\":\"Same\"}]}]}", "en_US", "de_DE")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("distinct labels — the control", KernelLaws.ADMITTED);
        expected.put("two values sharing one label text", "MVK_DISPLAY_VALUES_DUPLICATE");
        expected.put("the same text in two DIFFERENT languages", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "an enumeration's label texts against each other");
    }

    /** The bilingual axis, which a one-locale header cannot show — the constant the grid above holds. */
    @Test
    void whichDeclaredLocalesTheLabelsMustCover() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("both declared locales labelled",
                "{\"values\":[{\"value\":\"BASIC\",\"label\":[{\"locale\":\"en_US\",\"text\":\"Basic\"},"
                        + "{\"locale\":\"de_DE\",\"text\":\"Einfach\"}]},"
                        + "{\"value\":\"PLUS\",\"label\":[{\"locale\":\"en_US\",\"text\":\"Plus\"},"
                        + "{\"locale\":\"de_DE\",\"text\":\"Plus\"}]}]}");
        rows.put("only ONE declared locale labelled",
                "{\"values\":[{\"value\":\"BASIC\"," + label("en_US", "Basic") + "},"
                        + "{\"value\":\"PLUS\"," + label("en_US", "Plus") + "}]}");

        rows.put("one value bilingual, the other English only",
                "{\"values\":[{\"value\":\"BASIC\",\"label\":[{\"locale\":\"en_US\",\"text\":\"Basic\"},"
                        + "{\"locale\":\"de_DE\",\"text\":\"Einfach\"}]},"
                        + "{\"value\":\"PLUS\"," + label("en_US", "Plus") + "}]}");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, json) ->
                actual.put(row, KernelLaws.verdictOf(storedModelWith(json, "en_US", "de_DE"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both declared locales labelled", KernelLaws.ADMITTED);
        expected.put("only ONE declared locale labelled", DISPLAY_SHORT);
        expected.put("one value bilingual, the other English only", DISPLAY_SHORT);
        assertEquals(expected, actual, "which declared locales an enumeration's labels must cover");
    }
}
