package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code ValueAsDate(field, FirstDay|LastDay)} — resolves a <em>partially known date</em> field (a
 * {@code DateType} with a date precision other than {@code FULL}) to a concrete date: the earliest
 * ({@code FirstDay}) or latest ({@code LastDay}) real date matching the known parts (the kernel
 * {@code FieldIdOperationName.ValueAsDate}, an {@code IFieldIdOperationWithParameters} whose
 * {@code isFirstDay()} carries the day specification). A {@link Calculation} of {@link ValueKind#DATE};
 * wrap in a {@link DateExpr} to compare it. The {@link Source} renders bare, a keyed cell as
 * {@code Field For key}; the day specification is a
 * localized keyword ({@code FirstDay}/{@code LastDay}; DE {@code ErsterTag}/{@code LetzterTag}).
 */
public record ValueAsDate(Source source, boolean firstDay) implements Calculation {

    /**
     * The one partially known date it resolves: a field, or the cell a semantic index selects, which A12 admits in
     * the same position (`validate.laws.KeyedTemporalOperandLawsTest`).
     */
    public sealed interface Source permits FieldRef, IndexedFieldValue {
    }

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
