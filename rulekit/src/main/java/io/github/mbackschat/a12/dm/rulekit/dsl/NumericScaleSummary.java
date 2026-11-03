package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.math.BigDecimal;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * Parser-equivalent static metadata for the numeric equality gate. Scale and constant expandability
 * are independent: treating every literal as exempt loses the kernel's asymmetric smaller-side rule.
 */
record NumericScaleSummary(State state, int scale, boolean expandable) {

    enum State {
        KNOWN,
        UNKNOWN,
        DEFERRED
    }

    static NumericScaleSummary field(int scale) {
        return known(scale, false);
    }

    static NumericScaleSummary literal(NumberLiteral literal) {
        BigDecimal value = literal.value();
        int authoredOrStrippedScale = value.scale() > 0 ? value.scale() : value.stripTrailingZeros().scale();
        return known(authoredOrStrippedScale, true);
    }

    static NumericScaleSummary from(Calculation expression) {
        return switch (expression) {
            case NumberLiteral literal -> literal(literal);
            case Arithmetic arithmetic -> arithmetic(
                    arithmetic.op(), from(arithmetic.left()), from(arithmetic.right()), arithmetic.right());
            case Rounding rounding -> known(rounding.decimalPlaces().orElse(0), false);
            case FieldRounding rounding -> rounding.mode() == FieldRounding.Mode.ABS
                    ? deferred()
                    : known(rounding.decimalPlaces().orElse(0), false);
            case LengthOf ignored -> known(0, false);
            case CountAggregate ignored -> known(0, false);
            case CurrentRepetition ignored -> known(0, false);
            case BaseYear ignored -> known(0, false);
            case DateDifference ignored -> known(0, false);
            case DateExtraction ignored -> known(0, false);
            case RootGroupCount ignored -> known(0, false);
            case NumberOfValueInFields ignored -> known(0, false);
            case FieldValue ignored -> deferred();
            case StringLiteral ignored -> deferred();
            case EnumValueLiteral ignored -> deferred();
            case BoolLiteral ignored -> deferred();
            case DateLiteral ignored -> deferred();
            case TimeLiteral ignored -> deferred();
            case DateTimeLiteral ignored -> deferred();
            case EnumCategoryAccess ignored -> deferred();
            case ListAggregate ignored -> deferred();
            case Today ignored -> deferred();
            case Now ignored -> deferred();
            case DateShift ignored -> deferred();
            case IndexedFieldValue ignored -> deferred();
            case Concatenation ignored -> deferred();
            case DateListAggregate ignored -> deferred();
            case FirstFilledValue ignored -> deferred();
            case FieldValueAsString ignored -> deferred();
            case IndexedFieldValueAsString ignored -> deferred();
            case RangeAsString ignored -> deferred();
            case FieldValueAsNumber ignored -> deferred();
            case RangeAsNumber ignored -> deferred();
            case OpListMath ignored -> deferred();
            case ValueAsDate ignored -> deferred();
            case DateTimePart ignored -> deferred();
            case SumOfProducts ignored -> deferred();
            case SumOfTerms ignored -> deferred();
            case DateConstruction ignored -> deferred();
            case TimeConstruction ignored -> deferred();
            case DateTimeConstruction ignored -> deferred();
            case DateRangeBound ignored -> deferred();
            case DateRangeConstruction ignored -> deferred();
        };
    }

    static NumericScaleSummary arithmetic(
            Arithmetic.Op op,
            NumericScaleSummary left,
            NumericScaleSummary right,
            Calculation rightExpression) {
        if (left.state == State.DEFERRED || right.state == State.DEFERRED) {
            return deferred();
        }
        return switch (op) {
            case ADD, SUBTRACT -> left.state == State.KNOWN && right.state == State.KNOWN
                    ? known(Math.max(left.scale, right.scale), left.expandable && right.expandable)
                    : unknown(left.expandable && right.expandable);
            case MULTIPLY -> left.state == State.KNOWN && right.state == State.KNOWN
                    ? known(Math.addExact(left.scale, right.scale), left.expandable || right.expandable)
                    : unknown(left.expandable || right.expandable);
            case DIVIDE -> unknown(false);
            case POWER -> power(left, right, rightExpression);
        };
    }

    private static NumericScaleSummary power(
            NumericScaleSummary base,
            NumericScaleSummary exponent,
            Calculation exponentExpression) {
        if (base.state == State.KNOWN
                && base.scale == 0
                && exponent.state == State.KNOWN
                && exponent.scale <= 0
                && exponentExpression instanceof NumberLiteral literal
                && literal.value().signum() >= 0) {
            return known(0, false);
        }
        return unknown(false);
    }

    static NumericScaleSummary known(int scale, boolean expandable) {
        return new NumericScaleSummary(State.KNOWN, scale, expandable);
    }

    static NumericScaleSummary unknown(boolean expandable) {
        return new NumericScaleSummary(State.UNKNOWN, 0, expandable);
    }

    static NumericScaleSummary deferred() {
        return new NumericScaleSummary(State.DEFERRED, 0, false);
    }

    void requireEqualityCompatible(NumericScaleSummary other) {
        if (state == State.DEFERRED || other.state == State.DEFERRED) {
            return;
        }
        if (state == State.UNKNOWN || other.state == State.UNKNOWN) {
            throw invalid(displayScale(this), displayScale(other));
        }
        if (scale == other.scale) {
            return;
        }
        NumericScaleSummary smaller = scale < other.scale ? this : other;
        if (!smaller.expandable) {
            throw invalid(Integer.toString(scale), Integer.toString(other.scale));
        }
    }

    private static KernelCodeRefusal invalid(String left, String right) {
        return Refusals.of(KernelCode.MVK_INVALID_COMPARE_DEC_PLACES,
                "cannot compare numeric expressions with == / != at derived scales " + left + " and " + right
                        + " — only a multiplicative-constant-capable smaller-scale side may be padded;"
                        + " ordering (< <= > >=) is exempt");
    }

    private static String displayScale(NumericScaleSummary summary) {
        return summary.state == State.UNKNOWN ? "unknown" : Integer.toString(summary.scale);
    }
}
