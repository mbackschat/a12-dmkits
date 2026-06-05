package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * The {@code schema} verb (CLI-SPEC §4) — emits the JSON Schema of a verb's input or output, so an
 * agent can learn a structure's contract <b>on demand</b> instead of inferring it from one example.
 * Schemas are never attached to the verbs' own responses (that would bloat every call); they live
 * here, fetched when wanted. {@code schema explain} documents the {@code tree.op → operators[].id}
 * backlink; {@code schema add}/{@code batch} document the verbs' JSON <em>inputs</em>.
 *
 * <p>Schemas are classpath resources under {@code /schema/<name>.schema.json}, drift-locked by
 * {@code SchemaVerbTest}.
 */
@Command(name = "schema", mixinStandardHelpOptions = true,
        description = "Emit the JSON Schema of a verb's input/output on demand (lists names when bare).")
final class SchemaCommand extends BaseCommand {

    /** The verb I/O structures that have a published schema (CI-locked against drift). */
    static final List<String> NAMES = List.of("explain", "describe", "validate", "ingest", "add", "batch");

    @Parameters(index = "0", arity = "0..1", paramLabel = "<name>",
            description = "one of: explain, describe, validate, ingest (outputs); add, batch (inputs)")
    String name;

    @Override
    public Integer call() {
        if (name == null) {
            out().print("Available schemas (use `schema <name>`): " + String.join(", ", NAMES) + "\n");
            return 0;
        }
        if (!NAMES.contains(name)) {
            return fail("unknown schema: " + name + "   (available: " + String.join(", ", NAMES) + ")");
        }
        String resource = "/schema/" + name + ".schema.json";
        try (InputStream in = SchemaCommand.class.getResourceAsStream(resource)) {
            if (in == null) {
                return fail("schema resource missing: " + resource);
            }
            out().print(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return 0;
        } catch (IOException e) {
            return fail("cannot read schema: " + e.getMessage());
        }
    }
}
