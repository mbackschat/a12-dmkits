package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * An operand of the field-list × value-list quantifier family
 * ({@code AtLeastOne}/{@code No}/{@code NotAll}{@code FieldValue(s)IncludedInValueList}) — the ONLY
 * construct in which the kernel admits an enum <em>category projection</em> ({@code [field -> Cat]})
 * as a list member alongside plain fields and flattened repetition lists.
 *
 * <p>This is a deliberate <b>supertype</b> of {@link FieldOperand}: every {@link FieldRef} /
 * {@link FieldListRef} is a value-list operand, and additionally {@link EnumCategoryAccess} is one.
 * The split keeps category projections OUT of the other {@link FieldOperand} slots (presence
 * quantifiers, aggregates, uniqueness, date-range overlap), where the kernel rejects them — so an
 * illegal {@code Sum([field -> Cat])} stays unrepresentable rather than relying on per-factory guards.
 */
public sealed interface ValueListOperand permits FieldOperand, EnumCategoryAccess {

    /** The rendered path — with {@code *} on flattened segments ({@link FieldListRef}); {@code field -> Cat} form for a category projection. */
    String renderedPath();

    /** The base field's path (no stars, no category) — what the operand references, for F9/error-field checks. */
    String fieldPath();

    ValueKind kind();
}
