package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** Rule-family planning and rendering extracted mechanically from the donor synthesizer. */
final class RuleModelSynthesizer {

    private RuleModelSynthesizer() {
    }

    static Plan plan(BoundModel model, SynthesisProfile profile, long seed, Set<String> excludedPaths) {
        return planRuleFields(model, profile, seed, excludedPaths);
    }

    static List<Rule> render(BoundModel model, Plan plan, long seed) {
        return List.copyOf(renderRules(model, plan, seed));
    }

    record Plan(
            List<FieldInfo> ruleFields,
            RuleConstruct[] constructPlan,
            ScopeShape shape,
            Distribution arityDist,
            Distribution walkUpDist,
            int[] walkUpBudget,
            int[] outerBudget,
            int[] parallelBudget) {}

    private static Plan planRuleFields(
            BoundModel model, SynthesisProfile profile, long seed, Set<String> exclude) {
        Random random = SynthesisRandom.forPurpose(seed, "rule.pool");
        SynthesisProfile.Rules target = profile.rules();
        int count = target.count();
        List<FieldInfo> fields = model.fields().filter(field -> !exclude.contains(field.path())).toList();
        if (count == 0 || fields.isEmpty()) {
            return new Plan(List.of(), new RuleConstruct[0], ScopeShape.EMPTY, target.booleanArity(),
                    target.walkUpDistance(), new int[] {0}, new int[] {0}, new int[] {0});
        }

        ScopeShape shape = ScopeShape.of(model, fields);

        List<List<FieldInfo>> byClass = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (FieldInfo field : fields) {
            byClass.get(shape.scopeClass(field.groupPath())).add(field);
        }
        Comparator<FieldInfo> capableFirst = Comparator.comparingInt(
                (FieldInfo field) -> structuralCapabilityScore(field.groupPath(), shape))
                .reversed();
        byClass.get(0).sort(capableFirst);
        byClass.get(1).sort(capableFirst);

        int[] want = allocate(count, target.scope());
        Map<String, Integer> maxRepsByPath = model.groups().filter(GroupInfo::repeating)
                .collect(Collectors.toMap(GroupInfo::path, GroupInfo::maxRepetitions));
        Distribution ruleScopeCapacity = target.iteration() == null ? null : target.iteration().scopeCapacity();
        int parallelTarget = target.iteration() == null ? 0 : target.iteration().parallelIteration();

        List<FieldInfo> twinReservedRules = new ArrayList<>();
        List<FieldInfo> perRowPool = byClass.get(2);
        for (var iterator = perRowPool.iterator();
                iterator.hasNext() && twinReservedRules.size() < Math.min(parallelTarget, want[2]); ) {
            FieldInfo field = iterator.next();
            if (shape.siblingAggregatePair(field.groupPath()) != null) {
                twinReservedRules.add(field);
                iterator.remove();
            }
        }

        int stillWantedFromGroup = Math.min(parallelTarget, want[2]) - twinReservedRules.size();
        List<FieldInfo> twinReservedFromGroup = new ArrayList<>();
        List<FieldInfo> perGroupPool = byClass.get(1);
        for (var iterator = perGroupPool.iterator();
                iterator.hasNext() && twinReservedFromGroup.size() < stillWantedFromGroup; ) {
            FieldInfo field = iterator.next();
            if (shape.siblingAggregatePair(field.groupPath()) != null) {
                twinReservedFromGroup.add(field);
                iterator.remove();
            }
        }

        List<FieldInfo> twinReservedFromModelLevel = new ArrayList<>();
        List<FieldInfo> modelLevelPool = byClass.get(0);
        int stillWantedFromModelLevel = Math.min(parallelTarget, want[2]) - twinReservedRules.size()
                - twinReservedFromGroup.size();
        for (var iterator = modelLevelPool.iterator();
                iterator.hasNext() && twinReservedFromModelLevel.size() < stillWantedFromModelLevel; ) {
            FieldInfo field = iterator.next();
            if (shape.siblingAggregatePair(field.groupPath()) != null) {
                twinReservedFromModelLevel.add(field);
                iterator.remove();
            }
        }
        want[0] += twinReservedFromModelLevel.size();
        want[2] -= twinReservedFromModelLevel.size();

        List<FieldInfo> modelLevelWithTwinHubFirst = new ArrayList<>(twinReservedFromModelLevel);
        modelLevelWithTwinHubFirst.addAll(modelLevelPool);
        List<FieldInfo> perGroupWithTwinHubFirst = new ArrayList<>(twinReservedFromGroup);
        perGroupWithTwinHubFirst.addAll(perGroupPool);
        List<FieldInfo> perRow = new ArrayList<>(twinReservedRules);
        perRow.addAll(quantileMatchByScopeCapacity(perRowPool,
                Math.max(0, want[2] - twinReservedRules.size()), target.rulesPerErrorField().mean(),
                ruleScopeCapacity, shape, maxRepsByPath, random));
        List<List<FieldInfo>> matchedByClass = List.of(modelLevelWithTwinHubFirst, perGroupWithTwinHubFirst, perRow);

        int spill = 0;
        for (int scopeClass = 0; scopeClass < 3; scopeClass++) {
            if (matchedByClass.get(scopeClass).isEmpty()) {
                spill += want[scopeClass];
                want[scopeClass] = 0;
            }
        }
        List<FieldInfo> ruleFields = new ArrayList<>();
        for (int scopeClass = 0; scopeClass < 3; scopeClass++) {
            assignClustered(matchedByClass.get(scopeClass), want[scopeClass], target.rulesPerErrorField(), random,
                    ruleFields);
        }
        assignClustered(fields, spill, target.rulesPerErrorField(), random, ruleFields);
        RuleConstruct[] constructPlan = assignConstructs(ruleFields, target, shape);

        int[] walkUpBudget = {target.walkUpDistance().n()};
        int[] outerBudget = {target.iteration() == null ? 0 : target.iteration().outerCorrelation()};
        int[] parallelBudget = {target.iteration() == null ? 0 : target.iteration().parallelIteration()};
        return new Plan(ruleFields, constructPlan, shape, target.booleanArity(),
                target.walkUpDistance(), walkUpBudget, outerBudget, parallelBudget);
    }

