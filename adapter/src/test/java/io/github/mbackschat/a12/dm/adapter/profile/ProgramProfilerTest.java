package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileTopology.MODEL_LEVEL;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileTopology.PER_REPEATABLE_ROW;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

class ProgramProfilerTest {

    @Test
    void aggregatesLogicalProgramsAcrossEveryRuntimeCostAxis() {
        var modelJson = calibrationModel();
        var session = EditSession.read(modelJson);
        var survey = session.group("/Survey");
        var sections = session.group(survey.group("Sections").path());
        var answers = session.group(sections.group("Answers").path());
        var tags = session.group(survey.group("Tags").path());
        var headline = survey.str("Headline");
        var sectionCode = sections.str("Code");
        var key = answers.str("Key");
        var score = answers.num("Score");
        var weight = answers.num("Weight");
        var tagName = tags.str("Name");
        var filteredScores = survey.flatten("Sections*/Answers*/Score")
                .having(key.eq(key.outer()));
        var conditionPart = Dsl.sum(filteredScores).gt(0)
                .and(Dsl.groupFilled(answers.ref()))
                .and(Dsl.repetitionNotUnique(key.field(), weight.field()).from(sections.ref()))
                .and(tagName.filled())
                .and(sectionCode.filled())
                .and(headline.filled());
        var semanticIndexPart = score.forIndex(key.field()).expression();
        var model = BoundModel.ofJson(modelJson, ReferenceResolvers.selfContained());
        var topology = new ProfileTopology(new ModelContext(model).describe());

        var complex = ProgramProfiler.ConvertedProgram.builder(answers.ref().path())
                .addPart(ConditionComplexity.of(conditionPart))
                .addPart(ConditionComplexity.of(semanticIndexPart))
                .build();
        var profile = ProgramProfiler.builder(topology)
                .converted(survey.ref().path(), ConditionComplexity.of(headline.filled()))
                .add(complex)
                .opaque(sections.ref().path(), OpaqueReasonCategory.CONVERSION_LIMIT)
                .build();

        assertEquals(
                Map.of("CONVERTED", 2L, "OPAQUE", 1L),
                profile.conversion().outcomes().counts());
        assertEquals(
                Map.of("CONVERSION_LIMIT", 1L),
                profile.conversion().opaqueReasons().counts());
        assertEquals(2, profile.syntax().ast().nodeCount().count());
        assertEquals(2, profile.syntax().ast().distinctFanIn().count());
        assertEquals(
                Map.of(
                        "AGGREGATE", 1L,
                        "FILTER", 1L,
                        "GROUP_FILL", 1L,
                        "SEMANTIC_INDEX", 1L,
                        "UNIQUENESS", 1L),
                profile.syntax().dispatch().constructs().counts());
        assertEquals(
                Map.of(
                        "AGGREGATE+FILTER+GROUP_FILL+SEMANTIC_INDEX+UNIQUENESS", 1L,
                        "NONE", 1L),
                profile.syntax().dispatch().constructCombinations().counts());
        assertEquals(
                Map.of(
                        "AGGREGATE", 1L,
                        "FILTER", 1L,
                        "GROUP_FILL", 1L,
                        "SEMANTIC_INDEX", 1L,
                        "UNIQUENESS", 1L),
                profile.syntax()
                        .dispatch()
                        .constructsByScope()
                        .get(PER_REPEATABLE_ROW)
                        .counts());

        var runtime = profile.runtime();
        assertEquals(
                Map.of(MODEL_LEVEL, 1L, PER_REPEATABLE_ROW, 2L),
                runtime.scope().classes().counts());
        assertEquals(3, runtime.scope().capacity().count());
        assertEquals(15, runtime.scope().capacity().bounded().max());
        assertEquals(
                Map.of("FLATTENED_FIELD", 1L),
                runtime.evaluation().scans().kinds().counts());
        assertEquals(2, runtime.evaluation().scans().starDepth().max());
        assertEquals(15, runtime.evaluation().scans().capacity().bounded().max());
        assertEquals(3, runtime.evaluation().scans().filterComplexity().max());
        assertEquals(2, runtime.evaluation().combinedWork().capacity().count());
        assertEquals(225, runtime.evaluation().combinedWork().capacity().bounded().max());
        assertEquals(
                Map.of(PER_REPEATABLE_ROW + "|FLATTENED_FIELD", 1L),
                runtime.evaluation().combinedWork().scopeByScan().counts());

        var mechanisms = runtime.evaluation().mechanisms();
        assertMechanism(mechanisms.get("GROUP_FILL"), 3, 45);
        assertMechanism(mechanisms.get("SEMANTIC_INDEX"), 2, 15);
        assertMechanism(mechanisms.get("UNIQUENESS"), 2, 3);
        assertMechanism(
                mechanisms.get("PARALLEL_REFERENCE"),
                2,
                15,
                "the comparable Sections/Answers references form one branch, not two");
        assertEquals(
                Map.of("FIELD_VALUE", 1L),
                runtime.evaluation().outerCorrelation().forms().counts());
        assertEquals(15, runtime.evaluation().outerCorrelation().capacity().bounded().max());
        assertEquals(2, runtime.resolution().walkUpDistance().max());
    }

    private static void assertMechanism(
            ModelProfile.MechanismProfile mechanism,
            long shape,
            long capacity) {
        assertMechanism(mechanism, shape, capacity, null);
    }

    private static void assertMechanism(
            ModelProfile.MechanismProfile mechanism,
            long shape,
            long capacity,
            String message) {
        assertEquals(shape, mechanism.shape().max(), message);
        assertEquals(capacity, mechanism.capacity().bounded().max(), message);
    }

    private static String calibrationModel() {
        return DocumentModelDraft.named("program-profile").locale("en_US")
                .rootGroup("Survey", root -> root
                        .field("Headline", FieldKind.STRING)
                        .group("Sections", sections -> sections
                                .repeatable(3)
                                .field("Code", FieldKind.STRING)
                                .group("Answers", answers -> answers
                                        .repeatable(5)
                                        .indexField("Key")
                                        .field("Key", FieldKind.STRING)
                                        .field("Score", FieldKind.NUMBER)
                                        .field("Weight", FieldKind.NUMBER)))
                        .group("Tags", tags -> tags
                                .repeatable(7)
                                .field("Name", FieldKind.STRING)))
                .build()
                .toJson();
    }
}
