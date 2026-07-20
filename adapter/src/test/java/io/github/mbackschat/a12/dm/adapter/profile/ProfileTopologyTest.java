package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.Capacity.Cause.DECLARED;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileTopology.MODEL_LEVEL;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileTopology.PER_GROUP;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileTopology.PER_REPEATABLE_ROW;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

class ProfileTopologyTest {

    @Test
    void extractsStructureAndExactNestedCapacitiesFromTheTypedModelCard() {
        var topology = topologyOf(calibrationModel());

        var structure = topology.structureProfile();
        assertEquals(java.util.Map.of("FIELD", 7L, "GROUP", 6L), structure.elements().counts());
        assertEquals(3, structure.topology().maxGroupDepth());
        assertEquals(6, structure.topology().fieldsPerGroup().count());
        assertEquals(
                java.util.Map.of(
                        "BOOLEAN", 1L,
                        "ENUMERATION", 1L,
                        "NUMBER", 3L,
                        "STRING", 2L),
                structure.fields().kinds().counts());
        assertEquals(
                java.util.Map.of(
                        "NUMBER_BOUNDS", 1L,
                        "STRING_LENGTH", 1L,
                        "STRING_PATTERN", 1L),
                structure.fields().constrainedFields().counts());

        var repeatability = structure.repeatability();
        assertEquals(java.util.Map.of("BOUNDED", 3L), repeatability.outcomes().counts());
        assertEquals(2, repeatability.topology().maxDepth());
        assertEquals(java.util.Map.of("1", 2L, "2", 1L), repeatability.topology().exactDepth().counts());
        assertEquals(new Capacity.Bounded(15), topology.chainCapacity("/Survey/Sections/Answers"));
        assertEquals(
                new Capacity.Bounded(105),
                topology.scanCapacity(List.of(
                        "/Survey/Sections", "/Survey/Sections/Answers", "/Survey/Tags")));
        assertEquals(1, topology.descendantFieldCount("/Survey/Sections"));
    }

    @Test
    void classifiesScopesWalkUpsAndSiblingRepeatableBranches() {
        var topology = topologyOf(calibrationModel());

        assertEquals(MODEL_LEVEL, topology.scopeClass("/Survey"));
        assertEquals(PER_GROUP, topology.scopeClass("/Survey/Metadata"));
        assertEquals(PER_REPEATABLE_ROW, topology.scopeClass("/Survey/Sections/Answers"));
        assertEquals(2, topology.walkUpDistance(
                "/Survey/Sections/Answers", "/Survey/Headline"));
        assertEquals(
                java.util.Set.of("/Survey/Sections/Answers", "/Survey/Tags"),
                topology.repeatableBranchRoots(List.of(
                        "/Survey/Sections/Answers/Score", "/Survey/Tags/Name")));
        assertEquals(2, topology.structureProfile().repeatability().topology().siblingFan().max());
    }

    @Test
    void declaredUnboundednessStaysExplicitInEveryDerivedCapacity() {
        var card = new ModelCard(
                List.of(),
                List.of(
                        new ModelCard.Group("/Root", false, 1),
                        new ModelCard.Group("/Root/Rows", true, 0)),
                List.of());
        var topology = new ProfileTopology(card);

        assertEquals(new Capacity.Unbounded(DECLARED), topology.chainCapacity("/Root/Rows"));
        assertEquals(new Capacity.Unbounded(DECLARED), topology.scanCapacity(List.of("/Root/Rows")));
        assertEquals(
                java.util.Map.of("UNBOUNDED", 1L),
                topology.structureProfile().repeatability().outcomes().counts());
        assertEquals(
                java.util.Map.of("DECLARED", 1L),
                topology.structureProfile()
                        .repeatability()
                        .capacities()
                        .groupChains()
                        .unboundedCauses()
                        .counts());
    }

    private static ProfileTopology topologyOf(String modelJson) {
        var model = BoundModel.ofJson(modelJson, ReferenceResolvers.selfContained());
        return new ProfileTopology(new ModelContext(model).describe());
    }

    private static String calibrationModel() {
        return DocumentModelDraft.named("profile-topology").locale("en_US")
                .rootGroup("Survey", root -> root
                        .stringField("Headline", string -> string
                                .minLength(3)
                                .maxLength(40)
                                .pattern("[A-Za-z ]+", "letters and spaces"))
                        .field("Published", FieldKind.BOOLEAN)
                        .numberField("Budget", number -> number.fractionDigits(2))
                        .group("Metadata", metadata -> metadata
                                .enumField("Band", values -> values
                                        .value("SMALL")
                                        .value("LARGE"))
                                .numberField("Revision", number -> number
                                        .min("1")
                                        .max("99")
                                        .fractionDigits(0)))
                        .group("Sections", sections -> sections
                                .repeatable(3)
                                .group("Answers", answers -> answers
                                        .repeatable(5)
                                        .numberField("Score", number -> number.fractionDigits(2))))
                        .group("Tags", tags -> tags
                                .repeatable(7)
                                .stringField("Name", string -> { }))
                        .group("Empty", empty -> { }))
                .build()
                .toJson();
    }
}
