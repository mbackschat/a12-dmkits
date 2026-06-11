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
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code dmtool doc validate} — run the model through the real kernel (the F4 ground-truth step) and report
 * the uniform result envelope. The op always executes ({@code ok:true}); the model's consistency rides
 * {@code valid}, with the kernel's {@code MVK_*} notifications surfaced as enriched {@link Diagnostic}s.
 */
@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Run the model through the real kernel; report {valid, diagnostics[]} (exit 0 valid, 1 not).")
final class DocValidateCommand extends BaseCommand {

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

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
            BoundModel.ofJson(json, includes.resolverFor(model));
            valid = true;
        } catch (ModelConsistencyException e) {
            valid = false;
            for (RankedNotification n : e.notifications()) {
                diagnostics.add(KernelDiagnostics.enriched(n, null));
            }
        }

        String summary = valid ? "model is valid" : "model has " + diagnostics.size() + " error(s)";
        ResultEnvelope env = ResultEnvelope.read("doc", "validate", summary, valid, diagnostics, null);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
