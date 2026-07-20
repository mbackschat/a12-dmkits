package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

class ModelProfileExtractorTest {

    @Test
    void assemblesEveryProfileSectionFromOneStrictSnapshot() {
        ModelProfile profile = ModelProfileExtractor.extract(calibrationModel("Survey", "Score", "Total", 3));

        assertEquals(
                java.util.Map.of(
                        "COMPUTATION", 1L,
                        "FIELD", 2L,
                        "GROUP", 2L,
                        "RULE", 1L),
                profile.structure().elements().counts());
        assertEquals(java.util.Map.of("CONVERTED", 1L), profile.rules().conversion().outcomes().counts());
        assertEquals(
                java.util.Map.of("CONVERTED", 1L),
                profile.computations().programs().conversion().outcomes().counts());
        assertEquals(1, profile.rules().syntax().ast().nodeCount().count());
        assertEquals(1, profile.computations().programs().syntax().ast().nodeCount().count());
        assertEquals(2, profile.wiring().referencedFields().counts().get("ANY"));
        assertEquals(1, profile.computations().dependencies().maxCascadeDepth());
    }

    @Test
    void identifiersLiteralsPatternsAndMessagesCannotChangeTheProfile() {
        ModelProfile first = ModelProfileExtractor.extract(
                calibrationModel("Survey", "Score", "Total", 3));
        ModelProfile renamed = ModelProfileExtractor.extract(
                calibrationModel("Expedition", "Measurement", "Aggregate", 47));

        assertEquals(first, renamed);
    }

    private static String calibrationModel(
            String rootName,
            String rowFieldName,
            String totalFieldName,
            int literal) {
        String base = DocumentModelDraft.named(rootName.toLowerCase()).locale("en_US")
                .rootGroup(rootName, root -> root
                        .numberField(totalFieldName, field -> field.fractionDigits(2))
                        .group("Rows", rows -> rows.repeatable(40)
                                .numberField(
                                        rowFieldName,
                                        field -> field.fractionDigits(2))))
                .build()
                .toJson();
        String root = "/" + rootName;
        EditSession session = EditSession.read(base);
        session.addRule(model -> model.rule(model.group(root))
                .name("Threshold" + literal)
                .errorField(model.num(root + "/" + totalFieldName).field())
                .when(model.num(root + "/" + totalFieldName).gt(literal))
                .code("LIMIT_" + literal)
                .severity(Severity.ERROR)
                .message(en("private wording " + literal))
                .build());
        session.addComputation(model -> model.computation(model.num(root + "/" + totalFieldName))
                .name("Sum" + literal)
                .calc(Dsl.sum(model.flatten(root + "/Rows*/" + rowFieldName)).expression())
                .message(en("different private computation text " + literal))
                .build());
        return session.toJson();
    }
}
