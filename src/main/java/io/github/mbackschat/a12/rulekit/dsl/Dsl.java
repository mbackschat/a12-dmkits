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

    // --- field-list quantifiers — at least one field by signature ------------------------------

    public static Condition allFieldsFilled(FieldRef first, FieldRef... rest) {
        return fieldList(Quantifier.ALL_FIELDS_FILLED, first, rest);
    }

    public static Condition noFieldFilled(FieldRef first, FieldRef... rest) {
        return fieldList(Quantifier.NO_FIELD_FILLED, first, rest);
    }

    public static Condition atLeastOneFieldFilled(FieldRef first, FieldRef... rest) {
        return fieldList(Quantifier.AT_LEAST_ONE_FIELD_FILLED, first, rest);
    }

    public static Condition notAllFieldsFilled(FieldRef first, FieldRef... rest) {
        return fieldList(Quantifier.NOT_ALL_FIELDS_FILLED, first, rest);
    }

    public static Condition fieldsNotCollectivelyFilled(FieldRef first, FieldRef... rest) {
        return fieldList(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, first, rest);
    }

    public static Condition notExactlyOneFieldFilled(FieldRef first, FieldRef... rest) {
        return fieldList(Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED, first, rest);
    }

    private static Condition fieldList(Quantifier quantifier, FieldRef first, FieldRef... rest) {
        List<FieldRef> fields = new ArrayList<>();
        fields.add(first);
        fields.addAll(List.of(rest));
        return fieldList(quantifier, fields);
    }

    /** Shared construction path — also used by {@link BoundModel}'s string-path convenience forms. */
    static Condition fieldList(Quantifier quantifier, List<FieldRef> fields) {
        return new FieldListPredicate(quantifier, fields);
    }
}
