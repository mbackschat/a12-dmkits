package io.github.mbackschat.a12.rulekit.dsl;

import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * {@code AddDays|Months|Years(date, n)} — shifts a date by an integer amount, yielding a date (§6:
 * {@code AddMonths} preserves the day number, {@code AddYears} maps end-of-February; fractional
 * offsets truncate). The date operand renders BARE (entity ref).
 */
public record DateShift(Unit unit, Calculation date, long amount) implements Calculation {

    public enum Unit {
        DAYS("AddDays"),
        MONTHS("AddMonths"),
        YEARS("AddYears");

        private final String operatorId;

        Unit(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    public String operatorId() {
        return unit.operatorId();
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATE;
    }
}
