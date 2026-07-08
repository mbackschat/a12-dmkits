package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The per-operator machine-readable <b>semantics facets</b> (CATALOG-SPEC §3) — the
 * SEMANTICS-FORMALIZATION §9 dimensions, one field per dimension so a new dimension slots in
 * additively without reshaping. Every facet value is a verified claim: it carries a {@code basis}
 * cross-reference to its differential lock and kernel-executed {@code probes} (the facet lane), so
 * the facets are self-verifying and cannot rot.
 *
 * <p>The dimensions differ in natural shape: {@code emptyOperand} is a <em>list</em> of per-operand
 * claims (one operator can behave differently per operand position / kind), while {@code boundary}
 * (and the other operator-level dimensions) is a <em>single</em> claim — one value for the whole
 * operator.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OperatorSemantics(List<EmptyOperandClaim> emptyOperand, BoundaryClaim boundary,
        IterationRangeClaim iterationRange, AggregateIdentityClaim aggregateIdentity) {

    public OperatorSemantics {
        emptyOperand = emptyOperand == null ? List.of() : List.copyOf(emptyOperand);
    }
}
