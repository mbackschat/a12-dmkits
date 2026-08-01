package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.schemakit.Dialect;
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaExporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model export-jsonschema} — render the {@code -m} model as a JSON Schema. Export is
 * <b>best-effort</b> (the asymmetry SCHEMAKIT-SPEC §4d documents): structure + field config + enums + a
 * typeDefinition map to native JSON Schema, a Tier-A rule projects to a native keyword (e.g. {@code pattern}),
 * and everything else rides {@code x-a12-rule}/{@code x-a12-computation} carriage so an a12↔a12 round-trip is
 * lossless; temporal/custom field kinds fall back to {@code string}. The transcoding report (stderr) states
 * every disposition.
 *
 * <p>To stdout (no {@code -o}) it emits the JSON Schema <em>artifact</em>, so it is classified in
 * {@code SchemaCommand.CONTRACT_EXEMPT}; with {@code -o} the schema goes to the file and stdout carries a
 * WRITE-confirmation envelope.
 */
@Command(name = "export-jsonschema", mixinStandardHelpOptions = true,
        description = "Export the -m model as a JSON Schema (structure natively; rules/computations ride "
                + "x-a12-* carriage; the report on stderr states every disposition). Prints the schema to "
                + "stdout, or -o writes it to a file.")
final class ModelExportJsonschemaCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--dialect", paramLabel = "<dialect>",
            description = "the output JSON-Schema dialect → JSON_SCHEMA (default; Draft 2020-12, $defs) | OPENAPI_31 "
                    + "(OpenAPI 3.1/3.2) | OPENAPI_30 (OpenAPI 3.0). a12 has no null and inclusive-only bounds, so the "
                    + "dialect mainly drives --wrap-openapi's envelope version + the ref location; x-a12-* carriage is "
                    + "a valid extension in every version.")
    Dialect dialect;

    @Option(names = "--wrap-openapi",
            description = "wrap the schema in a minimal OpenAPI document under components/schemas (refs → "
                    + "#/components/schemas/…), a drop-in vs the bare Schema Object (which uses $defs).")
    boolean wrapOpenApi;

    @ParamKind(ParamKind.Kind.FILE)
    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the JSON Schema here instead of stdout; stdout then carries a write-confirmation envelope")
    String outputPath;

    @Override
    public Integer call() {
        String dmJson = readModelJson(model());
        if (dmJson == null) {
            return 2;
        }
        Dialect resolvedDialect = dialect != null ? dialect : Dialect.JSON_SCHEMA;
        JsonSchemaExporter.ExportResult result;
        try {
            result = new JsonSchemaExporter().export(dmJson, resolvedDialect, wrapOpenApi);
        } catch (RuntimeException e) {
            return fail("cannot export the model: " + e.getMessage());
        }
        JsonSchemaCli.printReport(err(), result.report());
        if (outputPath != null) {
            try {
                Files.writeString(Path.of(outputPath), result.jsonSchema());
            } catch (IOException e) {
                return fail("cannot write output: " + e.getMessage());
            }
            ObjectNode changed = MAPPER.createObjectNode();
            changed.set("report", JsonSchemaCli.reportNode(MAPPER, result.report()));   // structured dispositions in the envelope
            ResultEnvelope env = ResultEnvelope.applied("model", "export-jsonschema",
                    "exported model → JSON Schema", changed, outputPath, ConsistencyEvidence.none());
            out().print(env.toJsonString());
            return env.exitCode();
        }
        out().print(result.jsonSchema());
        return 0;
    }
}
