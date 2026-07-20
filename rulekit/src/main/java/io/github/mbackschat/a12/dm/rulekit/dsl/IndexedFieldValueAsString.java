package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code FieldValueAsString([Field For key])} — a semantically-indexed cell's value coerced to its string
 * representation (the kernel {@code FieldValueAsString} over an indexed operand, the corpus concat shape;
 * KERNEL-SEMANTICS §9). The indexed sibling of {@link FieldValueAsString}: a string-valued
 * {@link Calculation} that composes in string comparisons and concatenations, its operand rendered as the
 * bare {@code Field For key} index body (like the presence form, no {@code [ ]} value brackets). Build via
 * {@link Dsl#fieldValueAsString(SemanticIndex)} — the operand is an {@link SemanticIndex} handle
 * ({@code ref.semanticIndex(key)}), so number/date/enum cells all coerce uniformly. Kernel-adapter read-back
 * preserves the embedded semantic-index payload (API-FINDINGS finding 74).
 */
public record IndexedFieldValueAsString(IndexedFieldValue index) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.STRING;
    }

    public String operatorId() {
        return "FieldValueAsString";
    }
}
