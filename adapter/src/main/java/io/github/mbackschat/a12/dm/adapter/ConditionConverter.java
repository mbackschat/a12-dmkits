package io.github.mbackschat.a12.dm.adapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;

import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.IEntityId;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.IParseTreeEntity;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IApplicationCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IComparisonCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICompositeCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.ICompositeOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IEntityListAndValueListCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IEntityListCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IRootGroupCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IValueListCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IBoolConstantOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IConstantOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IEntityListOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IFieldIdOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IGroupOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IPointInTimeOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.date.IDateDiffOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.BoolConstantOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.ComparisonOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.EntityListAndValueListConditionName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.DateDiffOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.EntityListConditionName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.EntityListOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.FieldIdOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.GroupOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.LogOp;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.PointInTimeOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.ValueListConditionName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.ast.ASTVisitor;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.RepetitionNotUniqueCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.core.ValueKind;

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

    /**
     * Reject — up front, before any conversion — a construct the converter would otherwise <em>silently
     * drop</em> (N8: never silently corrupt). Today that is the {@code $} outer-iteration correlation
     * (D13, unmodeled): field-vs-field comparison round-trips, but the {@code $} rule-iteration marker
     * has no AST node, so converting {@code [$/Order/Quantity]} would emit a non-correlated
     * {@code [/Order/Quantity]} — a different rule. The kernel allows {@code $} only inside {@code Having}
     * filters, and its {@code traverse()} does not descend into filters, so we walk filters explicitly
     * (breadth-first, including filters nested inside filters).
     *
     * @throws UnsupportedConstructException if any reference is outer-correlated — caught by
     *         {@link KernelAdapter#ingest} and surfaced as {@link Ingest.Opaque} (preserved verbatim).
     */
    void refuseUnmodeledConstructs(ICondition root) {
        Deque<ICondition> filters = new ArrayDeque<>();
        filters.add(root);
        while (!filters.isEmpty()) {
            List<ICondition> nested = new ArrayList<>();
            filters.poll().traverse(new ASTVisitor() {
                @Override
                public boolean visit(IEntityId id) {
                    if (id.isOuter()) {
                        throw new UnsupportedConstructException("F10 converter does not model the '$' "
                                + "outer-iteration correlation on '" + id.getFullName() + "' — field-vs-field "
                                + "round-trips, but the $ rule-iteration marker (D13) has no AST node, so "
                                + "converting would silently drop it [kernel node: outer correlation ($)]");
                    }
                    ICondition filter = id.getFilterCondition();
                    if (filter != null) {
                        nested.add(filter);
                    }
                    return true;
                }
            });
            filters.addAll(nested);
        }
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
        if (node instanceof IRootGroupCondition rootGroup) {
            return convertRootGroup(rootGroup);
        }
        if (node instanceof IApplicationCondition application) {
            // a custom (host-delegated) condition is name-only (IApplicationCondition.getName()); the typed
            // CustomConditionRef captures it exactly, so "X And CustomCondition Y" stays fully editable.
            return Dsl.customCondition(application.getName());
        }
        if (node instanceof IEntityListAndValueListCondition entityAndValueList) {
            return convertEntityListAndValueList(entityAndValueList);
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
            // field-fill quantifiers over a GROUP scope (the fields inside the group, all reps if starred)
            case AllFieldsFilled -> groupScopedFieldFill(entities, Dsl::allFieldsFilled);
            case AtLeastOneFieldFilled -> groupScopedFieldFill(entities, Dsl::atLeastOneFieldFilled);
            case NoFieldFilled -> groupScopedFieldFill(entities, Dsl::noFieldFilled);
            default -> throw unsupported("group-list condition " + name, entityList);
        };
    }

    /** Build group-scope field-fill: each entity → a {@link GroupListRef} (starred per the wildcard indices). */
    private Condition groupScopedFieldFill(List<IEntityId> entities,
            BiFunction<GroupListRef, GroupListRef[], Condition> factory) {
        GroupListRef[] scopes = entities.stream()
                .map(e -> model.flattenGroup(reconstructStarPath(e))).toArray(GroupListRef[]::new);
        return factory.apply(scopes[0], Arrays.copyOfRange(scopes, 1, scopes.length));
    }

    /**
     * A root-group presence predicate — the kernel routes {@code GroupFilled}/{@code GroupNotFilled} on a
     * <em>root</em> group (e.g. {@code /MVK}) to {@code IRootGroupCondition} (a {@code Vordruck} check).
     * Maps to the same {@link io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef#filled()} as a nested group.
     */
    private Condition convertRootGroup(IRootGroupCondition root) {
        GroupRef group = model.group("/" + root.getRootGroupName());
        return switch (root.getConditionName()) {
            case MindestensEinVordruckAngegeben -> group.filled();   // GroupFilled(/RootGroup)
            case KeinVordruckAngegeben -> group.notFilled();         // GroupNotFilled(/RootGroup)
            default -> throw unsupported("root-group condition " + root.getConditionName(), root);
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

    /**
     * A field-list × value-list quantifier: "(at least one / no / not all) of these fields' values are
     * in this value list". The kernel carries the fields in {@code getEntityList()} and the constants in
     * {@code getValueListAsStringList()}; we route to the {@code Dsl} factory by field kind (string-,
     * enum-, or flattened-repetition list). Entity-valued value lists, mixed-kind field lists, and the
     * two unmodeled names (date-range overlap, value-consistency) stay opaque.
     */
    private Condition convertEntityListAndValueList(IEntityListAndValueListCondition node) {
        List<IEntityId> entities = node.getEntityList().getEntityIdList();
        if (entities.isEmpty()) {
            throw unsupported("empty entity+value-list condition", node);
        }
        EntityListAndValueListConditionName name = node.getConditionName();
        List<String> values = node.getValueListAsStringList();
        if (values.isEmpty()) {
            // entity-valued value list (field list checked against another field list)
            return convertEntityValuedList(node, name, entities);
        }
        if (entities.stream().allMatch(e -> e.isField() && !isWildcard(e))) {
            List<String> paths = entities.stream().map(IEntityId::getFullName).toList();
            return switch (homogeneousKind(paths, node)) {
                case STRING -> {
                    StringRef[] r = paths.stream().map(model::str).toArray(StringRef[]::new);
                    StringRef[] rest = Arrays.copyOfRange(r, 1, r.length);
                    yield switch (name) {
                        case AtLeastOneFieldValueIncludedInValueList ->
                                Dsl.atLeastOneFieldValueIncludedInValueList(values, r[0], rest);
                        case NoFieldValueIncludedInValueList ->
                                Dsl.noFieldValueIncludedInValueList(values, r[0], rest);
                        case NotAllFieldValuesIncludedInValueList ->
                                Dsl.notAllFieldValuesIncludedInValueList(values, r[0], rest);
                        default -> throw unsupported("entity+value-list condition " + name, node);
                    };
                }
                case ENUMERATION -> {
                    EnumRef[] r = paths.stream().map(model::enumOf).toArray(EnumRef[]::new);
                    EnumRef[] rest = Arrays.copyOfRange(r, 1, r.length);
                    yield switch (name) {
                        case AtLeastOneFieldValueIncludedInValueList ->
                                Dsl.atLeastOneFieldValueIncludedInValueList(values, r[0], rest);
                        case NoFieldValueIncludedInValueList ->
                                Dsl.noFieldValueIncludedInValueList(values, r[0], rest);
                        case NotAllFieldValuesIncludedInValueList ->
                                Dsl.notAllFieldValuesIncludedInValueList(values, r[0], rest);
                        default -> throw unsupported("entity+value-list condition " + name, node);
                    };
                }
                default -> throw unsupported("entity+value-list over a non-string/enum field list", node);
            };
        }
        if (entities.stream().allMatch(ConditionConverter::isWildcard)) {
            FieldListRef[] r = entities.stream()
                    .map(e -> model.flatten(reconstructStarPath(e))).toArray(FieldListRef[]::new);
            FieldListRef[] rest = Arrays.copyOfRange(r, 1, r.length);
            return switch (name) {
                case AtLeastOneFieldValueIncludedInValueList ->
                        Dsl.atLeastOneFieldValueIncludedInValueList(values, r[0], rest);
                case NoFieldValueIncludedInValueList ->
                        Dsl.noFieldValueIncludedInValueList(values, r[0], rest);
                case NotAllFieldValuesIncludedInValueList ->
                        Dsl.notAllFieldValuesIncludedInValueList(values, r[0], rest);
                default -> throw unsupported("entity+value-list condition " + name, node);
            };
        }
        throw unsupported("entity+value-list over a mixed (plain + wildcard) scope", node);
    }

    /**
     * The entity-valued form: the value list is itself a list of fields/repetition lists (a field {@code In}
     * a starred repetition list). Both sides become {@link FieldOperand}s (a plain field or a flattened
     * wildcard list); type compatibility is the kernel's call (F4/N7).
     */
    private Condition convertEntityValuedList(IEntityListAndValueListCondition node,
            EntityListAndValueListConditionName name, List<IEntityId> entities) {
        List<IEntityId> valueEntities = node.getValueListAsEntityList().getEntityIdList();
        if (valueEntities.isEmpty()) {
            throw unsupported("entity+value-list with neither constant nor entity values", node);
        }
        FieldOperand[] f = entities.stream().map(this::toFieldOperand).toArray(FieldOperand[]::new);
        FieldOperand[] rest = Arrays.copyOfRange(f, 1, f.length);
        List<FieldOperand> valueFields = valueEntities.stream().map(this::toFieldOperand).toList();
        return switch (name) {
            case AtLeastOneFieldValueIncludedInValueList ->
                    Dsl.atLeastOneFieldValueIncludedInValueList(valueFields, f[0], rest);
            case NoFieldValueIncludedInValueList ->
                    Dsl.noFieldValueIncludedInValueList(valueFields, f[0], rest);
            case NotAllFieldValuesIncludedInValueList ->
                    Dsl.notAllFieldValuesIncludedInValueList(valueFields, f[0], rest);
            default -> throw unsupported("entity+value-list condition " + name, node);
        };
    }

    /** A field entity to a {@link FieldOperand}: a flattened repetition list if it wildcards, else a plain field. */
    private FieldOperand toFieldOperand(IEntityId entity) {
        return isWildcard(entity) ? model.flatten(reconstructStarPath(entity)) : model.field(entity.getFullName());
    }

    /** The single value kind shared by every field at {@code paths}, or fail if they disagree / are unknown. */
    private ValueKind homogeneousKind(List<String> paths, ICondition origin) {
        ValueKind kind = null;
        for (String path : paths) {
            ValueKind k = model.fieldAt(path)
                    .orElseThrow(() -> unsupported("entity+value-list on unknown field " + path, origin)).kind();
            if (kind == null) {
                kind = k;
            } else if (kind != k) {
                throw unsupported("entity+value-list over a mixed-kind field list", origin);
            }
        }
        return kind;
    }

    /** Whether an entity addresses a repetition wildcard (any segment index is {@code WILDCARD}). */
    private static boolean isWildcard(IEntityId entity) {
        for (long idx : entity.getIndices()) {
            if (idx == IParseTreeEntity.WILDCARD) {
                return true;
            }
        }
        return false;
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
        if (left instanceof IDateDiffOperation diff) {
            return numberExprComparison(convertDateDiff(diff), op, right, comparison);
        }
        if (left instanceof IFieldIdOperation lenOp && lenOp.getOperatorName() == FieldIdOperationName.Length) {
            return numberExprComparison(model.str(lenOp.getField().getFullName()).length(), op, right, comparison);
        }
        if (left instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            return fieldValueComparison(field, op, right, comparison);
        }
        // normalize "constant op [field]" → "[field] flip(op) constant" (rulekit handles are field-first)
        if (left instanceof IConstantOperation && right instanceof IFieldIdOperation rf
                && rf.getOperatorName() == FieldIdOperationName.FieldValue) {
            return fieldValueComparison(rf, flip(op), left, comparison);
        }
        throw unsupported("comparison whose left side is not a plain field value or an aggregate", comparison);
    }

    /** Dispatch a {@code [field] <op> other} comparison by the field's kind (other = the constant/field operand). */
    private Condition fieldValueComparison(IFieldIdOperation field, ComparisonOperation op, IOperation other,
            ICondition origin) {
        String path = field.getField().getFullName();
        ValueKind kind = model.fieldAt(path)
                .orElseThrow(() -> unsupported("comparison on unknown field " + path, origin))
                .kind();
        // an enum field navigated to one of its categories ([field -> Cat]) rides on getCategoryName()
        String category = field.getField().getCategoryName();
        if (kind == ValueKind.ENUMERATION && category != null && !category.isEmpty()) {
            return categoryComparison(model.enumOf(path).category(category), op, other, origin);
        }
        return switch (kind) {
            case NUMBER -> numberComparison(model.num(path), op, other, origin);
            case STRING -> stringComparison(model.str(path), op, other, origin);
            case ENUMERATION -> enumComparison(model.enumOf(path), op, other, origin);
            case DATE -> dateComparison(model.date(path), op, other, origin);
            case BOOLEAN -> booleanComparison(model.bool(path), op, other, origin);
            case CONFIRM -> confirmComparison(model.confirm(path), op, other, origin);
            default -> throw unsupported("comparison on a " + kind + " field", origin);
        };
    }

    /** Mirror a comparison operator for operand-swap normalization ({@code a < b} ⟺ {@code b > a}). */
    private static ComparisonOperation flip(ComparisonOperation op) {
        return switch (op) {
            case LT -> ComparisonOperation.GT;
            case GT -> ComparisonOperation.LT;
            case LE -> ComparisonOperation.GE;
            case GE -> ComparisonOperation.LE;
            default -> op;   // EQ/NE are symmetric; others fall through to the per-kind unsupported
        };
    }

    /** An aggregate operand (`Sum`/`MaxValue`/… over a wildcard field scope, optionally `Having`-filtered). */
    private NumberExpr convertAggregate(IEntityListOperation aggregate) {
        List<IEntityId> entities = aggregate.getEntityList().getEntityIdList();
        EntityListOperationName name = aggregate.getOperatorName();
        // NumberOfFilledGroups(Group*): a single repeatable group, all reps — a group scope, not a field.
        if (name == EntityListOperationName.NumberOfFilledGroups
                && entities.size() == 1 && !entities.get(0).isField()) {
            return Dsl.numberOfFilledGroups(model.group(entities.get(0).getFullName()));
        }
        // Multi-field explicit scope, e.g. Sum(a, b) — each operand a plain field or a wildcard list.
        if (entities.size() > 1 && entities.stream().allMatch(IEntityId::isField)) {
            FieldOperand[] ops = entities.stream().map(this::toFieldOperand).toArray(FieldOperand[]::new);
            FieldOperand[] rest = Arrays.copyOfRange(ops, 1, ops.length);
            return switch (name) {
                case Sum -> Dsl.sum(ops[0], rest);
                case MaxValue -> Dsl.maxValue(ops[0], rest);
                case MinValue -> Dsl.minValue(ops[0], rest);
                case NumberOfDifferentValues -> Dsl.numberOfDifferentValues(ops[0], rest);
                case NumberOfFilledFields ->
                        Dsl.numberOfFilledFields(ops[0], ops[1], Arrays.copyOfRange(ops, 2, ops.length));
                default -> throw unsupported("aggregate operator " + name + " over a multi-field scope", aggregate);
            };
        }
        if (entities.size() != 1 || !entities.get(0).isField()) {
            throw unsupported("aggregate " + name + " over a non-single-field scope", aggregate);
        }
        FieldListRef scope = flattenScope(entities.get(0));
        return switch (name) {
            case Sum -> Dsl.sum(scope);
            case MaxValue -> Dsl.maxValue(scope);
            case MinValue -> Dsl.minValue(scope);
            case NumberOfDifferentValues -> Dsl.numberOfDifferentValues(scope);
            case NumberOfFilledFields -> Dsl.numberOfFilledFields(scope);
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
        NumberExpr rightExpr = numericExpr(right);
        if (rightExpr != null) {
            return exprVsExpr(expr, op, rightExpr, origin);
        }
        throw unsupported("aggregate compared against a non-constant operand", origin);
    }

    /** Convert {@code right} to a comparable numeric expression — an aggregate, a date-diff, a current
     *  repetition, a string length, or a number field value — else {@code null} (so the caller falls through). */
    private NumberExpr numericExpr(IOperation operand) {
        if (operand instanceof IEntityListOperation aggregate) {
            return convertAggregate(aggregate);
        }
        if (operand instanceof IDateDiffOperation diff) {
            return convertDateDiff(diff);
        }
        if (operand instanceof IFieldIdOperation lenOp && lenOp.getOperatorName() == FieldIdOperationName.Length) {
            return model.str(lenOp.getField().getFullName()).length();
        }
        if (operand instanceof ICompositeOperation arithmetic) {
            return convertArithmetic(arithmetic);
        }
        if (operand instanceof IGroupOperation groupOp
                && groupOp.getOperatorName() == GroupOperationName.CurrentRepetition) {
            return Dsl.currentRepetition(model.group(groupOp.getGroup().getFullName()));
        }
        if (operand instanceof IFieldIdOperation field
                && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            String path = field.getField().getFullName();
            if (model.fieldAt(path).map(info -> info.kind() == ValueKind.NUMBER).orElse(false)) {
                return model.num(path).asNumber();
            }
        }
        return null;
    }

    /** An arithmetic expression ({@code a + b}, {@code a - b}, …) — the kernel's n-ary {@code ICompositeOperation}. */
    private NumberExpr convertArithmetic(ICompositeOperation comp) {
        List<IOperation> ops = comp.getOperations();
        if (ops.isEmpty()) {
            throw unsupported("empty arithmetic operation", comp);
        }
        NumberExpr acc = arithmeticOperand(ops.get(0), comp);
        for (int i = 1; i < ops.size(); i++) {
            NumberExpr next = arithmeticOperand(ops.get(i), comp);
            acc = switch (comp.getCalcOp()) {
                case ADD -> acc.plus(next);
                case SUBTRACT -> acc.minus(next);
                case MULTIPLY -> acc.times(next);
                case DIVIDE -> acc.dividedBy(next);
                case POWER -> acc.toThePowerOf(next);
            };
        }
        return acc;
    }

    /** One operand of an arithmetic expression: a numeric constant or any recognised numeric expression. */
    private NumberExpr arithmeticOperand(IOperation op, Object origin) {
        if (op instanceof IConstantOperation constant) {
            return Dsl.number(constant.getValue());
        }
        NumberExpr expr = numericExpr(op);
        if (expr != null) {
            return expr;
        }
        throw unsupported("arithmetic operand " + op.getClass().getSimpleName(), origin);
    }

    /** A comparison between two numeric expressions (field-vs-aggregate, aggregate-vs-field, …). */
    private Condition exprVsExpr(NumberExpr left, ComparisonOperation op, NumberExpr right, ICondition origin) {
        return switch (op) {
            case LT -> left.lt(right);
            case LE -> left.le(right);
            case GT -> left.gt(right);
            case GE -> left.ge(right);
            case EQ -> left.eq(right);
            case NE -> left.ne(right);
            default -> throw unsupported("expression comparison operator " + op, origin);
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
        NumberExpr rightExpr = numericExpr(right);
        if (rightExpr != null) {
            return exprVsExpr(left.asNumber(), op, rightExpr, origin);
        }
        throw unsupported("number comparison against a non-field, non-constant operand", origin);
    }

    private Condition stringComparison(StringRef left, ComparisonOperation op, IOperation right,
            ICondition origin) {
        if (right instanceof IConstantOperation constant) {
            String v = constant.getValue();
            return switch (op) {
                case EQ -> left.eq(v);
                case NE -> left.ne(v);
                case WM -> left.matches(v);   // [field] WM "pattern"  → PatternMatched
                case NM -> left.violates(v);  // [field] NM "pattern"  → PatternViolated
                default -> throw unsupported("string comparison operator " + op, origin);
            };
        }
        if (right instanceof IFieldIdOperation rf && rf.getOperatorName() == FieldIdOperationName.FieldValue) {
            StringRef other = model.str(rf.getField().getFullName());
            return switch (op) {
                case EQ -> left.eq(other);
                case NE -> left.ne(other);
                default -> throw unsupported("string field comparison operator " + op, origin);
            };
        }
        throw unsupported("string comparison against a non-field, non-constant operand", origin);
    }

    /** {@code [field -> Category] == / != "value"} or {@code … == [other -> Category]} (category-vs-category). */
    private Condition categoryComparison(io.github.mbackschat.a12.dm.rulekit.dsl.CategoryRef category,
            ComparisonOperation op, IOperation right, Object origin) {
        if (right instanceof IConstantOperation constant) {
            return switch (op) {
                case EQ -> category.is(constant.getValue());
                case NE -> category.isNot(constant.getValue());
                default -> throw unsupported("enum category comparison operator " + op, origin);
            };
        }
        if (right instanceof IFieldIdOperation rf && rf.getOperatorName() == FieldIdOperationName.FieldValue
                && rf.getField().getCategoryName() != null && !rf.getField().getCategoryName().isEmpty()) {
            var other = model.enumOf(rf.getField().getFullName()).category(rf.getField().getCategoryName());
            return switch (op) {
                case EQ -> category.is(other);
                case NE -> category.isNot(other);
                default -> throw unsupported("enum category comparison operator " + op, origin);
            };
        }
        throw unsupported("enum category comparison against a non-constant operand", origin);
    }

    private Condition enumComparison(EnumRef left, ComparisonOperation op, IOperation right,
            ICondition origin) {
        if (right instanceof IConstantOperation constant) {
            return switch (op) {
                case EQ -> left.is(constant.getValue());
                case NE -> left.isNot(constant.getValue());
                default -> throw unsupported("enum comparison operator " + op, origin);
            };
        }
        if (right instanceof IFieldIdOperation rf && rf.getOperatorName() == FieldIdOperationName.FieldValue) {
            EnumRef other = model.enumOf(rf.getField().getFullName());
            return switch (op) {
                case EQ -> left.is(other);
                case NE -> left.isNot(other);
                default -> throw unsupported("enum field comparison operator " + op, origin);
            };
        }
        throw unsupported("enum comparison against a non-constant operand, operator " + op, origin);
    }

    /** {@code [bool] == True|False} → {@link io.github.mbackschat.a12.dm.rulekit.dsl.BoolRef}. */
    private Condition booleanComparison(io.github.mbackschat.a12.dm.rulekit.dsl.BoolRef left,
            ComparisonOperation op, IOperation right, ICondition origin) {
        boolean value = booleanConstant(right, origin);
        return switch (op) {
            case EQ -> value ? left.isTrue() : left.isFalse();
            case NE -> value ? left.isNotTrue() : left.isNotFalse();
            default -> throw unsupported("boolean comparison operator " + op, origin);
        };
    }

    /**
     * {@code [confirm] == True} / {@code != True} → {@link io.github.mbackschat.a12.dm.rulekit.dsl.ConfirmRef}.
     * A confirm compares only to {@code True} (kernel rejects {@code == False}); the literal is asserted.
     */
    private Condition confirmComparison(io.github.mbackschat.a12.dm.rulekit.dsl.ConfirmRef left,
            ComparisonOperation op, IOperation right, ICondition origin) {
        if (!booleanConstant(right, origin)) {
            throw unsupported("confirm comparison to False (kernel allows only True)", origin);
        }
        return switch (op) {
            case EQ -> left.confirmed();
            case NE -> left.notConfirmed();
            default -> throw unsupported("confirm comparison operator " + op, origin);
        };
    }

    /** The boolean value of a {@code True}/{@code False} literal operand ({@link IBoolConstantOperation}). */
    private boolean booleanConstant(IOperation right, ICondition origin) {
        if (right instanceof IBoolConstantOperation b) {
            return b.getValue() == BoolConstantOperationName.True;
        }
        throw unsupported("boolean/confirm comparison against a non-boolean-literal operand", origin);
    }

    private Condition dateComparison(DateRef left, ComparisonOperation op, IOperation right, ICondition origin) {
        DateOperand other = convertDateOperand(right, origin);
        return switch (op) {
            case LT -> left.before(other);
            case LE -> left.onOrBefore(other);
            case GT -> left.after(other);
            case GE -> left.onOrAfter(other);
            case EQ -> left.eq(other);
            default -> throw unsupported("date comparison operator " + op, origin);
        };
    }

    /** {@code DifferenceInDays/Months/Years(a, b)} as a numeric operand. */
    private NumberExpr convertDateDiff(IDateDiffOperation diff) {
        DateOperand a = convertDateOperand(diff.getDateSpec1(), diff);
        DateOperand b = convertDateOperand(diff.getDateSpec2(), diff);
        DateDiffOperationName name = diff.getOperatorName();
        return switch (name) {
            case DifferenceInDays -> Dsl.differenceInDays(a, b);
            case DifferenceInMonths -> Dsl.differenceInMonths(a, b);
            case DifferenceInYears -> Dsl.differenceInYears(a, b);
            default -> throw unsupported("date-diff operator " + name, diff);
        };
    }

    /** A date-valued operand: a date field ({@code FieldValue}) or a point-in-time ({@code Today}). */
    private DateOperand convertDateOperand(IOperation op, Object origin) {
        if (op instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            return model.date(field.getField().getFullName());
        }
        if (op instanceof IPointInTimeOperation pit && pit.getOperatorName() == PointInTimeOperationName.Today) {
            return Dsl.today();
        }
        throw unsupported("date operand " + op.getClass().getSimpleName(), origin);
    }

    private static UnsupportedConstructException unsupported(String what, Object node) {
        String kernelType = node.getClass().getInterfaces().length > 0
                ? node.getClass().getInterfaces()[0].getSimpleName()
                : node.getClass().getSimpleName();
        return new UnsupportedConstructException("F10 converter does not yet handle " + what
                + " [kernel node: " + kernelType + "]");
    }
}
