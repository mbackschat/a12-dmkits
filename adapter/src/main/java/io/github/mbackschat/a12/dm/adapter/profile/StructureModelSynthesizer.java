package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/** Builds the structure and typed field draft for a synthesized model. */
final class StructureModelSynthesizer {

    private StructureModelSynthesizer() {
    }

    /** Build the typed draft from the profile — package-visible so a round-trip test can bind it directly. */
    static DocumentModelDraft plan(SynthesisProfile profile, long seed) {
        SynthesisProfile.Structure s = profile.structure();
        Naming naming = new Naming();
        Random rnd = SynthesisRandom.forPurpose(seed, "structure");

        // 1+2. the group tree WITH repeatability, jointly planned so that most repeatable groups are children
        //       of their OWN non-repeatable HUB — creating many aggregate-capable positions (a computation/rule
        //       scoped at a hub can Sum over its repeatable child). A single nested chain reaches the repeatable
        //       nesting depth + overall nesting depth. (The old spine+fan-out left only ~5 aggregate-capable
        //       groups, capping AGGREGATE — MODEL-SYNTHESIS-FIDELITY-SPEC "joint structure+computation plan".)
        int groupCount = Math.max(1, s.groupCount());
        int depth = Math.max(1, s.maxNestingDepth());
        GroupPlan root = buildTree(groupCount, depth, s.repeatability(), rnd, naming);
        List<GroupPlan> groups = flatten(root);

        // 3. fields: distribute the field budget across groups (leaves guaranteed ≥1), typed by the kind mix.
        //    reachDemand — how many computations the target says carry a NON-TRIVIAL reach
        //    (nonTrivialFraction(comp.reachProduct) × count) — sizes the NUMBER-kind hub steering inside
        //    a DEFICIT-based lift, so a target whose natural hub-NUMBER supply already meets its own demand
        //    (a breadth-shaped or compact profile) is left untouched.
        SynthesisProfile.Iteration compIteration = profile.computations().iteration();
        int reachDemand = compIteration == null || compIteration.reachProduct() == null ? 0
                : (int) Math.ceil(nonTrivialFraction(compIteration.reachProduct(), 0.0)
                        * profile.computations().count());
        // Supply-side scope planning: the measured
        // comp.iteration.scopeProduct distribution sizes a SECOND, independent NUMBER-kind steering pass
        // inside distributeFields (steerNumberKindByScopeProduct), right after the reach-deficit
        // NUMBER-kind steering above.
        // Its OWN purpose-keyed stream ("structure.scopeSteer") — NEVER the shared "structure" stream —
        // is what makes a zero-swap profile (depth-shaped or compact) truly byte-identical: a first version
        // reused `rnd` and drew from it even on a no-op run (sampleMultiset draws unconditionally, before the
        // swap decision), shifting every LATER "structure"-stream draw (FieldGen's declared NUMBER scales)
        // and, through that, which field {@code aggregateOperandOfScale}'s scale-matching later picked —
        // moving comp.reachProduct on a breadth-shaped profile with ZERO scopeProduct swaps to show for it.
        // Measured, not assumed: reverting to the shared stream reproduced the exact same drift.
        Distribution compScopeProductTarget = compIteration == null ? null : compIteration.scopeProduct();
        List<ValueKind> kinds = kindSequence(s.fieldKinds(), Math.max(0, s.fieldCount()));
        distributeFields(groups, kinds, s.fieldsPerGroup(), reachDemand, compScopeProductTarget,
                profile.computations().count(), rnd, SynthesisRandom.forPurpose(seed, "structure.scopeSteer"));
        // every repeatable group needs a NUMBER (the aggregate target that makes its hub aggregate-capable) —
        // swap one in from a number-rich group (preserves both the per-group counts and the kind histogram)
        ensureRepeatableNumbers(groups);

        // 4. render the plan through the typed builder (validity by construction), applying the declared-
        //    constraint axes: per-number scale, and a budget of string length/pattern constraints
        DocumentModelDraft.Builder b = DocumentModelDraft.named("SynthModel").locale("en_US");
        FieldGen fg = new FieldGen(s.constraints(), rnd);
        b.rootGroup(root.name, gb -> renderInto(gb, root, fg, naming));
        return b.build();
    }
    /** Sample a boolean arity (≥1) from the target distribution's buckets (deterministic per {@code rnd}). */
    private static int sampleInt(Distribution d, Random rnd) {
        return Math.max(1, sampleRaw(d, rnd));                  // ≥1 variant (arity/depth/reps)
    }

    /** A value sampled from the distribution's buckets (0 possible — for fieldsPerGroup, which has empty groups). */
    private static int sampleRaw(Distribution d, Random rnd) {
        if (d.n() == 0 || d.buckets().isEmpty()) {
            return 0;
        }
        int total = d.buckets().values().stream().mapToInt(Integer::intValue).sum();
        int r = rnd.nextInt(total);
        for (var e : d.buckets().entrySet()) {
            r -= e.getValue();
            if (r < 0) {
                return Math.min(d.max(), bucketValue(e.getKey(), rnd));
            }
        }
        return d.median();
    }

    /**
     * Sample from the recorded order-statistics (min/median/p90/max), keeping the mass at the median for a
     * sharply-peaked axis (number scale is {@code median == p90} in real models). Uniform-in-bucket sampling
     * smears scale across 2..3, which inflates scale MISMATCHES between a computed field and its aggregate
     * operand — forcing a scale-fix {@code RoundUp/Down} on nearly every computation and overcounting the
     * ARITHMETIC construct (Rounding is tagged ARITHMETIC). Anchoring on the quantiles keeps most numbers at the
     * mode, so a same-scale {@code Sum} needs no round. Used for {@code numberScale}.
     */
    private static int sampleByQuantile(Distribution d, Random rnd) {
        if (d.n() == 0) {
            return 0;
        }
        int r = rnd.nextInt(d.n());
        if (r < d.n() / 2) {                                 // lower half → the median, a few excursions to min
            return rnd.nextInt(8) == 0 ? d.min() : d.median();
        }
        if (r < d.n() * 9 / 10) {                            // median..p90 band
            return uniformInclusive(d.median(), d.p90(), rnd);
        }
        return uniformInclusive(d.p90(), d.max(), rnd);      // the upper tail (p90..max)
    }

    private static int uniformInclusive(int lo, int hi, Random rnd) {
        return hi <= lo ? lo : lo + rnd.nextInt(hi - lo + 1);
    }

