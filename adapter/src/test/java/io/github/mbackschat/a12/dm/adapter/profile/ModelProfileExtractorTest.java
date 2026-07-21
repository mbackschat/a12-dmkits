package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

class ModelProfileExtractorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void assemblesEveryProfileSectionFromOneStrictSnapshot() {
        ModelProfile profile = ModelProfileExtractor.extract(calibrationModel(false));

        assertEquals(
                java.util.Map.of(
                        "COMPUTATION", 1L,
                        "FIELD", 4L,
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
    void identifiersLiteralsPatternsEnumValuesMessagesAndDescriptionsCannotChangeTheProfile() throws Exception {
        String firstModel = calibrationModel(false);
        String mutatedModel = calibrationModel(true);

        assertNotEquals(firstModel, mutatedModel);
        assertPrivateSurface(firstModel, "Survey", "[A-Z]{3}", "BRONZE", "private survey notes");
        assertPrivateSurface(mutatedModel, "Expedition", "[0-9]{5}", "AMBER", "mutated expedition notes");
        assertIdentifiers(
                firstModel,
                mutatedModel,
                "survey",
                "Survey",
                "Rows",
                "Score",
                "Total",
                "RespondentCode",
                "Tier",
                "Threshold3",
                "Sum3",
                "LIMIT_3");
        assertIdentifiers(
                mutatedModel,
                firstModel,
                "expedition",
                "Expedition",
                "Observations",
                "Measurement",
                "Aggregate",
                "Waypoint",
                "Readiness",
                "Threshold47",
                "Sum47",
                "LIMIT_47");

        ModelProfile first = ModelProfileExtractor.extract(firstModel);
        ModelProfile mutated = ModelProfileExtractor.extract(mutatedModel);

        assertEquals(first, mutated);
    }

    private static String calibrationModel(boolean mutated) {
        String rootName = mutated ? "Expedition" : "Survey";
        String rowGroupName = mutated ? "Observations" : "Rows";
        String rowFieldName = mutated ? "Measurement" : "Score";
        String totalFieldName = mutated ? "Aggregate" : "Total";
        String codeFieldName = mutated ? "Waypoint" : "RespondentCode";
        String tierFieldName = mutated ? "Readiness" : "Tier";
        int literal = mutated ? 47 : 3;
        String pattern = mutated ? "[0-9]{5}" : "[A-Z]{3}";
        String firstEnum = mutated ? "AMBER" : "BRONZE";
        String secondEnum = mutated ? "VIOLET" : "SILVER";
        String description = mutated ? "mutated expedition notes" : "private survey notes";
        String base = DocumentModelDraft.named(rootName.toLowerCase()).locale("en_US")
                .rootGroup(rootName, root -> root
                        .internalDescription("en_US", description)
                        .numberField(totalFieldName, field -> field
                                .fractionDigits(2)
                                .externalDescription("en_US", description + " total"))
                        .stringField(codeFieldName, field -> field
                                .pattern(pattern, "private pattern message " + literal)
                                .internalDescription("en_US", description + " code"))
                        .enumField(tierFieldName, field -> field
                                .value(firstEnum)
                                .value(secondEnum)
                                .externalDescription("en_US", description + " tier"))
                        .group(rowGroupName, rows -> rows.repeatable(40)
                                .internalDescription("en_US", description + " rows")
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
                .calc(Dsl.sum(model.flatten(root + "/" + rowGroupName + "*/" + rowFieldName)).expression())
                .message(en("different private computation text " + literal))
                .build());
        return session.toJson();
    }

    private static void assertPrivateSurface(
            String model,
            String identifier,
            String regex,
            String enumValue,
            String description) {
        assertTrue(model.contains(identifier), identifier);
        assertTrue(model.contains(regex), regex);
        assertTrue(model.contains(enumValue), enumValue);
        assertTrue(model.contains(description), description);
    }

    private static void assertIdentifiers(
            String expectedModel,
            String otherModel,
            String... identifiers) throws Exception {
        Set<String> expected = identifiersIn(expectedModel);
        Set<String> other = identifiersIn(otherModel);
        for (String identifier : identifiers) {
            assertTrue(expected.contains(identifier), identifier);
            assertTrue(!other.contains(identifier), identifier + " must be renamed");
        }
    }

    private static Set<String> identifiersIn(String model) throws Exception {
        var result = new HashSet<String>();
        collectIdentifiers(JSON.readTree(model), result);
        return result;
    }

    private static void collectIdentifiers(JsonNode node, Set<String> result) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (("id".equals(field.getKey())
                                || "name".equals(field.getKey())
                                || "code".equals(field.getKey())
                                || "errorCode".equals(field.getKey()))
                        && field.getValue().isTextual()) {
                    result.add(field.getValue().textValue());
                }
                collectIdentifiers(field.getValue(), result);
            });
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectIdentifiers(child, result));
        }
    }
}
