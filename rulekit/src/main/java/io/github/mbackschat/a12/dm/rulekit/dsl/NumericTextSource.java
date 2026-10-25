package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A source whose selected stored text can be converted by {@code FieldValueAsNumber}: a direct field, a typed
 * enumeration reference, one named category projection, or a key-selected cell ({@code Field For key}, KF568).
 * Keeping this source family sealed admits the kernel's forms without widening the function to arbitrary
 * calculations.
 */
public sealed interface NumericTextSource permits FieldRef, EnumRef, CategoryRef, IndexedFieldValue {

    /** The underlying field referenced by this direct or projected source. */
    FieldRef field();

    /** Whether the source is correlated to the enclosing iteration with {@code $}. */
    boolean correlated();
}
