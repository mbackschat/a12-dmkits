package io.github.mbackschat.a12.dm.cli.computation;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.JsonInput;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.RejectionBasis;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.lint.ComputationLinter;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool computation add <computation-spec.json>} — persist a computation (computed field +
 * alternatives), kernel-validated before + after, written back (in place; {@code -o}/{@code --dry-run}).
 * A rejection from EITHER kernel checker — the expression, or the error text — yields the same
 * {@code rejected} envelope, carrying that checker's own class and locus (nothing written).
 * See `schema computation add`.
 */
@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Persist a computation from a JSON computation-spec (kernel-validated before + after); "
                + "--dry-run is the read-only static preflight.")
final class ComputationAddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<computation-spec.json>",
            description = "the computation envelope as JSON (computedField, alternatives, "
                    + "messages, …)."
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
        // Read separately from the spec: sharing one catch attributed an unreadable MODEL to <computation-spec.json>,
        // naming an argument that was fine and a path the caller never gave for it.
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        JsonNode spec;
        try {
            spec = MAPPER.readTree(JsonInput.read(specPath, "<computation-spec.json>"));
        } catch (IOException e) {
            return refuseUnreadableInput("<computation-spec.json>", specPath, e);
        }
        Optional<Integer> rejected = rejectInvalidSpec("computation-add", spec);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        ComputationSpec.Parsed parsed = ComputationSpec.parse(spec);
        if (!parsed.ok()) {
            return rejectSpecParse(parsed.problem());
        }
        ModelResolver resolver = workspace.resolverFor(model);
        // G5: proactive author-time lints (forbidden constructs, overlapping preconditions) — they ride the
        // envelope whether the kernel accepts (as WARNINGs alongside APPLIED) or rejects (with the diagnostics).
        List<Diagnostic> lints = new ComputationLinter().lint(parsed.draft());
        ValidationResult check = new RuleValidator().check(modelJson, parsed.draft(), resolver);
        if (!check.ok()) {
            List<Diagnostic> diagnostics = new ArrayList<>(check.diagnostics());
            diagnostics.addAll(lints);
            ResultEnvelope env = ResultEnvelope.rejected(resultIdentity(),
                    "computation rejected by the kernel (" + check.diagnostics().size() + " finding(s))",
                    diagnostics, RejectionBasis.from(ConsistencyEvidence.of(check)));
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String persisted = ModelWriter.addComputation(modelJson, parsed.draft(), parsed.messages());
        BoundModel rebound;
        try {
            rebound = BoundModel.ofJson(persisted, resolver);
        } catch (ModelConsistencyException e) {
            // The error-text twin of RuleAddCommand's gate: a computation's message runs through the same
            // RuleErrortextChecker, so its rejection is this verb's verdict and rides the envelope.
            return emitKernelRejection("the computation is rejected by the kernel", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("computation", parsed.fullName());
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would add computation " + parsed.fullName(),
                        "added computation " + parsed.fullName())
                .changed(changed)
                .advisories(lints)
                .json(persisted)
                .gatedBy(ConsistencyEvidence.of(rebound))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
