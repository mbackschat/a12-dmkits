package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;

/**
 * The static-import facade (proposal §10, jOOQ-style): free-standing factories for composing
 * conditions, made for reusable combinators — {@code gate.and(fieldNotFilled(field))} instead of a
 * handle-method chain. Every factory takes <em>model-resolved handles</em> ({@link FieldRef},
 * {@link StringRef}, …), never string paths: model-awareness stays mandatory (§5a) because handles
 * only come from {@link BoundModel}. Grows with the operator catalog.
 */
public final class Dsl {

    private Dsl() {
    }

    public static Condition fieldFilled(FieldRef field) {
        return new FieldPresenceCondition(field, true);
    }

    public static Condition fieldNotFilled(FieldRef field) {
        return new FieldPresenceCondition(field, false);
    }

    /** {@code Length(field)} as a comparable numeric expression. */
    public static NumberExpr length(StringRef field) {
        return field.length();
    }

    /** {@code [field] PatternMatched "regex"} — anchored whole-value match. */
    public static Condition patternMatched(StringRef field, String regex) {
        return field.matches(regex);
    }

    /** {@code [field] PatternViolated "regex"} — anchored whole-value non-match. */
    public static Condition patternViolated(StringRef field, String regex) {
        return field.violates(regex);
    }

    // --- filled-count aggregates — comparable numeric expressions ------------------------------

