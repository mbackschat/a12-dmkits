package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.schemakit.Dialect;
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter;
import io.github.mbackschat.a12.dm.schemakit.MappingProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code dmtool model import-jsonschema} — turn a JSON Schema into a kernel-valid Document Model. Import is
 * <b>best-effort by default</b>: every JSON Schema feature lands in a12 structure or rules (directly, via a
 * composed rule, or via a customizable strategy), every guess is flagged in the transcoding report (stderr),
 * and anything a12 genuinely cannot represent is <b>omitted, never blocked</b> — so the result is always a
 * usable model skeleton with corrective to-dos. {@code --strict} flips to the conservative stance (omit
 * everything uncertain); per-decision {@link MappingProfile} flags override either base (SCHEMAKIT-SPEC §4b/§4d).
 *
 * <p>To stdout (no {@code -o}) it emits the model <em>artifact</em> (DM-JSON), so it is classified in
 * {@code SchemaCommand.CONTRACT_EXEMPT} (no result-envelope contract on the artifact form); with {@code -o}
 * the model goes to the file and stdout carries a WRITE-confirmation envelope whose {@code changed.report}
 * carries the structured to-do list.
 */
@Command(name = "import-jsonschema", mixinStandardHelpOptions = true,
        description = "Import a JSON Schema as a kernel-valid model. BEST-EFFORT by default (import as much as "
                + "possible, flag every guess in the stderr report); --strict omits everything uncertain. The "
                + "model id comes from the schema's `title` (or a default). Prints the model JSON to stdout, or "
                + "-o writes it to a file + a write envelope.")
