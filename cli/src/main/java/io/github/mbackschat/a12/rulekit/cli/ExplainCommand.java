package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.rulekit.adapter.Ingest;
import io.github.mbackschat.a12.rulekit.adapter.KernelAdapter;
import io.github.mbackschat.a12.rulekit.context.ModelContext;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code explain} verb (CLI-SPEC §4 / F9) — project an existing rule's condition into a structured
 * {@code RuleExplanation} (a flat operator glossary + a structural {@code tree} whose nodes backlink
 * into it, referenced fields, iteration scope, an NL gloss). Ingests the rule first (F10), then explains
 * the resulting AST — the same mechanism that works on a hand-built condition.
 */
@Command(name = "explain", mixinStandardHelpOptions = true,
        description = "Project an existing rule's condition as a structured RuleExplanation (glossary + tree).")
final class ExplainCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model file")
    String model;

    @Option(names = "--rule", required = true, paramLabel = "<ruleFullName>",
            description = "full name of the rule to explain, e.g. /Order/SomeRule")
    String rule;

    @Override
    public Integer call() {
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            BoundModel boundModel = BoundModel.ofJson(modelJson);
            Ingest ingest = KernelAdapter.of(boundModel).ingest(rule);
            if (ingest instanceof Ingest.Converted converted) {
                out().print(new ModelContext(boundModel).explain(converted.condition()).toJson() + "\n");
            } else if (ingest instanceof Ingest.Opaque opaque) {
                // can't explain a construct the converter doesn't model yet — report it, don't fail
                ObjectNode root = MAPPER.createObjectNode();
                root.put("rule", rule);
                root.put("explained", false);
                root.put("reason", opaque.reason());
                root.put("canonicalText", opaque.canonicalText());
                out().print(pretty(root));
            }
            return 0;
        } catch (RuntimeException e) {
            return fail("explain failed: " + e.getMessage());
        }
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize explain result", e);
        }
    }
}
