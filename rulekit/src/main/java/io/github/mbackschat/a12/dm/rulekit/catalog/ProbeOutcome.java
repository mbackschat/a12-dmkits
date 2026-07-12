package io.github.mbackschat.a12.dm.rulekit.catalog;

/**
 * The kernel outcome <b>type</b> a firing {@link FacetProbe} must produce — the value-vs-omission
 * typing (CATALOG-SPEC §5a). Set only on a probe that pins an operator's fill {@code polarity} (§3d),
 * where {@code fires: true} alone is not enough — the claim is about <em>how</em> it fires. A probe
 * without an {@code outcome} checks firing only (the default for the other dimensions).
 */
public enum ProbeOutcome {

    /** A VALUE error — the firing cannot be cleared by filling more fields (kernel {@code VALUE_ERROR}). */
    VALUE,

    /** An OMISSION error — the firing could be cleared by filling more fields (kernel {@code OMISSION_ERROR}). */
    OMISSION
}