final class ModelImportJsonschemaCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = "--schema", required = true, paramLabel = "<file>",
            description = "the JSON Schema or OpenAPI document to import, JSON or YAML (its `title`, or --component, "
                    + "becomes the model id; else a default)")
    String schemaPath;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--dialect", paramLabel = "<dialect>",
            description = "the input's JSON-Schema dialect → AUTO (default; detect from the `openapi:` version "
                    + "field, else assume JSON Schema) | JSON_SCHEMA (Draft 2020-12: type:[X,null] nullability, "
                    + "numeric exclusiveMinimum, $defs) | OPENAPI_31 (OpenAPI 3.1/3.2 = 2020-12 + components/schemas "
                    + "refs + the discriminator object) | OPENAPI_30 (OpenAPI 3.0: nullable:true → optional, BOOLEAN "
                    + "exclusiveMinimum/Maximum). An OpenAPI document also needs --component.")
    Dialect dialect;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--component", paramLabel = "<name>",
            description = "for an OpenAPI document, the components/schemas entry to import as the model root (e.g. "
                    + "--component Order); its #/components/schemas/* refs resolve against the siblings. A bare "
                    + "schema needs none; a multi-schema OpenAPI doc without one is refused, listing the components.")
    String component;

    @Option(names = "--strict",
            description = "conservative mode: omit everything uncertain (a bare number, scalar arrays, maps, "
                    + "unions → omitted; formats → plain string; no recursion or unbounded-container expansion). "
                    + "The default is the opposite (best-effort). Per-flag overrides below still apply on top.")
    boolean strict;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--number-scale", paramLabel = "<strategy>",
            description = "a bare JSON `number` has no a12 scale → DEFAULT_SCALE (default; a decimal with "
                    + "--default-scale places) | INTEGER (scale 0 — DROPS any decimals) | OMIT (skip + to-do).")
    MappingProfile.NumberScale numberScale;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--default-scale", paramLabel = "<n>",
            description = "decimal places for --number-scale DEFAULT_SCALE (default 2)")
    Integer defaultScale;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--array-of-scalar", paramLabel = "<strategy>",
            description = "an array of scalars → REPEATABLE_SINGLE (default; a repeatable single-field group) | "
                    + "MULTI_SELECT (a12 multi-select, needs items.enum) | VALUE_LIST (string multi-select) | "
                    + "OMIT (skip + to-do).")
    MappingProfile.ArrayOfScalar arrayOfScalar;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--map", paramLabel = "<strategy>",
            description = "an object map (additionalProperties/patternProperties) → REPEATABLE_KEY_VALUE (default; "
                    + "a repeatable {key,value} group) | IGNORE (drop the dynamic keys) | OMIT (skip + to-do).")
    MappingProfile.MapStrategy mapStrategy;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--union", paramLabel = "<strategy>",
            description = "oneOf/anyOf of object variants → SUPERSET_RULE (default; union of fields + a "
                    + "discriminator's per-variant requiredness rules) | FIRST_VARIANT (only the first) | OMIT.")
    MappingProfile.UnionStrategy unionStrategy;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--string-format", paramLabel = "<strategy>",
            description = "how a JSON Schema string `format` (email/uuid/…) maps → PATTERN (default; a standard "
                    + "regex where known) | CUSTOM_TYPE (a custom field type named after the format) | IGNORE "
                    + "(plain string). Temporal formats (date/date-time/time) always map to DATE/TIME kinds "
                    + "regardless. (Named --string-format, not --format, to stay distinct from the output-rendering "
                    + "--format on workspace list/graph.)")
    MappingProfile.FormatStrategy stringFormat;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--boolean", paramLabel = "<kind>",
            description = "a JSON boolean → BOOLEAN (default) | CONFIRM (a12 confirm/checkbox).")
    MappingProfile.BooleanKind booleanKind;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--enum-labels", paramLabel = "<strategy>",
            description = "where enum labels come from (JSON enums carry none) → DERIVE (default; label = value) | "
                    + "X_ENUM_NAMES (read x-enumNames) | BLANK (no labels).")
    MappingProfile.EnumLabels enumLabels;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--recursion-depth", paramLabel = "<n>",
            description = "how many levels to expand a recursive $ref (default 1; 0 = omit the recursion + to-do).")
    Integer recursionDepth;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--default-repeat-cap", paramLabel = "<n>",
            description = "max rows for an array/map with no maxItems/maxProperties (default 1000; 0 = omit the "
                    + "unbounded container + to-do). Capping it lets the importer reach into and recurse the items.")
    Integer defaultRepeatCap;

    @Option(names = "--out-dir", paramLabel = "<dir>",
            description = "import the WHOLE OpenAPI document as a BUNDLE — one model per components/schemas entry, "
                    + "written as <dir>/<Component>.dm.json and wired by includes (an object cross-reference becomes "
                    + "a mount; a recursive self-reference is expanded, since the kernel forbids a self-include). "
                    + "Mutually exclusive with --component and -o/--output (which import a single entry).")
    String outDir;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the imported model here instead of stdout; stdout then carries a write-confirmation "
                    + "envelope (with the structured report in changed.report)")
    String outputPath;

    @Override
    public Integer call() {
        if (outDir != null && outputPath != null) {
            return fail("--out-dir (whole-bundle) and -o/--output (single file) are mutually exclusive");
        }
        if (outDir != null && component != null) {
            return fail("--out-dir (whole-bundle) and --component (single entry) are mutually exclusive");
        }
        String schemaJson;
        try {
            schemaJson = Files.readString(Path.of(schemaPath));
        } catch (IOException e) {
            return refuseUnreadableInput("<schema.json>", schemaPath, e);
        }
        MappingProfile profile = buildProfile();
        Dialect resolvedDialect = dialect != null ? dialect : Dialect.AUTO;
        if (outDir != null) {
            return importBundle(schemaJson, profile, resolvedDialect);
        }
        JsonSchemaImporter.ImportResult result;
        try {
            result = new JsonSchemaImporter().importSchema(schemaJson, profile, resolvedDialect, component);
        } catch (RuntimeException e) {
            return fail("cannot import the schema: " + e.getMessage());
        }
        JsonSchemaCli.printReport(err(), result.report());
        if (outputPath != null) {
            try {
                Files.writeString(Path.of(outputPath), result.dmJson());
            } catch (IOException e) {
                return refuseUnwritableOutput("-o <file>", outputPath, e);
            }
            ObjectNode changed = MAPPER.createObjectNode();
            changed.set("report", JsonSchemaCli.reportNode(MAPPER, result.report()));   // the agent's to-do list, structured
            ResultEnvelope env = ResultEnvelope.applied(resultIdentity(), "imported JSON Schema → model", changed,
                    outputPath, ConsistencyEvidence.of(result));
            out().print(env.toJsonString());
            return env.exitCode();
        }
        out().print(result.dmJson());
        return 0;
    }

    /** The gate every component of {@code bundle} passed, or {@link ConsistencyEvidence#none()} if they differ. */
    private static ConsistencyEvidence sharedEvidence(JsonSchemaImporter.BundleResult bundle) {
        var gates = bundle.models().values().stream().map(JsonSchemaImporter.ImportResult::consistencyGate)
                .distinct().toList();
        return gates.size() == 1 ? ConsistencyEvidence.of(bundle.models().values().iterator().next())
                : ConsistencyEvidence.none();
    }

    /** Import the whole OpenAPI document as a bundle → one {@code <out-dir>/<Component>.dm.json} per component,
     *  each report to stderr, and a write envelope listing the models in {@code changed.models}. */
    private Integer importBundle(String schemaJson, MappingProfile profile, Dialect resolvedDialect) {
        JsonSchemaImporter.BundleResult bundle;
        try {
            bundle = new JsonSchemaImporter().importBundle(schemaJson, profile, resolvedDialect);
        } catch (RuntimeException e) {
            return fail("cannot import the bundle: " + e.getMessage());
        }
        Path dir = Path.of(outDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return refuseUnwritableOutput("--out-dir <dir>", outDir, e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        ArrayNode models = changed.putArray("models");
        List<String> landed = new ArrayList<>();
        for (var entry : bundle.models().entrySet()) {
            Path file = dir.resolve(entry.getKey() + ".dm.json");
            try {
                Files.writeString(file, entry.getValue().dmJson());
            } catch (IOException e) {
                return refuseUnwritableOutput(resultIdentity(), "--out-dir <dir>", file.toString(), e, landed);
            }
            landed.add(file.toString());
            JsonSchemaCli.printReport(err(), entry.getValue().report());
            ObjectNode m = models.addObject();
            m.put("id", entry.getKey());
            m.put("file", file.toString());
            m.set("report", JsonSchemaCli.reportNode(MAPPER, entry.getValue().report()));
        }
        // One receipt per component, so the bundle can attest only what ALL of them agree on: a bundle whose
        // components gated differently (some synthesized rules, some purely structural) has no single answer,
        // and picking one component's would attest a model the others never went through.
        ResultEnvelope env = ResultEnvelope.applied(resultIdentity(),
                "imported OpenAPI bundle → " + bundle.models().size() + " models", changed, outDir,
                sharedEvidence(bundle));
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * Resolve the profile: start from the base ({@code --strict} → conservative, else best-effort) and apply
     * only the flags the caller actually set (an unset flag is {@code null}/{@code Integer null}). So
     * {@code --strict --array-of-scalar MULTI_SELECT} = strict base with that one override.
     */
    private MappingProfile buildProfile() {
        MappingProfile base = strict ? MappingProfile.strict() : MappingProfile.defaults();
        return new MappingProfile(
                numberScale != null ? numberScale : base.numberScale(),
                defaultScale != null ? defaultScale : base.defaultScale(),
                arrayOfScalar != null ? arrayOfScalar : base.arrayOfScalar(),
                mapStrategy != null ? mapStrategy : base.mapStrategy(),
                unionStrategy != null ? unionStrategy : base.unionStrategy(),
                stringFormat != null ? stringFormat : base.format(),
                booleanKind != null ? booleanKind : base.booleanKind(),
                enumLabels != null ? enumLabels : base.enumLabels(),
                recursionDepth != null ? recursionDepth : base.recursionDepth(),
                defaultRepeatCap != null ? defaultRepeatCap : base.defaultRepeatCap());
    }
}
