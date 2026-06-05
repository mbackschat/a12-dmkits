package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One catalog entry — a single kernel construct described as data (proposal §9). It captures
 * semantics, not just surface: {@code meaning} + {@code constraints}/{@code gotchas} + valid/invalid
 * examples are what let an LLM reason about correctness, not only spelling (N6).
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
        OperatorStatus status,
        String sinceKernel) {

    public OperatorEntry {
        operands = operands == null ? List.of() : List.copyOf(operands);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        gotchas = gotchas == null ? List.of() : List.copyOf(gotchas);
        status = status == null ? OperatorStatus.SUPPORTED : status;
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
}
