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
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * {@code switch} over the {@code type} discriminator with twelve cases and a {@code default} that raises,
 * so the admitted set is exactly those twelve names and nothing else can be inferred into it. <i>Source:</i>
 * {@code kernel-md/kernel-md-serializer/…/serialization/FieldtypeDeserializer.java}, kernel {@code 31.1.1}
 * ({@code a12-kernel} eec9134), re-read 2026-11-02. {@code UnspecifiedType} left the switch in 31.1, so it is a
 * name A12 no longer reads. That enumeration is what makes an "unknown type name" row safe to state as a law
 * rather than as a probe of the handful of names someone happened to try.
 *
 * <p><b>The body is optional on every type in 31.1.1.</b> Each case builds the type from its defaults when the
 * body member is absent ({@code DateRangeType.builder().format("")}, {@code EnumerationType…values(List.of())},
 * …), so a missing body draws what an empty one draws, row for row (KERNEL-FINDINGS KF654). On 30.8.1 eight of the
 * thirteen dereferenced the missing body and refused the source.
 *
 * <p>Own domain (the shared order fixture).
 */
class FieldTypeBindingLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A type name the kernel does not declare, so any carrier that READS the member is refused. */
    private static final String UNBINDABLE_TYPE = "{\"type\":\"NotAType\",\"NotAType\":{}}";

    /** The twelve names {@code deriveFieldtype} switches on, in source order. */
    private static final List<String> DECLARED_TYPES = List.of(
            "StringType", "NumberType", "DateType", "DateTimeType", "DateRangeType", "DateFragmentType",
            "EnumerationType", "TimeType", "TypeDefType", "CustomFieldType", "ConfirmType", "BooleanType");

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
     * <p>Every one of the twelve BINDS here: no row is unreadable. What the row then says about the model is a
     * separate question and the verdicts fan out — some types need members an empty body does not supply. None
     * is a binding failure, and a checker that treated them as one would report the reader's refusal for a model
     * A12 read perfectly well.
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
        // These two carry a member A12 constructs from and an empty body has none — read, then refused
        // BEFORE any declaration is judged.
        expected.put("DateFragmentType", KernelLaws.NOT_BUILDABLE);
        expected.put("EnumerationType", KernelLaws.NOT_BUILDABLE);
        // A type definition reference naming nothing is a converter sentence on 31.1.1, beside the basic one
        // (KERNEL-FINDINGS KF652); 30.8.1 did not build the model.
        expected.put("TypeDefType", KernelLaws.REFUSED_CODELESS);
        // …and the fifth is a DIFFERENT tier, which the harness could not tell apart until 2026-09-15: A12
        // judges the declaration and states the verdict in prose, `"The date range format is invalid."`, so
        // the model is built and refused rather than never built. A grid that collapses the two reports one
        // mechanism where there are two.
        expected.put("DateRangeType", KernelLaws.REFUSED_CODELESS);
        // A verdict, not a build refusal: its own class key carries no `MVK_` prefix, which the harness
        // dropped until it started reading the class off the notification.
        expected.put("CustomFieldType", "CUSTOM_FIELD_TYPE_CODENAME_MISSING");
        assertEquals(sortedByName(expected), sortedByName(actual),
                "every declared field type with an empty body present");
    }

    /**
     * Every declared type name with the body member ABSENT, paired row by row with the grid above.
     *
     * <p>It varies exactly one thing, whether the body member is written, and on 31.1.1 no row differs: the
     * deserializer builds a missing body from the type's defaults. On 30.8.1 eight of the thirteen refused it.
     */
    @Test
    void aMissingBodyDrawsWhatAnEmptyOneDraws() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String type : DECLARED_TYPES) {
            actual.put(type, verdictWith("{\"type\":\"" + type + "\"}"));
        }

        Map<String, String> emptyBody = new LinkedHashMap<>();
        for (String type : DECLARED_TYPES) {
            emptyBody.put(type, verdictWith("{\"type\":\"" + type + "\",\"" + type + "\":{}}"));
        }
        assertEquals(sortedByName(emptyBody), sortedByName(actual),
                "every declared field type with its body member absent, against the same with an empty body");
    }

    /**
     * The member is a FIELD's, and a spurious one elsewhere does not make the model unreadable.
     *
     * <p>Written because a checker scanning every element for `fieldType` rather than every field is claiming
     * A12 reads it on every carrier — and if A12 ignores it there, such a checker reports a model that loads
     * as unloadable, which is the one direction that cannot be caught downstream. The rows use the type name
     * A12 does NOT have, so a carrier that DID read the member would be unreadable and unmistakable.
     *
     * <p><b>What this measures is a law about CARRIERS, not about this member.</b> On kernel 31.1.1 every carrier
     * passes over a member it does not declare — a group, a field and a rule alike — where 30.8.1's field and rule
     * refused one (KERNEL-FINDINGS KF654). The nonsense rows separate that reading from one about {@code fieldType}
     * itself, two names per carrier so the answer is not about one spelling. The computation carrier is not
     * measured here, so the law is stated for Group, Field and Rule and claims nothing beyond them.
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
        // Every carrier passes over a member it does not declare, so `fieldType` means nothing off a field and a
        // checker scanning every element would refuse a model A12 loads.
        expected.put("on a group", KernelLaws.ADMITTED);
        expected.put("on a rule", KernelLaws.ADMITTED);
        expected.put("a nonsense member on a group", KernelLaws.ADMITTED);
        expected.put("a nonsense member on a rule", KernelLaws.ADMITTED);
        expected.put("a second nonsense member on a group", KernelLaws.ADMITTED);
        expected.put("a second nonsense member on a rule", KernelLaws.ADMITTED);
        expected.put("a nonsense member on a field", KernelLaws.ADMITTED);
        expected.put("a second nonsense member on a field", KernelLaws.ADMITTED);
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
        // survive this path, so a format alone still draws A12's codeless verdict on the pair. A source
        // reading of the defaults would have predicted the opposite, which is why these rows are measured.
        expected.put("DateRangeType with a format only", KernelLaws.REFUSED_CODELESS);
        expected.put("DateRangeType with a format and a separator", KernelLaws.ADMITTED);
        expected.put("EnumerationType with one value", KernelLaws.ADMITTED);
        expected.put("DateFragmentType with a fragment format", KernelLaws.ADMITTED);
        expected.put("CustomFieldType with a name", KernelLaws.ADMITTED);
        // NOT a positive control: the wire member is `typeDefinitionId`, so `id` is an unknown member the reader
        // passes over, and the reference is then empty — a converter sentence beside the basic one (KF652, KF654).
        expected.put("TypeDefType with an id", KernelLaws.REFUSED_CODELESS);
        assertEquals(expected, actual, "the five types with a body supplying what they construct from");
    }

    /**
     * What a {@code TypeDefType}'s {@code id} must name, and which of two readings the refusal belongs to.
     *
     * <p><b>The earlier probe measured something else entirely.</b> It wrote {@code {"id":"NoSuchTypeDef"}}
     * and read the UNREADABLE result as "a dangling reference refuses the reader". Both halves were wrong:
     * the wire member is {@code typeDefinitionId} — {@code id} is only what the Java field is called — so
     * that probe was measuring an UNKNOWN member on a field body, which this carrier refuses whatever it
     * spells. The member name came from the kernel's Java class; the one that settles it came from a real
     * model ({@code a12-kernel-mm}'s selection model), which is the oracle for a wire name.
     *
     * <p>With the right member, a dangling reference is read and fails to BUILD, matching the source:
     * {@code EntityReferenceResolverVisitor.visitField} resolves the id against {@code content.typeDefinitions}
     * and deliberately sets the reference to null when it cannot, rather than raising. The declared-definition
     * row is the control that makes the rest attributable.
     */
    @Test
    void aTypeDefTypeIdIsResolvedAgainstTheModelsOwnTypeDefinitions() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("naming a DECLARED type definition", verdictWithTypeDef("Money", "Money"));
        actual.put("naming nothing, with a type definition declared",
                verdictWithTypeDef("Money", "NoSuchTypeDef"));
        actual.put("naming nothing, with none declared", verdictWithTypeDef(null, "NoSuchTypeDef"));
        // The member is `typeDefinitionId`; `id` is what the Java field is called and is NOT the wire name.
        actual.put("the reference written as `id` instead", KernelLaws.verdictOf(withRawTypeDef(
                "{\"type\":\"TypeDefType\",\"TypeDefType\":{\"id\":\"Money\"}}", "Money")));
        actual.put("no reference member at all", KernelLaws.verdictOf(withRawTypeDef(
                "{\"type\":\"TypeDefType\",\"TypeDefType\":{}}", "Money")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("naming a DECLARED type definition", KernelLaws.ADMITTED);
        // A dangling reference is read and stated by the converter on 31.1.1 (KERNEL-FINDINGS KF652); 30.8.1 did
        // not build the model.
        expected.put("naming nothing, with a type definition declared", KernelLaws.REFUSED_CODELESS);
        expected.put("naming nothing, with none declared", KernelLaws.REFUSED_CODELESS);
        // `id` is an unknown member the reader passes over (KF654), so the reference is empty, as when absent.
        expected.put("the reference written as `id` instead", KernelLaws.REFUSED_CODELESS);
        expected.put("no reference member at all", KernelLaws.REFUSED_CODELESS);
        assertEquals(expected, actual, "what a TypeDefType's typeDefinitionId must name");
    }

    /**
     * The order fixture with {@code CustomerName} typed as a {@code TypeDefType} naming [referencedId], and
     * optionally a {@code content.typeDefinitions} entry declaring [declaredId].
     */
    private static String verdictWithTypeDef(String declaredId, String referencedId) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            fieldBody(root, "CustomerName").set("fieldType", MAPPER.readTree(
                    "{\"type\":\"TypeDefType\",\"TypeDefType\":{\"typeDefinitionId\":\""
                            + referencedId + "\"}}"));
            if (declaredId != null) {
                ((ObjectNode) root.path("content")).set("typeDefinitions", MAPPER.readTree(
                        "[{\"id\":\"" + declaredId + "\",\"name\":\"" + declaredId
                                + "\",\"fieldType\":{\"type\":\"StringType\"}}]"));
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw fragments must parse", unreadable);
        }
    }

    /** The order fixture with a raw {@code fieldType} fragment and one declared type definition. */
    private static String withRawTypeDef(String rawFieldType, String declaredId) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            fieldBody(root, "CustomerName").set("fieldType", MAPPER.readTree(rawFieldType));
            ((ObjectNode) root.path("content")).set("typeDefinitions", MAPPER.readTree(
                    "[{\"id\":\"" + declaredId + "\",\"name\":\"" + declaredId
                            + "\",\"fieldType\":{\"type\":\"StringType\"}}]"));
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw fragments must parse", unreadable);
        }
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

    /**
     * {@code UnspecifiedType}, which kernel 31.1.1's reader no longer declares, on a root and a nested field and
     * twice.
     *
     * <p>On 30.8.1 it bound and drew {@code MVK_UNSPECIFIED_TYPE_NO_CODEGEN} once per declaration; on 31.1.1 it is a
     * type name the deserializer's {@code default} refuses, wherever it stands (KERNEL-FINDINGS KF654).
     */
    @Test
    void theRetiredUnspecifiedTypeIsUnreadableOnEveryCarrier() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a ROOT field", verdictWithUnspecified(0));
        actual.put("a NESTED field", verdictWithUnspecifiedNested());
        actual.put("TWO unspecified root fields", verdictWithUnspecified(1));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a ROOT field", KernelLaws.UNREADABLE);
        expected.put("a NESTED field", KernelLaws.UNREADABLE);
        expected.put("TWO unspecified root fields", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "the retired unspecified type, on either carrier");
    }

    /** The order fixture with `CustomerName` retyped as unspecified, plus [extra] more such root fields. */
    private static String verdictWithUnspecified(int extra) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            fieldBody(root, "CustomerName").set("fieldType",
                    MAPPER.readTree("{\"type\":\"UnspecifiedType\",\"UnspecifiedType\":{}}"));
            for (int i = 0; i < extra; i++) {
                ObjectNode added = (ObjectNode) ((ArrayNode)
                        root.path("content").path("modelRoot").path("rootGroups").get(0)
                                .path("Group").path("elements")).addObject();
                added.put("type", "Field").put("id", "spare" + i).put("name", "Spare" + i);
                added.putObject("Field").set("fieldType",
                        MAPPER.readTree("{\"type\":\"UnspecifiedType\",\"UnspecifiedType\":{}}"));
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /** …and with the unspecified field one level down instead, which is the carrier the grids never move. */
    private static String verdictWithUnspecifiedNested() {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("ShippingAddress".equals(element.path("name").asText()) && element.has("Group")) {
                    for (var nested : element.path("Group").path("elements")) {
                        if ("Street".equals(nested.path("name").asText())) {
                            ((ObjectNode) nested.path("Field")).set("fieldType", MAPPER.readTree(
                                    "{\"type\":\"UnspecifiedType\",\"UnspecifiedType\":{}}"));
                            return KernelLaws.verdictOf(root.toString());
                        }
                    }
                }
            }
            throw new IllegalStateException("the fixture no longer declares `/Order/ShippingAddress/Street`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
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
            ((ArrayNode) root.path("content").path("modelRoot")
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
