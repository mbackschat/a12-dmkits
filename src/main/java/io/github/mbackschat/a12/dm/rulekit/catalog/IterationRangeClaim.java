package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code iterationRange} facet claim (CATALOG-SPEC §3b) — an operator-level datum for a field-fill
 * quantifier that <em>deviates</em> from the natural {@link IterationRange#INSTANTIATED} default:
 * {@link IterationRange#DECLARED} (the {@code All}/{@code NotAll} family) or {@link IterationRange#MIXED}
 * ({@code FieldsNotCollectivelyFilled}). One value per operator (single object, like {@link BoundaryClaim}).
 *
 * <p>{@code basis} names the lock (IF49); {@code probes} pin a document whose <b>declared</b> range is
 * wider than the instantiated rows, so a DECLARED / INSTANTIATED slip flips {@code fires}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IterationRangeClaim(IterationRange value, String basis, String note, List<FacetProbe> probes) {

    public IterationRangeClaim {
        probes = probes == null ? List.of() : List.copyOf(probes);
    }
}
