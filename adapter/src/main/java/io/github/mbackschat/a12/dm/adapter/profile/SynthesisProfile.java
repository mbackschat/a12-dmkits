package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.EntityPaths;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;

/**
 * A <b>domain-blind complexity profile</b> of a document model — the peer of {@link ModelReport}, but
 * where the report glosses each rule in plain language (names, text, values), the profile emits only
 * <em>shape and complexity</em>: counts, nesting depth, repeatability, field-kind and operator
 * histograms, and — the point — the performance-sensitive constructs the interpreter benchmark treats as
 * hotspots (aggregates, quantifiers, group-fill, current-repetition, semantic-index, filters, cross-level
 * walk-ups, deep repeatable nesting). It names <em>kernel operators</em> and counts shapes, never a field
 * name, literal, enum value, regex, or rule text — so a licensed model can be profiled and the numbers
 * handed to a test-model author to reconstruct a similarly-complex, own-domain model for evals.
 *
 * <p>Every complexity axis is a {@link Distribution} (min/median/p90/max + log buckets), not a bare total:
 * a single monster rule among trivial ones is a different eval than many medium rules, and the interpreter
 * cares about that tail. Row counts (the N=1k/10k/100k perf axis) are an <em>instance</em> property the
 * eval author picks; this profiles the <em>model</em>'s <em>capacity</em> to exercise each hotspot.
 */
record SynthesisProfile(Structure structure, Rules rules, Computations computations, Wiring wiring) {

    SynthesisProfile {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(computations, "computations");
        Objects.requireNonNull(wiring, "wiring");
    }

    /** The static shape: size, nesting, field kinds, repeatability, and declared-constraint prevalence. */
    public record Structure(int fieldCount, int groupCount, int maxNestingDepth,
            Distribution fieldsPerGroup, Map<String, Integer> fieldKinds,
            Repeatability repeatability, Constraints constraints) {

        static SynthesisStructureBuilders.StructureBuilder builder() {
            return SynthesisStructureBuilders.structure();
        }
    }

    /**
     * Repeatable-group facts — the O(N²)-risk surface (B1/B2/B3). {@code nestingDepth} is the distribution of
     * how deeply nested each repeatable group is (its repeatable-ancestor count) — a model with many
     * depth-1 repeatables is a flatter iteration surface than one with depth-3/4 nesting even at the same
     * {@code maxRepeatableNestingDepth} (which is only the max).
     *
     * <p><b>chainProduct</b> (n = repeatable groups) is, per repeatable group, the PRODUCT of {@code
     * maxRepetitions} over its repeatable ancestors-or-self (root-to-group cumulative) — the real per-group
     * capacity a starred aggregate scoped there would walk, disambiguating whether a high {@code
     * listScanCapacity} comes from one huge rep or a chain of moderate ones. <b>fieldChainProduct</b> (n = ALL
     * fields) is the same product for every field's containing chain (1 when under no repeatable) — the
     * field-mass placement signal a blind builder scatters uniformly instead of concentrating on the
     * high-product chains the target needs. <b>siblingBranchFan</b> (n = groups with fan ≥ 1) is, per group,
     * the number of DISTINCT repeatable branch roots reachable from it through non-repeatable descent only
     * — how many twin/multi-way aggregate hubs to build (the {@code parallelIteration} lever).
     *
     * <p><b>chainProductByDepth</b> is {@code chainProduct} SPLIT
     * by {@code nestingDepth} class ({@code "1"} / {@code "2-3"} / {@code "4+"}, mirroring the
     * {@code constructsByScope} joint style) — the joint {@code chainProduct} alone can't recover: a root
     * (depth-1) position's OWN chainProduct is just its own size (no repeatable ancestor to multiply
     * against), while a nested (depth 2+) position's is a multi-factor product, so the two classes have
     * structurally different scales and the single combined distribution's median/p90 conflate them. A
     * synthesizer placing a root's size needs the depth-1 sub-distribution specifically, not a value drawn
     * from the whole (nested-dominated) mix.
     *
     * <p><b>nestingDepthExact</b> is {@code nestingDepth}'s EXACT
     * per-depth-value histogram ({@code "1" → count}, {@code "2" → count}, …) — depth is a tiny-range small
     * integer, so an exact count map is cheap, domain-blind, and closes the WHOLE class of bucket-ambiguity a
     * log-scaled {@link Distribution}'s bucket can introduce, not just one instance of it: {@code
     * nestingDepth()}'s own bucketing conflates a bucket like {@code "2-3"} (two DIFFERENT true depths) into
     * one count, so a consumer that needs to know how many reps are EXACTLY at the deepest level (vs. merely
     * inside the bucket containing it) cannot recover that from the bucketed distribution alone — found via a
     * severe real-target regression: routing an entire ambiguous bucket's count into a "reaches the max"
     * classification starved the branching mechanism to near-zero on a target whose bucket happened to blend
     * mostly-shallower reps with a small deepest-level minority.
     */
    public record Repeatability(int repeatableGroupCount, int unboundedCount, int maxRepeatableNestingDepth,
            Distribution maxRepetitions, Distribution nestingDepth, Distribution chainProduct,
            Distribution fieldChainProduct, Distribution siblingBranchFan,
            Map<String, Distribution> chainProductByDepth, Map<String, Integer> nestingDepthExact) {

        static SynthesisStructureBuilders.RepeatabilityBuilder builder() {
            return SynthesisStructureBuilders.repeatability();
        }
    }

    /**
     * The three independent bounded evaluation capacities. {@code scopeCapacity} is the number of contexts
     * created by the rule/computation's own repeatable placement. {@code listScanCapacity} is the greatest
     * explicit collection operand it scans; scalar references do not count. {@code workCapacity} is their
     * saturated product. {@code scannedScopeCapacity} retains the scope distribution of programs that really
     * perform a non-trivial collection scan.
     */
    public record Iteration(Distribution listScanCapacity, int parallelIteration, int outerCorrelation,
            Distribution scopeCapacity, Distribution workCapacity, Distribution scannedScopeCapacity) {

        public Iteration {
            Objects.requireNonNull(listScanCapacity, "listScanCapacity");
            Objects.requireNonNull(scopeCapacity, "scopeCapacity");
            Objects.requireNonNull(workCapacity, "workCapacity");
            Objects.requireNonNull(scannedScopeCapacity, "scannedScopeCapacity");
        }

        static SynthesisStructureBuilders.IterationBuilder builder() {
            return SynthesisStructureBuilders.iteration();
        }
    }

