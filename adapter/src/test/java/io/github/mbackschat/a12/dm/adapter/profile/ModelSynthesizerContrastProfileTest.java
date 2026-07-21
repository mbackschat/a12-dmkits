package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * The overfit tripwire ("the contrast-profile gate"): three hand-built
 * {@link SynthesisProfile} fixtures deliberately shaped UNLIKE the licensed target this synthesizer was tuned
 * against — flat-and-wide (no nesting, no giants), deep-and-narrow (one linear chain, no branching), and
 * rule-heavy/computation-light. Each is round-tripped through {@link ModelSynthesizer} and checked ONLY on
 * GENERIC structural axes (counts, nesting shape, chain-product bucket) — never a value specific to the
 * licensed target. A synthesizer that silently baked in an assumption from that one target (e.g. "giant roots
 * always exist" or "branching is the norm") fails one of these loudly instead of only showing up as a
 * numeric miss against a profile nobody else can reproduce.
 *
 * <p>These are directly-constructed {@link SynthesisProfile} records, not real kernel models profiled through
 * {@link SynthesisProfile#of}: the synthesizer's whole contract is "profile in, model out", so a hand-built
 * profile is exactly as valid an input as one derived from a real model, and building the record directly
 * (typed, {@link Distribution#of} computing every axis' stats from a raw list) is far less error-prone than
 * hand-authoring an equivalent JSON blob.
 */
class ModelSynthesizerContrastProfileTest {

    private static final Map<String, Map<String, Integer>> EMPTY_SCOPE_BUCKETS =
            Map.of("modelLevel", Map.of(), "perGroup", Map.of(), "perRepeatableRow", Map.of());

    @Test
    void flatAndWideProfileRoundTripsWithoutIntroducingNestingOrGiants() {
        // 40 repeatable groups, ALL at nesting depth 1 (no nesting at all), uniform moderate size — no
        // "1"-class bimodality, no "2-3"/"4+" classes at all. The opposite of the licensed target's bimodal
        // depth-1 shape (a few giant roots, many small) and its dominant nested ("2-3") mass.
        SynthesisProfile target = minimalProfile(flatAndWideRepeatability(), 200, 81, 2, 20, 5);
        for (long seed = 1; seed <= 3; seed++) {
            String json = ModelSynthesizer.synthesize(target, seed);
            assertDoesNotThrow(() -> BoundModel.ofJson(json), "seed " + seed + ": must stay kernel-valid");
            SynthesisProfile synth = SynthesisProfile.of(json);

            Distribution nesting = synth.structure().repeatability().nestingDepth();
            assertEquals(40, synth.structure().repeatability().repeatableGroupCount(),
                    "seed " + seed + ": repeatable count is reproduced exactly regardless of shape");
            assertEquals(nesting.n(), nesting.buckets().getOrDefault("1", 0),
                    "seed " + seed + ": a flat (all-depth-1) target must not gain ANY nested repeatable — "
                            + "every sample stays in the depth-1 bucket: " + nesting.buckets());
            assertTrue(synth.structure().repeatability().chainProduct().max() < 200,
                    "seed " + seed + ": with no nesting, chainProduct never exceeds a single group's own size: "
                            + synth.structure().repeatability().chainProduct().max());
        }
    }

    @Test
    void deepAndNarrowProfileRoundTripsWithoutIntroducingBranching() {
        // ONE linear chain reaching depth 4 (A -> B -> C -> D), no sibling branches anywhere. The opposite of
        // the licensed target's dominant branching shape (few roots, each fanning into several children).
        SynthesisProfile.Repeatability rep = SynthesisProfile.Repeatability.builder()
                .repeatableGroupCount(4)
                .maxRepeatableNestingDepth(4)
                .maxRepetitions(Distribution.of(List.of(50, 30, 20, 10)))
                .nestingDepth(Distribution.of(List.of(1, 2, 3, 4)))
                .chainProduct(Distribution.of(List.of(50, 1500, 30000, 300000)))
                .fieldChainProduct(Distribution.of(List.of(50, 1500, 30000, 300000)))
                .siblingBranchFan(Distribution.of(List.of(1, 1, 1, 1)))
                .chainProductByDepth(Map.of("1", Distribution.of(List.of(50)),
                        "2-3", Distribution.of(List.of(1500, 30000)),
                        "4+", Distribution.of(List.of(300000))))
                .nestingDepthExact(Map.of("1", 1, "2", 1, "3", 1, "4", 1))
                .build();

        // groupCount must leave enough geometry for a full depth-4 chain (root + a hub level + 4 repeatable
        // levels >= 6 groups) — a too-tight budget truncates the chain, which is a fixture-sizing artifact,
        // not a synthesizer fact (the same "tiny fixture needs room" lesson as buildTree's other tests).
        SynthesisProfile target = minimalProfile(rep, 20, 12, 6, 10, 3);
        for (long seed = 1; seed <= 3; seed++) {
            String json = ModelSynthesizer.synthesize(target, seed);
            assertDoesNotThrow(() -> BoundModel.ofJson(json), "seed " + seed + ": must stay kernel-valid");
            SynthesisProfile synth = SynthesisProfile.of(json);

            assertEquals(4, synth.structure().repeatability().repeatableGroupCount(),
                    "seed " + seed + ": repeatable count is reproduced exactly regardless of shape");
            assertEquals(4, synth.structure().repeatability().maxRepeatableNestingDepth(),
                    "seed " + seed + ": the full chain depth must be reached, not truncated");
            // a linear chain never puts >1 sample in the SAME depth-1 slot beyond the one root — no fan-out
            assertEquals(1, synth.structure().repeatability().nestingDepth().buckets().getOrDefault("1", 0),
                    "seed " + seed + ": a purely linear target has exactly ONE root, no fanned siblings");
        }
    }

    @Test
    void depthCappedMidBucketProfileReachesTheDeclaredMaxRepeatableNestingDepth() {
        // maxRepeatableNestingDepth (3) falls INSIDE the "2-3" bucket's range rather than AT a bucket's lower
        // bound — unlike every other contrast fixture here, where the max happens to coincide with a bucket
        // boundary by construction (4, 4+'s lower bound). This is a breadth-shaped profile shape that exposed
        // a depthSplit bug: a bucket-LOWER-BOUND test ("2-3"'s lower bound is 2, never >= 3) classified NO rep as
        // "deep", so the only code path that nests a repeatable beyond depth 2 (the linear deep chain) never
        // ran — the synth capped at maxRepeatableNestingDepth 2 regardless of seed.
        SynthesisProfile.Repeatability rep = SynthesisProfile.Repeatability.builder()
                .repeatableGroupCount(7)
                .maxRepeatableNestingDepth(3)
                .maxRepetitions(Distribution.of(List.of(10, 20, 15, 25, 30, 12, 18)))
                .nestingDepth(Distribution.of(List.of(1, 1, 2, 2, 2, 3, 3)))
                .chainProduct(Distribution.of(List.of(10, 20, 15, 25, 30, 12, 18)))
                .fieldChainProduct(Distribution.of(List.of(10, 20, 15, 25, 30, 12, 18)))
                .siblingBranchFan(Distribution.of(List.of(1, 1)))
                .nestingDepthExact(Map.of("1", 2, "2", 3, "3", 2))
                .build();

        SynthesisProfile target = minimalProfile(rep, 60, 30, 6, 10, 3);
        for (long seed = 1; seed <= 3; seed++) {
            String json = ModelSynthesizer.synthesize(target, seed);
            assertDoesNotThrow(() -> BoundModel.ofJson(json), "seed " + seed + ": must stay kernel-valid");
            SynthesisProfile synth = SynthesisProfile.of(json);

            assertEquals(3, synth.structure().repeatability().maxRepeatableNestingDepth(),
                    "seed " + seed + ": a mid-bucket max (3, inside the \"2-3\" bucket) must still be reached "
                            + "— depthSplit must not require the max to align with a bucket's lower bound");
        }
    }

    @Test
    void wideRootFieldlessProfileDoesNotStarveComputationsOfAFieldOperand() {
        // 29 leaf branches, ALL direct children of a root that ends up with ZERO fields of its own (no
        // repeatable group exists anywhere, so distributeFields' baseline-per-LEAF rule gives the whole field
        // budget to the leaves, none to the non-leaf root) — a breadth-shaped profile shape that exposed a
        // reachability bug: reserving most leaves' own (single) number field as computed leaves those leaves with no local
        // number, no reachable aggregate (no repeatable exists at all here), and — under the old SINGLE
        // global "keepNonComputed" pick — no reachable ANCESTOR number either, since the one kept field
        // lives in a DIFFERENT, sibling branch that a plain ancestor walk-up never visits. That produced a
        // bare literal operation with no field reference at all on the real target (MVK_NO_CONST).
        SynthesisProfile.Repeatability rep = SynthesisProfile.Repeatability.builder().build();

        SynthesisProfile target = minimalProfile(rep, 29, 30, 2, 0, 25);
        for (long seed = 1; seed <= 8; seed++) {
            long s = seed;
            assertDoesNotThrow(() -> ModelSynthesizer.synthesize(target, s),
                    "seed " + seed + ": every computation must keep a reachable field operand even when its "
                            + "own branch's only number got reserved as computed and the root carries no fields");
        }
    }

    @Test
    void exactNestingDepthSplitProducesRealBranchingInsteadOfOneGiantHub() {
        // A cheap direction check on a breadth-shaped profile: a hand-picked nestingDepth shape with
        // {n:300, mean:1.767, buckets:{"1":100, "2-3":200}} — a dominant middle bucket with a small deep
        // tail, depth 3 declared as the max. The bucket count PLUS the mean algebraically pin the EXACT
        // split: 100*1 + d2*2 + d3*3 = mean*n, d2+d3=200 solves to d2=170, d3=30 — only ~15% of the
        // ambiguous "2-3" bucket is truly depth-3, not the ~66% an older bucket-LABEL match assumed.
        // Hardwiring that exact split directly — before the nestingDepthExact profile metric even existed —
        // validates the depthSplit fix's DIRECTION: routing only the true ~30 deep reps through the linear
        // chain (not all 200) should leave the other ~170 for the branching step, producing a REAL
        // population of multi-way hubs instead of the single-fan outlier the ambiguous-bucket version
        // produced (a siblingBranchFan finding: an early synthesizer defect collapsed a genuinely
        // multi-way-fanned population down to a single dominant hub).
        List<Integer> rawDepths = new ArrayList<>();
        rawDepths.addAll(Collections.nCopies(100, 1));
        rawDepths.addAll(Collections.nCopies(170, 2));
        rawDepths.addAll(Collections.nCopies(30, 3));
        Map<String, Integer> exactSplit = Map.of("1", 100, "2", 170, "3", 30);

        List<Integer> repSizes = Collections.nCopies(300, 12);           // a uniform mid-size rep list
        List<Integer> fans = new ArrayList<>();
        fans.addAll(Collections.nCopies(150, 1));
        fans.addAll(Collections.nCopies(60, 2));
        fans.addAll(Collections.nCopies(25, 4));                          // a multi-way population

        SynthesisProfile.Repeatability rep = SynthesisProfile.Repeatability.builder()
                .repeatableGroupCount(300)
                .maxRepeatableNestingDepth(3)
                .maxRepetitions(Distribution.of(repSizes))
                .nestingDepth(Distribution.of(rawDepths))
                .chainProduct(Distribution.of(repSizes))
                .fieldChainProduct(Distribution.of(repSizes))
                .siblingBranchFan(Distribution.of(fans))
                .nestingDepthExact(exactSplit)
                .build();

        SynthesisProfile target = minimalProfile(rep, 2400, 1000, 6, 10, 10);
        int bestMultiWayHubs = 0;
        for (long seed = 1; seed <= 3; seed++) {
            String json = ModelSynthesizer.synthesize(target, seed);
            assertDoesNotThrow(() -> BoundModel.ofJson(json), "seed " + seed + ": must stay kernel-valid");
            SynthesisProfile synth = SynthesisProfile.of(json);
            Distribution sbf = synth.structure().repeatability().siblingBranchFan();
            bestMultiWayHubs = Math.max(bestMultiWayHubs, sbf.n() - sbf.buckets().getOrDefault("1", 0));
        }
        assertTrue(bestMultiWayHubs >= 10,
                "the exact depth split must leave enough reps for the branching step to build a REAL "
                        + "multi-way-hub population, not collapse to a single giant outlier: " + bestMultiWayHubs);
    }

    @Test
    void ruleHeavyComputationLightProfileRoundTripsWithoutInflatingComputations() {
        // 60 rules, 1 computation — the opposite of a computation-dominated model.
        SynthesisProfile.Repeatability rep = nestedRepeatability();

        SynthesisProfile target = minimalProfile(rep, 120, 41, 3, 60, 1);
        for (long seed = 1; seed <= 3; seed++) {
            String json = ModelSynthesizer.synthesize(target, seed);
            assertDoesNotThrow(() -> BoundModel.ofJson(json), "seed " + seed + ": must stay kernel-valid");
            SynthesisProfile synth = SynthesisProfile.of(json);

            assertEquals(60, synth.rules().count(), "seed " + seed + ": rule count is reproduced exactly");
            assertEquals(1, synth.computations().count(),
                    "seed " + seed + ": a computation-light target must not gain extra computations");
        }
    }

    /** The flat-and-wide shape (40 repeatable groups, ALL direct children of the root), reused from
     *  {@link #flatAndWideProfileRoundTripsWithoutIntroducingNestingOrGiants()} — the root is a ready-made
     *  PARENT for every one of them, so a UNIQUENESS slot's {@link ModelSynthesizer} lookup always finds
     *  a repeatable child to key into. */
    private static SynthesisProfile.Repeatability flatAndWideRepeatability() {
        List<Integer> uniformSizes = java.util.Collections.nCopies(40, 60);
        return SynthesisProfile.Repeatability.builder()
                .repeatableGroupCount(40)
                .maxRepeatableNestingDepth(1)
                .maxRepetitions(Distribution.of(uniformSizes))
                .nestingDepth(Distribution.of(java.util.Collections.nCopies(40, 1)))
                .chainProduct(Distribution.of(uniformSizes))
                .fieldChainProduct(Distribution.of(uniformSizes))
                .chainProductByDepth(Map.of("1", Distribution.of(uniformSizes)))
                .nestingDepthExact(Map.of("1", 40))
                .build();
    }

    private static SynthesisProfile.Repeatability nestedRepeatability() {
        return SynthesisProfile.Repeatability.builder()
                .repeatableGroupCount(6)
                .maxRepeatableNestingDepth(2)
                .maxRepetitions(Distribution.of(List.of(20, 20, 20, 15, 15, 15)))
                .nestingDepth(Distribution.of(List.of(1, 1, 1, 2, 2, 2)))
                .chainProduct(Distribution.of(List.of(20, 20, 20, 300, 300, 300)))
                .fieldChainProduct(Distribution.of(List.of(20, 20, 20, 300, 300, 300)))
                .siblingBranchFan(Distribution.of(List.of(1, 1, 1)))
                .chainProductByDepth(Map.of(
                        "1", Distribution.of(List.of(20, 20, 20)),
                        "2-3", Distribution.of(List.of(300, 300, 300))))
                .nestingDepthExact(Map.of("1", 3, "2", 3))
                .build();
    }

    @Test
    void uniquenessConstructUsesTheRuleGroupNotErrorFieldGroupSlotShape() {
        // A RepetitionNotUnique rule sits at the PARENT of the
        // repeated group it keys into (the error field lives INSIDE that child, never the rule's own
        // group) — the flat-and-wide shape's root is a guaranteed PARENT of every one of its 40 repeatable
        // groups, so the "modelLevel" bucket (the default minimalProfile Scope already budgets rules
        // there) always has a repeatable child to key into.
        SynthesisProfile target = minimalProfile(flatAndWideRepeatability(), 200, 81, 2, 20, 5,
                Map.of("UNIQUENESS", 4),
                Map.of("modelLevel", Map.of("UNIQUENESS", 4), "perGroup", Map.of(), "perRepeatableRow", Map.of()));
        for (long seed = 1; seed <= 3; seed++) {
            String json = ModelSynthesizer.synthesize(target, seed);
            assertDoesNotThrow(() -> BoundModel.ofJson(json), "seed " + seed + ": must stay kernel-valid");
            SynthesisProfile synth = SynthesisProfile.of(json);
            assertEquals(4, synth.rules().constructs().getOrDefault("UNIQUENESS", 0),
                    "seed " + seed + ": UNIQUENESS is reproduced exactly via the parent/child slot shape");
        }
    }

    @Test
    void zeroUniquenessBudgetNeverIntroducesTheConstruct() {
        // the zero-budget degrade contract: a target with NO UNIQUENESS in its histogram must synthesize
        // with none — the committable analog of a licensed-target check, same shape as the fixture above
        // minus the ask.
        SynthesisProfile target = minimalProfile(flatAndWideRepeatability(), 200, 81, 2, 20, 5);
        for (long seed = 1; seed <= 3; seed++) {
            String json = ModelSynthesizer.synthesize(target, seed);
            SynthesisProfile synth = SynthesisProfile.of(json);
            assertEquals(0, synth.rules().constructs().getOrDefault("UNIQUENESS", 0),
                    "seed " + seed + ": no UNIQUENESS in the target histogram must never introduce it");
        }
    }

    @Test
    void nonTrivialScopeCapacityTargetStaysKernelValidAndHoldsEveryExactAxis() {
        // Supply-side scope planning: a target whose
        // computations.iteration.scopeCapacity median is genuinely non-trivial (own-domain, no licensed
        // data) exercises steerNumberKindByScopeCapacity's actual swap path (the trivial-median degrade
        // guard, median<=1, is already exercised by every OTHER test in this file, whose default iteration
        // stays at median 1) — the nested rule-heavy shape gives it real depth to place into.
        SynthesisProfile.Repeatability rep = nestedRepeatability();

        SynthesisProfile target = minimalProfile(rep, 200, 60, 3, 20, 30, Map.of("NONE", 20), EMPTY_SCOPE_BUCKETS,
                Distribution.of(List.of(1, 4, 16)));
        for (long seed = 1; seed <= 3; seed++) {
            String json = ModelSynthesizer.synthesize(target, seed);
            assertDoesNotThrow(() -> BoundModel.ofJson(json), "seed " + seed + ": must stay kernel-valid");
            SynthesisProfile synth = SynthesisProfile.of(json);
            assertEquals(30, synth.computations().count(),
                    "seed " + seed + ": computation count is unaffected by the scope-steering pass");
            assertEquals(6, synth.structure().repeatability().repeatableGroupCount(),
                    "seed " + seed + ": repeatable count is unaffected by the scope-steering pass");
        }
    }

    /** A minimal-but-complete profile varying only {@code rep}/counts — every other axis is a small, valid,
     *  representative default (never a value specific to the licensed target this gate exists to guard against). */
    private static SynthesisProfile minimalProfile(SynthesisProfile.Repeatability rep, int fieldCount, int groupCount,
            int maxNestingDepth, int ruleCount, int compCount) {
        return minimalProfile(rep, fieldCount, groupCount, maxNestingDepth, ruleCount, compCount,
                Map.of("NONE", ruleCount), EMPTY_SCOPE_BUCKETS);
    }

    /** As above, but with an explicit rule {@code constructs}/{@code constructsByScope} histogram — the hook a
     *  UNIQUENESS-requesting (or explicitly zero-budget) contrast fixture needs. */
    private static SynthesisProfile minimalProfile(SynthesisProfile.Repeatability rep, int fieldCount, int groupCount,
            int maxNestingDepth, int ruleCount, int compCount, Map<String, Integer> ruleConstructs,
            Map<String, Map<String, Integer>> ruleConstructsByScope) {
        return minimalProfile(rep, fieldCount, groupCount, maxNestingDepth, ruleCount, compCount, ruleConstructs,
                ruleConstructsByScope, Distribution.of(List.of(1, 1, 2)));
    }

    /** As above, but with an explicit COMPUTATIONS {@code iteration.scopeCapacity} — the hook a
     *  non-trivial scopeCapacity fixture needs, independent of rules' own
     *  (unchanged, trivial-median) iteration. */
    private static SynthesisProfile minimalProfile(SynthesisProfile.Repeatability rep, int fieldCount, int groupCount,
            int maxNestingDepth, int ruleCount, int compCount, Map<String, Integer> ruleConstructs,
            Map<String, Map<String, Integer>> ruleConstructsByScope, Distribution compScopeCapacity) {
        var constraints = SynthesisProfile.Constraints.builder()
                .numberScale(Distribution.of(List.of(0)))
                .build();
        SynthesisProfile.Structure structure = SynthesisProfile.Structure.builder()
                .fieldCount(fieldCount)
                .groupCount(groupCount)
                .maxNestingDepth(maxNestingDepth)
                .fieldsPerGroup(Distribution.of(List.of(2, 3, 2, 3)))
                .fieldKinds(Map.of("NUMBER", fieldCount))
                .repeatability(rep)
                .constraints(constraints)
                .build();

        SynthesisProfile.Iteration ruleIteration = SynthesisProfile.Iteration.builder()
                .listScanCapacity(Distribution.of(List.of(1, 1, 2)))
                .scopeCapacity(Distribution.of(List.of(1, 1, 2)))
                .workCapacity(Distribution.of(List.of(1, 1, 1)))
                .scannedScopeCapacity(Distribution.of(List.of(1)))
                .build();
        SynthesisProfile.Iteration compIteration = SynthesisProfile.Iteration.builder()
                .listScanCapacity(Distribution.of(List.of(1, 1, 2)))
                .scopeCapacity(compScopeCapacity)
                .workCapacity(Distribution.of(List.of(1, 1, 1)))
                .scannedScopeCapacity(Distribution.of(List.of(1)))
                .build();
        SynthesisProfile.Scope scope = new SynthesisProfile.Scope(ruleCount / 2, 0, ruleCount - ruleCount / 2);

        SynthesisProfile.Rules rules = SynthesisProfile.Rules.builder()
                .count(ruleCount)
                .scope(scope)
                .nodeCount(Distribution.of(List.of(3, 3, 4)))
                .depth(Distribution.of(List.of(1, 1, 2)))
                .booleanArity(Distribution.of(List.of(1, 1, 2)))
                .fanIn(Distribution.of(List.of(1, 1)))
                .rulesPerErrorField(Distribution.of(List.of(1, 1)))
                .operators(Map.of("GreaterThan", ruleCount))
                .constructs(ruleConstructs)
                .constructCombos(Map.of("NONE", ruleCount))
                .constructsByScope(ruleConstructsByScope)
                .iteration(ruleIteration)
                .build();

        SynthesisProfile.Computations computations = SynthesisProfile.Computations.builder()
                .count(compCount)
                .nodeCount(Distribution.of(List.of(2, 2)))
                .depth(Distribution.of(List.of(1, 1)))
                .fanIn(Distribution.of(List.of(1, 1)))
                .maxCascadeDepth(1)
                .computedFieldKinds(Map.of("NUMBER", compCount))
                .alternatives(Distribution.of(List.of(1, 1)))
                .scope(new SynthesisProfile.Scope(compCount, 0, 0))
                .operators(Map.of("Sum", compCount))
                .constructs(Map.of("NONE", compCount))
                .archetypes(Map.of("COPY", compCount))
                .constructCombos(Map.of("NONE", compCount))
                .constructsByScope(EMPTY_SCOPE_BUCKETS)
                .aggregateScaleMatch(new SynthesisProfile.ScaleMatch(0, 0))
                .iteration(compIteration)
                .build();

        SynthesisProfile.Wiring wiring = new SynthesisProfile.Wiring(fieldCount / 2, ruleCount, compCount,
                Distribution.of(List.of(1, 1, 2)));
        return new SynthesisProfile(structure, rules, computations, wiring);
    }
}
