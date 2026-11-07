package io.github.mbackschat.a12.dm.cli.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.core.PossibleCauseFilter;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;

/**
 * The {@code possibleCauses} key that {@code rule add} and {@code apply}'s rule add share: two lists of entities named
 * by absolute path, like every CLI path key, stored as the relative entries A12 admits from the rule's group
 * ({@link PossibleCauseFilter#ofEntities}). Whether each entity is one the condition references is A12's to judge.
 */
public final class PossibleCauseSpec {

    public static final String KEY = "possibleCauses";
    public static final String IF_FILLED = "ifFilled";
    public static final String IF_NOT_FILLED = "ifNotFilled";
    private static final List<String> LISTS = List.of(IF_FILLED, IF_NOT_FILLED);

    private PossibleCauseSpec() {
    }

    /** Why the key cannot be read, with the corrective; the {@code at} names the offending list. */
    public record Refusal(String at, String summary, String fix) {
    }

    /**
     * Sets {@code draft}'s filter from the key, for a rule in {@code group}, and answers empty; when the key cannot
     * be read, answers the first entry it cannot carry and leaves the draft as it was.
     */
    public static Optional<Refusal> applyTo(DraftRule.Builder draft, JsonNode spec, String group) {
        Optional<Refusal> refusal = refusal(spec, group);
        if (refusal.isEmpty() && spec.has(KEY)) {
            JsonNode causes = spec.get(KEY);
            draft.possibleCauses(PossibleCauseFilter.ofEntities(group, paths(causes.path(IF_FILLED)),
                    paths(causes.path(IF_NOT_FILLED))));
        }
        return refusal;
    }

    private static Optional<Refusal> refusal(JsonNode spec, String group) {
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