    /** Declared-constraint prevalence (values stripped: presence + scale + enum cardinality only). */
    public record Constraints(int numbersWithBounds, int stringsWithLength, int stringsWithPattern,
            Distribution numberScale, Distribution enumCardinality) {

        static SynthesisStructureBuilders.ConstraintsBuilder builder() {
            return SynthesisStructureBuilders.constraints();
        }
    }

    /**
     * The rule population's complexity signal.
     *
     * <p><b>constructCombos</b> and <b>constructsByScope</b> are domain-blind <em>joints</em> (see the profile
     * spec's "profile any joint with a consumer" rule): {@code constructCombos} counts how the constructs
     * <em>co-occur</em> within one rule (e.g. {@code AGGREGATE+FILTER} vs {@code FILTER} alone — invisible in the
     * marginal {@code constructs} histogram, which only lets you subtract); {@code constructsByScope} is the
     * construct histogram split by scope class ({@code modelLevel}/{@code perGroup}/{@code perRepeatableRow}) — the
     * product that the separate scope + construct marginals can't recover, and the one that predicts the O(N²)
     * hotspot (an aggregate re-evaluated per repeatable row is quadratic; the same aggregate at model level is not).
     */
    public record Rules(int count, int opaqueCount, Scope scope,
            Distribution nodeCount, Distribution depth, Distribution booleanArity, Distribution fanIn,
            Distribution rulesPerErrorField, Distribution walkUpDistance, Distribution repeatableLevelsCrossed,
            Map<String, Integer> operators, Map<String, Integer> constructs,
            Map<String, Integer> constructCombos, Map<String, Map<String, Integer>> constructsByScope,
            Iteration iteration) {

        static SynthesisProgramBuilders.RulesBuilder builder() {
            return SynthesisProgramBuilders.rules();
        }
    }

    /** How often a rule evaluates: once (model-level / non-repeating group) vs per repeatable row. */
    public record Scope(int modelLevel, int perGroup, int perRepeatableRow) {}

    /**
     * The computation population's complexity signal, plus the dependency-cascade depth (B7).
     *
     * <p><b>computedFieldKinds</b> is the axis that answers "are all computed fields numbers?": it is the kind
     * histogram of the <em>computed</em> fields (NUMBER / STRING / DATE / …), independent of {@code count}.
     * <b>alternatives</b> is how many {@code when/calc} branches each computation carries (a single formula vs a
     * many-way conditional). <b>scope</b> mirrors the rule scope split (a per-repeatable-row computation runs
     * once per row — the N-row × computation cost). <b>preconditionedCount</b> is how many computations are
     * conditional at all (a common precondition or ≥1 preconditioned alternative).
     *
     * <p>The joint/derived axes (domain-blind, each with a consumer): <b>archetypes</b> is the per-computation
     * <em>operation shape</em> histogram — {@code COPY} / {@code AGGREGATE} / {@code ARITHMETIC} / {@code DATE} /
     * {@code STRING} / {@code CONSTANT} — a direct synthesis target (reconstructing shape from the operator +
     * construct marginals is lossy; the arithmetic-vs-aggregate split is exactly this). <b>constructCombos</b> and
     * <b>constructsByScope</b> mirror the rule joints (co-occurrence; construct × scope — the O(N²) predictor).
     * <b>aggregateScaleMatch</b> is the scale <em>joint</em>: of the aggregating computations, how many have the
     * computed field's declared scale equal to the summed cell's — the fact that decides whether the interpreter
     * must rescale (and, for synthesis, whether a same-scale {@code Sum} needs a scale-fix round).
     * <b>aggregateStars</b> (n = computations with ≥1 starred operand path) is, per such computation, the MAX
     * count of starred ({@code *}) segments in a single referenced path — 1 for a plain {@code Group*}{@code
     * /field} flatten, ≥2 for a nested-star ({@code RepA*}{@code /RepB*}{@code /field}) aggregate. Decides
     * whether a multi-star nested-aggregate synthesis recipe is needed at all.
     */
    public record Computations(int count, int opaqueCount,
            Distribution nodeCount, Distribution depth, Distribution fanIn,
            int maxCascadeDepth, int feedingOthers,
            Map<String, Integer> computedFieldKinds, Distribution alternatives, Scope scope, int preconditionedCount,
            Map<String, Integer> operators, Map<String, Integer> constructs,
            Map<String, Integer> archetypes, Map<String, Integer> constructCombos,
            Map<String, Map<String, Integer>> constructsByScope, ScaleMatch aggregateScaleMatch,
            Iteration iteration, Distribution aggregateStars) {

        static SynthesisProgramBuilders.ComputationsBuilder builder() {
            return SynthesisProgramBuilders.computations();
        }
    }

    /** The scale joint for aggregating computations: {@code matched} = computed-field scale equals the summed
     *  cell's scale (no interpreter rescale, no synthesis round); {@code mismatched} = differs. */
    public record ScaleMatch(int matched, int mismatched) {}

    /**
     * How densely the model is <b>wired</b> — a model where most fields feed a rule/computation is a different
     * eval than one where most fields are inert. {@code fieldsReferenced} counts fields touched by any rule or
     * computation (condition, operation, or as an error/computed field); {@code fieldFanOut} is, per referenced
     * field, how many distinct rules + computations touch it (the "edit field X → re-evaluate N" surface).
     */
    public record Wiring(int fieldsReferenced, int referencedByRules, int referencedByComputations,
            Distribution fieldFanOut) {}

    /** Assemble the profile of a self-contained, kernel-loadable model. */
    public static SynthesisProfile of(String modelJson) {
        return of(modelJson, null);
    }