    /** A value sampled uniformly within a {@link Distribution} bucket ({@code "1"} or {@code "lo-hi"}) — so the
     *  tail (p90/max) is reached, not just each bucket's low bound. Capped at the distribution's max by the caller. */
    private static int bucketValue(String label, Random rnd) {
        int dash = label.indexOf('-');
        try {
            if (dash < 0) {
                return Integer.parseInt(label);
            }
            int lo = Integer.parseInt(label.substring(0, dash));
            int hi = Integer.parseInt(label.substring(dash + 1));
            return lo + rnd.nextInt(hi - lo + 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
    /**
     * A {@code count}-sized multiset of values reproducing {@code d}'s bucket PROPORTIONS via largest-remainder
     * (not i.i.d. random draws — the house style for a sharply-peaked/heavy-tailed target, MODEL-SYNTHESIS-
     * FIDELITY-LEDGER "Sampling"): each bucket gets its exact proportional share of {@code count} (largest
     * remainder handing out the rounding leftovers), then one value per share is drawn from within that bucket
     * ({@link #bucketValue}). This is what lets a placement match a target DISTRIBUTION's shape deterministically
     * instead of only its mean.
     */
    private static List<Integer> sampleMultiset(Distribution d, int count, Random rnd) {
        if (count <= 0 || d == null || d.n() == 0 || d.buckets().isEmpty()) {
            return List.of();
        }
        int sum = d.buckets().values().stream().mapToInt(Integer::intValue).sum();
        List<String> labels = new ArrayList<>(d.buckets().keySet());
        double[] rem = new double[labels.size()];
        int[] share = new int[labels.size()];
        int assigned = 0;
        for (int i = 0; i < labels.size(); i++) {
            double exact = (double) d.buckets().get(labels.get(i)) / sum * count;
            share[i] = (int) Math.floor(exact);
            rem[i] = exact - share[i];
            assigned += share[i];
        }
        while (assigned < count) {
            int best = 0;
            for (int i = 1; i < labels.size(); i++) {
                if (rem[i] > rem[best]) {
                    best = i;
                }
            }
            share[best]++;
            rem[best] = -1;
            assigned++;
        }
        List<Integer> values = new ArrayList<>(count);
        for (int i = 0; i < labels.size(); i++) {
            for (int j = 0; j < share[i]; j++) {
                values.add(Math.min(d.max(), bucketValue(labels.get(i), rnd)));
            }
        }
        return values;
    }
    // ----------------------------------------------------------------------------------------------
    // group tree
    // ----------------------------------------------------------------------------------------------

    /**
     * Build the group tree AND mark repeatability jointly, maximizing <b>aggregate-capable hubs</b>: most
     * repeatable groups become the child of their own non-repeatable hub, so a computation/rule scoped at a hub
     * can aggregate over the repeatable child. One nested repeatable chain reaches both the repeatable-nesting
     * depth and the overall max nesting depth. Group count, repeatable count, and both depths are honored exactly.
     */
    private static GroupPlan buildTree(int groupCount, int maxDepth, SynthesisProfile.Repeatability rep, Random rnd,
            Naming naming) {
        GroupPlan root = new GroupPlan(naming.group());
        root.depth = 1;
        int[] left = {groupCount - 1};                              // groups still to place (root placed)
        int repsLeft = Math.min(rep.repeatableGroupCount(), Math.max(0, groupCount - 1));
        int nestDepth = Math.max(1, rep.maxRepeatableNestingDepth());
        Distribution repsDist = rep.maxRepetitions();
        List<GroupPlan> reps = new ArrayList<>();                   // every repeatable group, for per-row filler attach

        // A deliberate maxRepetitions multiset (largest-remainder, not i.i.d. per slot — kills the
        // cross-seed variance of whether a big value happens to land next to another; see
        // MODEL-SYNTHESIS-FIDELITY-SPEC). The FALLBACK pool for root sizing when chainProductByDepth is absent (an older
        // profile) — see rootSizePool below, which is now the primary source.
        List<Integer> repSizePool = new ArrayList<>(sampleMultiset(repsDist, repsLeft, rnd));

        // Branching retune (MODEL-SYNTHESIS-FIDELITY-SPEC): replaces the "one linear stack per
        // hub, depth 2..4 favouring 3" heuristic. Verified against the real target: nestingDepth is 24 reps at
        // depth 1 carrying 99 nested reps (a ~4-per-root fan) plus 2 reaching maxRepeatableNestingDepth — NOT
        // depth spread thinly across ~50 shallow linear stacks. A linear stack per hub structurally cannot
        // reach that shape (few roots, each with SEVERAL direct nested children — chainProduct mass
        // concentrates at root x one child, not root x child x grandchild), which is why the old heuristic's
        // chainProduct median sat ~2.7x low. Fixed by splitting reps into ROOT (no repeatable ancestor) and
        // DEEP (reaches maxRepeatableNestingDepth) counts straight from nestingDepth's own histogram
        // (depthSplit), routing the rare DEEP share through a linear chain as before, and FANNING the
        // remaining root/mid share out — each root gets several direct nested children, sized from
        // siblingBranchFan's shape (branchFans) rather than a coin-flipped single extra branch.
        int[] depthSplit = depthSplit(rep.nestingDepth(), rep.nestingDepthExact(), repsLeft, nestDepth);
        int deepChains = depthSplit[1];
        int rootTotal = depthSplit[0];
        int nestedCount = Math.max(0, repsLeft - rootTotal - deepChains);

        // chainProductByDepth wiring (MODEL-SYNTHESIS-FIDELITY-SPEC): replaces three separate
        // guesses with the metric built exactly for this. ROOT sizing no longer treats a chain-product target
        // as inapplicable and falls back to a plain maxRepetitions draw (an earlier stopgap) — chainProductByDepth's
        // "1" class IS the measured target distribution for a root's own size (a depth-1 position's chainProduct
        // is exactly its own size), so `rootSizePool` draws from it directly. The "2-3" class replaces the old
        // "the whole chainProduct distribution scaled down" approximation for fan-children targets (which mixed
        // in root-scale values the fan children never see) with the clean, dedicated nested-position sub-
        // distribution. The "4+" class replaces the old "reserve the largest overall chainProduct draws" proxy
        // for the deep chains' final level with the actual measured multi-factor-compound target. All three
        // fall back to the earlier branching-retune approximations (null-tolerant) when profiling an older profile
        // lacking the metric.
        Map<String, Distribution> byDepth = rep.chainProductByDepth();
        Distribution rootDist = byDepth == null ? null : byDepth.get("1");
        Distribution midDist = byDepth == null ? null : byDepth.get("2-3");
        Distribution deepDist = byDepth == null ? null : byDepth.get("4+");
        List<Integer> rootSizePool = rootDist == null ? null : new ArrayList<>(sampleMultiset(rootDist, rootTotal, rnd));

        // ASCENDING — this queue now serves only the RARE deep-chain middle levels below (step 1); the
        // DOMINANT branching case (step 2) draws from whatever remains via feasibility-matched assignment, not
        // queue order, so the sort here only shapes what a middle-of-a-deep-chain level receives (a moderate
        // value is fine — its own final level gets the true tail from deepFinalTargets below).
        List<Long> midTargetList = new ArrayList<>((midDist != null
                ? sampleMultiset(midDist, nestedCount, rnd)
                : sampleMultiset(rep.chainProduct(), nestedCount, rnd)).stream().map(Long::valueOf).toList());
        midTargetList.sort(java.util.Comparator.naturalOrder());
        java.util.ArrayDeque<Long> chainTargets = new java.util.ArrayDeque<>(midTargetList);

        // the deep chains' final level compounds THREE-plus factors (root x child x grandchild x ...), so it —
        // and only it — is structurally capable of reaching the distribution's extreme upper tail (the target's
        // OWN max sits at the deepest bucket).
        java.util.ArrayDeque<Long> deepFinalTargets;
        if (deepDist != null) {
            deepFinalTargets = new java.util.ArrayDeque<>(
                    sampleMultiset(deepDist, deepChains, rnd).stream().map(Long::valueOf).toList());
        } else {
            // fallback (no chainProductByDepth): reserve the largest overall chainProduct draws — an
            // approximation, since it can only reach the tail of the COMBINED distribution, not the true
            // depth-4+ sub-distribution.
            List<Long> combined = new ArrayList<>(sampleMultiset(rep.chainProduct(), deepChains, rnd).stream()
                    .map(Long::valueOf).toList());
            combined.sort(java.util.Comparator.reverseOrder());
            deepFinalTargets = new java.util.ArrayDeque<>(combined);
        }
        int hubDepthForChain = Math.max(2, maxDepth - nestDepth);

        // 1. deep chains — the rare linear descent reaching maxRepeatableNestingDepth exactly (kept linear:
        //    branching only matters for the dominant mid-depth mass fanned out in step 2, not this thin tail).
        for (int c = 0; c < deepChains && repsLeft > 0 && nestDepth >= 2 && maxDepth >= nestDepth + 1
                && left[0] >= nestDepth + 1; c++) {
            GroupPlan cur = root;
            for (int d = 2; d <= hubDepthForChain && left[0] > 0; d++) {
                cur = child(cur, naming);
                left[0]--;
            }
            int chain = Math.min(nestDepth, Math.max(1, maxDepth - hubDepthForChain));
            long chainProduct = 1;
            for (int i = 0; i < chain && repsLeft > 0 && left[0] > 0; i++) {
                // the chain's first (root) element has no ancestor product to divide against — size it
                // plainly; its LAST element compounds the whole chain and draws from the reserved extreme
                // targets (deepFinalTargets) so the tail actually lands here, not on a two-factor fan child.
                int size = i == 0 ? rootRepSize(rootSizePool, repSizePool, repsDist, rnd)
                        : i == chain - 1 ? nextRepSize(chainProduct, deepFinalTargets, repSizePool, repsDist, rnd)
                                : nextRepSize(chainProduct, chainTargets, repSizePool, repsDist, rnd);
                chainProduct = Math.min(chainProduct * size, Integer.MAX_VALUE);
                cur = repeatableChild(cur, naming, size);
                reps.add(cur);
                repsLeft--;
                left[0]--;
            }
        }

        // 2. root hubs, each fanned out to several DIRECT nested children — the branching structure a linear
        //    stack cannot produce. For a shallow model (maxDepth < 3) the hub IS the root, so reps hang
        //    directly off root (fan then means siblings at the SAME level, still matching the shape).
        //
        //    A MEASURED fraction of consecutive roots (multiWayHubFraction, from siblingBranchFan) SHARE the
        //    previous root's hub instead of getting a fresh one — this is a SEPARATE branching decision from
        //    the nested fan above (siblings AT the root level, not nested descendants of one root), and it is
        //    what gives a computation/rule a common scope reaching TWO sibling repeatable branches
        //    (siblingAggregatePair, comp/rule parallelIteration) when the target's structure is shallow —
        //    without it every root gets an isolated singleton hub and no scope ever reaches two siblings.
        int hubDepth = Math.min(2, maxDepth - 1);                   // 2 for deep models; 1 (=root) when maxDepth==2
        double multiWayFraction = multiWayHubFraction(rep.siblingBranchFan());
        List<GroupPlan> hubs = new ArrayList<>();
        int rootCount = Math.max(0, depthSplit[0] - deepChains);
        // fan-budget per root (how many direct children it gets) and root SIZE are drawn INDEPENDENTLY — from
        // siblingBranchFan and chainProductByDepth["1"] respectively, two separate measured axes with no
        // measured JOINT between them. Which root ends up hosting the widest fan is therefore decided by
        // FEASIBILITY, not a hand-coded correlation: below, each "2-3"-class target is assigned to whichever
        // root makes the resulting child size land closest to a typical maxRepetitions value — a giant root
        // can only make that work for a LARGE target (a small one would force an unrealistically tiny child),
        // so it naturally ends up hosting the tail of the nested distribution once assignment is feasibility-
        // driven. This generalizes to any profile's shape: a flat/uniform target (no giant roots) simply has
        // no infeasible pairings to resolve, and the greedy match degenerates to "whichever root is still
        // open" — it does not bake in "giants get thin fans" as a rule (see MODEL-SYNTHESIS-FIDELITY-SPEC
        // — an inverse-rank / size-threshold version of this was tried and rejected: it reproduced THIS
        // target's numbers by encoding an unmeasured correlation, not by matching what the profile expresses).
        List<Integer> fans = branchFans(rep.siblingBranchFan(), rootCount, Math.max(0, repsLeft - rootCount), rnd);
        java.util.Collections.shuffle(fans, rnd);           // no order correlation with rootSizes below
        List<Integer> rootSizes = new ArrayList<>(rootCount);
        for (int i = 0; i < rootCount; i++) {
            rootSizes.add(rootRepSize(rootSizePool, repSizePool, repsDist, rnd));
        }

        // feasibility-matched target assignment: greedily hand each remaining "2-3"-class target (largest
        // first, so the hardest-to-place values get first pick of a root) to the root whose size makes the
        // implied child size (target / rootSize) closest in log-space to a typical maxRepetitions value —
        // never to a hard-coded threshold or rank.
        List<Long> remainingTargets = new ArrayList<>(chainTargets);
        remainingTargets.sort(java.util.Comparator.reverseOrder());
        double typicalChildLog = Math.log(Math.max(1, repsDist.median()));
        List<java.util.ArrayDeque<Long>> targetsForRoot = new ArrayList<>(rootCount);
        int[] capacity = new int[rootCount];
        for (int i = 0; i < rootCount; i++) {
            targetsForRoot.add(new java.util.ArrayDeque<>());
            capacity[i] = i < fans.size() ? fans.get(i) : 0;
        }
        for (long target : remainingTargets) {
            int best = -1;
            double bestDiff = Double.MAX_VALUE;
            for (int i = 0; i < rootCount; i++) {
                if (capacity[i] <= 0) {
                    continue;
                }
                double quotientLog = Math.log(Math.max(1.0, (double) target / Math.max(1, rootSizes.get(i))));
                double diff = Math.abs(quotientLog - typicalChildLog);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = i;
                }
            }
            if (best < 0) {
                break;                                      // every root's fan budget is already spent
            }
            targetsForRoot.get(best).add(target);
            capacity[best]--;
        }

        GroupPlan prevHub = null;
        for (int i = 0; i < rootCount && repsLeft > 0 && left[0] > 0; i++) {
            GroupPlan hub;
            if (hubDepth < 2) {
                hub = root;                                         // maxDepth==2: reps hang directly off root
            } else if (prevHub != null && (left[0] < repsLeft + 1 || rnd.nextDouble() < multiWayFraction)) {
                // share — either by choice, or because budget is too tight to AFFORD a fresh hub without
                // risking a later rep in this same loop (a "small-N
                // stochastic derivation" fix, see MODEL-SYNTHESIS-FIDELITY-SPEC compact-profile intake): a fresh
                // hub costs 1 EXTRA slot beyond the rep itself, but every remaining rep (this one plus repsLeft-1
                // more) needs AT LEAST 1 slot each regardless of sharing — so a fresh hub is only safe to
                // afford when left[0] has at least 1 slot of slack beyond that hard floor (left[0] >=
                // repsLeft + 1). The OLD guard (left[0] < 2) only looked one hub ahead, not at the WHOLE
                // remaining rep budget — on a small-N profile (few groups relative to many reps + hub
                // overhead) a run of "unlucky" fresh-hub coin flips could exhaust left[0] with reps still
                // unplaced, permanently undercounting repeatableGroupCount (confirmed on a compact profile,
                // where the miss showed up on half the seeds pre-fix). A no-op for a generous
                // budget (both real depth-shaped and breadth-shaped profiles have groupCount far exceeding
                // repeatableGroupCount, so left[0] never approaches this floor there — reverified
                // byte-identical after this change).
                hub = prevHub;
            } else {
                hub = child(root, naming);
                left[0]--;
                hubs.add(hub);
            }
            int size = rootSizes.get(i);
            GroupPlan rootRep = repeatableChild(hub, naming, size);
            reps.add(rootRep);
            repsLeft--;
            left[0]--;
            prevHub = hub;

            java.util.ArrayDeque<Long> myTargets = targetsForRoot.get(i);
            int fan = i < fans.size() ? fans.get(i) : 0;
            for (int b = 0; b < fan && repsLeft > 0 && left[0] > 0 && rootRep.depth < maxDepth; b++) {
                int childSize = sizeForTarget(myTargets.poll(), size, repSizePool, repsDist, rnd);
                reps.add(repeatableChild(rootRep, naming, childSize));
                repsLeft--;
                left[0]--;
            }
        }

        // 3. leftover reps (rounding slack, or the fan/depth split under-consuming against geometry limits) —
        //    plain single-level stacks, keeping repeatableGroupCount exact regardless of geometry limits: a
        //    fresh hub when there's room for one, otherwise fall back to an EXISTING hub (or root) rather than
        //    silently dropping the rep to a plain filler group for want of 1 extra slot.
        while (repsLeft > 0 && left[0] > 0) {
            GroupPlan hub;
            if (hubDepth < 2) {
                hub = root;
            } else if (left[0] >= repsLeft + 1) {          // same lookahead floor as the root-hub loop above
                hub = child(root, naming);
                left[0]--;
                hubs.add(hub);
            } else {
                hub = hubs.isEmpty() ? root : hubs.get(hubs.size() - 1);
            }
            int size = rootRepSize(rootSizePool, repSizePool, repsDist, rnd);
            reps.add(repeatableChild(hub, naming, size));
            repsLeft--;
            left[0]--;
        }

        // 3. filler non-repeatable groups, WEIGHTED so hubs get ~2/3 and repeatable groups ~1/3. Repeatable groups
        //    need a non-repeatable child to host a per-row GroupFilled (the real model's GROUP_FILL/FILTER are
        //    DOMINANTLY per-repeatable-row: rules.constructsByScope), but the COMPUTATION aggregates depend on the
        //    hubs' field mass — moving fillers wholesale onto reps starves that (comp AGGREGATE ~724→541). So each
        //    hub is weighted twice and each eligible rep once, in one round-robin within the depth cap. This is the
        //    rule/computation entanglement, balanced rather than resolved (both share the fixed group budget).
        List<GroupPlan> attach = new ArrayList<>();
        for (GroupPlan h : hubs) {
            attach.add(h);
            attach.add(h);                                          // hubs weighted 2× (computation-aggregate field mass)
        }
        for (GroupPlan r : reps) {
            if (r.depth < maxDepth) {                               // a child of r must stay within the depth cap
                attach.add(r);                                      // reps 1× (per-row GroupFilled targets)
            }
        }
        attach.add(root);
        for (int i = 0; left[0] > 0; i++) {
            child(attach.get(i % attach.size()), naming);
            left[0]--;
        }
        return root;
    }

    /** A non-repeatable child of {@code parent}, depth = parent.depth + 1. */
    private static GroupPlan child(GroupPlan parent, Naming naming) {
        GroupPlan g = new GroupPlan(naming.group());
        g.depth = parent.depth + 1;
        parent.children.add(g);
        return g;
    }

    /** A repeatable child of {@code parent} with the sampled max repetitions. */
    private static GroupPlan repeatableChild(GroupPlan parent, Naming naming, int maxReps) {
        GroupPlan g = child(parent, naming);
        g.repeatable = true;
        g.maxReps = maxReps;
        return g;
    }

    /** A per-group maximum repetitions sampled from the target distribution (≥2; falls back to 10 if empty). */
    private static int sampleReps(Distribution d, Random rnd) {
        return d.n() == 0 ? 10 : Math.max(2, sampleInt(d, rnd));
    }

    /**
     * The measured fraction of fan-bearing groups ({@code siblingBranchFan} >= 1) that reach TWO OR MORE
     * distinct repeatable branches (fan >= 2, a twin/multi-way aggregate hub) — reused to decide
     * whether consecutive ROOT positions share a hub (giving a scope two sibling repeatable branches) rather
     * than each getting an isolated singleton hub. Falls back to 0.3 when the axis is absent (an older
     * profile).
     */
    private static double multiWayHubFraction(Distribution siblingBranchFan) {
        if (siblingBranchFan == null || siblingBranchFan.n() == 0 || siblingBranchFan.buckets().isEmpty()) {
            return 0.3;
        }
        int multiWay = 0;
        for (var e : siblingBranchFan.buckets().entrySet()) {
            if (!"1".equals(e.getKey())) {                          // bucket "1" is exactly fan == 1 (single-branch)
                multiWay += e.getValue();
            }
        }
        return (double) multiWay / siblingBranchFan.n();
    }

    /**
     * Splits {@code totalReps} into {@code {root, deep}} counts matching {@code nestingDepth}'s
     * measured shape: {@code root} = reps with no repeatable ancestor (depth 1), {@code deep} = reps AT
     * {@code nestDepth} itself (the deepest level present, since {@code nestDepth} is the distribution's own
     * reported max). Everything else ("mid" — nested at least once but short of the deepest level) falls out
     * as {@code totalReps - root - deep} at the call site. Falls back to {@code {totalReps, 0}} (every rep a
     * root — the old flat/shallow behavior) when {@code nestingDepth} is absent (an older profile).
     *
     * <p>Prefers {@code nestingDepthExact} — the EXACT per-depth-value histogram — when present:
     * an exact count has no bucket to blend two different true depths into one, unlike {@code nestingDepth}'s
     * own log-scaled bucket. Falls back to bucket-LABEL matching ({@link Distribution#bucketFor}) when {@code
     * nestingDepthExact} is absent (an older profile carrying {@code nestingDepth} but not yet the exact
     * histogram) — itself the fix for an even older bug (a bucket-LOWER-BOUND test that only worked
     * when {@code nestDepth} coincided with a bucket boundary).
     *
     * <p><b>Why the bucket-label fallback itself was later found insufficient (fixed here):</b>
     * matching nestDepth's bucket by LABEL correctly finds it, but then treats the bucket's WHOLE count as
     * "reaches the max" — when that bucket blends multiple true depths (e.g. {@code "2-3"} holding both
     * depth-2 and depth-3 reps), this over-counts {@code deepChains} by routing every rep in the ambiguous
     * bucket through the LINEAR deep-chain construction, consuming the rep budget before the BRANCHING
     * (fan-out) step ever runs — confirmed on a real target: a synthesized tree with 1 multi-way (fan≥2) hub
     * against the real target's 100, because {@code deepChains} was ~7× the true depth-{@code nestDepth}
     * count. {@code nestingDepthExact} closes this precisely, not just for this one target's bucket.
     */
    private static int[] depthSplit(Distribution nestingDepth, Map<String, Integer> nestingDepthExact,
            int totalReps, int nestDepth) {
        if (nestingDepth == null || nestingDepth.n() == 0 || totalReps <= 0) {
            return new int[] {totalReps, 0};
        }
        int n = nestingDepth.n();
        int rootCount;
        int deepCount;
        if (nestingDepthExact != null && !nestingDepthExact.isEmpty()) {
            rootCount = nestingDepthExact.getOrDefault("1", 0);
            deepCount = nestingDepthExact.getOrDefault(String.valueOf(nestDepth), 0);
        } else {
            rootCount = nestingDepth.buckets().getOrDefault("1", 0);
            String deepLabel = Distribution.bucketFor(nestDepth);
            deepCount = "1".equals(deepLabel) ? 0 : nestingDepth.buckets().getOrDefault(deepLabel, 0);
        }
        int root = Math.min(totalReps, (int) Math.round((double) rootCount / n * totalReps));
        int deep = Math.min(totalReps - root, (int) Math.round((double) deepCount / n * totalReps));
        return new int[] {root, deep};
    }

    /**
     * {@code rootCount} branch-fan values (how many DIRECT nested repeatable children each root
     * gets) shaped like {@code siblingBranchFan}'s distribution but rescaled to sum to exactly {@code
     * deeperCount} — replaces the old {@code multiWayHubFraction} coin-flip (a single extra branch at a fixed
     * probability) with the real target's shape (a heavy-tailed fan: mostly 1, a few hubs reaching into the
     * dozens). Raw values are drawn once via {@link #sampleMultiset} (preserving the bucket proportions).
     * Falls back to an even round-robin spread when the axis is absent (an older profile) or degenerate
     * (all-zero draw).
     *
     * <p><b>The MULTI-WAY (fan ≥ 2) count is reserved a floor, not left to a single uniform
     * rescale (a "supply jitter" finding).</b> A plain proportional
     * rescale (one {@code scale} factor applied to every raw value) can shrink a raw-drawn multi-way root
     * (sampled fan ≥ 2) below 2 whenever {@code deeperCount < sum} — which specific roots that erosion hits
     * varies with the raw draw, so the REALIZED multi-way count (the twin-hub-reservation supply
     * {@code allocateNumberComputedFields}'s TWIN-HUB claim depends on) rode seed-to-seed jitter instead of
     * the measured {@code multiWayHubFraction} (empirically: a breadth-shaped profile's {@code comp}/{@code
     * rule.parallelIteration} floors dropped BELOW their earlier worst-seed values purely from a re-seed, no
     * lever/logic change at all — see MODEL-SYNTHESIS-FIDELITY-SPEC). Fix: the top {@code
     * wantMultiWay} raw-sorted roots (the measured fraction's implied COUNT, floor-clamped to what {@code
     * deeperCount} can afford) are rescaled within their OWN sub-budget with a floor of 2 each — guaranteeing
     * the multi-way count is a fixed, target-implied number, not a rescale side effect — while the REST
     * cohort keeps the original technique over its own remaining sub-budget (and can still organically
     * exceed the floor; only the erosion BELOW it is closed). No attempt to hit the target's absolute hub
     * count exactly (a downstream, not-solely-structural quantity) — only to stop the SUPPLY eroding below
     * the distribution's own implied share.
     */
    private static List<Integer> branchFans(Distribution siblingBranchFan, int rootCount, int deeperCount,
            Random rnd) {
        if (rootCount <= 0) {
            return List.of();
        }
        List<Integer> raw = siblingBranchFan == null || siblingBranchFan.n() == 0
                || siblingBranchFan.buckets().isEmpty() ? List.of()
                        : new ArrayList<>(sampleMultiset(siblingBranchFan, rootCount, rnd));
        long sum = raw.stream().mapToLong(Integer::longValue).sum();
        if (sum <= 0) {
            int[] even = new int[rootCount];
            for (int i = 0; i < deeperCount; i++) {
                even[i % rootCount]++;
            }
            List<Integer> out = new ArrayList<>(rootCount);
            for (int v : even) {
                out.add(v);
            }
            return out;
        }
        List<Integer> sorted = new ArrayList<>(raw);
        sorted.sort(java.util.Comparator.reverseOrder());          // largest branchers first — deterministic

        double multiWayFraction = multiWayHubFraction(siblingBranchFan);
        int wantMultiWay = Math.min(rootCount,
                Math.min(deeperCount / 2, (int) Math.round(rootCount * multiWayFraction)));

        int[] share = new int[rootCount];
        double[] rem = new double[rootCount];
        int assigned = 0;
        long topSum = 0;
        for (int i = 0; i < wantMultiWay; i++) {
            topSum += sorted.get(i);
        }
        if (wantMultiWay > 0) {
            int topBudget = Math.max(wantMultiWay * 2,
                    Math.min(deeperCount, (int) Math.round(deeperCount * (double) topSum / sum)));
            double topScale = topSum == 0 ? 0 : (double) topBudget / topSum;
            for (int i = 0; i < wantMultiWay; i++) {
                double exact = sorted.get(i) * topScale;
                share[i] = Math.max(2, (int) Math.floor(exact));   // floor of 2 — never erodes below multi-way
                rem[i] = Math.max(0.0, exact - share[i]);
                assigned += share[i];
            }
            // the floor-of-2 clamp can push the cohort's total OVER topBudget when the scale shrinks several
            // entries below 2 — trim back down from the LARGEST shares first (never below the floor) so the
            // REST cohort below isn't shorted.
            while (assigned > topBudget) {
                int worst = -1;
                for (int i = 0; i < wantMultiWay; i++) {
                    if (share[i] > 2 && (worst < 0 || share[i] > share[worst])) {
                        worst = i;
                    }
                }
                if (worst < 0) {
                    break;                                       // every multi-way root already at its floor
                }
                share[worst]--;
                assigned--;
            }
        }
        int restCount = rootCount - wantMultiWay;
        int restBudget = deeperCount - assigned;
        long restSum = sum - topSum;
        if (restCount > 0) {
            if (restSum <= 0 || restBudget <= 0) {
                for (int k = 0; k < restBudget; k++) {
                    share[wantMultiWay + k % restCount]++;
                }
            } else {
                double restScale = (double) restBudget / restSum;
                int restAssigned = 0;
                for (int i = wantMultiWay; i < rootCount; i++) {
                    double exact = sorted.get(i) * restScale;
                    share[i] = (int) Math.floor(exact);
                    rem[i] = exact - share[i];
                    restAssigned += share[i];
                }
                while (restAssigned < restBudget) {                // hand leftovers to the largest remainders
                    int best = wantMultiWay;
                    for (int i = wantMultiWay + 1; i < rootCount; i++) {
                        if (rem[i] > rem[best]) {
                            best = i;
                        }
                    }
                    share[best]++;
                    rem[best] = -1;
                    restAssigned++;
                }
            }
        }
        List<Integer> out = new ArrayList<>(rootCount);
        for (int v : share) {
            out.add(v);
        }
        return out;
    }

    /**
     * The measured fraction of aggregating computations (those with >=1 starred operand,
     * {@code aggregateStars} n) that are NESTED (>=2 stars) rather than a single-level flatten — the real
     * target's dominant shape (median 2). Falls back to 0 (single-star only, the original behavior) when
     * the axis is absent (an older profile).
     */
    /** The fraction of {@code d}'s mass OUTSIDE its {@code "1"} (trivial) bucket — the general form of the
     *  "how often does this axis exceed its default value" signal {@link #nestedAggregateFraction} and
     *  {@link #multiWayHubFraction} each compute for their own axis; used by {@link #matchByCaptureTarget}'s
     *  reach budget to size against {@code reachProduct}'s own measured non-trivial (>1) share. Falls back
     *  to {@code fallback} when the axis is absent (an older profile) or degenerate. */
    private static double nonTrivialFraction(Distribution d, double fallback) {
        if (d == null || d.n() == 0 || d.buckets().isEmpty()) {
            return fallback;
        }
        int nonTrivial = 0;
        for (var e : d.buckets().entrySet()) {
            if (!"1".equals(e.getKey())) {
                nonTrivial += e.getValue();
            }
        }
        return (double) nonTrivial / d.n();
    }
    /**
     * The next rep size for a chain position whose cumulative product so far is {@code parentProduct}:
     * divide the next (largest-remaining) chain-product TARGET by {@code parentProduct} and take the value
     * closest to that quotient, IN LOG SPACE, from the pre-drawn {@code repSizePool} (removing it — without
     * replacement). Falls back to i.i.d. {@link #sampleReps} once either pool is exhausted (should only happen
     * on a rep-count/target-count mismatch at the margins).
     */
    private static int nextRepSize(long parentProduct, java.util.ArrayDeque<Long> chainTargets,
            List<Integer> repSizePool, Distribution repsDist, Random rnd) {
        if (repSizePool.isEmpty()) {
            return sampleReps(repsDist, rnd);
        }
        Long target = chainTargets.poll();
        if (target == null) {
            return repSizePool.remove(repSizePool.size() - 1);
        }
        return sizeForTarget(target, parentProduct, repSizePool, repsDist, rnd);
    }

    /**
     * {@link #nextRepSize}'s quotient-match core, taking {@code target} directly instead of
     * polling a shared queue: divide {@code target} by {@code parentProduct} and take the {@code repSizePool}
     * value closest to that quotient IN LOG SPACE (removing it — without replacement). Used by the
     * feasibility-matched branching assignment, where WHICH target a position receives is decided up front
     * (by a greedy match against candidate root sizes), not by queue order.
     */
    private static int sizeForTarget(Long target, long parentProduct, List<Integer> repSizePool,
            Distribution repsDist, Random rnd) {
        if (repSizePool.isEmpty()) {
            return sampleReps(repsDist, rnd);
        }
        if (target == null) {
            return repSizePool.remove(repSizePool.size() - 1);
        }
        double quotientLog = Math.log(Math.max(1.0, (double) target / Math.max(1, parentProduct)));
        int bestIdx = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < repSizePool.size(); i++) {
            double diff = Math.abs(Math.log(Math.max(1, repSizePool.get(i))) - quotientLog);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        return repSizePool.remove(bestIdx);
    }

    /**
     * A size for a ROOT repeatable position (no repeatable ancestor, so its OWN {@code chainProduct} equals
     * its own size — no ancestor product to divide a chain-product target against). Prefers {@code
     * rootSizePool} — {@code chainProductByDepth}'s {@code "1"} class, the measured target
     * distribution for exactly this position, since a root's chainProduct IS its own size — falling back to
     * the plain {@code repSizePool} (an earlier stopgap, for an older profile lacking the metric): handing a root
     * the (nested-scale-dominated) COMBINED chain-product target as if it applied to just that one position
     * would balloon it (see MODEL-SYNTHESIS-FIDELITY-SPEC).
     */
    private static int rootRepSize(List<Integer> rootSizePool, List<Integer> repSizePool, Distribution repsDist,
            Random rnd) {
        if (rootSizePool != null && !rootSizePool.isEmpty()) {
            return rootSizePool.remove(rootSizePool.size() - 1);
        }
        if (repSizePool.isEmpty()) {
            return sampleReps(repsDist, rnd);
        }
        return repSizePool.remove(repSizePool.size() - 1);
    }

    /**
     * Ensure every repeatable group holds at least one NUMBER (the aggregate target that makes its non-repeatable
     * parent aggregate-capable). For each repeatable group lacking one, promote a slot to NUMBER and, to keep the
     * global kind histogram AND per-group counts intact, demote a NUMBER slot in a number-rich donor group to the
     * kind we displaced — a pure swap of two kinds across groups.
     */
    private static void ensureRepeatableNumbers(List<GroupPlan> groups) {
        for (GroupPlan g : groups) {
            if (!g.repeatable || g.fields.isEmpty() || g.fields.contains(ValueKind.NUMBER)) {
                continue;
            }
            GroupPlan donor = null;                             // a group with ≥2 numbers (giving one up is safe)
            for (GroupPlan d : groups) {
                if (d != g && d.fields.stream().filter(k -> k == ValueKind.NUMBER).count() >= 2) {
                    donor = d;
                    break;
                }
            }
            if (donor == null) {
                continue;                                       // no safe donor (degenerate); leave as-is
            }
            ValueKind displaced = g.fields.get(0);
            g.fields.set(0, ValueKind.NUMBER);                  // this repeatable now has a number
            donor.fields.set(donor.fields.indexOf(ValueKind.NUMBER), displaced);  // donor absorbs the displaced kind
        }
    }

    // ----------------------------------------------------------------------------------------------
    // fields
    // ----------------------------------------------------------------------------------------------

    /** The kind of each of {@code total} fields, in the profile's proportions (largest-remainder rounding). */
    private static List<ValueKind> kindSequence(Map<String, Integer> histogram, int total) {
        List<ValueKind> out = new ArrayList<>();
        if (total == 0) {
            return out;
        }
        if (histogram.isEmpty()) {
            for (int i = 0; i < total; i++) {
                out.add(ValueKind.STRING);
            }
            return out;
        }
        int sum = histogram.values().stream().mapToInt(Integer::intValue).sum();
        // floor each share, track remainders, then hand out the leftover to the largest remainders
        record Share(ValueKind kind, int base, double rem) {}
        List<Share> shares = new ArrayList<>();
        int assigned = 0;
        for (var e : histogram.entrySet()) {
            double exact = (double) e.getValue() / sum * total;
            int base = (int) Math.floor(exact);
            shares.add(new Share(parseKind(e.getKey()), base, exact - base));
            assigned += base;
        }
        shares.sort((a, c) -> Double.compare(c.rem(), a.rem()));
        for (int i = 0; assigned < total; i++, assigned++) {
            Share sh = shares.get(i % shares.size());
            shares.set(i % shares.size(), new Share(sh.kind(), sh.base() + 1, sh.rem()));
        }
        // INTERLEAVE the kinds PROPORTIONALLY (error-diffusion / Sainte-Laguë) rather than emitting kind-blocks —
        // so every group gets a realistic MIX (numbers alongside strings/enums), not kind-homogeneous groups, and
        // the mix is even across the WHOLE sequence (a plain largest-remaining round-robin front-loads the
        // dominant kind). Kind-blocked/front-loaded output starved computations of non-number siblings
        // (preconditions) and starved rules of non-number error fields in shallow groups (scope + walk-ups).
        int[] remaining = new int[shares.size()];
        double[] acc = new double[shares.size()];
        double[] rate = new double[shares.size()];
        for (int i = 0; i < shares.size(); i++) {
            remaining[i] = shares.get(i).base();
            rate[i] = (double) shares.get(i).base() / total;    // this kind's share per emitted field
        }
        for (int emitted = 0; emitted < total; emitted++) {
            int best = -1;
            for (int i = 0; i < acc.length; i++) {
                acc[i] += rate[i];
                if (remaining[i] > 0 && (best < 0 || acc[i] > acc[best])) {
                    best = i;
                }
            }
            out.add(shares.get(best).kind());
            acc[best] -= 1;
            remaining[best]--;
        }
        return out;
    }

    /** Whether {@code gp}'s subtree reaches a repeatable group through non-repeatable children only — i.e. a
     *  computation/rule scoped at {@code gp} could aggregate ({@code Sum(<repeatable-list> …)}) over it. */
    private static boolean canReachRepeatable(GroupPlan gp) {
        for (GroupPlan c : gp.children) {
            if (c.repeatable || canReachRepeatable(c)) {
                return true;
            }
        }
        return false;
    }

    /** The DISTINCT repeatable branches reachable from {@code gp} through non-repeatable descent only — the
     *  plan-tree mirror of {@code SynthesisProfile}'s {@code siblingBranchFan}/{@code collectRepCells}: a group
     *  with ≥2 such branches is a multi-way (twin-hub-capable) hub. Does not descend INTO a repeatable
     *  branch once found — the star sits at that level, exactly as the profiled metric counts it. */
    private static void collectRepBranchesOfPlan(GroupPlan gp, List<GroupPlan> out) {
        for (GroupPlan c : gp.children) {
            if (c.repeatable) {
                out.add(c);
            } else {
                collectRepBranchesOfPlan(c, out);
            }
        }
    }

    /** Spread the kind list across groups; every LEAF group gets ≥1 field so no empty leaf is emitted. */
    private static void distributeFields(List<GroupPlan> groups, List<ValueKind> kinds, Distribution fpgDist,
            int reachDemand, Distribution targetScopeProduct, int compCount, Random rnd, Random scopeSteerRnd) {
        int total = kinds.size();
        int n = groups.size();
        int[] alloc = new int[n];
        int assigned = 0;
        // baseline: one field per LEAF group (never an empty leaf), capped at the budget
        for (int gi = 0; gi < n && assigned < total; gi++) {
            if (groups.get(gi).children.isEmpty()) {
                alloc[gi] = 1;
                assigned++;
            }
        }
        // spread the REMAINING fields by weights sampled from the target fieldsPerGroup distribution — so a few
        // groups get many (the 53-field tail) and others get 0, reproducing the SHAPE instead of a uniform ~F/G
        int remaining = total - assigned;
        // STRUCTURE-PHASE FEEDBACK: bias the field mass toward AGGREGATE-CAPABLE groups (those whose subtree
        // reaches a repeatable group through non-repeatable children) — so computations/rules scoped there can
        // form Sum/GroupFilled over the descendant. Without this the field tail lands on leaf groups and the
        // aggregate-capable internal groups stay field-poor (AGGREGATE/FILTER constructs ≈ 0). The multiset SHAPE
        // is preserved (same total + sampled counts); only WHICH groups receive the big allocations shifts.
        double[] weight = new double[n];
        double wsum = 0;
        for (int gi = 0; gi < n; gi++) {
            double w = sampleRaw(fpgDist, rnd);
            if (canReachRepeatable(groups.get(gi))) {
                w *= 12;                                        // pull the field tail onto aggregate-capable groups
            }
            weight[gi] = w;
            wsum += weight[gi];
        }
        if (remaining > 0 && wsum > 0) {
            double[] rem = new double[n];
            int given = 0;
            for (int gi = 0; gi < n; gi++) {
                double exact = weight[gi] / wsum * remaining;
                int add = (int) Math.floor(exact);
                alloc[gi] += add;
                rem[gi] = exact - add;
                given += add;
            }
            while (given < remaining) {                         // hand leftovers to the largest remainders
                int best = 0;
                for (int gi = 1; gi < n; gi++) {
                    if (rem[gi] > rem[best]) {
                        best = gi;
                    }
                }
                alloc[best]++;
                rem[best] = -1;
                given++;
            }
        } else {
            for (int k = 0; k < remaining; k++) {               // no weight signal → round-robin
                alloc[k % n]++;
            }
        }
        // CAP each group at the target's max fieldsPerGroup and redistribute the overflow — the aggregate-capable
        // bias otherwise piles the whole field tail onto one group (max ≫ target). Overflow goes to under-cap
        // groups, AGGREGATE-CAPABLE first, so the field mass SPREADS across many aggregate-capable groups (more
        // aggregating computations) while the fieldsPerGroup max stays at the target.
        int cap = Math.max(1, fpgDist.max());
        int overflow = 0;
        for (int gi = 0; gi < n; gi++) {
            if (alloc[gi] > cap) {
                overflow += alloc[gi] - cap;
                alloc[gi] = cap;
            }
        }
        List<Integer> capable = new ArrayList<>();
        List<Integer> others = new ArrayList<>();
        for (int gi = 0; gi < n; gi++) {
            (canReachRepeatable(groups.get(gi)) ? capable : others).add(gi);
        }
        for (List<Integer> tier : List.of(capable, others)) {
            int guard = 0;
            while (overflow > 0 && !tier.isEmpty() && guard++ < overflow + tier.size() * cap + 1) {
                boolean placed = false;
                for (int gi : tier) {
                    if (overflow <= 0) {
                        break;
                    }
                    if (alloc[gi] < cap) {
                        alloc[gi]++;
                        overflow--;
                        placed = true;
                    }
                }
                if (!placed) {
                    break;                                      // this tier is saturated at the cap
                }
            }
        }
        // Twin-hub QUALIFICATION
        // steering: {@code ensureRepeatableNumbers} (below, in {@code plan()}) guarantees a NUMBER for any
        // NON-EMPTY repeatable group, but explicitly SKIPS an empty one — so a multi-way hub (fan ≥ 2, on
        // the REALIZED tree — ground truth, not a plan target) whose sibling branches drew alloc=0 here can
        // never qualify for {@code siblingAggregatePair}, no matter how faithfully {@code branchFans}
        // preserved the raw fan≥2 count. Measured: qualified twin-hub supply DROPPED at some seeds even as
        // the raw multi-way count rose — qualification is a JOINT product of the fan structure
        // (built in {@code buildTree}, before any field exists) and this kind-placement pass, two
        // independently-random phases, so it can only be fixed HERE, the one phase that can see both the
        // hub structure and the field allocation. For each multi-way hub, guarantee its two LARGEST-alloc
        // sibling branches each carry ≥1 slot — stealing from an UNRELATED donor (never another hub's own
        // designated branch) — so the TOTAL (fieldCount/fieldsPerGroup shape, EXACT/gated axes) never
        // moves; only WHICH group receives a slot shifts, the same joint the aggregate-capable bias above
        // already steers.
        Map<GroupPlan, Integer> indexOf = new java.util.HashMap<>();
        for (int gi = 0; gi < n; gi++) {
            indexOf.put(groups.get(gi), gi);
        }
        List<List<GroupPlan>> designatedPairs = new ArrayList<>();
        Set<GroupPlan> reservedBranches = new java.util.LinkedHashSet<>();
        for (GroupPlan g : groups) {
            List<GroupPlan> branches = new ArrayList<>();
            collectRepBranchesOfPlan(g, branches);
            if (branches.size() < 2) {
                continue;
            }
            List<GroupPlan> byAlloc = new ArrayList<>(branches);
            byAlloc.sort(java.util.Comparator.comparingInt((GroupPlan b) -> alloc[indexOf.get(b)]).reversed());
            List<GroupPlan> pair = byAlloc.subList(0, 2);
            designatedPairs.add(pair);
            reservedBranches.addAll(pair);
        }
        for (List<GroupPlan> pair : designatedPairs) {
            for (GroupPlan b : pair) {
                int bi = indexOf.get(b);
                if (alloc[bi] > 0) {
                    continue;
                }
                int donor = -1;
                int donorAlloc = -1;
                for (int gi = 0; gi < n; gi++) {
                    if (alloc[gi] > 1 && !reservedBranches.contains(groups.get(gi)) && alloc[gi] > donorAlloc) {
                        donor = gi;
                        donorAlloc = alloc[gi];
                    }
                }
                if (donor < 0) {
                    break;                                      // no slack anywhere — leave this branch unqualified
                }
                alloc[donor]--;
                alloc[bi]++;
            }
        }
        // lay the kind sequence into the groups per their allocation
        int idx = 0;
        for (int gi = 0; gi < n; gi++) {
            for (int j = 0; j < alloc[gi] && idx < total; j++) {
                groups.get(gi).fields.add(kinds.get(idx++));
            }
        }
        while (idx < total) {                                    // safety: any residue (rounding) → the root
            groups.get(0).fields.add(kinds.get(idx++));
        }

        // Bias NUMBER-kind ASSIGNMENT (not volume — that's
        // already biased above) toward aggregate-capable (hub) groups — the fieldChainProduct signal's
        // noted consumer (MODEL-SYNTHESIS-FIDELITY-SPEC "field-mass placement bias"). Kind assignment above is laid
        // UNIFORMLY across every group's allocation regardless of position, so a hub group gets NUMBER
        // only in exact proportion to NUMBER's OVERALL share — measured on a depth-shaped profile: the
        // reservation-eligible aggregate-capable pool can cap far below the demand of reach-carrying
        // computations, a ceiling no downstream reservation-mix change can move (see
        // MODEL-SYNTHESIS-FIDELITY-SPEC).
        //
        // DEFICIT-based, demand-sized — NOT an unconditional boost (measured: an unconditional
        // "hub groups get 2× NUMBER share" variant fixed the depth-shaped profile but OVERSHOT the
        // breadth-shaped profile's much smaller reach demand — its realized reachProduct median blew past
        // its target several-fold and its parallel-iteration exactness regressed, because flooding hubs
        // with NUMBER also multiplies the render-time opportunistic aggregate rolls). {@code reachDemand}
        // is the measured demand (nonTrivialFraction(comp.reachProduct) × comp count); only the DEFICIT
        // (demand − current hub-NUMBER supply) is stolen, so a target whose natural supply already meets
        // its own demand — or wants none at all — is left completely untouched: zero swaps, layout
        // byte-identical.
        // Swaps are DETERMINISTIC (round-robin across hub groups in index order, no new RNG draws — never
        // perturbs the "structure" stream's other consumers) and preserve each group's OWN field COUNT
        // and the GLOBAL kind histogram exactly (a pure kind-relabeling between two slots, mirroring the
        // twin-hub COUNT-steal above but for KIND instead of count). Non-hub NON-repeatable donors are
        // preferred; repeatable donors are a fallback ({@code ensureRepeatableNumbers} restores every
        // non-empty repeatable group's own NUMBER floor right after this pass).
        if (reachDemand > 0) {
            List<Integer> hubIdx = new ArrayList<>();
            List<Integer> donorNonRep = new ArrayList<>();
            List<Integer> donorRep = new ArrayList<>();
            long hubNumberSupply = 0;
            for (int gi = 0; gi < n; gi++) {
                GroupPlan g = groups.get(gi);
                if (canReachRepeatable(g)) {
                    hubIdx.add(gi);
                    hubNumberSupply += g.fields.stream().filter(k -> k == ValueKind.NUMBER).count();
                } else {
                    (g.repeatable ? donorRep : donorNonRep).add(gi);
                }
            }
            int deficit = (int) Math.min(Integer.MAX_VALUE, reachDemand - hubNumberSupply);
            List<Integer> donorOrder = new ArrayList<>(donorNonRep);
            donorOrder.addAll(donorRep);
            boolean progress = true;
            while (deficit > 0 && progress && !hubIdx.isEmpty()) {
                progress = false;
                for (int gi : hubIdx) {
                    if (deficit <= 0) {
                        break;
                    }
                    GroupPlan g = groups.get(gi);
                    int targetSlot = -1;
                    for (int s = 0; s < g.fields.size(); s++) {
                        if (g.fields.get(s) != ValueKind.NUMBER) {
                            targetSlot = s;
                            break;
                        }
                    }
                    if (targetSlot < 0) {
                        continue;                               // this hub is already all-NUMBER
                    }
                    int donorGi = -1;
                    int donorSlot = -1;
                    for (int dgi : donorOrder) {
                        List<ValueKind> donorFields = groups.get(dgi).fields;
                        for (int s = 0; s < donorFields.size(); s++) {
                            if (donorFields.get(s) == ValueKind.NUMBER) {
                                donorGi = dgi;
                                donorSlot = s;
                                break;
                            }
                        }
                        if (donorGi >= 0) {
                            break;
                        }
                    }
                    if (donorGi < 0) {
                        deficit = 0;                            // no donor NUMBER left anywhere — stop
                        break;
                    }
                    ValueKind swappedOut = g.fields.get(targetSlot);
                    g.fields.set(targetSlot, ValueKind.NUMBER);
                    groups.get(donorGi).fields.set(donorSlot, swappedOut);
                    deficit--;
                    progress = true;
                }
            }
        }
        steerNumberKindByScopeProduct(groups, targetScopeProduct, compCount, scopeSteerRnd);
    }

    /**
     * Supply-side scope planning (existing metrics
     * only): {@code comp.scopeProduct.median} is decided when computed-field slots are PLACED, not when
     * operations are matched — several independently-designed matching-layer attempts
     * all failed with the same signature (regressing {@code comp.parallelIteration} without moving
     * {@code scopeProduct}), because they all reordered candidates within the reservation-eligible pool at
     * MATCH time. This steers the SUPPLY instead, at field-slot PLANNING time (right after the reach-deficit
     * NUMBER-kind steering above, same invariants): sample a scope-product multiset from the measured
     * {@code comp.iteration.scopeProduct} distribution (largest-remainder, {@link #sampleMultiset} — the
     * same technique {@link #quantileMatchByScopeProduct} already uses for MATCHING, reused here only for
     * its distribution-shaped SAMPLING), nearest-match each sampled value to the GROUP whose own scope-
     * product (via {@link #computeGroupScopeProducts}, the plan-side mirror of {@link #chainProductOf})
     * is closest, and ensure each matched group carries ≥1 NUMBER — stealing the deficit from a donor
     * group outside the matched set. Donors are NEVER hub-capable ({@link #canReachRepeatable}) AND NEVER
     * repeatable themselves — {@code canReachRepeatable} alone missed a LEAF repeatable (a twin-hub
     * sibling branch with no repeatable descendant of its own reports {@code canReachRepeatable = false},
     * so a first cut excluding only hub-capable groups still measurably regressed both
     * {@code comp.maxCascadeDepth} and {@code comp.parallelIteration} on the real breadth-shaped gate by raiding
     * exactly those branches). Among the safe remainder, the group with the FEWEST numbers goes first —
     * never the group holding the largest same-group run (cascade's own selection criterion). No matcher
     * touched, no candidate order changed anywhere downstream — only WHICH groups' fields are NUMBER
     * shifts.
     *
     * <p>Degrades to zero swaps whenever the target distribution is essentially trivial (mostly
     * scope-product 1, e.g. a depth-shaped or compact profile): the nearest-match for a target of 1 lands on
     * whichever shallow
     * group is already closest — which, since shallow groups dominate the field-mass bias above, already
     * carries a NUMBER far more often than not, so {@code needy} ends up empty and nothing moves. Preserves
     * every group's OWN field COUNT and the GLOBAL kind histogram exactly, same as every other steering
     * pass in this method. Draws from its OWN purpose-keyed stream ({@code "structure.scopeSteer"}, see
     * the {@code plan()} call site) — NEVER the shared {@code "structure"} stream {@code buildTree}/
     * {@code FieldGen} also draw from — so a genuinely zero-swap target degrades to truly byte-identical
     * output, not merely an unchanged kind layout with a shifted downstream RNG state (measured: reusing
     * the shared stream left the kind layout untouched but still moved {@code comp.reachProduct} on a
     * breadth-shaped profile, via {@code FieldGen}'s shifted NUMBER-scale draws changing which field {@code
     * aggregateOperandOfScale}'s scale-matching later preferred).
     */
    private static void steerNumberKindByScopeProduct(List<GroupPlan> groups, Distribution targetScopeProduct,
            int compCount, Random rnd) {
        // A target whose OWN median is trivial (1 — e.g. a depth-shaped or compact profile) "already
        // matches" by definition, so this returns BEFORE any RNG draw (not merely before any swap) — true
        // byte-identical degrade, not just an unchanged kind layout. A finer nonTrivialFraction-based
        // demand (matching the reach-deficit steering's OWN technique) still measurably moved the
        // depth-shaped profile's scopeProduct RAW values (its median is 1, but its
        // distribution's non-trivial TAIL is not itself negligible) without changing any EXACT axis or
        // scored bucket outcome — an acceptable "scorecard-held" result, but this median gate is
        // the strictly safer bar (a target that doesn't ask for depth here gets none, full stop).
        if (targetScopeProduct == null || targetScopeProduct.n() == 0 || compCount <= 0
                || targetScopeProduct.median() <= 1) {
            return;
        }
        int n = groups.size();
        Map<GroupPlan, Long> scopeProductOf = new java.util.IdentityHashMap<>();
        computeGroupScopeProducts(groups.get(0), 1L, scopeProductOf);

        // distinctEstimate: a target with MORE computations than groups (typical for a breadth-shaped
        // profile) would otherwise exhaust the ENTIRE pool as "receivers" (one sample per group), leaving ZERO
        // donors — a first version measured exactly this: comp.scopeProduct never moved at all, no donor
        // ever available. Cap at HALF the groups (room for donors is non-negotiable) and size the demand by
        // nonTrivialFraction (the same technique the reach-deficit steering's reachDemand uses) — how many
        // computations need a genuinely non-trivial scope, not "one per computation."
        int distinctEstimate = Math.max(1, Math.min(n / 2,
                (int) Math.ceil(nonTrivialFraction(targetScopeProduct, 0.0) * compCount)));
        List<Integer> targets = sampleMultiset(targetScopeProduct, distinctEstimate, rnd);
        List<Integer> pool = new ArrayList<>();
        for (int gi = 0; gi < n; gi++) {
            pool.add(gi);
        }
        List<Integer> receivers = new ArrayList<>();
        for (int target : targets) {
            int bestIdx = -1;
            long bestDiff = Long.MAX_VALUE;
            for (int i = 0; i < pool.size(); i++) {
                long diff = Math.abs(scopeProductOf.get(groups.get(pool.get(i))) - (long) target);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestIdx = i;
                }
            }
            receivers.add(pool.remove(bestIdx));
            if (pool.isEmpty()) {
                break;
            }
        }
        List<Integer> needy = new ArrayList<>();
        for (int gi : receivers) {
            if (groups.get(gi).fields.stream().noneMatch(k -> k == ValueKind.NUMBER)) {
                needy.add(gi);
            }
        }
        if (needy.isEmpty()) {
            return;                                          // supply already matches — zero swaps
        }
        // Donors: NEVER a hub-capable group AND NEVER repeatable itself. canReachRepeatable(g) alone missed
        // the case that actually broke comp.parallelIteration on the real breadth-shaped gate: a LEAF repeatable
        // (a twin-hub sibling branch with no repeatable descendant of its OWN) reports canReachRepeatable
        // = false, so it slipped through as a donor and cost that branch its qualifying NUMBER. Excluding
        // {@code g.repeatable} too closes it — twin-hub's sibling pairing and cascade's largest-same-group
        // run both live outside this donor pool now. AND spend only SURPLUS numbers — a group with exactly
        // 1 is a single-source reach/aggregate operand elsewhere (measured: allowing single-number donors
        // still measurably cost comp.reachProduct's same-or-adjacent fraction on a breadth-shaped profile,
        // even with every other exclusion in place). Among the >=2 remainder, spend the group with the
        // FEWEST numbers first — never the group holding the largest same-group run, which could otherwise
        // itself become a threatened cascade cluster.
        List<Integer> donorOrder = new ArrayList<>();
        for (int gi : pool) {
            GroupPlan g = groups.get(gi);
            if (!g.repeatable && !canReachRepeatable(g)
                    && g.fields.stream().filter(k -> k == ValueKind.NUMBER).count() >= 2) {
                donorOrder.add(gi);
            }
        }
        donorOrder.sort(java.util.Comparator.comparingLong(
                gi -> groups.get(gi).fields.stream().filter(k -> k == ValueKind.NUMBER).count()));
        for (int recv : needy) {
            int targetSlot = -1;
            List<ValueKind> recvFields = groups.get(recv).fields;
            for (int s = 0; s < recvFields.size(); s++) {
                if (recvFields.get(s) != ValueKind.NUMBER) {
                    targetSlot = s;
                    break;
                }
            }
            if (targetSlot < 0) {
                continue;                                      // already all-NUMBER
            }
            int donorGi = -1;
            int donorSlot = -1;
            for (int dgi : donorOrder) {
                List<ValueKind> donorFields = groups.get(dgi).fields;
                // re-checked LIVE (not the pre-filter snapshot): a donor already picked once this pass may
                // have dropped to 1 — the surplus-only invariant must hold across the WHOLE pass, not just
                // at donorOrder's construction time.
                if (donorFields.stream().filter(k -> k == ValueKind.NUMBER).count() < 2) {
                    continue;
                }
                for (int s = 0; s < donorFields.size(); s++) {
                    if (donorFields.get(s) == ValueKind.NUMBER) {
                        donorGi = dgi;
                        donorSlot = s;
                        break;
                    }
                }
                if (donorGi >= 0) {
                    break;
                }
            }
            if (donorGi < 0) {
                break;                                          // no donor NUMBER left anywhere — stop
            }
            ValueKind swappedOut = recvFields.get(targetSlot);
            recvFields.set(targetSlot, ValueKind.NUMBER);
            groups.get(donorGi).fields.set(donorSlot, swappedOut);
        }
    }

    /** {@code node}'s OWN scope-product (ancestors-or-self, {@code node} included) into {@code out}, recursively
     *  over its children — the plan-side (pre-render) mirror of {@link #chainProductOf}, needed because a
     *  {@link GroupPlan} carries no parent pointer to walk upward from. */
    private static void computeGroupScopeProducts(GroupPlan node, long parentProduct, Map<GroupPlan, Long> out) {
        long own = node.repeatable
                ? Math.min(parentProduct * Math.max(1, node.maxReps), Integer.MAX_VALUE)
                : parentProduct;
        out.put(node, own);
        for (GroupPlan c : node.children) {
            computeGroupScopeProducts(c, own, out);
        }
    }

    // ----------------------------------------------------------------------------------------------
    // rendering (plan → typed draft)
    // ----------------------------------------------------------------------------------------------

    private static void renderInto(GroupBuilder gb, GroupPlan plan, FieldGen fg, Naming naming) {
        if (plan.repeatable) {
            gb.repeatable(plan.maxReps);
        }
        for (ValueKind kind : plan.fields) {
            addField(gb, kind, naming.field(), fg);
        }
        for (GroupPlan child : plan.children) {
            gb.group(child.name, cb -> renderInto(cb, child, fg, naming));
        }
    }

    private static void addField(GroupBuilder gb, ValueKind kind, String name, FieldGen fg) {
        switch (kind) {
            case STRING -> {
                // spend the string-constraint budgets (declared-constraint axes): first the length quota, then
                // the pattern quota, on disjoint fields — so stringsWithLength / stringsWithPattern are hit exactly
                if (fg.strLengthLeft > 0) {
                    fg.strLengthLeft--;
                    gb.stringField(name, sb -> sb.minLength(1).maxLength(64));
                } else if (fg.strPatternLeft > 0) {
                    fg.strPatternLeft--;
                    gb.stringField(name, sb -> sb.pattern("[A-Za-z0-9 ]{0,64}", "invalid"));
                } else {
                    gb.field(name, FieldKind.STRING);
                }
            }
            case NUMBER -> {
                // quantile-anchored (not uniform-in-bucket): keeps most numbers at the scale MODE so a computed
                // field and its aggregate cell share a scale → no scale-fix round → no ARITHMETIC overcount
                int scale = fg.numberScale.n() == 0 ? 0 : Math.max(0, sampleByQuantile(fg.numberScale, fg.rnd));
                gb.numberField(name, nb -> nb.fractionDigits(scale, scale));  // reproduce the numberScale axis
            }
            case DATE -> gb.field(name, FieldKind.DATE);
            case BOOLEAN -> gb.field(name, FieldKind.BOOLEAN);
            case CONFIRM -> gb.field(name, FieldKind.CONFIRM);
            case ENUMERATION -> {
                int card = fg.enumCard.n() == 0 ? 2 : Math.max(2, sampleInt(fg.enumCard, fg.rnd));  // per-enum spread
                gb.enumField(name, e -> {
                    for (int i = 1; i <= card; i++) {
                        e.value("V" + i);
                    }
                });
            }
            case DATE_TIME -> gb.dateTimeField(name, d -> d.format("yyyy-MM-dd'T'HH:mm:ss"));
            case TIME -> gb.timeField(name, d -> d.format("HH:mm:ss"));
            case DATE_FRAGMENT -> gb.dateFragmentField(name, d -> d.fragmentFormat("yyyy-MM"));
            case DATE_RANGE -> gb.dateRangeField(name, d -> d.format("dd.MM.yyyy").separator("-"));
            // CUSTOM (needs a predefined-type ref) and any kind the basic builder can't express fall back to
            // STRING for v1 — a small kind-histogram skew, noted rather than risking an invalid model.
            default -> gb.field(name, FieldKind.STRING);
        }
    }

    /** Mutable field-attribute context threaded through rendering — the enum-cardinality and number-scale
     *  distributions plus the remaining string length/pattern constraint budgets (declared-constraint axes). */
    private static final class FieldGen {
        final Distribution enumCard;
        final Distribution numberScale;
        int strLengthLeft;
        int strPatternLeft;
        final Random rnd;

        FieldGen(SynthesisProfile.Constraints c, Random rnd) {
            this.enumCard = c.enumCardinality();
            this.numberScale = c.numberScale();
            this.strLengthLeft = c.stringsWithLength();
            this.strPatternLeft = c.stringsWithPattern();
            this.rnd = rnd;
        }
    }

    // ----------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------

    private static ValueKind parseKind(String name) {
        try {
            return ValueKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ValueKind.STRING;
        }
    }

    private static List<GroupPlan> flatten(GroupPlan root) {
        List<GroupPlan> out = new ArrayList<>();
        flattenInto(root, out);
        return out;
    }

    private static void flattenInto(GroupPlan g, List<GroupPlan> out) {
        out.add(g);
        for (GroupPlan c : g.children) {
            flattenInto(c, out);
        }
    }

    /** A mutable node in the group plan built before rendering. */
    private static final class GroupPlan {
        final String name;
        final List<GroupPlan> children = new ArrayList<>();
        final List<ValueKind> fields = new ArrayList<>();
        boolean repeatable;
        int maxReps = 1;
        int depth = 1;                                          // 1-based path depth (root = 1); for the depth cap

        GroupPlan(String name) {
            this.name = name;
        }
    }

    /** Deterministic name counters (groups + fields) — synthetic, unique; themed naming is a later polish. */
    private static final class Naming {
        private int group;
        private int field;

        String group() {
            return "G" + (++group);
        }

        String field() {
            return "F" + (++field);
        }
    }
}
