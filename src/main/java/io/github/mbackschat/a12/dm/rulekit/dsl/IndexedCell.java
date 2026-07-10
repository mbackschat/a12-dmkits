package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A dedicated handle to the cell a semantic index resolves to — {@code [Field For key]}
 * (KERNEL-SEMANTICS §9). Obtained from a typed ref via {@code ref.indexedCell(key)} (a literal or a
 * field key); its <em>sole</em> role is to author that cell's <strong>presence</strong>
 * ({@code .filled()} / {@code .notFilled()}). The cell's VALUE for an operand slot is
 * {@code ref.forIndex(key)} instead ({@link NumberRef#forIndex(String)} and peers).
 *
 * <p>Presence lives on this dedicated handle — rather than hanging {@code .filled()} off the six
 * value-expression wrappers — so that the non-cell misuse ({@code sum(...).filled()}, which would emit a
 * kernel-illegal {@code FieldFilled(Sum(...))}) is <em>unrepresentable at compile time</em> rather than a
 * runtime throw (API-FINDINGS finding 62, superseding finding 59's fail-fast guard).
 */
public record IndexedCell(IndexedFieldValue index) {

    /** {@code FieldFilled([Field For key])} — the presence of this indexed cell, rendered bare (no value brackets). */
    public Condition filled() {
        return new IndexedPresenceCondition(index, true);
    }

    /** {@code FieldNotFilled([Field For key])} — the absence of this indexed cell, rendered bare. */
    public Condition notFilled() {
        return new IndexedPresenceCondition(index, false);
    }
}
