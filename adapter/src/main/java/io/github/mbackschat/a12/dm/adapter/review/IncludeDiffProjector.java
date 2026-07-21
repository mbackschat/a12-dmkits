package io.github.mbackschat.a12.dm.adapter.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects direct authored includes and type-definition imports, never their expanded content. */
final class IncludeDiffProjector {

    private IncludeDiffProjector() {}

    static Map<String, Map<String, String>> project(ModelDiffState state) {
        Map<String, Map<String, String>> references = new LinkedHashMap<>();
        state.document().authoredReferences().forEach((purpose, entries) -> entries.forEach((alias, reference) -> {
            Map<String, String> aspects = new LinkedHashMap<>();
            aspects.put("purpose", purpose.name());
            aspects.put("reference", reference);
            references.put(alias, aspects);
        }));
        return references;
    }

    static List<String> reasons(ModelDiff.ChangeKind change) {
        return switch (change) {
            case ADDED, REMOVED, MODIFIED -> List.of("INCLUDE_CHANGED");
            case REORDERED, RENAMED, MOVED -> List.of();
        };
    }

    static String modifiedReason(ModelDiff.Delta delta) {
        return "INCLUDE_CHANGED";
    }
}
