package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code AddDays|Months|Years(date, n)} (→ a date) and {@code AddHours|Minutes|Seconds(dateTime, n)}
 * (→ a date-time) — shifts a temporal value by an amount (§6: {@code AddMonths} preserves the day
 * number, {@code AddYears} maps end-of-February; fractional offsets truncate). The {@code amount} is
 * usually an integer constant ({@link NumberLiteral}) but may be any numeric expression (a field value,
 * an arithmetic), so {@code AddYears(date, [n])} is expressible. The base operand renders BARE (entity
 * ref); the amount renders as a value.
 */
public record DateShift(Unit unit, Calculation date, Calculation amount) implements Calculation {

    /** Each shift carries its catalog operator-id and result kind (day/month/year → DATE; sub-day → DATE_TIME). */
    public enum Unit {
        DAYS("AddDays", ValueKind.DATE),
        MONTHS("AddMonths", ValueKind.DATE),
        YEARS("AddYears", ValueKind.DATE),
        HOURS("AddHours", ValueKind.DATE_TIME),
        MINUTES("AddMinutes", ValueKind.DATE_TIME),
        SECONDS("AddSeconds", ValueKind.DATE_TIME);

        private final String operatorId;
        private final ValueKind kind;

        Unit(String operatorId, ValueKind kind) {
            this.operatorId = operatorId;
            this.kind = kind;
        }

        public String operatorId() {
            return operatorId;
        }

        public ValueKind kind() {
            return kind;
        }
    }

    public String operatorId() {
        return unit.operatorId();
    }

    @Override
    public ValueKind kind() {
        return unit.kind();
    }
}
