package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.JsonInput;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.cli.field.FieldSpec;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool typedef modify --id <id>} — change a reusable type definition's config <b>in one place</b>;
 * every field typed by it (via {@code field add --typedef <id>}) inherits the change automatically — a
 * typedef is shared <em>by reference</em>, so there is no per-field rewrite (probe 10: without this, a
 * single-place type change forced an {@code inline → field modify each → extract} round-trip). Same two
 * shapes as {@code typedef add}: a simple {@code --kind <K>} re-type, or a rich {@code <spec.json>} carrying
 * the full per-kind config ({@code schema typedef modify}). It is a Local config edit (not a reference-
 * rewriting refactor): the typedef must already exist (else {@code RK_NO_SUCH_TYPEDEF}, nothing written),
 * and the changed model is re-bound through the kernel — if the propagated change breaks a typed field or a
 * dependent rule/computation, the result is a {@code rejected} envelope and nothing is written (atomic).
 * ({@code --kind} and the spec are mutually exclusive.)
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Change a type definition's config in one place (--id); typed fields inherit it. Simple via --kind, or rich via a <spec.json>.")
final class TypedefModifyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.TYPEDEF_ID)
    @Option(names = "--id", required = true, paramLabel = "<id>", description = "the type definition to modify (must already exist)")
    String id;

    @ParamKind(ParamKind.Kind.FIELD_KIND)
    @Option(names = "--kind", paramLabel = "<kind>",
            description = "re-type to this bare easy kind: STRING | NUMBER | DATE | BOOLEAN | CONFIRM "
                    + "(for a rich ENUM/restricted config, pass a <spec.json> instead)")
    FieldKind kind;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<spec.json>",
            description = "a rich type spec (JSON): kind + per-kind config (see `schema typedef modify`) — the "
                    + "alternative to --kind."
                    + JsonInput.HELP)
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
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);
        // the existence precheck runs BEFORE the --kind/spec arg-combo check, so a missing typedef always
        // gives the structured RK_NO_SUCH_TYPEDEF (not a bare usage error) regardless of the other args.
        if (!doc.typeDefinitionIds().contains(id)) {
            return refuseNotFound(RkCode.NO_SUCH_TYPEDEF, "no type definition '" + id + "'",
                    id, "modify an existing type definition (list them with `typedef read` — no arg); "
                            + "to declare a new one use `typedef add`. type definitions: " + doc.typeDefinitionIds());
        }
        if ((kind != null) == (specPath != null)) {
            return fail("pass EITHER --kind <K> (a bare easy kind) OR a <spec.json> (rich config), not both");
        }

        try {
            if (specPath != null) {
                JsonNode spec = MAPPER.readTree(JsonInput.read(specPath, "<spec.json>"));
                Optional<Integer> rejected = rejectInvalidSpec("typedef-add", spec);
                if (rejected.isPresent()) {
                    return rejected.get();
                }
                FieldTypeSpec type = FieldSpec.parseType(spec, id);
                doc.setTypeDefinitionType(id, type);
            } else {
                doc.setTypeDefinitionType(id, kind);
            }
        } catch (IllegalArgumentException badSpec) {
            return rejectSpecValue("typedef spec", specPath, badSpec);
        } catch (IOException e) {
            return refuseUnreadableInput("<spec.json>", specPath, e);
        }

        String resultJson;
        try {
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("the modified type definition is rejected by the kernel", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("modified", id);
        // echo the persisted kind through the resolver — the clean ValueKind name, same as `typedef read`
        changed.put("kind", doc.typeDefinitionKind(id).orElseThrow());
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would modify typedef " + id,
                        "modified typedef " + id)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
