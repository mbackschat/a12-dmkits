package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * Plans the complete group tree, field slots, and computed-field reservations without rendering.
 */
final class StructureSynthesisPlanner {

    static final long MAX_PLANNED_SLOTS = 1_000_000L;

    private StructureSynthesisPlanner() {}

    static StructureSynthesisPlan plan(ModelProfile profile, long seed) {
        Objects.requireNonNull(profile, "profile");
        ModelProfile.StructureProfile structure = profile.structure();

        // This gate deliberately precedes every count conversion and collection allocation. An
        // unbounded source profile needs an owner-supplied calibration policy; guessing one here would
        // manufacture a falsely cheap performance model.
        requireBounded(structure.repeatability());

        long groupCount = structure.elementCount("GROUP");
        long fieldCount = structure.elementCount("FIELD");
        int groups = requireSlotCount("group", groupCount);
        int fields = requireSlotCount("field", fieldCount);
        validateTopology(structure, groups, fields);
        validateComputedSupply(
                structure.fields().kinds(),
                profile.computations().shape().computedFieldKinds());

        List<MutableGroup> mutableGroups = planGroups(
                structure,
                groups,
                randomFor(seed, "structure.groups"),
                randomFor(seed, "structure.repeatability"));
        int[] widths = planWidths(
                structure.topology().fieldsPerGroup(),
                fields,
                randomFor(seed, "structure.widths"));
        List<ValueKind> fieldKinds =
                expandKinds(structure.fields().kinds(), fields, "field-kind");
        shuffle(fieldKinds, randomFor(seed, "structure.field-kinds"));
        boolean[] computed = reserveComputedFields(
                fieldKinds,
                profile.computations().shape().computedFieldKinds(),
                randomFor(seed, "structure.computed-fields"));

        var plannedGroups = new ArrayList<StructureSynthesisPlan.GroupSlot>(groups);
        for (int index = 0; index < mutableGroups.size(); index++) {
            MutableGroup group = mutableGroups.get(index);
            plannedGroups.add(new StructureSynthesisPlan.GroupSlot(
                    groupName(index),
                    group.parentIndex,
                    group.depth,
                    group.maxRepetitions));
        }

        var plannedFields = new ArrayList<StructureSynthesisPlan.FieldSlot>(fields);
        int fieldIndex = 0;
        for (int groupIndex = 0; groupIndex < widths.length; groupIndex++) {
            for (int inGroup = 0; inGroup < widths[groupIndex]; inGroup++) {
                ValueKind kind = fieldKinds.get(fieldIndex);
                plannedFields.add(new StructureSynthesisPlan.FieldSlot(
                        fieldName(kind, fieldIndex),
                        groupIndex,
                        kind,
                        computed[fieldIndex]));
                fieldIndex++;
            }
        }
        if (fieldIndex != fields) {
            throw new IllegalStateException(
                    "planned field widths produced " + fieldIndex + " slots instead of " + fields);
        }
        return new StructureSynthesisPlan(plannedGroups, plannedFields);
    }

    private static void requireBounded(ModelProfile.RepeatabilityProfile repeatability) {
        long unboundedGroups =
                repeatability.outcomes().counts().getOrDefault("UNBOUNDED", 0L);
        if (unboundedGroups > 0) {
            throw new IllegalArgumentException(
                    "$.structure.repeatability.outcomes contains " + unboundedGroups
                            + " UNBOUNDED group(s); synthesis needs an explicit bounded calibration policy");
        }
        requireBounded(
                "$.structure.repeatability.capacities.maxRepetitions",
                repeatability.capacities().maxRepetitions());
        requireBounded(
                "$.structure.repeatability.capacities.groupChains",
                repeatability.capacities().groupChains());
        requireBounded(
                "$.structure.repeatability.capacities.fieldChains",
                repeatability.capacities().fieldChains());
    }

    private static void requireBounded(
            String path, ModelProfile.CapacityProfile capacity) {
        if (capacity.unboundedCauses().total() > 0) {
            throw new IllegalArgumentException(
                    path + " is unbounded " + capacity.unboundedCauses().counts()
                            + "; synthesis needs an explicit bounded calibration policy");
        }
    }

    private static int requireSlotCount(String kind, long count) {
        if (count > MAX_PLANNED_SLOTS) {
            throw new IllegalArgumentException(
                    kind + " count " + count + " exceeds planner limit "
                            + MAX_PLANNED_SLOTS);
        }
        return Math.toIntExact(count);
    }

