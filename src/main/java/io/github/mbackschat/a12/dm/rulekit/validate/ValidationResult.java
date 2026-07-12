package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.core.Verification;

/**
 * The outcome of a ground-truth check: {@code ok} iff no {@code ERROR}-severity diagnostic was reported.
 * Lower-severity findings (warnings/info) are retained in {@link #diagnostics()}.
 *
 * <p>{@link #verification()} records how thoroughly the check ran (KERNEL-DECOUPLING-PROPOSAL §2c): with a
 * kernel backend it is {@link Verification#KERNEL_CONFIRMED}; without one it is
 * {@link Verification#STRUCTURAL_ONLY} and {@code ok()} reflects only the structural prechecks that ran —
 * so a consumer must consult {@code verification()} before reading {@code ok()} as kernel-green, and a
 * {@code STRUCTURAL_ONLY} result carries an {@code RK_NO_KERNEL_BACKEND} advisory. The CLI always bundles
 * the backend, so it always reports {@code KERNEL_CONFIRMED}.
 */
public record ValidationResult(boolean ok, List<Diagnostic> diagnostics, Verification verification) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ValidationResult {
        diagnostics = List.copyOf(diagnostics);
    }

    /** Build a result whose {@code ok} is derived from the diagnostics, stamping the {@code verification} mode. */
    public static ValidationResult of(List<Diagnostic> diagnostics, Verification verification) {
        boolean ok = diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR);
        return new ValidationResult(ok, diagnostics, verification);
    }

    /**
     * Build a result, stamping the verification of the <em>current environment</em>
     * ({@link Backends#verification()} — {@code KERNEL_CONFIRMED} when a backend is present, else
     * {@code STRUCTURAL_ONLY}). Use this everywhere the check ran in the ambient backend posture; pass the
     * mode explicitly only when a caller must pin it (e.g. a diagnostics-only serialization helper).
     */
    public static ValidationResult of(List<Diagnostic> diagnostics) {
        return of(diagnostics, Backends.verification());
    }

    /**
     * The diagnostics as an agent-parseable JSON array (files-as-API, §14) — one object per
     * diagnostic with {@code severity·source·code·summary·where·fix·explain}, empty optionals
     * omitted so the shape stays terse.
     */
    public String toJson() {
        ArrayNode array = MAPPER.createArrayNode();
        for (Diagnostic d : diagnostics) {
            ObjectNode node = array.addObject();
            node.put("severity", d.severity().name());
            node.put("source", d.source().name());
            d.code().ifPresent(c -> node.put("code", c));
            node.put("summary", d.summary());
            ObjectNode where = node.putObject("where");
            if (d.where().rule() != null) {
                where.put("rule", d.where().rule());
            }
            d.where().path().ifPresent(p -> where.put("path", p));
            d.where().arg().ifPresent(a -> where.put("arg", a));
            d.where().operator().ifPresent(op -> where.put("operator", op));
            d.where().span().ifPresent(s -> where.putArray("span").add(s.start()).add(s.end()));
            d.fix().ifPresent(f -> node.put("fix", f));
            d.explain().ifPresent(e -> node.put("explain", e));
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(array);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to render diagnostics as JSON", e);
        }
    }
}
