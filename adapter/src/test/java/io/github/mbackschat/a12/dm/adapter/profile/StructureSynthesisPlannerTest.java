package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;

class StructureSynthesisPlannerTest {

    @Test
    void plansExactCardinalitiesKindsComputedReservationsAndMaximumDepth() {
        ModelProfile profile = boundedProfile();

        StructureSynthesisPlan plan = StructureSynthesisPlanner.plan(profile, 42L);

        assertEquals(5, plan.groups().size());
        assertEquals(7, plan.fields().size());
        assertEquals(4, plan.groups().stream()
                .mapToInt(StructureSynthesisPlan.GroupSlot::depth)
                .max()
                .orElseThrow());
        assertEquals(
                Map.of(ValueKind.NUMBER, 4L, ValueKind.STRING, 2L, ValueKind.BOOLEAN, 1L),
                kindHistogram(plan, false));
        assertEquals(
                Map.of(ValueKind.NUMBER, 1L, ValueKind.STRING, 1L),
                kindHistogram(plan, true));
        assertEquals(2, plan.groups().stream()
                .filter(StructureSynthesisPlan.GroupSlot::repeatable)
                .count());
        assertEquals(
                Map.of(1, 1L, 2, 1L),
                repeatableDepthHistogram(plan));
        assertEquals(
                plan.fields().size(),
                plan.groups().stream()
                        .mapToInt(group -> fieldsIn(plan, group))
                        .sum());
    }