    /**
     * Assemble the profile, resolving {@code include}s/imports through {@code resolver} (or none).
     *
     * <p>Uses the <b>lenient</b> (read-only) load — deserialize + expand <em>without</em> the kernel
     * consistency gate — on purpose: the reason to profile a model is usually that it is a real, foreign,
     * or <em>older-versioned</em> model, and a strict version/consistency check (e.g. a {@code modelVersion}
     * that predates the pinned kernel) would reject the very models this verb exists to characterize. A
     * structurally unreadable model (a parse/expand failure) still throws; a consistency-imperfect one is
     * profiled anyway. (Contrast {@code model describe}/{@code report}, which surface invalid-on-disk.)
     */
    public static SynthesisProfile of(String modelJson,
            io.github.mbackschat.a12.dm.core.ModelResolver resolver) {
        BoundModel model = BoundModel.ofJsonLenient(modelJson,
                resolver == null ? io.github.mbackschat.a12.dm.core.ReferenceResolvers.selfContained() : resolver);
        ModelContext context = new ModelContext(model);
        ModelCard card = context.describe();
        KernelAdapter adapter = KernelAdapter.of(model);

        // --- repeatability index (path -> repeatable, and the group path set) -----------------------
        Set<String> groupPaths = new HashSet<>();
        Set<String> repeatablePaths = new HashSet<>();
        for (ModelCard.Group g : card.groups()) {
            groupPaths.add(g.path());
            if (g.repeatable()) {
                repeatablePaths.add(g.path());
            }
        }

        // field path -> declared number scale (for the aggregate scale-match joint; absent for non-numbers)
        Map<String, Integer> scaleByPath = new HashMap<>();
        for (ModelCard.Field f : card.fields()) {
            f.scale().ifPresent(s -> scaleByPath.put(f.path(), s));
        }
        // repeatable group path -> declared max repetitions (for the star-capture iteration-cost metric)
        Map<String, Integer> maxRepsByPath = new HashMap<>();
        for (ModelCard.Group g : card.groups()) {
            if (g.repeatable() && g.maxRepetitions() > 0) {
                maxRepsByPath.put(g.path(), g.maxRepetitions());
            }
        }

        Structure structure = structure(card, groupPaths, repeatablePaths, maxRepsByPath);
        Rules rules = rules(adapter, groupPaths, repeatablePaths, maxRepsByPath);
        Computations computations = computations(adapter, repeatablePaths, scaleByPath, maxRepsByPath);
        Wiring wiring = wiring(adapter, card);
        return new SynthesisProfile(structure, rules, computations, wiring);
    }

    // ----------------------------------------------------------------------------------------------
    // Wiring — how densely fields feed rules/computations
    // ----------------------------------------------------------------------------------------------

    private static Wiring wiring(KernelAdapter adapter, ModelCard card) {
        Map<String, Integer> fanOut = new LinkedHashMap<>();    // field path -> # rules + computations touching it
        Set<String> byRules = new HashSet<>();
        Set<String> byComputations = new HashSet<>();
        for (String name : adapter.ruleFullNames()) {
            Set<String> touched = new HashSet<>();
            adapter.ruleErrorField(name).ifPresent(touched::add);
            if (adapter.read(name) instanceof ReadResult.Converted c) {
                touched.addAll(ConditionReferences.of(c.condition()).fieldPaths());
            }
            byRules.addAll(touched);
            touched.forEach(f -> fanOut.merge(f, 1, Integer::sum));
        }
        for (String name : adapter.computationFullNames()) {
            Set<String> touched = new HashSet<>();
            if (adapter.readComputation(name) instanceof ReadComputation.Converted c) {
                Computation comp = c.computation();
                touched.add(comp.computedField().path().value());
                comp.commonPrecondition().ifPresent(p -> touched.addAll(ConditionReferences.of(p).fieldPaths()));
                for (Computation.Alternative alt : comp.alternatives()) {
                    alt.precondition().ifPresent(p -> touched.addAll(ConditionReferences.of(p).fieldPaths()));
                    touched.addAll(ConditionReferences.of(alt.operation()).fieldPaths());
                }
            }
            byComputations.addAll(touched);
            touched.forEach(f -> fanOut.merge(f, 1, Integer::sum));
        }
        Set<String> referenced = new HashSet<>(byRules);
        referenced.addAll(byComputations);
        return new Wiring(referenced.size(), byRules.size(), byComputations.size(),
                Distribution.of(new ArrayList<>(fanOut.values())));
    }

    // ----------------------------------------------------------------------------------------------
    // Structure
    // ----------------------------------------------------------------------------------------------

