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
 * <p>{@link #consistencyGate()} is the gate's <b>receipt</b> (KERNEL-DECOUPLING-PROPOSAL §2c): <b>empty when the
 * consistency oracle was never consulted</b>, else how it was arbitrated — {@link Verification#KERNEL_CONFIRMED} with
 * a kernel backend, {@link Verification#STRUCTURAL_ONLY} without one (and then {@code ok()} reflects only the
 * structural prechecks that ran, paired with an {@code RK_NO_KERNEL_BACKEND} advisory, so it must never be read as
 * kernel-green).
 *
 * <p><b>Empty is a third, distinct answer, and conflating it with the second shipped a false claim.</b> A validator
 * can reject <em>before</em> reaching the oracle — {@code RuleValidator}'s add-time prechecks
 * ({@code RK_NO_SUCH_GROUP}, {@code RK_NAME_EXISTS}) and its conversion catch-all both return early. This type used
 * to stamp {@code Backends.verification()} on those results too, which describes the <em>environment</em> (a backend
 * is on the classpath) rather than anything that ran. The CLI then published it as provenance, so a rule whose group
 * simply did not exist came back {@code valid:false} with {@code verification: KERNEL_CONFIRMED} — an envelope
 * contradicting its own {@code PRECHECK}-sourced diagnostic. Same shape as
 * {@link io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel#consistencyGate()}, and for the same reason.
 */
public final class ValidationResult {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean ok;
    private final List<Diagnostic> diagnostics;
    private final Verification consistencyGateMode;

    // Private on purpose: this type is a gate WITNESS — ConsistencyEvidence.of(ValidationResult) publishes the
    // receipt below as provenance, so a result anyone can construct would let a caller mint KERNEL_CONFIRMED for
    // a check that never ran. As a public record its canonical constructor could not be narrowed
    // (WitnessTypesAreUnforgeableTest). The two factories below are the only ways in, and each states its tier.
    private ValidationResult(boolean ok, List<Diagnostic> diagnostics, Verification consistencyGateMode) {
        this.ok = ok;
        this.diagnostics = List.copyOf(diagnostics);
        this.consistencyGateMode = consistencyGateMode;
    }

    /** Whether the check passed — no {@code ERROR}-severity diagnostic was reported. */
    public boolean ok() {
        return ok;
    }

    /** Every finding, including the sub-{@code ERROR} ones that leave {@link #ok()} true. */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** Build a result for a check the consistency oracle ARBITRATED, carrying the mode it ran in. */
    public static ValidationResult of(List<Diagnostic> diagnostics, Verification consistencyGateMode) {
        boolean ok = diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR);
        return new ValidationResult(ok, diagnostics, consistencyGateMode);
    }

    /**
     * Build a result for a rejection reached <b>before</b> the consistency oracle was consulted — a structural
     * precheck, or a conversion failure that stopped the check. It carries <b>no</b> gate receipt, because no gate
     * ran; a consumer must not publish provenance for it.
     */
    public static ValidationResult beforeTheGate(List<Diagnostic> diagnostics) {
        boolean ok = diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR);
        return new ValidationResult(ok, diagnostics, null);
    }

    /** The gate's receipt — empty when the oracle was never consulted. See the class javadoc. */
    public java.util.Optional<Verification> consistencyGate() {
        return java.util.Optional.ofNullable(consistencyGateMode);
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
