package io.github.mbackschat.a12.dm.adapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.IEntityId;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.IParseTreeEntity;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IApplicationCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IComparisonCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICompositeCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IDateValidityCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IPredefinedTypeValidityCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.ICompositeOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IEntityListAndValueListCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IEntityListCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IRootGroupCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.IValueListCondition;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IBoolConstantOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IConstantOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IEntityListOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IEntityListOperationWithValue;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IFieldIdOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IGroupOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.INumberOfRootGroupsOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IOperation;
import com.mgmtp.a12.kernel.core.calc.internal.util.compOp.IWithComparisonOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IFieldIdOperationWithParameters;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IOpListOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IOpListOperationWithParameter;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.IPointInTimeOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.date.IDateAddOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.date.IDateConstructionOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.date.IDateRangeConstructionOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.date.IDateTimeConstructionOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.date.ITimeConstructionOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.date.IDateRangeExtractionOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.date.IDateDiffOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.operation.date.IDateExtractionOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.BoolConstantOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.ComparisonOperation;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.EntityListAndValueListConditionName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.DateDiffOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.DateExtractionOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.DateRangeExtractionOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.ValidityConditionName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.EntityListConditionName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.EntityListOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.FieldIdOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.GroupOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.CalcOp;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.LogOp;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.OpListOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.OperationType;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.PointInTimeOperationName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.ValueListConditionName;
import com.mgmtp.a12.kernel.core.parsetree.a12internal.ast.ASTVisitor;

import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.dsl.BaseYear;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.CategoryRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Comments;
import io.github.mbackschat.a12.dm.rulekit.dsl.Now;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateValidity;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateListAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateFragmentRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateShift;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.TemporalOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValueAsNumber;
import io.github.mbackschat.a12.dm.rulekit.dsl.RangeAsNumber;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberOfValueInFields;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.OpListMath;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.RepetitionNotUniqueCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndex;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Tolerance;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueAsDate;
import io.github.mbackschat.a12.dm.core.ValueKind;

