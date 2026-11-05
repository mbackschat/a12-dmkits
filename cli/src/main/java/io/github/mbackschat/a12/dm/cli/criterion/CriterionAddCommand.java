package io.github.mbackschat.a12.dm.cli.criterion;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.JsonInput;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.UniquenessCriterion;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool criterion add <spec.json>} — declare a document uniqueness criterion and write the model back (in
 * place; {@code -o}/{@code --dry-run}). The spec is one stored entry ({@code schema criterion add}); a name the model
 * already uses is refused, and a criterion A12 does not admit is a {@code rejected} envelope in A12's own sentence.
 */
@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Declare a document uniqueness criterion from a <spec.json> in A12's stored entry shape.")
final class CriterionAddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<spec.json>",
            description = "the criterion as A12 stores it: {name, fields:[{fullName}], errorMessage:[{locale, text}]} "
                    + "(see `schema criterion add`; an entry from `criterion read` is a valid spec)."
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
        UniquenessCriterion criterion;
        try {
            JsonNode spec = MAPPER.readTree(JsonInput.read(specPath, "<spec.json>"));
            Optional<Integer> rejected = rejectInvalidSpec("criterion-add", spec);
            if (rejected.isPresent()) {
                return rejected.get();
            }
            criterion = CriterionSpec.parse(spec);
        } catch (IllegalArgumentException badSpec) {
            return rejectSpecValue("criterion spec", specPath, badSpec);
        } catch (IOException e) {
            return refuseUnreadableInput("<spec.json>", specPath, e);
        }
        ModelDocument doc = ModelDocument.read(modelJson);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.criterionNameFree(doc, criterion.name()))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }
        String resultJson;
        try {
            resultJson = doc.addUniquenessCriterion(criterion).validate(workspace.resolverFor(model)).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("the uniqueness criterion is rejected by the kernel", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("added", criterion.name());
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would add uniqueness criterion " + criterion.name(),
                        "added uniqueness criterion " + criterion.name())
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