    private static Structure structure(ModelCard card, Set<String> groupPaths, Set<String> repeatablePaths,
            Map<String, Integer> maxRepsByPath) {
        int maxNestingDepth = 0;
        Map<String, Integer> fieldsPerGroup = new LinkedHashMap<>();
        Map<String, List<String>> childrenOf = new LinkedHashMap<>();  // parent group path -> child group paths
        for (ModelCard.Group g : card.groups()) {
            maxNestingDepth = Math.max(maxNestingDepth, depthOf(g.path()));
            fieldsPerGroup.put(g.path(), 0);
            String parent = parentOf(g.path());
            if (!parent.equals(g.path())) {                     // exclude the root group (parentOf is a fixed point)
                childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(g.path());
            }
        }

        Map<String, Integer> fieldKinds = new LinkedHashMap<>();
        List<Integer> numberScales = new ArrayList<>();
        List<Integer> enumCardinalities = new ArrayList<>();
        List<Integer> fieldChainProducts = new ArrayList<>();   // per field: its containing chain's product
        int numbersWithBounds = 0;
        int stringsWithLength = 0;
        int stringsWithPattern = 0;
        for (ModelCard.Field f : card.fields()) {
            fieldKinds.merge(f.kind(), 1, Integer::sum);
            fieldsPerGroup.merge(parentOf(f.path()), 1, Integer::sum);
            if (f.min().isPresent() || f.max().isPresent()) {
                numbersWithBounds++;
            }
            if (f.minLength().isPresent() || f.maxLength().isPresent()) {
                stringsWithLength++;
            }
            if (f.pattern().isPresent()) {
                stringsWithPattern++;
            }
            f.scale().ifPresent(numberScales::add);
            if (!f.enumValues().isEmpty()) {
                enumCardinalities.add(f.enumValues().size());
            }
            fieldChainProducts.add((int) chainProduct(parentOf(f.path()), repeatablePaths, maxRepsByPath));
        }

        int repeatableCount = 0;
        int unbounded = 0;
        int maxRepeatableNesting = 0;
        List<Integer> maxReps = new ArrayList<>();
        List<Integer> nestingDepths = new ArrayList<>();        // per repeatable group: its repeatable-ancestor count
        List<Integer> chainProducts = new ArrayList<>();        // per repeatable group: root-to-group cumulative product
        Map<String, List<Integer>> chainProductsByDepth = new LinkedHashMap<>();  // depth class -> chainProducts
        for (ModelCard.Group g : card.groups()) {
            if (!g.repeatable()) {
                continue;
            }
            repeatableCount++;
            if (g.maxRepetitions() <= 0) {
                unbounded++;
            } else {
                maxReps.add(g.maxRepetitions());
            }
            int nesting = repeatableAncestorCount(g.path(), repeatablePaths);
            nestingDepths.add(nesting);
            maxRepeatableNesting = Math.max(maxRepeatableNesting, nesting);
            int cp = (int) chainProduct(g.path(), repeatablePaths, maxRepsByPath);
            chainProducts.add(cp);
            chainProductsByDepth.computeIfAbsent(nestingDepthClass(nesting), k -> new ArrayList<>()).add(cp);
        }

        List<Integer> siblingBranchFans = new ArrayList<>();    // per group with fan >= 1: distinct rep branches reached
        for (ModelCard.Group g : card.groups()) {
            int fan = siblingBranchFan(g.path(), childrenOf, repeatablePaths);
            if (fan >= 1) {
                siblingBranchFans.add(fan);
            }
        }

        Map<String, Distribution> chainProductByDepth = new LinkedHashMap<>();
        chainProductsByDepth.forEach((depthClass, values) -> chainProductByDepth.put(depthClass, Distribution.of(values)));

        // the EXACT per-depth-value histogram — unlike chainProductByDepth's bucket classes, this
        // is keyed by the ACTUAL integer depth ("1", "2", "3", …), so a consumer never has to guess how many
        // of an ambiguous bucket's reps are truly at one depth vs. another.
        Map<String, Integer> nestingDepthExact = new LinkedHashMap<>();
        for (int nesting : nestingDepths) {
            nestingDepthExact.merge(String.valueOf(nesting), 1, Integer::sum);
        }

        var repeatability = Repeatability.builder()
                .repeatableGroupCount(repeatableCount)
                .unboundedCount(unbounded)
                .maxRepeatableNestingDepth(maxRepeatableNesting)
                .maxRepetitions(Distribution.of(maxReps))
                .nestingDepth(Distribution.of(nestingDepths))
                .chainProduct(Distribution.of(chainProducts))
                .fieldChainProduct(Distribution.of(fieldChainProducts))
                .siblingBranchFan(Distribution.of(siblingBranchFans))
                .chainProductByDepth(chainProductByDepth)
                .nestingDepthExact(nestingDepthExact)
                .build();
        var constraints = Constraints.builder()
                .numbersWithBounds(numbersWithBounds)
                .stringsWithLength(stringsWithLength)
                .stringsWithPattern(stringsWithPattern)
                .numberScale(Distribution.of(numberScales))
                .enumCardinality(Distribution.of(enumCardinalities))
                .build();
        return Structure.builder()
                .fieldCount(card.fields().size())
                .groupCount(card.groups().size())
                .maxNestingDepth(maxNestingDepth)
                .fieldsPerGroup(Distribution.of(new ArrayList<>(fieldsPerGroup.values())))
                .fieldKinds(fieldKinds)
                .repeatability(repeatability)
                .constraints(constraints)
                .build();
    }

    // ----------------------------------------------------------------------------------------------
    // Rules
    // ----------------------------------------------------------------------------------------------

