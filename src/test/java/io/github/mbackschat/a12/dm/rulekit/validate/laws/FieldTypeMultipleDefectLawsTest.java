package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Whether ONE field type carrying two independent defects draws both classes or stops at the first.
 *
 * <p>The same question {@link ConditionMultipleDefectLawsTest} asks of a condition, on the member where a
 * portable checker runs the most families over one declaration: five read a field's type. Every grid in the
 * estate varies ONE defect at a time, which is precisely the shape that cannot see a stop.
 *
 * <p>The two defects are independent — an invalid pattern cannot cause a string-parameter conflict or the
 * reverse — so a missing class is a stop rather than a consequence.
 *
 * <p>Own domain (a claims policy).
 */
class FieldTypeMultipleDefectLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy.stringField("Reference", f -> { }))
            .build().toJson();

    @Test
    void whetherOneFieldTypeReportsEveryDefectOrStopsAtTheFirst() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain string — the control", verdict("{}"));
        actual.put("an invalid pattern alone", verdict("{\"pattern\":\"a++\"}"));
        actual.put("a string-parameter conflict alone", verdict("{\"noValueValidation\":true}"));
        actual.put("BOTH on one field",
                verdict("{\"pattern\":\"a++\",\"noValueValidation\":true}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain string — the control", KernelLaws.ADMITTED);
        expected.put("an invalid pattern alone", "MVK_PATTERN_INVALID,MVK_ERROR_MESSAGE_AND_PATTERN_INVALID");
        expected.put("a string-parameter conflict alone", "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK");
        // Every class from both rows above, so a field type does NOT stop at its first defect — which bounds
        // the withholding measured on a rule's members and inside a condition. Same estate, same session,
        // opposite answer: the stop is a property of particular members, never assumed from a neighbour.
        //
        // And a FOURTH class appears that neither row alone produces: `noValueValidation` beside a `pattern`
        // is its own refusal. A grid varying one defect at a time cannot reach a combination-only class, and
        // every other grid in this estate is built that way.
        expected.put("BOTH on one field", "MVK_PATTERN_INVALID,MVK_ERROR_MESSAGE_AND_PATTERN_INVALID,"
                + "MVK_STRING_TYPE_PARAMS_INCONSISTENT,MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK");
        assertEquals(expected, actual, "whether one field type reports every defect or stops at the first");
    }

    /** The drafted policy with `Reference`'s `StringType` body replaced by [stringType]. */
    private static String verdict(String stringType) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode field = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).get(0).path("Field");
            ObjectNode fieldType = (ObjectNode) field.path("fieldType");
            fieldType.set("StringType", MAPPER.readTree(stringType));
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
