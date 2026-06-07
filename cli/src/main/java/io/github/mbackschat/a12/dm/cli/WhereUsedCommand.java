package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code where-used} verb (F9a, CLI-SPEC §4) — the exact inverse of a rule's referenced entities:
 * given a field or group, list every rule whose condition <em>directly</em> references it. Uses the
 * kernel's own {@code getDirectlyReferencedEntities} via the adapter (no converter coverage needed), so
 * it works on every rule regardless of whether ingest models its construct.
 */
@Command(name = "where-used", mixinStandardHelpOptions = true,
        description = "List the rules whose condition directly references a given field or group.")
final class WhereUsedCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>",
            description = "the DM-JSON model FILE (a file path) — distinct from --entity, a path inside it")
    String model;

    @Option(names = "--entity", required = true, paramLabel = "<entityPath>",
            description = "the field or group's full (absolute) path inside the model, e.g. /Order/Quantity")
    String entity;

    @Override
    public Integer call() {
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            KernelAdapter adapter = KernelAdapter.ofJson(modelJson);
            Set<String> rules = adapter.whereUsed(entity);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("entity", entity);
            ArrayNode arr = root.putArray("rules");
            rules.forEach(arr::add);
            root.put("count", rules.size());
            out().print(pretty(root));
            return 0;
        } catch (RuntimeException e) {
            return fail("where-used failed: " + e.getMessage());
        }
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize where-used result", e);
        }
    }
}
