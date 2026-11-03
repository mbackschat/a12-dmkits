package io.github.mbackschat.a12.dm.adapter.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects direct authored includes and type-definition imports, never their expanded content. */
final class IncludeDiffProjector {

    private IncludeDiffProjector() {}

    /** Each referenced model, keyed by its id (DM 29.4 has no include alias, KF651), with the purposes it is
     *  referenced for, so turning an include into a type-definition import is one MODIFIED change. */
    static Map<String, Map<String, String>> project(ModelDiffState state) {
        Map<String, List<String>> purposes = new LinkedHashMap<>();
        state.document().authoredReferences().forEach((purpose, references) -> references.forEach(reference ->
                purposes.computeIfAbsent(reference, r -> new ArrayList<>()).add(purpose.name())));
        Map<String, Map<String, String>> projected = new LinkedHashMap<>();
        purposes.forEach((reference, names) -> projected.put(reference, Map.of("purpose", String.join(",", names))));
        return projected;
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