    private static Rules rules(KernelAdapter adapter, Set<String> groupPaths, Set<String> repeatablePaths,
            Map<String, Integer> maxRepsByPath) {
        int opaque = 0;
        int modelLevel = 0;
        int perGroup = 0;
        int perRow = 0;
        List<Integer> nodeCounts = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        List<Integer> arities = new ArrayList<>();
        List<Integer> fanIns = new ArrayList<>();
        List<Integer> walkUps = new ArrayList<>();          // only positive (rules that walk up)
        List<Integer> repeatableLevels = new ArrayList<>(); // per converted rule, incl. 0
        List<Integer> listScanCapacities = new ArrayList<>();     // per rule: worst-case starred element count
        List<Integer> scopeCapacities = new ArrayList<>();    // per rule: its OWN scope group's chain product
        List<Integer> workCapacities = new ArrayList<>();    // per rule: scopeCapacity × listScanCapacity
        int parallelIteration = 0;
        int outerCorrelation = 0;
        Map<String, Integer> operators = new LinkedHashMap<>();
        Map<String, Integer> constructs = new LinkedHashMap<>();
        Map<String, Integer> constructCombos = new LinkedHashMap<>();        // co-occurrence joint
        Map<String, Map<String, Integer>> constructsByScope = newScopeBuckets();  // construct × scope joint
        Map<String, Integer> perErrorField = new LinkedHashMap<>();

        for (String name : adapter.ruleFullNames()) {
            String scope = scopeOf(name);
            int scopeClass = scopeClass(scope, repeatablePaths);
            switch (scopeClass) {
                case 0 -> modelLevel++;
                case 1 -> perGroup++;
                default -> perRow++;
            }
            perErrorField.merge(adapter.ruleErrorField(name).orElse(scope), 1, Integer::sum);

            ReadResult read = adapter.read(name);
            if (!(read instanceof ReadResult.Converted converted)) {
                opaque++;
                continue;
            }
            Condition condition = converted.condition();
            ConditionComplexity cx = ConditionComplexity.of(condition);
            ConditionReferences refs = ConditionReferences.of(condition);
            nodeCounts.add(cx.nodeCount());
            depths.add(cx.depth());
            arities.add(cx.maxBooleanArity());
            fanIns.add(refs.fieldPaths().size() + refs.groupPaths().size());
            mergeInto(operators, cx.operatorHistogram());
            for (ConditionComplexity.Construct c : cx.constructs()) {
                constructs.merge(c.name(), 1, Integer::sum);
                constructsByScope.get(scopeClassName(scopeClass)).merge(c.name(), 1, Integer::sum);
            }
            constructCombos.merge(comboKey(cx.constructs()), 1, Integer::sum);
            int walkUp = maxWalkUp(scope, refs, groupPaths);
            if (walkUp > 0) {
                walkUps.add(walkUp);
            }
            repeatableLevels.add(maxRepeatableLevelsCrossed(refs, repeatablePaths));
            int listScanCapacity = listScanCapacity(cx.collectionPaths(), repeatablePaths, maxRepsByPath);
            int scopeCapacity = boundedInt(chainProduct(scope, repeatablePaths, maxRepsByPath));
            listScanCapacities.add(listScanCapacity);
            scopeCapacities.add(scopeCapacity);
            workCapacities.add(workCapacity(listScanCapacity, scopeCapacity));
            if (isParallelIteration(refs.fieldPaths(), repeatablePaths)) {
                parallelIteration++;
            }
            if (cx.outerCorrelations() > 0) {
                outerCorrelation++;
            }
        }

        var iteration = Iteration.builder()
                .listScanCapacity(Distribution.of(listScanCapacities))
                .parallelIteration(parallelIteration)
                .outerCorrelation(outerCorrelation)
                .scopeCapacity(Distribution.of(scopeCapacities))
                .workCapacity(Distribution.of(workCapacities))
                .scannedScopeCapacity(Distribution.of(
                        scannedScopeCapacities(scopeCapacities, listScanCapacities)))
                .build();
        return Rules.builder()
                .count(adapter.ruleFullNames().size())
                .opaqueCount(opaque)
                .scope(new Scope(modelLevel, perGroup, perRow))
                .nodeCount(Distribution.of(nodeCounts))
                .depth(Distribution.of(depths))
                .booleanArity(Distribution.of(arities))
                .fanIn(Distribution.of(fanIns))
                .rulesPerErrorField(Distribution.of(new ArrayList<>(perErrorField.values())))
                .walkUpDistance(Distribution.of(walkUps))
                .repeatableLevelsCrossed(Distribution.of(repeatableLevels))
                .operators(sortedByValue(operators))
                .constructs(sortedByValue(constructs))
                .constructCombos(sortedByValue(constructCombos))
                .constructsByScope(sortByValueEach(constructsByScope))
                .iteration(iteration)
                .build();
    }

    // ----------------------------------------------------------------------------------------------
    // Computations
    // ----------------------------------------------------------------------------------------------

