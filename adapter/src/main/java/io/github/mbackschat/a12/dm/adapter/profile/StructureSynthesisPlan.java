package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The immutable structure-and-field part of a complete synthesis plan.
 *
 * <p>Parents always precede children, so an eventual mechanical executor can build the tree in one
 * pass without making another placement decision.
 */
record StructureSynthesisPlan(List<GroupSlot> groups, List<FieldSlot> fields) {

    StructureSynthesisPlan {
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        for (int index = 0; index < groups.size(); index++) {
            GroupSlot group = groups.get(index);
            if (group.parentIndex() >= index) {
                throw new IllegalArgumentException(
                        "group parent must precede child " + group.name());
            }
            int expectedDepth = group.parentIndex() < 0
                    ? 1
                    : Math.incrementExact(groups.get(group.parentIndex()).depth());
            if (group.depth() != expectedDepth) {
                throw new IllegalArgumentException(
                        "group depth for " + group.name() + " must be " + expectedDepth
                                + " but was " + group.depth());
            }
        }
        for (FieldSlot field : fields) {
            if (field.groupIndex() < 0 || field.groupIndex() >= groups.size()) {
                throw new IllegalArgumentException(
                        "field group index is outside the planned group tree: "
                                + field.groupIndex());
            }
        }
    }

    String groupPath(int groupIndex) {
        if (groupIndex < 0 || groupIndex >= groups.size()) {
            throw new IllegalArgumentException(
                    "group index is outside the planned group tree: " + groupIndex);
        }
        var names = new ArrayDeque<String>();
        for (int current = groupIndex; current >= 0; current = groups.get(current).parentIndex()) {
            names.addFirst(groups.get(current).name());
        }
        return "/" + String.join("/", names);
    }

    String fieldPath(int fieldIndex) {
        if (fieldIndex < 0 || fieldIndex >= fields.size()) {
            throw new IllegalArgumentException(
                    "field index is outside the planned field list: " + fieldIndex);
        }
        FieldSlot field = fields.get(fieldIndex);
        return groupPath(field.groupIndex()) + "/" + field.name();
    }

    record GroupSlot(String name, int parentIndex, int depth, int maxRepetitions) {
        GroupSlot {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("planned group name must not be blank");
            }
            if (parentIndex < -1) {
                throw new IllegalArgumentException(
                        "planned group parent index must be -1 or greater: " + parentIndex);
            }
            if (depth < 1) {
                throw new IllegalArgumentException(
                        "planned group depth must be positive: " + depth);
            }
            if (maxRepetitions < 1) {
                throw new IllegalArgumentException(
                        "planned group repeatability must be positive: " + maxRepetitions);
            }
        }

        boolean repeatable() {
            return maxRepetitions > 1;
        }
    }

    record FieldSlot(String name, int groupIndex, ValueKind kind, boolean computed) {
        FieldSlot {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("planned field name must not be blank");
            }
            Objects.requireNonNull(kind, "kind");
        }
    }
}
