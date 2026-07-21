package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.List;
import java.util.Map;

/**
 * A standalone {@code main} run by {@link ModelSynthesizerCrossJvmDeterminismTest} in a FRESH child JVM — the
 * only way to actually observe cross-invocation nondeterminism (a per-process JVM quirk like {@code
 * Map.copyOf}'s launch-randomized iteration order looks perfectly stable inside a single JUnit process, since
 * the randomization is fixed for the whole process). Builds a fixed, multi-bucket profile (several distinct
 * {@code chainProductByDepth}/{@code maxRepetitions} buckets, so a bucket-order-dependent draw would actually
 * show up) and prints {@link ModelSynthesizer#synthesize}'s output to stdout for the parent to diff.
 */
final class ModelSynthesizerDeterminismMain {

    private ModelSynthesizerDeterminismMain() {
    }

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        System.out.print(ModelSynthesizer.synthesize(profile(), seed));
    }

    /** A small but multi-bucket profile — several repeatable-nesting classes and a varied maxRepetitions
     *  multiset, so {@code sampleMultiset}'s bucket traversal actually has more than one bucket to order. */
    static SynthesisProfile profile() {
        Map<String, Map<String, Integer>> emptyScopeBuckets =
                Map.of("modelLevel", Map.of(), "perGroup", Map.of(), "perRepeatableRow", Map.of());

        SynthesisProfile.Repeatability rep = SynthesisProfile.Repeatability.builder()
                .repeatableGroupCount(8)
                .maxRepeatableNestingDepth(4)
                .maxRepetitions(Distribution.of(List.of(5, 20, 60, 150, 5, 20, 60, 150)))
                .nestingDepth(Distribution.of(List.of(1, 1, 2, 2, 3, 3, 4, 4)))
                .chainProduct(Distribution.of(List.of(5, 100, 1200, 22000, 5, 100, 1200, 22000)))
                .fieldChainProduct(Distribution.of(List.of(5, 100, 1200, 22000, 5, 100, 1200, 22000)))
                .siblingBranchFan(Distribution.of(List.of(1, 1, 2, 2, 3)))
                .chainProductByDepth(Map.of("1", Distribution.of(List.of(5, 5)),
                        "2-3", Distribution.of(List.of(100, 1200, 100, 1200)),
                        "4+", Distribution.of(List.of(22000, 22000))))
                .nestingDepthExact(Map.of("1", 2, "2", 2, "3", 2, "4", 2))
                .build();

        var constraints = SynthesisProfile.Constraints.builder()
                .numberScale(Distribution.of(List.of(0)))
                .build();
        SynthesisProfile.Structure structure = SynthesisProfile.Structure.builder()
                .fieldCount(120)
                .groupCount(30)
                .maxNestingDepth(5)
                .fieldsPerGroup(Distribution.of(List.of(2, 3, 4, 2, 3, 4)))
                .fieldKinds(Map.of("NUMBER", 120))
                .repeatability(rep)
                .constraints(constraints)
                .build();

        SynthesisProfile.Iteration iteration = SynthesisProfile.Iteration.builder()
                .listScanCapacity(Distribution.of(List.of(1, 5, 30, 900, 1, 5)))
                .parallelIteration(1)
                .scopeCapacity(Distribution.of(List.of(1, 5, 30, 1, 5)))
                .workCapacity(Distribution.of(List.of(1, 1, 2)))
                .scannedScopeCapacity(Distribution.of(List.of(5, 30)))
                .build();
        SynthesisProfile.Scope scope = new SynthesisProfile.Scope(10, 5, 15);

        SynthesisProfile.Rules rules = SynthesisProfile.Rules.builder()
                .count(30)
                .scope(scope)
                .nodeCount(Distribution.of(List.of(1, 2, 3, 4)))
                .depth(Distribution.of(List.of(1, 1, 2, 3)))
                .booleanArity(Distribution.of(List.of(1, 1, 2)))
                .fanIn(Distribution.of(List.of(1, 1)))
                .rulesPerErrorField(Distribution.of(List.of(1, 1)))
                .operators(Map.of("GreaterThan", 20, "Equal", 10))
                .constructs(Map.of("NONE", 25, "AGGREGATE", 5))
                .constructCombos(Map.of("NONE", 30))
                .constructsByScope(emptyScopeBuckets)
                .iteration(iteration)
                .build();

        SynthesisProfile.Computations computations = SynthesisProfile.Computations.builder()
                .count(15)
                .nodeCount(Distribution.of(List.of(2, 3, 4)))
                .depth(Distribution.of(List.of(1, 1, 2)))
                .fanIn(Distribution.of(List.of(1, 2)))
                .maxCascadeDepth(6)
                .feedingOthers(3)
                .computedFieldKinds(Map.of("NUMBER", 15))
                .alternatives(Distribution.of(List.of(1, 1, 2)))
                .scope(new SynthesisProfile.Scope(5, 5, 5))
                .preconditionedCount(8)
                .operators(Map.of("Sum", 10, "Copy", 5))
                .constructs(Map.of("NONE", 10, "AGGREGATE", 5))
                .archetypes(Map.of("COPY", 10, "ARITHMETIC", 5))
                .constructCombos(Map.of("NONE", 15))
                .constructsByScope(emptyScopeBuckets)
                .aggregateScaleMatch(new SynthesisProfile.ScaleMatch(10, 5))
                .iteration(iteration)
                .build();

        SynthesisProfile.Wiring wiring = new SynthesisProfile.Wiring(60, 30, 15, Distribution.of(List.of(1, 1, 2)));
        return new SynthesisProfile(structure, rules, computations, wiring);
    }
}