    private static Computations computations(KernelAdapter adapter, Set<String> repeatablePaths,
            Map<String, Integer> scaleByPath, Map<String, Integer> maxRepsByPath) {
        int opaque = 0;
        List<Integer> nodeCounts = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        List<Integer> fanIns = new ArrayList<>();
        List<Integer> altCounts = new ArrayList<>();            // alternatives per computation
        List<Integer> listScanCapacities = new ArrayList<>();         // per computation: worst-case starred element count
        List<Integer> scopeCapacities = new ArrayList<>();        // per computation: its computed field's group chain product
        List<Integer> workCapacities = new ArrayList<>();        // per computation: scopeCapacity × listScanCapacity
        List<Integer> aggregateStarsList = new ArrayList<>();   // per computation with >=1 starred operand: MAX star nesting
        int parallelIteration = 0;
        int outerCorrelation = 0;
        Map<String, Integer> operators = new LinkedHashMap<>();
        Map<String, Integer> constructs = new LinkedHashMap<>();
        Map<String, Integer> computedKinds = new LinkedHashMap<>();  // kind of the COMPUTED field
        Map<String, Integer> archetypes = new LinkedHashMap<>();     // operation-shape histogram
        Map<String, Integer> constructCombos = new LinkedHashMap<>(); // co-occurrence joint
        Map<String, Map<String, Integer>> constructsByScope = newScopeBuckets();  // construct × scope joint
        int modelLevel = 0;
        int perGroup = 0;
        int perRow = 0;
        int preconditioned = 0;
        int scaleMatched = 0;
        int scaleMismatched = 0;

        // dependency edges: computed-field path -> the set of computed fields its formula reads
        Map<String, Set<String>> reads = new LinkedHashMap<>();
        Set<String> computedFields = new HashSet<>();

        for (String name : adapter.computationFullNames()) {
            ReadComputation read = adapter.readComputation(name);
            if (!(read instanceof ReadComputation.Converted converted)) {
                opaque++;
                continue;
            }
            Computation comp = converted.computation();
            computedFields.add(comp.computedField().path().value());
            computedKinds.merge(comp.computedField().kind().name(), 1, Integer::sum);
            altCounts.add(comp.alternatives().size());
            if (comp.commonPrecondition().isPresent()
                    || comp.alternatives().stream().anyMatch(a -> a.precondition().isPresent())) {
                preconditioned++;
            }
            String scope = parentOf(comp.computedField().path().value());   // the computed field's group
            int scopeClass = scopeClass(scope, repeatablePaths);
            switch (scopeClass) {
                case 0 -> modelLevel++;
                case 1 -> perGroup++;
                default -> perRow++;
            }

            int nodeCount = 0;
            int depth = 0;
            Set<String> refFields = new HashSet<>();
            Set<String> constructSet = new HashSet<>();             // this computation's full construct set
            Set<String> collectionPaths = new HashSet<>();
            Set<ConditionComplexity.Construct> opConstructs = new HashSet<>();  // OPERATION-only (for archetype)
            Set<String> opRefFields = new HashSet<>();              // OPERATION-only field refs (for archetype/scale)
            List<ConditionComplexity> parts = new ArrayList<>();
            comp.commonPrecondition().ifPresent(c -> parts.add(ConditionComplexity.of(c)));
            for (Computation.Alternative alt : comp.alternatives()) {
                alt.precondition().ifPresent(c -> parts.add(ConditionComplexity.of(c)));
                ConditionComplexity opCx = ConditionComplexity.of(alt.operation());
                parts.add(opCx);
                opConstructs.addAll(opCx.constructs());
                opRefFields.addAll(ConditionReferences.of(alt.operation()).fieldPaths());
            }
            int outerHere = 0;
            int maxStars = 0;                                        // MAX starred segments in a single operand path
            for (ConditionComplexity cx : parts) {
                nodeCount += cx.nodeCount();
                depth = Math.max(depth, cx.depth());
                outerHere += cx.outerCorrelations();
                maxStars = Math.max(maxStars, cx.maxStarSegments());
                collectionPaths.addAll(cx.collectionPaths());
                mergeInto(operators, cx.operatorHistogram());
                for (ConditionComplexity.Construct c : cx.constructs()) {
                    constructs.merge(c.name(), 1, Integer::sum);
                    constructsByScope.get(scopeClassName(scopeClass)).merge(c.name(), 1, Integer::sum);
                    constructSet.add(c.name());
                }
            }
            if (outerHere > 0) {
                outerCorrelation++;
            }
            if (maxStars >= 1) {
                aggregateStarsList.add(maxStars);
            }
            constructCombos.merge(comboKeyOf(constructSet), 1, Integer::sum);
            archetypes.merge(archetypeOf(comp, opConstructs, opRefFields), 1, Integer::sum);
            // scale-match joint: for aggregating computations, does the computed NUMBER field's scale equal the
            // scale of a repeatable number cell it sums? (matched → the interpreter needs no rescale, synthesis no round)
            if (opConstructs.contains(ConditionComplexity.Construct.AGGREGATE)
                    && scaleByPath.containsKey(comp.computedField().path().value())) {
                int cScale = scaleByPath.get(comp.computedField().path().value());
                boolean matched = opRefFields.stream()
                        .anyMatch(f -> hasRepeatableAncestorOrSelf(parentOf(f), repeatablePaths)
                                && scaleByPath.getOrDefault(f, Integer.MIN_VALUE) == cScale);
                if (matched) {
                    scaleMatched++;
                } else {
                    scaleMismatched++;
                }
            }
            comp.commonPrecondition().ifPresent(c -> refFields.addAll(ConditionReferences.of(c).fieldPaths()));
            for (Computation.Alternative alt : comp.alternatives()) {
                alt.precondition().ifPresent(c -> refFields.addAll(ConditionReferences.of(c).fieldPaths()));
                refFields.addAll(ConditionReferences.of(alt.operation()).fieldPaths());
            }
            nodeCounts.add(nodeCount);
            depths.add(depth);
            fanIns.add(refFields.size());
            int listScanCapacity = listScanCapacity(collectionPaths, repeatablePaths, maxRepsByPath);
            int scopeCapacity = boundedInt(chainProduct(scope, repeatablePaths, maxRepsByPath));
            listScanCapacities.add(listScanCapacity);
            scopeCapacities.add(scopeCapacity);
            workCapacities.add(workCapacity(listScanCapacity, scopeCapacity));
            if (isParallelIteration(refFields, repeatablePaths)) {
                parallelIteration++;
            }
            reads.put(comp.computedField().path().value(), refFields);
        }

        int feedingOthers = feedingOthers(reads, computedFields);
        int maxCascade = maxCascadeDepth(reads, computedFields);
        var iteration = Iteration.builder()
                .listScanCapacity(Distribution.of(listScanCapacities))
                .parallelIteration(parallelIteration)
                .outerCorrelation(outerCorrelation)
                .scopeCapacity(Distribution.of(scopeCapacities))
                .workCapacity(Distribution.of(workCapacities))
                .scannedScopeCapacity(Distribution.of(
                        scannedScopeCapacities(scopeCapacities, listScanCapacities)))
                .build();
        return Computations.builder()
                .count(adapter.computationFullNames().size())
                .opaqueCount(opaque)
                .nodeCount(Distribution.of(nodeCounts))
                .depth(Distribution.of(depths))
                .fanIn(Distribution.of(fanIns))
                .maxCascadeDepth(maxCascade)
                .feedingOthers(feedingOthers)
                .computedFieldKinds(sortedByValue(computedKinds))
                .alternatives(Distribution.of(altCounts))
                .scope(new Scope(modelLevel, perGroup, perRow))
                .preconditionedCount(preconditioned)
                .operators(sortedByValue(operators))
                .constructs(sortedByValue(constructs))
                .archetypes(sortedByValue(archetypes))
                .constructCombos(sortedByValue(constructCombos))
                .constructsByScope(sortByValueEach(constructsByScope))
                .aggregateScaleMatch(new ScaleMatch(scaleMatched, scaleMismatched))
                .iteration(iteration)
                .aggregateStars(Distribution.of(aggregateStarsList))
                .build();
    }

    // ----------------------------------------------------------------------------------------------
    // Iteration-cost helpers (star-capture, parallel iteration) — domain-blind, from paths + maxReps
    // ----------------------------------------------------------------------------------------------

    /**
     * The worst-case number of elements a starred aggregate/quantifier walks: the MAX over the referenced fields
     * of the PRODUCT of {@code maxRepetitions} across that field's repeatable ancestors (a field under two 100-row
     * lists → 10 000 — the O(N²) case). 1 when no referenced field sits in a repeatable group. Capped at
     * {@link Integer#MAX_VALUE} (huge nested-unbounded lists land in the top log-bucket rather than overflow).
     */
    private static int listScanCapacity(java.util.Collection<String> collectionPaths, Set<String> repeatablePaths,
            Map<String, Integer> maxRepsByPath) {
        long max = 1;
        for (String path : collectionPaths) {
            max = Math.max(max, chainProduct(parentOf(path), repeatablePaths, maxRepsByPath));
        }
        return boundedInt(max);
    }

