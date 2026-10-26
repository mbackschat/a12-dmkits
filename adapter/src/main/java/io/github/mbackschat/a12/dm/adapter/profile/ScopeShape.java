package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.core.EntityPaths;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupInfo;

/**
 * The model topology a synthesized rule attaches to: each group's candidate fields, each group's child groups, and
 * which groups repeat. A construct's availability and the operand it picks are both queries on this shape, so the
 * two cannot read different topologies.
 */
record ScopeShape(Map<String, List<FieldInfo>> byGroup, Map<String, List<GroupInfo>> childrenOf, Set<String> repeatable) {

    static final ScopeShape EMPTY = new ScopeShape(Map.of(), Map.of(), Set.of());

    /** The shape over {@code fields}, the rule candidates, grouped in their model order. */
    static ScopeShape of(BoundModel model, List<FieldInfo> fields) {
        Map<String, List<FieldInfo>> byGroup = new LinkedHashMap<>();
        for (FieldInfo field : fields) {
            byGroup.computeIfAbsent(field.groupPath(), ignored -> new ArrayList<>()).add(field);
        }
        Map<String, List<GroupInfo>> childrenOf = new LinkedHashMap<>();
        model.groups().forEach(group -> {
            String parent = parentOf(group.path());
            if (!parent.equals(group.path())) {
                childrenOf.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(group);
            }
        });
        Set<String> repeatable = model.groups().filter(GroupInfo::repeating)
                .map(GroupInfo::path).collect(Collectors.toSet());
        return new ScopeShape(byGroup, childrenOf, repeatable);
    }

    List<FieldInfo> fieldsOf(String group) {
        return byGroup.getOrDefault(group, List.of());
    }

    /** The first non-repeatable child group, whose fill a GROUP_FILL clause tests. */
    String groupFillTarget(String scope) {
        for (GroupInfo child : childrenOf.getOrDefault(scope, List.of())) {
            if (!repeatable.contains(child.path())) {
                return child.path();
            }
        }
        return null;
    }

    /** The first starred number cell below {@code group}, through non-repeatable children, as an aggregate reads it. */
    String aggregateOperand(String group) {
        for (GroupInfo child : childrenOf.getOrDefault(group, List.of())) {
            if (repeatable.contains(child.path())) {
                for (FieldInfo field : fieldsOf(child.path())) {
                    if (field.kind() == ValueKind.NUMBER) {
                        return child.path() + "*/" + field.name();
                    }
                }
            } else {
                String deeper = aggregateOperand(child.path());
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    /** The first field of the first repeatable group below {@code group}, the key a uniqueness clause checks. */
    String uniquenessKeyField(String group) {
        for (GroupInfo child : childrenOf.getOrDefault(group, List.of())) {
            if (repeatable.contains(child.path())) {
                List<FieldInfo> local = fieldsOf(child.path());
                if (!local.isEmpty()) {
                    return local.get(0).path();
                }
            } else {
                String deeper = uniquenessKeyField(child.path());
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    /** Two starred number cells below {@code scope} in repetitions neither of which contains the other. */
    List<String> siblingAggregatePair(String scope) {
        List<String> cells = new ArrayList<>();
        List<String> repetitions = new ArrayList<>();
        collectRepeatableCells(scope, cells, repetitions);
        for (int first = 0; first < repetitions.size(); first++) {
            for (int second = first + 1; second < repetitions.size(); second++) {
                String left = repetitions.get(first);
                String right = repetitions.get(second);
                if (!EntityPaths.isStrictlyInside(left, right) && !EntityPaths.isStrictlyInside(right, left)) {
                    return List.of(cells.get(first), cells.get(second));
                }
            }
        }
        return null;
    }

    private void collectRepeatableCells(String group, List<String> cells, List<String> repetitions) {
        for (GroupInfo child : childrenOf.getOrDefault(group, List.of())) {
            if (repeatable.contains(child.path())) {
                for (FieldInfo field : fieldsOf(child.path())) {
                    if (field.kind() == ValueKind.NUMBER) {
                        cells.add(child.path() + "*/" + field.name());
                        repetitions.add(child.path());
                        break;
                    }
                }
            } else {
                collectRepeatableCells(child.path(), cells, repetitions);
            }
        }
    }

    /** 0 for a model-level scope, 1 for a nested group, 2 for a scope inside a repetition. */
    int scopeClass(String groupPath) {
        for (String path = groupPath; ; ) {
            if (repeatable.contains(path)) {
                return 2;
            }
            String parent = parentOf(path);
            if (parent.equals(path)) {
                break;
            }
            path = parent;
        }
        return depthOf(groupPath) <= 1 ? 0 : 1;
    }

    private static int depthOf(String path) {
        int depth = 0;
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '/') {
                depth++;
            }
        }
        return depth;
    }

    static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : path;
    }
}
