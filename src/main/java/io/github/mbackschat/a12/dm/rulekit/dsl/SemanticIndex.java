package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A dedicated handle to a semantic index — {@code [Field For key]} (KERNEL-SEMANTICS §9), the kernel's
 * {@code IdentifierWithSemanticIndex}. Obtained from a typed ref via {@code ref.semanticIndex(key)} (a
 * literal or a field key); its role is to author the index's <strong>presence</strong>
 * ({@code .filled()} / {@code .notFilled()}), and it is also the operand of
 * {@link Dsl#fieldValueAsString(SemanticIndex)}. The index's VALUE for a comparison-operand slot is
 * {@code ref.forIndex(key)} instead ({@link NumberRef#forIndex(String)} and peers).
 *
 * <p>Presence lives on this dedicated handle — rather than hanging {@code .filled()} off the six
 * value-expression wrappers — so that the non-index misuse ({@code sum(...).filled()}, which would emit a
 * kernel-illegal {@code FieldFilled(Sum(...))}) is <em>unrepresentable at compile time</em> rather than a
 * runtime throw (API-FINDINGS finding 63, superseding finding 59's fail-fast guard).
 */
public record SemanticIndex(IndexedFieldValue index) {

    /** {@code FieldFilled([Field For key])} — the presence of this semantic index, rendered bare (no value brackets). */
    public Condition filled() {
        return new IndexedPresenceCondition(index, true);
    }

    /** {@code FieldNotFilled([Field For key])} — the absence of this semantic index, rendered bare. */
    public Condition notFilled() {
        return new IndexedPresenceCondition(index, false);
    }
}
