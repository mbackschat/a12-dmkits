package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A source whose selected stored text can be converted by {@code FieldValueAsNumber}: either a direct
 * field, a typed enumeration reference, or one named category projection. Keeping this source family
 * sealed admits the kernel's category form without widening the function to arbitrary calculations.
 */
public sealed interface NumericTextSource permits FieldRef, EnumRef, CategoryRef {

    /** The underlying field referenced by this direct or projected source. */
    FieldRef field();

    /** Whether the source is correlated to the enclosing iteration with {@code $}. */
    boolean correlated();
}
