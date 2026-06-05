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

/**
 * The {@code explain} verb (CLI-SPEC §4 / F9) — project an existing rule's condition into a structured
 * {@code RuleExplanation} (operators with catalog meaning + gotchas, referenced fields, iteration
 * scope, an NL gloss). Ingests the rule first (F10), then explains the resulting AST — the same
 * mechanism that works on a hand-built condition.
 *
 * <p>Usage: {@code explain <model.json> --rule <ruleFullName>}.
 */
final class ExplainCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExplainCommand() {
    }

    static int run(String[] args, Appendable out, Appendable err) {
        if (args.length == 0) {
            return fail(err, "usage: explain <model.json> --rule <ruleFullName>");
        }
        String modelPath = args[0];
        String rule = null;
        for (int i = 1; i < args.length; i++) {
            if ("--rule".equals(args[i]) && i + 1 < args.length) {
                rule = args[++i];
            } else {
                return fail(err, "unknown option: " + args[i] + "\nusage: explain <model.json> --rule <ruleFullName>");
            }
        }
        if (rule == null) {
            return fail(err, "explain needs --rule <ruleFullName>");
        }

        String modelJson;
        try {
            modelJson = Files.readString(Path.of(modelPath));
        } catch (IOException e) {
            return fail(err, "cannot read model: " + e.getMessage());
        }
        try {
            BoundModel model = BoundModel.ofJson(modelJson);
            Ingest ingest = KernelAdapter.of(model).ingest(rule);
            if (ingest instanceof Ingest.Converted converted) {
                append(out, new ModelContext(model).explain(converted.condition()).toJson() + "\n");
            } else if (ingest instanceof Ingest.Opaque opaque) {
                // can't explain a construct the converter doesn't model yet — report it, don't fail
                ObjectNode root = MAPPER.createObjectNode();
                root.put("rule", rule);
                root.put("explained", false);
                root.put("reason", opaque.reason());
                root.put("canonicalText", opaque.canonicalText());
                append(out, pretty(root));
            }
            return 0;
        } catch (RuntimeException e) {
            return fail(err, "explain failed: " + e.getMessage());
        }
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize explain result", e);
        }
    }

    private static void append(Appendable out, String text) {
        try {
            out.append(text);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write output", e);
        }
    }

    private static int fail(Appendable err, String message) {
        try {
            err.append(message).append('\n');
        } catch (IOException ignored) {
            // best-effort diagnostic
        }
        return 2;
    }
}