    /**
     * {@code NumberOfFilledFields(f1, f2, …)} — ≥2 fields by signature; kinds may be mixed.
     * Operands may be flattened repetition lists ({@link BoundModel#flatten}).
     */
    public static NumberExpr numberOfFilledFields(FieldOperand first, FieldOperand second, FieldOperand... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first);
        fields.add(second);
        fields.addAll(java.util.List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS, fields, java.util.List.of()));
    }

    /**
     * {@code NumberOfFilledFields(scope)} over a single flattened repetition list — counts the filled
     * fields across one wildcard scope (e.g. the flatten of {@code IndexFields*} then {@code indexField}).
     * The single-list form is valid on its own (the param law exempts a repetition list, verified — see
     * {@link CountAggregate}).
     */
    public static NumberExpr numberOfFilledFields(FieldListRef scope) {
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS,
                java.util.List.of(scope), java.util.List.of()));
    }

    /** {@code NumberOfFilledFields(group, …)} — counts the filled fields inside the group(s). */
    public static NumberExpr numberOfFilledFields(GroupRef first, GroupRef... rest) {
        var groups = new java.util.ArrayList<GroupRef>();
        groups.add(first);
        groups.addAll(java.util.List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS, java.util.List.of(), groups));
    }

    /** {@code Sum(…)} over number fields/repetition lists — unspecified fields count as 0 (§9). */
    public static NumberExpr sum(FieldOperand first, FieldOperand... rest) {
        return listAggregate(ListAggregate.AggOp.SUM, first, rest);
    }

    /** {@code MaxValue(…)} over number fields/repetition lists — unspecified fields are ignored. */
    public static NumberExpr maxValue(FieldOperand first, FieldOperand... rest) {
        return listAggregate(ListAggregate.AggOp.MAX_VALUE, first, rest);
    }

    /** {@code MinValue(…)} over number fields/repetition lists — unspecified fields are ignored. */
    public static NumberExpr minValue(FieldOperand first, FieldOperand... rest) {
        return listAggregate(ListAggregate.AggOp.MIN_VALUE, first, rest);
    }

    private static NumberExpr listAggregate(ListAggregate.AggOp op, FieldOperand first, FieldOperand... rest) {
        var operands = new java.util.ArrayList<FieldOperand>();
        operands.add(first);
        operands.addAll(java.util.List.of(rest));
        return new NumberExpr(new ListAggregate(op, operands));
    }

    // --- date family (item 9) — Today, differences (→ number), shifts (→ date) ----------------

    /** {@code Today} — the current date constant (DE {@code Heute}). */
    public static DateExpr today() {
        return new DateExpr(new Today());
    }

    /** {@code DifferenceInDays(a, b)} — a numeric date difference (a floor, not a calendar count). */
    public static NumberExpr differenceInDays(DateOperand a, DateOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.DAYS, a.expression(), b.expression()));
    }

    public static NumberExpr differenceInMonths(DateOperand a, DateOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.MONTHS, a.expression(), b.expression()));
    }

    public static NumberExpr differenceInYears(DateOperand a, DateOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.YEARS, a.expression(), b.expression()));
    }

    /** {@code AddDays(date, n)} — shifts a date, yielding a date (comparable). */
    public static DateExpr addDays(DateOperand date, long amount) {
        return new DateExpr(new DateShift(DateShift.Unit.DAYS, date.expression(), amount));
    }

    public static DateExpr addMonths(DateOperand date, long amount) {
        return new DateExpr(new DateShift(DateShift.Unit.MONTHS, date.expression(), amount));
    }

    public static DateExpr addYears(DateOperand date, long amount) {
        return new DateExpr(new DateShift(DateShift.Unit.YEARS, date.expression(), amount));
    }

    /** {@code CustomCondition <Name>} — the app-defined escape hatch (KERNEL-SEMANTICS §14). */
    public static Condition customCondition(String name) {
        return new CustomConditionRef(name);
    }

    /** {@code CurrentRepetition(group)} — the group's current iteration index, as a number expression. */
    public static NumberExpr currentRepetition(GroupRef group) {
        return new NumberExpr(new CurrentRepetition(group));
    }

    /** {@code NumberOfDifferentValues(…)} — distinct-value count over type-homogeneous operands. */
    public static NumberExpr numberOfDifferentValues(FieldOperand first, FieldOperand... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first);
        fields.addAll(java.util.List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.DIFFERENT_VALUES, fields, java.util.List.of()));
    }

    /**
     * {@code NumberOfFilledGroups(group*)} — counts the filled repetitions of one repeatable group (the
     * wildcard scope). The group must repeat; for distinct groups use the ≥2-group form.
     */
    public static NumberExpr numberOfFilledGroups(GroupRef repeatableGroup) {
        if (!repeatableGroup.repeating()) {
            throw new IllegalArgumentException("NumberOfFilledGroups over a single group needs a repeatable"
                    + " group (its wildcard scope): " + repeatableGroup.path());
        }
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_GROUPS,
                java.util.List.of(), java.util.List.of(repeatableGroup)));
    }

    /** {@code NumberOfFilledGroups(g1, g2, …)} — ≥2 groups by signature. */
    public static NumberExpr numberOfFilledGroups(GroupRef first, GroupRef second, GroupRef... rest) {
        var groups = new java.util.ArrayList<GroupRef>();
        groups.add(first);
        groups.add(second);
        groups.addAll(java.util.List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_GROUPS, java.util.List.of(), groups));
    }

    // --- field-list × value-list quantifiers — Name(f1, f2 In v1, v2) --------------------------
    // Typed entry points keep the field list type-homogeneous (MVK_VARYING_TYPES_NOT_ALLOWED
    // erased); the enum variants domain-check every value against every field (the kernel's
    // MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY pre-checked).

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, first, rest);
    }

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, first, rest);
    }

    private static Condition stringListQuantifier(
            ValueListQuantifier q, java.util.List<String> values, StringRef first, StringRef... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first.field());
        for (StringRef r : rest) {
            fields.add(r.field());
        }
        var literals = values.stream().<Calculation>map(StringLiteral::new).toList();
        return new FieldListValueListCondition(q, fields, literals);
    }

    private static Condition enumListQuantifier(
            ValueListQuantifier q, java.util.List<String> values, EnumRef first, EnumRef... rest) {
        var refs = new java.util.ArrayList<EnumRef>();
        refs.add(first);
        refs.addAll(java.util.List.of(rest));
        // every value must be valid for every listed enum field
        var literals = new java.util.ArrayList<Calculation>();
        for (String v : values) {
            for (EnumRef r : refs) {
                r.checkedLiteral(v);
            }
            literals.add(new EnumValueLiteral(v));
        }
        return new FieldListValueListCondition(q, refs.stream().<FieldOperand>map(EnumRef::field).toList(), literals);
    }

    // shape B over flattened repetition lists (string-kind fields; the corpus's dominant shape) —
    // enum-kind starred lists are deferred until FieldListRef carries the enum domain.

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<String> values, FieldListRef first, FieldListRef... rest) {
        return flattenedListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<String> values, FieldListRef first, FieldListRef... rest) {
        return flattenedListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<String> values, FieldListRef first, FieldListRef... rest) {
        return flattenedListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, first, rest);
    }

    private static Condition flattenedListQuantifier(
            ValueListQuantifier q, java.util.List<String> values, FieldListRef first, FieldListRef... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(checkedStringList(first));
        for (FieldListRef r : rest) {
            fields.add(checkedStringList(r));
        }
        var literals = values.stream().<Calculation>map(StringLiteral::new).toList();
        return new FieldListValueListCondition(q, fields, literals);
    }

    private static FieldListRef checkedStringList(FieldListRef ref) {
        if (ref.kind() != io.github.mbackschat.a12.dm.core.ValueKind.STRING) {
            throw new IllegalArgumentException("value-list quantifiers over repetition lists take"
                    + " string fields here; " + ref.renderedPath() + " is " + ref.kind()
                    + " (enum-kind lists are deferred — MVK_INVALID_TYPES_FOR_COMPARISON otherwise)");
        }
        return ref;
    }

    // shape C — the value list is itself a field list (entity-valued, not constants), e.g.
    // `Name(f1, f2 In g1, g2)` where g* are fields/repetition lists. Type compatibility is the kernel's.

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, valueFields, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, valueFields, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, valueFields, first, rest);
    }

    private static Condition entityListQuantifier(ValueListQuantifier q,
            java.util.List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first);
        fields.addAll(java.util.List.of(rest));
        return new FieldListEntityListCondition(q, fields, valueFields);
    }

    // --- field-list quantifiers — at least one field by signature ------------------------------

    public static Condition allFieldsFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.ALL_FIELDS_FILLED, first, rest);
    }

    public static Condition noFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.NO_FIELD_FILLED, first, rest);
    }

    public static Condition atLeastOneFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.AT_LEAST_ONE_FIELD_FILLED, first, rest);
    }

    public static Condition notAllFieldsFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.NOT_ALL_FIELDS_FILLED, first, rest);
    }

    public static Condition fieldsNotCollectivelyFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, first, rest);
    }

    public static Condition notExactlyOneFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED, first, rest);
    }

    /** {@code FieldValuesNotUnique(f1, f2, …)} — true when the listed values are not all distinct. */
    public static Condition fieldValuesNotUnique(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.FIELD_VALUES_NOT_UNIQUE, first, rest);
    }

    /**
     * {@code RepetitionNotUnique(key1, key2, …)} — duplicate-key detection over repetitions
     * (KERNEL-SEMANTICS §9). Scope with {@link RepetitionNotUniqueCondition#from from(group)}
     * ({@code @From}). Keys are plain {@link FieldRef}s by signature — groups and starred lists
     * are unrepresentable here.
     */
    public static RepetitionNotUniqueCondition repetitionNotUnique(FieldRef first, FieldRef... rest) {
        List<FieldRef> keys = new ArrayList<>();
        keys.add(first);
        keys.addAll(List.of(rest));
        return new RepetitionNotUniqueCondition(keys, java.util.Optional.empty());
    }

    private static Condition fieldList(Quantifier quantifier, FieldOperand first, FieldOperand... rest) {
        List<FieldOperand> fields = new ArrayList<>();
        fields.add(first);
        fields.addAll(List.of(rest));
        return fieldList(quantifier, fields);
    }

    /** Shared construction path — also used by {@link BoundModel}'s string-path convenience forms. */
    static Condition fieldList(Quantifier quantifier, List<? extends FieldOperand> fields) {
        return new FieldListPredicate(quantifier, List.copyOf(fields));
    }

    // --- group-scoped field-fill — the fields inside a group scope (optionally across all reps) ----

    /** {@code AllFieldsFilled(Group*)} — all fields inside the group scope(s) are filled. */
    public static Condition allFieldsFilled(GroupListRef first, GroupListRef... rest) {
        return groupScopedFieldFill(Quantifier.ALL_FIELDS_FILLED, first, rest);
    }

    /** {@code NoFieldFilled(Group*)} — no field inside the group scope(s) is filled. */
    public static Condition noFieldFilled(GroupListRef first, GroupListRef... rest) {
        return groupScopedFieldFill(Quantifier.NO_FIELD_FILLED, first, rest);
    }

    /** {@code AtLeastOneFieldFilled(Group*)} — at least one field inside the group scope(s) is filled. */
    public static Condition atLeastOneFieldFilled(GroupListRef first, GroupListRef... rest) {
        return groupScopedFieldFill(Quantifier.AT_LEAST_ONE_FIELD_FILLED, first, rest);
    }

    private static Condition groupScopedFieldFill(Quantifier q, GroupListRef first, GroupListRef... rest) {
        var scopes = new java.util.ArrayList<GroupListRef>();
        scopes.add(first);
        scopes.addAll(java.util.List.of(rest));
        return new GroupScopedFieldFill(q, scopes);
    }

    // --- localized error-message texts (F2a) ----------------------------------------------------
    // A model may declare ANY locales (header `locales`) — en/de are mere sugar for the common
    // codes; text(...) covers the rest. Builders check messages against the model's declared
    // locales at build() (every declared locale covered; no undeclared ones used).

    /** A message text for an arbitrary declared locale code, e.g. {@code text("fr_FR", "…")}. */
    public static LocalizedText text(String locale, String text) {
        return new LocalizedText(locale, text);
    }

    /** Sugar for the common {@code en_US} — only valid if the model declares it. */
    public static LocalizedText en(String text) {
        return new LocalizedText("en_US", text);
    }

    /** Sugar for the common {@code de_DE} — only valid if the model declares it. */
    public static LocalizedText de(String text) {
        return new LocalizedText("de_DE", text);
    }

    // --- n-ary composition (study finding 10) ---------------------------------------------------

    /** And-fold: {@code allOf(a, b, c)} renders {@code a And b And c} (no redundant parentheses). */
    public static Condition allOf(Condition first, Condition second, Condition... rest) {
        Condition folded = first.and(second);
        for (Condition c : rest) {
            folded = folded.and(c);
        }
        return folded;
    }

    /** Or-fold: {@code anyOf(a, b, c)} renders {@code a Or b Or c}; parenthesized when mixed with And. */
    public static Condition anyOf(Condition first, Condition second, Condition... rest) {
        Condition folded = first.or(second);
        for (Condition c : rest) {
            folded = folded.or(c);
        }
        return folded;
    }

    // --- group predicates (the group twin of the field forms) ----------------------------------

    public static Condition groupFilled(GroupRef group) {
        return new GroupPresenceCondition(group, true);
    }

    public static Condition groupNotFilled(GroupRef group) {
        return new GroupPresenceCondition(group, false);
    }

    public static Condition allGroupsFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.ALL_GROUPS_FILLED, first, rest);
    }

    public static Condition noGroupFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.NO_GROUP_FILLED, first, rest);
    }

    public static Condition atLeastOneGroupFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED, first, rest);
    }

    public static Condition notAllGroupsFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.NOT_ALL_GROUPS_FILLED, first, rest);
    }

    public static Condition groupsNotCollectivelyFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.GROUPS_NOT_COLLECTIVELY_FILLED, first, rest);
    }

    private static Condition groupList(GroupQuantifier quantifier, GroupRef first, GroupRef... rest) {
        List<GroupRef> groups = new ArrayList<>();
        groups.add(first);
        groups.addAll(List.of(rest));
        return new GroupListPredicate(quantifier, groups);
    }
}
