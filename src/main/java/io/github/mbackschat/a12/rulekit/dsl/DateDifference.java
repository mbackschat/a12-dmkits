package io.github.mbackschat.a12.rulekit.dsl;

import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * {@code DifferenceInDays|Months|Years(d1, d2)} — a numeric date difference (§6: a floor, not a
 * calendar count). Operands are date expressions, rendered BARE (entity refs, not bracketed values).
 */
public record DateDifference(Unit unit, Calculation left, Calculation right) implements Calculation {

    public enum Unit {
        DAYS("DifferenceInDays"),
        MONTHS("DifferenceInMonths"),
        YEARS("DifferenceInYears");

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
