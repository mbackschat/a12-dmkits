package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.predicates.ReferenceReachPredicatesKt;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The field and group paths a {@link Condition} references — collected by exhaustive switch over
 * the sealed AST (cheap thanks to D14's model-resolved refs). This is the seed of F9's
 * referenced-fields projection; today it powers the {@link Rule} builder's error-field-referenced
 * pre-check (directly, or indirectly via an enclosing group reference — kernel-verified semantics).
 *
 * <p>{@link #readMode(Condition, String)} answers a second question over the same traversal: <em>how</em> one
 * path is read — plainly, or through a semantic index or enum category. The reading mode is deliberately
 * <b>not</b> part of this record's identity, because a projection changes the operation and not which
 * entities the condition depends on: {@code ConditionEdit} classifies an {@code [A]} → {@code [A -> Cat]}
 * swap as an OPERAND edit (same node count, same references, and its operator multiset covers only condition
 * nodes), and folding the mode into this record's equality would relabel it a REFERENCES edit
 * ({@code ConditionEditTest.swappingAPlainReadForACategoryProjectionIsNotAReferenceEdit}).
 */
public record ConditionReferences(Set<String> fieldPaths, Set<String> groupPaths) {

    public ConditionReferences {
        fieldPaths = immutableEncounterOrder(fieldPaths);
        groupPaths = immutableEncounterOrder(groupPaths);
    }

    private static Set<String> immutableEncounterOrder(Set<String> paths) {
        Objects.requireNonNull(paths, "paths");
        Set<String> copy = new LinkedHashSet<>(paths.size());
        paths.forEach(path -> copy.add(Objects.requireNonNull(path, "path")));
        return Collections.unmodifiableSet(copy);
    }

    public static ConditionReferences of(Condition condition) {
        Refs refs = new Refs();
        collect(condition, refs);
        return new ConditionReferences(refs.fields, refs.groups);
    }

    /**
     * The references A12 counts as the rule's OWN when it asks whether the error entity is referenced: every read
     * outside a {@code Having} filter's body, and a read inside one only when it carries the {@code $} marker. A12
     * keeps a reference made with the filter flag on only when it is marked, so a target read only in an unmarked
     * body draws {@code MVK_ERROR_FIELD_NOT_REFERENCED} (KERNEL-FINDINGS KF488, {@code dsl.FilterBodyErrorReferenceTest}).
     */
    public static ConditionReferences rulesOwn(Condition condition) {
        Refs refs = new Refs();
        collect(condition, refs);
        return new ConditionReferences(refs.ownFields, refs.ownGroups);
    }

    /** The references of a value expression — e.g. a computation slot's {@code operation}. */
    public static ConditionReferences of(Calculation calculation) {
        Refs refs = new Refs();
        collect(calculation, refs);
        return new ConditionReferences(refs.fields, refs.groups);
    }

    /**
     * How a field is read: not at all, plainly, or through a <b>projection</b> — an enum category
     * ({@code [f -> Cat]}) or a semantic index ({@code [f For key]}, whose <em>key</em> field counts as
     * projected too, as the kernel matches it against the calculated field).
     *
     * <p>Ordered weakest-first, so {@code compareTo} folds several readings into the strongest one. That
     * ordering is the kernel's: where a computation touches its own computed field, a plain self-read is
     * {@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD}, a projected one is
     * {@code MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD}, and the projected class pre-empts the
     * plain one wherever both readings occur in the computation (kernel-measured, KERNEL-FINDINGS KF182).
     */
    public enum ReadMode {
        NONE, PLAIN, PROJECTED
    }

    /** How {@code fieldPath} is read by {@code condition} — one traversal, no intermediate collections. */
    public static ReadMode readMode(Condition condition, String fieldPath) {
        Refs refs = new Refs();
        collect(condition, refs);
        return refs.readModeOf(fieldPath);
    }

    /** How {@code fieldPath} is read by a value expression — the {@link #of(Calculation)} peer. */
    public static ReadMode readMode(Calculation calculation, String fieldPath) {
        Refs refs = new Refs();
        collect(calculation, refs);
        return refs.readModeOf(fieldPath);
    }

    /**
     * The channels one traversal fills. {@code projected} is a subset of {@code fields}: a projected read
     * is still a reference to its underlying field. {@code ownFields}/{@code ownGroups} are the subsets
     * {@link #rulesOwn} answers with: a read inside a filter body joins them only when it is marked.
     */
    private static final class Refs {
        private final Set<String> fields = new LinkedHashSet<>();
        private final Set<String> groups = new LinkedHashSet<>();
        private final Set<String> projected = new LinkedHashSet<>();
        private final Set<String> ownFields = new LinkedHashSet<>();
        private final Set<String> ownGroups = new LinkedHashSet<>();
        private boolean inFilterBody;

        void field(FieldRef ref) {
            field(ref.path().value(), ref.correlated());
        }

        void field(String path, boolean marked) {
            fields.add(path);
            if (own(marked)) {
                ownFields.add(path);
            }
        }

        void group(String path, boolean marked) {
            groups.add(path);
            if (own(marked)) {
                ownGroups.add(path);
            }
        }

        /** A field read through a semantic index or an enum category — a reference AND a projection. */
        void projectedField(String path, boolean marked) {
            field(path, marked);
            projected.add(path);
        }

        private boolean own(boolean marked) {
            return !inFilterBody || marked;
        }

        void filterBody(Condition filter) {
            boolean enclosing = inFilterBody;
            inFilterBody = true;
            collect(filter, this);
            inFilterBody = enclosing;
        }

        ReadMode readModeOf(String fieldPath) {
            if (projected.contains(fieldPath)) {
                return ReadMode.PROJECTED;
            }
            return fields.contains(fieldPath) ? ReadMode.PLAIN : ReadMode.NONE;
        }
    }

    /**
     * Whether the condition references the entity at {@code path}, a field or a group: it names that entity or a
     * group enclosing it. A field inside a group target is not enough — with {@code Lines/Parts} as the error
     * entity, {@code FieldFilled(Lines/Parts/PartNo)} draws {@code MVK_ERROR_FIELD_NOT_REFERENCED} while
     * {@code GroupFilled(Lines)} is admitted (KERNEL-FINDINGS KF324 and KF601, {@code dsl.RuleGroupErrorEntityTest}).
     * The relation is the portable checker's too, so both consume the one statement of it in {@code :predicates}.
     */
    public boolean covers(String path) {
        return Stream.concat(fieldPaths.stream(), groupPaths.stream())
                .anyMatch(reference -> ReferenceReachPredicatesKt.referenceReaches(reference, path));
    }

    /**
     * Whether {@code fieldPath} is referenced as a <b>field</b> (exact) — <em>not</em> merely via its
     * containing group. The calculated-field-reference prohibition
     * ({@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD}) uses this rather than {@link #covers}: guarding a
     * repeatable computed field with {@code GroupFilled}/{@code CurrentRepetition} on its <em>containing
     * group</em> is kernel-legal (the BA doc's current-repetition pattern), so a group reference must not
     * count as referencing the field.
     */
    public boolean referencesField(String fieldPath) {
        return fieldPaths.contains(fieldPath);
    }

    /**
     * Whether the condition references the rule's OWN group via the {@code RuleGroup} self-reference
     * anywhere. Since the error entity, a field or a group, must lie under the rule's group, a
     * self-reference names a group enclosing it — so {@link Rule#deferredChecks()} treats this as
     * satisfying the error-entity-referenced check (the kernel does, per {@code DirectiveLawsTest} for a
     * field and {@code dsl.RuleGroupErrorEntityTest} for a group), avoiding a spurious
     * {@code MVK_ERROR_FIELD_NOT_REFERENCED}.
     */
    public static boolean referencesRuleGroup(Condition condition) {
        return switch (condition) {
            case RuleGroupPresenceCondition ignored -> true;
            case LogicCondition l -> referencesRuleGroup(l.left()) || referencesRuleGroup(l.right());
            default -> false;
        };
    }

    private static void collect(Condition condition, Refs refs) {
        switch (condition) {
            case FieldPresenceCondition f -> refs.field(f.field());
            case EntityListPredicate p -> p.operands().forEach(o -> collectAggregateOperand(o, refs));
            case ComparisonCondition c -> {
                collect(c.left(), refs);
                collect(c.right(), refs);
            }
            case LogicCondition l -> {
                collect(l.left(), refs);
                collect(l.right(), refs);
            }
            case GroupPresenceCondition g -> refs.group(g.group().path().value(), g.outer());
            // RuleGroup is the rule's OWN group; its path is unknown here (no rule context), so it
            // contributes no concrete reference. Rule.deferredChecks() treats it as covering the error
            // field via referencesRuleGroup(...) below (the field is always under the rule's group).
            case RuleGroupPresenceCondition ignored -> { }
            case IndexedPresenceCondition p -> collect(p.index(), refs);
            case IndexedGroupPresenceCondition p -> collectIndexedGroup(p.group(), refs);
            case GroupListPredicate p -> p.operands().forEach(operand -> {
                switch (operand) {
                    case FieldRef field -> refs.field(field);
                    case GroupListRef scope -> refs.group(scope.path(), scope.correlated());
                    case IndexedGroupValue indexed -> collectIndexedGroup(indexed, refs);
                    case IndexedFieldValue indexed -> collect(indexed, refs);
                }
            });
            case PatternCondition p -> collectOperand(p.field(), refs);
            case ValueListCondition v -> {
                collectOperand(v.field(), refs);
                v.values().forEach(value -> collect(value, refs));
            }
            case FieldListValueListCondition v -> {
                v.fields().forEach(f -> collectOperand(f, refs));
                v.values().forEach(value -> collect(value, refs));
            }
            case FieldListEntityListCondition v -> {
                v.fields().forEach(f -> collectOperand(f, refs));
                v.valueFields().forEach(f -> collectOperand(f, refs));
            }
            // The @From scope DOES count as a reference — measured 2026-07-25: with @From the kernel accepts an
            // error field that only the scope contains, and without it the same shape draws
            // MVK_ERROR_FIELD_NOT_REFERENCED, so the keys' own group is NOT an implicit reference
            // (validate.laws.RepetitionNotUniqueKeyKindLawsTest). Omitting the scope produced a false advisory
            // on a kernel-legal rule; this comment previously recorded the honoring as unverified.
            case RepetitionNotUniqueCondition r -> {
                r.keys().forEach(refs::field);
                r.scope().ifPresent(s -> refs.group(s.path().value(), false));
            }
            case CustomConditionRef ignored -> { /* references nothing — host-defined */ }
            case DateValidity v -> collect(v.dateConstruct(), refs);
            case CustomTypeValidity v -> refs.field(v.field());
        }
    }

    private static void collect(Calculation calculation, Refs refs) {
        switch (calculation) {
            case FieldValue v -> refs.field(v.field().path().value(), v.outer());
            case LengthOf l -> collectOperand(l.field(), refs);
            case NumberLiteral n -> { }
            case StringLiteral s -> { }
            case DateLiteral d -> { }
            case TimeLiteral t -> { }
            case DateTimeLiteral dt -> { }
            case EnumValueLiteral e -> { }
            case BoolLiteral b -> { }
            case EnumCategoryAccess c -> refs.projectedField(c.field().path().value(), c.outer());
            case CountAggregate a -> a.operands().forEach(o -> collectAggregateOperand(o, refs));
            case ListAggregate a -> a.operands().forEach(o -> collectAggregateOperand(o, refs));
            case CurrentRepetition r -> refs.group(r.group().path().value(), r.outer());
            case Today ignored -> { }
            case Now ignored -> { }
            case BaseYear ignored -> { }
            case DateDifference d -> {
                collect(d.left(), refs);
                collect(d.right(), refs);
            }
            case DateShift s -> {
                collect(s.date(), refs);
                collect(s.amount(), refs);
            }
            case SemanticIndexValue v -> refs.projectedField(v.field().path().value(), v.field().correlated());
            case SemanticIndexFieldValue v -> {
                refs.projectedField(v.field().path().value(), v.field().correlated());
                // The KEY counts as projected too — measured, not assumed: a computation whose operation reads
                // `[Legs/LegRef For <computedField>]` draws MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD,
                // not the plain class, so the kernel matches the calculated field against the key as well
                // (KF182; ComputedTargetProjectedReadLawsTest).
                refs.projectedField(v.indexField().path().value(), v.indexField().correlated());
            }
            case Arithmetic a -> {
                collect(a.left(), refs);
                collect(a.right(), refs);
            }
            case Rounding r -> collect(r.operand(), refs);
            case Concatenation c -> c.parts().forEach(p -> collect(p, refs));
            case DateListAggregate a -> a.operands().forEach(o -> collectAggregateOperand(o, refs));
            case FirstFilledValue a -> a.operands().forEach(o -> collectFirstFilledOperand(o, refs));
            case FieldValueAsString c -> refs.field(c.field());
            case IndexedFieldValueAsString c -> collect(c.index(), refs);
            case RangeAsString c -> refs.field(c.field());
            case FieldValueAsNumber c -> refs.field(c.field());
            case RangeAsNumber c -> refs.field(c.field());
            case OpListMath m -> m.operands().forEach(o -> collect(o, refs));
            case NumberOfValueInFields n -> {
                collect(n.value(), refs);
                n.fields().forEach(f -> collectAggregateOperand(f, refs));
            }
            case ValueAsDate v -> {
                switch (v.source()) {
                    case FieldRef field -> refs.field(field);
                    case IndexedFieldValue indexed -> collect(indexed, refs);
                }
            }
            case DateExtraction e -> collect(e.source(), refs);
            case DateTimePart e -> collect(e.source(), refs);
            case SumOfProducts s -> {
                collectOperand(s.left(), refs);
                collectOperand(s.right(), refs);
            }
            case DateConstruction dc -> dc.components().forEach(component -> collect(component, refs));
            case TimeConstruction tc -> {
                tc.components().forEach(component -> collect(component, refs));
            }
            case DateTimeConstruction dtc -> {
                collect(dtc.date(), refs);
                collect(dtc.time(), refs);
            }
            case FieldRounding r -> refs.field(r.field());
            case DateRangeBound b -> collect(b.source(), refs);
            case RootGroupCount c -> refs.group(c.rootGroup().path().value(), false);
            case DateRangeConstruction dr -> {
                collect(dr.start(), refs);
                collect(dr.end(), refs);
            }
        }
    }

    /** An entity-list aggregate operand: a group scope is a GROUP reference, exactly as the count
     *  aggregate's own group list already is; everything else is a field operand. */
    private static void collectAggregateOperand(AggregateOperand op, Refs refs) {
        switch (op) {
            case FieldOperand field -> collectOperand(field, refs);
            case GroupListRef group -> refs.group(group.path(), group.correlated());
            case IndexedGroupValue indexed -> collectIndexedGroup(indexed, refs);
        }
    }

    /**
     * An index-selected group still references the GROUP for this bookkeeping — the index only picks
     * which repetition, not what it references — plus, for the field-keyed form, the KEY field itself:
     * that field's value is what the condition depends on to pick the repetition, so it must count
     * toward {@code fieldPaths()}/{@code covers()} the same way {@code IndexedFieldValue}'s own key
     * already does for the direct-value path (KF182's principle, applied to the shape it does not by
     * itself reach — {@code IndexedGroupValue} is never a {@link Calculation}, so it never flows through
     * this class's OTHER exhaustive switch, the one with the dedicated {@code SemanticIndexValue}/
     * {@code SemanticIndexFieldValue} arms). Shared by both call sites (this one and {@code
     * GroupListPredicate}'s own entity-list collection) rather than repeating the key-field check twice.
     *
     * <p>Registered PLAIN, not projected: {@code ReadMode.PROJECTED} exists to feed self-reference
     * detection for a COMPUTED FIELD's own formula, which a group — never itself a {@link Calculation} —
     * can never participate in.
     */
    private static void collectIndexedGroup(IndexedGroupValue indexed, Refs refs) {
        refs.group(indexed.group().path(), indexed.group().correlated());
        if (indexed instanceof GroupSemanticIndexFieldValue keyed) {
            refs.field(keyed.indexField());
        }
    }

    /** A list operand contributes its field path (a category projection → its underlying enum field) plus —
     *  for a filtered list — its Having filter's refs. Accepts the wider {@link ValueListOperand} so an enum
     *  category projection in a value-list quantifier is collected too.
     *
     *  <p>An operand reached through a list slot is the same read as one reached directly, so the projected
     *  forms are enumerated here as well as in the value-expression switch. This chain is {@code instanceof}
     *  rather than a switch over the sealed type, so widening {@link FieldOperand} does not fail the build —
     *  a new arm has to be added by hand or it silently lands in the plain-field {@code else}. */
    private static void collectOperand(ValueListOperand op, Refs refs) {
        if (op instanceof FieldListRef list && list.semanticIndex().isPresent()) {
            collectFirstFilledOperand(list.firstFilledOperand(), refs);
            return;
        }
        if (op instanceof EnumCategoryAccess category) {
            refs.projectedField(op.fieldPath(), category.outer());
        } else if (op instanceof SemanticIndexValue indexed) {
            refs.projectedField(indexed.field().path().value(), indexed.field().correlated());
        } else if (op instanceof SemanticIndexFieldValue indexed) {
            // Both halves projected, exactly as the value-expression switch above reads them: the kernel
            // matches a calculated field against the KEY as well as against the read (KF182).
            refs.projectedField(indexed.field().path().value(), indexed.field().correlated());
            refs.projectedField(indexed.indexField().path().value(), indexed.indexField().correlated());
        } else if (op instanceof IndexedGroupValue indexed) {
            collectIndexedGroup(indexed, refs);
        } else if (op instanceof GroupListRef group) {
            // A GROUP scope registers as a GROUP, not as a field. Registering its path in `fieldPaths` would
            // typecheck and read plausibly, and it would make `covers` blind to every field the kernel expands
            // the group to — the exact reachability-vs-exactness confusion that produced CG13-CG18 one layer up.
            // `covers` already walks `groupPaths` for containment, so this arm gets that for free.
            refs.group(group.path(), group.correlated());
        } else if (op instanceof FieldListRef list) {
            refs.field(op.fieldPath(), list.correlated());
        } else {
            refs.field(op.fieldPath(), op instanceof FieldRef field && field.correlated());
        }
        if (op instanceof FieldListRef list) {
            list.filter().ifPresent(refs::filterBody);
        }
    }

    /**
     * A {@code FirstFilledValue} operand carries its category/semantic-index modifiers on the operand
     * itself rather than in the target node, so the projection is read from {@code operand} — a plain
     * {@link #collectOperand} of its field would otherwise report the read as plain.
     */
    private static void collectFirstFilledOperand(
            FirstFilledOperand operand, Refs refs) {
        boolean projected = operand.category().isPresent() || operand.semanticIndex().isPresent();
        operand.field().ifPresent(field -> {
            if (projected) {
                refs.projectedField(field.fieldPath(), operand.outerReference());
            }
            collectOperand(field, refs);
            if (operand.outerReference()) {
                // The operand keeps its marker on itself, not on the field it carries.
                refs.field(field.fieldPath(), true);
            }
        });
        operand.group().ifPresent(group -> {
            refs.group(group.path(), group.correlated() || operand.outerReference());
            operand.expandedGroupFields().forEach(field -> refs.field(field.path().value(), operand.outerReference()));
        });
        operand.semanticIndex().filter(FirstFilledOperand.FieldIndex.class::isInstance)
                .map(FirstFilledOperand.FieldIndex.class::cast)
                .ifPresent(index -> refs.projectedField(index.field().path().value(), index.field().correlated()));   // the key, as above
    }
}
