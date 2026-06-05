package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.Ingest;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code ingest} verb (CLI-SPEC §4) — read an <em>existing</em> rule's condition back into rulekit's
 * typed AST (F10), via the opt-in kernel adapter. Tolerant: a construct the converter doesn't model
 * yet comes back as {@code Opaque} (preserved verbatim + a reason), never an error. This is the
 * read-back foundation that {@code explain} and the AST inverted-requiredness lint build on.
 */
@Command(name = "ingest", mixinStandardHelpOptions = true,
        description = "Read an existing rule's condition back into rulekit's typed AST (tolerant: Converted or Opaque).")
final class IngestCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model file")
    String model;

    @Option(names = "--rule", required = true, paramLabel = "<ruleFullName>",
            description = "full name of the rule to read back, e.g. /Order/SomeRule")
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
            Ingest ingest = KernelAdapter.ofJson(modelJson).ingest(rule);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("rule", rule);
            if (ingest instanceof Ingest.Converted converted) {
                root.put("converted", true);
                root.put("condition", new DslRenderer(ConditionLanguage.EN).render(converted.condition()));
            } else if (ingest instanceof Ingest.Opaque opaque) {
                root.put("converted", false);
                root.put("canonicalText", opaque.canonicalText());
                root.put("reason", opaque.reason());
            }
            out().print(pretty(root));
            return 0;
        } catch (RuntimeException e) {
            return fail("ingest failed: " + e.getMessage());
        }
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize ingest result", e);
        }
    }
}
