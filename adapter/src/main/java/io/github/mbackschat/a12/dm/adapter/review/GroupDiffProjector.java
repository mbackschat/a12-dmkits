package io.github.mbackschat.a12.dm.adapter.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.GroupMeta;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/** Projects authored group properties, never child structure, into deterministic diff aspects. */
final class GroupDiffProjector {

    private GroupDiffProjector() {}

    static Map<String, Map<String, String>> project(ModelDiffState state) {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        state.authored().groups().forEach((path, group) -> groups.put(path, aspects(group)));
        return groups;
    }

    static List<String> reasons(ModelDiff.ChangeKind change, List<ModelDiff.Delta> deltas) {
        return switch (change) {
            case ADDED -> List.of("STRUCTURE_ADDED");
            case REMOVED -> List.of("STRUCTURE_REMOVED");
            case MODIFIED -> modifiedReasons(deltas);
            case REORDERED, RENAMED, MOVED -> List.of();
        };
    }

    static String modifiedReason(ModelDiff.Delta delta) {
        if (delta.aspect().equals("repeatability")) {
            return repetitionReason(delta);
        }
        return isMetadata(delta.aspect()) ? "METADATA_CHANGED" : "GROUP_CHANGED";
    }

    private static List<String> modifiedReasons(List<ModelDiff.Delta> deltas) {
        List<String> reasons = new ArrayList<>();
        for (ModelDiff.Delta delta : deltas) {
            if (delta.aspect().equals("repeatability")) {
                reasons.add("REPEATABILITY_CHANGED");
            }
            reasons.add(modifiedReason(delta));
        }
        return reasons;
    }

    private static String repetitionReason(ModelDiff.Delta delta) {
        int before = Integer.parseInt(delta.before());
        int after = Integer.parseInt(delta.after());
        return after > before ? "CONSTRAINT_LOOSENED" : "CONSTRAINT_TIGHTENED";
    }

    private static boolean isMetadata(String aspect) {
        return aspect.startsWith("label.") || aspect.startsWith("internalDescription.")
                || aspect.startsWith("externalDescription.") || aspect.startsWith("annotation.");
    }

    private static Map<String, String> aspects(ModelDocument.GroupState group) {
        Map<String, String> aspects = new LinkedHashMap<>();
        var structure = group.structure();
        aspects.put("repeatability", Integer.toString(structure.repeatability()));
        structure.indexField().ifPresent(index -> aspects.put("indexField", index));
        for (int i = 0; i < structure.sortFields().size(); i++) {
            ElementDraft.SortField sort = structure.sortFields().get(i);
            aspects.put("sort[" + i + "]", sort.fieldName() + ":" + sort.order().name());
        }
        structure.mount().ifPresent(mount -> {
            aspects.put("mount.alias", mount.alias());
            aspects.put("mount.excludeRules", Boolean.toString(mount.options().excludeRules()));
            aspects.put("mount.excludeComputations", Boolean.toString(mount.options().excludeComputations()));
            aspects.put("mount.includeLevel", mount.options().includeLevel().name());
        });
        putMeta(aspects, group.meta());
        return aspects;
    }

    private static void putMeta(Map<String, String> aspects, GroupMeta meta) {
        putSorted(aspects, "label.", meta.labels());
        putSorted(aspects, "internalDescription.", meta.internalDescription());
        putSorted(aspects, "externalDescription.", meta.externalDescription());
        meta.annotations().stream().sorted(Comparator.comparing(ElementDraft.Annotation::name))
                .forEach(annotation -> aspects.put("annotation." + annotation.name(), annotation.value()));
    }

    private static void putSorted(Map<String, String> aspects, String prefix, Map<String, String> values) {
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> aspects.put(prefix + entry.getKey(), entry.getValue()));
    }
}
