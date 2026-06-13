package io.github.mbackschat.a12.dm.cli;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The uniform result envelope every {@code dmtool} verb (and every {@code apply} op) emits — CLI-SPEC §3b.
 * One op-record {@code {target, op, args}} in, one envelope out, so an agent learns a single output shape.
 *
 * <p>It embeds the existing {@link Diagnostic} array <b>verbatim</b> (via {@link ValidationResult}); the new
 * part is only the wrapper. The {@code ok}/{@code valid} split fixes a real overload: {@code ok} is "the
 * operation executed as asked", {@code valid} (present for validate/check) is "the subject model is
 * kernel-valid" — a {@code model validate} that finds the model invalid is {@code ok:true, valid:false}.
 */
record ResultEnvelope(
        String target,
        String op,
        Outcome outcome,
        String summary,
        JsonNode changed,
        JsonNode data,
        List<Diagnostic> diagnostics,
        Boolean valid,
        boolean written,
        String output) {

    /** The execution result class (distinct from the subject's validity). */
    enum Outcome { APPLIED, PREVIEW, REFUSED, REJECTED, READ, STAGED }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    ResultEnvelope {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /** A mutating op that succeeded and wrote the model. */
    static ResultEnvelope applied(String target, String op, String summary, JsonNode changed, String output) {
        return applied(target, op, summary, changed, List.of(), output);
    }

    /** A mutating op that succeeded, carrying advisory diagnostics (e.g. lint warnings) alongside the write. */
    static ResultEnvelope applied(String target, String op, String summary, JsonNode changed,
            List<Diagnostic> diagnostics, String output) {
        return new ResultEnvelope(target, op, Outcome.APPLIED, summary, changed, null, diagnostics, null, true, output);
    }

    /** A mutating op previewed under {@code --dry-run} — nothing written. */
    static ResultEnvelope preview(String target, String op, String summary, JsonNode changed) {
        return preview(target, op, summary, changed, List.of());
    }

    /** A mutating op previewed under {@code --dry-run} — nothing written — carrying advisory diagnostics. */
    static ResultEnvelope preview(String target, String op, String summary, JsonNode changed,
            List<Diagnostic> diagnostics) {
        return new ResultEnvelope(target, op, Outcome.PREVIEW, summary, changed, null, diagnostics, null, false, null);
    }

    /** A refactor/cascade safety gate refused (CLI-SPEC §6) — nothing written, exit 2. */
    static ResultEnvelope refused(String target, String op, String summary, List<Diagnostic> diagnostics) {
        return refused(target, op, summary, null, diagnostics);
    }

    /** A gate refused, carrying structured {@code data} (e.g. the referrers) — nothing written, exit 2. */
    static ResultEnvelope refused(String target, String op, String summary, JsonNode data,
            List<Diagnostic> diagnostics) {
        return new ResultEnvelope(target, op, Outcome.REFUSED, summary, null, data, diagnostics, null, false, null);
    }

    /** The kernel rejected the op (an invalid candidate/edit) — nothing written, exit 1. */
    static ResultEnvelope rejected(String target, String op, String summary, List<Diagnostic> diagnostics) {
        return new ResultEnvelope(target, op, Outcome.REJECTED, summary, null, null, diagnostics, null, false, null);
    }

    /**
     * A read-only op (read/validate/check/explain): the op executed; {@code valid} reports the subject,
     * {@code data} carries the read payload (an explanation tree, model card, fired-list, …) or {@code null}.
     */
    static ResultEnvelope read(String target, String op, String summary, boolean valid,
            List<Diagnostic> diagnostics, JsonNode data) {
        return new ResultEnvelope(target, op, Outcome.READ, summary, null, data, diagnostics, valid, false, null);
    }

    /** {@code ok} = the operation executed as asked — NOT the subject's validity (that is {@link #valid}). */
    boolean ok() {
        return switch (outcome) {
            case APPLIED, PREVIEW, READ, STAGED -> true;
            case REFUSED, REJECTED -> false;
        };
    }

    /** 0 = ok and not invalid; 1 = rejected, or a read that found the subject invalid; 2 = refused. */
    int exitCode() {
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
    String toJsonString() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJson()) + "\n";
        } catch (JsonProcessingException e) {
            return toJson().toString() + "\n";
        }
    }
}
