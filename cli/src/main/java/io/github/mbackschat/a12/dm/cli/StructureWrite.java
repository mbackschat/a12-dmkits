package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;

/**
 * Shared write/emit for the structure-add verbs ({@code add-field}/{@code add-group}): the {@code modify}
 * convention — print a summary, write the model back in place ({@code -o} redirects, {@code --dry-run}
 * writes nothing) — and the kernel-reject path, which surfaces the kernel's {@code MVK_*} diagnostics
 * (the same vocabulary {@code validate}/{@code check} use). The protected {@code out()}/{@code err()}/
 * {@code fail()} are reachable here because this helper sits in the same package as {@link BaseCommand}.
 */
final class StructureWrite {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructureWrite() {
    }

    /** Print {@code summary}, then write {@code resultJson} (in place, or to {@code outputPath}) unless {@code dryRun}. */
    static int write(BaseCommand cmd, ObjectNode summary, String resultJson, String target,
            String model, String outputPath, boolean dryRun) {
        summary.put("written", !dryRun);
        cmd.out().print(toJson(summary));
        if (dryRun) {
            cmd.err().println("(dry run — " + target + " not written)");
            return 0;
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return cmd.fail("cannot write output: " + e.getMessage());
        }
        cmd.err().println("wrote " + target + " → " + dest);
        return 0;
    }

    /** The edit left the model kernel-invalid: surface the kernel diagnostics, exit 1. */
    static int reject(BaseCommand cmd, String target, ModelConsistencyException e) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("valid", false);
        root.put("element", target);
        ArrayNode diagnostics = root.putArray("diagnostics");
        if (e.notifications().isEmpty()) {
            diagnostics.add(e.getMessage());
        } else {
            e.notifications().forEach(n -> diagnostics.add(n.getMessage()));
        }
        cmd.err().println(toJson(root));
        return 1;
    }

    private static String toJson(ObjectNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            return node.toString() + "\n";
        }
    }
}
