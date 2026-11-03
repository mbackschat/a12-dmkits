package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool include set-reference <reference> --to <new reference>} — repoint every mount of an included model,
 * and its header {@code modelReference}, at a different model IN PLACE (vs remove + re-add, which loses each mount's
 * position/options). The safety gate refuses if the model is not included ({@code RK_NO_SUCH_INCLUDE}); the kernel
 * re-validate (via {@code -w/--workspace} resolver) is the backstop when the new reference does not resolve.
 */
@Command(name = "set-reference", mixinStandardHelpOptions = true,
        description = "Repoint an include at a different referenced model in place (mounts kept).")
final class IncludeSetReferenceCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.INCLUDE_REFERENCE)
    @Parameters(index = "0", paramLabel = "<reference>", description = "the included model's id to repoint")
    String reference;

    @Option(names = "--to", required = true, paramLabel = "<reference>",
            description = "the model id every mount of the include should name instead")
    String newReference;

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
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.includeExists(doc, reference))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }
        String resultJson;
        try {
            doc.setIncludeReference(reference, newReference);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("the new reference left the model invalid", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("reference", reference).put("to", newReference);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would repoint include " + reference + " to " + newReference,
                        "repointed include " + reference + " to " + newReference)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
