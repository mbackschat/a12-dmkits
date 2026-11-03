package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A numeric temporal difference. {@code DifferenceInDays} accepts dates, date-times, and mixed pairs and
 * counts complete days between the two model-zone wall clocks (KERNEL-FINDINGS KF665). Months/years accept dates only and count complete calendar
 * periods. Hours/minutes/seconds accept date-times only and divide elapsed instants. Entity refs render bare.
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
