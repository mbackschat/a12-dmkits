package io.github.mbackschat.a12.rulekit.catalog;

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
        List<String> gotchas,
        Example validExample,
        InvalidExample invalidExample,
        String sinceKernel) {

    public OperatorEntry {
        operands = operands == null ? List.of() : List.copyOf(operands);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        gotchas = gotchas == null ? List.of() : List.copyOf(gotchas);
    }

    /** The localized keywords; the kernel selects the set via the model's {@code conditionLanguage}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Keywords(String en, String de) {
    }

    /** An operand slot, described by the value kind it accepts (e.g. {@code "number"}, {@code "any"}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Operand(String type) {
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
