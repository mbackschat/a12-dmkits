package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Guards the bundled {@code schema/*.json} surface against the two drifts {@link CommandReferencesResolveTest}
 * cannot see — it scans {@code dmtool <cmd>} patterns, not JSON examples or schema prose. That blind spot
 * shipped a {@code verb:"check"} in {@code batch}'s help, an {@code eval-doc} reference in a schema
 * description, and left five orphaned {@code add-*}/{@code remove} schema files in the binary:
 * <ol>
 *   <li><b>No orphan</b> — every bundled {@code schema/*.json} is reachable via {@link SchemaCommand} (its
 *       maps are the single source of truth), so a dead resource can't ship.</li>
 *   <li><b>No removed verb in agent-visible prose</b> — no schema {@code description}/{@code title}/example
 *       names a removed flat verb, so the self-describing surface can't point an agent at a verb that no
 *       longer exists. The {@code $id}/{@code $schema} identifier URLs are exempt: they are stable
 *       identifiers, not guidance, and are the only place a legacy basename survives
 *       ({@code eval-doc}/{@code compute-doc} — renaming those files would retire the exemption).</li>
 * </ol>
 */
class SchemaResourceSurfaceTest {

    private static final Path SCHEMA_DIR = Path.of("src/main/resources/schema");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Removed flat verbs (be0ce3b) in their <b>unambiguous compound</b> form — the simple names
     * ({@code read}, {@code add}, {@code check}, …) are still valid target-first OPS, so they are not
     * removed-verb tokens on their own.
     */
    private static final List<String> REMOVED_VERB_TOKENS =
            List.of("eval-doc", "compute-doc", "add-field", "add-group", "add-typedef", "add-include");

    @Test
    void everySchemaFileIsReachable() throws IOException {
        Set<String> referenced = SchemaCommand.servedSchemas().keySet();

        try (Stream<Path> files = Files.list(SCHEMA_DIR)) {
            List<String> orphans = files
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".schema.json"))
                    .map(n -> n.substring(0, n.length() - ".schema.json".length()))
                    .filter(base -> !referenced.contains(base))
                    .sorted().toList();
            assertTrue(orphans.isEmpty(),
                    () -> "orphaned schema files (bundled but reachable by no `schema` verb — delete them, or "
                            + "wire them into SchemaCommand): " + orphans);
        }
    }

    /**
     * A published schema may address a sibling by its {@code $id} rather than restate its shape — the
     * single-sourcing discipline {@code patterns.schema.json} was built on ("a restatement conforms to its
     * meta-schema, executes nothing, and drifts invisibly"). The cost is that the documents form a
     * <b>registry</b>: {@code schema result} alone does not resolve, because its {@code diagnostics} item
     * {@code $ref}s another document. That is only sound while every referenced document is itself obtainable,
     * so this guards the join — a {@code $ref} can never point at a document the tool will not hand over.
     */
    @Test
    void everyCrossDocumentRefTargetsASchemaTheToolServes() {
        Set<String> targets = SchemaCommand.crossDocumentReferences();
        Map<String, String> served = SchemaCommand.servedSchemas();
        List<String> dangling = targets.stream().filter(t -> !served.containsKey(t)).sorted().toList();
        assertTrue(dangling.isEmpty(),
                () -> "a published schema `$ref`s a document no `schema` invocation serves, so a consumer "
                        + "cannot resolve it (serve it from SchemaCommand, or inline the shape): " + dangling);

        // The join above passes vacuously on a scan that finds nothing, so two positive controls follow it —
        // one per nesting shape the scan must walk: `result` refs a sibling from an `items` object, `patterns`
        // refs three from inside a `oneOf` array. They run last so a genuinely dangling ref reports as
        // dangling rather than being masked by a control that is only asserting the scan still works.
        assertTrue(targets.contains("diagnostic"),
                () -> "the scan missed result.schema.json's `items.$ref` — it is broken, not clean: " + targets);
        assertTrue(targets.contains("rule-add"),
                () -> "the scan missed patterns.schema.json's `oneOf[].$ref` — it does not recurse into "
                        + "arrays: " + targets);
    }

    @Test
    void noSchemaDescribesARemovedVerb() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.list(SCHEMA_DIR)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".schema.json")).sorted().toList()) {
                scanProse(MAPPER.readTree(Files.readString(p)), p.getFileName().toString(), hits);
            }
        }
        assertTrue(hits.isEmpty(),
                () -> "schema prose names a removed verb (an agent reads these as guidance — use the "
                        + "target-first verb instead): " + hits);
    }

    /** Walk every string value except the {@code $id}/{@code $schema} identifier URLs, flagging a removed verb. */
    private static void scanProse(JsonNode node, String file, List<String> hits) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (!e.getKey().equals("$id") && !e.getKey().equals("$schema")) {
                    scanProse(e.getValue(), file, hits);
                }
            });
        } else if (node.isArray()) {
            node.forEach(n -> scanProse(n, file, hits));
        } else if (node.isTextual()) {
            for (String token : REMOVED_VERB_TOKENS) {
                if (node.asText().contains(token)) {
                    hits.add(file + ": \"" + token + "\"");
                }
            }
        }
    }
}
