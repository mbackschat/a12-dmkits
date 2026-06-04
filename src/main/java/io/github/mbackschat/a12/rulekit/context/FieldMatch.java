package io.github.mbackschat.a12.rulekit.context;

/**
 * One result of {@link ModelContext#find} — a field resolved from a name, with how it matched (so a
 * caller/LLM can tell an exact hit from a fuzzy guess). Ranked by {@link How#ordinal()}.
 */
public record FieldMatch(String path, String name, String kind, How how) {

    /** How the query matched, best first. */
    public enum How {
        EXACT_PATH,
        EXACT_NAME,
        SUBSTRING,
        FUZZY
    }
}