    private static void validateTopology(
            ModelProfile.StructureProfile structure, int groupCount, int fieldCount) {
        int maxDepth = structure.topology().maxGroupDepth();
        long repeatableCount = structure.repeatability().outcomes().total();
        int maxRepeatableDepth = structure.repeatability().topology().maxDepth();

        if (groupCount == 0) {
            if (fieldCount != 0) {
                throw new IllegalArgumentException(
                        "field count " + fieldCount + " needs at least one planned group");
            }
            if (maxDepth != 0 || repeatableCount != 0 || maxRepeatableDepth != 0) {
                throw new IllegalArgumentException(
                        "an empty group structure must have zero group and repeatable depth");
            }
            return;
        }
        if (maxDepth < 1 || maxDepth > groupCount) {
            throw new IllegalArgumentException(
                    "maximum group depth " + maxDepth
                            + " cannot be constructed from " + groupCount + " groups");
        }
        if (repeatableCount > groupCount) {
            throw new IllegalArgumentException(
                    "repeatable-group count " + repeatableCount
                            + " exceeds group count " + groupCount);
        }
        int repeatables = Math.toIntExact(repeatableCount);
        if (repeatables == 0) {
            if (maxRepeatableDepth != 0) {
                throw new IllegalArgumentException(
                        "zero repeatable groups require zero maximum repeatable depth");
            }
        } else {
            if (maxRepeatableDepth < 1 || maxRepeatableDepth > maxDepth) {
                throw new IllegalArgumentException(
                        "maximum repeatable depth " + maxRepeatableDepth
                                + " exceeds constructible group depth " + maxDepth);
            }
            for (int depth = 1; depth <= maxRepeatableDepth; depth++) {
                long count = structure.repeatability()
                        .topology()
                        .exactDepth()
                        .counts()
                        .getOrDefault(Integer.toString(depth), 0L);
                if (count == 0) {
                    throw new IllegalArgumentException(
                            "repeatable depth " + depth
                                    + " needs an ancestor slot before deeper repeatable groups");
                }
            }
        }
        long minimumGroups = Math.addExact(
                maxDepth, repeatableCount - maxRepeatableDepth);
        if (minimumGroups > groupCount) {
            throw new IllegalArgumentException(
                    "group depth " + maxDepth + " and " + repeatableCount
                            + " repeatable groups at maximum repeatable depth "
                            + maxRepeatableDepth + " need at least " + minimumGroups
                            + " groups, but the profile contains " + groupCount);
        }

        ModelProfile.CapacityProfile maxRepetitions =
                structure.repeatability().capacities().maxRepetitions();
        if (repeatables > 0) {
            if (maxRepetitions.bounded().min() < 2) {
                throw new IllegalArgumentException(
                        "$.structure.repeatability.capacities.maxRepetitions contains "
                                + "a value below the repeatable minimum 2");
            }
            if (maxRepetitions.bounded().max() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "$.structure.repeatability.capacities.maxRepetitions maximum "
                                + maxRepetitions.bounded().max()
                                + " exceeds the renderable integer maximum "
                                + Integer.MAX_VALUE);
            }
        }
        requireWidthCapacity(
                structure.topology().fieldsPerGroup(), fieldCount);
    }

    private static void requireWidthCapacity(LongDistribution distribution, long fieldCount) {
        long minimum = 0;
        long maximum = 0;
        for (LongDistribution.Bucket bucket : distribution.buckets()) {
            minimum = saturatedAdd(
                    minimum, saturatedMultiply(bucket.lower(), bucket.count()));
            maximum = saturatedAdd(
                    maximum, saturatedMultiply(bucket.upper(), bucket.count()));
        }
        if (fieldCount < minimum || fieldCount > maximum) {
            throw new IllegalArgumentException(
                    "field count " + fieldCount
                            + " is outside the fields-per-group bucket capacity ["
                            + minimum + ", " + maximum + "]");
        }
    }

    private static void validateComputedSupply(
            LongHistogram fields, LongHistogram computedFields) {
        for (var entry : computedFields.counts().entrySet()) {
            long available = fields.counts().getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() > available) {
                throw new IllegalArgumentException(
                        "computed-field kind " + entry.getKey() + " needs "
                                + entry.getValue() + " slots, but the field histogram supplies "
                                + available);
            }
        }
    }

    private static List<MutableGroup> planGroups(
            ModelProfile.StructureProfile structure,
            int groupCount,
            Random topologyRandom,
            Random repeatabilityRandom) {
        if (groupCount == 0) {
            return List.of();
        }
        int maxDepth = structure.topology().maxGroupDepth();
        int maxRepeatableDepth = structure.repeatability().topology().maxDepth();
        var groups = new ArrayList<MutableGroup>(groupCount);
        int[] repeatableAnchors = new int[maxRepeatableDepth + 1];

        for (int depth = 1; depth <= maxDepth; depth++) {
            int parent = depth == 1 ? -1 : depth - 2;
            boolean repeatable = depth <= maxRepeatableDepth;
            int index = groups.size();
            groups.add(new MutableGroup(parent, depth, repeatable));
            if (repeatable) {
                repeatableAnchors[depth] = index;
            }
        }

        for (int repeatableDepth = 1;
                repeatableDepth <= maxRepeatableDepth;
                repeatableDepth++) {
            long atDepth = structure.repeatability()
                    .topology()
                    .exactDepth()
                    .counts()
                    .get(Integer.toString(repeatableDepth));
            int extras = Math.toIntExact(atDepth - 1);
            int parent = repeatableDepth == 1
                    ? -1
                    : repeatableAnchors[repeatableDepth - 1];
            for (int extra = 0; extra < extras; extra++) {
                groups.add(new MutableGroup(parent, repeatableDepth, true));
            }
        }

        var eligibleParents = new ArrayList<Integer>(groupCount);
        for (int index = 0; index < groups.size(); index++) {
            if (groups.get(index).depth < maxDepth) {
                eligibleParents.add(index);
            }
        }
        while (groups.size() < groupCount) {
            int choice = topologyRandom.nextInt(eligibleParents.size() + 1);
            int parent = choice == eligibleParents.size()
                    ? -1
                    : eligibleParents.get(choice);
            int depth = parent < 0 ? 1 : Math.incrementExact(groups.get(parent).depth);
            int index = groups.size();
            groups.add(new MutableGroup(parent, depth, false));
            if (depth < maxDepth) {
                eligibleParents.add(index);
            }
        }

        List<Integer> repetitions = expandRepeatability(
                structure.repeatability().capacities().maxRepetitions().bounded());
        shuffle(repetitions, repeatabilityRandom);
        int repetitionIndex = 0;
        for (MutableGroup group : groups) {
            if (group.repeatable) {
                group.maxRepetitions = repetitions.get(repetitionIndex++);
            }
        }
        if (repetitionIndex != repetitions.size()) {
            throw new IllegalStateException(
                    "repeatability samples did not match planned repeatable groups");
        }
        return groups;
    }

    private static List<Integer> expandRepeatability(LongDistribution distribution) {
        var values = new ArrayList<Integer>(Math.toIntExact(distribution.count()));
        for (LongDistribution.Bucket bucket : distribution.buckets()) {
            if (bucket.lower() < 2) {
                throw new IllegalArgumentException(
                        "$.structure.repeatability.capacities.maxRepetitions bucket "
                                + bucket.lower() + "-" + bucket.upper()
                                + " contains values below the repeatable minimum 2");
            }
            int value = Math.toIntExact(bucket.lower());
            int count = Math.toIntExact(bucket.count());
            for (int index = 0; index < count; index++) {
                values.add(value);
            }
        }
        return values;
    }

    private static int[] planWidths(
            LongDistribution distribution, int fieldCount, Random random) {
        int groupCount = Math.toIntExact(distribution.count());
        int[] widths = new int[groupCount];
        int[] upperBounds = new int[groupCount];
        int cursor = 0;
        long assigned = 0;
        for (LongDistribution.Bucket bucket : distribution.buckets()) {
            int lower = Math.toIntExact(bucket.lower());
            int upper = Math.toIntExact(Math.min(bucket.upper(), fieldCount));
            int count = Math.toIntExact(bucket.count());
            for (int index = 0; index < count; index++) {
                widths[cursor] = lower;
                upperBounds[cursor] = upper;
                assigned = Math.addExact(assigned, lower);
                cursor++;
            }
        }
        long remaining = fieldCount - assigned;
        int[] order = shuffledIndexes(groupCount, random);
        for (int index : order) {
            if (remaining == 0) {
                break;
            }
            int addition = Math.toIntExact(Math.min(
                    remaining, (long) upperBounds[index] - widths[index]));
            widths[index] = Math.addExact(widths[index], addition);
            remaining -= addition;
        }
        if (remaining != 0) {
            throw new IllegalArgumentException(
                    "fields-per-group buckets leave " + remaining
                            + " fields without a legal group slot");
        }
        shuffle(widths, random);
        return widths;
    }

    private static List<ValueKind> expandKinds(
            LongHistogram histogram, int expectedCount, String role) {
        var kinds = new ArrayList<ValueKind>(expectedCount);
        for (var entry : histogram.counts().entrySet()) {
            ValueKind kind;
            try {
                kind = ValueKind.valueOf(entry.getKey());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "unknown " + role + " " + entry.getKey(), e);
            }
            int count = Math.toIntExact(entry.getValue());
            for (int index = 0; index < count; index++) {
                kinds.add(kind);
            }
        }
        if (kinds.size() != expectedCount) {
            throw new IllegalArgumentException(
                    role + " histogram contains " + kinds.size()
                            + " slots, expected " + expectedCount);
        }
        return kinds;
    }

    private static boolean[] reserveComputedFields(
            List<ValueKind> fieldKinds,
            LongHistogram computedHistogram,
            Random random) {
        var remaining = new LinkedHashMap<ValueKind, Integer>();
        computedHistogram.counts().forEach((name, count) ->
                remaining.put(ValueKind.valueOf(name), Math.toIntExact(count)));
        boolean[] computed = new boolean[fieldKinds.size()];
        for (int index : shuffledIndexes(fieldKinds.size(), random)) {
            ValueKind kind = fieldKinds.get(index);
            int needed = remaining.getOrDefault(kind, 0);
            if (needed > 0) {
                computed[index] = true;
                remaining.put(kind, needed - 1);
            }
        }
        if (remaining.values().stream().anyMatch(count -> count != 0)) {
            throw new IllegalStateException(
                    "computed-field reservation did not consume " + remaining);
        }
        return computed;
    }

    private static String groupName(int index) {
        return indexedName("Section", index);
    }

    private static String fieldName(ValueKind kind, int index) {
        String prefix = switch (kind) {
            case NUMBER -> "Amount";
            case STRING -> "Text";
            case ENUMERATION -> "Choice";
            case BOOLEAN -> "Flag";
            case CONFIRM -> "Approval";
            case DATE -> "Date";
            case DATE_TIME -> "Timestamp";
            case TIME -> "Time";
            case DATE_FRAGMENT -> "Period";
            case DATE_RANGE -> "Window";
            case CUSTOM -> "CustomValue";
            case UNKNOWN -> "UnknownValue";
        };
        return indexedName(prefix, index);
    }

    private static String indexedName(String prefix, int index) {
        String ordinal = Integer.toString(Math.incrementExact(index));
        return prefix + "0".repeat(Math.max(0, 6 - ordinal.length())) + ordinal;
    }

    private static int[] shuffledIndexes(int size, Random random) {
        int[] indexes = new int[size];
        Arrays.setAll(indexes, index -> index);
        for (int index = size - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            int value = indexes[index];
            indexes[index] = indexes[other];
            indexes[other] = value;
        }
        return indexes;
    }

    private static <T> void shuffle(List<T> values, Random random) {
        for (int index = values.size() - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            T value = values.get(index);
            values.set(index, values.get(other));
            values.set(other, value);
        }
    }

    private static void shuffle(int[] values, Random random) {
        for (int index = values.length - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            int value = values[index];
            values[index] = values[other];
            values[other] = value;
        }
    }

    private static long saturatedMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static Random randomFor(long seed, String purpose) {
        long mixed = seed + 0x9E3779B97F4A7C15L * (purpose.hashCode() + 0x100000001L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return new Random(mixed ^ (mixed >>> 31));
    }

    private static final class MutableGroup {
        private final int parentIndex;
        private final int depth;
        private final boolean repeatable;
        private int maxRepetitions = 1;

        private MutableGroup(int parentIndex, int depth, boolean repeatable) {
            this.parentIndex = parentIndex;
            this.depth = depth;
            this.repeatable = repeatable;
        }
    }
}
