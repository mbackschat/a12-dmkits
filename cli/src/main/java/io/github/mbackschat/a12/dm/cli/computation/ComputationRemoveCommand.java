package io.github.mbackschat.a12.dm.cli.computation;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool computation remove <computationPath>} — delete a computation and write the model back (in
 * place; {@code -o} redirects, {@code --dry-run} previews). Removal re-binds through the kernel.
 */
@Command(name = "remove", mixinStandardHelpOptions = true,
        description = "Remove a computation and write the model back (in place; -o redirects, --dry-run previews).")
final class ComputationRemoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.COMPUTATION_PATH)
    @Parameters(index = "0", paramLabel = "<computationPathInModel>",
            description = "the computation's full name — its slash-path in the model, e.g. /Order/TotalComp")
    String computation;

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
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        ModelResolver resolver = workspace.resolverFor(model);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.computationExists(KernelAdapter.ofJson(json, resolver), computation))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }
        String result;
        BoundModel reduced;
        try {
            // removeComputation re-binds through the gate, so the REDUCED model is the witness — the
            // pre-removal bind gated a model this verb is not writing.
            reduced = BoundModel.ofJson(json, resolver).removeComputation(computation);
            result = reduced.toJson();
        } catch (RuntimeException e) {
            return fail("cannot remove computation '" + computation + "': " + e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("removed", computation);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would remove computation " + computation,
                        "removed computation " + computation)
                .changed(changed)
                .json(result)
                .gatedBy(ConsistencyEvidence.of(reduced))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
