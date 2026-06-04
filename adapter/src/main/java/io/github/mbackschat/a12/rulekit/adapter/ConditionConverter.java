package io.github.mbackschat.a12.rulekit.adapter;

import java.util.List;

import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.IEntityId;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IComparisonCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICompositeCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IEntityListCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IConstantOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IFieldIdOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.ComparisonOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.FieldIdOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.LogOp;

import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * The F10 read direction: a kernel {@link ICondition} (parsed by the adapter) → rulekit's typed
 * {@link Condition} AST. It re-authors through the {@link BoundModel} handle API rather than
 * hand-constructing record nodes, so every produced node carries the D14 invariant (catalog id +
 * model-resolved refs + provenance) for free, exactly as if a human had built it.
 *
 * <p>Recursive {@code instanceof} dispatch over the kernel tree; any construct not covered raises
 * {@link UnsupportedConstructException} — fail-fast, never a silent loss (N8). This first slice
 * covers the corpus-frequent core: composite And/Or, comparisons (field vs constant / field vs
 * field) on number/string/enum fields, and single-field presence. Coverage grows by corpus priority.
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
        if (entities.size() == 1 && entities.get(0).isField()) {
            String path = entities.get(0).getFullName();
            return switch (entityList.getConditionName()) {
                case AllFieldsFilled -> model.field(path).filled();
                case NoFieldFilled -> model.field(path).notFilled();
                default -> throw unsupported("single-field entity-list name "
                        + entityList.getConditionName(), entityList);
            };
        }
        throw unsupported("multi-entity / group entity-list condition ("
                + entityList.getConditionName() + ")", entityList);
    }

    private Condition convertComparison(IComparisonCondition comparison) {
        IOperation left = comparison.getOperation1();
        if (!(left instanceof IFieldIdOperation field) || field.getOperatorName() != FieldIdOperationName.FieldValue) {
            throw unsupported("comparison whose left side is not a plain field value", comparison);
        }
        String path = field.getField().getFullName();
        ValueKind kind = model.fieldAt(path)
                .orElseThrow(() -> unsupported("comparison on unknown field " + path, comparison))
                .kind();
        ComparisonOperation op = comparison.getCmpOp();
        IOperation right = comparison.getOperation2();
        return switch (kind) {
            case NUMBER -> numberComparison(model.num(path), op, right, comparison);
            case STRING -> stringComparison(model.str(path), op, right, comparison);
            case ENUMERATION -> enumComparison(model.enumOf(path), op, right, comparison);
            default -> throw unsupported("comparison on a " + kind + " field", comparison);
        };
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
        if (op != ComparisonOperation.EQ) {
            throw unsupported("string comparison operator " + op + " (only equality is modeled)", origin);
        }
        if (right instanceof IConstantOperation constant) {
            return left.eq(constant.getValue());
        }
        if (right instanceof IFieldIdOperation rf && rf.getOperatorName() == FieldIdOperationName.FieldValue) {
            return left.eq(model.str(rf.getField().getFullName()));
        }
        throw unsupported("string equality against a non-field, non-constant operand", origin);
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
