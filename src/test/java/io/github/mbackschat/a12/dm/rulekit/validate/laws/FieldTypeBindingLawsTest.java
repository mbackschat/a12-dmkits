package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
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
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

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

    /** A type name the kernel does not declare, so any carrier that READS the member is refused. */
    private static final String UNBINDABLE_TYPE = "{\"type\":\"NotAType\",\"NotAType\":{}}";

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

    /**
     * The member is a FIELD's, and a spurious one elsewhere does not make the model unreadable.
     *
     * <p>Written because a checker scanning every element for `fieldType` rather than every field is claiming
     * A12 reads it on every carrier — and if A12 ignores it there, such a checker reports a model that loads
     * as unloadable, which is the one direction that cannot be caught downstream. The rows use the type name
     * A12 does NOT have, so a carrier that DID read the member would be unreadable and unmistakable.
     *
     * <p><b>What this measures turns out to be a law about CARRIERS, not about this member.</b> A group
     * silently drops a member it does not declare while a field and a rule refuse one, and the
     * {@code notAMemberA12Declares} rows are what separate those two readings: a spelling that is nonsense
     * everywhere parts the carriers exactly as {@code fieldType} does. Two undeclared member names per
     * carrier, because one could not tell "refuses undeclared members" from "refuses that one spelling".
     * The denominator is three of the element kinds — the computation carrier is not measured here, so the
     * law is stated for Group, Field and Rule and claims nothing beyond them.
     */
    @Test
    void aFieldTypeOnAnElementThatIsNotAFieldIsIgnored() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("on a FIELD — the control that proves the fragment is refusable",
                verdictWith("{\"type\":\"NotAType\",\"NotAType\":{}}"));
        actual.put("on a group", verdictOnElementBody("ShippingAddress", "Group", UNBINDABLE_TYPE));
        actual.put("on a rule", verdictOnElementBody("TotalGate", "Rule", UNBINDABLE_TYPE));
        // The discriminator: a member name that is nonsense on EVERY carrier. If the two rows above part
        // because of `fieldType` itself, these agree; if they part because the carriers differ on unknown
        // members at all, these part the same way — which is what they do.
        actual.put("a nonsense member on a group", verdictOnElementBody("ShippingAddress", "Group", "1"));
        actual.put("a nonsense member on a rule", verdictOnElementBody("TotalGate", "Rule", "1"));
        // A SECOND nonsense name on each carrier, so the answer is about the carrier and not about one
        // spelling — one name per carrier could not tell "refuses unknown members" from "refuses this one".
        actual.put("a second nonsense member on a group",
                verdictOnElementBody("ShippingAddress", "Group", "\"x\""));
        actual.put("a second nonsense member on a rule", verdictOnElementBody("TotalGate", "Rule", "\"x\""));
        // And the FIELD carrier, which the family reads and whose strictness therefore matters most here.
        actual.put("a nonsense member on a field", verdictOnFieldBody("CustomerName", "1"));
        actual.put("a second nonsense member on a field", verdictOnFieldBody("CustomerName", "\"x\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("on a FIELD — the control that proves the fragment is refusable", KernelLaws.UNREADABLE);
        // A GROUP drops a member it does not declare, so `fieldType` means nothing there and a checker
        // scanning every element would refuse a model A12 loads.
        expected.put("on a group", KernelLaws.ADMITTED);
        // A RULE refuses one — and the nonsense rows below show that is the CARRIER's strictness rather than
        // anything about this member, so the checker's silence here under-reports a general law and not a
        // `fieldType` one. Measured on three of the element kinds with two undeclared member names each;
        // the computation carrier is NOT measured, so the law as stated covers Group, Field and Rule.
        expected.put("on a rule", KernelLaws.UNREADABLE);
        expected.put("a nonsense member on a group", KernelLaws.ADMITTED);
        expected.put("a nonsense member on a rule", KernelLaws.UNREADABLE);
        expected.put("a second nonsense member on a group", KernelLaws.ADMITTED);
        expected.put("a second nonsense member on a rule", KernelLaws.UNREADABLE);
        expected.put("a nonsense member on a field", KernelLaws.UNREADABLE);
        expected.put("a second nonsense member on a field", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "a fieldType written on an element that is not a field");
    }

    /**
     * The POSITIVE control for the five types an empty body defeats: a body that supplies what they need.
     *
     * <p>Without these rows "an empty body leaves the model unbuildable" and "a body of this type leaves the
     * model unbuildable" are the same measurement, and a checker keying on the latter refuses every field
     * using one of these types — an over-rejection, the one direction that cannot be caught downstream.
     * Measured as a mutant: widening the checker's test from an empty body to any body survives until these
     * rows exist.
     *
     * <p>The bodies are minimal rather than realistic, and the members come from the kernel's own
     * declarations — each of these five carries a {@code @NonNull} member with no default
     * ({@code format}, {@code formatOfFragment}, {@code id}, {@code name}), except {@code EnumerationType},
     * whose {@code values} DOES default and which therefore fails for some other reason. That asymmetry is
     * why the checker keys on the measured outcome and never on a read of which member is required.
     */
    @Test
    void theTypesAnEmptyBodyDefeatsAreFineWithABodyThatSuppliesWhatTheyNeed() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DateRangeType with a format only",
                verdictWith("{\"type\":\"DateRangeType\",\"DateRangeType\":{\"format\":\"dd.MM.yyyy\"}}"));
        actual.put("DateRangeType with a format and a separator", verdictWith(
                "{\"type\":\"DateRangeType\",\"DateRangeType\":"
                        + "{\"format\":\"dd.MM.yyyy\",\"rangeSeparator\":\"-\"}}"));
        actual.put("EnumerationType with one value", verdictWith(
                "{\"type\":\"EnumerationType\",\"EnumerationType\":{\"values\":[{\"value\":\"A\"}]}}"));
        actual.put("DateFragmentType with a fragment format", verdictWith(
                "{\"type\":\"DateFragmentType\",\"DateFragmentType\":{\"formatOfFragment\":\"dd.MM.\"}}"));
        actual.put("CustomFieldType with a name",
                verdictWith("{\"type\":\"CustomFieldType\",\"CustomFieldType\":{\"name\":\"Signature\"}}"));
        actual.put("TypeDefType with an id",
                verdictWith("{\"type\":\"TypeDefType\",\"TypeDefType\":{\"id\":\"NoSuchTypeDef\"}}"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The separator's field carries a default in the kernel's own declaration and the default does NOT
        // survive this path, so a format alone is still not a buildable range. A source reading of the
        // defaults would have predicted the opposite, which is why these rows are measured.
        expected.put("DateRangeType with a format only", KernelLaws.NOT_BUILDABLE);
        expected.put("DateRangeType with a format and a separator", KernelLaws.ADMITTED);
        expected.put("EnumerationType with one value", KernelLaws.ADMITTED);
        expected.put("DateFragmentType with a fragment format", KernelLaws.ADMITTED);
        expected.put("CustomFieldType with a name", KernelLaws.ADMITTED);
        // NOT a positive control, and kept because it names a class this checker does not cover: an `id`
        // naming no declared type definition refuses A12's READER. A buildable `TypeDefType` needs a type
        // definition the model declares, so this type has no positive row here and the checker's silence
        // about a dangling id is an under-report the report's gap now names. Why the reader refuses rather
        // than failing to build is unmeasured.
        expected.put("TypeDefType with an id", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "the five types with a body supplying what they construct from");
    }

    /**
     * A field declared with NO {@code fieldType} leaves a model A12 cannot build — the within-model control.
     *
     * <p>The envelope grid already has this row by REMOVING the member from a fixture field, which varies one
     * thing but only on a field the fixture had already shaped. This adds a field that did not exist and
     * varies only whether it is given a type, so the verdict cannot be about anything the fixture did to
     * {@code CustomerName}. It is the row a corpus-wide consequence rests on: if it holds, every model whose
     * fields omit the member is one A12 refuses to build, test fixtures included.
     */
    @Test
    void aFieldWithNoDeclaredTypeLeavesAModelA12CannotBuild() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an added field WITH a type — the control",
                verdictWithAddedField("{\"fieldType\":{\"type\":\"StringType\"}}"));
        actual.put("the same field with no fieldType member", verdictWithAddedField("{}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an added field WITH a type — the control", KernelLaws.ADMITTED);
        expected.put("the same field with no fieldType member", KernelLaws.NOT_BUILDABLE);
        assertEquals(expected, actual, "a field declared without any fieldType");
    }

    /** The order fixture with one extra root field whose body is [rawBody] verbatim. */
    private static String verdictWithAddedField(String rawBody) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            ObjectNode added = MAPPER.createObjectNode();
            added.put("type", "Field");
            added.put("id", "/Order/SpareNote");
            added.put("name", "SpareNote");
            added.set("Field", MAPPER.readTree(rawBody));
            ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).add(added);
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw body must parse", unreadable);
        }
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

    /**
     * The order fixture with an unbindable {@code fieldType} written onto a non-field element of [kind].
     *
     * <p>The rule carrier is ADDED by the writer rather than found, so the model stays one A12 accepts apart
     * from the member under test.
     */
    private static String verdictOnElementBody(String name, String kind, String rawMember) {
        String base = "Rule".equals(kind)
                ? ModelWriter.addRule(Fixtures.orderJson(), "/Order", name, "../CustomerName", "PROBE",
                        "FieldNotFilled(CustomerName)", "ERROR", List.of(en("x"), de("y")))
                : Fixtures.orderJson();
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(base);
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if (name.equals(element.path("name").asText()) && element.has(kind)) {
                    ((ObjectNode) element.path(kind)).set(
                            UNBINDABLE_TYPE.equals(rawMember) ? "fieldType" : "notAMemberA12Declares",
                            MAPPER.readTree(rawMember));
                    return KernelLaws.verdictOf(root.toString());
                }
            }
            throw new IllegalStateException("the fixture no longer declares a " + kind + " named " + name);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw type fragment must parse", unreadable);
        }
    }

    /** The order fixture with an undeclared member written onto the named root field's own body. */
    private static String verdictOnFieldBody(String name, String rawMember) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            fieldBody(root, name).set("notAMemberA12Declares", MAPPER.readTree(rawMember));
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw fragment must parse", unreadable);
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
