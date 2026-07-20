package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonFixture.profile;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ProfileVocabularyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void rejectsUnknownKeysAcrossTheClosedCategoricalVocabulary() {
        assertAll(
                () -> assertUnknownHistogramKey("/structure/elements", "PRIVATE_ELEMENT"),
                () -> assertUnknownHistogramKey("/structure/fields/kinds", "PRIVATE_KIND"),
                () -> assertUnknownHistogramKey(
                        "/structure/fields/constrainedFields", "PRIVATE_CONSTRAINT"),
                () -> assertUnknownHistogramKey(
                        "/structure/repeatability/outcomes", "PRIVATE_OUTCOME"),
                () -> assertUnknownHistogramKey(
                        "/structure/repeatability/capacities/maxRepetitions/unboundedCauses",
                        "PRIVATE_CAUSE"),
                () -> assertUnknownHistogramKey(
                        "/rules/conversion/outcomes", "PRIVATE_CONVERSION"),
                () -> assertUnknownHistogramKey(
                        "/rules/conversion/opaqueReasons", "PRIVATE_REASON"),
                () -> assertUnknownHistogramKey(
                        "/rules/syntax/dispatch/catalogOperators", "PrivateOperator"),
                () -> assertUnknownHistogramKey(
                        "/rules/syntax/dispatch/intrinsicOperators", "%"),
                () -> assertUnknownHistogramKey(
                        "/rules/syntax/dispatch/constructs", "PRIVATE_CONSTRUCT"),
                () -> assertUnknownHistogramKey(
                        "/rules/runtime/scope/classes", "PRIVATE_SCOPE"),
                () -> assertUnknownHistogramKey(
                        "/rules/runtime/evaluation/scans/kinds", "PRIVATE_SCAN"),
                ProfileVocabularyTest::assertUnknownMechanism,
                () -> assertUnknownHistogramKey(
                        "/rules/runtime/evaluation/outerCorrelation/forms",
                        "PRIVATE_OUTER"),
                () -> assertUnknownHistogramKey(
                        "/computations/shape/tables/toleranceBands", "PRIVATE_TOLERANCE"),
                () -> assertUnknownHistogramKey(
                        "/computations/shape/archetypes", "PRIVATE_ARCHETYPE"),
                () -> assertUnknownHistogramKey(
                        "/computations/shape/aggregateScaleMatch", "PRIVATE_SCALE"),
                () -> assertUnknownHistogramKey(
                        "/wiring/referencedFields", "PRIVATE_FIELD"));
    }

    @Test
    void rejectsMissingRequiredAxesAndNonCanonicalCompositeKeys() {
        assertAll(
                () -> assertRemovedAxis(
                        "/rules/syntax/dispatch/constructsByScope", "PER_GROUP"),
                () -> assertRemovedAxis(
                        "/rules/runtime/evaluation/mechanisms", "SEMANTIC_INDEX"),
                () -> assertUnknownHistogramKey(
                        "/rules/syntax/dispatch/constructCombinations",
                        "FILTER+AGGREGATE"),
                () -> assertUnknownHistogramKey(
                        "/rules/runtime/evaluation/combinedWork/scopeByScan",
                        "FLATTENED_FIELD|MODEL_LEVEL"),
                () -> assertUnknownHistogramKey(
                        "/structure/repeatability/topology/exactDepth", "01"));
    }

    @Test
    void validatesVocabularyBeforeWriting() {
        ModelProfile original = profile(false);
        var invalidElements = new TreeMap<>(original.structure().elements().counts());
        invalidElements.put("PRIVATE_ELEMENT", 0L);
        var invalidStructure = new ModelProfile.StructureProfile(
                LongHistogram.of(invalidElements),
                original.structure().topology(),
                original.structure().fields(),
                original.structure().repeatability());
        var invalid = new ModelProfile(
                invalidStructure,
                original.rules(),
                original.computations(),
                original.wiring());

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ModelProfileJson.write(invalid));

        assertTrue(error.getMessage().contains("$.structure.elements"));
        assertTrue(error.getMessage().contains("PRIVATE_ELEMENT"));
    }

    private static ObjectNode mutableProfile() throws Exception {
        return (ObjectNode) JSON.readTree(ModelProfileJson.write(profile(false)));
    }

    private static void assertUnknownHistogramKey(String pointer, String key) throws Exception {
        ObjectNode root = mutableProfile();
        ((ObjectNode) root.at(pointer)).put(key, 0);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ModelProfileJson.read(root.toString()));

        assertTrue(error.getMessage().contains(key), error::getMessage);
    }

    private static void assertUnknownMechanism() throws Exception {
        ObjectNode root = mutableProfile();
        ObjectNode mechanisms =
                (ObjectNode) root.at("/rules/runtime/evaluation/mechanisms");
        mechanisms.set("PRIVATE_MECHANISM", mechanisms.path("GROUP_FILL").deepCopy());

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ModelProfileJson.read(root.toString()));

        assertTrue(error.getMessage().contains("PRIVATE_MECHANISM"), error::getMessage);
    }

    private static void assertRemovedAxis(String pointer, String key) throws Exception {
        ObjectNode root = mutableProfile();
        ((ObjectNode) root.at(pointer)).remove(key);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ModelProfileJson.read(root.toString()));

        assertTrue(
                error.getMessage().contains(pointer.replace('/', '.').substring(1)),
                error::getMessage);
    }
}
