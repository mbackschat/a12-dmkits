package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code boundary} facet claim (CATALOG-SPEC §3) — an operator-level datum: is this operator's
 * threshold / interval endpoint {@link Boundary#STRICT} (excluded) or {@link Boundary#INCLUSIVE}
 * (included)? Unlike {@link EmptyOperandClaim} (per-operand), boundary is one value for the whole
 * operator, so the claim is a single object, not a list.
 *
 * <p>{@code basis} names the differential lock / finding the value derives from (reuse, never re-derive
 * blind); {@code probes} make the claim self-verifying — each pins a document instance whose operand
 * value sits <em>exactly at</em> the endpoint, so a STRICT/INCLUSIVE mistake changes {@code fires}
 * (enforced structurally by {@code OperatorCatalogTest}, executed against the real kernel by the facet
 * lane).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoundaryClaim(Boundary value, String basis, String note, List<FacetProbe> probes) {

    public BoundaryClaim {
        probes = probes == null ? List.of() : List.copyOf(probes);
    }
}
