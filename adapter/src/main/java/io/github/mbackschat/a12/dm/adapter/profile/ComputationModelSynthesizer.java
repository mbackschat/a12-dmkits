package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumValueLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;

/**
 * Computation-specific planning and rendering extracted mechanically from {@link ModelSynthesizer}.
 */
final class ComputationModelSynthesizer {

    private ComputationModelSynthesizer() {
    }

    static Reservation reserve(BoundModel model, SynthesisProfile.Computations target, long seed) {
        return reserveComputedFields(model, target, SynthesisRandom.forPurpose(seed, "computation.reserve"));
    }

    static Plan plan(BoundModel model, SynthesisProfile profile, Reservation reservation, long seed) {
        return planComputationFields(model, profile, reservation.fields(), seed);
    }

    static List<Computation> render(BoundModel model, Plan plan, Reservation reservation,
            SynthesisProfile.Computations target, long seed) {
        return renderComputations(model, plan, reservation.fields(), reservation.operandPlan(), target, seed);
    }

    // computations — count + dependency cascade
    // ----------------------------------------------------------------------------------------------


    private static final Set<ValueKind> COMPUTABLE_KINDS =
            Set.of(ValueKind.NUMBER, ValueKind.STRING, ValueKind.DATE, ValueKind.BOOLEAN, ValueKind.ENUMERATION);



    record Reservation(List<FieldInfo> fields, Map<String, String> operandPlan) {}

    private static Reservation reserveComputedFields(BoundModel m, SynthesisProfile.Computations target,
            Random rnd) {
        int count = target.count();
        if (count <= 0) {
            return new Reservation(List.of(), Map.of());
        }
        Set<String> repeatable = m.groups().filter(GroupInfo::repeating)
                .map(GroupInfo::path).collect(java.util.stream.Collectors.toSet());
        Map<String, List<GroupInfo>> childrenOf = childrenOf(m);
        Map<String, List<FieldInfo>> numbersAll = new LinkedHashMap<>();
        for (FieldInfo f : m.fields().toList()) {
            if (f.kind() == ValueKind.NUMBER) {
                numbersAll.computeIfAbsent(f.groupPath(), k -> new ArrayList<>()).add(f);
            }
        }
        // KEEP one shallowest number NON-computed PER TOP-LEVEL BRANCH (direct child of the root group) — a
        // guaranteed star-free, non-computed (cycle-free) operand for any computed field that would otherwise
        // be operandless (a leaf computed field whose whole ancestor spine is computed-number-only →
        // MVK_NO_CONST). A SINGLE globally-shallowest field is not enough: {@code ancestorNumber}'s fallback
        // only walks a scope's OWN ancestor chain, never a sibling subtree, so a kept number in one branch is
        // unreachable from every OTHER branch — invisible whenever the root group itself carries no fields of
        // its own (common in a wide/many-branch model: a breadth-shaped profile can have a
        // zero-field root fanning out into a hundred-plus branches). One guaranteed-reachable number per
        // branch fixes this regardless of how many parallel branches the tree has, without hand-coding a
        // topology.
        Map<String, FieldInfo> shallowestPerBranch = new LinkedHashMap<>();
        for (FieldInfo f : m.fields().filter(fi -> fi.kind() == ValueKind.NUMBER).toList()) {
            shallowestPerBranch.merge(topLevelBranchOf(f.groupPath()), f,
                    (a, b) -> depthOf(a.path()) <= depthOf(b.path()) ? a : b);
        }
        Set<String> keepNonComputed = shallowestPerBranch.values().stream()
                .map(FieldInfo::path).collect(java.util.stream.Collectors.toSet());

        // how many computed fields of each kind (older profile → all NUMBER); non-computable kinds fold to NUMBER
        Map<String, Integer> wantKinds = target.computedFieldKinds() == null || target.computedFieldKinds().isEmpty()
                ? Map.of("NUMBER", count) : target.computedFieldKinds();
        Map<ValueKind, Integer> want = new LinkedHashMap<>();
        for (var e : wantKinds.entrySet()) {
            ValueKind vk = parseKind(e.getKey());
            want.merge(COMPUTABLE_KINDS.contains(vk) ? vk : ValueKind.NUMBER, e.getValue(), Integer::sum);
        }

        // PROTECT a non-computed number per aggregate-capable repeatable group (parent non-repeatable → a hub can
        // Sum it) so RULE AGGREGATE/FILTER have operands. Rules can't reference a computed field, so on a
        // computation-heavy model the reservation otherwise eats every repeatable-reachable number and rule
        // aggregates starve (measured: 1 capable rule scope). Computations are unaffected — they aggregate computed
        // cells too. Bounded by the count slack (total numbers − 1 sink − NUMBER-computed target) so the reservation
        // still reaches its per-kind quotas and the computation count stays exact.
        int totalNumbers = numbersAll.values().stream().mapToInt(List::size).sum();
        int protectBudget = Math.max(0, totalNumbers - 1 - want.getOrDefault(ValueKind.NUMBER, 0) - 4);
        // Protect a non-computed number in each repeatable group, NESTED reps (parent repeatable) FIRST: a per-row
        // rule scoped at an outer rep aggregates over its inner rep's non-computed number — and the real model's
        // AGGREGATE/FILTER are dominantly per-repeatable-row (rules.constructsByScope). Top reps (parent
        // non-repeatable) come next, for the smaller perGroup-hub aggregate share. Bounded by the count slack.
        List<String> repsNestedFirst = new ArrayList<>(repeatable);
        repsNestedFirst.sort((a, b) -> Boolean.compare(
                !repeatable.contains(parentOf(a)), !repeatable.contains(parentOf(b))));  // nested (parent rep) first
        Set<String> protectedNums = new java.util.HashSet<>();
        for (String repPath : repsNestedFirst) {
            if (protectedNums.size() >= protectBudget) {
                break;
            }
            numbersAll.getOrDefault(repPath, List.of()).stream()
                    .filter(f -> !keepNonComputed.contains(f.path()))
                    .findFirst().ifPresent(f -> protectedNums.add(f.path()));
        }

        // pool of NUMBER fields, aggregate-capable first (the sink + protected cells excluded); pools of other kinds
        List<FieldInfo> numCapable = new ArrayList<>();
        List<FieldInfo> numRest = new ArrayList<>();
        Map<ValueKind, List<FieldInfo>> byKind = new LinkedHashMap<>();
        for (FieldInfo f : m.fields().toList()) {
            if (f.kind() == ValueKind.NUMBER) {
                if (keepNonComputed.contains(f.path()) || protectedNums.contains(f.path())) {
                    continue;
                }
                boolean agg = aggregateOperand(f.groupPath(), childrenOf, repeatable, numbersAll) != null;
                (agg ? numCapable : numRest).add(f);
            } else {
                byKind.computeIfAbsent(f.kind(), k -> new ArrayList<>()).add(f);
            }
        }
        // The shared, priority-ordered computation-budget allocator — twin-hub, cascade, GROUP_FILL,
        // capture-plan, in that fixed priority order, decided once in {@link #allocateNumberComputedFields}.
        // Replaces four inline sequential-claim
        // blocks with one named function; the priority order and every claim's own logic are UNCHANGED
        // (a pure extraction, verified behavior-preserving against the 8-seed gate on both real targets).
        Map<String, Integer> maxRepsByPath = m.groups().filter(GroupInfo::repeating)
                .collect(java.util.stream.Collectors.toMap(GroupInfo::path, GroupInfo::maxRepetitions));
        NumberAllocation numberAllocation = allocateNumberComputedFields(numCapable, numRest,
                want.getOrDefault(ValueKind.NUMBER, 0), target, childrenOf, repeatable, numbersAll,
                maxRepsByPath, rnd);
        byKind.put(ValueKind.NUMBER, numberAllocation.numbers());
        Map<String, String> operandPlan = numberAllocation.operandPlan();

        List<FieldInfo> out = new ArrayList<>();
        Set<String> taken = new java.util.HashSet<>();
        for (var e : want.entrySet()) {
            List<FieldInfo> pool = byKind.getOrDefault(e.getKey(), List.of());
            int n = Math.min(e.getValue(), pool.size());
            for (int i = 0; i < n && out.size() < count; i++) {
                out.add(pool.get(i));
                taken.add(pool.get(i).path());
            }
        }
        // top up to the target count from any remaining COMPUTABLE field (prefer NUMBER order) — keeps the
        // computation count on target even when a kind's field pool is short
        if (out.size() < count) {
            List<FieldInfo> topUp = new ArrayList<>(numberAllocation.numbers());
            byKind.forEach((k, v) -> { if (k != ValueKind.NUMBER && COMPUTABLE_KINDS.contains(k)) topUp.addAll(v); });
            for (FieldInfo f : topUp) {
                if (out.size() >= count) {
                    break;
                }
                if (taken.add(f.path())) {
                    out.add(f);
                }
            }
        }
        return new Reservation(out.size() > count ? out.subList(0, count) : out, operandPlan);
    }


