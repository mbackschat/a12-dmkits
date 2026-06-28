package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter;
import io.github.mbackschat.a12.dm.schemakit.MappingProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model import-jsonschema} — turn a JSON Schema into a kernel-valid Document Model. Import aims
 * for <b>total coverage</b>: every JSON Schema feature lands in a12 structure or rules (directly, via a
 * composed rule, or via a customizable strategy), and anything a12 genuinely cannot represent is
 * <b>omitted, never blocked</b> — so the result is always a usable model skeleton, and the transcoding report
 * (stderr) carries a corrective to-do for each omission. The choice-driven decisions are tunable via the
 * {@link MappingProfile} flags (see SCHEMAKIT-SPEC §4b/§4d).
 *
 * <p>To stdout (no {@code -o}) it emits the model <em>artifact</em> (DM-JSON), so it is classified in
 * {@code SchemaCommand.CONTRACT_EXEMPT} (no result-envelope contract on the artifact form); with {@code -o}
 * the model goes to the file and stdout carries an additive WRITE-confirmation envelope.
 */
@Command(name = "import-jsonschema", mixinStandardHelpOptions = true,
        description = "Import a JSON Schema as a kernel-valid model (structure + rules; unmappable features "
                + "are omitted with a to-do in the report on stderr). The model id comes from the schema's "
                + "`title` (or a default). Prints the model JSON to stdout, or -o writes it to a file.")
final class ModelImportJsonschemaCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.FILE)
    @Option(names = "--schema", required = true, paramLabel = "<file>",
            description = "the JSON Schema file to import (its `title` becomes the model id, else a default)")
    String schemaPath;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--number-scale", paramLabel = "<strategy>",
            description = "a bare JSON `number` (no multipleOf) has no a12 scale: OMIT (default), INTEGER, DEFAULT_SCALE")
    MappingProfile.NumberScale numberScale = MappingProfile.NumberScale.OMIT;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--default-scale", paramLabel = "<n>",
            description = "the decimal scale used when --number-scale=DEFAULT_SCALE (default 2)")
    int defaultScale = 2;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--array-of-scalar", paramLabel = "<strategy>",
            description = "an array of scalars: OMIT (default), MULTI_SELECT, VALUE_LIST, REPEATABLE_SINGLE")
    MappingProfile.ArrayOfScalar arrayOfScalar = MappingProfile.ArrayOfScalar.OMIT;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--map", paramLabel = "<strategy>",
            description = "a map (additionalProperties/patternProperties): REPEATABLE_KEY_VALUE (default), OMIT, IGNORE")
    MappingProfile.MapStrategy mapStrategy = MappingProfile.MapStrategy.REPEATABLE_KEY_VALUE;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--union", paramLabel = "<strategy>",
            description = "oneOf/anyOf of object variants: SUPERSET_RULE (default), FIRST_VARIANT, OMIT")
    MappingProfile.UnionStrategy unionStrategy = MappingProfile.UnionStrategy.SUPERSET_RULE;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--format", paramLabel = "<strategy>",
            description = "a string format (email/uuid/…): PATTERN (default), CUSTOM_TYPE, IGNORE (temporal "
                    + "formats always map to DATE/TIME kinds)")
    MappingProfile.FormatStrategy format = MappingProfile.FormatStrategy.PATTERN;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--boolean", paramLabel = "<kind>",
            description = "a JSON boolean: BOOLEAN (default) or CONFIRM")
    MappingProfile.BooleanKind booleanKind = MappingProfile.BooleanKind.BOOLEAN;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--enum-labels", paramLabel = "<strategy>",
            description = "where enum labels come from (JSON enums carry none): DERIVE (default), X_ENUM_NAMES, BLANK")
    MappingProfile.EnumLabels enumLabels = MappingProfile.EnumLabels.DERIVE;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--recursion-depth", paramLabel = "<n>",
            description = "bounded expansion of a recursive $ref (default 0 = omit the recursion)")
    int recursionDepth = 0;

    @ParamKind(ParamKind.Kind.FILE)
    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the imported model here instead of stdout; stdout then carries a write-confirmation envelope")
    String outputPath;

    @Override
    public Integer call() {
        String schemaJson;
        try {
            schemaJson = Files.readString(Path.of(schemaPath));
        } catch (IOException e) {
            return fail("cannot read schema: " + e.getMessage());
        }
        MappingProfile profile = new MappingProfile(numberScale, defaultScale, arrayOfScalar, mapStrategy,
                unionStrategy, format, booleanKind, enumLabels, recursionDepth);
        JsonSchemaImporter.ImportResult result;
        try {
            result = new JsonSchemaImporter().importSchema(schemaJson, profile);
        } catch (RuntimeException e) {
            return fail("cannot import the schema: " + e.getMessage());
        }
        JsonSchemaCli.printReport(err(), result.report());
        if (outputPath != null) {
            try {
                Files.writeString(Path.of(outputPath), result.dmJson());
            } catch (IOException e) {
                return fail("cannot write output: " + e.getMessage());
            }
            ObjectNode changed = MAPPER.createObjectNode();
            changed.set("report", JsonSchemaCli.reportNode(MAPPER, result.report()));   // the agent's to-do list, structured
            ResultEnvelope env = ResultEnvelope.applied("model", "import-jsonschema",
                    "imported JSON Schema → model", changed, outputPath);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        out().print(result.dmJson());
        return 0;
    }
}
