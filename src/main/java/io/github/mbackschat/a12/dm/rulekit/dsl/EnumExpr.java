package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * An enum-valued expression that can be compared by stored value — currently the
 * {@link Dsl#firstFilledEnum first specified value} over an enum repetition list. The enum peer of
 * {@link StringExpr}; {@link EnumRef} compares against it. Type-compatibility of the two enums is the
 * kernel's call (F4/N7), as for {@link EnumRef#is(EnumRef)}.
 */
public record EnumExpr(Calculation expression) {
}
