package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code DifferenceInDays|Months|Years(d1, d2)} (over dates) and {@code DifferenceInHours|Minutes|Seconds(dt1, dt2)}
 * (over date-times) — a numeric difference (§6: a floor, not a calendar count). Operands are temporal
 * expressions, rendered BARE (entity refs, not bracketed values).
 */
public record DateDifference(Unit unit, Calculation left, Calculation right) implements Calculation {

    public enum Unit {
        DAYS("DifferenceInDays"),
        MONTHS("DifferenceInMonths"),
        YEARS("DifferenceInYears"),
        HOURS("DifferenceInHours"),
        MINUTES("DifferenceInMinutes"),
        SECONDS("DifferenceInSeconds");

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
        return ValueKind.NUMBER;
    }
}
