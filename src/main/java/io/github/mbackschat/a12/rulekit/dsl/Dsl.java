package io.github.mbackschat.a12.rulekit.dsl;

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

    /** {@code NumberOfFilledFields(group, …)} — counts the filled fields inside the group(s). */
    public static NumberExpr numberOfFilledFields(GroupRef first, GroupRef... rest) {
        var groups = new java.util.ArrayList<GroupRef>();
        groups.add(first);
        groups.addAll(java.util.List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS, java.util.List.of(), groups));
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
        if (ref.kind() != io.github.mbackschat.a12.rulekit.model.ValueKind.STRING) {
            throw new IllegalArgumentException("value-list quantifiers over repetition lists take"
                    + " string fields here; " + ref.renderedPath() + " is " + ref.kind()
                    + " (enum-kind lists are deferred — MVK_INVALID_TYPES_FOR_COMPARISON otherwise)");
        }
        return ref;
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
