package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * Mechanically materializes a decided structure plan through modelkit's typed authoring surface.
 *
 * <p>The executor makes no placement or fidelity decisions. A computed slot is declared like every
 * other field; the later program executor attaches its planned computation to that target.
 */
final class StructureSynthesisExecutor {

    private static final String MODEL_ID = "ProfileCalibration";
    private static final String LOCALE = "en_US";
    private static final String SYNTHETIC_CUSTOM_TYPE = "ProfileCalibrationText";

    private StructureSynthesisExecutor() {}

    static DocumentModelDraft execute(StructureSynthesisPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.groups().isEmpty()) {
            throw new IllegalArgumentException(
                    "a synthesized document model needs at least one planned group");
        }
        requireRepresentableFields(plan);

        PlanIndex index = PlanIndex.of(plan);
        DocumentModelDraft.Builder draft =
                DocumentModelDraft.named(MODEL_ID).locale(LOCALE);
        for (int groupIndex = 0; groupIndex < plan.groups().size(); groupIndex++) {
            StructureSynthesisPlan.GroupSlot group = plan.groups().get(groupIndex);
            if (group.parentIndex() < 0) {
                int rootIndex = groupIndex;
                draft.rootGroup(
                        group.name(),
                        builder -> renderGroup(builder, rootIndex, plan, index));
            }
        }
        return draft.build();
    }

    private static void requireRepresentableFields(StructureSynthesisPlan plan) {
        for (int fieldIndex = 0; fieldIndex < plan.fields().size(); fieldIndex++) {
            StructureSynthesisPlan.FieldSlot field = plan.fields().get(fieldIndex);
            if (field.kind() == ValueKind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "planned field " + plan.fieldPath(fieldIndex)
                                + " has no synthesizable typed field kind");
            }
        }
    }

    private static void renderGroup(
            DocumentModelDraft.GroupBuilder builder,
            int groupIndex,
            StructureSynthesisPlan plan,
            PlanIndex index) {
        StructureSynthesisPlan.GroupSlot group = plan.groups().get(groupIndex);
        if (group.repeatable()) {
            builder.repeatable(group.maxRepetitions());
        }
        for (StructureSynthesisPlan.FieldSlot field : index.fields().get(groupIndex)) {
            addField(builder, field);
        }
        for (int childIndex : index.children().get(groupIndex)) {
            StructureSynthesisPlan.GroupSlot child = plan.groups().get(childIndex);
            builder.group(
                    child.name(),
                    childBuilder -> renderGroup(
                            childBuilder, childIndex, plan, index));
        }
    }

    private static void addField(
            DocumentModelDraft.GroupBuilder group,
            StructureSynthesisPlan.FieldSlot field) {
        String name = field.name();
        switch (field.kind()) {
            case NUMBER -> group.field(name, FieldKind.NUMBER);
            case STRING -> group.field(name, FieldKind.STRING);
            case ENUMERATION ->
                    group.enumField(name, values ->
                            values.value("VALUE_1").value("VALUE_2"));
            case BOOLEAN -> group.field(name, FieldKind.BOOLEAN);
            case CONFIRM -> group.field(name, FieldKind.CONFIRM);
            case DATE -> group.field(name, FieldKind.DATE);
            case DATE_TIME ->
                    group.dateTimeField(
                            name, value -> value.format("yyyy-MM-dd'T'HH:mm:ss"));
            case TIME ->
                    group.timeField(name, value -> value.format("HH:mm:ss"));
            case DATE_FRAGMENT ->
                    group.dateFragmentField(
                            name, value -> value.fragmentFormat("yyyy-MM"));
            case DATE_RANGE ->
                    group.dateRangeField(
                            name,
                            value -> value
                                    .format("yyyy-MM-dd")
                                    .separator("/"));
            case CUSTOM ->
                    group.customField(
                            name, value -> value.type(SYNTHETIC_CUSTOM_TYPE));
            case UNKNOWN -> throw new IllegalStateException(
                    "UNKNOWN field escaped synthesis preflight");
        }
    }

    private record PlanIndex(
            List<List<Integer>> children,
            List<List<StructureSynthesisPlan.FieldSlot>> fields) {

        private PlanIndex {
            children = immutableBuckets(children);
            fields = immutableBuckets(fields);
        }

        static PlanIndex of(StructureSynthesisPlan plan) {
            List<List<Integer>> children = buckets(plan.groups().size());
            for (int groupIndex = 0; groupIndex < plan.groups().size(); groupIndex++) {
                int parentIndex = plan.groups().get(groupIndex).parentIndex();
                if (parentIndex >= 0) {
                    children.get(parentIndex).add(groupIndex);
                }
            }
            List<List<StructureSynthesisPlan.FieldSlot>> fields =
                    buckets(plan.groups().size());
            for (StructureSynthesisPlan.FieldSlot field : plan.fields()) {
                fields.get(field.groupIndex()).add(field);
            }
            return new PlanIndex(children, fields);
        }

        private static <T> List<List<T>> buckets(int count) {
            var result = new ArrayList<List<T>>(count);
            for (int index = 0; index < count; index++) {
                result.add(new ArrayList<>());
            }
            return result;
        }

        private static <T> List<List<T>> immutableBuckets(
                List<List<T>> source) {
            return source.stream().map(List::copyOf).toList();
        }
    }
}
