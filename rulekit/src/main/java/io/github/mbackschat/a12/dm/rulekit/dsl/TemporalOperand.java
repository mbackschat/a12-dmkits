package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * Closed marker for the three temporal value families: {@link DateOperand date}, {@link TimeOperand time},
 * and {@link DateTimeOperand date-time}. Kernel operations accept specific subsets, so public DSL methods use
 * the exact operand interfaces or overloads rather than this broad marker.
 */
public sealed interface TemporalOperand permits DateOperand, TimeOperand, DateTimeOperand {

    /** This operand as a value expression (a {@link FieldValue}, {@link Today}, or a derived value). */
    Calculation expression();
}
