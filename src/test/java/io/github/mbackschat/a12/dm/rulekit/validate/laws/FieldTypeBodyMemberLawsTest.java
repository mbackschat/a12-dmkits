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
 * WHICH member each field type that constructs from its body actually requires.
 *
 * <p>{@link FieldTypeBindingLawsTest} measured that five of the thirteen types leave an unbuildable model
 * when their body is EMPTY, and stopped there — so a body carrying some members but not the one A12 builds
 * from was outside every measurement, and a checker keyed on emptiness says nothing about it. This grid
 * closes that, and it is a widening of a REFUSAL, so it needs a measured basis rather than a reading.
 *
 * <p><b>The denominator is DERIVED, which is what makes naming one member per type safe.</b> Each of these
 * classes takes its arguments through a Lombok {@code @Builder} on a private constructor, and a builder
 * argument is optional exactly when the hand-written {@code …Builder} class pre-initialises its field —
 * which each of these classes does explicitly, with a comment saying so. So the REQUIRED member of a type is
 * the {@code @NonNull} constructor parameter its builder class leaves alone, and there is exactly one per
 * type. <i>Source:</i> {@code kernel-md/kernel-md-a12internal/…/fieldtypes/{DateRangeType, DateFragmentType,
 * EnumerationType, CustomFieldType}.java}, kernel {@code 30.8.1}, read 2026-09-13:
 *
 * <table><caption>the derived reading</caption>
 * <tr><th>type</th><th>required</th><th>builder-defaulted</th></tr>
 * <tr><td>{@code DateRangeType}</td><td>{@code format}</td>
 *     <td>{@code annotations}, {@code rangeSeparator}, {@code youngerThan1900Check}</td></tr>
 * <tr><td>{@code DateFragmentType}</td><td>{@code formatOfFragment}</td>
 *     <td>{@code youngerThan1900Check}</td></tr>
 * <tr><td>{@code EnumerationType}</td><td>{@code values}</td>
 *     <td>{@code annotations}, {@code categories}, {@code errorMessage}, {@code alphabeticalSorting}</td></tr>
 * <tr><td>{@code CustomFieldType}</td><td>{@code name}</td><td>{@code annotations}</td></tr>
 * </table>
 *
 * <p>{@code EnumerationType} is the row that reading could most easily get wrong: its {@code values} FIELD
 * carries an initialiser ({@code = new EnumValues()}), so a reader skimming the fields would call it
 * defaulted. The initialiser is irrelevant — the builder overwrites the field through the constructor, and
 * the builder class defaults every other {@code @NonNull} parameter and not that one.
 *
 * <p>{@code TypeDefType} is the fifth constructing type and is deliberately absent: its member is a
 * REFERENCE, resolved against {@code content.typeDefinitions} rather than merely present, and
 * {@link FieldTypeBindingLawsTest} owns that grid.
 *
 * <p>Own domain (the shared order fixture), on {@code /Order/CustomerName} — a plain root field no other
 * declaration names, so a verdict here is about the type and not about a role the field plays.
 */
class FieldTypeBodyMemberLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Each constructing type with only an OPTIONAL member, then with the required one, then with both.
     *
     * <p>The optional-only row is the one the checker's gap named and nobody had run. The required-only row
     * is what says the member found by reading is the member A12 wants: without it, an unbuildable
     * optional-only row would be equally consistent with the type needing something else entirely, or two
     * things, or the body having to be a particular size.
     *
     * <p><b>The control is a type that requires nothing, carrying the SAME optional member.</b>
     * {@code DateType} and {@code DateFragmentType} both declare {@code youngerThan1900Check} and only the
     * second requires anything, so the pair moves one variable — whether the type constructs from a member —
     * and holds the body's shape fixed. A grid without it would be equally consistent with "a body carrying
     * exactly one member is refused".
     */
    @Test
    void whichBodyMemberEachConstructingTypeRequires() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DateType with an optional member only — the control",
                verdictFor("DateType", "{\"youngerThan1900Check\":true}"));
        actual.put("DateRangeType with a separator only", verdictFor("DateRangeType",
                "{\"rangeSeparator\":\"-\"}"));
        actual.put("DateRangeType with a format only", verdictFor("DateRangeType",
                "{\"format\":\"dd.MM.yyyy\"}"));
        actual.put("DateRangeType with a format the DEFAULT separator admits", verdictFor("DateRangeType",
                "{\"format\":\"yyyy-MM-dd\"}"));
        actual.put("DateRangeType with both", verdictFor("DateRangeType",
                "{\"format\":\"dd.MM.yyyy\",\"rangeSeparator\":\"-\"}"));
        actual.put("DateFragmentType with an optional member only", verdictFor("DateFragmentType",
                "{\"youngerThan1900Check\":true}"));
        actual.put("DateFragmentType with its format", verdictFor("DateFragmentType",
                "{\"formatOfFragment\":\"dd.MM.\"}"));
        actual.put("DateFragmentType with both", verdictFor("DateFragmentType",
                "{\"formatOfFragment\":\"dd.MM.\",\"youngerThan1900Check\":true}"));
        actual.put("DateFragmentType with a YEAR-bearing format and the check", verdictFor("DateFragmentType",
                "{\"formatOfFragment\":\"dd.MM.yyyy\",\"youngerThan1900Check\":true}"));
        actual.put("EnumerationType with sorting only", verdictFor("EnumerationType",
                "{\"alphabeticalSorting\":true}"));
        actual.put("EnumerationType with one value", verdictFor("EnumerationType",
                "{\"values\":[{\"value\":\"A\"}]}"));
        actual.put("EnumerationType with both", verdictFor("EnumerationType",
                "{\"values\":[{\"value\":\"A\"}],\"alphabeticalSorting\":true}"));
        actual.put("CustomFieldType with a display name only", verdictFor("CustomFieldType",
                "{\"displayName\":\"Signature\"}"));
        actual.put("CustomFieldType with its name", verdictFor("CustomFieldType",
                "{\"name\":\"Signature\"}"));
        actual.put("CustomFieldType with both", verdictFor("CustomFieldType",
                "{\"name\":\"Signature\",\"displayName\":\"Handwritten\"}"));
        // The member is PRESENT and carries nothing — the row that says whether "declares it" means the key
        // or a usable value, which absence alone cannot answer.
        actual.put("CustomFieldType with an EMPTY name", verdictFor("CustomFieldType", "{\"name\":\"\"}"));
        actual.put("CustomFieldType with a BLANK name", verdictFor("CustomFieldType", "{\"name\":\"   \"}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DateType with an optional member only — the control", KernelLaws.ADMITTED);
        expected.put("DateRangeType with a separator only", KernelLaws.REFUSED_CODELESS);
        // NOT sufficient, and this is the row that says the derived member is NECESSARY and no more: the
        // builder does default `rangeSeparator`, to `/`, and A12 allowlists the format-and-separator PAIR —
        // (`dd.MM.yyyy`, `/`) is not one of the eight admitted pairs (TEMPORAL-FORMAT-VOCABULARY).
        expected.put("DateRangeType with a format only", KernelLaws.REFUSED_CODELESS);
        // The discriminator for that account: a format the DEFAULT separator IS admitted with. Without it
        // the row above is equally consistent with `format` alone never being enough for this type.
        expected.put("DateRangeType with a format the DEFAULT separator admits", KernelLaws.ADMITTED);
        expected.put("DateRangeType with both", KernelLaws.ADMITTED);
        expected.put("DateFragmentType with an optional member only", KernelLaws.NOT_BUILDABLE);
        expected.put("DateFragmentType with its format", KernelLaws.ADMITTED);
        // The second row where the required member is present and the model is still refused — here by a
        // consistency check rather than a build failure, because a 1900 check needs a year and `dd.MM.` has
        // none. The next row is its discriminator and admits with the identical pair of members.
        expected.put("DateFragmentType with both", "MVK_ADDITIONAL_CHECK_INVALID");
        expected.put("DateFragmentType with a YEAR-bearing format and the check", KernelLaws.ADMITTED);
        expected.put("EnumerationType with sorting only", KernelLaws.NOT_BUILDABLE);
        expected.put("EnumerationType with one value", KernelLaws.ADMITTED);
        expected.put("EnumerationType with both", KernelLaws.ADMITTED);
        // The one row here that reaches a VERDICT rather than a build refusal — under an unprefixed class.
        expected.put("CustomFieldType with a display name only", "CUSTOM_FIELD_TYPE_CODENAME_MISSING");
        expected.put("CustomFieldType with its name", KernelLaws.ADMITTED);
        expected.put("CustomFieldType with both", KernelLaws.ADMITTED);
        expected.put("CustomFieldType with an EMPTY name", "CUSTOM_FIELD_TYPE_CODENAME_MISSING");
        expected.put("CustomFieldType with a BLANK name", "CUSTOM_FIELD_TYPE_CODENAME_MISSING");
        assertEquals(expected, actual, "which member each constructing field type requires");
    }

    /** The order fixture with {@code /Order/CustomerName} typed [type] and given [rawBody] verbatim. */
    private static String verdictFor(String type, String rawBody) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ((ObjectNode) element.path("Field")).set("fieldType", MAPPER.readTree(
                            "{\"type\":\"" + type + "\",\"" + type + "\":" + rawBody + "}"));
                    return KernelLaws.verdictOf(root.toString());
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw body must parse", unreadable);
        }
    }
}
