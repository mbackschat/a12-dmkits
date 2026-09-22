package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/** {@code Length(field)} — the <b>UTF-16 code-unit</b> length of a string field, as a numeric operand.
 *  Not a code-point or grapheme-cluster count: a supplementary-plane character (an emoji) counts 2, a
 *  combining sequence counts per unit. (The a12-dmkits contract; see the catalog {@code Length} entry.) */
public record LengthOf(FieldRef field) implements Calculation {

    /** The catalog operator-id (D14) — keyword functions resolve their keyword from the catalog. */
    public String operatorId() {
        return "Length";
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }
}
