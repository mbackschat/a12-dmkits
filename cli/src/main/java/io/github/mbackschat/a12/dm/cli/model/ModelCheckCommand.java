package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.CheckEngine;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.EngineOption;
import io.github.mbackschat.a12.dm.cli.PortableCheck;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.consistency.ConsistencyReport;
import io.github.mbackschat.a12.dm.consistency.ConsistencyStatus;
import io.github.mbackschat.a12.dm.consistency.ReportJsonKt;
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
 * {@code dmtool model check} — the STATIC validation verb: run the whole model's definitions through a
 * consistency engine and report the uniform result envelope. Covers BOTH rule conditions AND computation
 * expressions; takes no document instance (that is the runtime {@code model eval}/{@code model compute}).
 * Malformed input refuses before a consistency verdict exists.
 *
 * <p><b>Two engines, selected only by the caller.</b> The default and {@code --engine=kernel} run the real
 * kernel exactly as this verb always has: once loading succeeds the model's consistency rides {@code valid},
 * with the kernel's {@code MVK_*} notifications surfaced as enriched {@link Diagnostic}s.
 * {@code --engine=portable} runs the bundled kernel-free checker instead, which is a different contract rather
 * than the same one computed elsewhere — it cannot report a model VALID, so its ordinary answer on an
 * acceptable model is INCONCLUSIVE at exit 3, and its findings ride {@code data} in the checker's own
 * published report shape.
 *
 * <p><b>Neither engine is ever a fallback for the other.</b> An incomplete portable answer does not reach for
 * the kernel and a kernel failure does not become a portable answer; the receipt names the engine requested
 * and the engine that ran, and {@code ConsistencyEvidence.requestedBy} refuses to build an envelope where
 * those differ — so a substitution cannot be published even when both engines would agree.
 *
 * <p>The model-scope static-check verb — peer of the candidate-rule {@code rule check}; the runtime peers are
 * {@code model eval} (conditions) and {@code model compute} (computations). See the check/eval/compute
 * taxonomy in CLI-SPEC.
 */
@Command(name = "check", mixinStandardHelpOptions = true,
        description = "Statically CHECK the whole model's definitions (rules + computations) through the real "
                + "kernel; report {valid, diagnostics[]} (no document instance — exit 0 valid, 1 not; "
                + "malformed model input is refused with a PRECHECK diagnostic and exit 2). "
                + "--engine=portable checks with the kernel-free checker instead, which reports INVALID or "
                + "INCONCLUSIVE (exit 3) and never VALID.")
final class ModelCheckCommand extends BaseCommand {

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Mixin
    EngineOption engine = new EngineOption();

    @Override
    public Integer call() {
        String model = model();
        String json = readModelJson(model);
        if (json == null) {
            return 2;
        }
        CheckEngine requested = engine.selected();
        return switch (requested) {
            case A12_KERNEL -> checkWithKernel(model, json, requested);
            case PORTABLE_CHECKER -> checkWithPortableChecker(model, json, requested);
        };
    }

    /** The kernel gate, unchanged — the default, and what {@code --engine=kernel} asks for explicitly. */
    private Integer checkWithKernel(String model, String json, CheckEngine requested) {
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
        return publish(ResultEnvelope.consistencyVerdict(resultIdentity(), summary, valid, diagnostics, null,
                evidence.requestedBy(requested)));
    }

    /**
     * The kernel-free checker, which answers a narrower question and says so.
     *
     * <p>The report rides {@code data} in the checker's <b>own</b> published shape rather than being folded
     * into the envelope's {@code diagnostics}. That shape separates {@code findings} — A12's verdict on a
     * declaration — from {@code diagnostics} — the checker saying it could not read something — and publishes
     * {@code checksCompleted} and {@code gaps} as coverage evidence beside them. Re-encoding any of that into
     * the envelope's own vocabulary would make the second copy of a fact whose owner already separated the two,
     * and would re-create exactly the conflation the checker's report was designed to avoid.
     */
    private Integer checkWithPortableChecker(String model, String json, CheckEngine requested) {
        PortableCheck check = PortableCheck.of(json, model);
        ConsistencyReport report = check.report();
        ConsistencyEvidence evidence = ConsistencyEvidence.of(check).requestedBy(requested);
        JsonNode data = parseReport(report);
        ConsistencyStatus status = report.getStatus();

        if (status instanceof ConsistencyStatus.Invalid) {
            long rejecting = report.getFindings().stream().filter(f -> f.getJustifiesRejection()).count();
            return publish(ResultEnvelope.consistencyVerdict(resultIdentity(),
                    "model has " + rejecting + " finding(s) that justify rejection", false, List.of(), data,
                    evidence));
        }
        if (status instanceof ConsistencyStatus.Inconclusive) {
            return publish(ResultEnvelope.consistencyInconclusive(resultIdentity(),
                    "no violation established, and the portable checker's coverage is incomplete — "
                            + "this is NOT a statement that the model is valid",
                    List.of(), data, evidence));
        }
        // `Valid` is a planned third state that arrives with the coverage evidence earning it. Mapping an
        // unrecognized status onto INCONCLUSIVE would silently publish "concluded nothing" for a checker that
        // had in fact concluded the model is fine, so this refuses loudly instead of guessing.
        throw new IllegalStateException("the portable checker returned a status this verb does not map: " + status
                + " — a VALID arm needs its own `valid:true` envelope at exit 0, decided deliberately");
    }

    /**
     * The checker's report as a tree, parsed from the one serialization the checker itself publishes.
     *
     * <p>Decoded through {@link BaseCommand#readGeneratedJson}, the shared boundary for JSON <em>we</em>
     * produced, rather than a mapper of this command's own: it carries the reviewed parser configuration and
     * turns a malformed or truncated payload into an internal failure with its parsing cause, which is the
     * right classification because only a defect in our own writer can produce one. A private
     * {@code new ObjectMapper()} here would instead have been a second, unconfigured JSON dialect inside the
     * tool — the shape {@code A12JsonConstructionGuardTest} refuses on sight.
     */
    private static JsonNode parseReport(ConsistencyReport report) {
        return readGeneratedJson(ReportJsonKt.toJson(report));
    }

    private Integer publish(ResultEnvelope envelope) {
        out().print(envelope.toJsonString());
        return envelope.exitCode();
    }
}
