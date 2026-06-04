package io.github.mbackschat.a12.rulekit.adapter;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.IEntityId;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.IParseTreeEntity;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IComparisonCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICompositeCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IEntityListCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IValueListCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IConstantOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IEntityListOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IFieldIdOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IGroupOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.ComparisonOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.EntityListConditionName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.EntityListOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.FieldIdOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.GroupOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.LogOp;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.ValueListConditionName;

import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.rulekit.dsl.RepetitionNotUniqueCondition;
import io.github.mbackschat.a12.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * The F10 read direction: a kernel {@link ICondition} (parsed by the adapter) → rulekit's typed
 * {@link Condition} AST. It re-authors through the {@link BoundModel} handle API rather than
 * hand-constructing record nodes, so every produced node carries the D14 invariant (catalog id +
 * model-resolved refs + provenance) for free, exactly as if a human had built it.
 *
 * <p>Recursive {@code instanceof} dispatch over the kernel tree; any construct not covered raises
 * {@link UnsupportedConstructException} — fail-fast, never a silent loss (N8). Coverage (by corpus
 * priority): composite And/Or; comparisons on number/string/enum fields vs a constant or another
 * field; string patterns ({@code WM}/{@code NM} → {@code matches}/{@code violates}); single-field
 * and single-group presence; and the field-list and group-list quantifier families. Still
 * fail-fast (later slices): value-list membership, aggregates/{@code Having}, dates, tolerance
 * comparisons, operand-wrapping field ops ({@code Length}/{@code Round…}).
 *
 * <p>Kernel-canonicalization note (verified): the kernel parses single-field {@code FieldFilled(x)} /
 * {@code FieldNotFilled(x)} into an {@code IEntityListCondition} named {@code AllFieldsFilled} /
 * {@code NoFieldFilled} with a one-element list — so a single-field entity-list condition converts
 * back to {@code .filled()} / {@code .notFilled()}.
 */
final class ConditionConverter {

    private final BoundModel model;

    ConditionConverter(BoundModel model) {
        this.model = model;
    }

    Condition convert(ICondition node) {
        if (node instanceof ICompositeCondition composite) {
            return convertComposite(composite);
        }
        if (node instanceof IComparisonCondition comparison) {
            return convertComparison(comparison);
        }
        if (node instanceof IEntityListCondition entityList) {
            return convertEntityList(entityList);
        }
        if (node instanceof IValueListCondition valueList) {
            return convertValueList(valueList);
        }
        throw unsupported("condition", node);
    }

    private Condition convertComposite(ICompositeCondition composite) {
        List<ICondition> children = composite.getConditionList();
        if (children.isEmpty()) {
            throw unsupported("empty composite condition", composite);
        }
        boolean and = composite.getLogOp() == LogOp.And;
        Condition acc = convert(children.get(0));
        for (int i = 1; i < children.size(); i++) {
            Condition next = convert(children.get(i));
            acc = and ? acc.and(next) : acc.or(next);
        }
        return acc;
    }

    private Condition convertEntityList(IEntityListCondition entityList) {
        List<IEntityId> entities = entityList.getEntityList().getEntityIdList();
        if (entities.isEmpty()) {
            throw unsupported("empty entity-list condition", entityList);
        }
        List<String> paths = entities.stream().map(IEntityId::getFullName).toList();
        EntityListConditionName name = entityList.getConditionName();
        boolean single = paths.size() == 1;
        String[] p = paths.toArray(String[]::new);
        if (entities.get(0).isField()) {
            return switch (name) {
                // single-field FieldFilled/FieldNotFilled canonicalize to AllFieldsFilled/NoFieldFilled[x]
                case AllFieldsFilled -> single ? model.field(p[0]).filled() : model.allFieldsFilled(p);
                case NoFieldFilled -> single ? model.field(p[0]).notFilled() : model.noFieldFilled(p);
                case AtLeastOneFieldFilled -> model.atLeastOneFieldFilled(p);
                case NotAllFieldsFilled -> model.notAllFieldsFilled(p);
                case FieldsNotCollectivelyFilled -> model.fieldsNotCollectivelyFilled(p);
                case NotExactlyOneFieldFilled -> model.notExactlyOneFieldFilled(p);
                case FieldValuesNotUnique -> {
                    FieldRef[] keys = fieldRefs(paths);
                    yield Dsl.fieldValuesNotUnique(keys[0], Arrays.copyOfRange(keys, 1, keys.length));
                }
                case RepetitionNotUnique -> {
                    FieldRef[] keys = fieldRefs(paths);
                    RepetitionNotUniqueCondition rnu =
                            Dsl.repetitionNotUnique(keys[0], Arrays.copyOfRange(keys, 1, keys.length));
                    // a kernel `… From group` scope rides on getAdditionalGroupReference()
                    yield entityList.getAdditionalGroupReference()
                            .map(g -> (Condition) rnu.from(model.group(g.getFullName())))
                            .orElse(rnu);
                }
                default -> throw unsupported("field-list condition " + name, entityList);
            };
        }
        return switch (name) {
            // single-group GroupFilled/GroupNotFilled canonicalize to AllGroupsFilled/NoGroupFilled[g]
            case AllGroupsFilled -> single ? model.group(p[0]).filled() : groups(paths, Dsl::allGroupsFilled);
            case NoGroupFilled -> single ? model.group(p[0]).notFilled() : groups(paths, Dsl::noGroupFilled);
            case AtLeastOneGroupFilled -> groups(paths, Dsl::atLeastOneGroupFilled);
            case NotAllGroupsFilled -> groups(paths, Dsl::notAllGroupsFilled);
            case GroupsNotCollectivelyFilled -> groups(paths, Dsl::groupsNotCollectivelyFilled);
            default -> throw unsupported("group-list condition " + name, entityList);
        };
    }

