package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A field value reached through a semantic index — {@code [Field For key]} (KERNEL-SEMANTICS §9): the
 * literal-keyed {@link SemanticIndexValue} or the field-keyed {@link SemanticIndexFieldValue}. Beyond
 * being a comparable {@link Calculation} value ({@code ref.forIndex(key)}), an indexed cell has a
 * <em>presence</em> — so it grows {@code FieldFilled}/{@code FieldNotFilled} via
 * {@link IndexedPresenceCondition}, authored through the dedicated {@link SemanticIndex} handle
 * ({@code ref.semanticIndex(key).filled()} / {@code .notFilled()}).
 */
public sealed interface IndexedFieldValue extends Calculation
        permits SemanticIndexValue, SemanticIndexFieldValue {

    /** The field whose indexed cell this reads. */
    FieldRef field();

    /** The catalog operator-id — {@code SemanticIndex} for both key forms. */
    String operatorId();

    /** This indexed cell as one compositional {@code FirstFilledValue} owner entity. */
    default FirstFilledOperand firstFilledOperand() {
        return FirstFilledOperand.of(this);
    }
}
