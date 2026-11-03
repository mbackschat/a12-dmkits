package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import com.mgmtp.a12.kernel.md.model.api.services.DocumentModelExpansionException;

import io.github.mbackschat.a12.dm.adapter.preparation.WorkspacePreparationFixtures;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.ModelReader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** Measured refusal basis for structural include checks enforced by portable workspace preparation. */
class WorkspaceIncludeStructureKernelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void kernelAcceptsNullButRejectsOtherNonArrayModelReferenceContainers() {
        var acceptedNull = tree(DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.field("Reference", FieldKind.STRING))
                .build()
                .toJson());
        ((ObjectNode) acceptedNull.path("header"))
                .set("modelReferences", MAPPER.getNodeFactory().nullNode());
        KernelModelOracle.of(acceptedNull.toString(), reference -> null);

        var invalidValues = List.of(
                MAPPER.getNodeFactory().booleanNode(true),
                MAPPER.getNodeFactory().numberNode(7),
                MAPPER.getNodeFactory().textNode("not-references"),
                MAPPER.createObjectNode());
        for (var value : invalidValues) {
            var model = tree(DocumentModelDraft.named("permit").locale("en_US")
                    .rootGroup("Permit", permit -> permit.field("Reference", FieldKind.STRING))
                    .build()
                    .toJson());
            ((ObjectNode) model.path("header")).set("modelReferences", value);

            assertThrows(
                    RuntimeException.class,
                    () -> KernelModelOracle.of(model.toString(), reference -> null),
                    value.toString());
        }
    }

    @Test
    void kernelRejectsAnUnknownIncludeLevel() {
        var component = oneRootComponent();
        var valid = DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.mount(
                        "Details",
                        "component",
                        MountOptions.single().asModelRoot()))
                .build()
                .toJson();
        assertTrue(valid.contains("\"includeLevel\" : \"MODEL_ROOT\""), "the typed fixture carries includeLevel");
        var invalid = valid.replace("\"includeLevel\" : \"MODEL_ROOT\"", "\"includeLevel\" : \"UNKNOWN\"");

        assertThrows(JacksonException.class, () -> KernelModelOracle.of(
                invalid,
                reference -> "component".equals(reference) ? component : null));
    }

    @Test
    void kernelRejectsSingleLevelMountingOfMultipleRootGroups() {
        var component = DocumentModelDraft.named("component").locale("en_US")
                .rootGroup("Applicant", applicant -> applicant.field("Name", FieldKind.STRING))
                .rootGroup("Decision", decision -> decision.field("Status", FieldKind.STRING))
                .build()
                .toJson();
        var entry = DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.mount("Details", "component"))
                .build()
                .toJson();

        assertThrows(DocumentModelExpansionException.class, () -> KernelModelOracle.of(
                entry,
                reference -> "component".equals(reference) ? component : null));
    }

    @Test
    void kernelRejectsSingleLevelMountingOfARepeatableRootGroup() {
        var component = DocumentModelDraft.named("component").locale("en_US")
                .rootGroup("Component", group -> group
                        .repeatable(2)
                        .field("Description", FieldKind.STRING))
                .build()
                .toJson();
        var entry = DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.mount("Details", "component"))
                .build()
                .toJson();

        assertThrows(DocumentModelExpansionException.class, () -> KernelModelOracle.of(
                entry,
                reference -> "component".equals(reference) ? component : null));
    }

    @Test
    void kernelDefaultsAnAbsentSingleRootRepeatabilityToOne() {
        var component = tree(oneRootComponent());
        bodyOfRoot(component).remove("repeatability");

        var expanded = KernelModelOracle.of(
                entryModel(),
                reference -> "component".equals(reference) ? component.toString() : null);

        expanded.field("/Permit/Details/Description");
    }

    @Test
    void kernelRejectsMalformedSingleRootRepeatability() {
        var component = tree(oneRootComponent());
        bodyOfRoot(component).put("repeatability", "one");

        assertThrows(RuntimeException.class, () -> loadEntryWith(component));
    }

    @Test
    void kernelAcceptanceOfNonCanonicalSingleRootRepeatabilityIsCharacterized() {
        var acceptedValues = List.of(
                MAPPER.getNodeFactory().numberNode(new BigDecimal("0.5")),
                MAPPER.getNodeFactory().numberNode(new BigDecimal("1.1")),
                MAPPER.getNodeFactory().numberNode(new BigDecimal("1.5")),
                MAPPER.getNodeFactory().numberNode(new BigDecimal("1.9")),
                MAPPER.getNodeFactory().numberNode(new BigDecimal("-1.5")),
                MAPPER.getNodeFactory().numberNode(0),
                MAPPER.getNodeFactory().numberNode(-1),
                MAPPER.getNodeFactory().textNode("1"));
        for (var value : acceptedValues) {
            var component = tree(oneRootComponent());
            bodyOfRoot(component).set("repeatability", value);

            loadEntryWith(component).field("/Permit/Details/Description");
        }

        // A JSON null is refused by the reader on kernel 31: the member is a primitive int (KF593).
        var rejectedValues = List.of(
                MAPPER.getNodeFactory().nullNode(),
                MAPPER.getNodeFactory().booleanNode(false),
                MAPPER.getNodeFactory().booleanNode(true),
                MAPPER.createObjectNode(),
                MAPPER.createArrayNode(),
                MAPPER.getNodeFactory().numberNode(new BigDecimal("2.1")));
        for (var value : rejectedValues) {
            var component = tree(oneRootComponent());
            bodyOfRoot(component).set("repeatability", value);

            assertThrows(RuntimeException.class, () -> loadEntryWith(component), value.toString());
        }
    }

    /**
     * A {@code repeatability} written as a STRING binds as an INTEGER, which no number row can show.
     *
     * The number rows above are truncating — {@code 1.5} and {@code 1.9} are admitted and {@code 2.1} is not
     * — so the obvious reading is one lenient numeric coercion for both shapes. It is two: the string
     * {@code "1.5"} is refused by the READER ({@code ModelInputException}, the model never loads) while
     * {@code "2"} reaches the non-repeatable test and is refused by EXPANSION. The empty string bound as zero
     * on kernel 30.8.1 and is a reader refusal on 31.1.1, whose primitive {@code int} cannot hold a null (KF593).
     * Measured 2026-09-15 because a portable reader had generalized the number rule to strings and so
     * mounted a provider the Kernel refuses ({@code consistency.Preparation}).
     */
    @Test
    void kernelBindsAStringSingleRootRepeatabilityAsAnInteger() {
        var readable = List.of("1", " 1 ", "01", "0", "-1");
        for (var spelling : readable) {
            var component = tree(oneRootComponent());
            bodyOfRoot(component).put("repeatability", spelling);

            loadEntryWith(component).field("/Permit/Details/Description");
        }

        var unreadable = List.of("1.5", "1f", "NaN", "");
        for (var spelling : unreadable) {
            var component = tree(oneRootComponent());
            bodyOfRoot(component).put("repeatability", spelling);

            assertThrows(RuntimeException.class, () -> loadEntryWith(component), spelling);
        }

        var repeatable = tree(oneRootComponent());
        bodyOfRoot(repeatable).put("repeatability", "2");
        assertThrows(DocumentModelExpansionException.class, () -> loadEntryWith(repeatable),
                "a string that BINDS still meets the non-repeatable test, which is the second gate");
    }

    /**
     * A MODEL_ROOT include of a provider with no root group mounts nothing on kernel 31.1.1, which builds the entry
     * with the mount empty; kernel 30.8.1 refused the expansion. The consistency half is
     * {@code ElementBodyReadingDiffTest}'s.
     */
    @Test
    void kernelMountsNothingForAModelRootIncludeOfAProviderWithoutARootGroup() {
        var component = tree(oneRootComponent());
        ((ArrayNode) component.path("content").path("modelRoot").path("rootGroups")).removeAll();

        var loaded = KernelModelOracle.of(modelRootHost(false), reference -> component.toString());

        assertEquals(List.of(), loaded.group("/Permit/Details").getElements());
    }

    /**
     * An {@code attachment} group must carry the eight mandated attachment fields, so a marked MODEL_ROOT mount,
     * whose children are the provider's root GROUPS, can never be consistent (the expansion half of that mount is
     * {@code MountUsageTypeKernelTest}'s).
     */
    @Test
    void kernelRefusesAnAttachmentGroupThatDeclaresNoneOfItsMandatedFields() {
        var marker = tree(oneRootComponent());
        bodyOfRoot(marker).put("usageType", "attachment");

        // The contract is `checkConsistency`'s, not expansion's: the same model DESERIALIZES and expands.
        KernelModelOracle.ofSelfContained(marker.toString());
        var refused = assertThrows(RuntimeException.class, () -> BoundModel.ofJson(marker.toString()));

        assertTrue(refused.getMessage().contains("for attachment with group"), refused.getMessage());
    }

    /** The entry of the three MODEL_ROOT rows: a mount carrying `excludeRules`, optionally marked attachment. */
    private static String modelRootHost(boolean marked) {
        return modelRootHost(marked, true);
    }

    private static String modelRootHost(boolean marked, boolean excludeRules) {
        var options = MountOptions.single().asModelRoot();
        var entry = tree(DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.mount(
                        "Details", "component", excludeRules ? options.excludingRules() : options))
                .build()
                .toJson());
        if (marked) {
            ((ObjectNode) elementsOfRoot(entry).get(0).path("Group")).put("usageType", "attachment");
        }
        return entry.toString();
    }

    @Test
    void kernelRejectsSingleLevelMountingOfAnEmptyModelRoot() {
        var component = tree(oneRootComponent());
        ((ArrayNode) component.path("content").path("modelRoot").path("rootGroups")).removeAll();

        assertThrows(DocumentModelExpansionException.class, () -> loadEntryWith(component));
    }

    /**
     * A provider root's {@code Group} body that is not an object leaves no model, while a {@code null} one is an empty
     * group on kernel 31.1.1 ({@link #kernelReadsAMissingOrNullProviderRootBodyAsAnEmptyGroup}).
     */
    @Test
    void kernelRejectsEveryNonObjectProviderRootGroupBody() {
        var invalidValues = List.of(
                MAPPER.getNodeFactory().booleanNode(true),
                MAPPER.getNodeFactory().numberNode(7),
                MAPPER.getNodeFactory().textNode("not-a-group-body"),
                MAPPER.createArrayNode());
        for (var value : invalidValues) {
            var component = tree(oneRootComponent());
            ((ObjectNode) component.path("content").path("modelRoot").path("rootGroups").get(0))
                    .set("Group", value);

            assertThrows(RuntimeException.class, () -> loadEntryWith(component), value.toString());
        }
    }

    @Test
    void kernelRejectsEveryNonArrayProviderRootElementsMember() {
        var invalidValues = List.of(
                MAPPER.getNodeFactory().nullNode(),
                MAPPER.getNodeFactory().booleanNode(true),
                MAPPER.getNodeFactory().numberNode(7),
                MAPPER.getNodeFactory().textNode("not-elements"),
                MAPPER.createObjectNode());
        for (var value : invalidValues) {
            var component = tree(oneRootComponent());
            bodyOfRoot(component).set("elements", value);

            assertThrows(RuntimeException.class, () -> loadEntryWith(component), value.toString());
        }
    }

    @Test
    void kernelRejectsNonObjectAndUnknownIncludedElements() {
        var componentWithScalar = tree(oneRootComponent());
        elementsOfRoot(componentWithScalar).add("not-an-element");
        assertThrows(RuntimeException.class, () -> loadEntryWith(componentWithScalar));

        var componentWithUnknownType = tree(oneRootComponent());
        ((ObjectNode) elementsOfRoot(componentWithUnknownType).get(0)).put("type", "UnknownElement");
        assertThrows(RuntimeException.class, () -> loadEntryWith(componentWithUnknownType));
    }

    @Test
    void kernelRejectsAnUnknownIncludedRootType() {
        var component = tree(oneRootComponent());
        ((ObjectNode) component.path("content").path("modelRoot").path("rootGroups").get(0))
                .put("type", "UnknownElement");

        assertThrows(RuntimeException.class, () -> loadEntryWith(component));
    }

    @Test
    void kernelRejectsAKnownNonGroupRoot() {
        var fieldRoot = tree(oneRootComponent());
        var root = (ObjectNode) fieldRoot.path("content").path("modelRoot").path("rootGroups").get(0);
        var firstField = (ObjectNode) elementsOfRoot(fieldRoot).get(0);
        root.put("type", "Field");
        root.set("Field", firstField.path("Field").deepCopy());
        root.remove("Group");
        assertThrows(RuntimeException.class, () -> loadEntryWith(fieldRoot));
    }

    /**
     * Kernel 31.1.1 reads a provider root's missing or {@code null} {@code Group} body as an empty group, so the
     * SINGLE_RG mount holds nothing; kernel 30.8.1 refused both. Every other carrier and the three readers' agreement
     * are {@code ElementBodyReadingDiffTest}'s.
     */
    @Test
    void kernelReadsAMissingOrNullProviderRootBodyAsAnEmptyGroup() {
        var missingBody = tree(oneRootComponent());
        ((ObjectNode) missingBody.path("content").path("modelRoot").path("rootGroups").get(0)).remove("Group");
        var nullBody = tree(oneRootComponent());
        ((ObjectNode) nullBody.path("content").path("modelRoot").path("rootGroups").get(0))
                .set("Group", MAPPER.getNodeFactory().nullNode());

        for (var component : List.of(missingBody, nullBody)) {
            assertEquals(List.of(), loadEntryWith(component).group("/Permit/Details").getElements(), component.toString());
        }
    }

    @Test
    void kernelElementBodyAcceptanceIsCharacterizedAcrossTheClosedElementFamily() {
        var accepted = new ArrayList<String>();
        for (var type : List.of("Field", "Rule", "Computation")) {
            var missing = tree(executableOneRootComponent());
            elementOfType(missing, type).remove(type);
            recordAccepted(accepted, type + "=missing", missing);

            for (var value : List.of(
                    MAPPER.getNodeFactory().nullNode(),
                    MAPPER.getNodeFactory().booleanNode(true),
                    MAPPER.getNodeFactory().numberNode(7),
                    MAPPER.getNodeFactory().textNode("not-an-element-body"),
                    MAPPER.createArrayNode())) {
                var malformed = tree(executableOneRootComponent());
                elementOfType(malformed, type).set(type, value);

                recordAccepted(accepted, type + "=" + value, malformed);
            }
        }
        assertEquals(
                List.of(),
                accepted,
                "kernel 31.1.1 refuses every malformed body, an absent or null Field body included, which " +
                        "kernel 30.8.1 admitted as a default string field and portable preparation already refused");
    }

    @Test
    void kernelElementIdentityAcceptanceIsCharacterized() {
        var accepted = new ArrayList<String>();
        for (var member : List.of("type", "name", "id")) {
            var missing = tree(oneRootComponent());
            elementOfType(missing, "Field").remove(member);
            recordAccepted(accepted, member + "=missing", missing);

            for (var value : List.of(
                    MAPPER.getNodeFactory().nullNode(),
                    MAPPER.getNodeFactory().booleanNode(true),
                    MAPPER.getNodeFactory().numberNode(7),
                    MAPPER.createObjectNode(),
                    MAPPER.createArrayNode())) {
                var malformed = tree(oneRootComponent());
                elementOfType(malformed, "Field").set(member, value);

                recordAccepted(accepted, member + "=" + value, malformed);
            }
        }
        assertEquals(
                List.of(
                        "name=missing", "name=null", "name=true", "name=7",
                        "id=missing", "id=null", "id=true", "id=7"),
                accepted,
                "kernel 31.1.1 loads a missing, null or scalar name/id and refuses an object or array one, while a " +
                "missing or malformed type is refused; portable preparation requires a normalized string id");
    }

    @Test
    void elementShapedRecordsOutsideTheModelTreeNeverBecomeContent() {
        var headerModel = tree(oneRootComponent());
        var phantom = elementOfType(headerModel, "Field").deepCopy();
        phantom.put("name", "Phantom");
        phantom.put("id", "/Phantom");
        ((ObjectNode) headerModel.path("header")).set("unrecognizedElement", phantom);
        assertUnknownPropertyRejected(
                headerModel,
                "the Kernel rejects the unknown header member before it can become model content");

        var fieldModel = tree(oneRootComponent());
        var nestedPhantom = elementOfType(fieldModel, "Field").deepCopy();
        nestedPhantom.put("name", "NestedPhantom");
        nestedPhantom.put("id", "/NestedPhantom");
        ((ObjectNode) elementOfType(fieldModel, "Field").path("Field"))
                .set("unrecognizedElement", nestedPhantom);
        var loaded = KernelModelOracle.ofSelfContained(fieldModel.toString());
        assertEquals(List.of("/Component/Description"), loaded.fieldPaths("/Component").toList(),
                "kernel 31.1.1 ignores an unknown member of a real element body, so the record hidden there "
                        + "still never becomes model content");
    }

    private static void assertUnknownPropertyRejected(ObjectNode model, String message) {
        // Kernel 31.1.1 reads through Jackson 3, whose refusals are unchecked and reach the caller unwrapped.
        assertThrows(
                UnrecognizedPropertyException.class,
                () -> KernelModelOracle.ofSelfContained(model.toString()),
                message);
    }

    @Test
    void kernelRejectsNonObjectModelContainersAndNonArrayRootGroups() {
        var accepted = new ArrayList<String>();
        var missingContent = tree(oneRootComponent());
        missingContent.remove("content");
        recordAccepted(accepted, "content=missing", missingContent);

        var missingModelRoot = tree(oneRootComponent());
        ((ObjectNode) missingModelRoot.path("content")).remove("modelRoot");
        recordAccepted(accepted, "modelRoot=missing", missingModelRoot);

        var missingRootGroups = tree(oneRootComponent());
        ((ObjectNode) missingRootGroups.path("content").path("modelRoot")).remove("rootGroups");
        recordAccepted(accepted, "rootGroups=missing", missingRootGroups);

        var nonObjects = List.of(
                MAPPER.getNodeFactory().nullNode(),
                MAPPER.getNodeFactory().booleanNode(true),
                MAPPER.getNodeFactory().numberNode(7),
                MAPPER.getNodeFactory().textNode("not-an-object"),
                MAPPER.createArrayNode());
        for (var value : nonObjects) {
            var malformedContent = tree(oneRootComponent());
            malformedContent.set("content", value);
            recordAccepted(accepted, "content=" + value, malformedContent);

            var malformedModelRoot = tree(oneRootComponent());
            ((ObjectNode) malformedModelRoot.path("content")).set("modelRoot", value);
            recordAccepted(accepted, "modelRoot=" + value, malformedModelRoot);
        }
        for (var value : List.of(
                MAPPER.getNodeFactory().nullNode(),
                MAPPER.getNodeFactory().booleanNode(true),
                MAPPER.getNodeFactory().numberNode(7),
                MAPPER.getNodeFactory().textNode("not-an-array"),
                MAPPER.createObjectNode())) {
            var malformedRootGroups = tree(oneRootComponent());
            ((ObjectNode) malformedRootGroups.path("content").path("modelRoot")).set("rootGroups", value);
            recordAccepted(accepted, "rootGroups=" + value, malformedRootGroups);
        }

        assertEquals(
                List.of("modelRoot=missing", "rootGroups=missing"),
                accepted,
                "the Kernel treats an absent modelRoot/rootGroups as an empty model but requires a content object");
    }

    @Test
    void kernelDatePrecisionAcceptanceIsCharacterized() {
        var absent = tree(dateModel());
        dateTypeOfFirstField(absent).remove("datePrecision");
        KernelModelOracle.ofSelfContained(absent.toString()).field("/Permit/ObservedOn");

        for (var accepted : List.of("FULL", "DAY_OPTIONAL")) {
            var model = tree(dateModel());
            dateTypeOfFirstField(model).put("datePrecision", accepted);
            KernelModelOracle.ofSelfContained(model.toString()).field("/Permit/ObservedOn");
        }

        for (var value : List.of(
                MAPPER.getNodeFactory().nullNode(),
                MAPPER.getNodeFactory().textNode("UNKNOWN"),
                MAPPER.getNodeFactory().booleanNode(true),
                MAPPER.getNodeFactory().numberNode(7),
                MAPPER.createObjectNode(),
                MAPPER.createArrayNode())) {
            var model = tree(dateModel());
            dateTypeOfFirstField(model).set("datePrecision", value);

            assertThrows(
                    RuntimeException.class,
                    () -> KernelModelOracle.ofSelfContained(model.toString()),
                    value.toString());
        }
    }

    @Test
    void kernelRejectsAnUnknownFieldTypeDiscriminator() {
        var model = tree(oneRootComponent());
        ((ObjectNode) elementOfType(model, "Field").path("Field").path("fieldType"))
                .put("type", "UnknownFieldType");

        assertThrows(RuntimeException.class, () -> KernelModelOracle.ofSelfContained(model.toString()));
    }

    @Test
    void kernelRejectsAComputationTargetThatWalksAboveTheModelRoot() {
        var model = tree(executableOneRootComponent());
        ((ObjectNode) elementOfType(model, "Computation").path("Computation"))
                .put("computedFieldRelPath", "../../../../Result");

        assertThrows(RuntimeException.class, () -> KernelModelOracle.ofSelfContained(model.toString()));
    }

    /**
     * DM 29.4 binds the exclusion flags through A12's shared boolean law ([KF651](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf651)): the
     * JSON literals, an integer by whether it is zero, and six spellings after a Java {@code trim()}. Every other
     * value refuses the model. The reader must agree on every row, the bound ones by verdict and the rest by
     * refusing too; both outcomes are present, so a reader that dropped or kept every executable would not pass.
     */
    @Test
    void anExclusionFlagBindsByA12sBooleanLaw() {
        var component = executableOneRootComponent();
        ModelResolver resolver = reference -> "component".equals(reference) ? component : null;
        var nodes = MAPPER.getNodeFactory();
        var bound = new LinkedHashMap<JsonNode, String>();
        bound.put(nodes.booleanNode(true), "EXCLUDED");
        bound.put(nodes.textNode("true"), "EXCLUDED");
        bound.put(nodes.textNode(" True "), "EXCLUDED");
        bound.put(nodes.numberNode(1), "EXCLUDED");
        bound.put(nodes.booleanNode(false), "KEPT");
        bound.put(nodes.textNode("FALSE"), "KEPT");
        bound.put(nodes.numberNode(0), "KEPT");
        var refused = List.<JsonNode>of(nodes.textNode("yes"), nodes.numberNode(new BigDecimal("0.5")),
                nodes.nullNode(), MAPPER.createObjectNode(), MAPPER.createArrayNode());

        var kernel = new LinkedHashMap<String, String>();
        var reader = new LinkedHashMap<String, String>();
        var expected = new LinkedHashMap<String, String>();
        for (var executable : IncludedExecutable.values()) {
            for (var spelling : bound.entrySet()) {
                var entry = tree(entryModel());
                mountConfig(entry).set(executable.directive(), spelling.getKey());

                var observation = PreparationParityHarness.observe(entry.toString(), resolver);

                var row = executable.directive() + "=" + spelling.getKey();
                kernel.put(row, executable.verdictIn(observation.kernel()));
                reader.put(row, executable.verdictIn(observation.reader()));
                expected.put(row, spelling.getValue());
            }
            for (var spelling : refused) {
                var entry = tree(entryModel());
                mountConfig(entry).set(executable.directive(), spelling);
                var row = executable.directive() + "=" + spelling;
                assertThrows(RuntimeException.class, () -> KernelModelOracle.of(entry.toString(), resolver), row);
                assertThrows(ModelInputException.class, () -> ModelReader.expand(entry.toString(), resolver), row);
            }
        }

        assertEquals(expected, kernel, "the Kernel binds the flag by its boolean law");
        assertEquals(kernel, reader, "the JVM reader diverged from the Kernel on an exclusion flag");
    }

    @Test
    void kernelAndReaderExpandAnIncludeMountedAtTheDiscardedProviderRoot() {
        var leaf = oneRootComponent();
        var provider = EditSession.read(DocumentModelDraft.named("provider").locale("en_US")
                        .rootGroup("Placeholder", group -> {})
                        .build()
                        .toJson());
        provider.removeGroup("/Placeholder");
        provider.mount("", "ProviderRoot", "leaf");
        var providerJson = provider.toJson();
        var entry = DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.mount("Details", "provider"))
                .build()
                .toJson();
        var resolver = (io.github.mbackschat.a12.dm.core.ModelResolver) reference -> switch (reference) {
            case "provider" -> providerJson;
            case "leaf" -> leaf;
            default -> null;
        };

        var observation = PreparationParityHarness.observeElements(entry, resolver);

        PreparationParityHarness.assertElementsEquivalent(observation);
        assertTrue(observation.kernel().containsKey("/Permit/Details/Description"));
    }

    /** Kernel 31.1.1 replaces a mount group's local elements with the included content (KF651). */
    @Test
    void kernelReplacesLocalElementsBesideTheInclude() {
        var entry = tree(entryModel());
        var local = tree(DocumentModelDraft.named("permit-local").locale("en_US")
                .rootGroup("Permit", permit -> permit.group(
                        "Details",
                        details -> details.field("Extra", FieldKind.STRING)))
                .build()
                .toJson());
        mountBody(entry).set("elements", mountBody(local).path("elements").deepCopy());

        var expanded = KernelModelOracle.of(
                entry.toString(),
                reference -> "component".equals(reference) ? oneRootComponent() : null);

        expanded.field("/Permit/Details/Description");
        assertThrows(IllegalArgumentException.class, () -> expanded.field("/Permit/Details/Extra"));
    }

    @Test
    void kernelRejectsMalformedReferenceLevelAndElementsMembers() {
        var invalidReferences = List.of(
                MAPPER.getNodeFactory().nullNode(),
                MAPPER.createObjectNode(),
                MAPPER.createArrayNode(),
                MAPPER.getNodeFactory().textNode(""));
        for (var value : invalidReferences) {
            var entry = tree(entryModel());
            mountConfig(entry).set("reference", value);
            assertThrows(RuntimeException.class, () -> loadEntry(entry), value.toString());
        }

        var invalidLevels = List.of(
                MAPPER.getNodeFactory().numberNode(7),
                MAPPER.getNodeFactory().booleanNode(true),
                MAPPER.createObjectNode(),
                MAPPER.createArrayNode(),
                MAPPER.getNodeFactory().textNode(""),
                MAPPER.getNodeFactory().textNode("UNKNOWN"));
        for (var value : invalidLevels) {
            var entry = tree(entryModel());
            mountConfig(entry).set("includeLevel", value);
            assertThrows(RuntimeException.class, () -> loadEntry(entry), value.toString());
        }

        var invalidElements = List.of(
                MAPPER.getNodeFactory().numberNode(7),
                MAPPER.getNodeFactory().booleanNode(true),
                MAPPER.getNodeFactory().textNode("not-an-array"),
                MAPPER.createObjectNode());
        for (var value : invalidElements) {
            var entry = tree(entryModel());
            mountBody(entry).set("elements", value);
            assertThrows(RuntimeException.class, () -> loadEntry(entry), value.toString());
        }
    }

    @Test
    void kernelTreatsAnExplicitNullIncludeConfigLikeAbsence() {
        var nullConfig = tree(entryModel());
        mountBody(nullConfig).putNull("includeConfig");
        var unmounted = KernelModelOracle.of(
                nullConfig.toString(),
                reference -> "component".equals(reference) ? oneRootComponent() : null);
        unmounted.group("/Permit/Details");
        assertThrows(IllegalArgumentException.class, () -> unmounted.field("/Permit/Details/Description"));
    }

    @Test
    void kernelRejectsAnExplicitNullIncludeLevel() {
        var entry = tree(entryModel());
        mountConfig(entry).putNull("includeLevel");

        assertThrows(RuntimeException.class, () -> loadEntry(entry));
    }

    @Test
    void kernelRejectsExplicitNullElements() {
        var entry = tree(entryModel());
        mountBody(entry).putNull("elements");

        assertThrows(RuntimeException.class, () -> loadEntry(entry));
    }

    /** A DM 28 mount option on an ordinary group is a member A12 31.1.1 ignores (KF651). */
    @Test
    void kernelIgnoresADm28IncludeLevelOnAnOrdinaryGroup() {
        var model = tree(DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.field("Description", FieldKind.STRING))
                .build()
                .toJson());
        bodyOfRoot(model).put("includeLevel", "MODEL_ROOT");

        KernelModelOracle.ofSelfContained(model.toString()).field("/Permit/Description");
    }

    @Test
    void kernelAcceptsExclusionFlagsOnAnOrdinaryGroup() {
        for (var directive : new String[] {"excludeRules", "excludeComputations"}) {
            var model = tree(DocumentModelDraft.named("permit").locale("en_US")
                    .rootGroup("Permit", permit -> permit.field("Description", FieldKind.STRING))
                    .build()
                    .toJson());
            bodyOfRoot(model).put(directive, true);

            var expanded = KernelModelOracle.ofSelfContained(model.toString());

            expanded.field("/Permit/Description");
        }
    }

    private static String oneRootComponent() {
        return DocumentModelDraft.named("component").locale("en_US")
                .rootGroup("Component", component -> component.field("Description", FieldKind.STRING))
                .build()
                .toJson();
    }

    private static String dateModel() {
        return DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.dateField("ObservedOn", date -> date.format("dd.MM.yyyy")))
                .build()
                .toJson();
    }

    private static String executableOneRootComponent() {
        var structure = DocumentModelDraft.named("component").locale("en_US")
                .rootGroup("Component", component -> component
                        .field("Description", FieldKind.STRING)
                        .field("Result", FieldKind.STRING))
                .build()
                .toJson();
        var session = EditSession.read(structure);
        var component = session.group("/Component");
        var description = component.str("Description");
        var result = component.str("Result");
        component.addRule(rule -> rule.name("DescriptionRequired")
                .errorField(description)
                .when(description.notFilled())
                .code("DESCRIPTION_REQUIRED")
                .severity(Severity.ERROR)
                .message(en("A description is required."))
                .build());
        component.addComputation(result, computation -> computation.name("CopyDescription")
                .when(description.filled())
                .calc(description.asString().expression())
                .message(en("The result follows the description."))
                .build());
        return session.validate().toJson();
    }

    private static String entryModel() {
        return DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.mount("Details", "component"))
                .build()
                .toJson();
    }

    private static KernelModelOracle loadEntryWith(ObjectNode component) {
        return KernelModelOracle.of(
                entryModel(),
                reference -> "component".equals(reference) ? component.toString() : null);
    }

    private static void loadEntry(ObjectNode entry) {
        KernelModelOracle.of(
                entry.toString(),
                reference -> "component".equals(reference) ? oneRootComponent() : null);
    }

    private static ObjectNode tree(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ArrayNode elementsOfRoot(ObjectNode model) {
        return (ArrayNode) bodyOfRoot(model).path("elements");
    }

    private static ObjectNode bodyOfRoot(ObjectNode model) {
        return (ObjectNode) model.path("content").path("modelRoot").path("rootGroups")
                .get(0).path("Group");
    }

    private static ObjectNode elementOfType(ObjectNode model, String type) {
        for (var element : elementsOfRoot(model)) {
            if (type.equals(element.path("type").asText())) {
                return (ObjectNode) element;
            }
        }
        throw new IllegalArgumentException("no element of type " + type);
    }

    private static ObjectNode dateTypeOfFirstField(ObjectNode model) {
        return (ObjectNode) elementsOfRoot(model).get(0).path("Field").path("fieldType").path("DateType");
    }

    private static void recordAccepted(List<String> accepted, String description, ObjectNode model) {
        try {
            KernelModelOracle.ofSelfContained(model.toString());
            accepted.add(description);
        } catch (RuntimeException expected) {
            // Rejection is the measured outcome; only accepted shapes are reported by the characterization.
        }
    }

    private static ObjectNode mountBody(ObjectNode model) {
        return (ObjectNode) elementsOfRoot(model).get(0).path("Group");
    }

    private static ObjectNode mountConfig(ObjectNode model) {
        return (ObjectNode) mountBody(model).path("includeConfig");
    }

    /** The two executables an include can drop, each with the mount directive that drops it. */
    private enum IncludedExecutable {
        RULE("excludeRules", "/Permit/Details/DescriptionRequired"),
        COMPUTATION("excludeComputations", "/Permit/Details/CopyDescription");

        private final String directive;
        private final String path;

        IncludedExecutable(String directive, String path) {
            this.directive = directive;
            this.path = path;
        }

        String directive() {
            return directive;
        }

        String verdictIn(PreparationParityHarness.PreparedSurface surface) {
            boolean survives = switch (this) {
                case RULE -> surface.rules().contains(path);
                case COMPUTATION -> surface.computations().containsKey(path);
            };
            return survives ? "KEPT" : "EXCLUDED";
        }
    }


}
