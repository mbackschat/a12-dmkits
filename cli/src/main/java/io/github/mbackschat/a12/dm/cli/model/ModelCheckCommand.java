package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.Severity;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import io.github.mbackschat.a12.dm.rulekit.validate.ModelVersion;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code dmtool model check} — the STATIC validation verb: run the whole model's definitions through the real
 * kernel's consistency check (the F4 ground-truth step) and report the uniform result envelope. Covers BOTH
 * rule conditions AND computation expressions; takes no document instance (that is the runtime {@code model
 * eval}/{@code model compute}). The op always executes ({@code ok:true}); the model's consistency rides
 * {@code valid}, with the kernel's {@code MVK_*} notifications surfaced as enriched {@link Diagnostic}s.
 *
 * <p>The model-scope static-check verb — peer of the candidate-rule {@code rule check}; the runtime peers are
 * {@code model eval} (conditions) and {@code model compute} (computations). See the check/eval/compute
 * taxonomy in CLI-SPEC.
 */
@Command(name = "check", mixinStandardHelpOptions = true,
        description = "Statically CHECK the whole model's definitions (rules + computations) through the real "
                + "kernel; report {valid, diagnostics[]} (no document instance — exit 0 valid, 1 not).")
final class ModelCheckCommand extends BaseCommand {

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

        List<Diagnostic> diagnostics = new ArrayList<>();
        boolean valid;
        // The gate's own receipt, on BOTH branches: a successful bind hands one out, and a rejection carries one
        // through its exception. The verdict and its provenance are therefore produced together — never a
        // Backends.verification() stamped on afterwards, which would have said KERNEL_CONFIRMED regardless.
        ConsistencyEvidence evidence;
        try {
            evidence = ConsistencyEvidence.of(BoundModel.ofJson(json, workspace.resolverFor(model)));
            valid = true;
        } catch (ModelConsistencyException e) {
            valid = false;
            evidence = ConsistencyEvidence.of(e);
            for (Notification n : e.notifications()) {
                diagnostics.add(KernelDiagnostics.enriched(n, null));
            }
        }
        // version awareness (the kernel is the authority; we only classify): code a version-mismatch ERROR
        // as RK_MODEL_VERSION_INCOMPATIBLE, or add a tolerant INFO RK_MODEL_VERSION_SKEW when the model's
        // version differs from this build's reference but the kernel still accepted it.
        diagnostics = ModelVersion.classify(json, diagnostics);

        long errors = diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).count();
        String summary = valid ? "model is valid" : "model has " + errors + " error(s)";
        ResultEnvelope env = ResultEnvelope.consistencyVerdict("model", "check", summary, valid, diagnostics, null,
                evidence);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
