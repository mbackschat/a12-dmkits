package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

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
    @Option(names = "--group", paramLabel = "<path>",
            description = "the group to add the field to, by full name-path, e.g. /Order (flags mode)")
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
                description = "the field kind: STRING | NUMBER | DATE | BOOLEAN | CONFIRM")
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
    IncludeDirOption includes = new IncludeDirOption();

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
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);

        String effGroup;
        String target;
        ObjectNode changed = MAPPER.createObjectNode();
        try {
            if (specPath != null) {
                JsonNode spec = MAPPER.readTree(Files.readString(Path.of(specPath)));
                FieldSpec.Parsed parsed = FieldSpec.parse(spec);
                effGroup = parsed.group();
                target = effGroup + "/" + parsed.field().name();
                if (refused(doc, effGroup, "field", "add")) {
                    return 2;
                }
                doc.addField(effGroup, parsed.field());
                changed.put("added", target);
                changed.put("kind", spec.hasNonNull("typedef") ? "TypeDefType" : spec.path("kind").asText());
            } else {
                if (group == null || name == null || type == null) {
                    return fail("flags mode needs --group + --name + (--kind | --typedef)");
                }
                effGroup = group;
                target = group + "/" + name;
                if (refused(doc, effGroup, "field", "add")) {
                    return 2;
                }
                if (type.typedef != null) {
                    doc.addTypedefField(group, name, type.typedef);
                    changed.put("kind", "TypeDefType");
                } else {
                    doc.addField(group, name, type.kind);
                    changed.put("kind", type.kind.name());
                }
                changed.put("added", target);
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
            return fail(e.getMessage());
        }
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("field", "add", "would add field " + target, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("field", "add", "added field " + target, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /** Emit the safety-gate refusal (group must exist) and report whether it fired. */
    private boolean refused(ModelDocument doc, String groupPath, String target, String op) {
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.exists(doc, groupPath, true))
                .firstFailure();
        if (refusal.isPresent()) {
            refuse(target, op, refusal.get());
            return true;
        }
        return false;
    }
}
