package io.github.mbackschat.a12.dm.cli;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The uniform result envelope every {@code dmtool} verb (and every {@code apply} op) emits — CLI-SPEC §3b.
 * One op-record {@code {target, op, args}} in, one envelope out, so an agent learns a single output shape.
 *
 * <p>It embeds the existing {@link Diagnostic} array <b>verbatim</b> (via {@link ValidationResult}); the new
 * part is only the wrapper. The {@code ok}/{@code valid} split fixes a real overload: {@code ok} is "the
 * operation executed as asked", {@code valid} (present for validate/check) is "the subject model is
 * kernel-valid" — a {@code model check} that finds the model invalid is {@code ok:true, valid:false}.
 */
public record ResultEnvelope(
        String target,
        String op,
        Outcome outcome,
        String summary,
        JsonNode changed,
        JsonNode data,
        List<Diagnostic> diagnostics,
        Boolean valid,
        boolean written,
        String output,
        Verification verification) {

    /** The execution result class (distinct from the subject's validity). */
    enum Outcome { APPLIED, PREVIEW, REFUSED, REJECTED, READ, STAGED, ERROR }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ResultEnvelope {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * A copy tagged with how a <em>verdict</em> was verified (KERNEL-DECOUPLING-PROPOSAL §2c): the verb
     * checked the subject under {@link Verification#KERNEL_CONFIRMED} (a kernel backend arbitrated — dmtool
     * always) or {@link Verification#STRUCTURAL_ONLY} (no backend; the verdict is structural-only, never
     * kernel-green). Attach it on the check/validate verbs so a library consumer's degraded posture is
     * visible even though the CLI itself always bundles the backend.
     */
    public ResultEnvelope withVerification(Verification verification) {
        return new ResultEnvelope(target, op, outcome, summary, changed, data, diagnostics,
                valid, written, output, verification);
    }

    /** A mutating op that succeeded and wrote the model. */
    public static ResultEnvelope applied(String target, String op, String summary, JsonNode changed, String output) {
        return applied(target, op, summary, changed, List.of(), output);
    }

    /** A mutating op that succeeded, carrying advisory diagnostics (e.g. lint warnings) alongside the write. */
    public static ResultEnvelope applied(String target, String op, String summary, JsonNode changed,
            List<Diagnostic> diagnostics, String output) {
        return new ResultEnvelope(target, op, Outcome.APPLIED, summary, changed, null, diagnostics, null, true, output, null);
    }

    /** A mutating op previewed under {@code --dry-run} — nothing written. */
    public static ResultEnvelope preview(String target, String op, String summary, JsonNode changed) {
        return preview(target, op, summary, changed, List.of());
    }

    /** A mutating op previewed under {@code --dry-run} — nothing written — carrying advisory diagnostics. */
    public static ResultEnvelope preview(String target, String op, String summary, JsonNode changed,
            List<Diagnostic> diagnostics) {
        return new ResultEnvelope(target, op, Outcome.PREVIEW, summary, changed, null, diagnostics, null, false, null, null);
    }

    /** A refactor/cascade safety gate refused (CLI-SPEC §6) — nothing written, exit 2. */
    public static ResultEnvelope refused(String target, String op, String summary, List<Diagnostic> diagnostics) {
        return refused(target, op, summary, null, diagnostics);
    }

    /** A gate refused, carrying structured {@code data} (e.g. the referrers) — nothing written, exit 2. */
    public static ResultEnvelope refused(String target, String op, String summary, JsonNode data,
            List<Diagnostic> diagnostics) {
        return new ResultEnvelope(target, op, Outcome.REFUSED, summary, null, data, diagnostics, null, false, null, null);
    }

    /** The kernel rejected the op (an invalid candidate/edit) — nothing written, exit 1. */
    public static ResultEnvelope rejected(String target, String op, String summary, List<Diagnostic> diagnostics) {
        return new ResultEnvelope(target, op, Outcome.REJECTED, summary, null, null, diagnostics, null, false, null, null);
    }

    /**
     * The tool itself failed — an unexpected throwable caught at the command boundary (CLI-CONFORMANCE: no
     * raw stack trace escapes a verb), NOT a kernel rejection of input. {@code ok:false}, exit 70, nothing
     * written; the carried {@code RK_INTERNAL_ERROR} diagnostic names the failure (full trace on stderr).
     */
    public static ResultEnvelope internalError(String target, String op, String summary, List<Diagnostic> diagnostics) {
        return new ResultEnvelope(target, op, Outcome.ERROR, summary, null, null, diagnostics, null, false, null, null);
    }

    /**
     * A read-only op (read/validate/check/explain): the op executed; {@code valid} reports the subject,
     * {@code data} carries the read payload (an explanation tree, model card, fired-list, …) or {@code null}.
     */
    public static ResultEnvelope read(String target, String op, String summary, boolean valid,
            List<Diagnostic> diagnostics, JsonNode data) {
        return new ResultEnvelope(target, op, Outcome.READ, summary, null, data, diagnostics, valid, false, null, null);
    }

    /** {@code ok} = the operation executed as asked — NOT the subject's validity (that is {@link #valid}). */
    boolean ok() {
        return switch (outcome) {
            case APPLIED, PREVIEW, READ, STAGED -> true;
            case REFUSED, REJECTED, ERROR -> false;
        };
    }

    /** 0 = ok and not invalid; 1 = rejected, or a read that found the subject invalid; 2 = refused; 70 = internal tool error. */
    public int exitCode() {
        if (outcome == Outcome.ERROR) {
            return 70; // EX_SOFTWARE — the tool itself failed (distinct from rejected=1 / refused=2)
        }
        if (outcome == Outcome.REFUSED) {
            return 2;
        }
        if (outcome == Outcome.REJECTED) {
            return 1;
        }
        if (Boolean.FALSE.equals(valid)) {
            return 1;
        }
        return 0;
    }

    JsonNode toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("target", target);
        root.put("op", op);
        root.put("outcome", outcome.name().toLowerCase(Locale.ROOT));
        root.put("ok", ok());
        if (valid != null) {
            root.put("valid", valid);
        }
        if (verification != null) {
            root.put("verification", verification.name());
        }
        root.put("summary", summary);
        if (changed != null && !changed.isNull()) {
            root.set("changed", changed);
        }
        if (data != null && !data.isNull()) {
            root.set("data", data);
        }
        // reuse the canonical Diagnostic-array serialization verbatim (CLI-SPEC §4)
        try {
            root.set("diagnostics", MAPPER.readTree(ValidationResult.of(diagnostics).toJson()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render diagnostics", e);
        }
        root.put("written", written);
        if (output != null) {
            root.put("output", output);
        }
        return root;
    }

    /** Pretty-printed, newline-terminated — what a verb prints to stdout. */
    public String toJsonString() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJson()) + "\n";
        } catch (JsonProcessingException e) {
            return toJson().toString() + "\n";
        }
    }
}
