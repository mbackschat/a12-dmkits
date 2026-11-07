package io.github.mbackschat.a12.dm.cli.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.core.PossibleCauseFilter;

/**
 * The {@code possibleCauses} key that {@code rule add} and {@code apply}'s rule add share: two lists of entities named
 * by absolute path, like every CLI path key, stored as the relative entries A12 admits from the rule's group
 * ({@link PossibleCauseFilter#ofEntities}). Whether each entity is one the condition references is A12's to judge.
 */
public final class PossibleCauseSpec {

    public static final String KEY = "possibleCauses";
    private static final List<String> LISTS = List.of("ifFilled", "ifNotFilled");

    private PossibleCauseSpec() {
    }

    /** Why the key cannot be read, with the corrective; the {@code at} names the offending list. */
    public record Refusal(String at, String summary, String fix) {
    }

    /** The first entry this key cannot carry, for a rule in {@code group}; empty when the key reads. */
    public static Optional<Refusal> refusal(JsonNode spec, String group) {
        if (!spec.has(KEY)) {
            return Optional.empty();
        }
        JsonNode causes = spec.get(KEY);
        for (String list : LISTS) {
            JsonNode entries = causes.path(list);
            if (!entries.isMissingNode() && !entries.isArray()) {
                return Optional.of(new Refusal(KEY + "." + list, KEY + "." + list + " must be an array of paths",
                        "give it as {\"ifFilled\": [\"/Group/Field\"], \"ifNotFilled\": [\"/Group\"]}"));
            }
            for (JsonNode entry : entries) {
                if (!entry.isTextual() || !entry.asText().startsWith("/")) {
                    return Optional.of(new Refusal(KEY + "." + list,
                            KEY + "." + list + " names entities by absolute path, got: " + entry,
                            correctiveFor(entry, group)));
                }
            }
        }
        return Optional.empty();
    }

    /** The filter the key names for a rule in {@code group}; call only once {@link #refusal} is empty. */
    public static Optional<PossibleCauseFilter> filter(JsonNode spec, String group) {
        if (!spec.has(KEY)) {
            return Optional.empty();
        }
        JsonNode causes = spec.get(KEY);
        return Optional.of(PossibleCauseFilter.ofEntities(group, paths(causes.path("ifFilled")),
                paths(causes.path("ifNotFilled"))));
    }

    // A relative entry is unambiguous here, unlike an error field's: A12 stores a cause relative to the rule's
    // GROUP (KF682), so its absolute form is derived, never guessed.
    private static String correctiveFor(JsonNode entry, String group) {
        if (!entry.isTextual()) {
            return "give each entry as a string, the entity's absolute path";
        }
        return "give the absolute path; against the rule's group that entry is "
                + EntityPath.of(group).resolve(entry.asText()).value();
    }

    private static List<String> paths(JsonNode entries) {
        List<String> paths = new ArrayList<>();
        entries.forEach(entry -> paths.add(entry.asText()));
        return paths;
    }
}
