package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import com.mgmtp.a12.model.notification.RankedNotification;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Shared base for the picocli verb commands: gives each one its {@code out}/{@code err} writers (from
 * the {@link Cli#run} seam, via picocli) and the exit-2 {@code fail} helper, so a verb's class holds
 * only its options + its work. The {@code @Command} here is just to host the injected {@code @Spec}
 * (picocli-codegen requires it); the abstract base is never registered as a runnable command.
 */
@Command
abstract class BaseCommand implements Callable<Integer> {

    @Spec
    protected CommandSpec spec;

    protected PrintWriter out() {
        return spec.commandLine().getOut();
    }

    protected PrintWriter err() {
        return spec.commandLine().getErr();
    }

    /** Print a diagnostic to stderr and return exit code 2 (usage / IO error). */
    protected int fail(String message) {
        err().println(message);
        return 2;
    }

    /**
     * The DM-JSON at {@code modelPath}, or {@code null} after printing the corrective failure to stderr (the
     * caller then returns exit 2). Centralizes the read-the-model-file step every mutating verb opens with —
     * a missing {@code -m} ({@code modelPath == null}) and an unreadable file both fail with a clear message.
     */
    protected String readModelJson(String modelPath) {
        if (modelPath == null) {
            fail("pass -m <model.json> (before or after the verb)");
            return null;
        }
        try {
            return Files.readString(Path.of(modelPath));
        } catch (IOException e) {
            fail("cannot read model: " + e.getMessage());
            return null;
        }
    }

    /**
     * Emit a {@code refused} envelope from a refactor safety-gate {@link Refusal} (CLI-SPEC §6): the
     * structured {@code data} (e.g. the referrers) + the corrective diagnostic, nothing written, exit 2.
     */
    protected int refuse(String target, String op, Refusal refusal) {
        ResultEnvelope env = ResultEnvelope.refused(target, op, refusal.summary(), refusal.data(),
                refusal.diagnostics());
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * The {@code -m/--model} context option, read from the root command. It is an inherited option, so it
     * binds to {@link RootCommand} wherever it appears — before or after the verb. {@code null} if absent.
     */
    protected String model() {
        return spec.root().userObject() instanceof RootCommand root ? root.model : null;
    }

    /**
     * Emit a {@code rejected} result envelope from a kernel consistency failure — the {@code MVK_*}
     * notifications surfaced as enriched {@link Diagnostic}s (catalog-joined fix/explain). Shared by the
     * modelkit edit verbs, whose {@code EditSession.validate()} funnels both kernel tiers into this exception.
     */
    protected int emitKernelRejection(String target, String op, String summary, ModelConsistencyException e) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (RankedNotification n : e.notifications()) {
            diagnostics.add(KernelDiagnostics.enriched(n, null));
        }
        if (diagnostics.isEmpty()) {
            diagnostics.add(Diagnostic.precheck(Optional.empty(), e.getMessage(),
                    new Diagnostic.Where((String) null, Optional.empty()), Optional.empty(), Optional.empty()));
        }
        ResultEnvelope env = ResultEnvelope.rejected(target, op, summary, diagnostics);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
