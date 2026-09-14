package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §8 — the SHAPE laws A12's metamodel puts on an Enumeration declaration.
 *
 * <p>[MVK-LEDGER] carries fifteen of them as {@code n/a-structural}, and a large share turn on members a
 * stored model has no room for: A12's DM-JSON {@code EnumerationType} declares {@code values},
 * {@code categories}, {@code errorMessage}, {@code alphabeticalSorting} and {@code sValue} — no table, no
 * column, no dependent columns. These rows measure the ones that are left, and attack the table axis rather
 * than concluding it away.
 *
 * <p>Own domain (insurance).
 */
class EnumDeclarationShapeLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("policy-enum-shape").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS")))
            .build().toJson();

    /** The fixture with `Tier`'s {@code EnumerationType} body replaced by [body]. */
    private static String verdict(String body) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ArrayNode elements = (ArrayNode) root.at("/content/modelRoot/rootGroups/0/Group/elements");
            for (var element : elements) {
                if ("Tier".equals(element.path("name").asText())) {
                    ((ObjectNode) element.path("Field")).set(
                            "fieldType",
                            MAPPER.readTree("{\"type\":\"EnumerationType\",\"EnumerationType\":{"
                                    + body + "}}"));
                }
            }
            return KernelLaws.verdictOf(MAPPER.writeValueAsString(root));
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static final String TWO_VALUES = "\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}]";

    @Test
    void whichEnumerationShapeLawsAStoredModelCanReach() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two declared values — the control", verdict(TWO_VALUES));
        actual.put("an EMPTY value list", verdict("\"values\":[]"));
        actual.put("no `values` member at all", verdict("\"alphabeticalSorting\":false"));
        actual.put("an sValue naming a declared value", verdict(TWO_VALUES + ",\"sValue\":\"BASIC\""));
        actual.put("an sValue naming NO declared value", verdict(TWO_VALUES + ",\"sValue\":\"NOPE\""));
        actual.put("a BLANK sValue", verdict(TWO_VALUES + ",\"sValue\":\"\""));
        actual.put("a table name beside the inline values", verdict(TWO_VALUES + ",\"tableName\":\"TIERS\""));
        actual.put("a table and column name beside them",
                verdict(TWO_VALUES + ",\"tableName\":\"TIERS\",\"columnName\":\"CODE\""));
        actual.put("two categories sharing a name", verdict(TWO_VALUES
                + ",\"categories\":[{\"name\":\"Band\",\"values\":[\"LOW\",\"HIGH\"]},"
                + "{\"name\":\"Band\",\"values\":[\"A\",\"B\"]}]"));
        actual.put("a repeated VALUE with a category declared",
                verdict("\"values\":[{\"value\":\"BASIC\"},{\"value\":\"BASIC\"}],"
                        + "\"categories\":[{\"name\":\"Band\",\"values\":[\"LOW\",\"HIGH\"]}]"));
        actual.put("the same repeated value with NO category",
                verdict("\"values\":[{\"value\":\"BASIC\"},{\"value\":\"BASIC\"}]"));
        actual.put("a category holding FEWER tokens than the enum has values",
                verdict(TWO_VALUES + ",\"categories\":[{\"name\":\"Band\",\"values\":[\"LOW\"]}]"));
        actual.put("a value carrying a character XML cannot hold",
                verdict("\"values\":[{\"value\":\"BASIC\"},{\"value\":\"A<B\"}]"));
        actual.put("a category TOKEN carrying one",
                verdict(TWO_VALUES + ",\"categories\":[{\"name\":\"Band\",\"values\":[\"LOW\",\"A<B\"]}]"));
        actual.put("the same repeated value with TWO categories",
                verdict("\"values\":[{\"value\":\"BASIC\"},{\"value\":\"BASIC\"}],"
                        + "\"categories\":[{\"name\":\"Band\",\"values\":[\"LOW\",\"HIGH\"]},"
                        + "{\"name\":\"Zone\",\"values\":[\"A\",\"B\"]}]"));
        actual.put("a repeated category TOKEN over distinct values",
                verdict(TWO_VALUES + ",\"categories\":[{\"name\":\"Band\",\"values\":[\"LOW\",\"LOW\"]}]"));
        actual.put("a value carrying a control character XML forbids outright",
                verdict("\"values\":[{\"value\":\"BASIC\"},{\"value\":\"A\\u0001B\"}]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two declared values — the control", KernelLaws.ADMITTED);
        // `MVK_INTERNAL_VALUES_MISSING` names a shape A12 refuses one tier earlier: an Enumeration with
        // nothing to enumerate is not built at all, so no verdict carrying that class exists.
        expected.put("an EMPTY value list", KernelLaws.NOT_BUILDABLE);
        expected.put("no `values` member at all", KernelLaws.NOT_BUILDABLE);
        expected.put("an sValue naming a declared value", KernelLaws.ADMITTED);
        // The class behind modelkit's one deliberate over-strictness, measured on the checker's own carrier.
        expected.put("an sValue naming NO declared value", "MVK_S_VALUE_INVALID");
        // The table axis, attacked rather than concluded away — and the refusal is one tier out again:
        // A12's stored type declares no such member and its READER refuses the bytes, so the five "not
        // allowed beside a table" laws and the table/column co-requirement have no stored shape at all.
        // A blank one is not a value outside the domain but a member A12 reads as unset, so the law is not
        // asked — the row that keeps a checker from refusing it.
        expected.put("a BLANK sValue", KernelLaws.ADMITTED);
        expected.put("a table name beside the inline values", KernelLaws.UNREADABLE);
        expected.put("a table and column name beside them", KernelLaws.UNREADABLE);
        // The categories arrive as a MAP keyed by name, so a repeated name is a conversion failure rather
        // than a verdict — `MVK_CATEGORY_NAME_DUPLICATE` names a shape A12 never judges.
        expected.put("two categories sharing a name", KernelLaws.NOT_BUILDABLE);
        // A repeated enum value is written into every category's own value list as well, so a category
        // turns one defect into two classes — the accumulation a checker must either report or disclose.
        expected.put("a repeated VALUE with a category declared",
                "MVK_CATEGORY_VALUE_DUPLICATE,MVK_INTERNAL_VALUES_DUPLICATE");
        expected.put("the same repeated value with NO category", "MVK_INTERNAL_VALUES_DUPLICATE");
        expected.put("the same repeated value with TWO categories",
                "MVK_CATEGORY_VALUE_DUPLICATE,MVK_INTERNAL_VALUES_DUPLICATE");
        // …and the law is about the ENUM's values, never the category's tokens: A12 writes the enum's own
        // values into every category as `internalValue` and the tokens beside them as `categoryValue`, and
        // only the first is asked to be unique. A checker implementing the obvious reading would refuse this.
        expected.put("a repeated category TOKEN over distinct values", KernelLaws.ADMITTED);
        expected.put("a category holding FEWER tokens than the enum has values", "MVK_MISSING_CATEGORY_VALUE");
        // The two CUSTOM-CONDITION laws stay unmeasured rather than unreachable: neither markup nor a
        // control character reaches them, so what `ungueltigerXMLWert` and `ungueltigerKategorieWert`
        // actually refuse is unknown, and a checker must not guess a predicate it has not seen fire.
        expected.put("a value carrying a character XML cannot hold", KernelLaws.ADMITTED);
        expected.put("a category TOKEN carrying one", KernelLaws.ADMITTED);
        expected.put("a value carrying a control character XML forbids outright", KernelLaws.ADMITTED);

        assertEquals(expected, actual, "which Enumeration shape laws a stored A12 model can reach");
    }
}
