package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code polarity} facet claim (CATALOG-SPEC §3d) — how this operator propagates the directional
 * fillability of an unfilled operand ({@link FillPolarity}). Operator-level (single value), like
 * {@link BoundaryClaim}.
 *
 * <p>{@code basis} names the lock (IF73/IF51/IF71); {@code probes} each pin a fired {@code F(empty) >= 0}
 * comparison whose {@code outcome} the lane asserts — the UP direction is the discriminating one
 * (SYMMETRIC → OMISSION, GROW_ONLY → VALUE; the DOWN direction is OMISSION for both), so a polarity
 * slip flips the outcome TYPE, not just {@code fires}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolarityClaim(FillPolarity value, String basis, String note, List<FacetProbe> probes) {

    public PolarityClaim {
        probes = probes == null ? List.of() : List.copyOf(probes);
    }
}
