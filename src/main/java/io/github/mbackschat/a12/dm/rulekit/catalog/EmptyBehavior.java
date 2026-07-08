package io.github.mbackschat.a12.dm.rulekit.catalog;

/**
 * What an operator does when a given operand is <b>empty</b> (a present-but-valueless field) — the
 * {@code emptyOperand} facet's value vocabulary (CATALOG-SPEC §3). Derived from the differentially
 * locked kernel findings, not designed a priori; each value's canonical lock is named in
 * CATALOG-SPEC. The per-kind <em>defaults</em> (an empty NUMBER reads 0, an empty STRING is not
 * evaluated, …) live once in the catalog header ({@code emptyOperandDefaults}); an operator carries
 * a claim only where it <em>deviates</em> from (or refines) the operand kind's default.
 */
public enum EmptyBehavior {

    /** The empty operand participates as the fillable {@code 0} — the evaluation proceeds and can
     *  fire ({@code [Quantity] < 100} fires on an empty Quantity; {@code Length(empty)} is 0). */
    AS_ZERO,

    /** The empty operand participates as {@code False} (the CONFIRM kind's default:
     *  {@code [TermsConfirmed] != True} fires on an empty confirm). */
    AS_FALSE,

    /** The empty operand participates as the empty string {@code ""} (a string field inside a
     *  concatenation — unlike a bare string comparison, which is not evaluated). */
    AS_EMPTY_STRING,

    /** The enclosing comparison/predicate is not evaluated — no verdict, the rule cannot fire on
     *  this operand's emptiness (an empty string/boolean/date/enum in a comparison; a value-list
     *  membership subject). */
    NOT_EVALUATED,

    /** The operand is dropped from the operator's set/fold; the operator still evaluates over the
     *  remaining operands/cells (a starred aggregate's empty cell; a date {@code Min}/{@code Max}
     *  operand; a value-list entry that can never match). */
    SKIPPED,

    /** The predicate evaluates to False — it does not fire, but unlike {@link #NOT_EVALUATED} this
     *  is a definite verdict ({@code AtLeastOneDateRangeOverlaps} with an empty scalar range). */
    EVALUATES_FALSE
}
