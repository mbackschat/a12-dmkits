package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** One complete, vocabulary-valid current profile shared by the JSON boundary tests. */
final class ProfileJsonFixture {

    private ProfileJsonFixture() {}

    static ModelProfile profile(boolean reverse) {
        var structure = new ModelProfile.StructureProfile(
                histogram(reverse, "FIELD", 4, "GROUP", 2, "RULE", 3, "COMPUTATION", 2),
                new ModelProfile.TopologyProfile(2, distribution(1, 3)),
                new ModelProfile.FieldProfile(
                        histogram(reverse, "NUMBER", 2, "STRING", 2),
                        histogram(reverse, "NUMBER_BOUNDS", 1, "STRING_PATTERN", 1),
                        distribution(0, 2),
                        distribution(3, 8)),
                new ModelProfile.RepeatabilityProfile(
                        histogram(reverse, "BOUNDED", 2, "UNBOUNDED", 1),
                        new ModelProfile.RepeatableTopology(
                                2,
                                histogram(reverse, "1", 2, "2", 1),
                                distribution(1, 2)),
                        new ModelProfile.RepeatableCapacities(
                                capacity(reverse, new long[] {10, 20}, "DECLARED", 1),
                                capacity(reverse, new long[] {10, 40}, "DECLARED", 1),
                                capacity(reverse, new long[] {1, 1, 10, 40}, null, 0))));

        var computations = new ModelProfile.ComputationProfile(
                program(false, reverse),
                new ModelProfile.ComputationShape(
                        histogram(reverse, "NUMBER", 1, "STRING", 1),
                        new ModelProfile.TableProfile(
                                distribution(1, 2),
                                distribution(0, 1),
                                histogram(reverse, "TR1", 1)),
                        histogram(reverse, "AGGREGATE", 1, "COPY", 1),
                        histogram(reverse, "MATCHED", 1, "MISMATCHED", 1)),
                new ModelProfile.DependencyProfile(2, 1));

        return new ModelProfile(
                structure,
                program(true, reverse),
                computations,
                new ModelProfile.WiringProfile(
                        histogram(reverse, "ANY", 2),
                        distribution(1, 2)));
    }

    private static ModelProfile.ProgramProfile program(
            boolean includesOpaque, boolean reverse) {
        long programCount = includesOpaque ? 3 : 2;
        var outcomes = includesOpaque
                ? histogram(reverse, "CONVERTED", 2, "OPAQUE", 1)
                : histogram(reverse, "CONVERTED", 2);
        var reasons = includesOpaque
                ? histogram(reverse, "CONVERSION_LIMIT", 1)
                : LongHistogram.empty();
        var conversion = new ModelProfile.ConversionProfile(outcomes, reasons);
        var syntax = new ModelProfile.SyntaxProfile(
                new ModelProfile.AstProfile(
                        distribution(3, 7),
                        distribution(2, 4),
                        distribution(1, 2),
                        distribution(1, 3),
                        distribution(1, 2)),
                new ModelProfile.DispatchProfile(
                        histogram(reverse, "FieldFilled", 2, "GreaterThan", 1),
                        histogram(reverse, "+", 1, "*", 1),
                        histogram(reverse, "AGGREGATE", 1, "FILTER", 1),
                        histogram(reverse, "AGGREGATE+FILTER", 1, "NONE", 1),
                        sortedHistograms(
                                reverse,
                                Map.entry(
                                        "MODEL_LEVEL",
                                        histogram(reverse, "AGGREGATE", 1)),
                                Map.entry("PER_GROUP", LongHistogram.empty()),
                                Map.entry(
                                        "PER_REPEATABLE_ROW",
                                        histogram(reverse, "FILTER", 1)))));
        var scope = new ModelProfile.ScopeProfile(
                histogram(
                        reverse,
                        "MODEL_LEVEL",
                        1,
                        "PER_REPEATABLE_ROW",
                        programCount - 1),
                capacity(reverse, sequence(programCount), null, 0));
        var scans = new ModelProfile.ScanProfile(
                histogram(reverse, "FLATTENED_FIELD", 1, "FLATTENED_GROUP", 1),
                distribution(1, 2),
                capacity(reverse, new long[] {20, 40}, null, 0),
                distribution(3));
        var mechanisms = sortedMechanisms(
                reverse,
                Map.entry(
                        "UNIQUENESS",
                        mechanism(reverse, distribution(2), 40)),
                Map.entry(
                        "GROUP_FILL",
                        mechanism(reverse, distribution(4), 20)),
                Map.entry(
                        "SEMANTIC_INDEX",
                        mechanism(reverse, LongDistribution.empty())),
                Map.entry(
                        "PARALLEL_REFERENCE",
                        mechanism(reverse, LongDistribution.empty())));
        var evaluation = new ModelProfile.EvaluationProfile(
                scans,
                new ModelProfile.WorkProfile(
                        capacity(reverse, new long[] {20, 80}, null, 0),
                        histogram(
                                reverse,
                                "MODEL_LEVEL|FLATTENED_FIELD",
                                1,
                                "PER_REPEATABLE_ROW|FLATTENED_GROUP",
                                1)),
                mechanisms,
                new ModelProfile.OuterCorrelationProfile(
                        histogram(reverse, "FIELD_VALUE", 1),
                        capacity(reverse, new long[] {40}, null, 0)));
        return new ModelProfile.ProgramProfile(
                conversion,
                syntax,
                new ModelProfile.RuntimeProfile(
                        scope,
                        evaluation,
                        new ModelProfile.ResolutionProfile(distribution(0, 2))));
    }

    private static ModelProfile.MechanismProfile mechanism(
            boolean reverse, LongDistribution shape, long... capacities) {
        return new ModelProfile.MechanismProfile(
                shape, capacity(reverse, capacities, null, 0));
    }

    private static long[] sequence(long count) {
        long[] values = new long[Math.toIntExact(count)];
        Arrays.fill(values, 1);
        return values;
    }

    private static ModelProfile.CapacityProfile capacity(
            boolean reverse, long[] bounded, String cause, long unbounded) {
        LongHistogram causes = cause == null
                ? LongHistogram.empty()
                : histogram(reverse, cause, unbounded);
        return new ModelProfile.CapacityProfile(distribution(bounded), causes);
    }

    private static LongDistribution distribution(long... values) {
        return LongDistribution.of(Arrays.stream(values).boxed().toList());
    }

    private static LongHistogram histogram(boolean reverse, Object... pairs) {
        var counts = new LinkedHashMap<String, Long>();
        if (reverse) {
            for (int index = pairs.length - 2; index >= 0; index -= 2) {
                counts.put((String) pairs[index], ((Number) pairs[index + 1]).longValue());
            }
        } else {
            for (int index = 0; index < pairs.length; index += 2) {
                counts.put((String) pairs[index], ((Number) pairs[index + 1]).longValue());
            }
        }
        return LongHistogram.of(counts);
    }

    @SafeVarargs
    private static <V> SortedMap<String, V> sorted(
            boolean reverse, Map.Entry<String, V>... entries) {
        var result = new TreeMap<String, V>(
                reverse ? Comparator.reverseOrder() : Comparator.naturalOrder());
        for (var entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @SafeVarargs
    private static SortedMap<String, LongHistogram> sortedHistograms(
            boolean reverse, Map.Entry<String, LongHistogram>... entries) {
        return sorted(reverse, entries);
    }

    @SafeVarargs
    private static SortedMap<String, ModelProfile.MechanismProfile> sortedMechanisms(
            boolean reverse, Map.Entry<String, ModelProfile.MechanismProfile>... entries) {
        return sorted(reverse, entries);
    }
}
