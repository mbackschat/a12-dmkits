package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Map;

/** Fluent construction paths for the rule and computation synthesis-profile records. */
final class SynthesisProgramBuilders {

    private SynthesisProgramBuilders() {}

    static RulesBuilder rules() {
        return new RulesBuilder();
    }

    static ComputationsBuilder computations() {
        return new ComputationsBuilder();
    }

    static final class RulesBuilder {
        private int count;
        private int opaqueCount;
        private SynthesisProfile.Scope scope = new SynthesisProfile.Scope(0, 0, 0);
        private Distribution nodeCount = Distribution.empty();
        private Distribution depth = Distribution.empty();
        private Distribution booleanArity = Distribution.empty();
        private Distribution fanIn = Distribution.empty();
        private Distribution rulesPerErrorField = Distribution.empty();
        private Distribution walkUpDistance = Distribution.empty();
        private Distribution repeatableLevelsCrossed = Distribution.empty();
        private Map<String, Integer> operators = Map.of();
        private Map<String, Integer> constructs = Map.of();
        private Map<String, Integer> constructCombos = Map.of();
        private Map<String, Map<String, Integer>> constructsByScope = Map.of();
        private SynthesisProfile.Iteration iteration = SynthesisProfile.Iteration.builder().build();

        RulesBuilder count(int value) {
            count = value;
            return this;
        }

        RulesBuilder opaqueCount(int value) {
            opaqueCount = value;
            return this;
        }

        RulesBuilder scope(SynthesisProfile.Scope value) {
            scope = presentOr(scope, value);
            return this;
        }

        RulesBuilder nodeCount(Distribution value) {
            nodeCount = presentOr(nodeCount, value);
            return this;
        }

        RulesBuilder depth(Distribution value) {
            depth = presentOr(depth, value);
            return this;
        }

        RulesBuilder booleanArity(Distribution value) {
            booleanArity = presentOr(booleanArity, value);
            return this;
        }

        RulesBuilder fanIn(Distribution value) {
            fanIn = presentOr(fanIn, value);
            return this;
        }

        RulesBuilder rulesPerErrorField(Distribution value) {
            rulesPerErrorField = presentOr(rulesPerErrorField, value);
            return this;
        }

        RulesBuilder walkUpDistance(Distribution value) {
            walkUpDistance = presentOr(walkUpDistance, value);
            return this;
        }

        RulesBuilder repeatableLevelsCrossed(Distribution value) {
            repeatableLevelsCrossed = presentOr(repeatableLevelsCrossed, value);
            return this;
        }

        RulesBuilder operators(Map<String, Integer> value) {
            operators = nonEmptyOr(operators, value);
            return this;
        }

        RulesBuilder constructs(Map<String, Integer> value) {
            constructs = nonEmptyOr(constructs, value);
            return this;
        }

        RulesBuilder constructCombos(Map<String, Integer> value) {
            constructCombos = nonEmptyOr(constructCombos, value);
            return this;
        }

        RulesBuilder constructsByScope(Map<String, Map<String, Integer>> value) {
            constructsByScope = nonEmptyOr(constructsByScope, value);
            return this;
        }

        RulesBuilder iteration(SynthesisProfile.Iteration value) {
            iteration = presentOr(iteration, value);
            return this;
        }

        SynthesisProfile.Rules build() {
            return new SynthesisProfile.Rules(
                    count,
                    opaqueCount,
                    scope,
                    nodeCount,
                    depth,
                    booleanArity,
                    fanIn,
                    rulesPerErrorField,
                    walkUpDistance,
                    repeatableLevelsCrossed,
                    operators,
                    constructs,
                    constructCombos,
                    constructsByScope,
                    iteration);
        }
    }

