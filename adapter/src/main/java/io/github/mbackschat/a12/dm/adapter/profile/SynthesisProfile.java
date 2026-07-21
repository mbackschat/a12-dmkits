package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The donor synthesizer's input view over the one current profile format.
 *
 * <p>This is deliberately an internal projection, not a second profile format. It keeps the PR's proven
 * synthesis mechanism intact while adapting the current profile's reorganized metrics at one boundary.
 */
record SynthesisProfile(
        Structure structure,
        Rules rules,
        Computations computations) {

    static SynthesisProfile from(ModelProfile source) {
        requireBounded(source);
        return new SynthesisProfile(
                structure(source.structure()),
                rules(source.rules(), source.wiring()),
                computations(source.computations()));
    }

    private static void requireBounded(ModelProfile source) {
        var repeatability = source.structure().repeatability();
        if (repeatability.outcomes().counts().getOrDefault("UNBOUNDED", 0L) > 0) {
            throw unbounded("$.structure.repeatability.outcomes.UNBOUNDED");
        }
        requireBounded(
                repeatability.capacities().maxRepetitions(),
                "$.structure.repeatability.capacities.maxRepetitions");
        requireBounded(
                repeatability.capacities().groupChains(),
                "$.structure.repeatability.capacities.groupChains");
        requireBounded(
                repeatability.capacities().fieldChains(),
                "$.structure.repeatability.capacities.fieldChains");
        requireBounded(source.rules().runtime(), "$.rules.runtime");
        requireBounded(source.computations().programs().runtime(), "$.computations.programs.runtime");
    }

    private static void requireBounded(ModelProfile.RuntimeProfile runtime, String path) {
        requireBounded(runtime.scope().capacity(), path + ".scope.capacity");
        var evaluation = runtime.evaluation();
        requireBounded(evaluation.scans().capacity(), path + ".evaluation.scans.capacity");
        requireBounded(evaluation.combinedWork().capacity(), path + ".evaluation.combinedWork.capacity");
        evaluation.mechanisms().forEach((name, mechanism) -> requireBounded(
                mechanism.capacity(),
                path + ".evaluation.mechanisms." + name + ".capacity"));
        requireBounded(
                evaluation.outerCorrelation().capacity(),
                path + ".evaluation.outerCorrelation.capacity");
    }

    private static void requireBounded(ModelProfile.CapacityProfile capacity, String path) {
        if (capacity.unboundedCauses().total() > 0) {
            throw unbounded(path);
        }
    }

    private static IllegalArgumentException unbounded(String path) {
        return new IllegalArgumentException(
                "profile synthesis cannot represent unbounded capacity at " + path
                        + "; use a bounded calibration target");
    }

    record Structure(
            int fieldCount,
            int groupCount,
            int maxNestingDepth,
            Distribution fieldsPerGroup,
            Map<String, Integer> fieldKinds,
            Repeatability repeatability,
            Constraints constraints) {

        Structure {
            fieldKinds = immutableSortedMap(fieldKinds);
        }

        static SynthesisStructureBuilders.StructureBuilder builder() {
            return SynthesisStructureBuilders.structure();
        }
    }

    record Repeatability(
            int repeatableGroupCount,
            int maxRepeatableNestingDepth,
            Distribution maxRepetitions,
            Distribution nestingDepth,
            Distribution chainProduct,
            Distribution fieldChainProduct,
            Distribution siblingBranchFan,
            Map<String, Distribution> chainProductByDepth,
            Map<String, Integer> nestingDepthExact) {

        Repeatability {
            chainProductByDepth = immutableSortedMap(chainProductByDepth);
            nestingDepthExact = immutableSortedMap(nestingDepthExact);
        }

        static SynthesisStructureBuilders.RepeatabilityBuilder builder() {
            return SynthesisStructureBuilders.repeatability();
        }
    }

    record Iteration(
            Distribution starCapture,
            int parallelIteration,
            int outerCorrelation,
            Distribution scopeProduct,
            Distribution reachProduct,
            Distribution reachScopeProduct) {

        static SynthesisStructureBuilders.IterationBuilder builder() {
            return SynthesisStructureBuilders.iteration();
        }
    }

    record Constraints(
            int numbersWithBounds,
            int stringsWithLength,
            int stringsWithPattern,
            Distribution numberScale,
            Distribution enumCardinality) {

        static SynthesisStructureBuilders.ConstraintsBuilder builder() {
            return SynthesisStructureBuilders.constraints();
        }
    }

    record Rules(
            int count,
            int opaqueCount,
            Scope scope,
            Distribution nodeCount,
            Distribution depth,
            Distribution booleanArity,
            Distribution fanIn,
            Distribution rulesPerErrorField,
            Distribution walkUpDistance,
            Distribution repeatableLevelsCrossed,
            Map<String, Integer> operators,
            Map<String, Integer> constructs,
            Map<String, Integer> constructCombos,
            Map<String, Map<String, Integer>> constructsByScope,
            Iteration iteration) {

        Rules {
            operators = immutableSortedMap(operators);
            constructs = immutableSortedMap(constructs);
            constructCombos = immutableSortedMap(constructCombos);
            constructsByScope = immutableNestedSortedMap(constructsByScope);
        }

        static SynthesisProgramBuilders.RulesBuilder builder() {
            return SynthesisProgramBuilders.rules();
        }
    }

    record Scope(int modelLevel, int perGroup, int perRepeatableRow) {}

    record Computations(
            int count,
            int opaqueCount,
            Distribution nodeCount,
            Distribution depth,
            Distribution fanIn,
            int maxCascadeDepth,
            int feedingOthers,
            Map<String, Integer> computedFieldKinds,
            Distribution alternatives,
            Scope scope,
            int preconditionedCount,
            Map<String, Integer> operators,
            Map<String, Integer> constructs,
            Map<String, Integer> archetypes,
            Map<String, Integer> constructCombos,
            Map<String, Map<String, Integer>> constructsByScope,
            ScaleMatch aggregateScaleMatch,
            Iteration iteration,
            Distribution aggregateStars) {

        Computations {
            computedFieldKinds = immutableSortedMap(computedFieldKinds);
            operators = immutableSortedMap(operators);
            constructs = immutableSortedMap(constructs);
            archetypes = immutableSortedMap(archetypes);
            constructCombos = immutableSortedMap(constructCombos);
            constructsByScope = immutableNestedSortedMap(constructsByScope);
        }

        static SynthesisProgramBuilders.ComputationsBuilder builder() {
            return SynthesisProgramBuilders.computations();
        }
    }

    record ScaleMatch(int matched, int mismatched) {}

    private static <V> Map<String, V> immutableSortedMap(Map<String, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        var copy = new TreeMap<String, V>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "map key"),
                Objects.requireNonNull(value, "map value")));
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static <V> Map<String, Map<String, V>> immutableNestedSortedMap(
            Map<String, Map<String, V>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        var copy = new TreeMap<String, Map<String, V>>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "nested map key"),
                immutableSortedMap(value)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static Structure structure(ModelProfile.StructureProfile source) {
        var fields = source.fields();
        var repeatability = source.repeatability();
        var constrained = fields.constrainedFields().counts();
        return Structure.builder()
                .fieldCount(exactInt(source.elementCount("FIELD")))
                .groupCount(exactInt(source.elementCount("GROUP")))
                .maxNestingDepth(source.topology().maxGroupDepth())
                .fieldsPerGroup(distribution(source.topology().fieldsPerGroup()))
                .fieldKinds(counts(fields.kinds()))
                .repeatability(Repeatability.builder()
                        .repeatableGroupCount(exactInt(repeatability.outcomes().total()))
                        .maxRepeatableNestingDepth(repeatability.topology().maxDepth())
                        .maxRepetitions(distribution(repeatability.capacities().maxRepetitions().bounded()))
                        .nestingDepth(distribution(repeatability.topology().exactDepth()))
                        .chainProduct(distribution(repeatability.capacities().groupChains().bounded()))
                        .fieldChainProduct(distribution(repeatability.capacities().fieldChains().bounded()))
                        .siblingBranchFan(distribution(repeatability.topology().siblingFan()))
                        .chainProductByDepth(Map.of())
                        .nestingDepthExact(counts(repeatability.topology().exactDepth()))
                        .build())
                .constraints(Constraints.builder()
                        .numbersWithBounds(exactInt(constrained.getOrDefault("NUMBER_BOUNDS", 0L)))
                        .stringsWithLength(exactInt(constrained.getOrDefault("STRING_LENGTH", 0L)))
                        .stringsWithPattern(exactInt(constrained.getOrDefault("STRING_PATTERN", 0L)))
                        .numberScale(distribution(fields.numberScale()))
                        .enumCardinality(distribution(fields.enumCardinality()))
                        .build())
                .build();
    }

    private static Rules rules(
            ModelProfile.ProgramProfile source,
            ModelProfile.WiringProfile wiring) {
        var syntax = source.syntax();
        var runtime = source.runtime();
        return Rules.builder()
                .count(exactInt(source.conversion().total()))
                .opaqueCount(exactInt(source.conversion().outcomes().counts().getOrDefault("OPAQUE", 0L)))
                .scope(scope(runtime.scope()))
                .nodeCount(distribution(syntax.ast().nodeCount()))
                .depth(distribution(syntax.ast().depth()))
                .booleanArity(distribution(syntax.ast().booleanArity()))
                .fanIn(distribution(syntax.ast().distinctFanIn()))
                .rulesPerErrorField(distribution(wiring.fieldFanOut()))
                .walkUpDistance(distribution(runtime.resolution().walkUpDistance()))
                .repeatableLevelsCrossed(distribution(runtime.evaluation().scans().starDepth()))
                .operators(mergedCounts(
                        syntax.dispatch().catalogOperators(),
                        syntax.dispatch().intrinsicOperators()))
                .constructs(counts(syntax.dispatch().constructs()))
                .constructCombos(counts(syntax.dispatch().constructCombinations()))
                .constructsByScope(scopeCounts(syntax.dispatch().constructsByScope()))
                .iteration(iteration(runtime))
                .build();
    }

    private static Computations computations(ModelProfile.ComputationProfile source) {
        var programs = source.programs();
        var syntax = programs.syntax();
        var runtime = programs.runtime();
        var shape = source.shape();
        var scale = shape.aggregateScaleMatch().counts();
        return Computations.builder()
                .count(exactInt(programs.conversion().total()))
                .opaqueCount(exactInt(programs.conversion().outcomes().counts().getOrDefault("OPAQUE", 0L)))
                .nodeCount(distribution(syntax.ast().nodeCount()))
                .depth(distribution(syntax.ast().depth()))
                .fanIn(distribution(syntax.ast().distinctFanIn()))
                .maxCascadeDepth(source.dependencies().maxCascadeDepth())
                .feedingOthers(exactInt(source.dependencies().feedingComputations()))
                .computedFieldKinds(counts(shape.computedFieldKinds()))
                .alternatives(distribution(shape.tables().alternatives()))
                .scope(scope(runtime.scope()))
                .preconditionedCount(nonZeroSamples(shape.tables().preconditions()))
                .operators(mergedCounts(
                        syntax.dispatch().catalogOperators(),
                        syntax.dispatch().intrinsicOperators()))
                .constructs(counts(syntax.dispatch().constructs()))
                .archetypes(counts(shape.archetypes()))
                .constructCombos(counts(syntax.dispatch().constructCombinations()))
                .constructsByScope(scopeCounts(syntax.dispatch().constructsByScope()))
                .aggregateScaleMatch(new ScaleMatch(
                        exactInt(scale.getOrDefault("MATCHED", 0L)),
                        exactInt(scale.getOrDefault("MISMATCHED", 0L))))
                .iteration(iteration(runtime))
                .aggregateStars(distribution(runtime.evaluation().scans().starDepth()))
                .build();
    }

    private static Scope scope(ModelProfile.ScopeProfile source) {
        var classes = source.classes().counts();
        return new Scope(
                exactInt(classes.getOrDefault("MODEL_LEVEL", 0L)),
                exactInt(classes.getOrDefault("PER_GROUP", 0L)),
                exactInt(classes.getOrDefault("PER_REPEATABLE_ROW", 0L)));
    }

    private static Iteration iteration(ModelProfile.RuntimeProfile source) {
        var evaluation = source.evaluation();
        return Iteration.builder()
                .starCapture(distribution(evaluation.scans().capacity().bounded()))
                .parallelIteration(mechanismCount(evaluation, "PARALLEL_REFERENCE"))
                .outerCorrelation(exactInt(evaluation.outerCorrelation().forms().total()))
                .scopeProduct(distribution(source.scope().capacity().bounded()))
                .reachProduct(distribution(evaluation.combinedWork().capacity().bounded()))
                .reachScopeProduct(distribution(source.scope().capacity().bounded()))
                .build();
    }

    private static int mechanismCount(ModelProfile.EvaluationProfile source, String name) {
        var mechanism = source.mechanisms().get(name);
        return mechanism == null ? 0 : exactInt(mechanism.shape().count());
    }

    private static int nonZeroSamples(LongDistribution source) {
        long zeroes = source.buckets().stream()
                .filter(bucket -> bucket.lower() == 0 && bucket.upper() == 0)
                .mapToLong(LongDistribution.Bucket::count)
                .sum();
        return exactInt(source.count() - zeroes);
    }

    private static Map<String, Map<String, Integer>> scopeCounts(
            Map<String, LongHistogram> source) {
        var result = new LinkedHashMap<String, Map<String, Integer>>();
        result.put("modelLevel", counts(source.get("MODEL_LEVEL")));
        result.put("perGroup", counts(source.get("PER_GROUP")));
        result.put("perRepeatableRow", counts(source.get("PER_REPEATABLE_ROW")));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String, Integer> mergedCounts(LongHistogram first, LongHistogram second) {
        var merged = new TreeMap<String, Integer>();
        counts(first).forEach((key, value) -> merged.merge(key, value, Math::addExact));
        counts(second).forEach((key, value) -> merged.merge(key, value, Math::addExact));
        return java.util.Collections.unmodifiableMap(merged);
    }

    private static Map<String, Integer> counts(LongHistogram source) {
        if (source == null) {
            return Map.of();
        }
        var result = new TreeMap<String, Integer>();
        source.counts().forEach((key, value) -> result.put(key, exactInt(value)));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Distribution distribution(LongHistogram source) {
        if (source == null || source.total() == 0) {
            return Distribution.empty();
        }
        var values = new java.util.ArrayList<Integer>();
        source.counts().forEach((key, count) -> {
            int value = clamp(Long.parseLong(key));
            for (long index = 0; index < count; index++) {
                values.add(value);
            }
        });
        return Distribution.of(values);
    }

    private static Distribution distribution(LongDistribution source) {
        if (source == null || source.count() == 0) {
            return Distribution.empty();
        }
        var buckets = new LinkedHashMap<String, Integer>();
        double weighted = 0;
        for (var bucket : source.buckets()) {
            int lower = clamp(bucket.lower());
            int upper = clamp(bucket.upper());
            String label = lower == upper ? Integer.toString(lower) : lower + "-" + upper;
            buckets.merge(label, exactInt(bucket.count()), Math::addExact);
            weighted += ((double) lower + upper) / 2.0 * bucket.count();
        }
        return new Distribution(
                exactInt(source.count()),
                clamp(source.min()),
                clamp(source.max()),
                weighted / source.count(),
                clamp(source.median()),
                clamp(source.p90()),
                buckets);
    }

    private static int clamp(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(value);
    }

    private static int exactInt(long value) {
        return Math.toIntExact(value);
    }
}
