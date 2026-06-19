package io.github.mbackschat.a12.dm.cli.workspace;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.model.notification.RankedNotification;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.Workspace;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import io.github.mbackschat.a12.dm.rulekit.validate.ModelVersion;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool workspace validate <dir>} — the project gate: kernel-validate <b>every</b> document model in a
 * directory (resolving each one's references against the whole tree) and report per-model {@code {valid,
 * diagnostics[]}}, the same rich {@code MVK_*}-enriched diagnostics {@code model validate} gives for one model.
 * It is a read (it never mutates), but it gates on the aggregate verdict: {@code valid} is true only when every
 * model is valid, and the process <b>exits 1 if any model is invalid</b> (0 when all pass) — so a pre-commit
 * hook or CI step can call it directly. {@code workspace list --validate} only flags validity (a boolean per
 * model, no diagnostics); this verb is the one that surfaces <em>why</em> and fails the build.
 */
@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Kernel-validate every model in a directory; per-model {valid, diagnostics[]}, "
                + "exit 1 if any is invalid (the project gate).")
final class WorkspaceValidateCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<dir>",
            description = "the workspace directory whose document models to validate (defaults to -w/--workspace, then $DMTOOL_WORKSPACE)")
    String dir;

    @Option(names = {"--recursive", "-r"},
            description = "descend into sub-directories (default: the immediate directory only, like `ls`)")
    boolean recursive;

    @Override
    public Integer call() {
        dir = workspaceDir(dir).orElse(null);
        if (dir == null) {
            return fail("pass a workspace directory positionally (dmtool workspace <op> <dir>), or set -w/--workspace (or $DMTOOL_WORKSPACE)");
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            return fail("not a directory: " + dir);
        }
        List<Workspace.Model> models;
        try {
            models = Workspace.scan(root, recursive);
        } catch (RuntimeException e) {
            return fail("scan failed: " + e.getMessage());
        }
        // every model resolves its references against the whole tree (multi-file models stay valid).
        IDocumentModelReferenceResolver resolver = ReferenceResolvers.directory(root);

        ObjectNode data = MAPPER.createObjectNode();
        data.put("root", dir);
        data.put("recursive", recursive);
        data.put("count", models.size());
        ArrayNode arr = MAPPER.createArrayNode();
        int invalid = 0;
        for (Workspace.Model m : models) {
            ModelVerdict v = validateOne(root, resolver, m);
            if (!v.valid()) {
                invalid++;
            }
            ObjectNode n = arr.addObject();
            n.put("id", m.id());
            n.put("path", m.path());
            n.put("valid", v.valid());
            n.set("diagnostics", diagnosticsJson(v.diagnostics()));
        }
        data.put("invalid", invalid);
        data.set("models", arr);

        boolean allValid = invalid == 0;
        String summary = allValid
                ? "all " + models.size() + " model" + (models.size() == 1 ? "" : "s") + " valid"
                : invalid + " of " + models.size() + " model" + (models.size() == 1 ? "" : "s") + " invalid";
        ResultEnvelope env = ResultEnvelope.read("workspace", "validate", summary, allValid, List.of(), data);
        out().print(env.toJsonString());
        return env.exitCode();   // 0 when all valid, 1 when any model is invalid (the gate)
    }

    private record ModelVerdict(boolean valid, List<Diagnostic> diagnostics) {}

    /** Validate one model through the real kernel, capturing its rich diagnostics — the {@code model validate} path. */
    private ModelVerdict validateOne(Path root, IDocumentModelReferenceResolver resolver, Workspace.Model m) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        boolean valid;
        String json;
        try {
            json = Files.readString(root.resolve(m.path()));
        } catch (IOException e) {
            return new ModelVerdict(false, List.of(precheck("cannot read model: " + e.getMessage())));
        }
        try {
            BoundModel.ofJson(json, resolver);
            valid = true;
        } catch (ModelConsistencyException e) {
            valid = false;
            for (RankedNotification n : e.notifications()) {
                diagnostics.add(KernelDiagnostics.enriched(n, null));
            }
        } catch (RuntimeException e) {
            // a non-consistency failure (e.g. an unparseable condition the deserializer accepted) — surface it,
            // don't crash the whole gate run.
            valid = false;
            diagnostics.add(precheck(e.getMessage()));
        }
        // version awareness, identical to `model validate`: classify a version mismatch as an ERROR/INFO.
        diagnostics = ModelVersion.classify(json, diagnostics);
        return new ModelVerdict(valid, diagnostics);
    }

    private static Diagnostic precheck(String message) {
        return Diagnostic.precheck(java.util.Optional.empty(), message,
                new Diagnostic.Where((String) null, java.util.Optional.empty()),
                java.util.Optional.empty(), java.util.Optional.empty());
    }

    private JsonNode diagnosticsJson(List<Diagnostic> diagnostics) {
        try {
            return MAPPER.readTree(ValidationResult.of(diagnostics).toJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render diagnostics", e);
        }
    }
}
