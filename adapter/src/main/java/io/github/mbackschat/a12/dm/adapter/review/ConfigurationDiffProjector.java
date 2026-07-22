package io.github.mbackschat.a12.dm.adapter.review;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/** Projects the authored model header/configuration into one stable, deterministic diff element. */
final class ConfigurationDiffProjector {

    private static final String ID = "model";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigurationDiffProjector() {}

    static Map<String, Map<String, String>> project(ModelDiffState state) {
        Map<String, Map<String, String>> configuration = new LinkedHashMap<>();
        configuration.put(ID, aspects(state.document()));
        return configuration;
    }

    static List<String> reasons(ModelDiff.ChangeKind change, List<ModelDiff.Delta> deltas) {
        return switch (change) {
            case ADDED, REMOVED -> List.of("CONFIG_CHANGED");
            case MODIFIED -> deltas.stream().map(ConfigurationDiffProjector::modifiedReason).toList();
            case REORDERED, RENAMED, MOVED -> List.of();
        };
    }

    static String modifiedReason(ModelDiff.Delta delta) {
        if (isMetadata(delta.aspect())) {
            return "METADATA_CHANGED";
        }
        if (delta.aspect().equals("locales")) {
            return setDirection(delta, "LOCALE_ADDED", "LOCALE_REMOVED");
        }
        if (delta.aspect().equals("supportedCharacters")) {
            return supportedCharacterDirection(delta);
        }
        return "CONFIG_CHANGED";
    }

    /** An empty list is the full-BMP default, so its direction is the inverse of an ordinary empty set. */
    private static String supportedCharacterDirection(ModelDiff.Delta delta) {
        Set<String> before = values(delta.before());
        Set<String> after = values(delta.after());
        if (before.isEmpty()) {
            return "CONSTRAINT_TIGHTENED";
        }
        if (after.isEmpty()) {
            return "CONSTRAINT_LOOSENED";
        }
        return setDirection(before, after, "CONSTRAINT_LOOSENED", "CONSTRAINT_TIGHTENED");
    }

    private static String setDirection(ModelDiff.Delta delta, String expanded, String restricted) {
        Set<String> before = values(delta.before());
        Set<String> after = values(delta.after());
        return setDirection(before, after, expanded, restricted);
    }

    private static String setDirection(Set<String> before, Set<String> after, String expanded, String restricted) {
        if (after.containsAll(before)) {
            return expanded;
        }
        if (before.containsAll(after)) {
            return restricted;
        }
        return "CONFIG_CHANGED";
    }

    private static boolean isMetadata(String aspect) {
        return aspect.equals("comment") || aspect.equals("roles")
                || aspect.startsWith("label.") || aspect.startsWith("annotation.");
    }

    private static Map<String, String> aspects(ModelDocument document) {
        Map<String, String> aspects = new LinkedHashMap<>();
        aspects.put("id", document.id());
        aspects.put("modelVersion", document.modelVersion());
        aspects.put("locales", canonical(document.locales()));
        ModelDocument.DocConfig config = document.config();
        aspects.put("decimalSeparator", config.decimalSeparator());
        aspects.put("timeZone", config.timeZone());
        aspects.put("conditionLanguage", config.conditionLanguage());
        aspects.put("fieldRefByShortNameAllowed", Boolean.toString(config.fieldRefByShortNameAllowed()));
        aspects.put("supportedCharacters", canonical(config.supportedCharacters()));
        document.baseYear().ifPresent(value -> aspects.put("baseYear", Integer.toString(value)));
        putSorted(aspects, "label.", document.modelLabels());
        document.modelComment().ifPresent(value -> aspects.put("comment", value));
        Map<String, String> annotations = new LinkedHashMap<>(document.modelAnnotations());
        String roles = annotations.remove("roles");
        if (roles != null) {
            List<String> roleNames = Arrays.stream(roles.split(","))
                    .map(String::trim).filter(name -> !name.isEmpty()).toList();
            if (!roleNames.isEmpty()) {
                aspects.put("roles", canonical(roleNames));
            }
        }
        putSorted(aspects, "annotation.", annotations);
        return aspects;
    }

    private static void putSorted(Map<String, String> aspects, String prefix, Map<String, String> values) {
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> aspects.put(prefix + entry.getKey(), entry.getValue()));
    }

    /** JSON is unambiguous for arbitrary Unicode values; sorting removes source-order noise from set-like slots. */
    private static String canonical(List<String> values) {
        try {
            return JSON.writeValueAsString(values.stream().distinct().sorted().toList());
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("failed to serialize string values", impossible);
        }
    }

    private static Set<String> values(String json) {
        if (json == null) {
            return Set.of();
        }
        try {
            return new LinkedHashSet<>(Arrays.asList(JSON.readValue(json, String[].class)));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("invalid canonical string values: " + json, impossible);
        }
    }
}
