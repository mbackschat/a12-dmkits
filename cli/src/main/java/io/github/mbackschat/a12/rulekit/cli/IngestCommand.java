package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.rulekit.adapter.Ingest;
import io.github.mbackschat.a12.rulekit.adapter.KernelAdapter;
import io.github.mbackschat.a12.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.rulekit.dsl.DslRenderer;

/**
 * The {@code ingest} verb (CLI-SPEC §4) — read an <em>existing</em> rule's condition back into rulekit's
 * typed AST (F10), via the opt-in kernel adapter. Tolerant: a construct the converter doesn't model
 * yet comes back as {@code Opaque} (preserved verbatim + a reason), never an error. This is the
 * read-back foundation that {@code explain} and the AST inverted-requiredness lint build on.
 *
 * <p>Usage: {@code ingest <model.json> --rule <ruleFullName>}.
 */
final class IngestCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IngestCommand() {
    }

    static int run(String[] args, Appendable out, Appendable err) {
        if (args.length == 0) {
            return fail(err, "usage: ingest <model.json> --rule <ruleFullName>");
        }
        String modelPath = args[0];
        String rule = null;
        for (int i = 1; i < args.length; i++) {
            if ("--rule".equals(args[i]) && i + 1 < args.length) {
                rule = args[++i];
            } else {
                return fail(err, "unknown option: " + args[i] + "\nusage: ingest <model.json> --rule <ruleFullName>");
            }
        }
        if (rule == null) {
            return fail(err, "ingest needs --rule <ruleFullName>");
        }

        String modelJson;
        try {
            modelJson = Files.readString(Path.of(modelPath));
        } catch (IOException e) {
            return fail(err, "cannot read model: " + e.getMessage());
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
            append(out, pretty(root));
            return 0;
        } catch (RuntimeException e) {
            return fail(err, "ingest failed: " + e.getMessage());
        }
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize ingest result", e);
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
