package io.github.mbackschat.a12.dm.cli.workspace;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.RejectionBasis;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Notification;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.Workspace;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import io.github.mbackschat.a12.dm.rulekit.validate.ModelVersion;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool workspace check <dir>} — the project gate: statically CHECK <b>every</b> document model in a
 * directory (resolving each one's references against the whole tree) and report per-model {@code {valid,
 * diagnostics[]}}, the same rich {@code MVK_*}-enriched diagnostics {@code model check} gives for one model.
 * It is a read (it never mutates), but it gates on the aggregate verdict: {@code valid} is true only when every
 * model is valid AND was actually checked, and the process <b>exits nonzero if any model is invalid or could not be checked at all</b> (0 only when every model passed) — so a pre-commit
 * hook or CI step can call it directly. {@code workspace list --validate} only flags validity (a boolean per
 * model, no diagnostics); this verb is the one that surfaces <em>why</em> and fails the build.
 *
 * <p>The workspace-scope static-check verb — the {@code check} family at workspace scope, peer of
 * {@code model check} / {@code rule check}. See the check/eval/compute taxonomy in CLI-SPEC.
 */
@Command(name = "check", mixinStandardHelpOptions = true,
        description = "Statically CHECK every model in a directory through the kernel; per-model {valid, "
                + "diagnostics[]}, exit 1 if any is invalid (the project gate).")
final class WorkspaceCheckCommand extends BaseCommand {

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
        ModelResolver resolver = ReferenceResolvers.directory(root);

        ObjectNode data = MAPPER.createObjectNode();
        data.put("root", dir);
        data.put("recursive", recursive);
        data.put("count", models.size());
        ArrayNode arr = MAPPER.createArrayNode();
        int invalid = 0;
        // The aggregate's evidence is whichever gate actually ran; a model whose FILE could not even be read was
        // never gated and contributes none, so an all-unreadable (or empty) scan honestly attests nothing.
        ConsistencyEvidence evidence = ConsistencyEvidence.none();
        for (Workspace.Model m : models) {
            ModelVerdict v = validateOne(root, resolver, m);
            if (v.evidence().gateRan()) {
                evidence = v.evidence();
            }
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
        // Not one gate but N, so the family turns on what actually happened across the scan — but THE VERB IS A
        // GATE, and it must never report success for a workspace it could not clear. Three cases:
        //
        //  * at least one model gated → a real aggregate verdict, exit 0/1 on `allValid`;
        //  * models exist but NONE reached the oracle → there is no verdict to give, yet the workspace plainly did
        //    not pass, so this is a REJECTION (exit 1) with no fabricated verdict and no provenance. Reachable, and
        //    it was briefly a silent regression: a single-model directory whose `include` names an id absent from
        //    the tree throws UnresolvedReferenceException — a RuntimeException, not ModelConsistencyException — so
        //    `validateOne` counts it invalid with no receipt. Routing that through a QUERY made exitCode()
        //    structurally 0, i.e. a CI gate that passes a workspace it never managed to check;
        //  * NO models at all → nothing was asked of the gate; a query, exit 0, unchanged.
        //
        // The per-model verdicts ride `data` in every case, and a nested verdict never reaches the exit code.
        ResultEnvelope env;
        if (evidence.gateRan()) {
            env = ResultEnvelope.consistencyVerdict(resultIdentity(), summary, allValid, List.of(), data,
                    evidence);
        } else if (models.isEmpty()) {
            env = ResultEnvelope.queryResult(resultIdentity(), summary, data, ConsistencyEvidence.none());
        } else {
            env = ResultEnvelope.rejected(resultIdentity(), "no model could be checked — " + summary, data,
                    List.of(Diagnostic.precheck(Optional.of(RkCode.MODEL_PREPARATION_FAILED.code()),
                            "not one of the " + models.size() + " model(s) reached the consistency gate, so this "
                                    + "workspace cannot be reported as passing",
                            Diagnostic.Where.path(dir),
                            Optional.of("read the per-model `problem` entries in `data.models` — a "
                                    + "typical cause is an include or type-def reference that names an id absent "
                                    + "from the scanned tree"),
                            Optional.empty())),
                    RejectionBasis.preflight());
        }
        out().print(env.toJsonString());
        return env.exitCode();   // 0 only when every model was checked AND valid — this verb is the project gate
    }

    private record ModelVerdict(boolean valid, List<Diagnostic> diagnostics, ConsistencyEvidence evidence) {}

    /** Validate one model through the real kernel, capturing its rich diagnostics — the {@code model check} path. */
    private ModelVerdict validateOne(Path root, ModelResolver resolver, Workspace.Model m) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        boolean valid;
        ConsistencyEvidence evidence;
        String json;
        try {
            json = Files.readString(root.resolve(m.path()));
        } catch (IOException e) {
            // never reached the gate — no receipt to give
            return new ModelVerdict(false, List.of(precheck("cannot read model: " + e.getMessage())),
                    ConsistencyEvidence.none());
        }
        try {
            evidence = ConsistencyEvidence.of(BoundModel.ofJson(json, resolver));
            valid = true;
        } catch (ModelConsistencyException e) {
            valid = false;
            evidence = ConsistencyEvidence.of(e);   // the gate ran and rejected — its notifications ARE the verdict
            for (Notification n : e.notifications()) {
                diagnostics.add(KernelDiagnostics.enriched(n, null));
            }
        } catch (RuntimeException e) {
            // a non-consistency failure (e.g. an unparseable condition the deserializer accepted) — surface it,
            // don't crash the whole gate run. We cannot tell how far the gate got, so we claim nothing.
            valid = false;
            evidence = ConsistencyEvidence.none();
            diagnostics.add(precheck(e.getMessage()));
        }
        // version awareness, identical to `model check`: classify a version mismatch as an ERROR/INFO.
        diagnostics = ModelVersion.classify(json, diagnostics);
        return new ModelVerdict(valid, diagnostics, evidence);
    }

    private static Diagnostic precheck(String message) {
        return Diagnostic.precheck(Optional.empty(), message,
                new Diagnostic.Where((String) null, Optional.empty()),
                Optional.empty(), Optional.empty());
    }

    private JsonNode diagnosticsJson(List<Diagnostic> diagnostics) {
        try {
            return MAPPER.readTree(ValidationResult.of(diagnostics).toJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render diagnostics", e);
        }
    }
}