import org.apache.commons.lang3.tuple.Pair;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.serviceparams.ICalculationInfo;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.services.IBaseCalculationService.RoundingMode;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;

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
     * Count the {@code $} outer-iteration correlation markers in the source AST (D13). The kernel
     * allows {@code $} only inside {@code Having} filters, and its {@code traverse()} does not descend
     * into filters, so we walk filters explicitly (breadth-first, including filters nested inside
     * filters). This count is the input to the N8 backstop {@link #verifyOuterPreserved}: the converter
     * faithfully threads the marker through the comparison kinds whose handles expose {@code outer()}
     * (number, string); a kind not yet threaded would silently drop a {@code $}, which the backstop
     * catches by comparing this source count to the markers actually rendered after conversion.
     */
    int countOuterRefs(ICondition root) {
        int[] count = {0};
        Deque<ICondition> filters = new ArrayDeque<>();
        filters.add(root);
        while (!filters.isEmpty()) {
            List<ICondition> nested = new ArrayList<>();
            filters.poll().traverse(new ASTVisitor() {
                @Override
                public boolean visit(IEntityId id) {
                    if (id.isOuter()) {
                        count[0]++;
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
        return count[0];
    }

    /**
     * N8 backstop: if the converted condition renders fewer {@code $} markers than the source carried
     * ({@code expectedOuter}), a correlation was silently dropped (a comparison kind whose handle does
     * not yet thread {@code outer()}) — so refuse the conversion, degrading the rule to a verbatim
     * {@link ReadResult.Opaque} rather than corrupting it into a non-correlated rule. {@code $} only ever
     * renders as the {@code [$} value-bracket prefix, so counting that substring is exact.
     *
     * @throws UnsupportedConstructException when a marker was dropped — caught by {@link KernelAdapter#read}.
     */
    void verifyOuterPreserved(Condition converted, int expectedOuter) {
        if (expectedOuter == 0) {
            return;
        }
        // The $ marker renders in several positions — "[$path]" (value/category), "($group)"
        // (CurrentRepetition), and bare "$path" (presence predicates, value-list operands). $ is the DSL's
        // ONLY use of the character outside a string literal, so: drop string literals, then count every $.
        String rendered = new DslRenderer().render(converted).replaceAll("\"[^\"]*\"", "");
        int renderedOuter = countOccurrences(rendered, "$");
        if (renderedOuter < expectedOuter) {
            throw new UnsupportedConstructException("F10 converter would silently drop a '$' outer-iteration "
                    + "correlation (D13): the source carries " + expectedOuter + " marker(s) but conversion "
                    + "rendered only " + renderedOuter + " — the comparison kind is not yet outer-threaded, "
                    + "so the rule is preserved verbatim [kernel node: outer correlation ($)]");
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
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
        if (node instanceof IDateValidityCondition validity) {
            // Valid(Date(...)) / Invalid(...) — the date-validity condition over a constructed date
            boolean valid = validity.getConditionName() == ValidityConditionName.Valid;
            DateOperand date = convertDateOperand(validity.getDateConstruct(), validity);
            return valid ? Dsl.valid(date) : Dsl.invalid(date);
        }
        if (node instanceof IPredefinedTypeValidityCondition typeValidity) {
            // Valid(field, "Type") / Invalid(field, "Type") — the custom-field-type validity condition
            boolean valid = typeValidity.getConditionName() == ValidityConditionName.Valid;
            FieldRef field = model.field(typeValidity.getField().getFullName());
            return valid
                    ? Dsl.validType(field, typeValidity.getPredefinedTypeConstant())
                    : Dsl.invalidType(field, typeValidity.getPredefinedTypeConstant());
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
        // a field-fill quantifier over a MIXED group+field scope (FieldsNotCollectivelyFilled(A, B/G2), where A
        // is a group and B/G2 a field): the kernel expands each group to every descendant field recursively
        // — make that explicit and build a field-list quantifier over the flattened field paths.
        if (isFieldFillQuantifier(name) && entities.stream().anyMatch(e -> !e.isField())
                && entities.stream().anyMatch(IEntityId::isField)) {
            List<String> fieldPaths = new ArrayList<>();
            for (IEntityId e : entities) {
                if (e.isField()) {
                    fieldPaths.add(e.getFullName());
                } else {
                    descendantFields(e).forEach(f -> fieldPaths.add(f.path()));
                }
            }
            return fieldFillQuantifier(name, fieldPaths.toArray(String[]::new), entityList);
        }
        if (entities.get(0).isField()) {
            // FieldFilled([Field For key]) / FieldNotFilled(...): the semantic index is part of the
            // entity id, not a separate condition node. Preserve it before the ordinary field-list
            // path below reduces the entity to getFullName().
            if (single && hasSemanticIndex(entities.get(0))) {
                SemanticIndex index = semanticIndex(entities.get(0), entityList);
                return switch (name) {
                    case AllFieldsFilled -> index.filled();
                    case NoFieldFilled -> index.notFilled();
                    default -> throw unsupported("semantic-index presence with operator " + name, entityList);
                };
            }
            // A wildcard scope with a Having filter (e.g. AtLeastOneFieldFilled(Group*/Field Having …)):
            // build the filtered FieldListRef (flattenScope converts the filter — threading any $) and apply
            // the quantifier over it. The plain path form below drops the wildcard + filter, so it must not
            // be taken here.
            if (single && entities.get(0).getFilterCondition() != null) {
                FieldListRef scope = flattenScope(entities.get(0));
                return fieldFillQuantifier(name, new FieldOperand[] {scope}, entityList);
            }
            // $-correlated presence predicate (e.g. NoFieldFilled($a, $b) inside a filter, D13): build
            // outer-marked field operands so the $ survives — else the count-check refuses the conversion.
            if (entities.stream().anyMatch(IEntityId::isOuter)) {
                FieldOperand[] ops = entities.stream()
                        .map(e -> e.isOuter() ? model.field(e.getFullName()).outer() : model.field(e.getFullName()))
                        .toArray(FieldOperand[]::new);
                return fieldFillQuantifier(name, ops, entityList);
            }
            // An unfiltered wildcard field-fill quantifier is still a list operation. Route every operand
            // through the wildcard-aware converter so a single starred operand and mixed starred/plain lists
            // retain their per-repetition meaning instead of falling into the plain-path canonicalization.
            if (isFieldFillQuantifier(name) && entities.stream().anyMatch(ConditionConverter::isWildcard)) {
                FieldOperand[] operands = entities.stream().map(this::toFieldOperand).toArray(FieldOperand[]::new);
                return fieldFillQuantifier(name, operands, entityList);
            }
            return switch (name) {
                // single-field FieldFilled/FieldNotFilled canonicalize to AllFieldsFilled/NoFieldFilled[x]
                case AllFieldsFilled -> single ? model.field(p[0]).filled() : model.allFieldsFilled(p);
                case NoFieldFilled -> single ? model.field(p[0]).notFilled() : model.noFieldFilled(p);
                case AtLeastOneFieldFilled -> model.atLeastOneFieldFilled(p);
                case NotAllFieldsFilled -> model.notAllFieldsFilled(p);
                case FieldsNotCollectivelyFilled -> model.fieldsNotCollectivelyFilled(p);
                case NotExactlyOneFieldFilled -> model.notExactlyOneFieldFilled(p);
                case MoreThanOneFieldFilled -> model.moreThanOneFieldFilled(p);
                case FieldValuesNotUnique -> {
                    // keys may wildcard a repeatable group (FieldValuesNotUnique(Items*/Sku)) — use the
                    // wildcard-aware operand conversion so the '*' survives (else MVK_NO_WILDCARD on write-back)
                    FieldOperand[] keys = entities.stream().map(this::toFieldOperand).toArray(FieldOperand[]::new);
                    yield Dsl.fieldValuesNotUnique(keys[0], Arrays.copyOfRange(keys, 1, keys.length));
                }
                case DateRangesOverlap -> {
                    FieldOperand[] ops = entities.stream().map(this::toFieldOperand).toArray(FieldOperand[]::new);
                    yield Dsl.dateRangesOverlap(ops[0], Arrays.copyOfRange(ops, 1, ops.length));
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
        // The two kernel-legal starred group-count forms are list scans, even with one operand.
        // Preserve the exact scope before the ordinary single-group canonicalization below reduces
        // NoGroupFilled(G) to scalar GroupNotFilled(G). AtLeastOneGroupFilled is the polarity sibling.
        if ((name == EntityListConditionName.NoGroupFilled
                        || name == EntityListConditionName.AtLeastOneGroupFilled)
                && entities.stream().anyMatch(ConditionConverter::isWildcard)) {
            GroupListRef[] scopes = entities.stream()
                    .map(e -> model.flattenGroup(reconstructStarPath(e)))
                    .toArray(GroupListRef[]::new);
            return groupFillQuantifier(name, scopes, entityList);
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
            case NotAllFieldsFilled -> groupScopedFieldFill(entities, Dsl::notAllFieldsFilled);
            case FieldsNotCollectivelyFilled -> groupScopedFieldFill(entities, Dsl::fieldsNotCollectivelyFilled);
            case NotExactlyOneFieldFilled -> groupScopedFieldFill(entities, Dsl::notExactlyOneFieldFilled);
            case MoreThanOneFieldFilled -> groupScopedFieldFill(entities, Dsl::moreThanOneFieldFilled);
            default -> throw unsupported("group-list condition " + name, entityList);
        };
    }

    /** A group-count quantifier over exact group scopes, preserving starred repetition scans. */
    private Condition groupFillQuantifier(
            EntityListConditionName name, GroupListRef[] scopes, ICondition origin) {
        GroupListRef[] rest = Arrays.copyOfRange(scopes, 1, scopes.length);
        return switch (name) {
            case NoGroupFilled -> Dsl.noGroupFilled(scopes[0], rest);
            case AtLeastOneGroupFilled -> Dsl.atLeastOneGroupFilled(scopes[0], rest);
            default -> throw unsupported("starred group-list condition " + name, origin);
        };
    }

    /** The seven field-fill quantifier names (over fields, not groups). */
    private static boolean isFieldFillQuantifier(EntityListConditionName name) {
        return switch (name) {
            case AllFieldsFilled, NoFieldFilled, AtLeastOneFieldFilled, NotAllFieldsFilled,
                    FieldsNotCollectivelyFilled, NotExactlyOneFieldFilled, MoreThanOneFieldFilled -> true;
            default -> false;
        };
    }

    /** A field-fill quantifier over explicit field paths (used for the mixed group+field expansion). */
    private Condition fieldFillQuantifier(EntityListConditionName name, String[] p, ICondition origin) {
        return switch (name) {
            case AllFieldsFilled -> model.allFieldsFilled(p);
            case NoFieldFilled -> model.noFieldFilled(p);
            case AtLeastOneFieldFilled -> model.atLeastOneFieldFilled(p);
            case NotAllFieldsFilled -> model.notAllFieldsFilled(p);
            case FieldsNotCollectivelyFilled -> model.fieldsNotCollectivelyFilled(p);
            case NotExactlyOneFieldFilled -> model.notExactlyOneFieldFilled(p);
            case MoreThanOneFieldFilled -> model.moreThanOneFieldFilled(p);
            default -> throw unsupported("field-fill quantifier " + name, origin);
        };
    }

    /** A field-fill quantifier over field operands, preserving flattened wildcard lists. */
    private Condition fieldFillQuantifier(EntityListConditionName name, FieldOperand[] operands, ICondition origin) {
        FieldOperand[] rest = Arrays.copyOfRange(operands, 1, operands.length);
        return switch (name) {
            case AllFieldsFilled -> Dsl.allFieldsFilled(operands[0], rest);
            case NoFieldFilled -> Dsl.noFieldFilled(operands[0], rest);
            case AtLeastOneFieldFilled -> Dsl.atLeastOneFieldFilled(operands[0], rest);
            case NotAllFieldsFilled -> Dsl.notAllFieldsFilled(operands[0], rest);
            case FieldsNotCollectivelyFilled -> Dsl.fieldsNotCollectivelyFilled(operands[0], rest);
            case NotExactlyOneFieldFilled -> Dsl.notExactlyOneFieldFilled(operands[0], rest);
            case MoreThanOneFieldFilled -> Dsl.moreThanOneFieldFilled(operands[0], rest);
            default -> throw unsupported("field-fill quantifier " + name, origin);
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
                    // preserve each member's exact projection: a direct enum reference vs a category
                    // access ([field -> Cat]) are distinct members, and literals ride the union of their
                    // domains (SPEC-2026-07-22-05) — so build EnumListRefs off the ENTITIES, not the paths.
                    EnumListRef[] r = entities.stream().map(this::enumListRef).toArray(EnumListRef[]::new);
                    EnumListRef[] rest = Arrays.copyOfRange(r, 1, r.length);
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
        // date-range overlap carries no category and needs plain FieldOperands (its Dsl factory's shape)
        if (name == EntityListAndValueListConditionName.AtLeastOneDateRangeOverlaps) {
            FieldOperand[] f = entities.stream().map(this::toFieldOperand).toArray(FieldOperand[]::new);
            FieldOperand[] vf = valueEntities.stream().map(this::toFieldOperand).toArray(FieldOperand[]::new);
            return Dsl.atLeastOneDateRangeOverlaps(f[0], vf[0], Arrays.copyOfRange(vf, 1, vf.length));
        }
        // value-list membership: both sides may carry enum category projections — retain them exactly.
        ValueListOperand[] f = entities.stream().map(this::toValueListOperand).toArray(ValueListOperand[]::new);
        ValueListOperand[] rest = Arrays.copyOfRange(f, 1, f.length);
        List<ValueListOperand> valueFields = valueEntities.stream().map(this::toValueListOperand).toList();
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

    /** A constant operand to a literal {@link Calculation} — a boolean, or a number/string/date by its kernel type. */
    private Calculation constantOperand(IOperation op, Object origin) {
        if (op instanceof IBoolConstantOperation b) {
            return new BoolLiteral(b.getValue() == BoolConstantOperationName.True);
        }
        if (op instanceof IConstantOperation c) {
            return switch (c.getType()) {
                case DATE -> new DateLiteral(c.getValue());
                case NUMBER -> NumberLiteral.of(c.getValue());
                default -> new StringLiteral(c.getValue());   // STRING (DATE_RANGE never appears here)
            };
        }
        throw unsupported("constant operand " + op.getClass().getSimpleName(), origin);
    }

    /** A field entity to a {@link FieldOperand}: a flattened repetition list if it wildcards, else a plain field. */
    private FieldOperand toFieldOperand(IEntityId entity) {
        FieldOperand op = isWildcard(entity)
                ? model.flatten(reconstructStarPath(entity)) : model.field(entity.getFullName());
        if (entity.isOuter()) {   // $ correlation (D13): e.g. the value side of an entity-valued value-list
            op = (op instanceof FieldListRef list) ? list.outer() : ((FieldRef) op).outer();
        }
        return op;
    }

    /**
     * A value-list quantifier member — like {@link #toFieldOperand}, but preserving an enum category
     * projection ({@code [field -> Cat]}) as an {@link EnumCategoryAccess} rather than erasing it to the
     * bare field (SPEC-2026-07-22-05). Category projections ride on {@code getCategoryName()}.
     */
    private ValueListOperand toValueListOperand(IEntityId entity) {
        String category = entity.getCategoryName();
        if (category != null && !category.isEmpty()) {
            CategoryRef cat = model.enumOf(entity.getFullName()).category(category);
            return (entity.isOuter() ? cat.outer() : cat).listOperand();
        }
        return toFieldOperand(entity);
    }

    /** An enum-list quantifier member (literal value-list side) — a direct {@link EnumRef} or, when the
     *  entity carries {@code getCategoryName()}, a {@link CategoryRef} projection; the union of their
     *  domains admits the literals. */
    private EnumListRef enumListRef(IEntityId entity) {
        EnumRef enumRef = model.enumOf(entity.getFullName());
        String category = entity.getCategoryName();
        if (category == null || category.isEmpty()) {
            return entity.isOuter() ? enumRef.outer() : enumRef;
        }
        CategoryRef cat = enumRef.category(category);
        return entity.isOuter() ? cat.outer() : cat;
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
        boolean included = valueList.getConditionName() == ValueListConditionName.FieldValueIncludedInValueList;
        if (valueList.getValueList().isEmpty()) {
            throw unsupported("empty value list", valueList);
        }
        // a value list whose entries include a FIELD (not a constant), e.g.
        // FieldValueIncludedInValueList(Product, [Other]) — build the node with mixed value operands.
        boolean anyFieldEntry = valueList.getValueList().stream().anyMatch(op ->
                op instanceof IFieldIdOperation f && f.getOperatorName() == FieldIdOperationName.FieldValue);
        if (anyFieldEntry) {
            List<Calculation> vals = valueList.getValueList().stream()
                    .map(op -> valueListEntry(op, kind, valueList)).toList();
            return new ValueListCondition(model.field(path), included, vals);
        }
        List<String> values = valueList.getValueList().stream().map(op -> constantValue(op, valueList)).toList();
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

    /** One entry of a value list — a field reference ({@code [Other]}) or a literal matching the field's kind. */
    private Calculation valueListEntry(IOperation op, ValueKind fieldKind, ICondition origin) {
        if (op instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            return new FieldValue(model.field(field.getField().getFullName()));
        }
        if (op instanceof IConstantOperation constant) {
            return fieldKind == ValueKind.NUMBER ? NumberLiteral.of(constant.getValue())
                    : new StringLiteral(constant.getValue());
        }
        throw unsupported("value-list entry " + op.getClass().getSimpleName(), origin);
    }

    private Condition convertComparison(IComparisonCondition comparison) {
        IOperation left = comparison.getOperation1();
        ComparisonOperation op = comparison.getCmpOp();
        IOperation right = comparison.getOperation2();
        if (left instanceof IOpListOperation operandList && operandList.getType() == OperationType.DATE) {
            TemporalKind kind = temporalOperandListKind(operandList, right);
            return switch (kind) {
                case DATE -> dateComparison(new DateExpr(convertTemporalOperandList(operandList, kind)), op,
                        convertDateOperand(right, comparison), comparison);
                case TIME -> timeComparison(new TimeExpr(convertTemporalOperandList(operandList, kind)), op, right,
                        comparison);
                case DATE_TIME -> dateTimeComparison(new DateTimeExpr(convertTemporalOperandList(operandList, kind)),
                        op, right, comparison);
            };
        }
        if (left instanceof IEntityListOperation aggregate) {
            // a TIME/DATETIME extremum (MaxValue/MinValue over a temporal scope) compares as that kind — the
            // kernel folds all three date-like kinds through one DateCombiner (SPEC-2026-07-21-07)
            ValueKind extremumKind = temporalExtremumKind(aggregate);
            if (extremumKind == ValueKind.TIME) {
                return timeComparison(new TimeExpr(temporalListAggregate(aggregate)), op, right, comparison);
            }
            if (extremumKind == ValueKind.DATE_TIME) {
                return dateTimeComparison(new DateTimeExpr(temporalListAggregate(aggregate)), op, right, comparison);
            }
            // a date-valued aggregate (MaxValue/MinValue/FirstFilledValue over a DATE scope) compares as a date
            if (isDateValuedAggregate(aggregate)) {
                return dateComparison(convertDateAggregate(aggregate), op, convertDateOperand(right, comparison),
                        comparison);
            }
            return numberExprComparison(convertAggregate(aggregate), op, right, comparison);
        }
        if (left instanceof IGroupOperation groupOp
                && groupOp.getOperatorName() == GroupOperationName.CurrentRepetition) {
            NumberExpr expr = Dsl.currentRepetition(model.group(groupOp.getGroup().getFullName()),
                    groupOp.getGroup().isOuter());   // $ self-exclusion correlation (D13)
            return numberExprComparison(expr, op, right, comparison);
        }
        if (left instanceof IDateDiffOperation diff) {
            return numberExprComparison(convertDateDiff(diff), op, right, comparison);
        }
        if (left instanceof IDateExtractionOperation ext) {
            // the value-valued parts (DateFromDateTime/TimeFromDateTime) compare as a date/time, not a number
            return switch (ext.getOperatorName()) {
                case DateFromDateTime -> dateComparison(convertDateOperand(left, comparison), op,
                        convertDateOperand(right, comparison), comparison);
                case TimeFromDateTime -> timeComparison(convertTimeOperand(left, comparison), op, right, comparison);
                default -> numberExprComparison(convertDateExtraction(ext), op, right, comparison);
            };
        }
        // a date/date-time shift on the left — preserve the result family selected by the shift and its base
        if (left instanceof IDateAddOperation add) {
            return isDateTimeValue(add)
                    ? dateTimeComparison(convertDateTimeAdd(add), op, right, comparison)
                    : dateComparison(convertDateAdd(add), op, convertDateOperand(right, comparison), comparison);
        }
        if (left instanceof IFieldIdOperation lenOp && lenOp.getOperatorName() == FieldIdOperationName.Length) {
            return numberExprComparison(model.str(lenOp.getField().getFullName()).length(), op, right, comparison);
        }
        // a ValueAsDate(field, FirstDay|LastDay) on the left — compare it as a date operand
        if (left instanceof IFieldIdOperationWithParameters vad
                && vad.getOperatorName() == FieldIdOperationName.ValueAsDate) {
            return dateComparison(convertDateOperand(left, comparison), op, convertDateOperand(right, comparison),
                    comparison);
        }
        // a string coercion on the left ([RangeAsString(...)] != "BD_") — compare the string expression
        if (isStringCoercion(left)) {
            return stringExprComparison(stringCoercion((IFieldIdOperation) left), op, right, comparison);
        }
        if (left instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            return fieldValueComparison(field, op, right, comparison);
        }
        // normalize "constant op [field]" → "[field] flip(op) constant" (rulekit handles are field-first)
        if (left instanceof IConstantOperation && right instanceof IFieldIdOperation rf
                && rf.getOperatorName() == FieldIdOperationName.FieldValue) {
            return fieldValueComparison(rf, flip(op), left, comparison);
        }
        // a numeric expression on the left not matched above (e.g. arithmetic on both sides, ([a]+1) == ([b]-2)
        // or a rounding/root-group count) — compare it expression-vs-expression
        NumberExpr leftExpr = numericExpr(left);
        if (leftExpr != null) {
            NumberExpr rightExpr = right instanceof IConstantOperation c ? Dsl.number(c.getValue())
                    : numericExpr(right);
            if (rightExpr != null) {
                return exprVsExpr(leftExpr, op, rightExpr, comparison);
            }
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
        // a $ outer-iteration correlation on the left operand (D13) — threaded for the kinds whose
        // handles expose outer(); other kinds leave the marker unset and the N8 backstop (verifyOuterPreserved)
        // catches the silent drop and degrades the rule to Opaque.
        boolean leftOuter = field.getField().isOuter();
        // a semantic index ([field For X]) reads the field's value in the repetition whose group index field
        // equals X — it rides on getSemanticIndex(). The kernel's index is always a single element: a constant
        // String key ([field For "x"]) or a field-ref IEntityId key ([field For keyField]).
        if (hasSemanticIndex(field.getField())) {
            Calculation semantic = semanticIndex(field.getField(), origin).index();
            return switch (kind) {
                case NUMBER -> numberExprComparison(new NumberExpr(semantic), op, other, origin);
                case STRING -> stringExprComparison(new StringExpr(semantic), op, other, origin);
                case ENUMERATION -> enumExprComparison(new EnumExpr(semantic), op, other, origin);
                case DATE, DATE_FRAGMENT -> dateComparison(new DateExpr(semantic), op, other, origin);
                case TIME -> timeComparison(new TimeExpr(semantic), op, other, origin);
                case DATE_TIME -> dateTimeComparison(new DateTimeExpr(semantic), op, other, origin);
                default -> throw unsupported("semantic index ([field For …]) on a " + kind + " field", origin);
            };
        }
        // an enum field navigated to one of its categories ([field -> Cat]) rides on getCategoryName()
        String category = field.getField().getCategoryName();
        if (kind == ValueKind.ENUMERATION && category != null && !category.isEmpty()) {
            CategoryRef cat = model.enumOf(path).category(category);
            return categoryComparison(leftOuter ? cat.outer() : cat, op, other, origin);
        }
        return switch (kind) {
            case NUMBER -> numberComparison(leftOuter ? model.num(path).outer() : model.num(path), op, other, origin);
            case STRING -> stringComparison(leftOuter ? model.str(path).outer() : model.str(path), op, other, origin);
            case ENUMERATION -> enumComparison(leftOuter ? model.enumOf(path).outer() : model.enumOf(path), op, other, origin);
            case DATE -> dateComparison(leftOuter ? model.date(path).outer() : model.date(path), op, other, origin);
            // a date-fragment field compares like a date (the kernel collapses it to a Date, §6) — same machinery
            case DATE_FRAGMENT -> dateComparison(leftOuter ? model.dateFragment(path).outer() : model.dateFragment(path), op, other, origin);
            case TIME -> timeComparison(leftOuter ? model.time(path).outer() : model.time(path), op, other, origin);
            case DATE_TIME -> dateTimeComparison(leftOuter ? model.dateTime(path).outer() : model.dateTime(path), op, other, origin);
            case BOOLEAN -> booleanComparison(model.bool(path), op, other, origin);
            case CONFIRM -> confirmComparison(model.confirm(path), op, other, origin);
            // a custom-typed field's value is text — the kernel compares it as a string (m.custom → StringRef)
            case CUSTOM -> stringComparison(leftOuter ? model.custom(path).outer() : model.custom(path), op, other, origin);
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
        // NumberOfValueInFields(constant IN f1, f2, …) — a count-of-value aggregate carrying a constant (family 6)
        if (aggregate instanceof IEntityListOperationWithValue withValue) {
            Calculation value = constantOperand(withValue.getConstantOperation(), aggregate);
            if (entities.stream().anyMatch(e -> !e.isField())) {
                throw unsupported("NumberOfValueInFields over a group scope", aggregate);
            }
            FieldOperand[] fields = entities.stream().map(this::toFieldOperand).toArray(FieldOperand[]::new);
            return new NumberExpr(new NumberOfValueInFields(value, java.util.List.of(fields)));
        }
        if (name == EntityListOperationName.FirstFilledValue) {
            return new NumberExpr(convertFirstFilledValue(aggregate, ValueKind.NUMBER));
        }
        // NumberOfFilledGroups(Group*): a single repeatable group, all reps — a group scope, not a field.
        if (name == EntityListOperationName.NumberOfFilledGroups
                && entities.size() == 1 && !entities.get(0).isField()) {
            return Dsl.numberOfFilledGroups(model.group(entities.get(0).getFullName()));
        }
        // NumberOfFilledGroups(g1, g2, …): two or more distinct groups — count how many are filled.
        if (name == EntityListOperationName.NumberOfFilledGroups
                && entities.size() > 1 && entities.stream().noneMatch(IEntityId::isField)) {
            GroupRef[] g = entities.stream().map(e -> model.group(e.getFullName())).toArray(GroupRef[]::new);
            return Dsl.numberOfFilledGroups(g[0], g[1], Arrays.copyOfRange(g, 2, g.length));
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
                case SumOfProducts -> {
                    if (ops.length != 2) {
                        throw unsupported("SumOfProducts over " + ops.length + " operands (expects 2)", aggregate);
                    }
                    yield Dsl.sumOfProducts(ops[0], ops[1]);
                }
                default -> throw unsupported("aggregate operator " + name + " over a multi-field scope", aggregate);
            };
        }
        if (entities.size() != 1) {
            throw unsupported("aggregate " + name + " over a non-single-field scope", aggregate);
        }
        // a single GROUP wildcard scope (Sum(Group*)) — the kernel expands it to the group's contained number
        // fields (KERNEL-FINDINGS §9), so we make that explicit: Sum(Group*/f1, Group*/f2, …).
        if (!entities.get(0).isField()) {
            FieldOperand[] ops = groupNumberFieldOperands(entities.get(0), aggregate);
            FieldOperand[] rest = Arrays.copyOfRange(ops, 1, ops.length);
            return switch (name) {
                case Sum -> Dsl.sum(ops[0], rest);
                case MaxValue -> Dsl.maxValue(ops[0], rest);
                case MinValue -> Dsl.minValue(ops[0], rest);
                case NumberOfDifferentValues -> Dsl.numberOfDifferentValues(ops[0], rest);
                default -> throw unsupported("group-scope aggregate operator " + name, aggregate);
            };
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

    /**
     * Rebuild a kind-homogeneous {@code FirstFilledValue} operand list without collapsing its authored
     * encounter order. The compatible typed surface requires the first operand to remain a repetition
     * list; every later operand may independently be another list or a plain field.
     */
    private Calculation convertFirstFilledValue(IEntityListOperation aggregate, ValueKind expectedKind) {
        if (aggregate.getOperatorName() != EntityListOperationName.FirstFilledValue) {
            throw unsupported("non-FirstFilledValue operation at the FirstFilledValue seam", aggregate);
        }
        List<IEntityId> entities = aggregate.getEntityList().getEntityIdList();
        if (entities.isEmpty() || entities.stream().anyMatch(entity -> !entity.isField())) {
            throw unsupported("FirstFilledValue over an empty or non-field operand list", aggregate);
        }
        List<FieldOperand> operands = entities.stream().map(this::toFieldOperand).toList();
        if (!(operands.getFirst() instanceof FieldListRef scope)) {
            throw unsupported("FirstFilledValue whose first operand is not a repetition list", aggregate);
        }
        if (operands.stream().anyMatch(operand -> operand.kind() != expectedKind)) {
            throw unsupported("FirstFilledValue with operands outside its " + expectedKind + " result kind", aggregate);
        }
        if (operands.size() == 1) {
            return switch (expectedKind) {
                case NUMBER -> Dsl.firstFilledNumber(scope).expression();
                case STRING -> Dsl.firstFilledString(scope).expression();
                case DATE -> Dsl.firstFilledDate(scope).expression();
                case ENUMERATION -> Dsl.firstFilledEnum(scope).expression();
                default -> throw unsupported("FirstFilledValue with result kind " + expectedKind, aggregate);
            };
        }
        FieldOperand second = operands.get(1);
        FieldOperand[] rest = operands.subList(2, operands.size()).toArray(FieldOperand[]::new);
        return switch (expectedKind) {
            case NUMBER -> Dsl.firstFilledNumber(scope, second, rest).expression();
            case STRING -> Dsl.firstFilledString(scope, second, rest).expression();
            case DATE -> Dsl.firstFilledDate(scope, second, rest).expression();
            case ENUMERATION -> Dsl.firstFilledEnum(scope, second, rest).expression();
            default -> throw unsupported("FirstFilledValue with result kind " + expectedKind, aggregate);
        };
    }

    /** Rebuild the wildcard scope (e.g. {@code Items} flattened) from the resolved path + indices, plus any Having filter. */
    private FieldListRef flattenScope(IEntityId entity) {
        FieldListRef scope = model.flatten(reconstructStarPath(entity));
        ICondition filter = entity.getFilterCondition();
        return filter == null ? scope : scope.having(convert(filter));
    }

    /**
     * The descendant number fields of a group, each as a flattened wildcard list — the recursive expansion
     * of a group-scoped aggregate ({@code Sum(Group*)} becomes a Sum over each {@code Group* / fN} list),
     * matching the kernel's "a group argument is expanded to its contained fields" semantic (KERNEL-FINDINGS §9).
     */
    private FieldOperand[] groupNumberFieldOperands(IEntityId groupEntity, IEntityListOperation origin) {
        String groupPath = groupEntity.getFullName();
        String star = reconstructStarPath(groupEntity);
        FieldOperand[] ops = descendantFields(groupEntity).stream()
                .filter(f -> f.kind() == ValueKind.NUMBER)
                .map(f -> (FieldOperand) model.flatten(star + f.path().substring(groupPath.length())))
                .toArray(FieldOperand[]::new);
        if (ops.length == 0) {
            throw unsupported("group-scope aggregate over a group with no number fields", origin);
        }
        return ops;
    }

    /** Every field below a group, recursively and in model order. */
    private List<FieldInfo> descendantFields(IEntityId groupEntity) {
        String prefix = model.group(groupEntity.getFullName()).path() + "/";
        return model.fields().filter(field -> field.path().startsWith(prefix)).toList();
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
            Tolerance tol = toleranceOf(op);   // NE1/NE2/NE5/NE10 → DiffersWithToleranceRange{N}
            if (tol != null) {
                return expr.differsWithTolerance(tol, Dsl.number(v));
            }
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
        if (operand instanceof IDateExtractionOperation ext) {
            return convertDateExtraction(ext);
        }
        // NumberOfFilledGroups(/RootGroup*) — count of a repeatable root group's filled repetitions
        if (operand instanceof INumberOfRootGroupsOperation rootCount) {
            return Dsl.numberOfFilledRootGroups(model.group("/" + rootCount.getRootGroupName()));
        }
        // BaseYear compared to a number field acts as the year number
        if (operand instanceof IPointInTimeOperation pit
                && pit.getOperatorName() == PointInTimeOperationName.BaseYear) {
            return new NumberExpr(new BaseYear());
        }
        if (operand instanceof IFieldIdOperation lenOp && lenOp.getOperatorName() == FieldIdOperationName.Length) {
            return model.str(lenOp.getField().getFullName()).length();
        }
        // numeric coercions (family 4): RangeAsNumber(field, s, e) — 1-based syntax over the kernel's 0-based start
        if (operand instanceof IFieldIdOperationWithParameters params
                && params.getOperatorName() == FieldIdOperationName.RangeAsNumber) {
            return new NumberExpr(new RangeAsNumber(model.field(params.getField().getFullName()),
                    params.getRangeStart() + 1, params.getRangeEnd()));
        }
        // FieldValueAsNumber(field) — a string/enum field's value coerced to a number
        if (operand instanceof IFieldIdOperation fvn
                && fvn.getOperatorName() == FieldIdOperationName.FieldValueAsNumber) {
            return new NumberExpr(new FieldValueAsNumber(model.field(fvn.getField().getFullName())));
        }
        // field-level rounding/abs: RoundDownValue(f)/RoundUpValue(f)/RoundAccountingValue(f)/AbsValue(f)
        if (operand instanceof IFieldIdOperation fieldOp) {
            NumberRef field = switch (fieldOp.getOperatorName()) {
                case RoundDownValue, RoundUpValue, RoundAccountingValue, AbsValue ->
                        model.num(fieldOp.getField().getFullName());
                default -> null;
            };
            if (field != null) {
                int places = fieldOp instanceof IFieldIdOperationWithParameters params
                        ? params.getNumberOfDecimalPlaces()
                        : 0;
                boolean explicitPlaces = fieldOp instanceof IFieldIdOperationWithParameters && places > 0;
                return switch (fieldOp.getOperatorName()) {
                    case RoundDownValue -> explicitPlaces
                            ? Dsl.roundDownValue(field, places) : Dsl.roundDownValue(field);
                    case RoundUpValue -> explicitPlaces
                            ? Dsl.roundUpValue(field, places) : Dsl.roundUpValue(field);
                    case RoundAccountingValue -> explicitPlaces
                            ? Dsl.roundAccountingValue(field, places) : Dsl.roundAccountingValue(field);
                    case AbsValue -> Dsl.absValue(field);
                    default -> null;
                };
            }
        }
        if (operand instanceof ICompositeOperation arithmetic) {
            return convertArithmetic(arithmetic);
        }
        if (operand instanceof IOpListOperationWithParameter rounding) {
            return convertRounding(rounding);
        }
        // operand-list math (family 5): Abs(value) / Min(v1, …) / Max(v1, …) over numeric expressions
        if (operand instanceof IOpListOperation oplist) {
            List<NumberExpr> args = oplist.getOperations().stream().map(o -> arithmeticOperand(o, oplist)).toList();
            NumberExpr[] rest = args.subList(1, args.size()).toArray(NumberExpr[]::new);
            return switch (oplist.getOperatorName()) {
                case Abs -> Dsl.abs(args.get(0));
                case Min -> Dsl.min(args.get(0), rest);
                case Max -> Dsl.max(args.get(0), rest);
                default -> throw unsupported("operand-list operator " + oplist.getOperatorName(), oplist);
            };
        }
        if (operand instanceof IGroupOperation groupOp
                && groupOp.getOperatorName() == GroupOperationName.CurrentRepetition) {
            return Dsl.currentRepetition(model.group(groupOp.getGroup().getFullName()),
                    groupOp.getGroup().isOuter());   // $ self-exclusion correlation (D13)
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

    /** {@code RoundDown/RoundUp/RoundAccounting(expr[, n])} — kernel operand-list rounding over an expression. */
    private NumberExpr convertRounding(IOpListOperationWithParameter rounding) {
        // The parse object stores omission and explicit zero alike as 0, so read-back canonicalizes both
        // to the one-argument spelling. A positive argument remains explicit.
        NumberExpr operand = arithmeticOperand(rounding.getOperations().get(0), rounding);
        int places = rounding.getNumberOfDecimalPlaces();
        OpListOperationName name = rounding.getOperatorName();
        return switch (name) {
            case RoundDown -> places > 0 ? Dsl.roundDown(operand, places) : Dsl.roundDown(operand);
            case RoundUp -> places > 0 ? Dsl.roundUp(operand, places) : Dsl.roundUp(operand);
            case RoundAccounting -> places > 0
                    ? Dsl.roundAccounting(operand, places) : Dsl.roundAccounting(operand);
            default -> throw unsupported("operand-list operator " + name, rounding);
        };
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
        Tolerance tol = toleranceOf(op);   // NE1/NE2/NE5/NE10 → DiffersWithToleranceRange{N}
        if (tol != null) {
            return left.differsWithTolerance(tol, right);
        }
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

    /** Map a kernel tolerance comparison operator ({@code NE1/NE2/NE5/NE10}) to a {@link Tolerance}, else {@code null}. */
    private static Tolerance toleranceOf(ComparisonOperation op) {
        return switch (op) {
            case NE1 -> Tolerance.RANGE_1;
            case NE2 -> Tolerance.RANGE_2;
            case NE5 -> Tolerance.RANGE_5;
            case NE10 -> Tolerance.RANGE_10;
            default -> null;
        };
    }

    private Condition numberComparison(NumberRef left, ComparisonOperation op, IOperation right,
            ICondition origin) {
        // tolerance comparison ([a] DiffersWithToleranceRange{N} [b]) — unequal within a tolerance
        Tolerance tol = toleranceOf(op);
        if (tol != null) {
            if (right instanceof IConstantOperation c) {
                return left.asNumber().differsWithTolerance(tol, Dsl.number(c.getValue()));
            }
            if (right instanceof IFieldIdOperation rf && rf.getOperatorName() == FieldIdOperationName.FieldValue) {
                NumberRef other = model.num(rf.getField().getFullName());
                return left.differsWithTolerance(tol, rf.getField().isOuter() ? other.outer() : other);
            }
            NumberExpr rightExpr = numericExpr(right);
            if (rightExpr != null) {
                return left.asNumber().differsWithTolerance(tol, rightExpr);
            }
            throw unsupported("tolerance comparison against an unsupported operand", origin);
        }
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
            if (rf.getField().isOuter()) {
                other = other.outer();   // $ correlation on the right operand (D13)
            }
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
            if (rf.getField().isOuter()) {
                other = other.outer();   // $ correlation on the right operand (D13)
            }
            return switch (op) {
                case EQ -> left.eq(other);
                case NE -> left.ne(other);
                default -> throw unsupported("string field comparison operator " + op, origin);
            };
        }
        // [field] == / != FieldValueAsString(other) / RangeAsString(other, …) — a string coercion on the right
        if (isStringCoercion(right)) {
            StringExpr coerced = stringCoercion((IFieldIdOperation) right);
            return switch (op) {
                case EQ -> left.eq(coerced);
                case NE -> left.ne(coerced);
                default -> throw unsupported("string coercion comparison operator " + op, origin);
            };
        }
        // [field] == / != FirstFilledValue(/Group*/StringField) — a string-valued aggregate on the right
        if (right instanceof IEntityListOperation aggregate) {
            StringExpr agg = stringAggregate(aggregate);
            return switch (op) {
                case EQ -> left.eq(agg);
                case NE -> left.ne(agg);
                default -> throw unsupported("string aggregate comparison operator " + op, origin);
            };
        }
        // [field] == / != ([a] + " " + [b]) — string concatenation on the right (a string-typed composite)
        if (right instanceof ICompositeOperation comp && comp.getType() == OperationType.STRING) {
            StringExpr concat = convertConcatenation(comp);
            return switch (op) {
                case EQ -> left.eq(concat);
                case NE -> left.ne(concat);
                default -> throw unsupported("string concatenation comparison operator " + op, origin);
            };
        }
        throw unsupported("string comparison against a non-field, non-constant operand", origin);
    }

    /** {@code [a] + " " + [b]} — the kernel's string-typed {@code +} composite, flattened into a concatenation. */
    private StringExpr convertConcatenation(ICompositeOperation comp) {
        if (comp.getCalcOp() != CalcOp.ADD) {
            throw unsupported("string composite with operator " + comp.getCalcOp(), comp);
        }
        List<IOperation> ops = comp.getOperations();
        StringExpr acc = stringOperand(ops.get(0), comp);
        for (int i = 1; i < ops.size(); i++) {
            acc = acc.plus(stringOperand(ops.get(i), comp));
        }
        return acc;
    }

    /** One operand of a concatenation: a string literal, a string field value, or a nested concatenation. */
    private StringExpr stringOperand(IOperation op, Object origin) {
        if (op instanceof IConstantOperation constant) {
            return Dsl.text(constant.getValue());
        }
        if (op instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            return model.str(field.getField().getFullName()).asString();
        }
        if (isStringCoercion(op)) {
            return stringCoercion((IFieldIdOperation) op);
        }
        if (op instanceof IEntityListOperation aggregate) {
            return stringAggregate(aggregate);
        }
        if (op instanceof ICompositeOperation nested && nested.getType() == OperationType.STRING) {
            return convertConcatenation(nested);
        }
        throw unsupported("string concatenation operand " + op.getClass().getSimpleName(), origin);
    }

    /** A string-valued aggregate ({@code FirstFilledValue} over a string repetition list) as a {@link StringExpr}. */
    private StringExpr stringAggregate(IEntityListOperation aggregate) {
        List<IEntityId> entities = aggregate.getEntityList().getEntityIdList();
        if (aggregate.getOperatorName() == EntityListOperationName.FirstFilledValue) {
            return new StringExpr(convertFirstFilledValue(aggregate, ValueKind.STRING));
        }
        if (entities.size() != 1 || !entities.get(0).isField()) {
            throw unsupported("string aggregate over a non-single-field scope", aggregate);
        }
        throw unsupported("string aggregate operator " + aggregate.getOperatorName(), aggregate);
    }

    /** Whether {@code op} is a string-coercion field operation ({@code FieldValueAsString} / {@code RangeAsString}). */
    private static boolean isStringCoercion(IOperation op) {
        return op instanceof IFieldIdOperation f
                && (f.getOperatorName() == FieldIdOperationName.FieldValueAsString
                        || f.getOperatorName() == FieldIdOperationName.RangeAsString);
    }

    /** Convert a string-coercion field operation to a {@link StringExpr}. */
    private StringExpr stringCoercion(IFieldIdOperation field) {
        String path = field.getField().getFullName();
        return switch (field.getOperatorName()) {
            case FieldValueAsString -> hasSemanticIndex(field.getField())
                    ? Dsl.fieldValueAsString(semanticIndex(field.getField(), field))
                    : Dsl.fieldValueAsString(model.field(path));
            case RangeAsString -> {
                // the kernel stores a 0-based start; the rule-language syntax is 1-based (verified by round-trip)
                IFieldIdOperationWithParameters params = (IFieldIdOperationWithParameters) field;
                yield Dsl.rangeAsString(model.field(path), params.getRangeStart() + 1, params.getRangeEnd());
            }
            default -> throw unsupported("string coercion " + field.getOperatorName(), field);
        };
    }

    /** Whether this entity carries the kernel's {@code [Field For key]} semantic-index payload. */
    private static boolean hasSemanticIndex(IEntityId entity) {
        List<Object> key = entity.getSemanticIndex();
        return key != null && !key.isEmpty();
    }

    /**
     * Convert the one-element semantic-index payload once for every condition/operation locus that embeds
     * an {@link IEntityId}. A key is either a string literal or another entity id; any other or composite
     * shape remains explicitly opaque.
     */
    private SemanticIndex semanticIndex(IEntityId entity, Object origin) {
        List<Object> keys = entity.getSemanticIndex();
        if (keys == null || keys.size() != 1) {
            throw unsupported("semantic index ([field For …]) with a non-single key", origin);
        }
        FieldRef field = model.field(entity.getFullName());
        Object key = keys.get(0);
        if (key instanceof String literal) {
            return field.semanticIndex(literal);
        }
        if (key instanceof IEntityId keyField) {
            return field.semanticIndex(model.field(keyField.getFullName()));
        }
        throw unsupported("semantic index ([field For …]) with key " + key.getClass().getSimpleName(), origin);
    }

    /** A comparison whose left operand is a string expression (a coercion) — vs a constant, field, or coercion. */
    private Condition stringExprComparison(StringExpr left, ComparisonOperation op, IOperation right,
            ICondition origin) {
        if (right instanceof IConstantOperation constant) {
            return switch (op) {
                case EQ -> left.eq(constant.getValue());
                case NE -> left.ne(constant.getValue());
                default -> throw unsupported("string-expression comparison operator " + op, origin);
            };
        }
        if (right instanceof IFieldIdOperation rf && rf.getOperatorName() == FieldIdOperationName.FieldValue) {
            StringExpr other = model.str(rf.getField().getFullName()).asString();
            return switch (op) {
                case EQ -> left.eq(other);
                case NE -> left.ne(other);
                default -> throw unsupported("string-expression comparison operator " + op, origin);
            };
        }
        if (isStringCoercion(right)) {
            StringExpr other = stringCoercion((IFieldIdOperation) right);
            return switch (op) {
                case EQ -> left.eq(other);
                case NE -> left.ne(other);
                default -> throw unsupported("string-expression comparison operator " + op, origin);
            };
        }
        throw unsupported("string-expression compared against an unsupported operand", origin);
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
            if (rf.getField().isOuter()) {
                other = other.outer();   // $ correlation on the right operand (D13)
            }
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
            if (rf.getField().isOuter()) {
                other = other.outer();   // $ correlation on the right operand (D13)
            }
            return switch (op) {
                case EQ -> left.is(other);
                case NE -> left.isNot(other);
                default -> throw unsupported("enum field comparison operator " + op, origin);
            };
        }
        // an enum-valued FirstFilledValue over an enum repetition list ([enum] != FirstFilledValue(items*/Cat))
        if (right instanceof IEntityListOperation aggregate
                && aggregate.getOperatorName() == EntityListOperationName.FirstFilledValue) {
            EnumExpr other = new EnumExpr(convertFirstFilledValue(aggregate, ValueKind.ENUMERATION));
            return switch (op) {
                case EQ -> left.is(other);
                case NE -> left.isNot(other);
                default -> throw unsupported("enum aggregate comparison operator " + op, origin);
            };
        }
        throw unsupported("enum comparison against a non-constant operand, operator " + op, origin);
    }

    /** {@code <enum expr> <op> other} — the expression peer of {@link #enumComparison} (a DG15 enum semantic index). */
    private Condition enumExprComparison(EnumExpr left, ComparisonOperation op, IOperation right, ICondition origin) {
        if (right instanceof IConstantOperation constant) {
            return switch (op) {
                case EQ -> left.is(constant.getValue());
                case NE -> left.isNot(constant.getValue());
                default -> throw unsupported("enum comparison operator " + op, origin);
            };
        }
        if (right instanceof IFieldIdOperation rf && rf.getOperatorName() == FieldIdOperationName.FieldValue) {
            EnumRef other = model.enumOf(rf.getField().getFullName());
            if (rf.getField().isOuter()) {
                other = other.outer();   // $ correlation on the right operand (D13)
            }
            return switch (op) {
                case EQ -> left.is(other);
                case NE -> left.isNot(other);
                default -> throw unsupported("enum field comparison operator " + op, origin);
            };
        }
        throw unsupported("enum semantic-index compared against " + right.getClass().getSimpleName(), origin);
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

    private Condition dateComparison(DateOperand left, ComparisonOperation op, IOperation right, ICondition origin) {
        return dateComparison(left, op, convertDateOperand(right, origin), origin);
    }

    /** Compare two date operands (the left may be a field or a computed shift) — the shared op dispatch. */
    private Condition dateComparison(DateOperand left, ComparisonOperation op, DateOperand other, ICondition origin) {
        return switch (op) {
            case LT -> left.before(other);
            case LE -> left.onOrBefore(other);
            case GT -> left.after(other);
            case GE -> left.onOrAfter(other);
            case EQ -> left.eq(other);
            case NE -> left.ne(other);
            default -> throw unsupported("date comparison operator " + op, origin);
        };
    }

    /** {@code [time] <op> other} — a time field vs another time field or {@code TimeFromDateTime(dt)}. */
    private Condition timeComparison(TimeOperand left, ComparisonOperation op, IOperation right, ICondition origin) {
        TimeOperand other = convertTimeOperand(right, origin);
        return switch (op) {
            case LT -> left.before(other);
            case LE -> left.onOrBefore(other);
            case GT -> left.after(other);
            case GE -> left.onOrAfter(other);
            case EQ -> left.eq(other);
            case NE -> left.ne(other);
            default -> throw unsupported("time comparison operator " + op, origin);
        };
    }

    /** {@code [dateTime] <op> other} — a date-time field vs another date-time value (field, {@code DateTime(...)}, or shift). */
    private Condition dateTimeComparison(DateTimeOperand left, ComparisonOperation op, IOperation right, ICondition origin) {
        DateTimeOperand other = convertDateTimeOperand(right, origin);
        return switch (op) {
            case LT -> left.before(other);
            case LE -> left.onOrBefore(other);
            case GT -> left.after(other);
            case GE -> left.onOrAfter(other);
            case EQ -> left.eq(other);
            case NE -> left.ne(other);
            default -> throw unsupported("date-time comparison operator " + op, origin);
        };
    }

    /** A date-time-valued operand: a date-time field, a {@code DateTime(date, time)} construction, or a sub-day shift. */
    private DateTimeOperand convertDateTimeOperand(IOperation op, Object origin) {
        if (op instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue
                && model.fieldAt(field.getField().getFullName())
                        .map(i -> i.kind() == ValueKind.DATE_TIME).orElse(false)) {
            DateTimeRef dt = model.dateTime(field.getField().getFullName());
            return field.getField().isOuter() ? dt.outer() : dt;   // $ correlation (D13)
        }
        // DateTime(date, time) — the date is a date value (field/literal/Date(...)), the time a time value
        if (op instanceof IDateTimeConstructionOperation dtc) {
            Calculation date = convertDateOperand(dtc.getDate(), dtc).expression();
            Calculation time = convertTimeOperand(dtc.getTime(), dtc).expression();
            return new DateTimeExpr(new DateTimeConstruction(date, time));
        }
        // a sub-day shift, or AddDays over a date-time, yields a date-time
        if (op instanceof IDateAddOperation add) {
            return convertDateTimeAdd(add);
        }
        if (op instanceof IOpListOperation operandList && operandList.getType() == OperationType.DATE) {
            return new DateTimeExpr(convertTemporalOperandList(operandList, TemporalKind.DATE_TIME));
        }
        // Now / BaseYear as a date-time operand (both compare to a date-time field)
        if (op instanceof IPointInTimeOperation pit) {
            return switch (pit.getOperatorName()) {
                case Now -> new DateTimeExpr(new Now());
                case BaseYear -> new DateTimeExpr(new BaseYear());
                default -> throw unsupported("date-time point-in-time " + pit.getOperatorName(), origin);
            };
        }
        // MaxValue/MinValue over a DATETIME repetition list → the latest/earliest instant
        if (op instanceof IEntityListOperation aggregate && temporalExtremumKind(aggregate) == ValueKind.DATE_TIME) {
            return new DateTimeExpr(temporalListAggregate(aggregate));
        }
        throw unsupported("date-time operand " + op.getClass().getSimpleName(), origin);
    }

    /** Date/time differences, dispatched through the exact operand families each kernel operation accepts. */
    private NumberExpr convertDateDiff(IDateDiffOperation diff) {
        DateDiffOperationName name = diff.getOperatorName();
        return switch (name) {
            case DifferenceInDays -> differenceInDays(
                    convertDateOrDateTimeOperand(diff.getDateSpec1(), diff),
                    convertDateOrDateTimeOperand(diff.getDateSpec2(), diff), diff);
            case DifferenceInMonths -> Dsl.differenceInMonths(
                    convertDateOperand(diff.getDateSpec1(), diff), convertDateOperand(diff.getDateSpec2(), diff));
            case DifferenceInYears -> Dsl.differenceInYears(
                    convertDateOperand(diff.getDateSpec1(), diff), convertDateOperand(diff.getDateSpec2(), diff));
            case DifferenceInHours -> Dsl.differenceInHours(
                    convertDateTimeOperand(diff.getDateSpec1(), diff),
                    convertDateTimeOperand(diff.getDateSpec2(), diff));
            case DifferenceInMinutes -> Dsl.differenceInMinutes(
                    convertDateTimeOperand(diff.getDateSpec1(), diff),
                    convertDateTimeOperand(diff.getDateSpec2(), diff));
            case DifferenceInSeconds -> Dsl.differenceInSeconds(
                    convertDateTimeOperand(diff.getDateSpec1(), diff),
                    convertDateTimeOperand(diff.getDateSpec2(), diff));
            default -> throw unsupported("date-diff operator " + name, diff);
        };
    }

    private NumberExpr differenceInDays(TemporalOperand a, TemporalOperand b, Object origin) {
        if (a instanceof DateOperand dateA && b instanceof DateOperand dateB) {
            return Dsl.differenceInDays(dateA, dateB);
        }
        if (a instanceof DateOperand dateA && b instanceof DateTimeOperand dateTimeB) {
            return Dsl.differenceInDays(dateA, dateTimeB);
        }
        if (a instanceof DateTimeOperand dateTimeA && b instanceof DateOperand dateB) {
            return Dsl.differenceInDays(dateTimeA, dateB);
        }
        if (a instanceof DateTimeOperand dateTimeA && b instanceof DateTimeOperand dateTimeB) {
            return Dsl.differenceInDays(dateTimeA, dateTimeB);
        }
        throw unsupported("DifferenceInDays over a non-date temporal operand", origin);
    }

    /** The exact date-or-date-time union accepted by {@code DifferenceInDays}. */
    private TemporalOperand convertDateOrDateTimeOperand(IOperation op, Object origin) {
        return isDateTimeValue(op) ? convertDateTimeOperand(op, origin) : convertDateOperand(op, origin);
    }

    /** Operation-shape discriminator for values whose kernel family is date-time rather than date. */
    private boolean isDateTimeValue(IOperation op) {
        if (op instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            return model.fieldAt(field.getField().getFullName())
                    .map(i -> i.kind() == ValueKind.DATE_TIME).orElse(false);
        }
        if (op instanceof IDateTimeConstructionOperation) {
            return true;
        }
        if (op instanceof IDateAddOperation add) {
            return switch (add.getOperatorName()) {
                case AddHours, AddMinutes, AddSeconds -> true;
                case AddDays -> isDateTimeValue(add.getDateSpec());
                case AddMonths, AddYears -> false;
                default -> false;
            };
        }
        return op instanceof IPointInTimeOperation pit
                && pit.getOperatorName() == PointInTimeOperationName.Now;
    }

    /** A date-valued operand: a date field ({@code FieldValue}), a date constant, or a point-in-time ({@code Today}). */
    private DateOperand convertDateOperand(IOperation op, Object origin) {
        if (op instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            String path = field.getField().getFullName();
            boolean outer = field.getField().isOuter();   // $ correlation (D13)
            // a date-fragment field is a legal date operand too (the kernel collapses it to a Date, §6) —
            // resolving it as a plain date here would throw "is not a date field (kind=DATE_FRAGMENT)"
            if (model.fieldAt(path).map(i -> i.kind() == ValueKind.DATE_FRAGMENT).orElse(false)) {
                DateFragmentRef frag = model.dateFragment(path);
                return outer ? frag.outer() : frag;
            }
            DateRef date = model.date(path);
            return outer ? date.outer() : date;
        }
        // a date constant — [date] > "01.01.2022", or a DifferenceInDays(date, "01.01.2022") operand (the
        // kernel stores it German-format, type DATE, independent of the field's display format)
        if (op instanceof IConstantOperation constant) {
            return new DateExpr(new DateLiteral(constant.getValue()));
        }
        // ValueAsDate(field, FirstDay|LastDay) — resolves a partially-known-date field to a concrete date
        if (op instanceof IFieldIdOperationWithParameters vad
                && vad.getOperatorName() == FieldIdOperationName.ValueAsDate) {
            return new DateExpr(new ValueAsDate(model.field(vad.getField().getFullName()), vad.isFirstDay()));
        }
        if (op instanceof IPointInTimeOperation pit) {
            return switch (pit.getOperatorName()) {
                case Today -> Dsl.today();
                case Now -> new DateExpr(new Now());          // Now compares to a date field too (day-truncated)
                case BaseYear -> new DateExpr(new BaseYear()); // the base-year start, as a date
                default -> throw unsupported("point-in-time " + pit.getOperatorName(), origin);
            };
        }
        if (op instanceof IOpListOperation operandList && operandList.getType() == OperationType.DATE) {
            return new DateExpr(convertTemporalOperandList(operandList, TemporalKind.DATE));
        }
        // MaxValue/MinValue over a date repetition list → a date-valued operand (latest/earliest)
        if (op instanceof IEntityListOperation aggregate) {
            return convertDateAggregate(aggregate);
        }
        // AddDays/AddMonths/AddYears(date, n) → a shifted date
        if (op instanceof IDateAddOperation add) {
            return convertDateAdd(add);
        }
        // DateFromDateTime(dt) → the date part of a date-time, as a date operand
        if (op instanceof IDateExtractionOperation ext
                && ext.getOperatorName() == DateExtractionOperationName.DateFromDateTime) {
            return Dsl.dateFromDateTime(convertDateTimeOperand(ext.getDateSpec(), ext));
        }
        // StartOfDateRange(range) / EndOfDateRange(range) → a bound of a date-range, as a date operand
        if (op instanceof IDateRangeExtractionOperation rangeExt) {
            boolean start = rangeExt.getOperatorName() == DateRangeExtractionOperationName.StartOfDateRange;
            IOperation spec = rangeExt.getDateRangeSpec();
            // BaseYear is the one point-in-time usable as a date-range source: Start = 01.01, End = 31.12 of
            // the reference year. Discriminate on the enum — its getType() reports NUMBER in this position.
            if (spec instanceof IPointInTimeOperation pit
                    && pit.getOperatorName() == PointInTimeOperationName.BaseYear) {
                return start ? Dsl.startOfBaseYear() : Dsl.endOfBaseYear();
            }
            DateRangeRef range = dateRangeSource(spec, rangeExt);
            return start ? Dsl.startOfDateRange(range) : Dsl.endOfDateRange(range);
        }
        // Date(day, month, year[, decade]) → a constructed date operand
        if (op instanceof IDateConstructionOperation date) {
            Optional<Calculation> year2 = date.getYear2() == null ? Optional.empty()
                    : Optional.of(convertDateComponent(date.getYear2(), date));
            return new DateExpr(new DateConstruction(convertDateComponent(date.getDay(), date),
                    convertDateComponent(date.getMonth(), date), convertDateComponent(date.getYear1(), date), year2));
        }
        throw unsupported("date operand " + op.getClass().getSimpleName(), origin);
    }

    /** One component of a {@code Date(...)} construction — a numeric field/extraction or a (zero-padded) constant. */
    private Calculation convertDateComponent(IOperation op, Object origin) {
        if (op instanceof IConstantOperation constant) {
            // the kernel renders date-component literals quoted (e.g. Date("01", …)) — preserve verbatim
            return new StringLiteral(constant.getValue());
        }
        if (op instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            return new FieldValue(model.field(field.getField().getFullName()));
        }
        if (op instanceof IDateExtractionOperation ext) {
            return convertDateExtraction(ext).expression();
        }
        throw unsupported("date-construction component " + op.getClass().getSimpleName(), origin);
    }

    /** A time-valued operand: a time field ({@code FieldValue}), a time constant, or {@code TimeFromDateTime(dt)}. */
    private TimeOperand convertTimeOperand(IOperation op, Object origin) {
        if (op instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue
                && model.fieldAt(field.getField().getFullName()).map(i -> i.kind() == ValueKind.TIME).orElse(false)) {
            TimeRef time = model.time(field.getField().getFullName());
            return field.getField().isOuter() ? time.outer() : time;   // $ correlation (D13)
        }
        // a time constant — [time] > "17:00:00" (kernel: an IConstantOperation typed DATE, value HH:mm:ss)
        if (op instanceof IConstantOperation constant) {
            return new TimeExpr(new TimeLiteral(constant.getValue()));
        }
        // Time(hour, minute, second) — a constructed time-of-day (components are quoted constants or fields)
        if (op instanceof ITimeConstructionOperation tc) {
            return new TimeExpr(new TimeConstruction(convertDateComponent(tc.getHour(), tc),
                    convertDateComponent(tc.getMinute(), tc), convertDateComponent(tc.getSecond(), tc)));
        }
        if (op instanceof IOpListOperation operandList && operandList.getType() == OperationType.DATE) {
            return new TimeExpr(convertTemporalOperandList(operandList, TemporalKind.TIME));
        }
        // TimeFromDateTime(dt) → the time part of a date-time, as a time operand
        if (op instanceof IDateExtractionOperation ext
                && ext.getOperatorName() == DateExtractionOperationName.TimeFromDateTime) {
            return Dsl.timeFromDateTime(convertDateTimeOperand(ext.getDateSpec(), ext));
        }
        // MaxValue/MinValue over a TIME repetition list → the latest/earliest time-of-day
        if (op instanceof IEntityListOperation aggregate && temporalExtremumKind(aggregate) == ValueKind.TIME) {
            return new TimeExpr(temporalListAggregate(aggregate));
        }
        throw unsupported("time operand " + op.getClass().getSimpleName(), origin);
    }

    /**
     * The date-like kind carried by an operand-list {@code Min}/{@code Max}. The kernel transport flattens
     * DATE, TIME, and DATETIME to {@link OperationType#DATE}, so recover the exact kind from the typed operands;
     * a lone context-typed literal falls back to the comparison peer.
     */
    private TemporalKind temporalOperandListKind(IOpListOperation operandList, IOperation comparisonPeer) {
        TemporalKind found = inferredTemporalOperandListKind(operandList);
        if (found == null) {
            found = temporalOperandKind(comparisonPeer);
        }
        if (found == null) {
            throw unsupported("date-like operand-list whose exact kind cannot be recovered", operandList);
        }
        return found;
    }

    /** Infer from the members alone; {@code null} lets an enclosing list or comparison supply the context. */
    private TemporalKind inferredTemporalOperandListKind(IOpListOperation operandList) {
        TemporalKind found = null;
        for (IOperation operand : operandList.getOperations()) {
            TemporalKind current = temporalOperandKind(operand);
            if (current == null) {
                continue;
            }
            if (found != null && found != current) {
                throw unsupported("mixed date-like operand-list kinds " + found + " and " + current, operandList);
            }
            found = current;
        }
        return found;
    }

    /** Exact DATE/TIME/DATETIME family of one kernel operation, or {@code null} when it carries no such clue. */
    private TemporalKind temporalOperandKind(IOperation op) {
        if (op instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue) {
            return model.fieldAt(field.getField().getFullName())
                    .map(FieldInfo::kind)
                    .map(ConditionConverter::temporalKind)
                    .orElse(null);
        }
        if (op instanceof IDateTimeConstructionOperation) {
            return TemporalKind.DATE_TIME;
        }
        if (op instanceof ITimeConstructionOperation) {
            return TemporalKind.TIME;
        }
        if (op instanceof IDateConstructionOperation || op instanceof IDateRangeExtractionOperation) {
            return TemporalKind.DATE;
        }
        if (op instanceof IDateExtractionOperation extraction) {
            return switch (extraction.getOperatorName()) {
                case DateFromDateTime -> TemporalKind.DATE;
                case TimeFromDateTime -> TemporalKind.TIME;
                case DayFromDate, MonthFromDate, YearFromDate, QuarterFromDate,
                        HoursFromTime, MinutesFromTime, SecondsFromTime -> null;
            };
        }
        if (op instanceof IDateAddOperation add) {
            return isDateTimeValue(add) ? TemporalKind.DATE_TIME : TemporalKind.DATE;
        }
        if (op instanceof IPointInTimeOperation point) {
            return point.getOperatorName() == PointInTimeOperationName.Now ? TemporalKind.DATE_TIME : TemporalKind.DATE;
        }
        if (op instanceof IFieldIdOperationWithParameters field
                && field.getOperatorName() == FieldIdOperationName.ValueAsDate) {
            return TemporalKind.DATE;
        }
        if (op instanceof IEntityListOperation aggregate) {
            return temporalKind(temporalExtremumKind(aggregate));
        }
        if (op instanceof IOpListOperation nested && nested.getType() == OperationType.DATE) {
            return inferredTemporalOperandListKind(nested);
        }
        return null;
    }

    /** Narrow the broad model vocabulary to the exact three kinds this converter branch can carry. */
    private static TemporalKind temporalKind(ValueKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case DATE, DATE_FRAGMENT -> TemporalKind.DATE;
            case TIME -> TemporalKind.TIME;
            case DATE_TIME -> TemporalKind.DATE_TIME;
            case NUMBER, STRING, ENUMERATION, BOOLEAN, CONFIRM, DATE_RANGE, CUSTOM, UNKNOWN -> null;
        };
    }

    /** Convert a homogeneous date-like operand-list once, preserving its exact result kind in the typed wrapper. */
    private OpListMath convertTemporalOperandList(IOpListOperation operandList, TemporalKind kind) {
        OpListMath.Op operator = switch (operandList.getOperatorName()) {
            case Min -> OpListMath.Op.MIN;
            case Max -> OpListMath.Op.MAX;
            default -> throw unsupported("date-like operand-list operator " + operandList.getOperatorName(),
                    operandList);
        };
        List<Calculation> operands = operandList.getOperations().stream().map(operand -> switch (kind) {
            case DATE -> convertDateOperand(operand, operandList).expression();
            case TIME -> convertTimeOperand(operand, operandList).expression();
            case DATE_TIME -> convertDateTimeOperand(operand, operandList).expression();
        }).toList();
        return new OpListMath(operator, operands);
    }

    private enum TemporalKind {
        DATE,
        TIME,
        DATE_TIME
    }

    /**
     * The number-valued date/time component extractions — {@code YearFromDate}/…/{@code QuarterFromDate}
     * (from a date or date-time) and {@code HoursFromTime}/{@code MinutesFromTime}/{@code SecondsFromTime}
     * (from a time or date-time). The date/time-valued {@code Date|TimeFromDateTime} are handled as
     * date/time operands, not here.
     */
    private NumberExpr convertDateExtraction(IDateExtractionOperation ext) {
        DateExtractionOperationName name = ext.getOperatorName();
        return switch (name) {
            case DayFromDate -> dayFromDate(convertDateOrDateTimeOperand(ext.getDateSpec(), ext), ext);
            case MonthFromDate -> monthFromDate(convertDateOrDateTimeOperand(ext.getDateSpec(), ext), ext);
            case YearFromDate -> yearFromDate(convertDateOrDateTimeOperand(ext.getDateSpec(), ext), ext);
            case QuarterFromDate -> quarterFromDate(convertDateOrDateTimeOperand(ext.getDateSpec(), ext), ext);
            case HoursFromTime -> hoursFromTime(convertTimeOrDateTimeOperand(ext.getDateSpec(), ext), ext);
            case MinutesFromTime -> minutesFromTime(convertTimeOrDateTimeOperand(ext.getDateSpec(), ext), ext);
            case SecondsFromTime -> secondsFromTime(convertTimeOrDateTimeOperand(ext.getDateSpec(), ext), ext);
            default -> throw unsupported("date/time extraction " + name, ext);
        };
    }

    private TemporalOperand convertTimeOrDateTimeOperand(IOperation op, Object origin) {
        return isDateTimeValue(op) ? convertDateTimeOperand(op, origin) : convertTimeOperand(op, origin);
    }

    private NumberExpr dayFromDate(TemporalOperand source, Object origin) {
        if (source instanceof DateOperand date) return Dsl.dayFromDate(date);
        if (source instanceof DateTimeOperand dateTime) return Dsl.dayFromDate(dateTime);
        throw unsupported("DayFromDate over a non-date temporal operand", origin);
    }

    private NumberExpr monthFromDate(TemporalOperand source, Object origin) {
        if (source instanceof DateOperand date) return Dsl.monthFromDate(date);
        if (source instanceof DateTimeOperand dateTime) return Dsl.monthFromDate(dateTime);
        throw unsupported("MonthFromDate over a non-date temporal operand", origin);
    }

    private NumberExpr yearFromDate(TemporalOperand source, Object origin) {
        if (source instanceof DateOperand date) return Dsl.yearFromDate(date);
        if (source instanceof DateTimeOperand dateTime) return Dsl.yearFromDate(dateTime);
        throw unsupported("YearFromDate over a non-date temporal operand", origin);
    }

    private NumberExpr quarterFromDate(TemporalOperand source, Object origin) {
        if (source instanceof DateOperand date) return Dsl.quarterFromDate(date);
        if (source instanceof DateTimeOperand dateTime) return Dsl.quarterFromDate(dateTime);
        throw unsupported("QuarterFromDate over a non-date temporal operand", origin);
    }

    private NumberExpr hoursFromTime(TemporalOperand source, Object origin) {
        if (source instanceof TimeOperand time) return Dsl.hoursFromTime(time);
        if (source instanceof DateTimeOperand dateTime) return Dsl.hoursFromTime(dateTime);
        throw unsupported("HoursFromTime over a non-time temporal operand", origin);
    }

    private NumberExpr minutesFromTime(TemporalOperand source, Object origin) {
        if (source instanceof TimeOperand time) return Dsl.minutesFromTime(time);
        if (source instanceof DateTimeOperand dateTime) return Dsl.minutesFromTime(dateTime);
        throw unsupported("MinutesFromTime over a non-time temporal operand", origin);
    }

    private NumberExpr secondsFromTime(TemporalOperand source, Object origin) {
        if (source instanceof TimeOperand time) return Dsl.secondsFromTime(time);
        if (source instanceof DateTimeOperand dateTime) return Dsl.secondsFromTime(dateTime);
        throw unsupported("SecondsFromTime over a non-time temporal operand", origin);
    }

    /** The {@code DATE_RANGE} field source of {@code Start|EndOfDateRange} (a {@code BaseYear} point-in-time stays opaque). */
    private DateRangeRef dateRangeSource(IOperation op, Object origin) {
        if (op instanceof IFieldIdOperation field && field.getOperatorName() == FieldIdOperationName.FieldValue
                && model.fieldAt(field.getField().getFullName())
                        .map(i -> i.kind() == ValueKind.DATE_RANGE).orElse(false)) {
            DateRangeRef r = model.dateRange(field.getField().getFullName());
            return field.getField().isOuter() ? r.outer() : r;
        }
        throw unsupported("date-range source " + op.getClass().getSimpleName(), origin);
    }

    /** {@code AddDays/Months/Years(date, n)} (→ date). */
    private DateOperand convertDateAdd(IDateAddOperation add) {
        DateOperand base = convertDateOperand(add.getDateSpec(), add);
        NumberExpr amount = arithmeticOperand(add.getAmountSpec(), add);   // constant, field, or expression
        return switch (add.getOperatorName()) {
            case AddDays -> Dsl.addDays(base, amount);
            case AddMonths -> Dsl.addMonths(base, amount);
            case AddYears -> Dsl.addYears(base, amount);
            case AddHours, AddMinutes, AddSeconds ->
                throw unsupported(add.getOperatorName() + " over a date operand", add);
            default -> throw unsupported("date-add operator " + add.getOperatorName(), add);
        };
    }

    /** {@code AddDays/Hours/Minutes/Seconds(dateTime, n)} (→ date-time). */
    private DateTimeOperand convertDateTimeAdd(IDateAddOperation add) {
        DateTimeOperand base = convertDateTimeOperand(add.getDateSpec(), add);
        NumberExpr amount = arithmeticOperand(add.getAmountSpec(), add);
        return switch (add.getOperatorName()) {
            case AddDays -> Dsl.addDays(base, amount);
            case AddHours -> Dsl.addHours(base, amount);
            case AddMinutes -> Dsl.addMinutes(base, amount);
            case AddSeconds -> Dsl.addSeconds(base, amount);
            case AddMonths, AddYears ->
                throw unsupported(add.getOperatorName() + " over a date-time operand", add);
            default -> throw unsupported("date-time-add operator " + add.getOperatorName(), add);
        };
    }

    /**
     * True when an aggregate is <em>date-valued</em>: an order/first-filled aggregate
     * ({@code MaxValue}/{@code MinValue}/{@code FirstFilledValue}) over a single {@code DATE} field scope.
     * Such an aggregate compares as a date, not a number — {@link #convertDateAggregate} builds it.
     */
    private boolean isDateValuedAggregate(IEntityListOperation aggregate) {
        EntityListOperationName name = aggregate.getOperatorName();
        if (name != EntityListOperationName.MaxValue && name != EntityListOperationName.MinValue
                && name != EntityListOperationName.FirstFilledValue) {
            return false;
        }
        List<IEntityId> entities = aggregate.getEntityList().getEntityIdList();
        if (name == EntityListOperationName.FirstFilledValue) {
            return !entities.isEmpty() && entities.stream().allMatch(entity -> entity.isField()
                    && model.fieldAt(entity.getFullName()).map(i -> i.kind() == ValueKind.DATE).orElse(false));
        }
        return entities.size() == 1 && entities.getFirst().isField()
                && model.fieldAt(entities.getFirst().getFullName()).map(i -> i.kind() == ValueKind.DATE).orElse(false);
    }

    /**
     * The value kind of a {@code MaxValue}/{@code MinValue} <em>extremum</em> over a single DATE/TIME/DATETIME
     * field, or {@code null} when the aggregate is not a temporal extremum (a numeric aggregate, a
     * {@code FirstFilledValue}, or a multi-field scope). The kernel folds all three date-like kinds through one
     * {@code DateCombiner} (SPEC-2026-07-21-07), so the extremum lowers to the matching temporal operand.
     */
    private ValueKind temporalExtremumKind(IEntityListOperation aggregate) {
        EntityListOperationName name = aggregate.getOperatorName();
        if (name != EntityListOperationName.MaxValue && name != EntityListOperationName.MinValue) {
            return null;
        }
        List<IEntityId> entities = aggregate.getEntityList().getEntityIdList();
        if (entities.size() != 1 || !entities.getFirst().isField()) {
            return null;
        }
        return model.fieldAt(entities.getFirst().getFullName())
                .map(i -> i.kind())
                .filter(k -> k == ValueKind.DATE || k == ValueKind.TIME || k == ValueKind.DATE_TIME)
                .orElse(null);
    }

    /** The kind-agnostic {@code MaxValue}/{@code MinValue} extremum node; the caller wraps it in the matching
     *  temporal operand expression ({@link DateExpr}/{@link TimeExpr}/{@link DateTimeExpr}). */
    private DateListAggregate temporalListAggregate(IEntityListOperation aggregate) {
        FieldListRef scope = flattenScope(aggregate.getEntityList().getEntityIdList().getFirst());
        return switch (aggregate.getOperatorName()) {
            case MaxValue -> new DateListAggregate(DateListAggregate.Mode.LATEST, scope);
            case MinValue -> new DateListAggregate(DateListAggregate.Mode.EARLIEST, scope);
            default -> throw unsupported("temporal extremum operator " + aggregate.getOperatorName(), aggregate);
        };
    }

    /** {@code MaxValue}/{@code MinValue}/{@code FirstFilledValue} over a DATE repetition list, as a date operand
     *  (the date variant of the numeric aggregate; the TIME/DATETIME extremum siblings use
     *  {@link #temporalListAggregate} wrapped in a {@link TimeExpr}/{@link DateTimeExpr}). */
    private DateOperand convertDateAggregate(IEntityListOperation aggregate) {
        if (aggregate.getOperatorName() == EntityListOperationName.FirstFilledValue) {
            return new DateExpr(convertFirstFilledValue(aggregate, ValueKind.DATE));
        }
        return new DateExpr(temporalListAggregate(aggregate));
    }

    /**
     * Convert a computation's value-expression (the kernel's {@link IOperation}) to a rulekit
     * {@link Calculation}, reusing the comparison-operand machinery ({@link #numericExpr}). rulekit's
     * computation surface is numeric, so a non-NUMBER computed field, or an operation {@code numericExpr}
     * doesn't recognise, raises the strict signal (→ a tolerant {@link ReadComputation.Opaque}).
     */
    Calculation convertOperation(IOperation op, ValueKind computedKind) {
        // The kernel wraps each computation alternative's value-expression with its assignment operator
        // (CalcOperationWithCompOp). getOriginalOperation() unwraps it to the bare value-expression
        // (and strips any auto-added rounding — exactness is enforced in convertComputation).
        if (op instanceof IWithComparisonOperation withOp) {
            return convertOperation(withOp.getOriginalOperation(), computedKind);
        }
        // A constant assignment of any kind — number/string/date literal, or a boolean True/False
        // (the common metamodel shape: a default value computed into a string/boolean field).
        if (op instanceof IConstantOperation || op instanceof IBoolConstantOperation) {
            return constantOperand(op, op);
        }
        // A plain field value of any kind ([OtherField]) → the field's value as a Calculation.
        if (op instanceof IFieldIdOperation fv && fv.getOperatorName() == FieldIdOperationName.FieldValue) {
            return new FieldValue(model.field(fv.getField().getFullName()), fv.getField().isOuter());
        }
        // A value-expression, dispatched by the computed field's kind — reusing the same operand
        // converters the comparison side uses (arithmetic/concat, aggregates, coercions, date math, …).
        if (computedKind == ValueKind.STRING) {
            return stringOperand(op, op).expression();
        }
        if (computedKind == ValueKind.NUMBER) {
            NumberExpr expr = numericExpr(op);
            if (expr != null) {
                return expr.expression();
            }
        }
        if (computedKind == ValueKind.DATE && convertDateOperand(op, op) instanceof DateExpr date) {
            return date.expression();
        }
        // SomeRange = DateRange(StartDate, EndDate) — the date-range constructor (computation-only)
        if (computedKind == ValueKind.DATE_RANGE && op instanceof IDateRangeConstructionOperation range) {
            return Dsl.dateRange(model.date(range.getDate1Id().getFullName()),
                    model.date(range.getDate2Id().getFullName()));
        }
        if (computedKind == ValueKind.TIME && convertTimeOperand(op, op) instanceof TimeExpr time) {
            return time.expression();   // e.g. SomeTime = TimeFromDateTime(dt)
        }
        // an enum-valued FirstFilledValue over an enum repetition list (SomeEnum = FirstFilledValue(items*/Cat))
        if (computedKind == ValueKind.ENUMERATION && op instanceof IEntityListOperation enumAgg
                && enumAgg.getOperatorName() == EntityListOperationName.FirstFilledValue) {
            return convertFirstFilledValue(enumAgg, ValueKind.ENUMERATION);
        }
        throw unsupported("computation operation " + op.getClass().getSimpleName()
                + " into a " + computedKind + " field", op);
    }

    /** Preserve the comparison wrapper as computation metadata before conversion unwraps its value. */
    private static ToleranceBand toleranceBand(IOperation operation) {
        if (!(operation instanceof IWithComparisonOperation wrapped)
                || wrapped.getComparisonOperation() == null) {
            return null;
        }
        return switch (wrapped.getComparisonOperation()) {
            case NE1 -> ToleranceBand.TR1;
            case NE2 -> ToleranceBand.TR2;
            case NE5 -> ToleranceBand.TR5;
            case NE10 -> ToleranceBand.TR10;
            default -> null;
        };
    }

    /**
     * Assemble a rulekit {@link Computation} from the kernel's extracted {@link ICalculationInfo}
     * (calculated field + common precondition + {@code (precondition, operation)} alternatives) plus the
     * computation's recovered error {@code messages}. Non-{@code EXACT} rounding is unmodeled — rulekit
     * authors exact computations only — so it raises the strict signal (→ {@link ReadComputation.Opaque}).
     */
    Computation convertComputation(ICalculationInfo info, List<LocalizedText> messages,
            List<String> errorCodesToSuppress) {
        if (info.getRoundingMode() != RoundingMode.EXACT) {
            throw unsupported("computation with non-EXACT rounding (" + info.getRoundingMode()
                    + ") — rulekit models exact computations only", info.getCalculatedField());
        }
        String computedPath = info.getCalculatedField().getFullName();
        FieldRef computed = model.field(computedPath);
        ValueKind kind = computed.kind();
        Optional<Condition> common = info.getCommonPrecondition().map(this::convert);
        List<Computation.Alternative> alternatives = new ArrayList<>();
        for (Pair<ICondition, IOperation> partial : info.getPartialCalculations()) {
            ICondition pre = partial.getLeft();
            Optional<Condition> precondition = pre == null ? Optional.empty() : Optional.of(convert(pre));
            IOperation operation = partial.getRight();
            alternatives.add(new Computation.Alternative(
                    precondition,
                    convertOperation(operation, kind),
                    Optional.ofNullable(toleranceBand(operation))));
        }
        String groupPath = computedPath.substring(0, computedPath.lastIndexOf('/'));
        // info.getName() is the computation's full path; Computation.name is the short (last-segment) name
        String name = info.getName().substring(info.getName().lastIndexOf('/') + 1);
        // a read computation carries no comments on the AST (concrete syntax stays in the stored slot
        // texts); authoring/preservation populate the side-channel, not the converter.
        return new Computation(groupPath, name, computed, common, alternatives, messages, Comments.none(),
                errorCodesToSuppress);
    }

    private static UnsupportedConstructException unsupported(String what, Object node) {
        String kernelType = node.getClass().getInterfaces().length > 0
                ? node.getClass().getInterfaces()[0].getSimpleName()
                : node.getClass().getSimpleName();
        return new UnsupportedConstructException("F10 converter does not yet handle " + what
                + " [kernel node: " + kernelType + "]");
    }
}