    /** Resolve group paths to {@code GroupRef}s and apply a {@code (first, rest…)} quantifier factory. */
    private Condition groups(List<String> paths, BiFunction<GroupRef, GroupRef[], Condition> factory) {
        List<GroupRef> refs = paths.stream().map(model::group).toList();
        return factory.apply(refs.get(0), refs.subList(1, refs.size()).toArray(GroupRef[]::new));
    }

    private FieldRef[] fieldRefs(List<String> paths) {
        return paths.stream().map(model::field).toArray(FieldRef[]::new);
    }

    private Condition convertValueList(IValueListCondition valueList) {
        String path = valueList.getField().getFullName();
        ValueKind kind = model.fieldAt(path)
                .orElseThrow(() -> unsupported("value-list on unknown field " + path, valueList))
                .kind();
        List<String> values = valueList.getValueList().stream().map(op -> constantValue(op, valueList)).toList();
        if (values.isEmpty()) {
            throw unsupported("empty value list", valueList);
        }
        boolean included = valueList.getConditionName() == ValueListConditionName.FieldValueIncludedInValueList;
        return switch (kind) {
            case STRING -> {
                StringRef r = model.str(path);
                String[] rest = values.subList(1, values.size()).toArray(String[]::new);
                yield included ? r.in(values.get(0), rest) : r.notIn(values.get(0), rest);
            }
            case ENUMERATION -> {
                EnumRef r = model.enumOf(path);
                String[] rest = values.subList(1, values.size()).toArray(String[]::new);
                yield included ? r.in(values.get(0), rest) : r.notIn(values.get(0), rest);
            }
            case NUMBER -> {
                NumberRef r = model.num(path);
                long[] longs = new long[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    try {
                        longs[i] = Long.parseLong(values.get(i));
                    } catch (NumberFormatException e) {
                        throw unsupported("number value-list with a non-integer value '"
                                + values.get(i) + "'", valueList);
                    }
                }
                long[] rest = Arrays.copyOfRange(longs, 1, longs.length);
                yield included ? r.in(longs[0], rest) : r.notIn(longs[0], rest);
            }
            default -> throw unsupported("value-list membership on a " + kind + " field", valueList);
        };
    }

    private String constantValue(IOperation op, ICondition origin) {
        if (op instanceof IConstantOperation constant) {
            return constant.getValue();
        }
        throw unsupported("value-list entry that is not a constant", origin);
    }

    private Condition convertComparison(IComparisonCondition comparison) {
        IOperation left = comparison.getOperation1();
        ComparisonOperation op = comparison.getCmpOp();
        IOperation right = comparison.getOperation2();
        if (left instanceof IEntityListOperation aggregate) {
            return numberExprComparison(convertAggregate(aggregate), op, right, comparison);
        }
        if (left instanceof IGroupOperation groupOp
                && groupOp.getOperatorName() == GroupOperationName.CurrentRepetition) {
            NumberExpr expr = Dsl.currentRepetition(model.group(groupOp.getGroup().getFullName()));
            return numberExprComparison(expr, op, right, comparison);
        }
        if (left instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            String path = field.getField().getFullName();
            ValueKind kind = model.fieldAt(path)
                    .orElseThrow(() -> unsupported("comparison on unknown field " + path, comparison))
                    .kind();
            return switch (kind) {
                case NUMBER -> numberComparison(model.num(path), op, right, comparison);
                case STRING -> stringComparison(model.str(path), op, right, comparison);
                case ENUMERATION -> enumComparison(model.enumOf(path), op, right, comparison);
                default -> throw unsupported("comparison on a " + kind + " field", comparison);
            };
        }
        throw unsupported("comparison whose left side is not a plain field value or an aggregate", comparison);
    }

