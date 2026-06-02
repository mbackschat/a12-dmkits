package io.github.mbackschat.a12.rulekit.dsl;

/**
 * A typed value expression (proposal §5) — the operand kind that supplies values inside conditions
 * and renders into a computation's {@code operation}. The closed set keeps rendering exhaustive.
 */
public sealed interface Calculation permits FieldValue, NumberLiteral, StringLiteral, LengthOf {
}