    /** Total bounded work: the element's evaluation multiplicity times its greatest explicit list scan. */
    private static int workCapacity(int listScanCapacity, int scopeCapacity) {
        return boundedInt((long) listScanCapacity * scopeCapacity);
    }

    /** Scope capacities restricted to programs that perform an explicit non-trivial collection scan. */
    private static List<Integer> scannedScopeCapacities(
            List<Integer> scopeCapacities, List<Integer> listScanCapacities) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < scopeCapacities.size(); i++) {
            if (listScanCapacities.get(i) > 1) {
                out.add(scopeCapacities.get(i));
            }
        }
        return out;
    }

    private static int boundedInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    /**
     * The PRODUCT of {@code maxRepetitions} over the repeatable groups at or above {@code path}
     * (ancestors-or-self) — the real per-position iteration capacity (M1/M2/M3: a repeatable group's own
     * {@code chainProduct}, a field's {@code fieldChainProduct}, a rule/computation's scope {@code
     * scopeCapacity}, and {@code listScanCapacity}'s per-referenced-field factor are all this same walk). 1 when
     * {@code path} carries no repeatable ancestor. Computed in {@code long} and saturated at every step so a
     * pathologically deep/large chain can't overflow {@code long} before the final {@code int} narrowing —
     * unbounded in general, unlike this method's actual callers' fixed small model sizes.
     */
    private static long chainProduct(String path, Set<String> repeatablePaths, Map<String, Integer> maxRepsByPath) {
        long product = 1;
        for (String p = path; ; ) {
            if (repeatablePaths.contains(p)) {
                product = Math.min(product * maxRepsByPath.getOrDefault(p, 1), Integer.MAX_VALUE);
            }
            String parent = parentOf(p);
            if (parent.equals(p)) {
                return product;
            }
            p = parent;
        }
    }

    /**
     * The number of DISTINCT repeatable branch roots reachable from {@code group} through non-repeatable
     * descent only (M5) — a group's children, recursing through non-repeatable ones and counting (not
     * descending past) each repeatable one found. The twin/multi-way aggregate hub signal: a group with fan
     * ≥2 can host a parallel cross-list aggregate over its sibling branches.
     */
    private static int siblingBranchFan(String group, Map<String, List<String>> childrenOf,
            Set<String> repeatablePaths) {
        int fan = 0;
        for (String child : childrenOf.getOrDefault(group, List.of())) {
            if (repeatablePaths.contains(child)) {
                fan++;
            } else {
                fan += siblingBranchFan(child, childrenOf, repeatablePaths);
            }
        }
        return fan;
    }

    /**
     * Whether the element references fields in ≥2 <em>incomparable</em> repeatable branches — a parallel cross-list
     * join (O(N×M)), distinct from nested iteration (one repeatable below another, already in
     * {@code repeatableLevelsCrossed}). True when two referenced fields' nearest repeatable ancestors are neither
     * equal nor ancestor/descendant of each other.
     */
    private static boolean isParallelIteration(java.util.Collection<String> fieldPaths, Set<String> repeatablePaths) {
        Set<String> nearestReps = new HashSet<>();
        for (String field : fieldPaths) {
            String rep = nearestRepeatable(parentOf(field), repeatablePaths);
            if (rep != null) {
                nearestReps.add(rep);
            }
        }
        for (String a : nearestReps) {
            for (String b : nearestReps) {
                if (!a.equals(b) && !EntityPaths.isStrictlyInside(a, b)
                        && !EntityPaths.isStrictlyInside(b, a)) {
                    return true;                                // incomparable branches → parallel iteration
                }
            }
        }
        return false;
    }

    /** The nearest repeatable group at or above {@code path}, or null if none — the branch a field iterates in. */
    private static String nearestRepeatable(String path, Set<String> repeatablePaths) {
        for (String p = path; ; ) {
            if (repeatablePaths.contains(p)) {
                return p;
            }
            String parent = parentOf(p);
            if (parent.equals(p)) {
                return null;
            }
            p = parent;
        }
    }

    /** The operation-shape archetype of a computation — a single label from its OPERATION constructs (preconditions
     *  excluded), falling back to the computed field's kind. Priority: arithmetic ⊐ aggregate ⊐ date, then string /
     *  a plain field copy / a bare constant. A direct synthesis target the operator+construct marginals blur. */
    private static String archetypeOf(Computation comp, Set<ConditionComplexity.Construct> opConstructs,
            Set<String> opRefFields) {
        if (opConstructs.contains(ConditionComplexity.Construct.ARITHMETIC)) {
            return "ARITHMETIC";
        }
        if (opConstructs.contains(ConditionComplexity.Construct.AGGREGATE)) {
            return "AGGREGATE";
        }
        if (opConstructs.contains(ConditionComplexity.Construct.DATE_MATH)) {
            return "DATE";
        }
        if ("STRING".equals(comp.computedField().kind().name())) {
            return "STRING";
        }
        return opRefFields.isEmpty() ? "CONSTANT" : "COPY";      // a field reference vs a bare literal
    }

    /** How many computed fields are read by <em>another</em> computation's formula. */
    private static int feedingOthers(Map<String, Set<String>> reads, Set<String> computedFields) {
        Set<String> fed = new HashSet<>();
        for (var entry : reads.entrySet()) {
            for (String read : entry.getValue()) {
                if (computedFields.contains(read) && !read.equals(entry.getKey())) {
                    fed.add(read);
                }
            }
        }
        return fed.size();
    }

    /** Longest computed-on-computed dependency chain (1 = no computation reads another). */
    private static int maxCascadeDepth(Map<String, Set<String>> reads, Set<String> computedFields) {
        int max = 0;
        Map<String, Integer> memo = new HashMap<>();
        for (String field : reads.keySet()) {
            max = Math.max(max, chainDepth(field, reads, computedFields, memo, new HashSet<>()));
        }
        return max;
    }

    private static int chainDepth(String field, Map<String, Set<String>> reads, Set<String> computedFields,
            Map<String, Integer> memo, Set<String> onStack) {
        if (memo.containsKey(field)) {
            return memo.get(field);
        }
        if (!onStack.add(field)) {
            return 1; // defend against a cycle the kernel would have rejected
        }
        int best = 1;
        for (String read : reads.getOrDefault(field, Set.of())) {
            if (computedFields.contains(read) && !read.equals(field)) {
                best = Math.max(best, 1 + chainDepth(read, reads, computedFields, memo, onStack));
            }
        }
        onStack.remove(field);
        memo.put(field, best);
        return best;
    }

    // ----------------------------------------------------------------------------------------------
    // Path + map helpers
    // ----------------------------------------------------------------------------------------------

    /** Segment depth of an absolute path ({@code /A/B} -> 2). */
    private static int depthOf(String path) {
        int d = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                d++;
            }
        }
        return d;
    }

    /** The containing group path of an element ({@code /A/B/f} -> {@code /A/B}). */
    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : path;
    }

    /** The element's scope group — its containing group (mirrors {@link ModelReport}'s rule scoping). */
    private static String scopeOf(String fullName) {
        int slash = fullName.lastIndexOf('/');
        return slash > 0 ? fullName.substring(0, slash) : fullName;
    }

    /** 0 = model-level (root group), 1 = non-repeating subgroup, 2 = within/at a repeatable group. */
    private static int scopeClass(String scope, Set<String> repeatablePaths) {
        if (hasRepeatableAncestorOrSelf(scope, repeatablePaths)) {
            return 2;
        }
        return depthOf(scope) <= 1 ? 0 : 1;
    }

    private static boolean hasRepeatableAncestorOrSelf(String path, Set<String> repeatablePaths) {
        for (String p = path; ; ) {
            if (repeatablePaths.contains(p)) {
                return true;
            }
            String parent = parentOf(p);
            if (parent.equals(p)) {
                return false;
            }
            p = parent;
        }
    }

    /** Repeatable groups among a path's ancestors-or-self — the nested-repeatable depth for that node. */
    private static int repeatableAncestorCount(String path, Set<String> repeatablePaths) {
        int count = 0;
        for (String p = path; ; ) {
            if (repeatablePaths.contains(p)) {
                count++;
            }
            String parent = parentOf(p);
            if (parent.equals(p)) {
                return count;
            }
            p = parent;
        }
    }

    /** The {@code chainProductByDepth} bucket key for a {@code nestingDepth} value: {@code "1"} (no repeatable
     *  ancestor — a root, whose OWN chainProduct is just its own size), {@code "2-3"}, or {@code "4+"}. */
    private static String nestingDepthClass(int nesting) {
        if (nesting <= 1) {
            return "1";
        }
        return nesting <= 3 ? "2-3" : "4+";
    }

    /** Max walk-up distance: a referenced element whose group is a strict ancestor of the rule's scope. */
    private static int maxWalkUp(String scope, ConditionReferences refs, Set<String> groupPaths) {
        int scopeDepth = depthOf(scope);
        int max = 0;
        for (String field : refs.fieldPaths()) {
            max = Math.max(max, walkUp(scope, scopeDepth, parentOf(field), groupPaths));
        }
        for (String group : refs.groupPaths()) {
            max = Math.max(max, walkUp(scope, scopeDepth, group, groupPaths));
        }
        return max;
    }

    private static int walkUp(String scope, int scopeDepth, String refGroup, Set<String> groupPaths) {
        if (groupPaths.contains(refGroup) && EntityPaths.isStrictlyInside(scope, refGroup)) {
            return scopeDepth - depthOf(refGroup);
        }
        return 0;
    }

    /** The most repeatable-group levels any referenced field path crosses — the deep-star signal. */
    private static int maxRepeatableLevelsCrossed(ConditionReferences refs, Set<String> repeatablePaths) {
        int max = 0;
        for (String field : refs.fieldPaths()) {
            max = Math.max(max, repeatableAncestorCount(parentOf(field), repeatablePaths));
        }
        for (String group : refs.groupPaths()) {
            max = Math.max(max, repeatableAncestorCount(group, repeatablePaths));
        }
        return max;
    }

    private static void mergeInto(Map<String, Integer> target, Map<String, Integer> source) {
        source.forEach((k, v) -> target.merge(k, v, Integer::sum));
    }

    /** Order a histogram by descending count so the dominant operators/constructs read first. */
    private static Map<String, Integer> sortedByValue(Map<String, Integer> map) {
        Map<String, Integer> out = new LinkedHashMap<>();
        map.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    // ----------------------------------------------------------------------------------------------
    // Joint-metric helpers (construct co-occurrence, construct × scope) — domain-blind, count-only
    // ----------------------------------------------------------------------------------------------

    /** The three scope-class bucket names, matching the {@link Scope} record fields. */
    private static final List<String> SCOPE_CLASSES = List.of("modelLevel", "perGroup", "perRepeatableRow");

    private static Map<String, Map<String, Integer>> newScopeBuckets() {
        Map<String, Map<String, Integer>> m = new LinkedHashMap<>();
        for (String s : SCOPE_CLASSES) {
            m.put(s, new LinkedHashMap<>());
        }
        return m;
    }

    private static String scopeClassName(int scopeClass) {
        return SCOPE_CLASSES.get(Math.min(scopeClass, 2));
    }

    /** A canonical co-occurrence key: the construct names sorted + {@code +}-joined ({@code "NONE"} when empty). */
    private static String comboKey(Set<ConditionComplexity.Construct> constructs) {
        List<String> names = new ArrayList<>();
        for (ConditionComplexity.Construct c : constructs) {
            names.add(c.name());
        }
        return joinCombo(names);
    }

    private static String comboKeyOf(Set<String> names) {
        return joinCombo(new ArrayList<>(names));
    }

    private static String joinCombo(List<String> names) {
        if (names.isEmpty()) {
            return "NONE";
        }
        names.sort(null);
        return String.join("+", names);
    }

    /** Sort each inner histogram of a {@code scope -> construct -> count} nesting by descending count. */
    private static Map<String, Map<String, Integer>> sortByValueEach(Map<String, Map<String, Integer>> nested) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        nested.forEach((k, v) -> out.put(k, sortedByValue(v)));
        return out;
    }
}
