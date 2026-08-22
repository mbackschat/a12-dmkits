package io.github.mbackschat.a12.dm.cli.computation;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.RejectionBasis;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.cli.JsonInput;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool computation modify <computationPath> --spec <file>} — re-express a whole computation,
 * keeping the same computed field, validate against the kernel, and write the model back. The before/after
 * {@code RenderedDiff} rides {@code changed}; a kernel-rejected re-expression yields a {@code rejected}
 * envelope. The {@code --spec} must re-express the SAME computation (matching computedField/name).
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Re-express a computation (--spec) and write the model back (in place; -o/--dry-run).")
final class ComputationModifyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.COMPUTATION_PATH)
    @Parameters(index = "0", paramLabel = "<computationPathInModel>",
            description = "the computation's full name — its slash-path in the model, e.g. /Order/TotalComp")
    String computation;

    @Option(names = "--spec", required = true, paramLabel = "<computation-spec.json>",
            description = "the re-expressed computation as a computation-spec JSON file."
                    + JsonInput.HELP)
    String specPath;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — print the diff and write NOTHING")
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
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.computationExists(KernelAdapter.ofJson(modelJson, resolver), computation))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }
        JsonNode spec;
        try {
            spec = MAPPER.readTree(JsonInput.read(specPath, "<computation-spec.json>"));
        } catch (IOException e) {
            return refuseUnreadableInput("--spec", specPath, e);
        }
        Optional<Integer> rejected = rejectInvalidSpec("computation-add", spec);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        ComputationSpec.Parsed parsed = ComputationSpec.parse(spec);
        if (!parsed.ok()) {
            return fail(parsed.error());
        }
        if (!parsed.fullName().equals(computation)) {
            return fail("the --spec re-expresses " + parsed.fullName() + ", not the computation " + computation
                    + " (a re-expression keeps the same computation — match computedField/name)");
        }

        String modifiedJson;
        try {
            modifiedJson = ModelWriter.replaceComputation(modelJson, computation, parsed.draft(), parsed.messages());
        } catch (RuntimeException e) {
            return fail("cannot apply the new computation: " + e.getMessage());
        }
        BoundModel rebound;
        try {
            rebound = BoundModel.ofJson(modifiedJson, resolver);
        } catch (RuntimeException e) {
            Diagnostic d = Diagnostic.precheck(Optional.empty(),
                    "the re-expression is rejected by the kernel: " + e.getMessage(),
                    new Diagnostic.Where(computation, Optional.empty()), Optional.empty(), Optional.empty());
            // See RuleModifyCommand: a ModelConsistencyException is the gate's own verdict (valid:false + its
            // arbitrator); any other RuntimeException means no verdict was reached, so neither is claimed.
            ResultEnvelope env = ResultEnvelope.rejected(resultIdentity(), "re-expression rejected by the kernel", List.of(d),
                    e instanceof ModelConsistencyException rejection
                            ? RejectionBasis.kernel(ConsistencyEvidence.of(rejection))
                            : RejectionBasis.preflight());
            out().print(env.toJsonString());
            return env.exitCode();
        }

        JsonNode changed;
        try {
            ReadComputation modified = KernelAdapter.ofJson(modifiedJson, resolver).readComputation(computation);
            if (!(modified instanceof ReadComputation.Converted modConverted)) {
                return fail("the re-expressed computation did not read cleanly (Opaque) — cannot render");
            }
            RenderedDiff diff = KernelAdapter.ofJson(modelJson, resolver)
                    .diffComputation(computation, modConverted.computation());
            changed = MAPPER.readTree(diff.toJson());
        } catch (IOException | RuntimeException e) {
            return fail("re-expression failed: " + e.getMessage());
        }

        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would re-express " + computation,
                        "re-expressed " + computation)
                .changed(changed)
                .json(modifiedJson)
                .gatedBy(ConsistencyEvidence.of(rebound))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
