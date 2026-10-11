package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * An operand of the field-list × value-list quantifier family
 * ({@code AtLeastOne}/{@code No}/{@code NotAll}{@code FieldValue(s)IncludedInValueList}) — the ONLY
 * construct in which the kernel admits an enum <em>category projection</em> ({@code [field -> Cat]})
 * as a list member alongside plain fields, flattened repetition lists, and group scopes.
 *
 * <p>This is a deliberate <b>supertype</b> of {@link FieldOperand}: every {@link FieldRef} /
 * {@link FieldListRef} is a value-list operand, and additionally {@link EnumCategoryAccess} is one.
 * The split keeps category projections OUT of the other {@link FieldOperand} slots (presence
 * quantifiers, aggregates, uniqueness, date-range overlap), where the kernel rejects them — so an
 * illegal {@code Sum([field -> Cat])} stays unrepresentable rather than relying on per-factory guards.
 *
 * <p><b>The {@link GroupListRef} arm, and why this interface carries no {@code kind()} (DG35, 2026-08-14).</b>
 * The kernel admits a group scope here — measured on both the literal value side and the entity value side, and
 * on <em>both</em> slots of the entity form ({@code rulekit.validate.laws.EntityListValueListGroupOperandLawsTest},
 * with the per-carrier table in KERNEL-FINDINGS {@code kf187}). A group has no kind of its own: the value-list
 * family's base-kind rule applies to the group's EXPANSION, which recurses into nested subgroups. Pre-checking it
 * client-side would mean re-implementing that extent, and an over-strict copy would refuse a legal model — so
 * {@code kind()} lives on the field-like arms that actually know one, and the group arm's kind is arbitrated by
 * the kernel ({@code MVK_VARYING_TYPES_NOT_ALLOWED} / {@code MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED}).
 * {@link AggregateOperand} made the identical call for the identical reason, and this interface now matches it
 * rather than carrying a member one of its arms cannot honestly answer. A selected group retains that same
 * contract through {@link IndexedGroupValue}; selected fixed Number and lower-star String groups are measured
 * on both entity sides by {@code IndexedValueListGroupLawsTest} (KF187).
 */
public sealed interface ValueListOperand extends DuplicateOperand
        permits FieldOperand, EnumCategoryAccess, GroupListRef, IndexedGroupValue {
}
