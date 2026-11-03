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
 * {@code dmtool include remove <reference>} — remove an include (its header model-reference <em>and</em> every
 * mount group) and write the model back. There is no proactive safe-delete gate for includes; a rule still
 * referencing the mounted content is caught by the kernel on re-bind — a {@code rejected} envelope.
 */
@Command(name = "remove", mixinStandardHelpOptions = true,
        description = "Remove an include (header reference + its mounts); a rule still using it is rejected.")
final class IncludeRemoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.INCLUDE_REFERENCE)
    @Parameters(index = "0", paramLabel = "<reference>", description = "the included model's id to remove")
    String reference;

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
            doc.removeInclude(reference);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("removal left the model invalid", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("removed", reference);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would remove include " + reference,
                        "removed include " + reference)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