    @Test
    void producesOneDeeplyImmutableDecisionForTheSameSeed() {
        ModelProfile profile = boundedProfile();

        StructureSynthesisPlan first = StructureSynthesisPlanner.plan(profile, 987_654_321L);
        StructureSynthesisPlan second = StructureSynthesisPlanner.plan(profile, 987_654_321L);

        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.groups().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.fields().clear());
    }

    @Test
    void refusesEveryUnboundedRepeatabilityRepresentationBeforePlanningSlots() {
        ModelProfile base = boundedProfile();

        assertUnbounded(
                withGroupCount(
                        withRepeatability(base, repeatability ->
                                new ModelProfile.RepeatabilityProfile(
                                        histogram("BOUNDED", 1, "UNBOUNDED", 1),
                                        repeatability.topology(),
                                        repeatability.capacities())),
                        StructureSynthesisPlanner.MAX_PLANNED_SLOTS + 1),
                "$.structure.repeatability.outcomes");
        assertUnbounded(
                withCapacity(base, capacities -> new ModelProfile.RepeatableCapacities(
                        capacityWithUnbounded(new long[] {20}, "DECLARED", 1),
                        capacities.groupChains(),
                        capacities.fieldChains())),
                "$.structure.repeatability.capacities.maxRepetitions");
        assertUnbounded(
                withCapacity(base, capacities -> new ModelProfile.RepeatableCapacities(
                        capacities.maxRepetitions(),
                        capacityWithUnbounded(new long[] {20}, "OVERFLOW", 1),
                        capacities.fieldChains())),
                "$.structure.repeatability.capacities.groupChains");
        assertUnbounded(
                withCapacity(base, capacities -> new ModelProfile.RepeatableCapacities(
                        capacities.maxRepetitions(),
                        capacities.groupChains(),
                        capacityWithUnbounded(new long[] {1, 1, 1, 1, 1, 1}, "DECLARED", 1))),
                "$.structure.repeatability.capacities.fieldChains");
    }

    @Test
    void reportsOversizedGroupAndFieldCountsWithoutNarrowingThem() {
        long tooMany = StructureSynthesisPlanner.MAX_PLANNED_SLOTS + 1;

        IllegalArgumentException groups = assertThrows(
                IllegalArgumentException.class,
                () -> StructureSynthesisPlanner.plan(withGroupCount(boundedProfile(), tooMany), 1L));
        assertEquals(
                "group count " + tooMany + " exceeds planner limit "
                        + StructureSynthesisPlanner.MAX_PLANNED_SLOTS,
                groups.getMessage());

        IllegalArgumentException fields = assertThrows(
                IllegalArgumentException.class,
                () -> StructureSynthesisPlanner.plan(withFieldCount(boundedProfile(), tooMany), 1L));
        assertEquals(
                "field count " + tooMany + " exceeds planner limit "
                        + StructureSynthesisPlanner.MAX_PLANNED_SLOTS,
                fields.getMessage());
    }

    @Test
    void refusesAComputedKindThatTheFieldPopulationCannotReserve() {
        ModelProfile base = boundedProfile();
        ModelProfile.ComputationProfile computation = base.computations();
        var shape = new ModelProfile.ComputationShape(
                histogram("ENUMERATION", 2),
                computation.shape().tables(),
                computation.shape().archetypes(),
                computation.shape().aggregateScaleMatch());
        ModelProfile invalid = new ModelProfile(
                base.structure(),
                base.rules(),
                new ModelProfile.ComputationProfile(
                        computation.programs(),
                        shape,
                        computation.dependencies()),
                base.wiring());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> StructureSynthesisPlanner.plan(invalid, 42L));

        assertEquals(
                "computed-field kind ENUMERATION needs 2 slots, but the field histogram supplies 0",
                failure.getMessage());
    }

    private static ModelProfile boundedProfile() {
        ModelProfile base = ProfileJsonFixture.profile(false);
        var structure = new ModelProfile.StructureProfile(
                histogram("FIELD", 7, "GROUP", 5, "RULE", 3, "COMPUTATION", 2),
                new ModelProfile.TopologyProfile(4, distribution(0, 1, 1, 2, 3)),
                new ModelProfile.FieldProfile(
                        histogram("NUMBER", 4, "STRING", 2, "BOOLEAN", 1),
                        LongHistogram.empty(),
                        distribution(0, 2, 2, 3),
                        LongDistribution.empty()),
                new ModelProfile.RepeatabilityProfile(
                        histogram("BOUNDED", 2),
                        new ModelProfile.RepeatableTopology(
                                2,
                                histogram("1", 1, "2", 1),
                                distribution(1)),
                        new ModelProfile.RepeatableCapacities(
                                capacity(10, 20),
                                capacity(10, 200),
                                capacity(1, 1, 1, 10, 10, 20, 200))));
        return new ModelProfile(
                structure, base.rules(), base.computations(), base.wiring());
    }

    private static ModelProfile withRepeatability(
            ModelProfile profile,
            UnaryOperator<ModelProfile.RepeatabilityProfile> replacement) {
        var structure = profile.structure();
        return new ModelProfile(
                new ModelProfile.StructureProfile(
                        structure.elements(),
                        structure.topology(),
                        structure.fields(),
                        replacement.apply(structure.repeatability())),
                profile.rules(),
                profile.computations(),
                profile.wiring());
    }

    private static ModelProfile withCapacity(
            ModelProfile profile,
            UnaryOperator<ModelProfile.RepeatableCapacities> replacement) {
        return withRepeatability(profile, repeatability ->
                new ModelProfile.RepeatabilityProfile(
                        repeatability.outcomes(),
                        repeatability.topology(),
                        replacement.apply(repeatability.capacities())));
    }

    private static ModelProfile withGroupCount(ModelProfile profile, long groupCount) {
        var old = profile.structure();
        var structure = new ModelProfile.StructureProfile(
                histogram(
                        "FIELD", 7,
                        "GROUP", groupCount,
                        "RULE", 3,
                        "COMPUTATION", 2),
                new ModelProfile.TopologyProfile(
                        1, constantDistribution(groupCount, 0)),
                old.fields(),
                old.repeatability());
        return new ModelProfile(
                structure, profile.rules(), profile.computations(), profile.wiring());
    }

    private static ModelProfile withFieldCount(ModelProfile profile, long fieldCount) {
        var old = profile.structure();
        var structure = new ModelProfile.StructureProfile(
                histogram(
                        "FIELD", fieldCount,
                        "GROUP", 1,
                        "RULE", 3,
                        "COMPUTATION", 2),
                new ModelProfile.TopologyProfile(1, distribution(fieldCount)),
                new ModelProfile.FieldProfile(
                        histogram("NUMBER", fieldCount),
                        LongHistogram.empty(),
                        LongDistribution.empty(),
                        LongDistribution.empty()),
                new ModelProfile.RepeatabilityProfile(
                        LongHistogram.empty(),
                        new ModelProfile.RepeatableTopology(
                                0, LongHistogram.empty(), LongDistribution.empty()),
                        new ModelProfile.RepeatableCapacities(
                                emptyCapacity(),
                                emptyCapacity(),
                                new ModelProfile.CapacityProfile(
                                        constantDistribution(fieldCount, 1),
                                        LongHistogram.empty()))));
        return new ModelProfile(
                structure, profile.rules(), profile.computations(), profile.wiring());
    }

    private static void assertUnbounded(ModelProfile profile, String path) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> StructureSynthesisPlanner.plan(profile, 42L));
        assertTrue(failure.getMessage().startsWith(path), failure::getMessage);
    }

    private static Map<ValueKind, Long> kindHistogram(
            StructureSynthesisPlan plan, boolean computedOnly) {
        return plan.fields().stream()
                .filter(field -> !computedOnly || field.computed())
                .collect(Collectors.groupingBy(
                        StructureSynthesisPlan.FieldSlot::kind,
                        Collectors.counting()));
    }

    private static Map<Integer, Long> repeatableDepthHistogram(
            StructureSynthesisPlan plan) {
        var result = new LinkedHashMap<Integer, Long>();
        for (int index = 0; index < plan.groups().size(); index++) {
            if (!plan.groups().get(index).repeatable()) {
                continue;
            }
            int depth = 0;
            for (int current = index;
                    current >= 0;
                    current = plan.groups().get(current).parentIndex()) {
                if (plan.groups().get(current).repeatable()) {
                    depth++;
                }
            }
            result.merge(depth, 1L, Long::sum);
        }
        return result;
    }

    private static int fieldsIn(
            StructureSynthesisPlan plan, StructureSynthesisPlan.GroupSlot group) {
        int groupIndex = plan.groups().indexOf(group);
        return Math.toIntExact(plan.fields().stream()
                .filter(field -> field.groupIndex() == groupIndex)
                .count());
    }

    private static ModelProfile.CapacityProfile capacity(long... values) {
        return new ModelProfile.CapacityProfile(
                distribution(values), LongHistogram.empty());
    }

    private static ModelProfile.CapacityProfile capacityWithUnbounded(
            long[] bounded, String cause, long count) {
        return new ModelProfile.CapacityProfile(
                distribution(bounded), histogram(cause, count));
    }

    private static ModelProfile.CapacityProfile emptyCapacity() {
        return new ModelProfile.CapacityProfile(
                LongDistribution.empty(), LongHistogram.empty());
    }

    private static LongDistribution distribution(long... values) {
        return LongDistribution.of(Arrays.stream(values).boxed().toList());
    }

    private static LongDistribution constantDistribution(long count, long value) {
        if (count == 0) {
            return LongDistribution.empty();
        }
        long lower = LongDistribution.bucketLower(value);
        return new LongDistribution(
                count,
                value,
                value,
                value,
                value,
                java.util.List.of(new LongDistribution.Bucket(
                        lower, LongDistribution.bucketUpper(lower), count)));
    }

    private static LongHistogram histogram(Object... pairs) {
        var counts = new LinkedHashMap<String, Long>();
        for (int index = 0; index < pairs.length; index += 2) {
            counts.put(
                    (String) pairs[index],
                    ((Number) pairs[index + 1]).longValue());
        }
        return LongHistogram.of(counts);
    }
}
