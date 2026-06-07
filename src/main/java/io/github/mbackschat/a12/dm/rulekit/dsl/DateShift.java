package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code AddDays|Months|Years(date, n)} — shifts a date by an amount, yielding a date (§6:
 * {@code AddMonths} preserves the day number, {@code AddYears} maps end-of-February; fractional
 * offsets truncate). The {@code amount} is usually an integer constant ({@link NumberLiteral}) but
 * may be any numeric expression (a field value, an arithmetic), so {@code AddYears(date, [n])} is
 * expressible. The date operand renders BARE (entity ref); the amount renders as a value.
 */
public record DateShift(Unit unit, Calculation date, Calculation amount) implements Calculation {

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
