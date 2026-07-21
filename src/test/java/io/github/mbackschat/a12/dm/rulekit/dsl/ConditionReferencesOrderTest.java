package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;

/** Locks reference encounter order from the AST through model-context projections. */
class ConditionReferencesOrderTest {

    @Test
    void canonicalConstructorPreservesEncounterOrderForBothReferenceKinds() {
        List<String> fieldPaths = IntStream.range(0, 64).mapToObj(i -> "/Survey/F" + i).toList();
        List<String> groupPaths = IntStream.range(0, 64).mapToObj(i -> "/Survey/G" + i).toList();

        ConditionReferences references = new ConditionReferences(
                new LinkedHashSet<>(fieldPaths), new LinkedHashSet<>(groupPaths));

        assertEquals(fieldPaths, new ArrayList<>(references.fieldPaths()));
        assertEquals(groupPaths, new ArrayList<>(references.groupPaths()));
    }

    @Test
    void conditionTraversalOrderSelectsAStableIterationScope() {
        BoundModel model = repeatedSurvey();
        Condition condition = Dsl.allFieldsFilled(
                model.field("/Survey/Primary/Code"),
                model.field("/Survey/Secondary/Code"));

        assertEquals(
                List.of("/Survey/Primary/Code", "/Survey/Secondary/Code"),
                new ArrayList<>(ConditionReferences.of(condition).fieldPaths()));
        assertEquals(Optional.of("/Survey/Primary"), new ModelContext(model).explain(condition).iterationGroup());
    }

    @Test
    void groupOperandTraversalPreservesTheSiblingEncounterOrder() {
        BoundModel model = repeatedSurvey();
        Condition condition = Dsl.atLeastOneGroupFilled(
                model.group("/Survey/Secondary"),
                model.group("/Survey/Primary"));

        assertEquals(
                List.of("/Survey/Secondary", "/Survey/Primary"),
                new ArrayList<>(ConditionReferences.of(condition).groupPaths()));
    }

    private static BoundModel repeatedSurvey() {
        String json = DocumentModelDraft.named("survey").locale("en_US")
                .rootGroup("Survey", survey -> survey
                        .group("Primary", group -> group.repeatable(3).field("Code", FieldKind.STRING))
                        .group("Secondary", group -> group.repeatable(3).field("Code", FieldKind.STRING)))
                .build()
                .toJson();
        return BoundModel.ofJson(json);
    }
}