    /** An aggregate operand (`Sum`/`MaxValue`/… over a wildcard field scope, optionally `Having`-filtered). */
    private NumberExpr convertAggregate(IEntityListOperation aggregate) {
        List<IEntityId> entities = aggregate.getEntityList().getEntityIdList();
        EntityListOperationName name = aggregate.getOperatorName();
        if (entities.size() != 1 || !entities.get(0).isField()) {
            throw unsupported("aggregate " + name + " over a non-single-field scope", aggregate);
        }
        FieldListRef scope = flattenScope(entities.get(0));
        return switch (name) {
            case Sum -> Dsl.sum(scope);
            case MaxValue -> Dsl.maxValue(scope);
            case MinValue -> Dsl.minValue(scope);
            case NumberOfDifferentValues -> Dsl.numberOfDifferentValues(scope);
            default -> throw unsupported("aggregate operator " + name, aggregate);
        };
    }

    /** Rebuild the wildcard scope (e.g. {@code Items} flattened) from the resolved path + indices, plus any Having filter. */
    private FieldListRef flattenScope(IEntityId entity) {
        FieldListRef scope = model.flatten(reconstructStarPath(entity));
        ICondition filter = entity.getFilterCondition();
        return filter == null ? scope : scope.having(convert(filter));
    }

    /** {@code getFullName()} drops the wildcard; re-insert {@code *} after each segment marked WILDCARD in indices. */
    private static String reconstructStarPath(IEntityId entity) {
        String[] segments = entity.getFullName().split("/", -1);   // ["", "Order", "Items", "Count"]
        long[] indices = entity.getIndices();                       // [1, 0, 1] — 0 == WILDCARD at "Items"
        StringBuilder path = new StringBuilder();
        for (int i = 1; i < segments.length; i++) {
            path.append('/').append(segments[i]);
            int idx = i - 1;
            if (idx < indices.length && indices[idx] == IParseTreeEntity.WILDCARD) {
                path.append('*');
            }
        }
        return path.toString();
    }

    private Condition numberExprComparison(NumberExpr expr, ComparisonOperation op, IOperation right,
            ICondition origin) {
        if (right instanceof IConstantOperation constant) {
            String v = constant.getValue();
            return switch (op) {
                case GT -> expr.gt(v);
                case LT -> expr.lt(v);
                case GE -> expr.ge(v);
                case LE -> expr.le(v);
                case EQ -> expr.eq(v);
                case NE -> expr.ne(v);
                default -> throw unsupported("aggregate comparison operator " + op, origin);
            };
        }
        throw unsupported("aggregate compared against a non-constant operand", origin);
    }

    private Condition numberComparison(NumberRef left, ComparisonOperation op, IOperation right,
            ICondition origin) {
        if (right instanceof IConstantOperation constant) {
            String v = constant.getValue();
            return switch (op) {
                case GT -> left.gt(v);
                case LT -> left.lt(v);
                case GE -> left.ge(v);
                case LE -> left.le(v);
                case EQ -> left.eq(v);
                case NE -> left.ne(v);
                default -> throw unsupported("number comparison operator " + op, origin);
            };
        }
        if (right instanceof IFieldIdOperation rf && rf.getOperatorName() == FieldIdOperationName.FieldValue) {
            NumberRef other = model.num(rf.getField().getFullName());
            return switch (op) {
                case GT -> left.gt(other);
                case LT -> left.lt(other);
                case GE -> left.ge(other);
                case LE -> left.le(other);
                case EQ -> left.eq(other);
                case NE -> left.ne(other);
                default -> throw unsupported("number field comparison operator " + op, origin);
            };
        }
        throw unsupported("number comparison against a non-field, non-constant operand", origin);
    }

    private Condition stringComparison(StringRef left, ComparisonOperation op, IOperation right,
            ICondition origin) {
        if (right instanceof IConstantOperation constant) {
            String v = constant.getValue();
            return switch (op) {
                case EQ -> left.eq(v);
                case WM -> left.matches(v);   // [field] WM "pattern"  → PatternMatched
                case NM -> left.violates(v);  // [field] NM "pattern"  → PatternViolated
                default -> throw unsupported("string comparison operator " + op, origin);
            };
        }
        if (op == ComparisonOperation.EQ && right instanceof IFieldIdOperation rf
                && rf.getOperatorName() == FieldIdOperationName.FieldValue) {
            return left.eq(model.str(rf.getField().getFullName()));
        }
        throw unsupported("string comparison against a non-field, non-constant operand", origin);
    }

    private Condition enumComparison(EnumRef left, ComparisonOperation op, IOperation right,
            ICondition origin) {
        if (op == ComparisonOperation.EQ && right instanceof IConstantOperation constant) {
            return left.is(constant.getValue());
        }
        throw unsupported("enum comparison (only `== \"value\"` is modeled), operator " + op, origin);
    }

    private static UnsupportedConstructException unsupported(String what, Object node) {
        String kernelType = node.getClass().getInterfaces().length > 0
                ? node.getClass().getInterfaces()[0].getSimpleName()
                : node.getClass().getSimpleName();
        return new UnsupportedConstructException("F10 converter does not yet handle " + what
                + " [kernel node: " + kernelType + "]");
    }
}
