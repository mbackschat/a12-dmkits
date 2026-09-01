package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * An operand of the <b>entity-list aggregates</b> — {@code Sum}, {@code MaxValue}, {@code MinValue},
 * {@code NumberOfDifferentValues}, {@code NumberOfFilledFields} — which the kernel routes through one
 * {@code getRefList}: either a {@link FieldOperand} (a field or a flattened repetition list) or a
 * {@link GroupListRef}, a <b>group scope</b> whose contained fields the kernel's {@code ExpandService}
 * expands recursively and feeds to the combiner ({@code Sum(/Invoice/Lines*)} — KERNEL-FINDINGS
 * {@code kf12}).
 *
 * <p><b>The group arm behaves exactly like a repetition list at every entity-list gate</b>, which is why one
 * type covers both rather than the aggregates growing a second operand list. Measured on kernel 30.8.1,
 * identically for all four aggregates ({@code rulekit.validate.laws.AggregateGroupOperandLawsTest}):
 *
 * <ul>
 *   <li>a single starred group satisfies the arity gate <em>alone</em>, as a {@link FieldListRef} does;</li>
 *   <li>the same starred group may be listed <em>twice</em> — the kernel's {@code checkDuplicatesInDirectRefs}
 *       skips wildcarded refs, so no {@code MVK_DUPLICATE_PARAM1};</li>
 *   <li>a group beside a <em>descendant</em> of itself is {@code MVK_DUPLICATE_PARAM2}, the indirect-overlap
 *       arm — the one duplicate gate a wildcard does not escape;</li>
 *   <li>nested stars ({@code /Invoice/Lines*}{@code /Fees*}) are legal and both survive.</li>
 * </ul>
 *
 * <p><b>Two gates stay kernel-arbitrated (F4), and each has a measured reason.</b> The <em>star</em> is not
 * optional on a repeatable group — an unstarred one is {@code MVK_NO_WILDCARD} — but {@link GroupListRef}
 * derives it from the live model rather than from the caller, so the shape cannot be authored wrong.
 * And the <em>kind</em> rule applies to the group's EXPANSION, not to the group: {@code Sum} over a group
 * containing a string field is {@code MVK_NO_NUMBER} even though the group itself has no kind. Pre-checking
 * that here would mean re-implementing the expansion's extent — the recursion into nested subgroups — and an
 * over-strict copy would refuse a legal model, so it is delegated, exactly as {@link GroupListOperand}
 * already delegates the same family's gates.
 *
 * <p>The third arm, {@link IndexedGroupValue} — a group scope reached through a semantic index
 * ({@code [Group For key]}) — behaves like a single group entity here: it never
 * satisfies arity alone (unlike a starred {@link GroupListRef}), and it participates in exact-duplicate
 * detection keyed on group path AND index. Added directly rather than nested under {@link GroupListRef}
 * because that record is {@code final} — unlike {@link FieldOperand}'s own indexed-field arm, which
 * nested inside an existing sealed member.
 */
public sealed interface AggregateOperand permits FieldOperand, GroupListRef, IndexedGroupValue {

    /** The rendered path, with a {@code *} on every starred segment. */
    String renderedPath();
}
