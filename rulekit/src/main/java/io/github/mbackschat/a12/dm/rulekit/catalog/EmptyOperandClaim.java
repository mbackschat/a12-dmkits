package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One {@code emptyOperand} facet claim (CATALOG-SPEC §4): what this operator does when the operand(s)
 * at {@code operand} are empty — recorded only where the operator <em>deviates</em> from (or refines)
 * the operand kind's default in the catalog header's {@code emptyOperandDefaults}.
 *
 * <p>{@code operand} selects the claim's scope: {@code "all"} (every operand — the variadic families),
 * or a 0-based index into the entry's {@code operands} array (the value-list membership predicates
 * split subject vs entries this way). Exactly one of {@code behavior} (kind-uniform) or
 * {@code perKind} (the behavior varies with the operand's value kind — the operand-list
 * {@code Min}/{@code Max}, where an empty number is substituted 0 but an empty date-like value is skipped) is
 * set. {@code basis} names the differential lock / finding the value derives from (reuse, never
 * re-derive blind); {@code probes} make the claim self-verifying (every claim needs at least one —
 * enforced structurally by {@code OperatorCatalogTest} and executed against the real kernel by the
 * facet lane).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmptyOperandClaim(
        String operand,
        EmptyBehavior behavior,
        Map<String, EmptyBehavior> perKind,
        String basis,
        String note,
        List<FacetProbe> probes) {

    public EmptyOperandClaim {
        perKind = perKind == null ? Map.of() : Map.copyOf(perKind);
        probes = probes == null ? List.of() : List.copyOf(probes);
    }

    /** Whether this claim covers the operand at {@code index} of the entry's {@code operands} array. */
    public boolean appliesTo(int index) {
        return "all".equals(operand) || String.valueOf(index).equals(operand);
    }

    /** The behavior for an operand of {@code kindToken} (a catalog kind token, e.g. {@code "number"}). */
    public Optional<EmptyBehavior> behaviorFor(String kindToken) {
        if (behavior != null) {
            return Optional.of(behavior);
        }
        return Optional.ofNullable(perKind.get(kindToken));
    }
}