    static final class ComputationsBuilder {
        private int count;
        private int opaqueCount;
        private Distribution nodeCount = Distribution.empty();
        private Distribution depth = Distribution.empty();
        private Distribution fanIn = Distribution.empty();
        private int maxCascadeDepth;
        private int feedingOthers;
        private Map<String, Integer> computedFieldKinds = Map.of();
        private Distribution alternatives = Distribution.empty();
        private SynthesisProfile.Scope scope = new SynthesisProfile.Scope(0, 0, 0);
        private int preconditionedCount;
        private Map<String, Integer> operators = Map.of();
        private Map<String, Integer> constructs = Map.of();
        private Map<String, Integer> archetypes = Map.of();
        private Map<String, Integer> constructCombos = Map.of();
        private Map<String, Map<String, Integer>> constructsByScope = Map.of();
        private SynthesisProfile.ScaleMatch aggregateScaleMatch = new SynthesisProfile.ScaleMatch(0, 0);
        private SynthesisProfile.Iteration iteration = SynthesisProfile.Iteration.builder().build();
        private Distribution aggregateStars = Distribution.empty();

        ComputationsBuilder count(int value) {
            count = value;
            return this;
        }

        ComputationsBuilder opaqueCount(int value) {
            opaqueCount = value;
            return this;
        }

        ComputationsBuilder nodeCount(Distribution value) {
            nodeCount = presentOr(nodeCount, value);
            return this;
        }

        ComputationsBuilder depth(Distribution value) {
            depth = presentOr(depth, value);
            return this;
        }

        ComputationsBuilder fanIn(Distribution value) {
            fanIn = presentOr(fanIn, value);
            return this;
        }

        ComputationsBuilder maxCascadeDepth(int value) {
            maxCascadeDepth = value;
            return this;
        }

        ComputationsBuilder feedingOthers(int value) {
            feedingOthers = value;
            return this;
        }

        ComputationsBuilder computedFieldKinds(Map<String, Integer> value) {
            computedFieldKinds = nonEmptyOr(computedFieldKinds, value);
            return this;
        }

        ComputationsBuilder alternatives(Distribution value) {
            alternatives = presentOr(alternatives, value);
            return this;
        }

        ComputationsBuilder scope(SynthesisProfile.Scope value) {
            scope = presentOr(scope, value);
            return this;
        }

        ComputationsBuilder preconditionedCount(int value) {
            preconditionedCount = value;
            return this;
        }

        ComputationsBuilder operators(Map<String, Integer> value) {
            operators = nonEmptyOr(operators, value);
            return this;
        }

        ComputationsBuilder constructs(Map<String, Integer> value) {
            constructs = nonEmptyOr(constructs, value);
            return this;
        }

        ComputationsBuilder archetypes(Map<String, Integer> value) {
            archetypes = nonEmptyOr(archetypes, value);
            return this;
        }

        ComputationsBuilder constructCombos(Map<String, Integer> value) {
            constructCombos = nonEmptyOr(constructCombos, value);
            return this;
        }

        ComputationsBuilder constructsByScope(Map<String, Map<String, Integer>> value) {
            constructsByScope = nonEmptyOr(constructsByScope, value);
            return this;
        }

        ComputationsBuilder aggregateScaleMatch(SynthesisProfile.ScaleMatch value) {
            aggregateScaleMatch = presentOr(aggregateScaleMatch, value);
            return this;
        }

        ComputationsBuilder iteration(SynthesisProfile.Iteration value) {
            iteration = presentOr(iteration, value);
            return this;
        }

        ComputationsBuilder aggregateStars(Distribution value) {
            aggregateStars = presentOr(aggregateStars, value);
            return this;
        }

        SynthesisProfile.Computations build() {
            return new SynthesisProfile.Computations(
                    count,
                    opaqueCount,
                    nodeCount,
                    depth,
                    fanIn,
                    maxCascadeDepth,
                    feedingOthers,
                    computedFieldKinds,
                    alternatives,
                    scope,
                    preconditionedCount,
                    operators,
                    constructs,
                    archetypes,
                    constructCombos,
                    constructsByScope,
                    aggregateScaleMatch,
                    iteration,
                    aggregateStars);
        }
    }

    private static <T> T presentOr(T current, T candidate) {
        return candidate == null ? current : candidate;
    }

    private static <K, V> Map<K, V> nonEmptyOr(Map<K, V> current, Map<K, V> candidate) {
        return candidate == null || candidate.isEmpty() ? current : candidate;
    }
}
