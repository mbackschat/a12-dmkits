package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mgmtp.a12.model.notification.RankedNotification;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import io.github.mbackschat.a12.dm.rulekit.validate.ModelVersion;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code dmtool model validate} — run the model through the real kernel (the F4 ground-truth step) and report
 * the uniform result envelope. The op always executes ({@code ok:true}); the model's consistency rides
 * {@code valid}, with the kernel's {@code MVK_*} notifications surfaced as enriched {@link Diagnostic}s.
 */
@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Run the model through the real kernel; report {valid, diagnostics[]} (exit 0 valid, 1 not).")
final class ModelValidateCommand extends BaseCommand {

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
        try {
            BoundModel.ofJson(json, workspace.resolverFor(model));
            valid = true;
        } catch (ModelConsistencyException e) {
            valid = false;
            for (RankedNotification n : e.notifications()) {
                diagnostics.add(KernelDiagnostics.enriched(n, null));
            }
        }
        // version awareness (the kernel is the authority; we only classify): code a version-mismatch ERROR
        // as RK_MODEL_VERSION_INCOMPATIBLE, or add a tolerant INFO RK_MODEL_VERSION_SKEW when the model's
        // version differs from this build's reference but the kernel still accepted it.
        diagnostics = ModelVersion.classify(json, diagnostics);

        long errors = diagnostics.stream().filter(d -> d.severity() == com.mgmtp.a12.model.notification.Severity.ERROR).count();
        String summary = valid ? "model is valid" : "model has " + errors + " error(s)";
        ResultEnvelope env = ResultEnvelope.read("model", "validate", summary, valid, diagnostics, null);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
