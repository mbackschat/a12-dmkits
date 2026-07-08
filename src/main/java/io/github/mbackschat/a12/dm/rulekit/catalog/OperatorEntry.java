package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One catalog entry — a single kernel construct described as data (PROJECT-DESIGN §9). It captures
 * semantics, not just surface: {@code meaning} + {@code constraints}/{@code gotchas} + valid/invalid
 * examples are what let an LLM reason about correctness, not only spelling (N6).
 *
 * <p>{@code opposite} lists the operator's <b>negation partner(s)</b> — the predicate(s) to reach for when
 * you want the inverse (FieldFilled → FieldNotFilled, AllFieldsFilled → NotAllFieldsFilled, {@code <} →
 * {@code >=}). It serves the kernel's "<b>no generic negation</b>" model (express the inverse with the
 * paired predicate, not a {@code Not(…)}); it is a discoverability pointer, <em>not</em> a claim of strict
 * logical complement under three-valued / formal-error semantics (a field with a formal error is removed
 * from evaluation, so {@code FieldFilled} and {@code FieldNotFilled} are not exhaustive — see their gotcha).
 *
 * <p>Unknown JSON fields are ignored so the on-disk schema can grow ahead of this record.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OperatorEntry(
        String id,
        OperatorKind kind,
        Keywords keywords,
        List<Operand> operands,
        String returns,
        String meaning,
        List<String> constraints,
        List<Gotcha> gotchas,
        Example validExample,
        InvalidExample invalidExample,
        ComputationExample computationExample,
        OperatorStatus status,
        String sinceKernel,
        List<String> opposite,
        List<String> seeAlso,
        OperatorSemantics semantics,
        List<Corrective> correctives) {

    public OperatorEntry {
        operands = operands == null ? List.of() : List.copyOf(operands);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        gotchas = gotchas == null ? List.of() : List.copyOf(gotchas);
        status = status == null ? OperatorStatus.SUPPORTED : status;
        opposite = opposite == null ? List.of() : List.copyOf(opposite);
        seeAlso = seeAlso == null ? List.of() : List.copyOf(seeAlso);
        correctives = correctives == null ? List.of() : List.copyOf(correctives);
    }

    /** The {@code emptyOperand} facet claims (CATALOG-SPEC §4) — empty when the operator follows the
     *  per-kind defaults in the catalog header's {@code emptyOperandDefaults}. */
    public List<EmptyOperandClaim> emptyOperandClaims() {
        return semantics == null ? List.of() : semantics.emptyOperand();
    }

    /** The {@code boundary} facet claim (CATALOG-SPEC §3), or {@code null} when the operator carries
     *  no threshold / interval endpoint (most operators). */
    public BoundaryClaim boundaryClaim() {
        return semantics == null ? null : semantics.boundary();
    }

    /** The {@code iterationRange} facet claim (CATALOG-SPEC §3b), or {@code null} when the operator
     *  follows the natural INSTANTIATED default (all operators but the deviating field-fill quantifiers). */
    public IterationRangeClaim iterationRangeClaim() {
        return semantics == null ? null : semantics.iterationRange();
    }

    /** The {@code aggregateIdentity} facet claim (CATALOG-SPEC §3c), or {@code null} when the operator
     *  is not a value-folding aggregate. */
    public AggregateIdentityClaim aggregateIdentityClaim() {
        return semantics == null ? null : semantics.aggregateIdentity();
    }

    /** Whether rulekit builds a typed handle for this operator (vs a {@link OperatorStatus#PLANNED} stub). */
    public boolean supported() {
        return status == OperatorStatus.SUPPORTED;
    }

    /** The localized keywords; the kernel selects the set via the model's {@code conditionLanguage}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Keywords(String en, String de) {
    }

    /** An operand slot, described by the value kind it accepts (e.g. {@code "number"}, {@code "any"}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Operand(String type) {
    }

    /**
     * A semantic note about the operator, classified so the F4a gotcha/INFO channel surfaces the
     * author-relevant ones ({@link GotchaKind#SURPRISE}/{@link GotchaKind#AUTHORING}) and suppresses
     * trivia ({@link GotchaKind#RENDERING}). A missing {@code kind} defaults to {@code SURPRISE} so
     * older entries still surface.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gotcha(String text, GotchaKind kind) {
        public Gotcha {
            kind = kind == null ? GotchaKind.SURPRISE : kind;
        }
    }

    /** A valid usage example (CI-validated against the real kernel as a later anchor — N7). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Example(String dsl, String note) {
    }

    /** A deliberately-rejected example with the kernel error class and the fix. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvalidExample(String dsl, String error, String fix) {
    }

    /**
     * A valid usage example for a <b>computation-only</b> operator — one that has no legal condition form
     * (e.g. the {@code DateRange} constructor), so it can't carry a condition {@link Example}. The
     * {@code operation} DSL is verified by injecting it as a computation assigning {@code computedField}
     * and running the real kernel (the N7 peer, {@code CatalogComputationExamplesAreKernelValidTest}).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ComputationExample(String operation, String computedField, String note) {
    }
}
