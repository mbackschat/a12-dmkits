package io.github.mbackschat.a12.dm.rulekit.catalog;

/**
 * Which repetition range a field-fill quantifier iterates over a starred / group scope — the
 * {@code iterationRange} facet's value vocabulary (CATALOG-SPEC §3b). Derived from IF49 (the kernel's
 * two iteration ranges), not designed a priori.
 *
 * <p>{@link #INSTANTIATED} is the natural <b>default</b> (a quantifier looks at the rows that exist),
 * so only the operators that <em>deviate</em> carry a claim: {@code AllFieldsFilled} /
 * {@code NotAllFieldsFilled} scan the full DECLARED range (an un-instantiated rep counts as empty), and
 * {@code FieldsNotCollectivelyFilled} MIXES the two. The count quantifiers ({@code AtLeastOneFieldFilled},
 * {@code NoFieldFilled}, {@code MoreThanOneFieldFilled}, {@code NotExactlyOneFieldFilled}) are
 * observationally identical under either range for <em>firing</em> — an un-instantiated rep contributes
 * no filled cell — so they carry no claim (the deviations-only rule).
 */
public enum IterationRange {

    /** Iterate the rows that <b>exist</b> (are instantiated) — the natural default; not recorded as a
     *  claim (the count quantifiers), only named here for the vocabulary. */
    INSTANTIATED,

    /** Iterate the <b>full declared</b> repetition range — an un-instantiated rep counts as empty, so
     *  {@code AllFieldsFilled(/Items*)} is TRUE only when every <em>declared</em> rep is filled (IF49). */
    DECLARED,

    /** A <b>mix</b> of both: {@code FieldsNotCollectivelyFilled} fires on
     *  instantiated-≥1-filled AND declared-≥1-empty (IF49). */
    MIXED
}
