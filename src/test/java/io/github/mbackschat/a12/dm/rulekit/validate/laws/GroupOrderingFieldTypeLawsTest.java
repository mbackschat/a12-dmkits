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
 * What A12 demands of the FIELD a group's {@code indexFieldName} or {@code sortFields[].name} lands on —
 * a separate family from whether the spelling resolves at all, which
 * {@link GroupIndexAndSortReferenceLawsTest} owns.
 *
 * <p>The split is the Kernel's own: {@code NoMetaModelChecks.checkIndexField} runs three gates in order and
 * returns at the first that fires — resolution, then {@code FieldUtils.isNoValueValidation}, then
 * {@code FieldUtils.isTableEnum} — so a type verdict is only ever reached by a reference that already
 * resolved. Reporting them as one family would let a resolution fix read as a type approval.
 *
 * <p><b>The two sides are NOT symmetric in source, and the asymmetry turns out not to matter.</b>
 * {@code checkSortField} carries the no-value-validation gate and stops there, with no table-enumeration gate
 * at all — and the index member's extra gate cannot fire for a stored A12 model either, because the shape it
 * rejects is unreadable (the second test, with its four attacked links). So the family a checker owns here is
 * the no-value-validation PAIR, and {@code MVK_INDEX_FIELD_NO_TABLE_ENUM} is excluded on a cited dead path
 * rather than carried as an unmeasured silence.
 *
 * <p>Own domain (the shared order fixture: the repeatable {@code /Order/Items} keyed by {@code Sku}).
 */
class GroupOrderingFieldTypeLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A no-value-validation string is refused on BOTH members, each with its own code.
     *
     * <p>Moved here from the reference family on 2026-09-12 when the type gate became a family of its own;
     * the rows are unchanged.
     */
    @Test
    void aNoValueValidationStringIsRefusedOnBothMembers() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain string field — the control", verdictWith(null, "indexFieldName"));
        actual.put("a field declared noValueValidation", verdictWith(NO_VALUE_VALIDATION, "indexFieldName"));
        actual.put("the same, as a sort field", verdictWith(NO_VALUE_VALIDATION, "sortFields"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain string field — the control", KernelLaws.ADMITTED);
        expected.put("a field declared noValueValidation", "MVK_INDEX_FIELD_NO_VALUE_VALIDATION");
        expected.put("the same, as a sort field", "MVK_SORT_FIELD_NO_VALUE_VALIDATION");
        assertEquals(expected, actual, "a no-value-validation string as an ordering field");
    }

    /**
     * The index member's THIRD gate, {@code MVK_INDEX_FIELD_NO_TABLE_ENUM}, cannot fire for a stored A12
     * model, because the shape it rejects cannot be authored: the reader refuses the table reference itself.
     *
     * <p>This row is the executable half of a reachability claim, so the other links are named rather than
     * assumed, each attacked (kernel `30.8.1`, source-read 2026-09-12):
     *
     * <ul>
     * <li>the DM-JSON binding — {@code md.model.a12internal.fieldtypes.EnumerationType} declares
     *     {@code values}, {@code categories}, {@code errorMessage}, {@code alphabeticalSorting} and
     *     {@code sValue}, and no table member at all. That is what this test MEASURES;</li>
     * <li>the construction path — {@code TypeConverter.convertEnumeration} builds
     *     {@code EnumerationTypeImpl(errorMessages, values, categoryValues, xmlValues, sValue)}, whose
     *     constructor takes no table argument, so no caller could supply one;</li>
     * <li>the accessor {@code FieldUtils.isTableEnum} reads — {@code EnumerationTypeImpl.getTableName()}
     *     returns {@code Optional.empty()} unconditionally, with no branch;</li>
     * <li>the denominator — 23 files import {@code core.tool.a12internal.api.ado.IEnumerationType} and
     *     exactly ONE implements it, that same class. The one name that reads like a counterexample,
     *     {@code CodeGenEnumerationTypeImpl}, extends {@code CodeGenFeldTypBasis} and DELEGATES to an
     *     enumeration rather than implementing the interface; and {@code EnumerationTypeWrapper} implements
     *     the identically-named {@code md.model.api.fieldtypes.IEnumerationType}, a different interface in a
     *     different layer, which never reaches {@code FieldUtils}.</li>
     * </ul>
     *
     * <p>The second row is the control that keeps the first from proving the wrong thing: an ordinary
     * value-list enumeration is perfectly readable, so the refusal above is about the TABLE member and not
     * about enumerations being unusable as ordering keys.
     */
    @Test
    void aTableReferencingEnumerationCannotBeAuthoredSoItsGateIsUnreachable() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an enumeration naming a value table, as the index",
                verdictWith(TABLE_ENUM, "indexFieldName"));
        actual.put("a plain value-list enumeration, as the index", verdictWith(VALUE_LIST_ENUM, "indexFieldName"));
        actual.put("an enumeration naming a value table, as a SORT field",
                verdictWith(TABLE_ENUM, "sortFields"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The READER refuses it — the model never reaches the gate, on either member.
        expected.put("an enumeration naming a value table, as the index", KernelLaws.UNREADABLE);
        expected.put("a plain value-list enumeration, as the index", KernelLaws.ADMITTED);
        expected.put("an enumeration naming a value table, as a SORT field", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "a table-referencing enumeration as an ordering field");
    }

    /** A no-value-validation string: both flags, because A12 refuses the first without the second. */
    private static final String NO_VALUE_VALIDATION =
            "{\"type\":\"StringType\",\"StringType\":{\"noValueValidation\":true,\"lineBreaksPermitted\":true}}";

    /** An enumeration naming a value table — what {@code FieldUtils.isTableEnum} keys on. */
    private static final String TABLE_ENUM =
            "{\"type\":\"EnumerationType\",\"EnumerationType\":{\"tableName\":\"Colours\"}}";

    /** The same kind of type with its values inline and no table named. */
    private static final String VALUE_LIST_ENUM =
            "{\"type\":\"EnumerationType\",\"EnumerationType\":{\"values\":[{\"value\":\"RED\"}]}}";

    /**
     * The order fixture with {@code /Order/Items/Sku} retyped to [fieldType] (or left alone when null) and
     * named by [member] as that group's ordering key.
     */
    private static String verdictWith(String fieldType, String member) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            ObjectNode items = groupBody(root, "Items");
            if (fieldType != null) {
                for (var element : items.path("elements")) {
                    if ("Sku".equals(element.path("name").asText())) {
                        ((ObjectNode) element.path("Field")).set("fieldType", MAPPER.readTree(fieldType));
                    }
                }
            }
            // The fixture already keys this group by `Sku`, so leaving that in place would fire the INDEX
            // gate beside the sort one and measure both at once.
            items.remove("indexFieldName");
            items.set(member, MAPPER.readTree(
                    "sortFields".equals(member) ? "[{\"name\":\"Sku\"}]" : "\"Sku\""));
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw type fragment must parse", unreadable);
        }
    }

    /** The named group's body, refusing rather than returning a model the substitution never reached. */
    private static ObjectNode groupBody(ObjectNode root, String name) {
        for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if (name.equals(element.path("name").asText())) {
                return (ObjectNode) element.path("Group");
            }
        }
        throw new IllegalStateException("the fixture no longer declares the group `" + name + "`");
    }
}
