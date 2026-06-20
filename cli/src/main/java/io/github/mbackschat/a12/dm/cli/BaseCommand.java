package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
public abstract class BaseCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

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
     * A no-specifier read: the lean <b>name-index</b> of a kind — {@code data.<plural>} is the full-name list,
     * plus a {@code count}. The uniform "no path = list everything in scope" half of the read contract
     * (CLI-SPEC §5), shared by {@code rule}/{@code computation}/{@code field}/{@code group read}. Deliberately
     * lean (names only): it is the navigation handle to then {@code <target> read <name>} for one — distinct
     * from {@code model read} (full DSL of everything) and {@code export} (a takeaway artifact).
     */
    protected int readNameIndex(String target, String plural, Collection<String> names) {
        ObjectNode data = JSON.createObjectNode();
        ArrayNode arr = data.putArray(plural);
        names.forEach(arr::add);
        data.put("count", names.size());
        String summary = names.size() + " " + target + (names.size() == 1 ? "" : "s");
        ResultEnvelope env = ResultEnvelope.read(target, "read", summary, true, List.of(), data);
        out().print(env.toJsonString());
        return env.exitCode();
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
     * Resolve a {@code workspace} verb's scan directory under the same uniformity rule the rest of the CLI
     * follows: the explicit positional {@code <dir>} if given, else the inherited {@code -w/--workspace} root,
     * else {@code $DMTOOL_WORKSPACE} — so an agent that set {@code -w} once can drive {@code workspace} without
     * repeating the path positionally (probe 01-de Finding 5). Empty when none is resolvable; the caller emits
     * the corrective. (Unlike {@code -m}, the positional {@code <dir>} stays — it is the verb's natural scan
     * target — and {@code -w} is the session-default fallback, not a replacement.)
     */
    protected Optional<String> workspaceDir(String positional) {
        if (positional != null) {
            return Optional.of(positional);
        }
        List<Path> roots = spec.root().userObject() instanceof RootCommand root ? root.workspace : List.of();
        if (!roots.isEmpty()) {
            return Optional.of(roots.get(0).toString());
        }
        String env = System.getenv(WorkspaceOption.WORKSPACE_ENV);
        return env != null && !env.isBlank() ? Optional.of(env) : Optional.empty();
    }

    /**
     * Strict input gate: if {@code spec} carries a key the {@code schema/<schemaName>.schema.json} does not
     * declare (top-level or nested), emit a {@code rejected} envelope of {@code RK_UNKNOWN_ARG} diagnostics
     * (with a did-you-mean) and return its exit code; otherwise {@link Optional#empty()} so the caller proceeds.
     * The single defense against a typo'd/out-of-scope key being silently dropped into a false {@code applied}.
     */
    protected Optional<Integer> rejectUnknownKeys(String target, String op, String schemaName, JsonNode spec) {
        List<Diagnostic> unknown = SpecKeys.forSchema(schemaName).validate(spec);
        if (unknown.isEmpty()) {
            return Optional.empty();
        }
        String summary = unknown.size() == 1
                ? "1 unrecognized key in the spec"
                : unknown.size() + " unrecognized keys in the spec";
        ResultEnvelope env = ResultEnvelope.rejected(target, op, summary, unknown);
        out().print(env.toJsonString());
        return Optional.of(env.exitCode());
    }

    /**
     * Emit a {@code rejected} result envelope from a kernel consistency failure — the {@code MVK_*}
     * notifications surfaced as enriched {@link Diagnostic}s (catalog-joined fix/explain). Shared by the
     * modelkit edit verbs, whose {@code EditSession.validate()} funnels both kernel tiers into this exception.
     */
    protected int emitKernelRejection(String target, String op, String summary, ModelConsistencyException e) {
        ResultEnvelope env = kernelRejectionEnvelope(target, op, summary, e);
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * Build (don't print) the {@code rejected} envelope for a kernel consistency failure — its {@code MVK_*}
     * notifications as enriched {@link Diagnostic}s. Static + shared so the {@link Cli} execution-exception
     * handler can map an <em>escaped</em> {@link ModelConsistencyException} (a verb that LOADS an
     * invalid-on-disk model — probe 01-de Finding 7) to the same structured envelope, instead of letting it
     * surface as a raw stack trace.
     */
    static ResultEnvelope kernelRejectionEnvelope(String target, String op, String summary, ModelConsistencyException e) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (RankedNotification n : e.notifications()) {
            diagnostics.add(KernelDiagnostics.enriched(n, null));
        }
        if (diagnostics.isEmpty()) {
            diagnostics.add(Diagnostic.precheck(Optional.empty(), e.getMessage(),
                    new Diagnostic.Where((String) null, Optional.empty()), Optional.empty(), Optional.empty()));
        }
        return ResultEnvelope.rejected(target, op, summary, diagnostics);
    }
}
