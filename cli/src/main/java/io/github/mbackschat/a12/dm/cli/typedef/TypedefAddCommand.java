package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.cli.field.FieldSpec;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Optional;

/**
 * {@code dmtool typedef add --id <id>} — declare a reusable type definition and write the model back (in
 * place; {@code -o}/{@code --dry-run}). Two type shapes: the simple {@code --kind <K>} flag (a bare easy
 * kind), or a rich {@code <spec.json>} (positional) carrying the full per-kind config — the SAME shape a
 * field's type takes in {@code field add} ({@code schema typedef add}), so an ENUM type-def declares
 * {@code enum.values} and a restricted STRING declares {@code string.pattern} (G25). Fields then reference it
 * via {@code field add --typedef <id>}. Re-bound through the kernel; a kernel-invalid result is a
 * {@code rejected} envelope. ({@code --kind} and the spec are mutually exclusive.)
 */
@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Declare a reusable type definition (--id) — simple via --kind, or rich (ENUM/restricted) via a <spec.json>.")
final class TypedefAddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--id", required = true, paramLabel = "<id>", description = "the type definition's id")
    String id;

    @ParamKind(ParamKind.Kind.FIELD_KIND)
    @Option(names = "--kind", paramLabel = "<kind>",
            description = "the underlying easy kind: STRING | NUMBER | DATE | BOOLEAN | CONFIRM "
                    + "(for a rich ENUM/restricted type, pass a <spec.json> instead)")
    FieldKind kind;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<spec.json>",
            description = "a rich type spec (JSON): kind + per-kind config (see `schema typedef add`) — the "
                    + "alternative to --kind")
    String specPath;

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
        if ((kind != null) == (specPath != null)) {
            return fail("pass EITHER --kind <K> (a bare easy kind) OR a <spec.json> (rich config), not both");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        ModelResolver resolver = workspace.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);

        try {
            if (specPath != null) {
                JsonNode spec = MAPPER.readTree(Files.readString(Path.of(specPath)));
                Optional<Integer> rejected = rejectInvalidSpec("typedef-add", spec);
                if (rejected.isPresent()) {
                    return rejected.get();
                }
                FieldTypeSpec type = FieldSpec.parseType(spec, id);
                doc.addTypeDefinition(id, type);
            } else {
                doc.addTypeDefinition(id, kind);
            }
        } catch (IllegalArgumentException badSpec) {
            return fail("invalid typedef spec: " + badSpec.getMessage());
        } catch (IOException e) {
            return fail("cannot read typedef spec: " + e.getMessage());
        }

        String resultJson;
        try {
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("the type definition is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return failEditGate(e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("added", id);
        // echo the persisted kind through the resolver — the clean ValueKind name, same as `typedef read`
        changed.put("kind", doc.typeDefinitionKind(id).orElseThrow());
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would add typedef " + id,
                        "added typedef " + id)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