    record NumberAllocation(List<FieldInfo> numbers, Map<String, String> operandPlan) {}


    private static NumberAllocation allocateNumberComputedFields(List<FieldInfo> numCapable,
            List<FieldInfo> numRest, int wantNumber, SynthesisProfile.Computations target,
            Map<String, List<GroupInfo>> childrenOf, Set<String> repeatable, Map<String, List<FieldInfo>> numbersAll,
            Map<String, Integer> maxRepsByPath, Random rnd) {
        List<FieldInfo> numCandidates = new ArrayList<>(numCapable);
        numCandidates.addAll(numRest);
        Distribution compScopeCapacity = target.iteration() == null ? null : target.iteration().scopeCapacity();

        int parallelTarget = target.iteration() == null ? 0 : target.iteration().parallelIteration();
        List<FieldInfo> twinReserved = new ArrayList<>();
        for (var it = numCandidates.iterator(); it.hasNext() && twinReserved.size() < parallelTarget; ) {
            FieldInfo f = it.next();
            if (siblingAggregatePair(f.groupPath(), childrenOf, repeatable, numbersAll) != null) {
                twinReserved.add(f);
                it.remove();
            }
        }
        int cascadeTarget = Math.max(1, target.maxCascadeDepth());
        Map<String, List<FieldInfo>> candidatesByGroup = new LinkedHashMap<>();
        for (FieldInfo f : numCandidates) {
            candidatesByGroup.computeIfAbsent(f.groupPath(), k -> new ArrayList<>()).add(f);
        }
        List<FieldInfo> cascadeCluster = candidatesByGroup.values().stream()
                .max(java.util.Comparator.comparingInt(List::size))
                .filter(g -> g.size() >= 2)
                .map(g -> new ArrayList<>(g.subList(0, Math.min(g.size(), cascadeTarget))))
                .orElseGet(ArrayList::new);
        numCandidates.removeAll(cascadeCluster);

        int groupFillCount = target.constructs() == null ? 0 : target.constructs().getOrDefault("GROUP_FILL", 0);
        List<FieldInfo> groupFillReserved = new ArrayList<>();
        for (var it = numCandidates.iterator(); it.hasNext() && groupFillReserved.size() < groupFillCount; ) {
            FieldInfo f = it.next();
            if (groupFillTarget(f.groupPath(), childrenOf, repeatable) != null) {
                groupFillReserved.add(f);
                it.remove();
            }
        }

        int remainingWant = Math.max(0, wantNumber - cascadeCluster.size() - twinReserved.size()
                - groupFillReserved.size());
        Distribution compListScanCapacity = target.iteration() == null ? null : target.iteration().listScanCapacity();
        CaptureMatch captureMatch = compListScanCapacity != null
                ? matchByCaptureTarget(numCandidates, remainingWant, compListScanCapacity, repeatable,
                        maxRepsByPath, numbersAll, childrenOf, rnd)
                : null;
        List<FieldInfo> matched = captureMatch != null ? captureMatch.fields()
                : quantileMatchByScopeCapacity(numCandidates, remainingWant, 1.0, compScopeCapacity,
                        repeatable, maxRepsByPath, numbersAll, childrenOf, rnd);
        Map<String, String> operandPlan = captureMatch != null ? captureMatch.operandPlan() : Map.of();

        List<FieldInfo> numbers = new ArrayList<>(cascadeCluster);
        numbers.addAll(twinReserved);
        numbers.addAll(groupFillReserved);
        numbers.addAll(matched);
        return new NumberAllocation(numbers, operandPlan);
    }

    // ----------------------------------------------------------------------------------------------
    // computations — the planner/executor split: {@code
    // planComputationFields} fully decides (the computed→computed edge graph, every budget's INITIAL
    // value) before {@code renderComputations}' per-field build loop ever runs.
    // ----------------------------------------------------------------------------------------------


    record Plan(Map<String, String> computedEdge, Map<String, List<FieldInfo>> numbersByGroup,
            Map<String, List<FieldInfo>> fieldsByGroup, Map<String, List<FieldInfo>> numbersAllByGroup,
            Map<String, List<GroupInfo>> childrenOf, Set<String> repeatable, int[] outerBudget, int[] constBudget,
            int[] parallelBudget, int[] groupFillBudget) {}


    private static Plan planComputationFields(BoundModel m, SynthesisProfile profile,
            List<FieldInfo> computed, long seed) {
        if (computed.isEmpty()) {
            return new Plan(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), new int[] {0},
                    new int[] {0}, new int[] {0}, new int[] {0});
        }
        Set<String> computedPaths = computed.stream().map(FieldInfo::path)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, List<FieldInfo>> numbersByGroup = new LinkedHashMap<>();
        Map<String, List<FieldInfo>> fieldsByGroup = new LinkedHashMap<>();
        Map<String, List<FieldInfo>> numbersAllByGroup = new LinkedHashMap<>();
        for (FieldInfo f : m.fields().toList()) {
            if (f.kind() == ValueKind.NUMBER) {
                numbersAllByGroup.computeIfAbsent(f.groupPath(), k -> new ArrayList<>()).add(f);
            }
            if (computedPaths.contains(f.path())) {
                continue;
            }
            fieldsByGroup.computeIfAbsent(f.groupPath(), k -> new ArrayList<>()).add(f);
            if (f.kind() == ValueKind.NUMBER) {
                numbersByGroup.computeIfAbsent(f.groupPath(), k -> new ArrayList<>()).add(f);
            }
        }
        Set<String> repeatable = m.groups().filter(GroupInfo::repeating)
                .map(GroupInfo::path).collect(java.util.stream.Collectors.toSet());
        Map<String, List<GroupInfo>> childrenOf = childrenOf(m);

