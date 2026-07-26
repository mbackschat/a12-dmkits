package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.DocumentModelExpansionException;

import io.github.mbackschat.a12.dm.adapter.preparation.WorkspacePreparationFixtures;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
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
                .include("component", "component")
                .rootGroup("Permit", permit -> permit.mount(
                        "Details",
                        "component",
                        MountOptions.single().asModelRoot()))
                .build()
                .toJson();
        assertTrue(valid.contains("\"includeLevel\" : \"MODEL_ROOT\""), "the typed fixture carries includeLevel");
        var invalid = valid.replace("\"includeLevel\" : \"MODEL_ROOT\"", "\"includeLevel\" : \"UNKNOWN\"");

        assertThrows(UncheckedIOException.class, () -> KernelModelOracle.of(
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
                .include("component", "component")
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
                .include("component", "component")
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
                MAPPER.getNodeFactory().nullNode(),
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

        var rejectedValues = List.of(
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

    @Test
    void kernelRejectsSingleLevelMountingOfAnEmptyModelRoot() {
        var component = tree(oneRootComponent());
        ((ArrayNode) component.path("content").path("modelRoot").path("rootGroups")).removeAll();

        assertThrows(DocumentModelExpansionException.class, () -> loadEntryWith(component));
    }

    @Test
    void kernelRejectsEveryNonObjectProviderRootGroupBody() {
        var invalidValues = List.of(
                MAPPER.getNodeFactory().nullNode(),
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
    void kernelRejectsAKnownNonGroupRootAndAMissingGroupBody() {
        var fieldRoot = tree(oneRootComponent());
        var root = (ObjectNode) fieldRoot.path("content").path("modelRoot").path("rootGroups").get(0);
        var firstField = (ObjectNode) elementsOfRoot(fieldRoot).get(0);
        root.put("type", "Field");
        root.set("Field", firstField.path("Field").deepCopy());
        root.remove("Group");
        assertThrows(RuntimeException.class, () -> loadEntryWith(fieldRoot));

        var missingBody = tree(oneRootComponent());
        ((ObjectNode) missingBody.path("content").path("modelRoot").path("rootGroups").get(0))
                .remove("Group");
        assertThrows(RuntimeException.class, () -> loadEntryWith(missingBody));
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
                List.of("Field=missing", "Field=null"),
                accepted,
                "the Kernel alone admits an absent/null Field body; portable preparation deliberately refuses " +
                        "that shape rather than fabricating a default string field");
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
                        "name=null", "name=true", "name=7", "name={}", "name=[]",
                        "id=null", "id=true", "id=7", "id={}", "id=[]"),
                accepted,
                "missing identity members and malformed type are refused; the Kernel coerces present non-string " +
                "name/id values, while portable preparation requires a normalized string id");
    }

    @Test
    void kernelRejectsElementShapedRecordsOutsideTheModelTree() {
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
        assertUnknownPropertyRejected(
                fieldModel,
                "the Kernel also rejects element records hidden inside a real element body");
    }

    private static void assertUnknownPropertyRejected(ObjectNode model, String message) {
        var rejection = assertThrows(
                UncheckedIOException.class,
                () -> KernelModelOracle.ofSelfContained(model.toString()),
                message);
        assertInstanceOf(UnrecognizedPropertyException.class, rejection.getCause());
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

    @Test
    void kernelAcceptsEachNonBooleanIncludeExclusionAsFalse() {
        var component = executableOneRootComponent();
        for (var expectation : List.of(
                new ExclusionExpectation("excludeRules", "DescriptionRequired"),
                new ExclusionExpectation("excludeComputations", "CopyDescription"))) {
            var entry = tree(entryModel());
            mountBody(entry).set(expectation.directive(), MAPPER.createObjectNode());

            var expanded = KernelModelOracle.of(
                    entry.toString(),
                    reference -> "component".equals(reference) ? component : null);

            expanded.field("/Permit/Details/Description");
            assertTrue(expanded.group("/Permit/Details").getElements().stream()
                    .anyMatch(element -> expectation.elementName().equals(element.getName())),
                    expectation.directive() + " is treated as false, so the included executable survives");
        }
    }

    @Test
    void kernelAndReaderExpandAnIncludeMountedAtTheDiscardedProviderRoot() {
        var leaf = oneRootComponent();
        var provider = EditSession.read(DocumentModelDraft.named("provider").locale("en_US")
                        .include("leafAlias", "leaf")
                        .rootGroup("Placeholder", group -> {})
                        .build()
                        .toJson());
        provider.removeGroup("/Placeholder");
        provider.mount("", "ProviderRoot", "leafAlias");
        var providerJson = provider.toJson();
        var entry = DocumentModelDraft.named("permit").locale("en_US")
                .include("providerAlias", "provider")
                .rootGroup("Permit", permit -> permit.mount("Details", "providerAlias"))
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

    @Test
    void kernelUsesFinalExpandedUsageTypeForOuterExclusions() {
        for (var topology : finalUsageTopologies()) {
            var scenario = finalUsageScenario(topology, FinalUsageVariant.PROVIDER_OVERRIDE_OUTER);
            var observation = PreparationParityHarness.observe(scenario.entryJson(), scenario.resolver());

            PreparationParityHarness.assertEquivalent(observation);
            assertEquals(scenario.expectedUsageType(),
                    observation.kernel().elements().get(scenario.outputGroupPath()).usageType(),
                    scenario.outputGroupPath());
            assertEquals(scenario.expectedRulePresent(),
                    observation.kernel().rules().contains(scenario.outputRulePath()),
                    scenario.outputGroupPath() + " must apply excludeRules after its final usageType is known");
        }
    }

    @Test
    void kernelReplacesRawUsageTypeWithProviderAbsenceBeforeApplyingExclusions() {
        for (var topology : finalUsageTopologies()) {
            for (var variant : List.of(
                    FinalUsageVariant.PROVIDER_ABSENCE_OUTER,
                    FinalUsageVariant.PROVIDER_ABSENCE_MOUNT)) {
                var scenario = finalUsageScenario(topology, variant);
                var observation = PreparationParityHarness.observe(scenario.entryJson(), scenario.resolver());

                PreparationParityHarness.assertEquivalent(observation);
                assertEquals(scenario.expectedUsageType(),
                        observation.kernel().elements().get(scenario.outputGroupPath()).usageType(),
                        scenario.outputGroupPath());
                assertEquals(scenario.expectedRulePresent(),
                        observation.kernel().rules().contains(scenario.outputRulePath()),
                        scenario.outputGroupPath() + " must use the final provider usageType");
            }
        }
    }

    @Test
    void kernelAcceptsLocalElementsThatSilentlyShadowTheInclude() {
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

        expanded.field("/Permit/Details/Extra");
        assertThrows(IllegalArgumentException.class, () -> expanded.field("/Permit/Details/Description"));
    }

    @Test
    void kernelRejectsMalformedAliasLevelAndElementsMembers() {
        var invalidAliases = List.of(
                MAPPER.getNodeFactory().numberNode(7),
                MAPPER.getNodeFactory().booleanNode(true),
                MAPPER.createObjectNode(),
                MAPPER.createArrayNode(),
                MAPPER.getNodeFactory().textNode(""));
        for (var value : invalidAliases) {
            var entry = tree(entryModel());
            mountBody(entry).set("modelAlias", value);
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
            mountBody(entry).set("includeLevel", value);
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
    void kernelTreatsAnExplicitNullAliasLikeAbsence() {
        var nullAlias = tree(entryModel());
        mountBody(nullAlias).putNull("modelAlias");
        var unmounted = KernelModelOracle.of(
                nullAlias.toString(),
                reference -> "component".equals(reference) ? oneRootComponent() : null);
        unmounted.group("/Permit/Details");
        assertThrows(IllegalArgumentException.class, () -> unmounted.field("/Permit/Details/Description"));
    }

    @Test
    void kernelRejectsAnExplicitNullIncludeLevel() {
        var entry = tree(entryModel());
        mountBody(entry).putNull("includeLevel");

        assertThrows(RuntimeException.class, () -> loadEntry(entry));
    }

    @Test
    void kernelRejectsExplicitNullElements() {
        var entry = tree(entryModel());
        mountBody(entry).putNull("elements");

        assertThrows(RuntimeException.class, () -> loadEntry(entry));
    }

    @Test
    void kernelRejectsIncludeLevelOnAnOrdinaryGroup() {
        var model = tree(DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.field("Description", FieldKind.STRING))
                .build()
                .toJson());
        bodyOfRoot(model).put("includeLevel", "MODEL_ROOT");

        assertThrows(AssertionError.class, () -> KernelModelOracle.ofSelfContained(model.toString()));
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

    private static String ruledOneRootComponent() {
        var session = EditSession.read(oneRootComponent());
        var component = session.group("/Component");
        var description = component.str("Description");
        component.addRule(rule -> rule.name("DescriptionRequired")
                .errorField(description)
                .when(description.notFilled())
                .code("DESCRIPTION_REQUIRED")
                .severity(Severity.ERROR)
                .message(en("A description is required."))
                .build());
        return session.validate().toJson();
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
                .include("component", "component")
                .rootGroup("Permit", permit -> permit.mount("Details", "component"))
                .build()
                .toJson();
    }

    private static List<FinalUsageTopology> finalUsageTopologies() {
        return List.of(
                FinalUsageTopology.builder("imported-typedef-nested-single")
                        .entryModelId("permit-single")
                        .providerModelId("permit-section")
                        .transientMountName("Inspection")
                        .outputGroupPath("/Permit/Review/Inspection")
                        .outputRulePath("/Permit/Review/Inspection/NegativeScore")
                        .build(),
                FinalUsageTopology.builder("single-provider-root-include")
                        .entryModelId("permit-root-mount-host")
                        .providerModelId("inspection-root-mount")
                        .transientMountName("InspectionProxy")
                        .outputGroupPath("/Permit/Review")
                        .outputRulePath("/Permit/Review/NegativeScore")
                        .build());
    }

    private static FinalUsageScenario finalUsageScenario(
            FinalUsageTopology topology,
            FinalUsageVariant variant) {
        var fixture = WorkspacePreparationFixtures.caseById(topology.caseId());
        var sources = new LinkedHashMap<>(fixture.sources());

        var leaf = tree(sources.get("inspection-checks"));
        if (variant.providerRootUsageType() == null) {
            bodyOfRoot(leaf).remove("usageType");
        } else {
            bodyOfRoot(leaf).put("usageType", variant.providerRootUsageType());
        }
        sources.put("inspection-checks", leaf.toString());

        var provider = tree(sources.get(topology.providerModelId()));
        var transientMount = groupBodyNamed(provider, topology.transientMountName());
        transientMount.put("usageType", "attachment");
        if (variant.exclusionOwner() == ExclusionOwner.MOUNT) {
            transientMount.put("excludeRules", true);
        } else {
            transientMount.remove("excludeRules");
        }
        sources.put(topology.providerModelId(), provider.toString());

        var entry = tree(sources.get(topology.entryModelId()));
        var outerMount = groupBodyNamed(entry, "Review");
        if (variant.exclusionOwner() == ExclusionOwner.OUTER) {
            outerMount.put("excludeRules", true);
        } else {
            outerMount.remove("excludeRules");
        }
        sources.put(topology.entryModelId(), entry.toString());

        ModelResolver resolver = sources::get;
        return new FinalUsageScenario(entry.toString(), resolver, topology, variant);
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

    private static ObjectNode groupBodyNamed(ObjectNode model, String name) {
        for (var root : model.path("content").path("modelRoot").path("rootGroups")) {
            var body = groupBodyNamed(root, name);
            if (body != null) {
                return body;
            }
        }
        throw new IllegalArgumentException("no group named " + name);
    }

    private static ObjectNode groupBodyNamed(JsonNode element, String name) {
        if (!"Group".equals(element.path("type").asText())) {
            return null;
        }
        var body = (ObjectNode) element.path("Group");
        if (name.equals(element.path("name").asText())) {
            return body;
        }
        for (var child : body.path("elements")) {
            var nested = groupBodyNamed(child, name);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private record FinalUsageScenario(
            String entryJson,
            ModelResolver resolver,
            FinalUsageTopology topology,
            FinalUsageVariant variant) {

        String outputGroupPath() {
            return topology.outputGroupPath();
        }

        String outputRulePath() {
            return topology.outputRulePath();
        }

        String expectedUsageType() {
            return variant.expectedUsageType();
        }

        boolean expectedRulePresent() {
            return variant.expectedRulePresent();
        }
    }

    private record FinalUsageTopology(
            String caseId,
            String entryModelId,
            String providerModelId,
            String transientMountName,
            String outputGroupPath,
            String outputRulePath) {

        static Builder builder(String caseId) {
            return new Builder(caseId);
        }

        private static final class Builder {
            private final String caseId;
            private String entryModelId;
            private String providerModelId;
            private String transientMountName;
            private String outputGroupPath;
            private String outputRulePath;

            private Builder(String caseId) {
                this.caseId = caseId;
            }

            Builder entryModelId(String value) {
                entryModelId = value;
                return this;
            }

            Builder providerModelId(String value) {
                providerModelId = value;
                return this;
            }

            Builder transientMountName(String value) {
                transientMountName = value;
                return this;
            }

            Builder outputGroupPath(String value) {
                outputGroupPath = value;
                return this;
            }

            Builder outputRulePath(String value) {
                outputRulePath = value;
                return this;
            }

            FinalUsageTopology build() {
                return new FinalUsageTopology(
                        caseId,
                        entryModelId,
                        providerModelId,
                        transientMountName,
                        outputGroupPath,
                        outputRulePath);
            }
        }
    }

    private enum FinalUsageVariant {
        PROVIDER_OVERRIDE_OUTER("complexType", ExclusionOwner.OUTER, "complexType", false),
        PROVIDER_ABSENCE_OUTER(null, ExclusionOwner.OUTER, null, false),
        PROVIDER_ABSENCE_MOUNT(null, ExclusionOwner.MOUNT, null, false);

        private final String providerRootUsageType;
        private final ExclusionOwner exclusionOwner;
        private final String expectedUsageType;
        private final boolean expectedRulePresent;

        FinalUsageVariant(
                String providerRootUsageType,
                ExclusionOwner exclusionOwner,
                String expectedUsageType,
                boolean expectedRulePresent) {
            this.providerRootUsageType = providerRootUsageType;
            this.exclusionOwner = exclusionOwner;
            this.expectedUsageType = expectedUsageType;
            this.expectedRulePresent = expectedRulePresent;
        }

        String providerRootUsageType() {
            return providerRootUsageType;
        }

        ExclusionOwner exclusionOwner() {
            return exclusionOwner;
        }

        String expectedUsageType() {
            return expectedUsageType;
        }

        boolean expectedRulePresent() {
            return expectedRulePresent;
        }
    }

    private enum ExclusionOwner {
        OUTER,
        MOUNT
    }

    private record ExclusionExpectation(String directive, String elementName) {
    }
}
