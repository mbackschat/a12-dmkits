package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One kernel-executable probe backing a facet claim (CATALOG-SPEC §5) — the facet lane's N7 peer:
 * the claim's {@code dsl} is injected as a rule on the {@code Order} fixture's root group and run
 * through the <em>real kernel runtime</em> against the described document instance, asserting
 * {@code fires}. A facet claim without a passing probe fails the build, so a facet value can never
 * silently drift from the engine.
 *
 * <p>The probe contract (executed by {@code adapter.laws.CatalogFacetProbesTest}):
 * <ul>
 *   <li>{@code dsl} — the error condition, written against {@code models/order.dm.json};</li>
 *   <li>{@code kind} — for a {@code perKind} aggregate-identity claim, the exact branch this probe executes;
 *       absent on kind-uniform and non-aggregate facets;</li>
 *   <li>{@code errorField} — the rule's error field, relative to {@code /Order} (e.g.
 *       {@code "Quantity"}, {@code "Items/Count"}); explicit by design, no inference;</li>
 *   <li>{@code rows} — group rows to instantiate beyond the implicit {@code /Order}; a path part
 *       may carry a 1-based repetition index ({@code "/Order/Items[2]"});</li>
 *   <li>{@code given} — filled cells (path → value, {@code [n]} indexing as above). The lane always
 *       adds the content-bearing filler {@code /Order/CustomerName = "Acme"} unless the probe
 *       itself uses CustomerName, so every probe runs on a filled row (the KERNEL-FINDINGS §2
 *       precondition for empty-value evaluation);</li>
 *   <li>{@code empty} — instantiated-empty cells (present but valueless — how a form renders an
 *       unfilled field);</li>
 *   <li>{@code fires} — whether the rule must fire on that instance;</li>
 *   <li>{@code outcome} — (optional, the {@code polarity} dimension only) the value-vs-omission
 *       TYPE the firing must carry; when present the lane asserts {@code fires: true} <em>and</em> the
 *       fired message's type, so a fill-polarity claim is verified, not just its firing.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FacetProbe(
        String dsl,
        String kind,
        String errorField,
        List<String> rows,
        Map<String, String> given,
        List<String> empty,
        Boolean fires,
        ProbeOutcome outcome,
        String note) {

    public FacetProbe {
        rows = rows == null ? List.of() : List.copyOf(rows);
        given = given == null ? Map.of() : Map.copyOf(given);
        empty = empty == null ? List.of() : List.copyOf(empty);
    }
}
