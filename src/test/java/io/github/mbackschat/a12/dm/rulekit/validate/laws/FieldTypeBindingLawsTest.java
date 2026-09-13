package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * Which {@code fieldType} shapes A12's reader BINDS, measured on a field nothing else references.
 *
 * <p>{@link GroupOrderingFieldTypeLawsTest} asks what a field's type does to a group that ORDERS by it; this
 * asks the prior question, whether the declaration binds at all. The two are separated on purpose: every row
 * there sits on {@code /Order/Items/Sku}, which is named by an {@code indexFieldName} or a {@code sortFields}
 * entry, so a refusal measured there cannot distinguish "this type is unbindable" from "this type is
 * unusable as an ordering key". Here the carrier is {@code /Order/CustomerName}, a plain root field no
 * declaration names.
 *
 * <p><b>The denominator is DERIVED, not assembled.</b> {@code FieldtypeDeserializer.deriveFieldtype} is one
 * {@code switch} over the {@code type} discriminator with thirteen cases and a {@code default} that raises,
 * so the admitted set is exactly those thirteen names and nothing else can be inferred into it. <i>Source:</i>
 * {@code kernel-md/kernel-md-serializer/…/serialization/FieldtypeDeserializer.java}, kernel {@code 30.8.1},
 * read 2026-09-13. That enumeration is what makes an "unknown type name" row safe to state as a law rather
 * than as a probe of the handful of names someone happened to try.
 *
 * <p><b>The split the source predicts, and the reason this grid exists.</b> Five of the thirteen cases guard
 * the body — {@code if (typeNode == null) …builder().build()} — and the other eight dereference it behind a
 * {@code // typeNode is never null} comment. A comment is not a guard, so the prediction is that the body is
 * OPTIONAL on those five and FATAL to omit on the other eight. It is a source reading and stays a hypothesis
 * until this grid runs: the two halves are indistinguishable from any single type, and the one row that
 * existed before this class ({@code GroupOrderingFieldTypeLawsTest}'s "a type discriminator naming a body
 * that is absent") sat on {@code StringType} — a guarded case — so it measured one side of a split it could
 * not see.
 *
 * <p>Own domain (the shared order fixture).
 */
class FieldTypeBindingLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The thirteen names {@code deriveFieldtype} switches on, in source order. */
    private static final List<String> DECLARED_TYPES = List.of(
            "StringType", "NumberType", "DateType", "DateTimeType", "DateRangeType", "DateFragmentType",
            "EnumerationType", "TimeType", "TypeDefType", "CustomFieldType", "ConfirmType", "BooleanType",
            "UnspecifiedType");

    /**
     * The ENVELOPE around the discriminator: what a {@code fieldType} that is not a usable object does.
     *
     * <p>The absent-member row is the control that gives the others their meaning. A {@code fieldType} object
     * with no {@code type} makes the deserializer return {@code null}, which is the same value the field
     * carries when the member is absent altogether — so if those two rows AGREE, the empty object is simply
     * an untyped field, and if they DISAGREE the null hypothesis is wrong and something downstream
     * distinguishes them. One row settles which, and it cannot be reasoned out from the deserializer alone.
     */
    @Test
    void whatAFieldTypeThatIsNotAUsableObjectDoes() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain string type — the control", verdictWith("{\"type\":\"StringType\"}"));
        actual.put("no fieldType member at all", verdictWith(null));
        actual.put("an EMPTY fieldType object", verdictWith("{}"));
        actual.put("a fieldType object with only a body, no discriminator",
                verdictWith("{\"StringType\":{}}"));
        actual.put("a type name A12 does not have", verdictWith("{\"type\":\"NotAType\",\"NotAType\":{}}"));
        actual.put("the discriminator in the wrong case", verdictWith("{\"type\":\"stringtype\"}"));
        actual.put("fieldType as text", verdictWith("\"StringType\""));
        actual.put("fieldType as a number", verdictWith("7"));
        actual.put("fieldType as a list", verdictWith("[{\"type\":\"StringType\"}]"));
        actual.put("fieldType as null", verdictWith("null"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain string type — the control", KernelLaws.ADMITTED);
        // THREE tiers, not two, and the answer refutes the reading the deserializer alone suggests. An
        // absent member and an empty object both leave the field with no type, so "they are the same null"
        // predicts one verdict for both — and they part. What separates them is that `Field.fieldType` is
        // Lombok `@NonNull`, so its SETTER raises: an empty object and an explicit null are deserialized to
        // null and then set, while an absent member never calls the setter and survives reading, to fail one
        // tier later as A12 builds. Confirmed by all three rows moving exactly as that account predicts.
        expected.put("no fieldType member at all", KernelLaws.NOT_BUILDABLE);
        expected.put("an EMPTY fieldType object", KernelLaws.UNREADABLE);
        expected.put("a fieldType object with only a body, no discriminator", KernelLaws.UNREADABLE);
        expected.put("a type name A12 does not have", KernelLaws.UNREADABLE);
        // The `switch` is on the raw string, so the discriminator is case-SENSITIVE — it falls to the
        // raising default like any other name the kernel does not declare.
        expected.put("the discriminator in the wrong case", KernelLaws.UNREADABLE);
        expected.put("fieldType as text", KernelLaws.UNREADABLE);
        expected.put("fieldType as a number", KernelLaws.UNREADABLE);
        expected.put("fieldType as a list", KernelLaws.UNREADABLE);
        expected.put("fieldType as null", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "what a fieldType that is not a usable typed object does");
    }

    /**
     * Every declared type name carrying an empty body — the control half of the body-optional split.
     *
     * <p>Every one of the thirteen BINDS here, which is what makes this the control: no row is unreadable, so
     * the unreadable rows in the paired test below are attributable to the missing body and to nothing else.
     * What the row then says about the model is a separate question and the verdicts fan out — five types
     * need members an empty body does not supply and leave the model unbuildable, and {@code UnspecifiedType}
     * binds and draws a consistency code of its own. Neither is a binding failure, and a checker that treated
     * them as one would report the reader's refusal for a model A12 read perfectly well.
     */
    @Test
    void everyDeclaredTypeBindsWithAnEmptyBody() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String type : DECLARED_TYPES) {
            actual.put(type, verdictWith("{\"type\":\"" + type + "\",\"" + type + "\":{}}"));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("StringType", KernelLaws.ADMITTED);
        expected.put("NumberType", KernelLaws.ADMITTED);
        // A format is OPTIONAL on all three temporal types — the empty body is enough to build a model.
        expected.put("DateType", KernelLaws.ADMITTED);
        expected.put("DateTimeType", KernelLaws.ADMITTED);
        expected.put("TimeType", KernelLaws.ADMITTED);
        expected.put("ConfirmType", KernelLaws.ADMITTED);
        expected.put("BooleanType", KernelLaws.ADMITTED);
        // Read, then unbuildable: these five carry a member A12 constructs from and an empty body has none.
        expected.put("DateRangeType", KernelLaws.NOT_BUILDABLE);
        expected.put("DateFragmentType", KernelLaws.NOT_BUILDABLE);
        expected.put("EnumerationType", KernelLaws.NOT_BUILDABLE);
        expected.put("TypeDefType", KernelLaws.NOT_BUILDABLE);
        expected.put("CustomFieldType", KernelLaws.NOT_BUILDABLE);
        // Bound, built, and then REFUSED by a consistency check — the one type that reaches a verdict at all.
        expected.put("UnspecifiedType", "MVK_UNSPECIFIED_TYPE_NO_CODEGEN");
        assertEquals(sortedByName(expected), sortedByName(actual),
                "every declared field type with an empty body present");
    }

    /**
     * Every declared type name with the body member ABSENT — the half the source says splits five from eight.
     *
     * <p>This is the discriminating grid. Paired row by row with the one above, it varies exactly one thing —
     * whether the body member is written — so a difference between the two cannot be the type itself.
     */
    @Test
    void onlyTheGuardedTypesBindWithoutTheirBody() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String type : DECLARED_TYPES) {
            actual.put(type, verdictWith("{\"type\":\"" + type + "\"}"));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        // The five cases that guard the null and fall back to a default instance. All five BIND — which is
        // the claim under test; `UnspecifiedType` then draws its own consistency code, exactly as it does
        // with a body present, so the code is a property of the type and not of the omission.
        expected.put("StringType", KernelLaws.ADMITTED);
        expected.put("NumberType", KernelLaws.ADMITTED);
        expected.put("ConfirmType", KernelLaws.ADMITTED);
        expected.put("BooleanType", KernelLaws.ADMITTED);
        expected.put("UnspecifiedType", "MVK_UNSPECIFIED_TYPE_NO_CODEGEN");
        // The eight that dereference the body behind a comment claiming it is never null.
        expected.put("DateType", KernelLaws.UNREADABLE);
        expected.put("DateTimeType", KernelLaws.UNREADABLE);
        expected.put("DateRangeType", KernelLaws.UNREADABLE);
        expected.put("DateFragmentType", KernelLaws.UNREADABLE);
        expected.put("EnumerationType", KernelLaws.UNREADABLE);
        expected.put("TimeType", KernelLaws.UNREADABLE);
        expected.put("TypeDefType", KernelLaws.UNREADABLE);
        expected.put("CustomFieldType", KernelLaws.UNREADABLE);
        assertEquals(sortedByName(expected), sortedByName(actual),
                "every declared field type with its body member absent");
    }

    /** Compared name-ordered, because the expectations are grouped by the split rather than by source order. */
    private static Map<String, String> sortedByName(Map<String, String> rows) {
        return new java.util.TreeMap<>(rows);
    }

    /**
     * The order fixture with {@code /Order/CustomerName}'s {@code fieldType} replaced by [rawFieldType], or
     * the member removed entirely when it is null.
     */
    private static String verdictWith(String rawFieldType) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            ObjectNode field = fieldBody(root, "CustomerName");
            if (rawFieldType == null) {
                field.remove("fieldType");
            } else {
                field.set("fieldType", MAPPER.readTree(rawFieldType));
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw type fragment must parse", unreadable);
        }
    }

    /** The named root field's body, refusing rather than returning a model the substitution never reached. */
    private static ObjectNode fieldBody(ObjectNode root, String name) {
        for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if (name.equals(element.path("name").asText()) && element.has("Field")) {
                return (ObjectNode) element.path("Field");
            }
        }
        throw new IllegalStateException("the fixture no longer declares the root field `" + name + "`");
    }
}
