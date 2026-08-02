package io.github.mbackschat.a12.dm.rulekit.catalog;

/**
 * What an aggregate over an <b>all-empty selection</b> folds to — the {@code aggregateIdentity} facet's
 * value vocabulary (CATALOG-SPEC §3c). This is the terminal case the {@code emptyOperand} dimension
 * deliberately leaves out (§4a): {@code emptyOperand} answers "one operand empty"; this answers "the
 * WHOLE selection empty". Derived from the locked findings, not designed a priori.
 *
 * <p>The split is per <em>element kind</em>: the number aggregates fold to the fillable {@code 0}
 * ({@link #ZERO}), while a date-like fold yields no value ({@link #NO_VALUE}) — so an operator whose element
 * kind varies ({@code Min}/{@code Max}/{@code FirstFilledValue}) carries a {@code perKind} split, not a
 * single value.
 */
public enum AggregateIdentity {

    /** The empty fold is the fillable <b>0</b> — `Sum(∅) = 0`, `MaxValue(∅) = 0`, a number
     *  `Min`/`Max`/`FirstFilledValue` over an empty selection (KERNEL-FINDINGS §1; IF51; IF80). A
     *  comparison against 0 therefore fires on the empty selection. */
    ZERO,

    /** The empty fold yields <b>no value</b> — a NULL date-like value (the kernel's `DateCombiner`,
     *  IF58/IF204) or the owner kind's NOT-GIVEN value for every non-number kind admitted by
     *  {@code FirstFilledValue} (IF83). The enclosing comparison does not fire (there is no value to compare);
     *  a computation into that target is CLEARED. */
    NO_VALUE
}
