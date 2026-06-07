package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code ValueAsDate(field, FirstDay|LastDay)} — resolves a <em>partially known date</em> field (a
 * {@code DateType} with a date precision other than {@code FULL}) to a concrete date: the earliest
 * ({@code FirstDay}) or latest ({@code LastDay}) real date matching the known parts (the kernel
 * {@code FieldIdOperationName.ValueAsDate}, an {@code IFieldIdOperationWithParameters} whose
 * {@code isFirstDay()} carries the day specification). A {@link Calculation} of {@link ValueKind#DATE};
 * wrap in a {@link DateExpr} to compare it. The field renders bare; the day specification is a
 * localized keyword ({@code FirstDay}/{@code LastDay}; DE {@code ErsterTag}/{@code LetzterTag}).
 */
public record ValueAsDate(FieldRef field, boolean firstDay) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.DATE;
    }

    public String operatorId() {
        return "ValueAsDate";
    }

    /** The catalog operator-id of the day specification keyword — {@code FirstDay} or {@code LastDay}. */
    public String dayKeywordId() {
        return firstDay ? "FirstDay" : "LastDay";
    }
}
