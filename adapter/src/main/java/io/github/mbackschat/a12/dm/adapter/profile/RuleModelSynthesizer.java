package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.core.EntityPaths;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** Rule-family planning and rendering extracted mechanically from the donor synthesizer. */
final class RuleModelSynthesizer {

    private static final List<String> STRUCTURAL_CONSTRUCTS = List.of(
            "GROUP_FILL", "AGGREGATE", "FILTER", "CURRENT_REPETITION", "ARITHMETIC", "DATE_MATH",
            "UNIQUENESS");
    private static final List<String> UBIQUITOUS_CONSTRUCTS = List.of("CUSTOM", "QUANTIFIER", "VALUE_LIST");
    /**
     * The declared classes {@link #dateMathCondition} builds on, which {@code BoundModel.date/dateTime} admit by
     * (DEV-API-GAPS DG132), so a scope never offers DATE_MATH where it cannot build it.
     */
    private static final Set<ValueKind> DATE_MATH_CLASSES = Set.of(ValueKind.DATE, ValueKind.DATE_TIME);

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
            String[] constructPlan,
            Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable,
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
            return new Plan(List.of(), new String[0], Map.of(), Map.of(), Set.of(), target.booleanArity(),
                    target.walkUpDistance(), new int[] {0}, new int[] {0}, new int[] {0});
        }

        Set<String> repeatable = model.groups().filter(GroupInfo::repeating)
                .map(GroupInfo::path).collect(Collectors.toSet());
        Map<String, List<FieldInfo>> byGroup = new LinkedHashMap<>();
        for (FieldInfo field : fields) {
            byGroup.computeIfAbsent(field.groupPath(), ignored -> new ArrayList<>()).add(field);
        }
        Map<String, List<GroupInfo>> childrenOf = childrenOf(model);

        List<List<FieldInfo>> byClass = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (FieldInfo field : fields) {
            byClass.get(scopeClass(field.groupPath(), repeatable)).add(field);
        }
        Comparator<FieldInfo> capableFirst = Comparator.comparingInt(
                (FieldInfo field) -> structuralCapabilityScore(field.groupPath(), byGroup, childrenOf, repeatable))
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
            if (siblingAggregatePair(field.groupPath(), childrenOf, repeatable, byGroup) != null) {
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
            if (siblingAggregatePair(field.groupPath(), childrenOf, repeatable, byGroup) != null) {
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
            if (siblingAggregatePair(field.groupPath(), childrenOf, repeatable, byGroup) != null) {
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
                ruleScopeCapacity, repeatable, maxRepsByPath, byGroup, childrenOf, random));
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
        String[] constructPlan = assignConstructs(ruleFields, target, byGroup, childrenOf, repeatable);

        int[] walkUpBudget = {target.walkUpDistance().n()};
        int[] outerBudget = {target.iteration() == null ? 0 : target.iteration().outerCorrelation()};
        int[] parallelBudget = {target.iteration() == null ? 0 : target.iteration().parallelIteration()};
        return new Plan(ruleFields, constructPlan, byGroup, childrenOf, repeatable, target.booleanArity(),
                target.walkUpDistance(), walkUpBudget, outerBudget, parallelBudget);
    }

    private static List<Rule> renderRules(BoundModel model, Plan plan, long seed) {
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < plan.ruleFields().size(); i++) {
            int index = i + 1;
            rules.add(buildRule(model, plan.ruleFields().get(i), index, plan.constructPlan()[i], plan.arityDist(),
                    plan.walkUpDist(), plan.walkUpBudget(), plan.outerBudget(), plan.parallelBudget(), plan.byGroup(),
                    plan.childrenOf(), plan.repeatable(), SynthesisRandom.forPurpose(seed, "rule.build:" + index)));
        }
        return rules;
    }

    private static Rule buildRule(
            BoundModel model,
            FieldInfo field,
            int index,
            String construct,
            Distribution arityDistribution,
            Distribution walkUpDistribution,
            int[] walkUpBudget,
            int[] outerBudget,
            int[] parallelBudget,
            Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable,
            Random random) {
        Condition guard = simpleClause(model, field);
        List<Condition> rest = new ArrayList<>();
        Condition constructCondition = "FILTER".equals(construct) && outerBudget[0] > 0
                && outerCorrelationClause(model, field, byGroup, repeatable) instanceof Condition selfJoin
                        ? decrementAnd(outerBudget, selfJoin)
                        : constructClause(model, construct, field, byGroup, childrenOf, repeatable);
        boolean constructMustAlwaysHold = "UNIQUENESS".equals(construct);
        if (constructCondition != null && !constructMustAlwaysHold) {
            rest.add(constructCondition);
        }
        if (walkUpBudget[0] > 0) {
            String ancestor = ancestorField(field.groupPath(), sampleInt(walkUpDistribution, random), byGroup);
            if (ancestor != null) {
                rest.add(model.field(ancestor).filled());
                walkUpBudget[0]--;
            }
        }
        if (parallelBudget[0] > 0) {
            List<String> pair = siblingAggregatePair(field.groupPath(), childrenOf, repeatable, byGroup);
            if (pair != null) {
                rest.add(Dsl.sum(model.flatten(pair.get(0))).gt(0)
                        .and(Dsl.sum(model.flatten(pair.get(1))).gt(0)));
                parallelBudget[0]--;
            }
        }
        int arity = sampleInt(arityDistribution, random);
        List<FieldInfo> siblings = byGroup.getOrDefault(field.groupPath(), List.of()).stream()
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
        String errorFieldPath = "UNIQUENESS".equals(construct)
                ? uniquenessKeyField(field.groupPath(), childrenOf, repeatable, byGroup)
                : null;
        return model.rule(model.group(field.groupPath()))
                .name("R" + index)
                .errorField(model.field(errorFieldPath != null ? errorFieldPath : field.path()))
                .when(when)
                .code("C" + index)
                .severity(Severity.ERROR)
                .message(Dsl.en("synthetic rule " + index))
                .build();
    }

    private static String[] assignConstructs(
            List<FieldInfo> ruleFields,
            SynthesisProfile.Rules target,
            Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable) {
        String[] plan = new String[ruleFields.size()];
        Map<String, Map<String, Integer>> byScope = target.constructsByScope();
        List<List<Integer>> slotsByClass = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < ruleFields.size(); i++) {
            slotsByClass.get(scopeClass(ruleFields.get(i).groupPath(), repeatable)).add(i);
        }
        if (byScope != null && !byScope.isEmpty()) {
            String[] classNames = {"modelLevel", "perGroup", "perRepeatableRow"};
            for (int scopeClass = 0; scopeClass < 3; scopeClass++) {
                assignWithinSlots(plan, slotsByClass.get(scopeClass),
                        byScope.getOrDefault(classNames[scopeClass], Map.of()), ruleFields, byGroup, childrenOf,
                        repeatable);
            }
        } else {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < plan.length; i++) {
                all.add(i);
            }
            assignWithinSlots(plan, all, target.constructs(), ruleFields, byGroup, childrenOf, repeatable);
        }
        return plan;
    }

    private static void assignWithinSlots(
            String[] plan,
            List<Integer> slots,
            Map<String, Integer> target,
            List<FieldInfo> ruleFields,
            Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        for (String construct : STRUCTURAL_CONSTRUCTS) {
            remaining.put(construct, target.getOrDefault(construct, 0));
        }
        for (String construct : UBIQUITOUS_CONSTRUCTS) {
            remaining.put(construct, target.getOrDefault(construct, 0));
        }
        for (int index : slots) {
            if (plan[index] != null) {
                continue;
            }
            String scope = ruleFields.get(index).groupPath();
            String best = mostStarved(STRUCTURAL_CONSTRUCTS, remaining, target, scope, byGroup, childrenOf,
                    repeatable);
            if (best == null) {
                best = mostStarved(UBIQUITOUS_CONSTRUCTS, remaining, target, scope, byGroup, childrenOf, repeatable);
            }
            if (best != null) {
                plan[index] = best;
                remaining.merge(best, -1, Integer::sum);
            }
        }
    }

    private static String mostStarved(
            List<String> candidates,
            Map<String, Integer> remaining,
            Map<String, Integer> target,
            String scope,
            Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable) {
        String best = null;
        double bestFraction = 0;
        int bestTarget = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int remainder = remaining.get(candidate);
            int targetCount = target.getOrDefault(candidate, 0);
            if (remainder <= 0 || !scopeSupports(candidate, scope, byGroup, childrenOf, repeatable)) {
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

    static boolean scopeSupports(
            String construct,
            String scope,
            Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable) {
        List<FieldInfo> local = byGroup.getOrDefault(scope, List.of());
        return switch (construct) {
            case "GROUP_FILL" -> groupFillTarget(scope, childrenOf, repeatable) != null;
            case "AGGREGATE", "FILTER" -> aggregateOperand(scope, childrenOf, repeatable, byGroup) != null;
            case "CURRENT_REPETITION" -> repeatable.contains(scope);
            case "UNIQUENESS" -> uniquenessKeyField(scope, childrenOf, repeatable, byGroup) != null;
            case "ARITHMETIC" -> local.stream().anyMatch(field -> field.kind() == ValueKind.NUMBER);
            case "DATE_MATH" -> local.stream().anyMatch(field -> DATE_MATH_CLASSES.contains(field.temporalClass()));
            case "VALUE_LIST" -> local.stream()
                    .anyMatch(field -> field.kind() == ValueKind.STRING || field.kind() == ValueKind.ENUMERATION);
            case "QUANTIFIER" -> local.size() >= 2
                    || aggregateOperand(scope, childrenOf, repeatable, byGroup) != null;
            case "CUSTOM" -> true;
            default -> false;
        };
    }

    private static int structuralCapabilityScore(
            String scope,
            Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable) {
        int score = 0;
        for (String construct : STRUCTURAL_CONSTRUCTS) {
            if (scopeSupports(construct, scope, byGroup, childrenOf, repeatable)) {
                score++;
            }
        }
        return score;
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

    private static String ancestorField(
            String scope, int targetDistance, Map<String, List<FieldInfo>> byGroup) {
        List<String> ancestors = new ArrayList<>();
        for (String group = parentOf(scope); ; ) {
            ancestors.add(group);
            String parent = parentOf(group);
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
            List<FieldInfo> fields = byGroup.get(ancestors.get(index));
            if (fields != null && !fields.isEmpty()) {
                return fields.get(0).path();
            }
        }
        for (int index = start - 1; index >= 0; index--) {
            List<FieldInfo> fields = byGroup.get(ancestors.get(index));
            if (fields != null && !fields.isEmpty()) {
                return fields.get(0).path();
            }
        }
        return null;
    }

    private static Condition outerCorrelationClause(
            BoundModel model,
            FieldInfo field,
            Map<String, List<FieldInfo>> byGroup,
            Set<String> repeatable) {
        String scope = field.groupPath();
        if (!repeatable.contains(scope)) {
            return null;
        }
        FieldInfo number = byGroup.getOrDefault(scope, List.of()).stream()
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

    private static Condition constructClause(
            BoundModel model,
            String construct,
            FieldInfo field,
            Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable) {
        if (construct == null) {
            return null;
        }
        String scope = field.groupPath();
        return switch (construct) {
            case "VALUE_LIST" -> valueListCondition(model, scope, byGroup);
            case "QUANTIFIER" -> quantifierCondition(model, scope, byGroup, childrenOf, repeatable);
            case "GROUP_FILL" -> {
                String group = groupFillTarget(scope, childrenOf, repeatable);
                yield group == null ? null : model.group(group).filled();
            }
            case "AGGREGATE" -> {
                String flattened = aggregateOperand(scope, childrenOf, repeatable, byGroup);
                yield flattened == null ? null : Dsl.sum(model.flatten(flattened)).gt(0);
            }
            case "FILTER" -> {
                String flattened = aggregateOperand(scope, childrenOf, repeatable, byGroup);
                if (flattened == null) {
                    yield null;
                }
                String cell = flattened.replace("*", "");
                yield Dsl.allFieldsFilled(model.flatten(flattened).having(model.num(cell).gt(0)));
            }
            case "DATE_MATH" -> dateMathCondition(model, scope, byGroup);
            case "CUSTOM" -> Dsl.customCondition("SynthCustomCheck");
            case "CURRENT_REPETITION" -> repeatable.contains(scope)
                    ? Dsl.currentRepetition(model.group(scope)).gt(0)
                    : null;
            case "UNIQUENESS" -> {
                String key = uniquenessKeyField(scope, childrenOf, repeatable, byGroup);
                yield key == null
                        ? null
                        : model.group(parentOf(key)).filled().and(Dsl.repetitionNotUnique(model.field(key)));
            }
            case "ARITHMETIC" -> arithmeticCondition(model, scope, byGroup);
            default -> null;
        };
    }

    private static Condition valueListCondition(
            BoundModel model, String scope, Map<String, List<FieldInfo>> byGroup) {
        for (FieldInfo candidate : byGroup.getOrDefault(scope, List.of())) {
            if (candidate.kind() == ValueKind.STRING || candidate.kind() == ValueKind.ENUMERATION) {
                return valueListClause(model, candidate.path(), candidate.kind());
            }
        }
        return null;
    }

    private static Condition quantifierCondition(
            BoundModel model,
            String scope,
            Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable) {
        String flattened = aggregateOperand(scope, childrenOf, repeatable, byGroup);
        if (flattened != null) {
            return Dsl.allFieldsFilled(model.flatten(flattened));
        }
        List<FieldInfo> siblings = byGroup.getOrDefault(scope, List.of());
        return siblings.size() >= 2
                ? Dsl.allFieldsFilled(model.field(siblings.get(0).path()), model.field(siblings.get(1).path()))
                : null;
    }

    static Condition dateMathCondition(
            BoundModel model, String scope, Map<String, List<FieldInfo>> byGroup) {
        for (FieldInfo candidate : byGroup.getOrDefault(scope, List.of())) {
            if (candidate.temporalClass() == ValueKind.DATE) {
                return Dsl.yearFromDate(model.date(candidate.path())).gt(2000);
            }
            if (candidate.temporalClass() == ValueKind.DATE_TIME) {
                return Dsl.yearFromDate(model.dateTime(candidate.path())).gt(2000);
            }
        }
        return null;
    }

    private static Condition arithmeticCondition(
            BoundModel model, String scope, Map<String, List<FieldInfo>> byGroup) {
        for (FieldInfo candidate : byGroup.getOrDefault(scope, List.of())) {
            if (candidate.kind() == ValueKind.NUMBER) {
                return Dsl.abs(new NumberExpr(model.num(candidate.path()).value())).gt(0);
            }
        }
        return null;
    }

    private static String groupFillTarget(
            String scope, Map<String, List<GroupInfo>> childrenOf, Set<String> repeatable) {
        for (GroupInfo child : childrenOf.getOrDefault(scope, List.of())) {
            if (!repeatable.contains(child.path())) {
                return child.path();
            }
        }
        return null;
    }

    private static Map<String, List<GroupInfo>> childrenOf(BoundModel model) {
        Map<String, List<GroupInfo>> childrenOf = new LinkedHashMap<>();
        model.groups().forEach(group -> {
            String parent = parentOf(group.path());
            if (!parent.equals(group.path())) {
                childrenOf.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(group);
            }
        });
        return childrenOf;
    }

    private static String aggregateOperand(
            String group,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable,
            Map<String, List<FieldInfo>> byGroup) {
        return aggregateOperand(group, childrenOf, repeatable, byGroup, null);
    }

    private static String aggregateOperand(
            String group,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable,
            Map<String, List<FieldInfo>> byGroup,
            Integer preferredScale) {
        if (preferredScale != null) {
            String matched = aggregateOperandOfScale(group, childrenOf, repeatable, byGroup, preferredScale);
            if (matched != null) {
                return matched;
            }
        }
        return aggregateOperandOfScale(group, childrenOf, repeatable, byGroup, null);
    }

    private static String aggregateOperandOfScale(
            String group,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable,
            Map<String, List<FieldInfo>> byGroup,
            Integer requiredScale) {
        for (GroupInfo child : childrenOf.getOrDefault(group, List.of())) {
            if (repeatable.contains(child.path())) {
                for (FieldInfo field : byGroup.getOrDefault(child.path(), List.of())) {
                    if (field.kind() == ValueKind.NUMBER
                            && (requiredScale == null || field.scale() == requiredScale)) {
                        return child.path() + "*/" + field.name();
                    }
                }
            } else {
                String deeper = aggregateOperandOfScale(child.path(), childrenOf, repeatable, byGroup,
                        requiredScale);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    private static String uniquenessKeyField(
            String group,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable,
            Map<String, List<FieldInfo>> byGroup) {
        for (GroupInfo child : childrenOf.getOrDefault(group, List.of())) {
            if (repeatable.contains(child.path())) {
                List<FieldInfo> local = byGroup.getOrDefault(child.path(), List.of());
                if (!local.isEmpty()) {
                    return local.get(0).path();
                }
            } else {
                String deeper = uniquenessKeyField(child.path(), childrenOf, repeatable, byGroup);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    private static List<String> siblingAggregatePair(
            String scope,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable,
            Map<String, List<FieldInfo>> numberFields) {
        List<String> cells = new ArrayList<>();
        List<String> repetitions = new ArrayList<>();
        collectRepeatableCells(scope, childrenOf, repeatable, numberFields, cells, repetitions);
        for (int first = 0; first < repetitions.size(); first++) {
            for (int second = first + 1; second < repetitions.size(); second++) {
                String left = repetitions.get(first);
                String right = repetitions.get(second);
                if (!EntityPaths.isStrictlyInside(left, right)
                        && !EntityPaths.isStrictlyInside(right, left)) {
                    return List.of(cells.get(first), cells.get(second));
                }
            }
        }
        return null;
    }

    private static void collectRepeatableCells(
            String group,
            Map<String, List<GroupInfo>> childrenOf,
            Set<String> repeatable,
            Map<String, List<FieldInfo>> numberFields,
            List<String> cells,
            List<String> repetitions) {
        for (GroupInfo child : childrenOf.getOrDefault(group, List.of())) {
            if (repeatable.contains(child.path())) {
                for (FieldInfo field : numberFields.getOrDefault(child.path(), List.of())) {
                    if (field.kind() == ValueKind.NUMBER) {
                        cells.add(child.path() + "*/" + field.name());
                        repetitions.add(child.path());
                        break;
                    }
                }
            } else {
                collectRepeatableCells(child.path(), childrenOf, repeatable, numberFields, cells, repetitions);
            }
        }
    }

    private static Condition valueListClause(BoundModel model, String path, ValueKind kind) {
        return switch (kind) {
            case NUMBER -> model.num(path).in(0, 1);
            case ENUMERATION -> model.enumOf(path).in("V1", "V2");
            default -> model.str(path).in("V1", "V2");
        };
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
            Set<String> repeatable,
            Map<String, Integer> maxRepetitionsByPath,
            Map<String, List<FieldInfo>> byGroup,
            Map<String, List<GroupInfo>> childrenOf,
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
                double difference = Math.abs(chainProductOf(scope, repeatable, maxRepetitionsByPath) - (long) target);
                boolean capable = aggregateOperand(scope, childrenOf, repeatable, byGroup) != null;
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
            String parent = parentOf(path);
            if (parent.equals(path)) {
                return product;
            }
            path = parent;
        }
    }

    private static int scopeClass(String groupPath, Set<String> repeatable) {
        for (String path = groupPath; ; ) {
            if (repeatable.contains(path)) {
                return 2;
            }
            String parent = parentOf(path);
            if (parent.equals(path)) {
                break;
            }
            path = parent;
        }
        return depthOf(groupPath) <= 1 ? 0 : 1;
    }

    private static int depthOf(String path) {
        int depth = 0;
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : path;
    }

}
