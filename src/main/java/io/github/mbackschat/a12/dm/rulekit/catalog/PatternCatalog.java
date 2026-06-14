package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The validation-idiom catalog (FEATURE-GAPS G2) — the rule/computation-level peer of {@link OperatorCatalog}.
 * It lists the named patterns an agent can scaffold a correct, valid rule from (right polarity, a referenced
 * error field baked in), served by {@code dmtool patterns}.
 *
 * <p>v1 holds the built-in, typed-DSL-backed patterns ({@link BuiltInPattern}). The {@link PatternEntry}
 * interface is the seam a future user extension plugs into (a data-template impl loaded from a workspace file
 * and kernel-validated), so this loader stays the single place that merges sources.
 */
public final class PatternCatalog {

    /** Declaration-ordered (deterministic output); {@code byId} is the lookup index over the same entries. */
    private final List<PatternEntry> ordered;
    private final Map<String, PatternEntry> byId;

    private PatternCatalog(List<PatternEntry> entries) {
        Map<String, PatternEntry> map = new LinkedHashMap<>();
        for (PatternEntry e : entries) {
            if (map.putIfAbsent(e.id(), e) != null) {
                throw new IllegalStateException("duplicate pattern id: " + e.id());
            }
        }
        this.ordered = List.copyOf(entries);
        this.byId = map;   // private, never mutated after construction; iterated only via `ordered`
    }

    /** Load the catalog (the built-in patterns; later, merged with any user extensions). */
    public static PatternCatalog load() {
        return new PatternCatalog(List.of(BuiltInPattern.values()));
    }

    /** Every pattern, in declaration order. */
    public List<PatternEntry> entries() {
        return ordered;
    }

    /** The pattern with {@code id}, if any. */
    public Optional<PatternEntry> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The number of patterns. */
    public int size() {
        return byId.size();
    }
}
