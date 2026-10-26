package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;

/**
 * {@link ModelDocument} reads a stored {@code repeatability} and {@code baseYear} as {@link ModelIndex} does, which
 * is how A12's reader binds them ({@code core.ModelIndexIntBindingTest}; the A12 rows are
 * {@code adapter.laws.RepeatabilityTextReadingDiffTest} and {@code ModelTextReadingDiffTest}). Each row once read
 * differently here, because Jackson's {@code asInt} takes ASCII digits only and reads the text {@code null} as no
 * number, so {@code "\u0663"} was a non-repeatable group to the editor and a three-row group to every other reader.
 * Own domain (a claims register).
 */
class ModelDocumentIntBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLAIMS = "/Register/Claims";

    private static String model(String member, String raw) {
        ObjectNode root = (ObjectNode) read(DocumentModelDraft.named("claims").locale("en_US").baseYear(2000)
                .rootGroup("Register", r -> r.group("Claims", c -> c.repeatable(5)
                        .numberField("Amount", f -> f.fractionDigits(0))))
                .build().toJson());
        ObjectNode owner = member.equals("baseYear")
                ? (ObjectNode) root.at("/content/modelInfo")
                : (ObjectNode) root.at("/content/modelRoot/rootGroups/0/Group/elements/0/Group");
        owner.set(member, read(raw));
        return root.toString();
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyRepetitionReadAgreesWithTheModelIndex() {
        Map<String, List<Object>> expected = new LinkedHashMap<>();
        Map<String, List<Object>> observed = new LinkedHashMap<>();
        for (String raw : List.of("3.7", "\"\\u0663\"", "\"\\uFF13\"", "\"null\"", "\"\"", "null", "\" 3 \"", "\"3.7\"")) {
            String json = model("repeatability", raw);
            int count = ModelIndex.of(json, ReferenceResolvers.selfContained()).maxRepetitions(CLAIMS);
            expected.put(raw, List.of(count, count > 1, count, count > 1));
            ModelDocument document = ModelDocument.read(json);
            observed.put(raw, List.of(document.maxRepetitions(CLAIMS), document.isRepeating(CLAIMS),
                    document.authoredState().groups().get(CLAIMS).structure().repeatability(),
                    sortFieldsAdmitted(document)));
        }
        assertEquals(expected, observed);
    }

    /** {@code setSortFields} refuses a non-repeatable group, so whether it throws is a fourth repetition read. */
    private static boolean sortFieldsAdmitted(ModelDocument document) {
        try {
            document.setSortFields(CLAIMS, List.of(new ElementDraft.SortField("Amount", SortOrder.ASC)));
            return true;
        } catch (IllegalArgumentException notRepeatable) {
            return false;
        }
    }

    @Test
    void aBaseYearAgreesWithTheModelIndex() {
        Map<String, Optional<Integer>> expected = new LinkedHashMap<>();
        Map<String, Optional<Integer>> observed = new LinkedHashMap<>();
        for (String raw : List.of("2024.7", "\" 2024 \"", "\"\\u0662\\u0660\\u0662\\u0664\"", "\"null\"", "\"\"",
                "2147483647.5", "true")) {
            String json = model("baseYear", raw);
            expected.put(raw, ModelIndex.of(json, ReferenceResolvers.selfContained()).baseYear());
            observed.put(raw, ModelDocument.read(json).baseYear());
        }
        assertEquals(expected, observed);
    }
}
