package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * Whether a group carrying a defect on BOTH ordering members draws both classes or stops at the first.
 *
 * <p>The fourth member put through the defect-pairing pass. {@link GroupOrderingFieldTypeLawsTest} measures
 * each member alone and says so in its own fixture: it deletes {@code indexFieldName} before setting
 * {@code sortFields}, because leaving it "would fire the INDEX gate beside the sort one and measure both at
 * once". That is precisely the row nobody had.
 *
 * <p><b>Every row keeps the model BUILDABLE.</b> An ordering name that resolves to nothing stops the build,
 * which is a different tier — pairing across it would move two variables at once, so the unresolvable member
 * gets one clearly labelled row at the end rather than a place in the grid.
 *
 * <p>Own domain (the shared order fixture: the repeatable {@code /Order/Items}).
 */
class GroupOrderingMultipleDefectLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INDEX_TYPE = "MVK_INDEX_FIELD_NO_VALUE_VALIDATION";
    private static final String SORT_TYPE = "MVK_SORT_FIELD_NO_VALUE_VALIDATION";
    private static final String SORT_DUPLICATE = "MVK_SORT_FIELD_DUPLICATE";

    private static final String OPAQUE = "{\"type\":\"StringType\",\"StringType\":{\"noValueValidation\":true}}";

    @Test
    void bothOrderingMembersReportAndNeitherWithholdsTheOther() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("both members on a plain field — the control", ordering(false, "Sku", "Sku"));
        actual.put("an opaque INDEX field alone", ordering(true, "Sku"));
        actual.put("an opaque SORT field alone", ordering(true, null, "Sku"));
        actual.put("both members on the opaque field", ordering(true, "Sku", "Sku"));
        actual.put("an opaque index with a plain sort field", ordering(true, "Sku", "Count"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both members on a plain field — the control", KernelLaws.ADMITTED);
        expected.put("an opaque INDEX field alone", INDEX_TYPE + "," + OPAQUE_STRING);
        expected.put("an opaque SORT field alone", SORT_TYPE + "," + OPAQUE_STRING);
        // The row the sibling file's fixture deletes `indexFieldName` to avoid: both classes, so neither
        // member withholds the other. The fifth member measured this way and the fourth to report in full.
        expected.put("both members on the opaque field", INDEX_TYPE + "," + SORT_TYPE + "," + OPAQUE_STRING);
        expected.put("an opaque index with a plain sort field", INDEX_TYPE + "," + OPAQUE_STRING);
        assertEquals(expected, actual, "both ordering members report and neither withholds the other");
    }

    /**
     * What the sort LIST draws about itself — a repeat, and how many bad entries it speaks about.
     *
     * <p>Two findings here are about the list rather than an entry, and both needed their own multiplicity
     * measured: the duplicate class is drawn ONCE however many names repeat and however often, and the type
     * class is drawn ONCE however many entries are opaque. The last is why the added-field control exists —
     * one class for two bad entries is only a stop if the second entry is judged when it is the only bad one.
     */
    @Test
    void theSortListSpeaksOnceAboutARepeatAndOnceAboutItsOpaqueEntries() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one field sorted twice — plain", ordering(false, null, "Sku", "Sku"));
        actual.put("one field sorted THREE times", ordering(false, null, "Sku", "Sku", "Sku"));
        actual.put("TWO fields each sorted twice",
                ordering(false, null, "Sku", "Sku", "Count", "Count"));
        actual.put("the opaque field sorted TWICE", ordering(true, null, "Sku", "Sku"));
        actual.put("TWO different opaque fields sorted", twoOpaqueSorts());
        actual.put("the ADDED field alone, sorted — the control", onlyBatchOpaque());
        actual.put("two opaque fields and NO ordering member", twoOpaqueFieldsUnordered());

        Map<String, String> expected = new LinkedHashMap<>();
        // One, whatever repeats and however often — a verdict on the LIST, not on an entry.
        expected.put("one field sorted twice — plain", SORT_DUPLICATE);
        expected.put("one field sorted THREE times", SORT_DUPLICATE);
        expected.put("TWO fields each sorted twice", SORT_DUPLICATE);
        // …and it rides beside the type class rather than replacing it.
        expected.put("the opaque field sorted TWICE", SORT_TYPE + "," + SORT_DUPLICATE + "," + OPAQUE_STRING);
        // ONE type class for TWO opaque entries. The control below is what makes that a stop rather than an
        // unread field: the added field draws the class when it is the only bad one.
        expected.put("TWO different opaque fields sorted", SORT_TYPE + "," + OPAQUE_STRING);
        expected.put("the ADDED field alone, sorted — the control", SORT_TYPE + "," + OPAQUE_STRING);
        // …and the string-type class is itself once per model, not once per opaque field, which this row
        // separates from the ordering rows above by removing both ordering members.
        expected.put("two opaque fields and NO ordering member", OPAQUE_STRING);
        assertEquals(expected, actual, "what the sort list draws about itself");
    }

    /**
     * The order's tail: a member naming nothing, which stops the BUILD rather than drawing a member code.
     *
     * <p>Its own test because the buildability tier is the variable it moves, and a row inside the grids
     * above would move that as well as the member.
     */
    @Test
    void anUnresolvableOrderingNameStopsTheBuildAndTheOtherMembersVerdict() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an unresolvable index alone", ordering(false, "NoSuchField"));
        actual.put("an unresolvable sort field alone", ordering(false, null, "NoSuchField"));
        actual.put("an unresolvable index beside an OPAQUE sort field",
                ordering(true, "NoSuchField", "Sku"));
        actual.put("an opaque index beside an unresolvable sort field",
                ordering(true, "Sku", "NoSuchField"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an unresolvable index alone", KernelLaws.NOT_BUILDABLE);
        expected.put("an unresolvable sort field alone", KernelLaws.NOT_BUILDABLE);
        // No member code survives the tier change, in either direction — so an unresolvable name withholds
        // the other member's verdict as well, and a checker pairing across this boundary would be measuring
        // buildability rather than the member it meant to vary.
        expected.put("an unresolvable index beside an OPAQUE sort field", KernelLaws.NOT_BUILDABLE);
        expected.put("an opaque index beside an unresolvable sort field", KernelLaws.NOT_BUILDABLE);
        assertEquals(expected, actual, "an unresolvable ordering name stops the build");
    }

    /** Rides along in every opaque row: the field this test makes defective trips the string-type law too. */
    private static final String OPAQUE_STRING = "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK";

    /**
     * One verdict for `/Order/Items` with its ordering members rewritten.
     *
     * @param opaque whether `Sku` is redeclared as a no-value-validation string
     * @param index the `indexFieldName` to set, or null to remove the member
     * @param sortFields the `sortFields[].name` entries; passing none removes the member entirely
     */
    private static String ordering(boolean opaque, String index, String... sortFields) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            ObjectNode items = groupBody(root, "Items");
            if (opaque) {
                for (JsonNode element : items.path("elements")) {
                    if ("Sku".equals(element.path("name").asText())) {
                        ((ObjectNode) element.path("Field")).set("fieldType", MAPPER.readTree(OPAQUE));
                    }
                }
            }
            items.remove("indexFieldName");
            items.remove("sortFields");
            if (index != null) {
                items.put("indexFieldName", index);
            }
            if (sortFields.length > 0) {
                ArrayNode sorts = items.putArray("sortFields");
                for (String name : sortFields) {
                    sorts.addObject().put("name", name);
                }
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the type fragment must parse", unreadable);
        }
    }

    /** Two opaque fields with neither ordering member set, which isolates the string-type class's count. */
    private static String twoOpaqueFieldsUnordered() {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            ObjectNode items = groupBody(root, "Items");
            for (JsonNode element : items.path("elements")) {
                if ("Sku".equals(element.path("name").asText())) {
                    ((ObjectNode) element.path("Field")).set("fieldType", MAPPER.readTree(OPAQUE));
                }
            }
            ObjectNode batch = ((ArrayNode) items.path("elements")).addObject();
            batch.put("type", "Field").put("id", "fbatch").put("name", "Batch");
            batch.putObject("Field").set("fieldType", MAPPER.readTree(OPAQUE));
            items.remove("indexFieldName");
            items.remove("sortFields");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the type fragment must parse", unreadable);
        }
    }

    /** The added field opaque and `Sku` left plain, sorted on the added one — the reachability control. */
    private static String onlyBatchOpaque() {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            ObjectNode items = groupBody(root, "Items");
            ObjectNode batch = ((ArrayNode) items.path("elements")).addObject();
            batch.put("type", "Field").put("id", "fbatch").put("name", "Batch");
            batch.putObject("Field").set("fieldType", MAPPER.readTree(OPAQUE));
            items.remove("indexFieldName");
            items.putArray("sortFields").addObject().put("name", "Batch");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the type fragment must parse", unreadable);
        }
    }

    /** A second opaque string field beside `Sku`, so two sort entries can name two different fields. */
    private static String twoOpaqueSorts() {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            ObjectNode items = groupBody(root, "Items");
            for (JsonNode element : items.path("elements")) {
                if ("Sku".equals(element.path("name").asText())) {
                    ((ObjectNode) element.path("Field")).set("fieldType", MAPPER.readTree(OPAQUE));
                }
            }
            ObjectNode batch = ((ArrayNode) items.path("elements")).addObject();
            batch.put("type", "Field").put("id", "fbatch").put("name", "Batch");
            batch.putObject("Field").set("fieldType", MAPPER.readTree(OPAQUE));
            items.remove("indexFieldName");
            ArrayNode sorts = items.putArray("sortFields");
            sorts.addObject().put("name", "Sku");
            sorts.addObject().put("name", "Batch");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the type fragment must parse", unreadable);
        }
    }

    /** The named group's body, refusing rather than returning a model the substitution never reached. */
    private static ObjectNode groupBody(ObjectNode root, String name) {
        for (JsonNode element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if (name.equals(element.path("name").asText())) {
                return (ObjectNode) element.path("Group");
            }
        }
        throw new IllegalStateException("the fixture no longer declares the group `" + name + "`");
    }
}
