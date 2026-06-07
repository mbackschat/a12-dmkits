package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * The field-list × value-list quantifiers — {@code Name(f1, f2 In v1, v2)} shapes (the kernel's
 * {@code feldListeUndWerteListenBedingung}). Each maps to its catalog operator id.
 */
public enum ValueListQuantifier {
    AT_LEAST_ONE_FIELD_VALUE_INCLUDED("AtLeastOneFieldValueIncludedInValueList"),
    NO_FIELD_VALUE_INCLUDED("NoFieldValueIncludedInValueList"),
    NOT_ALL_FIELD_VALUES_INCLUDED("NotAllFieldValuesIncludedInValueList"),
    AT_LEAST_ONE_DATE_RANGE_OVERLAPS("AtLeastOneDateRangeOverlaps");

    private final String operatorId;

    ValueListQuantifier(String operatorId) {
        this.operatorId = operatorId;
    }

    public String operatorId() {
        return operatorId;
    }
}
