package io.github.mbackschat.a12.dm.rulekit.context;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * One result of {@link ModelContext#find} — a field resolved from a name, with how it matched (so a
 * caller/LLM can tell an exact hit from a fuzzy guess). Ranked by {@link How#ordinal()}. The {@code kind}
 * is the typed {@link ValueKind} (matching {@code FieldInfo.kind()}), not a stringly value.
 */
public record FieldMatch(String path, String name, ValueKind kind, How how) {

    /** How the query matched, best first. */
    public enum How {
        EXACT_PATH,
        EXACT_NAME,
        SUBSTRING,
        FUZZY
    }
}
