package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * {@link ModelIndex#baseYear()} and {@link ModelIndex#maxRepetitions} read a stored member as A12's reader binds it
 * to an {@code int} ({@link A12IntBinding}; the A12 rows are {@code adapter.laws.ModelTextReadingDiffTest} and
 * {@code RepeatabilityTextReadingDiffTest}). Jackson's own {@code asInt} reads only ASCII digits and the textual
 * null as no number at all, so each row below once read differently here. Own domain (a claims register).
 */
class ModelIndexIntBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelIndex index(String member, JsonNode value) {
        ObjectNode root = (ObjectNode) read(DocumentModelDraft.named("claims").locale("en_US").baseYear(2000)
                .rootGroup("Register", r -> r.group("Claims", c -> c.repeatable(5).numberField("Amount", f -> f.fractionDigits(0))))
                .build().toJson());
        ObjectNode owner = member.equals("baseYear")
                ? (ObjectNode) root.at("/content/modelInfo")
                : (ObjectNode) root.at("/content/modelRoot/rootGroups/0/Group/elements/0/Group");
        owner.set(member, value);
        return ModelIndex.of(root.toString(), ReferenceResolvers.selfContained());
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aBaseYearIsTheYearA12Binds() {
        Map<String, Optional<Integer>> expected = new LinkedHashMap<>();
        expected.put("2024.7", Optional.of(2024));
        expected.put("\" 2024 \"", Optional.of(2024));
        expected.put("\"\\u0662\\u0660\\u0662\\u0664\"", Optional.of(2024));
        expected.put("\"null\"", Optional.empty());
        expected.put("\"\"", Optional.empty());
        expected.put("2147483647.5", Optional.empty());
        expected.put("true", Optional.empty());
        Map<String, Optional<Integer>> observed = new LinkedHashMap<>();
        expected.keySet().forEach(raw -> observed.put(raw, index("baseYear", read(raw)).baseYear()));
        assertEquals(expected, observed);
    }

    @Test
    void aRepetitionCountIsTheCountA12Binds() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("3.7", 3);
        expected.put("\"\\u0663\"", 3);
        expected.put("\"\\uFF13\"", 3);
        // A primitive `int`: on kernel 31 a value with no value leaves no model, read as the default (KF593).
        expected.put("\"null\"", 1);
        expected.put("\"\"", 1);
        expected.put("null", 1);
        Map<String, Integer> observed = new LinkedHashMap<>();
        expected.keySet().forEach(raw -> observed.put(raw, index("repeatability", read(raw)).maxRepetitions("/Register/Claims")));
        assertEquals(expected, observed);
    }

    /**
     * A number token whose {@code BigDecimal} scale is {@code Integer.MIN_VALUE} leaves A12 with no model, though the
     * JDK constructs it (KERNEL-FINDINGS KF593); it is written as raw text, because a parsed tree prints it as 0.
     */
    @Test
    void aScaleTheFastParserRefusesBindsNoCount() {
        String json = DocumentModelDraft.named("claims").locale("en_US")
                .rootGroup("Register", r -> r.group("Claims", c -> c.repeatable(5).numberField("Amount", f -> f.fractionDigits(0))))
                .build().toJson();
        String withScale = json.replace("\"repeatability\" : 5", "\"repeatability\" : 0e2147483648");
        assertEquals(false, json.equals(withScale), "the raw lexeme replaced the count");
        assertEquals(1, ModelIndex.of(withScale, ReferenceResolvers.selfContained()).maxRepetitions("/Register/Claims"));
    }
}
