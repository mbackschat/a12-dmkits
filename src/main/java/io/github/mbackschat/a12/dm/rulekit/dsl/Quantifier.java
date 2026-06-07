package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * The field-list quantifier predicates — predicates over a list of fields (proposal §5). Each maps
 * to its catalog operator id, from which the renderer looks up the localized keyword.
 */
public enum Quantifier {
    ALL_FIELDS_FILLED("AllFieldsFilled"),
    NO_FIELD_FILLED("NoFieldFilled"),
    AT_LEAST_ONE_FIELD_FILLED("AtLeastOneFieldFilled"),
    NOT_ALL_FIELDS_FILLED("NotAllFieldsFilled"),
    FIELDS_NOT_COLLECTIVELY_FILLED("FieldsNotCollectivelyFilled"),
    NOT_EXACTLY_ONE_FIELD_FILLED("NotExactlyOneFieldFilled"),
    MORE_THAN_ONE_FIELD_FILLED("MoreThanOneFieldFilled"),
    FIELD_VALUES_NOT_UNIQUE("FieldValuesNotUnique"),
    DATE_RANGES_OVERLAP("DateRangesOverlap");

    private final String operatorId;

    Quantifier(String operatorId) {
        this.operatorId = operatorId;
    }

    public String operatorId() {
        return operatorId;
    }
}
