package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Holds every bundled schema's {@code description}/{@code title} prose to the current contract, stated once, within
 * its budget. Between the last agent probe (2026-08-02) and 2026-11-06 this prose grew from 78k to 125k characters:
 * corrections were appended instead of rewritten, one paragraph was pasted twice, and the three per-kind spec schemas
 * restate each other, so every fact was paid for three times.
 */
class SchemaDescriptionGuardTest {

    private static final Path SCHEMA_DIR = Path.of("src/main/resources/schema");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void descriptionsCarryNoHistoryAndNoRepeatedSentence() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, List<String>> schema : prose().entrySet()) {
            schema.getValue().forEach(text -> hits.addAll(AgentProse.proseDefects(schema.getKey(), text)));
        }
        assertTrue(hits.isEmpty(), () -> AgentProse.proseFailure(hits));
    }

    @Test
    void everySchemasProseStaysWithinItsBudget() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        prose().forEach((file, texts) -> actual.put(file, texts.stream().mapToInt(String::length).sum()));
        List<String> defects = AgentProse.budgetDefects(actual, AgentProse.budget("schemas"));
        assertTrue(defects.isEmpty(), () -> AgentProse.budgetFailure("schemas", defects));
    }

    /** Each bundled schema's {@code description}/{@code title} strings, keyed by file name. */
    private static Map<String, List<String>> prose() throws IOException {
        Map<String, List<String>> prose = new TreeMap<>();
        try (Stream<Path> files = Files.list(SCHEMA_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".schema.json")).sorted().toList()) {
                List<String> texts = new ArrayList<>();
                collect(MAPPER.readTree(file.toFile()), texts::add);
                prose.put(file.getFileName().toString(), texts);
            }
        }
        return prose;
    }

    private static void collect(JsonNode node, Consumer<String> sink) {
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isTextual() && (entry.getKey().equals("description") || entry.getKey().equals("title"))) {
                sink.accept(value.asText());
            }
        });
        node.forEach(child -> {
            if (child.isContainerNode()) {
                collect(child, sink);
            }
        });
    }
}