        List<FieldInfo> numberComputed = computed.stream().filter(f -> f.kind() == ValueKind.NUMBER).toList();
        Map<String, String> computedEdge = planComputedEdges(numberComputed,
                profile.computations().maxCascadeDepth(), profile.computations().feedingOthers());

        int[] compOuterBudget = {profile.computations().iteration() == null ? 0
                : profile.computations().iteration().outerCorrelation()};
        Map<String, Integer> archetypes = profile.computations().archetypes();
        Map<String, Integer> cfk = profile.computations().computedFieldKinds();
        int targetConstant = archetypes == null ? 0 : archetypes.getOrDefault("CONSTANT", 0);
        int boolEnumReserve = cfk == null ? 0 : cfk.getOrDefault("BOOLEAN", 0) + cfk.getOrDefault("ENUMERATION", 0);
        int[] constBudget = {Math.max(0, targetConstant - boolEnumReserve)};
        int[] parallelBudget = {profile.computations().iteration() == null ? 0
                : profile.computations().iteration().parallelIteration()};
        int[] groupFillBudget = {profile.computations().constructs() == null ? 0
                : profile.computations().constructs().getOrDefault("GROUP_FILL", 0)};

        return new Plan(computedEdge, numbersByGroup, fieldsByGroup, numbersAllByGroup, childrenOf,
                repeatable, compOuterBudget, constBudget, parallelBudget, groupFillBudget);
    }


    private static List<Computation> renderComputations(BoundModel m, Plan cp, List<FieldInfo> computed,
            Map<String, String> operandPlan, SynthesisProfile.Computations target, long seed) {
        List<Computation> out = new ArrayList<>();
        for (int i = 0; i < computed.size(); i++) {
            FieldInfo c = computed.get(i);
            out.add(buildComputation(m, c, i, cp.computedEdge().get(c.path()), operandPlan.get(c.path()),
                    cp.numbersByGroup(), cp.fieldsByGroup(), cp.numbersAllByGroup(), cp.childrenOf(), cp.repeatable(),
                    target, cp.outerBudget(), cp.constBudget(), cp.parallelBudget(), cp.groupFillBudget(),
                    SynthesisRandom.forPurpose(seed, "computation.build:" + c.path())));
        }
        return out;
    }


    private record BudgetClaimResult(Computation claimed, Calculation mainOp) {}

    private enum PreconditionStyle {
        PROFILED_CONSTRUCTS,
        PLAIN_FIELD
    }


    private static BudgetClaimResult tryBudgetClaims(BoundModel m, Computation.Builder b, FieldInfo c,
            String computedOperand, String plannedOperand, boolean canSplit, List<FieldInfo> localFields,
            String aggFlat, List<String> localNumbers, int depth, int cScale, double filteredFraction,
            Map<String, List<FieldInfo>> fieldsByGroup, Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable, Map<String, List<FieldInfo>> numbersAllByGroup, int[] constBudget,
            int[] parallelBudget, int[] outerBudget, int[] groupFillBudget, PreconditionStyle preconditionStyle,
            Random rnd) {
        String scope = c.groupPath();
        boolean budgetEligible = computedOperand == null && plannedOperand == null;

        if (budgetEligible && c.kind() == ValueKind.NUMBER && canSplit && constBudget[0] > 0) {
            constBudget[0]--;
            Calculation lit = new NumberLiteral(java.math.BigDecimal.ZERO.setScale(cScale));
            Computation built = b.commonPrecondition(precondition(m, localFields, 0, rnd, scope, childrenOf,
                    repeatable, groupFillBudget, preconditionStyle)).calc(lit).build();
            return new BudgetClaimResult(built, null);
        }
        Calculation mainOp = kindOperation(m, c, null, aggFlat, localNumbers, depth, cScale, filteredFraction,
                fieldsByGroup, rnd);
        if (budgetEligible && c.kind() == ValueKind.NUMBER && canSplit && parallelBudget[0] > 0) {
            List<String> pair = siblingAggregatePair(scope, childrenOf, repeatable, numbersAllByGroup);
            if (pair != null) {
                parallelBudget[0]--;
                Computation built = b
                        .when(precondition(m, localFields, 0, rnd, scope, childrenOf, repeatable, groupFillBudget,
                                preconditionStyle))
                        .calc(operation(m, null, pair.get(0), localNumbers, depth, cScale, filteredFraction, rnd))
                        .when(precondition(m, localFields, 1, rnd, scope, childrenOf, repeatable, groupFillBudget,
                                preconditionStyle))
                        .calc(operation(m, null, pair.get(1), localNumbers, depth, cScale, filteredFraction, rnd))
                        .build();
                return new BudgetClaimResult(built, null);
            }
        }
        if (budgetEligible && outerBudget[0] > 0) {
            Condition outerPre = outerCorrelationClause(m, c, fieldsByGroup, repeatable);
            if (outerPre != null) {
                outerBudget[0]--;
                Computation built = b.commonPrecondition(outerPre).calc(mainOp).build();
                return new BudgetClaimResult(built, null);
            }
        }
        return new BudgetClaimResult(null, mainOp);
    }


    private static Computation buildComputation(BoundModel m, FieldInfo c, int idx, String computedOperand,
            String plannedOperand, Map<String, List<FieldInfo>> numbersByGroup,
            Map<String, List<FieldInfo>> fieldsByGroup, Map<String, List<FieldInfo>> numbersAllByGroup,
            Map<String, List<GroupInfo>> childrenOf, Set<String> repeatable, SynthesisProfile.Computations target,
            int[] outerBudget, int[] constBudget, int[] parallelBudget, int[] groupFillBudget, Random rnd) {
        String scope = c.groupPath();
        int cScale = c.kind() == ValueKind.NUMBER ? c.scale() : 0;  // scale() is NUMBER-only; used by the number op
        List<String> localNumbers = new ArrayList<>(numbersByGroup.getOrDefault(scope, List.of()).stream()
                .map(FieldInfo::path).filter(p -> !p.equals(c.path())).toList());
        // aggregate over a reachable repeatable descendant's number list (computed fields allowed — the aggregate
        // reads DOWNWARD only, so the dependency graph stays acyclic). Prefer a cell of the computed field's own
        // scale, so the Sum matches and needs no rounding (which would inflate the ARITHMETIC construct).
        Integer requireScale = c.kind() == ValueKind.NUMBER ? cScale : null;
        // The plan-threading discipline: a field selected via the
        // capture-anchored placement (matchByCaptureTarget) FOR a specific reach carries that operand forward
        // as a STRONG preference — honor it directly, skipping the independent probabilistic re-roll below,
        // so the reach it was placed for is actually realized (placement and operation
        // choice used to be fully decoupled, so a field placed for its reach potential wasn't guaranteed to
        // receive an aggregate operation at all).
        // The "zero-budget degrade defect" (compact-profile intake): a MODERN
        // profile's aggregateStars is a genuine 0-sample Distribution (n==0), not null, when the real model
        // has NO aggregating computations at all — distinct from an OLDER profile lacking the axis entirely
        // (null, unknown). Only the confirmed-zero case disables aggregation outright; null preserves the
        // pre-existing opportunistic fallback (aggregate whenever the scope geometrically allows it), so an
        // older profile's fidelity is unaffected. Overrides {@code plannedOperand} too — {@code
        // matchByCaptureTarget} (the reservation-time capture-plan matcher) sizes from the DIFFERENT
        // iteration.listScanCapacity/workCapacity axes (every field carries a measured value, even a trivial
        // one — n=1 for a compact profile, not n=0), so it can still hand back an aggregate operand for a
        // reservation whose OWN construct-level aggregateStars target is confirmed zero; a plan built on
        // the wrong axis is not authoritative over the construct budget that IS zero.
        boolean targetWantsNoAggregates = target.aggregateStars() != null && target.aggregateStars().n() == 0;
        String aggFlat;
        if (targetWantsNoAggregates) {
            aggFlat = null;
        } else if (plannedOperand != null) {
            aggFlat = plannedOperand;
        } else {
            // The "wide aggregate hub" pattern (MODEL-SYNTHESIS-FIDELITY-SPEC): the real target's
            // dominant aggregateStars shape is a NESTED (>=2-star) aggregate (median 2), a scope OUTSIDE a
            // multi-level chain reaching down through every level, which the single-level aggregateOperand can't
            // produce. Roll for it in the SAME proportion the target actually has (not always — the target still
            // has a genuine single-star minority), falling back to the single-level search otherwise.
            aggFlat = rnd.nextDouble() < nestedAggregateFraction(target.aggregateStars())
                    ? multiStarAggregateOperand(scope, childrenOf, repeatable, numbersAllByGroup, requireScale)
                    : null;
            if (aggFlat == null) {
                aggFlat = aggregateOperand(scope, childrenOf, repeatable, numbersAllByGroup, requireScale);  // starred, or null
            }
        }
        // GUARANTEE at least one field operand: the kernel rejects a purely-constant operation (MVK_NO_CONST),
        // and a scale-round of literals is exactly that. When the group has no aggregate reach, no planned
        // computed edge, and no local number, fall back to an ANCESTOR number (a star-free walk-up, single value).
        if (localNumbers.isEmpty()) {
            // GUARANTEE a field operand so EVERY alternative is non-constant (MVK_NO_CONST) — the aggregate rides
            // only the first alternative (below), so the others rely on this. A NON-computed number in scope or an
            // ancestor (a star-free walk-up); non-computed fields have no formula, so no cycle (MVK_ERROR_CALC_CYCLE).
            String anc = ancestorNumber(scope, c.path(), numbersByGroup);
            if (anc != null) {
                localNumbers.add(anc);
            }
        }
        int depth = sampleInt(target.depth(), rnd);
        double filteredFraction = filteredSumFraction(target);

        var b = m.computation(m.field(c.path())).name("K" + idx).message(Dsl.en("synthetic computation " + idx));

        // preconditioned alternatives where a scope-local field exists to key on; else one unconditional calc.
        // Alternatives are sampled from the target's alternatives-per-computation distribution (older profile → 1..3).
        List<FieldInfo> localFields = fieldsByGroup.getOrDefault(scope, List.of()).stream()
                .filter(f -> !f.path().equals(c.path())).toList();
        boolean canSplit = !localFields.isEmpty();             // preconditioned alternatives need a scope-local key
        PreconditionStyle preconditionStyle = target.constructs().getOrDefault("QUANTIFIER", 0) == 0
                        && target.operators().getOrDefault("FieldFilled", 0) > 0
                ? PreconditionStyle.PLAIN_FIELD
                : PreconditionStyle.PROFILED_CONSTRUCTS;
        int alternatives = !canSplit ? 1
                : (target.alternatives() != null && target.alternatives().n() > 0
                        ? Math.max(1, sampleInt(target.alternatives(), rnd))
                        : 1 + sampleInt(target.fanIn(), rnd) % 3);
        // The shared, priority-ordered budget-claim allocator:
        // CONST, then PARALLEL, then OUTER_CORRELATION, in that fixed priority order, decided once
        // in tryBudgetClaims — replacing three separately-typed "computedOperand == null && plannedOperand
        // == null" guards with the SAME exclusion expressed ONCE. A pure extraction: the priority order, the
        // eligibility rules, and mainOp's unconditional computation once CONST doesn't win are all UNCHANGED
        // from the inline sequence this replaces (kindOperation can consume rnd, so mainOp must still be
        // built even when PARALLEL/OUTER_CORRELATION goes on to claim the field — reproducing that exact,
        // otherwise-wasteful RNG consumption is required for byte-identical output, not incidental).
        BudgetClaimResult claim = tryBudgetClaims(m, b, c, computedOperand, plannedOperand, canSplit, localFields,
                aggFlat, localNumbers, depth, cScale, filteredFraction, fieldsByGroup, childrenOf, repeatable,
                numbersAllByGroup, constBudget, parallelBudget, outerBudget, groupFillBudget,
                preconditionStyle, rnd);
        if (claim.claimed() != null) {
            return claim.claimed();
        }
        Calculation mainOp = claim.mainOp();
        // the feeding/cascade edge becomes its OWN round-free COPY alternative rather than an arithmetic term folded
        // into the main op — a copy of a same-scale fed field carries the feedingOthers edge WITHOUT the Rounding
        // that tags ARITHMETIC (the over-round root cause; see MODEL-SYNTHESIS-FIDELITY-SPEC). Needs a scope-local
        // key to host a second alternative; without one it must fold (the unavoidable arithmetic minority).
        boolean feedAsCopy = computedOperand != null && canSplit;
        if (!canSplit) {
            b.calc(computedOperand != null
                    ? kindOperation(m, c, computedOperand, aggFlat, localNumbers, depth, cScale, filteredFraction,
                            fieldsByGroup, rnd)
                    : mainOp);
        } else {
            int alts = Math.max(alternatives, feedAsCopy ? 2 : 1);
            if (alts == 1) {
                // most single-alternative computations still carry a COMMON precondition (~88% even at 1 branch)
                if (preconditionedFraction(target) > rnd.nextInt(100)) {
                    b.commonPrecondition(precondition(m, localFields, 0, rnd, scope, childrenOf, repeatable,
                            groupFillBudget, preconditionStyle));
                }
                b.calc(mainOp);
            } else {
                for (int a = 0; a < alts; a++) {
                    Condition pre = precondition(m, localFields, a, rnd, scope, childrenOf, repeatable,
                            groupFillBudget, preconditionStyle);
                    // a=0 carries the aggregate/main op; a=1 the round-free feeding copy (if any); the rest are
                    // simple round-free ops over the guaranteed operand — each non-constant, no arithmetic inflation
                    Calculation op = a == 0 ? mainOp
                            : (a == 1 && feedAsCopy ? feedingCalc(m, computedOperand, cScale, rnd)
                                    : kindOperation(m, c, null, null, localNumbers, depth, cScale, filteredFraction,
                                            fieldsByGroup, rnd));
                    b.when(pre).calc(op);
                }
            }
        }
        return b.build();
    }


    private static Calculation feedingCalc(BoundModel m, String fed, int cScale, Random rnd) {
        if (m.num(fed).scale() == cScale) {
            return m.num(fed).asNumber().expression();
        }
        return roundTo(new NumberExpr(m.num(fed).value()), cScale, rnd);
    }


    private static Calculation kindOperation(BoundModel m, FieldInfo c, String computedOperand, String aggFlat,
            List<String> localNumbers, int depth, int cScale, double filteredFraction,
            Map<String, List<FieldInfo>> fieldsByGroup, Random rnd) {
        switch (c.kind()) {
            case STRING -> {
                // copy a scope-local/ancestor STRING field's value; else stringify a NUMBER (FieldValueAsString
                // requires a numeric operand); else a literal (last resort — no reachable field)
                String s = ancestorFieldOfKind(c.groupPath(), c.path(), fieldsByGroup, ValueKind.STRING);
                if (s != null) {
                    return m.str(s).asString().expression();
                }
                String n = ancestorFieldOfKind(c.groupPath(), c.path(), fieldsByGroup, ValueKind.NUMBER);
                return n != null ? Dsl.fieldValueAsString(m.num(n).field()).expression() : Dsl.text("S").expression();
            }
            case DATE -> {
                String d = ancestorFieldOfKind(c.groupPath(), c.path(), fieldsByGroup, ValueKind.DATE);
                return d != null ? Dsl.addYears(m.date(d), 1).expression() : Dsl.today().expression();
            }
            case BOOLEAN -> {
                // copy a scope-local/ancestor NON-computed BOOLEAN field (a FieldValue copy); else a bool literal
                // (a CONSTANT under the precondition the single-alt path adds — the reserved headroom accounts for it)
                String b = ancestorFieldOfKind(c.groupPath(), c.path(), fieldsByGroup, ValueKind.BOOLEAN);
                return b != null ? new FieldValue(m.field(b)) : new BoolLiteral(true);
            }
            case ENUMERATION -> {
                // an enum literal that FITS the computed field (every synthetic enum starts at V1). A FieldValue COPY
                // from a different enum is rejected (MVK_ERROR_INVALID_ENUM_CALCULATION: the source values must fit
                // the target's), so a constant is the safe form — CONSTANT archetype, valid under a precondition.
                return new EnumValueLiteral("V1");
            }
            default -> {
                return operation(m, computedOperand, aggFlat, localNumbers, depth, cScale, filteredFraction, rnd);
            }
        }
    }


    private static int preconditionedFraction(SynthesisProfile.Computations target) {
        return target.count() == 0 ? 0 : 100 * target.preconditionedCount() / target.count();
    }


    private static Condition precondition(BoundModel m, List<FieldInfo> localFields, int variant, Random rnd,
            String scope, Map<String, List<GroupInfo>> childrenOf, Set<String> repeatable, int[] groupFillBudget,
            PreconditionStyle style) {
        if (groupFillBudget[0] > 0) {
            String g = groupFillTarget(scope, childrenOf, repeatable);
            if (g != null) {
                groupFillBudget[0]--;
                return m.group(g).filled();
            }
        }
        switch (style) {
            case PLAIN_FIELD -> {
                return m.field(localFields.get(variant % localFields.size()).path()).filled();
            }
            case PROFILED_CONSTRUCTS -> {
                // Continue with the profiled construct mix below.
            }
        }
        List<Condition> clauses = new ArrayList<>();
        // A QUANTIFIER clause dominates the target's computation preconditions (~93% of the preconditioned ones;
        // AtLeastOneFieldFilled is by far the most common quantifier). So lead with one wherever the scope has ≥2
        // fields — the old 1-in-3 rate left computation QUANTIFIER far under target (307 vs 834).
        if (localFields.size() >= 2) {
            FieldInfo a = localFields.get(variant % localFields.size());
            FieldInfo b = localFields.get((variant + 1) % localFields.size());
            clauses.add(rnd.nextInt(10) == 0
                    ? Dsl.allFieldsFilled(m.field(a.path()), m.field(b.path()))
                    : Dsl.atLeastOneFieldFilled(m.field(a.path()), m.field(b.path())));
        }
        // ~40% of the preconditioned computations also carry a VALUE_LIST clause on a scope-local string/enum field
        FieldInfo vl = localFields.stream()
                .filter(f -> f.kind() == ValueKind.STRING || f.kind() == ValueKind.ENUMERATION)
                .findFirst().orElse(null);
        if (vl != null && rnd.nextInt(5) < 2) {
            clauses.add(valueListClause(m, vl.path(), vl.kind()));
        }
        if (clauses.isEmpty()) {                                 // no quantifier/value-list operand → a plain clause
            clauses.add(variedClause(m, localFields.get(variant % localFields.size()), rnd));
        }
        Condition out = clauses.get(0);
        for (int i = 1; i < clauses.size(); i++) {
            out = rnd.nextBoolean() ? out.and(clauses.get(i)) : out.or(clauses.get(i));
        }
        return out;
    }


    private static Map<String, String> planComputedEdges(List<FieldInfo> computed, int cascadeTarget, int feedTarget) {
        Map<String, List<FieldInfo>> byGroup = new LinkedHashMap<>();
        for (FieldInfo f : computed) {
            byGroup.computeIfAbsent(f.groupPath(), k -> new ArrayList<>()).add(f);
        }
        // groups with ≥2 computed fields, largest first — the largest hosts the cascade spine
        List<List<FieldInfo>> runs = byGroup.values().stream()
                .filter(g -> g.size() >= 2)
                .sorted((a, b) -> b.size() - a.size())
                .collect(java.util.stream.Collectors.toList());
        Map<String, String> edges = new LinkedHashMap<>();
        int cascade = Math.max(1, cascadeTarget);
        int feeders = 0;
        boolean spineDone = false;
        for (List<FieldInfo> run : runs) {
            if (feeders >= feedTarget && spineDone) {
                break;
            }
            int chain = Math.min(run.size(), cascade);          // never exceed the cascade depth
            if (spineDone) {
                // subsequent runs only need to add feeders; short 2-chains suffice and stay well under cascade
                chain = Math.min(run.size(), 2);
            }
            for (int k = 1; k < chain; k++) {
                if (feeders >= feedTarget && spineDone) {
                    break;
                }
                edges.put(run.get(k).path(), run.get(k - 1).path());   // reader reads the previous same-group field
                feeders++;
            }
            spineDone = true;
        }
        return edges;
    }


    private static Calculation operation(BoundModel m, String computedOperand, String aggFlat,
            List<String> localNumbers, int targetDepth, int cScale, double filteredFraction, Random rnd) {
        // SCALE-PRESERVING, round-free forms first. Rounding tags the ARITHMETIC construct on EVERY op it wraps,
        // so the real model — which reaches the computed field's scale naturally (a Sum/copy of same-scale
        // operands) — carries ARITHMETIC on only ~14% of computations, not all of them. Only the fallback rounds.
        if (computedOperand == null) {
            if (aggFlat != null) {
                String cell = aggFlat.replace("*", "");
                NumberExpr agg = sumOf(m, aggFlat, filteredFraction, rnd);
                if (m.num(cell).scale() == cScale) {
                    return agg.expression();                    // Sum matches the field scale → no round (AGGREGATE only)
                }
                return roundTo(agg, cScale, rnd);               // scale mismatch → round (the ARITHMETIC minority)
            }
            for (String p : localNumbers) {
                if (m.num(p).scale() == cScale) {
                    return m.num(p).asNumber().expression();     // a same-scale copy → no round, no arithmetic
                }
            }
        } else if (aggFlat == null && m.num(computedOperand).scale() == cScale) {
            // a feeding/cascade edge that just COPIES a same-scale computed field → still a feeding edge, but no
            // round and no arithmetic (the reference is what feedingOthers counts, not the surrounding math)
            return m.num(computedOperand).asNumber().expression();
        }
        // fallback: an arithmetic expression rounded to the field scale — used for feeding/cascade edges, scale
        // mismatches, and the arithmetic minority the target genuinely carries
        List<NumberExpr> leaves = new ArrayList<>();
        if (aggFlat != null) {
            leaves.add(sumOf(m, aggFlat, filteredFraction, rnd));
        }
        if (computedOperand != null) {
            leaves.add(new NumberExpr(m.num(computedOperand).value()));  // the feeding/cascade edge
        }
        for (String p : localNumbers) {
            if (leaves.size() >= 4) {
                break;
            }
            leaves.add(new NumberExpr(m.num(p).value()));
        }
        if (leaves.isEmpty()) {
            leaves.add(new NumberExpr(new NumberLiteral(java.math.BigDecimal.ONE)));
        }
        NumberExpr expr = leaves.get(0);
        // at least (leaves-1) operations so EVERY leaf is incorporated (the computed operand + aggregate must not
        // be dropped when the sampled depth is small — that silently broke the dependency chain)
        int ops = Math.max(leaves.size() - 1, targetDepth - 1);
        for (int k = 0; k < ops; k++) {
            NumberExpr leaf = leaves.get((k + 1) % leaves.size());
            expr = (k % 2 == 0) ? expr.plus(leaf) : expr.times(leaf);
        }
        return roundTo(expr, cScale, rnd);                       // scale-fix (MVK_INVALID_COMPARE_DEC_PLACES)
    }


    private static NumberExpr filteredSum(BoundModel m, String aggFlat) {
        String cell = aggFlat.replace("*", "");
        return Dsl.sum(m.flatten(aggFlat).having(m.num(cell).gt(0).and(m.num(cell).ge(0))));
    }


    private static NumberExpr sumOf(BoundModel m, String aggFlat, double filteredFraction, Random rnd) {
        return rnd.nextDouble() < filteredFraction ? filteredSum(m, aggFlat) : Dsl.sum(m.flatten(aggFlat));
    }


    private static double filteredSumFraction(SynthesisProfile.Computations target) {
        Map<String, Integer> constructs = target.constructs();
        if (constructs == null) {
            return 1.0;
        }
        int aggregate = constructs.getOrDefault("AGGREGATE", 0);
        if (aggregate <= 0) {
            return 1.0;
        }
        int filter = constructs.getOrDefault("FILTER", 0);
        return Math.min(1.0, (double) filter / aggregate);
    }


    private static Calculation roundTo(NumberExpr expr, int cScale, Random rnd) {
        return switch (rnd.nextInt(3)) {
            case 0 -> Dsl.roundUp(expr, cScale).expression();
            case 1 -> Dsl.roundDown(expr, cScale).expression();
            default -> Dsl.roundAccounting(expr, cScale).expression();
        };
    }


    private static String groupFillTarget(String scope, Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable) {
        for (GroupInfo c : childrenOf.getOrDefault(scope, List.of())) {
            if (!repeatable.contains(c.path())) {
                return c.path();                                // a non-repeatable direct child
            }
        }
        return null;
    }


    private static String ancestorNumber(String scope, String exclude, Map<String, List<FieldInfo>> nonComputedNumbers) {
        for (String g = scope; ; ) {                        // same-group is safe here: the map holds only
            for (FieldInfo f : nonComputedNumbers.getOrDefault(g, List.of())) {  // NON-computed numbers (no cycle)
                if (!f.path().equals(exclude)) {
                    return f.path();
                }
            }
            String parent = parentOf(g);
            if (parent.equals(g)) {
                return null;
            }
            g = parent;
        }
    }


    private static String ancestorFieldOfKind(String scope, String exclude, Map<String, List<FieldInfo>> fieldsByGroup,
            ValueKind kind) {
        for (String g = scope; ; ) {
            for (FieldInfo f : fieldsByGroup.getOrDefault(g, List.of())) {
                if (!f.path().equals(exclude) && (kind == null || f.kind() == kind)) {
                    return f.path();
                }
            }
            String parent = parentOf(g);
            if (parent.equals(g)) {
                return null;
            }
            g = parent;
        }
    }


    private static Map<String, List<GroupInfo>> childrenOf(BoundModel m) {
        Map<String, List<GroupInfo>> childrenOf = new LinkedHashMap<>();
        m.groups().forEach(g -> {
            String parent = parentOf(g.path());
            if (!parent.equals(g.path())) {
                childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(g);
            }
        });
        return childrenOf;
    }
    private static Condition variedClause(BoundModel m, FieldInfo f, Random rnd) {
        String p = f.path();
        return switch (f.kind()) {
            case NUMBER -> switch (rnd.nextInt(4)) {
                case 0 -> m.num(p).gt(0);                       // GreaterThan
                case 1 -> m.num(p).lt(0);                       // LessThan
                case 2 -> m.num(p).ge(0);                       // GreaterOrEqual
                default -> m.num(p).notFilled();                // FieldNotFilled
            };
            case ENUMERATION -> switch (rnd.nextInt(3)) {
                case 0 -> m.enumOf(p).is("V1");                 // Equal
                case 1 -> m.enumOf(p).isNot("V1");              // NotEqual
                default -> m.enumOf(p).notFilled();
            };
            case STRING -> switch (rnd.nextInt(4)) {
                case 0 -> m.str(p).in("V1", "V2");              // value-list
                case 1 -> m.str(p).eq("V1");                    // Equal (string equality)
                case 2 -> m.str(p).notFilled();
                default -> m.str(p).filled();
            };
            default -> rnd.nextBoolean() ? m.field(p).filled() : m.field(p).notFilled();
        };
    }
    private static Condition outerCorrelationClause(BoundModel m, FieldInfo f,
            Map<String, List<FieldInfo>> byGroup, Set<String> repeatable) {
        String scope = f.groupPath();
        if (!repeatable.contains(scope)) {
            return null;
        }
        FieldInfo num = byGroup.getOrDefault(scope, List.of()).stream()
                .filter(x -> x.kind() == ValueKind.NUMBER).findFirst().orElse(null);
        if (num == null) {
            return null;
        }
        Condition filter = Dsl.currentRepetition(m.group(scope))
                .ne(Dsl.currentRepetition(m.group(scope), true))          // CurrentRepetition(G) != CurrentRepetition($G)
                .and(m.num(num.path()).eq(m.num(num.path()).outer()));    // num == num.outer() (the correlated match)
        return Dsl.atLeastOneFieldFilled(m.flatten(scope + "*/" + num.name()).having(filter));
    }
    private static String aggregateOperand(String group, Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable, Map<String, List<FieldInfo>> byGroup) {
        return aggregateOperand(group, childrenOf, repeatable, byGroup, null);
    }


    private static String aggregateOperand(String group, Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable, Map<String, List<FieldInfo>> byGroup, Integer preferScale) {
        if (preferScale != null) {
            String matched = aggregateOperandOfScale(group, childrenOf, repeatable, byGroup, preferScale);
            if (matched != null) {
                return matched;
            }
        }
        return aggregateOperandOfScale(group, childrenOf, repeatable, byGroup, null);
    }


    private static String aggregateOperandOfScale(String group, Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable, Map<String, List<FieldInfo>> byGroup, Integer requireScale) {
        for (GroupInfo c : childrenOf.getOrDefault(group, List.of())) {
            if (repeatable.contains(c.path())) {
                for (FieldInfo cf : byGroup.getOrDefault(c.path(), List.of())) {
                    if (cf.kind() == ValueKind.NUMBER && (requireScale == null || cf.scale() == requireScale)) {
                        return c.path() + "*/" + cf.name();     // e.g. /scope/A/B*/Num (rendered relative to scope)
                    }
                }
            } else {                                            // descend only through non-repeatable groups
                String deeper = aggregateOperandOfScale(c.path(), childrenOf, repeatable, byGroup, requireScale);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }
    private static String multiStarAggregateOperand(String group, Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable, Map<String, List<FieldInfo>> byGroup, Integer preferScale) {
        if (preferScale != null) {
            String matched = multiStarAggregateOperandOfScale(group, childrenOf, repeatable, byGroup, preferScale);
            if (matched != null) {
                return matched;
            }
        }
        return multiStarAggregateOperandOfScale(group, childrenOf, repeatable, byGroup, null);
    }


    private static String multiStarAggregateOperandOfScale(String group, Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable, Map<String, List<FieldInfo>> byGroup, Integer requireScale) {
        for (GroupInfo c : childrenOf.getOrDefault(group, List.of())) {
            if (repeatable.contains(c.path())) {
                String deeper =
                        multiStarAggregateOperandOfScale(c.path(), childrenOf, repeatable, byGroup, requireScale);
                if (deeper != null) {                                // a nested repeatable reaches further — star it too
                    return c.path() + "*" + deeper.substring(c.path().length());
                }
                for (FieldInfo cf : byGroup.getOrDefault(c.path(), List.of())) {
                    if (cf.kind() == ValueKind.NUMBER && (requireScale == null || cf.scale() == requireScale)) {
                        return c.path() + "*/" + cf.name();
                    }
                }
            } else {                                                // descend only through non-repeatable groups
                String deeper =
                        multiStarAggregateOperandOfScale(c.path(), childrenOf, repeatable, byGroup, requireScale);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }


    private static List<String> siblingAggregatePair(String scope, Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable, Map<String, List<FieldInfo>> numbersAll) {
        List<String> cells = new ArrayList<>();
        List<String> reps = new ArrayList<>();
        collectRepCells(scope, childrenOf, repeatable, numbersAll, cells, reps);
        for (int i = 0; i < reps.size(); i++) {
            for (int j = i + 1; j < reps.size(); j++) {
                String a = reps.get(i);
                String b = reps.get(j);
                if (!a.startsWith(b + "/") && !b.startsWith(a + "/")) {   // incomparable → sibling branches
                    return List.of(cells.get(i), cells.get(j));
                }
            }
        }
        return null;
    }


    private static void collectRepCells(String group, Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable, Map<String, List<FieldInfo>> numbersAll, List<String> cells, List<String> reps) {
        for (GroupInfo c : childrenOf.getOrDefault(group, List.of())) {
            if (repeatable.contains(c.path())) {
                for (FieldInfo cf : numbersAll.getOrDefault(c.path(), List.of())) {
                    if (cf.kind() == ValueKind.NUMBER) {
                        cells.add(c.path() + "*/" + cf.name());
                        reps.add(c.path());
                        break;
                    }
                }
            } else {
                collectRepCells(c.path(), childrenOf, repeatable, numbersAll, cells, reps);
            }
        }
    }

    private static Condition valueListClause(BoundModel m, String path, ValueKind kind) {
        return switch (kind) {
            case NUMBER -> m.num(path).in(0, 1);
            case ENUMERATION -> m.enumOf(path).in("V1", "V2");
            default -> m.str(path).in("V1", "V2");              // STRING (operands only picks STRING/NUMBER/ENUM)
        };
    }
    private static int sampleInt(Distribution d, Random rnd) {
        return Math.max(1, sampleRaw(d, rnd));                  // ≥1 variant (arity/depth/reps)
    }


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


    private static long chainProductOf(String scope, Set<String> repeatable, Map<String, Integer> maxRepsByPath) {
        long product = 1;
        for (String p = scope; ; ) {
            if (repeatable.contains(p)) {
                product = Math.min(product * maxRepsByPath.getOrDefault(p, 1), Integer.MAX_VALUE);
            }
            String parent = parentOf(p);
            if (parent.equals(p)) {
                return product;
            }
            p = parent;
        }
    }


    private static List<FieldInfo> quantileMatchByScopeCapacity(List<FieldInfo> candidates, int wantCount,
            double meanClusterSize, Distribution targetScopeCapacity, Set<String> repeatable,
            Map<String, Integer> maxRepsByPath, Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf, Random rnd) {
        if (candidates.isEmpty() || targetScopeCapacity == null || targetScopeCapacity.n() == 0 || wantCount <= 0) {
            return candidates;
        }
        int distinctEstimate = Math.max(1,
                Math.min(candidates.size(), (int) Math.ceil(wantCount / Math.max(1.0, meanClusterSize))));
        List<Integer> targets = sampleMultiset(targetScopeCapacity, distinctEstimate, rnd);

        List<FieldInfo> pool = new ArrayList<>(candidates);
        List<FieldInfo> matched = new ArrayList<>(distinctEstimate);
        for (int target : targets) {
            int bestIdx = 0;
            double bestScore = Double.MAX_VALUE;
            for (int i = 0; i < pool.size(); i++) {
                String scope = pool.get(i).groupPath();
                double diff = Math.abs(chainProductOf(scope, repeatable, maxRepsByPath) - (long) target);
                boolean capable = aggregateOperand(scope, childrenOf, repeatable, byGroup) != null;
                double score = capable ? diff * 0.8 : diff;         // bias toward reach-capable scopes on near-ties
                if (score < bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
            matched.add(pool.remove(bestIdx));
            if (pool.isEmpty()) {
                break;
            }
        }
        matched.addAll(pool);                                       // leftover candidates trail, as a safety net
        return matched;
    }


    private record CaptureMatch(List<FieldInfo> fields, Map<String, String> operandPlan) {}

    private static CaptureMatch matchByCaptureTarget(List<FieldInfo> candidates, int wantCount,
            Distribution targetCapture, Set<String> repeatable,
            Map<String, Integer> maxRepsByPath, Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf, Random rnd) {
        if (candidates.isEmpty() || targetCapture == null || targetCapture.n() == 0 || wantCount <= 0) {
            return new CaptureMatch(candidates, Map.of());
        }
        int distinctEstimate = Math.min(candidates.size(), wantCount);

        // shuffled — many candidates commonly TIE on capturePotential (e.g. every non-aggregate-capable,
        // non-repeatable-scoped field has potential 1), and an unshuffled pool always resolves a tie to the
        // same first-in-list candidate regardless of seed, which can consistently miss OTHER placement needs
        // (e.g. a GROUP_FILL precondition needing a scope with an eligible child) that this capture-only match
        // has no visibility into.
        List<FieldInfo> pool = new ArrayList<>(candidates);
        java.util.Collections.shuffle(pool, rnd);
        List<FieldInfo> matched = new ArrayList<>(distinctEstimate);
        Map<String, String> operandPlan = new LinkedHashMap<>();

        int reachCapableCount = (int) pool.stream()
                .filter(f -> capturePotentialDetailed(f.groupPath(), childrenOf, repeatable, byGroup, maxRepsByPath)
                        .operand() != null)
                .count();
        int reachBudget = Math.min(reachCapableCount, Math.min(distinctEstimate,
                (int) Math.round(distinctEstimate * nonTrivialFraction(targetCapture, 0.0))));
        Distribution reachMagnitudes = reachBudget > 0 ? excludingTrivialBucket(targetCapture) : null;

        if (reachMagnitudes != null) {
            for (int target : sampleMultiset(reachMagnitudes, reachBudget, rnd)) {
                double targetLog = Math.log(Math.max(1, target));
                // TWO passes, PAIR-CAPABLE candidates deprioritized: a candidate whose scope ALSO satisfies
                // siblingAggregatePair is exactly the supply the OPPORTUNISTIC PARALLEL claim in
                // tryBudgetClaims depends on later (a field this reach-budget plans is budgetEligible=false
                // by then — the priority order already excludes it from PARALLEL, correctly). Claiming one
                // here when a non-pair-capable reach-capable candidate would do just as well starves that
                // later opportunistic supply for no fidelity gain (measured: it silently dropped
                // comp.parallelIteration's floor even though twin-hub's OWN reservation was unaffected —
                // see MODEL-SYNTHESIS-FIDELITY-SPEC). Only fall back to a pair-capable candidate when
                // no other reach-capable one exists for this slot.
                //
                // NOT jointly matched against scannedScopeCapacity (tried and reverted): pairing a
                // sampled scope target alongside the reach target competes with THIS match for the same
                // candidate slots, degrading reach-fit quality enough to sharply regress comp.workCapacity
                // and comp.parallelIteration without lifting scopeCapacity in return (see
                // MODEL-SYNTHESIS-FIDELITY-SPEC — a new pairwise deference breaking a
                // previously-working one). Reach-only, as it was.
                int bestIdx = -1;
                double bestDiff = Double.MAX_VALUE;
                String bestOperand = null;
                for (boolean allowPairCapable : new boolean[] {false, true}) {
                    for (int i = 0; i < pool.size(); i++) {
                        CapturePotential cp = capturePotentialDetailed(pool.get(i).groupPath(), childrenOf,
                                repeatable, byGroup, maxRepsByPath);
                        if (cp.operand() == null) {
                            continue;                              // this budget is reach-capable-only
                        }
                        if (!allowPairCapable
                                && siblingAggregatePair(pool.get(i).groupPath(), childrenOf, repeatable, byGroup)
                                        != null) {
                            continue;                              // reserve this candidate for PARALLEL, first pass
                        }
                        double diff = Math.abs(Math.log(cp.value()) - targetLog);
                        if (bestIdx < 0 || diff < bestDiff) {
                            bestDiff = diff;
                            bestIdx = i;
                            bestOperand = cp.operand();
                        }
                    }
                    if (bestIdx >= 0) {
                        break;                                     // the exclusion pass found a candidate
                    }
                }
                if (bestIdx < 0) {
                    break;                                         // reach-capable supply exhausted
                }
                FieldInfo winner = pool.remove(bestIdx);
                matched.add(winner);
                operandPlan.put(winner.path(), bestOperand);
                if (pool.isEmpty()) {
                    break;
                }
            }
        }

        int remainingWant = Math.min(distinctEstimate - matched.size(), pool.size());
        if (remainingWant > 0) {
            for (int target : sampleMultiset(targetCapture, remainingWant, rnd)) {
                double targetLog = Math.log(Math.max(1, target));
                int bestIdx = 0;
                double bestDiff = Double.MAX_VALUE;
                String bestOperand = null;
                for (int i = 0; i < pool.size(); i++) {
                    CapturePotential cp = capturePotentialDetailed(pool.get(i).groupPath(), childrenOf, repeatable,
                            byGroup, maxRepsByPath);
                    double diff = Math.abs(Math.log(Math.max(1, cp.value())) - targetLog);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestIdx = i;
                        bestOperand = cp.operand();
                    }
                }
                FieldInfo winner = pool.remove(bestIdx);
                matched.add(winner);
                if (bestOperand != null) {
                    operandPlan.put(winner.path(), bestOperand);
                }
                if (pool.isEmpty()) {
                    break;
                }
            }
        }
        matched.addAll(pool);                                       // leftover candidates trail, as a safety net
        return new CaptureMatch(matched, operandPlan);
    }


    private record CapturePotential(long value, String operand) {}

    private static CapturePotential capturePotentialDetailed(String scope, Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable, Map<String, List<FieldInfo>> byGroup, Map<String, Integer> maxRepsByPath) {
        String operand = multiStarAggregateOperand(scope, childrenOf, repeatable, byGroup, null);
        if (operand == null) {
            return new CapturePotential(chainProductOf(scope, repeatable, maxRepsByPath), null);
        }
        String deepestGroup = parentOf(operand.replace("*", ""));   // the operand's dotted path, minus the field
        return new CapturePotential(chainProductOf(deepestGroup, repeatable, maxRepsByPath), operand);
    }
    private static double nestedAggregateFraction(Distribution aggregateStars) {
        if (aggregateStars == null || aggregateStars.n() == 0 || aggregateStars.buckets().isEmpty()) {
            return 0.0;
        }
        int nested = 0;
        for (var e : aggregateStars.buckets().entrySet()) {
            if (!"1".equals(e.getKey())) {                          // bucket "1" is exactly a single-star flatten
                nested += e.getValue();
            }
        }
        return (double) nested / aggregateStars.n();
    }


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


    private static Distribution excludingTrivialBucket(Distribution d) {
        if (d == null) {
            return null;
        }
        Map<String, Integer> filtered = new LinkedHashMap<>(d.buckets());
        filtered.remove("1");
        int n = filtered.values().stream().mapToInt(Integer::intValue).sum();
        return n == 0 ? null : new Distribution(n, d.min(), d.max(), d.mean(), d.median(), d.p90(), filtered);
    }

    private static ValueKind parseKind(String name) {
        try {
            return ValueKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ValueKind.STRING;
        }
    }
    private static int depthOf(String path) {
        int d = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                d++;
            }
        }
        return d;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : path;
    }


    private static String topLevelBranchOf(String groupPath) {
        String cur = groupPath;
        while (true) {
            String parent = parentOf(cur);
            if (parent.equals(cur)) {
                return cur;                                  // cur IS the root — no branch above it
            }
            if (parentOf(parent).equals(parent)) {
                return cur;                                   // parent IS the root — cur is the direct-child branch
            }
            cur = parent;
        }
    }
}
