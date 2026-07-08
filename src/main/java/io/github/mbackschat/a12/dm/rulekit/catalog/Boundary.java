package io.github.mbackschat.a12.dm.rulekit.catalog;

/**
 * Whether an operator's threshold / interval endpoint is <b>included</b> — the {@code boundary} facet's
 * value vocabulary (CATALOG-SPEC §3). Derived from the differentially locked kernel findings, not
 * designed a priori: the ordering comparisons are STRICT (<code>&lt;</code>/<code>&gt;</code>) vs INCLUSIVE
 * (<code>&lt;=</code>/<code>&gt;=</code>) by definition, the tolerance band is STRICT (fires only
 * <em>beyond</em> N — a difference exactly equal to N does not fire, {@code ToleranceDiffTest}), and the
 * date-range overlap predicates are INCLUSIVE (CLOSED intervals — touching ranges overlap, IF72).
 *
 * <p>The facet answers "does the endpoint value itself satisfy the operator?" — the datum a judge needs
 * to reason that {@code [Q] < 100} and {@code [Q] <= 99} are equivalent over integers but
 * {@code [Q] < 100} and {@code [Q] <= 100} are not.
 */
public enum Boundary {

    /** The endpoint value is <b>excluded</b> — it does NOT satisfy the operator ({@code <}/{@code >}; the
     *  tolerance band fires only when {@code |a − b| > N}, not at {@code = N}). */
    STRICT,

    /** The endpoint value is <b>included</b> — it DOES satisfy the operator ({@code <=}/{@code >=}; the
     *  date-range overlap predicates use CLOSED intervals, so touching ranges overlap). */
    INCLUSIVE
}
