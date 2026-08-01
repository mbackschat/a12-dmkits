package io.github.mbackschat.a12.dm.cli.field;

import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool field add} — add a field and write the model back (in place; {@code -o}/{@code --dry-run}).
 * Two input shapes: the <b>flags</b> for a simple field ({@code --group --name (--kind | --typedef)}), or a
 * rich <b>{@code <spec.json>}</b> (positional) that carries the full per-kind config + field metadata
 * (length/pattern/suggestions, scale/unit/flags, enum values+categories, custom type, descriptions, …) —
 * see {@code schema field add}. Re-bound through the kernel; a kernel-invalid result is a {@code rejected}
 * envelope.
 */
@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Add a field — simple via --group/--name/(--kind|--typedef), or rich via a <spec.json>.")
final class FieldAddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<spec.json>",
            description = "a rich field spec (JSON): kind + per-kind config + metadata (see `schema field add`) "
                    + "— the alternative to the --group/--name/--kind flags")
    String specPath;

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Option(names = {"--group", "--parent"}, paramLabel = "<path>",
            description = "the group to add the field to, by full name-path, e.g. /Order (flags mode). "
                    + "--parent is the alias matching every sibling add-verb (group/include/multiselect/attachment).")
    String group;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--name", paramLabel = "<name>", description = "the new field's name (flags mode)")
    String name;

    @ArgGroup
    FieldType type;

    /** Exactly one of: an easy kind, or a type-definition reference (flags mode). */
    static final class FieldType {
        @ParamKind(ParamKind.Kind.FIELD_KIND)
        @Option(names = "--kind", paramLabel = "<kind>",
                description = "the field kind, SIMPLE (config-less) form: STRING | NUMBER | DATE | BOOLEAN | "
                        + "CONFIRM. The config-bearing kinds — TIME | DATETIME | DATERANGE | DATEFRAGMENT | ENUM "
                        + "| CUSTOM — carry config, so they need a <spec.json> instead (see `schema field add`).")
        FieldKind kind;

        @ParamKind(ParamKind.Kind.TYPEDEF_ID)
        @Option(names = "--typedef", paramLabel = "<typeDefId>",
                description = "type the field by an existing type definition")
        String typedef;
    }

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        boolean flagsMode = group != null || name != null || type != null;
        if (specPath == null && !flagsMode) {
            return fail("pass a <spec.json>, or --group + --name + (--kind | --typedef)");
        }
        if (specPath != null && flagsMode) {
            return fail("pass EITHER a <spec.json> OR the --group/--name/--kind flags, not both");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        ModelResolver resolver = workspace.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);

        String effGroup;
        String target;
        ObjectNode changed = MAPPER.createObjectNode();
        try {
            if (specPath != null) {
                JsonNode spec = MAPPER.readTree(Files.readString(Path.of(specPath)));
                Optional<Integer> rejected = rejectUnknownKeys("field", "add", "field-add", spec);
                if (rejected.isPresent()) {
                    return rejected.get();
                }
                FieldSpec.Parsed parsed = FieldSpec.parse(spec);
                effGroup = parsed.group();
                target = effGroup + "/" + parsed.field().name();
                if (refused(doc, effGroup, "field", "add")) {
                    return 2;
                }
                doc.addField(effGroup, parsed.field());
                // G23: a custom required-field message attaches to the field's requirednessConfig, so it
                // applies AFTER the field (with its requiredness) is created
                java.util.Map<String, String> requiredMessages = FieldSpec.requiredMessages(spec);
                if (!requiredMessages.isEmpty()) {
                    doc.setRequiredErrorMessages(target, requiredMessages);
                }
                changed.put("added", target);
                if (spec.hasNonNull("typedef")) {
                    changed.put("typedef", spec.get("typedef").asText());
                }
                // echo the persisted kind through the same resolver `field read` uses — the clean ValueKind
                // name (a typedef-typed field → its underlying kind), so add and read speak one vocabulary
                changed.put("kind", doc.fieldKind(target).orElseThrow());
            } else {
                if (group == null || name == null || type == null) {
                    return fail("flags mode needs --group + --name + (--kind | --typedef)");
                }
                effGroup = group;
                target = group + "/" + name;
                if (refused(doc, effGroup, "field", "add")) {
                    return 2;
                }
                changed.put("added", target);   // key order matches the established field-add envelope
                if (type.typedef != null) {
                    doc.addTypedefField(group, name, type.typedef);
                    changed.put("typedef", type.typedef);
                } else {
                    doc.addField(group, name, type.kind);
                }
                changed.put("kind", doc.fieldKind(target).orElseThrow());
            }
        } catch (IllegalArgumentException badSpec) {
            return fail("invalid field spec: " + badSpec.getMessage());
        } catch (IOException e) {
            return fail("cannot read field spec: " + e.getMessage());
        }

        String resultJson;
        try {
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("field", "add", "the field is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return failEditGate(e);
        }
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("field", "add", "would add field " + target, changed,
                    ConsistencyEvidence.of(doc));
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("field", "add", "added field " + target, changed, dest,
                ConsistencyEvidence.of(doc));
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /** Emit the safety-gate refusal (group must exist) and report whether it fired. */
    private boolean refused(ModelDocument doc, String groupPath, String target, String op) {
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.exists(doc, groupPath, true))
                .require(SafetyChecks.notAnIncludeMount(doc, groupPath))
                .firstFailure();
        if (refusal.isPresent()) {
            refuse(target, op, refusal.get());
            return true;
        }
        return false;
    }
}