    private static List<Rule> renderRules(BoundModel model, Plan plan, long seed) {
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < plan.ruleFields().size(); i++) {
            int index = i + 1;
            rules.add(buildRule(model, plan.ruleFields().get(i), index, plan.constructPlan()[i], plan.arityDist(),
                    plan.walkUpDist(), plan.walkUpBudget(), plan.outerBudget(), plan.parallelBudget(), plan.shape(),
                    SynthesisRandom.forPurpose(seed, "rule.build:" + index)));
        }
        return rules;
    }

    private static Rule buildRule(
            BoundModel model,
            FieldInfo field,
            int index,
            RuleConstruct construct,
            Distribution arityDistribution,
            Distribution walkUpDistribution,
            int[] walkUpBudget,
            int[] outerBudget,
            int[] parallelBudget,
            ScopeShape shape,
            Random random) {
        Condition guard = simpleClause(model, field);
        List<Condition> rest = new ArrayList<>();
        Condition constructCondition = construct == RuleConstruct.FILTER && outerBudget[0] > 0
                && outerCorrelationClause(model, field, shape) instanceof Condition selfJoin
                        ? decrementAnd(outerBudget, selfJoin)
                        : construct == null ? null : construct.clause(model, field.groupPath(), shape);
        boolean constructMustAlwaysHold = construct == RuleConstruct.UNIQUENESS;
        if (constructCondition != null && !constructMustAlwaysHold) {
            rest.add(constructCondition);
        }
        if (walkUpBudget[0] > 0) {
            String ancestor = ancestorField(field.groupPath(), sampleInt(walkUpDistribution, random), shape);
            if (ancestor != null) {
                rest.add(model.field(ancestor).filled());
                walkUpBudget[0]--;
            }
        }
        if (parallelBudget[0] > 0) {
            List<String> pair = shape.siblingAggregatePair(field.groupPath());
            if (pair != null) {
                rest.add(Dsl.sum(model.flatten(pair.get(0))).gt(0)
                        .and(Dsl.sum(model.flatten(pair.get(1))).gt(0)));
                parallelBudget[0]--;
            }
        }
        int arity = sampleInt(arityDistribution, random);
        List<FieldInfo> siblings = shape.fieldsOf(field.groupPath()).stream()
                .filter(sibling -> !sibling.path().equals(field.path())).toList();
        for (int siblingIndex = 0; !siblings.isEmpty() && rest.size() + 1 < arity; siblingIndex++) {
            rest.add(variedClause(model, siblings.get(siblingIndex % siblings.size()), random));
            if (siblingIndex > 3 * arity) {
                break;
            }
        }
        Condition combined = rest.isEmpty() ? null : rest.get(0);
        for (int i = 1; i < rest.size(); i++) {
            combined = random.nextBoolean() ? combined.and(rest.get(i)) : combined.or(rest.get(i));
        }
        Condition when = combined == null ? guard : guard.and(combined);
        if (constructMustAlwaysHold && constructCondition != null) {
            when = when.and(constructCondition);
        }
        String errorFieldPath = constructMustAlwaysHold ? shape.uniquenessKeyField(field.groupPath()) : null;
        return model.rule(model.group(field.groupPath()))
                .name("R" + index)
                .errorField(model.field(errorFieldPath != null ? errorFieldPath : field.path()))
                .when(when)
                .code("C" + index)
                .severity(Severity.ERROR)
                .message(Dsl.en("synthetic rule " + index))
                .build();
    }

    private static RuleConstruct[] assignConstructs(
            List<FieldInfo> ruleFields, SynthesisProfile.Rules target, ScopeShape shape) {
        RuleConstruct[] plan = new RuleConstruct[ruleFields.size()];
        Map<String, Map<String, Integer>> byScope = target.constructsByScope();
        List<List<Integer>> slotsByClass = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < ruleFields.size(); i++) {
            slotsByClass.get(shape.scopeClass(ruleFields.get(i).groupPath())).add(i);
        }
        if (byScope != null && !byScope.isEmpty()) {
            String[] classNames = {"modelLevel", "perGroup", "perRepeatableRow"};
            for (int scopeClass = 0; scopeClass < 3; scopeClass++) {
                assignWithinSlots(plan, slotsByClass.get(scopeClass),
                        byScope.getOrDefault(classNames[scopeClass], Map.of()), ruleFields, shape);
            }
        } else {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < plan.length; i++) {
                all.add(i);
            }
            assignWithinSlots(plan, all, target.constructs(), ruleFields, shape);
        }
        return plan;
    }

    private static void assignWithinSlots(
            RuleConstruct[] plan,
            List<Integer> slots,
            Map<String, Integer> target,
            List<FieldInfo> ruleFields,
            ScopeShape shape) {
        Map<RuleConstruct, Integer> remaining = new EnumMap<>(RuleConstruct.class);
        for (RuleConstruct construct : RuleConstruct.values()) {
            remaining.put(construct, target.getOrDefault(construct.name(), 0));
        }
        for (int index : slots) {
            if (plan[index] != null) {
                continue;
            }
            String scope = ruleFields.get(index).groupPath();
            RuleConstruct best = mostStarved(RuleConstruct.STRUCTURAL, remaining, target, scope, shape);
            if (best == null) {
                best = mostStarved(RuleConstruct.UBIQUITOUS, remaining, target, scope, shape);
            }
            if (best != null) {
                plan[index] = best;
                remaining.merge(best, -1, Integer::sum);
            }
        }
    }

    private static RuleConstruct mostStarved(
            List<RuleConstruct> candidates,
            Map<RuleConstruct, Integer> remaining,
            Map<String, Integer> target,
            String scope,
            ScopeShape shape) {
        RuleConstruct best = null;
        double bestFraction = 0;
        int bestTarget = Integer.MAX_VALUE;
        for (RuleConstruct candidate : candidates) {
            int remainder = remaining.get(candidate);
            int targetCount = target.getOrDefault(candidate.name(), 0);
            if (remainder <= 0 || !candidate.supportedIn(scope, shape)) {
                continue;
            }
            double fraction = (double) remainder / targetCount;
            if (fraction > bestFraction || (fraction == bestFraction && targetCount < bestTarget)) {
                best = candidate;
                bestFraction = fraction;
                bestTarget = targetCount;
            }
        }
        return best;
    }

    private static int structuralCapabilityScore(String scope, ScopeShape shape) {
        return (int) RuleConstruct.STRUCTURAL.stream().filter(construct -> construct.supportedIn(scope, shape)).count();
    }

    private static Condition simpleClause(BoundModel model, FieldInfo field) {
        return model.field(field.path()).filled();
    }

    private static Condition variedClause(BoundModel model, FieldInfo field, Random random) {
        String path = field.path();
        return switch (field.kind()) {
            case NUMBER -> switch (random.nextInt(4)) {
                case 0 -> model.num(path).gt(0);
                case 1 -> model.num(path).lt(0);
                case 2 -> model.num(path).ge(0);
                default -> model.num(path).notFilled();
            };
            case ENUMERATION -> switch (random.nextInt(3)) {
                case 0 -> model.enumOf(path).is("V1");
                case 1 -> model.enumOf(path).isNot("V1");
                default -> model.enumOf(path).notFilled();
            };
            case STRING -> switch (random.nextInt(4)) {
                case 0 -> model.str(path).in("V1", "V2");
                case 1 -> model.str(path).eq("V1");
                case 2 -> model.str(path).notFilled();
                default -> model.str(path).filled();
            };
            default -> random.nextBoolean() ? model.field(path).filled() : model.field(path).notFilled();
        };
    }

    private static String ancestorField(String scope, int targetDistance, ScopeShape shape) {
        List<String> ancestors = new ArrayList<>();
        for (String group = ScopeShape.parentOf(scope); ; ) {
            ancestors.add(group);
            String parent = ScopeShape.parentOf(group);
            if (parent.equals(group)) {
                break;
            }
            group = parent;
        }
        if (ancestors.isEmpty()) {
            return null;
        }
        int start = Math.min(Math.max(0, targetDistance - 1), ancestors.size() - 1);
        for (int index = start; index < ancestors.size(); index++) {
            List<FieldInfo> fields = shape.fieldsOf(ancestors.get(index));
            if (!fields.isEmpty()) {
                return fields.get(0).path();
            }
        }
        for (int index = start - 1; index >= 0; index--) {
            List<FieldInfo> fields = shape.fieldsOf(ancestors.get(index));
            if (!fields.isEmpty()) {
                return fields.get(0).path();
            }
        }
        return null;
    }

    private static Condition outerCorrelationClause(BoundModel model, FieldInfo field, ScopeShape shape) {
        String scope = field.groupPath();
        if (!shape.repeatable().contains(scope)) {
            return null;
        }
        FieldInfo number = shape.fieldsOf(scope).stream()
                .filter(candidate -> candidate.kind() == ValueKind.NUMBER).findFirst().orElse(null);
        if (number == null) {
            return null;
        }
        Condition filter = Dsl.currentRepetition(model.group(scope))
                .ne(Dsl.currentRepetition(model.group(scope), true))
                .and(model.num(number.path()).eq(model.num(number.path()).outer()));
        return Dsl.atLeastOneFieldFilled(model.flatten(scope + "*/" + number.name()).having(filter));
    }

    private static Condition decrementAnd(int[] budget, Condition condition) {
        budget[0]--;
        return condition;
    }

    private static void assignClustered(
            List<FieldInfo> bucket,
            int count,
            Distribution rulesPerErrorField,
            Random random,
            List<FieldInfo> output) {
        if (count <= 0 || bucket.isEmpty()) {
            return;
        }
        int assigned = 0;
        int index = 0;
        while (assigned < count) {
            FieldInfo errorField = bucket.get(index++ % bucket.size());
            int clusterSize = Math.min(sampleInt(rulesPerErrorField, random), count - assigned);
            for (int clusterIndex = 0; clusterIndex < clusterSize; clusterIndex++) {
                output.add(errorField);
            }
            assigned += clusterSize;
        }
    }

    private static int[] allocate(int count, SynthesisProfile.Scope scope) {
        int[] weights = {scope.modelLevel(), scope.perGroup(), scope.perRepeatableRow()};
        int totalWeight = weights[0] + weights[1] + weights[2];
        int[] allocation = new int[3];
        if (totalWeight == 0) {
            allocation[0] = count;
            return allocation;
        }
        double[] remainders = new double[3];
        int assigned = 0;
        for (int i = 0; i < 3; i++) {
            double exact = (double) weights[i] / totalWeight * count;
            allocation[i] = (int) Math.floor(exact);
            remainders[i] = exact - allocation[i];
            assigned += allocation[i];
        }
        while (assigned < count) {
            int best = 0;
            for (int i = 1; i < 3; i++) {
                if (remainders[i] > remainders[best]) {
                    best = i;
                }
            }
            allocation[best]++;
            remainders[best] = -1;
            assigned++;
        }
        return allocation;
    }

    private static List<FieldInfo> quantileMatchByScopeCapacity(
            List<FieldInfo> candidates,
            int count,
            double meanClusterSize,
            Distribution targetScopeCapacity,
            ScopeShape shape,
            Map<String, Integer> maxRepetitionsByPath,
            Random random) {
        if (candidates.isEmpty() || targetScopeCapacity == null || targetScopeCapacity.n() == 0 || count <= 0) {
            return candidates;
        }
        int distinctEstimate = Math.max(1,
                Math.min(candidates.size(), (int) Math.ceil(count / Math.max(1.0, meanClusterSize))));
        List<Integer> targets = sampleMultiset(targetScopeCapacity, distinctEstimate, random);

        List<FieldInfo> pool = new ArrayList<>(candidates);
        List<FieldInfo> matched = new ArrayList<>(distinctEstimate);
        for (int target : targets) {
            int bestIndex = 0;
            double bestScore = Double.MAX_VALUE;
            for (int index = 0; index < pool.size(); index++) {
                String scope = pool.get(index).groupPath();
                double difference =
                        Math.abs(chainProductOf(scope, shape.repeatable(), maxRepetitionsByPath) - (long) target);
                boolean capable = shape.aggregateOperand(scope) != null;
                double score = capable ? difference * 0.8 : difference;
                if (score < bestScore) {
                    bestScore = score;
                    bestIndex = index;
                }
            }
            matched.add(pool.remove(bestIndex));
            if (pool.isEmpty()) {
                break;
            }
        }
        matched.addAll(pool);
        return matched;
    }

    private static int sampleInt(Distribution distribution, Random random) {
        return Math.max(1, sampleRaw(distribution, random));
    }

    private static int sampleRaw(Distribution distribution, Random random) {
        if (distribution.n() == 0 || distribution.buckets().isEmpty()) {
            return 0;
        }
        int total = distribution.buckets().values().stream().mapToInt(Integer::intValue).sum();
        int draw = random.nextInt(total);
        for (var bucket : distribution.buckets().entrySet()) {
            draw -= bucket.getValue();
            if (draw < 0) {
                return Math.min(distribution.max(), bucketValue(bucket.getKey(), random));
            }
        }
        return distribution.median();
    }

    private static int bucketValue(String label, Random random) {
        int dash = label.indexOf('-');
        try {
            if (dash < 0) {
                return Integer.parseInt(label);
            }
            int low = Integer.parseInt(label.substring(0, dash));
            int high = Integer.parseInt(label.substring(dash + 1));
            return low + random.nextInt(high - low + 1);
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private static List<Integer> sampleMultiset(Distribution distribution, int count, Random random) {
        if (count <= 0 || distribution == null || distribution.n() == 0 || distribution.buckets().isEmpty()) {
            return List.of();
        }
        int total = distribution.buckets().values().stream().mapToInt(Integer::intValue).sum();
        List<String> labels = new ArrayList<>(distribution.buckets().keySet());
        double[] remainders = new double[labels.size()];
        int[] shares = new int[labels.size()];
        int assigned = 0;
        for (int index = 0; index < labels.size(); index++) {
            double exact = (double) distribution.buckets().get(labels.get(index)) / total * count;
            shares[index] = (int) Math.floor(exact);
            remainders[index] = exact - shares[index];
            assigned += shares[index];
        }
        while (assigned < count) {
            int best = 0;
            for (int index = 1; index < labels.size(); index++) {
                if (remainders[index] > remainders[best]) {
                    best = index;
                }
            }
            shares[best]++;
            remainders[best] = -1;
            assigned++;
        }
        List<Integer> values = new ArrayList<>(count);
        for (int index = 0; index < labels.size(); index++) {
            for (int share = 0; share < shares[index]; share++) {
                values.add(Math.min(distribution.max(), bucketValue(labels.get(index), random)));
            }
        }
        return values;
    }

    private static long chainProductOf(
            String scope, Set<String> repeatable, Map<String, Integer> maxRepetitionsByPath) {
        long product = 1;
        for (String path = scope; ; ) {
            if (repeatable.contains(path)) {
                product = Math.min(product * maxRepetitionsByPath.getOrDefault(path, 1), Integer.MAX_VALUE);
            }
            String parent = ScopeShape.parentOf(path);
            if (parent.equals(path)) {
                return product;
            }
            path = parent;
        }
    }
}
