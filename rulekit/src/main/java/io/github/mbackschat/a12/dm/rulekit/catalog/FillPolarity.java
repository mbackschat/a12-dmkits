package io.github.mbackschat.a12.dm.rulekit.catalog;

/**
 * How an operator's result carries the <b>directional fillability</b> of an unfilled operand — the
 * {@code polarity} facet's value vocabulary (CATALOG-SPEC §3d). This is the kernel's
 * {@code kannGroesserWerden}/{@code kannKleinerWerden} propagation (the runtime {@code Polarity}
 * object): combined with the enclosing comparison's direction it decides whether a fired comparison is
 * a VALUE or an OMISSION error. Derived from IF73 (the empty-operand value functions), not designed a
 * priori.
 *
 * <p>Only the operators whose fill class is a clean <b>per-operator scalar</b> carry a claim. The
 * compositional-fill operators — arithmetic ({@code +}/{@code −}/{@code ×}/{@code ÷}/{@code ^},
 * IF69), the value functions that transform a fillable argument ({@code Round*} preserves,
 * {@code Abs} transforms, operand-list {@code Min}/{@code Max} combine, IF71), the sign-dependent
 * starred aggregates ({@code Sum}/{@code MaxValue}/{@code MinValue}), and the filter-derived date-range
 * overlaps (IF72) — do NOT: their fill is a truth table over the operands' fills/signs or is computed
 * per evaluation, so it is not a single value a facet can carry. Those live in the differential locks
 * (`ArithmeticFillPolarityDiffTest` / `FunctionFillPolarityDiffTest` / `DateRangeOverlapSemanticsDiffTest`).
 */
public enum FillPolarity {

    /** The result can grow AND shrink when an operand is unfilled, so a fired comparison is an
     *  <b>OMISSION</b> in any direction — the date diffs and the date/time extractors (IF73), whose
     *  empty operand reads 0 and whose fill is symmetric. */
    SYMMETRIC,

    /** The result can only <b>grow</b> off an unfilled operand, so a fired {@code >=}/{@code >} is a
     *  VALUE (filling cannot clear it) while a fired {@code <}/{@code <=}/{@code ==} is an OMISSION —
     *  {@code Length} (IF73), the fill-count family (IF51), {@code RangeAsNumber} (IF71). */
    GROW_ONLY
}
