package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The {@code schema} verb (CLI-SPEC §4) — emits the JSON Schema of a verb's output, so an agent can
 * learn a structure's contract <b>on demand</b> instead of inferring it from one example. Schemas are
 * never attached to the verbs' own responses (that would bloat every call); they live here, fetched
 * when wanted. Notably {@code schema explain} documents the {@code tree.op → operators[].id} backlink.
 *
 * <p>Usage: {@code schema} (lists the available names) or {@code schema <name>} (prints that schema).
 * Schemas are classpath resources under {@code /schema/<name>.schema.json}, drift-locked by
 * {@code SchemaVerbTest} (every emitted top-level key must be declared).
 */
final class SchemaCommand {

    /** The verb outputs that have a published schema (CI-locked against drift). */
    static final List<String> NAMES = List.of("explain", "describe", "validate", "ingest");

    private SchemaCommand() {
    }

    static int run(String[] args, Appendable out, Appendable err) {
        if (args.length == 0) {
            append(out, "Available schemas (use `schema <name>`): " + String.join(", ", NAMES) + "\n");
            return 0;
        }
        String name = args[0];
        if (!NAMES.contains(name)) {
            return fail(err, "unknown schema: " + name + "   (available: " + String.join(", ", NAMES) + ")");
        }
        String resource = "/schema/" + name + ".schema.json";
        try (InputStream in = SchemaCommand.class.getResourceAsStream(resource)) {
            if (in == null) {
                return fail(err, "schema resource missing: " + resource);
            }
            append(out, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return 0;
        } catch (IOException e) {
            return fail(err, "cannot read schema: " + e.getMessage());
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
