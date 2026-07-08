package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code aggregateIdentity} facet claim (CATALOG-SPEC §3c) — what this aggregate folds to over an
 * <b>all-empty selection</b>. Operator-level, but with a per-element-kind split (like
 * {@link EmptyOperandClaim}): exactly one of {@code value} (kind-uniform — the number aggregates
 * `Sum`/`MaxValue`/`MinValue` are always {@link AggregateIdentity#ZERO}) or {@code perKind} (the fold
 * varies with the element kind — `Min`/`Max`/`FirstFilledValue` are {@code ZERO} over numbers but
 * {@code NO_VALUE} over dates/strings) is set.
 *
 * <p>{@code basis} names the lock (IF51/IF58/IF80/IF83); {@code probes} pin an all-empty selection over
 * a <b>non-index</b> field (an index field's empty cell is not-check-relevant — the invalid class, IF86,
 * not emptiness), so a ZERO / NO_VALUE slip flips {@code fires}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AggregateIdentityClaim(
        AggregateIdentity value,
        Map<String, AggregateIdentity> perKind,
        String basis,
        String note,
        List<FacetProbe> probes) {

    public AggregateIdentityClaim {
        perKind = perKind == null ? Map.of() : Map.copyOf(perKind);
        probes = probes == null ? List.of() : List.copyOf(probes);
    }

    /** The identity for an element of {@code kindToken} (a catalog kind token, e.g. {@code "number"}). */
    public Optional<AggregateIdentity> identityFor(String kindToken) {
        if (value != null) {
            return Optional.of(value);
        }
        return Optional.ofNullable(perKind.get(kindToken));
    }
}
