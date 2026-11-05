package io.github.mbackschat.a12.dm.cli.criterion;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool criterion remove <name>} — remove a document uniqueness criterion and write the model back. Nothing
 * refers to a criterion, so there is no safe-delete gate; removing one is what frees its fields for
 * {@code field remove} and {@code group remove}, which refuse while a criterion names them.
 */
@Command(name = "remove", mixinStandardHelpOptions = true,
        description = "Remove the document uniqueness criterion <name>.")
final class CriterionRemoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.CRITERION_NAME)
    @Parameters(index = "0", paramLabel = "<name>", description = "the criterion's name (see `criterion read`)")
    String name;

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
        ModelDocument doc = ModelDocument.read(modelJson);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.criterionExists(doc, name))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }
        String resultJson;
        try {
            resultJson = doc.removeUniquenessCriterion(name).validate(workspace.resolverFor(model)).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("removal left the model invalid", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("removed", name);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would remove uniqueness criterion " + name,
                        "removed uniqueness criterion " + name)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
