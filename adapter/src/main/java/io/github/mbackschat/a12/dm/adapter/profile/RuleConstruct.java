package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.List;
import java.util.Set;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;

/**
 * A construct a synthesized rule's condition carries, counted in a profile under its name
 * ({@code SynthesisProfile.Rules.constructs}). Each one says whether a scope can carry it and builds it there,
 * both by exhaustive switch, so a new construct cannot be offered without a clause or built without a test.
 */
enum RuleConstruct {
    GROUP_FILL, AGGREGATE, FILTER, CURRENT_REPETITION, ARITHMETIC, DATE_MATH, UNIQUENESS, CUSTOM, QUANTIFIER, VALUE_LIST;

    /** The constructs that need a scope of the right shape, tried first and in this order. */
    static final List<RuleConstruct> STRUCTURAL =
            List.of(GROUP_FILL, AGGREGATE, FILTER, CURRENT_REPETITION, ARITHMETIC, DATE_MATH, UNIQUENESS);
    /** The constructs nearly any scope can carry, tried when no structural one fits. */
    static final List<RuleConstruct> UBIQUITOUS = List.of(CUSTOM, QUANTIFIER, VALUE_LIST);

    /**
     * The declared classes DATE_MATH builds on, which {@code BoundModel.date/dateTime} admit by (DEV-API-GAPS DG132),
     * so a scope never offers DATE_MATH where it cannot build it.
     */
    private static final Set<ValueKind> DATE_MATH_CLASSES = Set.of(ValueKind.DATE, ValueKind.DATE_TIME);

    /** Whether {@code scope} can carry this construct. */
    boolean supportedIn(String scope, ScopeShape shape) {
        List<FieldInfo> local = shape.fieldsOf(scope);
        return switch (this) {
            case GROUP_FILL -> shape.groupFillTarget(scope) != null;
            case AGGREGATE, FILTER -> shape.aggregateOperand(scope) != null;
            case CURRENT_REPETITION -> shape.repeatable().contains(scope);
            case UNIQUENESS -> shape.uniquenessKeyField(scope) != null;
            case ARITHMETIC -> local.stream().anyMatch(field -> field.kind() == ValueKind.NUMBER);
            case DATE_MATH -> local.stream().anyMatch(field -> DATE_MATH_CLASSES.contains(field.temporalClass()));
            case VALUE_LIST -> local.stream()
                    .anyMatch(field -> field.kind() == ValueKind.STRING || field.kind() == ValueKind.ENUMERATION);
            case QUANTIFIER -> local.size() >= 2 || shape.aggregateOperand(scope) != null;
            case CUSTOM -> true;
        };
    }

    /** This construct's clause in {@code scope}, or {@code null} when the scope cannot carry it. */
    Condition clause(BoundModel model, String scope, ScopeShape shape) {
        return switch (this) {
            case VALUE_LIST -> valueList(model, shape.fieldsOf(scope));
            case QUANTIFIER -> quantifier(model, scope, shape);
            case GROUP_FILL -> {
                String group = shape.groupFillTarget(scope);
                yield group == null ? null : model.group(group).filled();
            }
            case AGGREGATE -> {
                String flattened = shape.aggregateOperand(scope);
                yield flattened == null ? null : Dsl.sum(model.flatten(flattened)).gt(0);
            }
            case FILTER -> {
                String flattened = shape.aggregateOperand(scope);
                yield flattened == null ? null : Dsl.allFieldsFilled(
                        model.flatten(flattened).having(model.num(flattened.replace("*", "")).gt(0)));
            }
            case DATE_MATH -> dateMath(model, shape.fieldsOf(scope));
            case CUSTOM -> Dsl.customCondition("SynthCustomCheck");
            case CURRENT_REPETITION -> shape.repeatable().contains(scope)
                    ? Dsl.currentRepetition(model.group(scope)).gt(0)
                    : null;
            case UNIQUENESS -> {
                String key = shape.uniquenessKeyField(scope);
                yield key == null ? null
                        : model.group(ScopeShape.parentOf(key)).filled().and(Dsl.repetitionNotUnique(model.field(key)));
            }
            case ARITHMETIC -> arithmetic(model, shape.fieldsOf(scope));
        };
    }

    private static Condition valueList(BoundModel model, List<FieldInfo> local) {
        for (FieldInfo candidate : local) {
            if (candidate.kind() == ValueKind.ENUMERATION) {
                return model.enumOf(candidate.path()).in("V1", "V2");
            }
            if (candidate.kind() == ValueKind.STRING) {
                return model.str(candidate.path()).in("V1", "V2");
            }
        }
        return null;
    }

    private static Condition quantifier(BoundModel model, String scope, ScopeShape shape) {
        String flattened = shape.aggregateOperand(scope);
        if (flattened != null) {
            return Dsl.allFieldsFilled(model.flatten(flattened));
        }
        List<FieldInfo> siblings = shape.fieldsOf(scope);
        return siblings.size() >= 2
                ? Dsl.allFieldsFilled(model.field(siblings.get(0).path()), model.field(siblings.get(1).path()))
                : null;
    }

    private static Condition dateMath(BoundModel model, List<FieldInfo> local) {
        for (FieldInfo candidate : local) {
            if (candidate.temporalClass() == ValueKind.DATE) {
                return Dsl.yearFromDate(model.date(candidate.path())).gt(2000);
            }
            if (candidate.temporalClass() == ValueKind.DATE_TIME) {
                return Dsl.yearFromDate(model.dateTime(candidate.path())).gt(2000);
            }
        }
        return null;
    }

    private static Condition arithmetic(BoundModel model, List<FieldInfo> local) {
        for (FieldInfo candidate : local) {
            if (candidate.kind() == ValueKind.NUMBER) {
                return Dsl.abs(new NumberExpr(model.num(candidate.path()).value())).gt(0);
            }
        }
        return null;
    }
}
