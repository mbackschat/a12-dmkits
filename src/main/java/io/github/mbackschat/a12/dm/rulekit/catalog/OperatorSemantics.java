package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The per-operator machine-readable <b>semantics facets</b> (CATALOG-SPEC §3) — the
 * SEMANTICS-FORMALIZATION §9 dimensions, one field per dimension so later dimensions
 * ({@code polarity}, {@code iterationRange}, {@code boundary}, {@code aggregateIdentity}) slot in
 * additively without reshaping. Every facet value is a verified claim: it carries a {@code basis}
 * cross-reference to its differential lock and kernel-executed {@code probes} (the facet lane), so
 * the facets are self-verifying and cannot rot.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OperatorSemantics(List<EmptyOperandClaim> emptyOperand) {

    public OperatorSemantics {
        emptyOperand = emptyOperand == null ? List.of() : List.copyOf(emptyOperand);
    }
}
