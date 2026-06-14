package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The static-import facade (proposal §10, jOOQ-style): free-standing factories for composing
 * conditions, made for reusable combinators — {@code gate.and(fieldNotFilled(field))} instead of a
 * handle-method chain. Every factory takes <em>model-resolved handles</em> ({@link FieldRef},
 * {@link StringRef}, …), never string paths: model-awareness stays mandatory (§5a) because handles
 * only come from {@link BoundModel}. Grows with the operator catalog.
 */
public final class Dsl {

    /** The kernel's date-constant form (German {@code dd.MM.yyyy}); see {@link DateLiteral}. */
    private static final DateTimeFormatter DATE_LITERAL_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** The kernel's time-constant form ({@code HH:mm:ss}); see {@link TimeLiteral}. */
    private static final DateTimeFormatter TIME_LITERAL_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Dsl() {
    }

    public static Condition fieldFilled(FieldRef field) {
        return new FieldPresenceCondition(field, true);
    }

    public static Condition fieldNotFilled(FieldRef field) {
        return new FieldPresenceCondition(field, false);
    }

    /** {@code Length(field)} as a comparable numeric expression. */
    public static NumberExpr length(StringRef field) {
        return field.length();
    }

    /** {@code [field] PatternMatched "regex"} — anchored whole-value match. */
    public static Condition patternMatched(StringRef field, String regex) {
        return field.matches(regex);
    }

    /** {@code [field] PatternViolated "regex"} — anchored whole-value non-match. */
    public static Condition patternViolated(StringRef field, String regex) {
        return field.violates(regex);
    }

    // --- filled-count aggregates — comparable numeric expressions ------------------------------

    /**
     * {@code NumberOfFilledFields(f1, f2, …)} — ≥2 fields by signature; kinds may be mixed.
     * Operands may be flattened repetition lists ({@link BoundModel#flatten}).
     */
    public static NumberExpr numberOfFilledFields(FieldOperand first, FieldOperand second, FieldOperand... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first);
        fields.add(second);
        fields.addAll(java.util.List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS, fields, java.util.List.of()));
    }

    /**
     * {@code NumberOfFilledFields(scope)} over a single flattened repetition list — counts the filled
     * fields across one wildcard scope (e.g. the flatten of {@code IndexFields*} then {@code indexField}).
     * The single-list form is valid on its own (the param law exempts a repetition list, verified — see
     * {@link CountAggregate}).
     */
    public static NumberExpr numberOfFilledFields(FieldListRef scope) {
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS,
                java.util.List.of(scope), java.util.List.of()));
    }

    /** {@code NumberOfFilledFields(group, …)} — counts the filled fields inside the group(s). */
    public static NumberExpr numberOfFilledFields(GroupRef first, GroupRef... rest) {
        var groups = new java.util.ArrayList<GroupRef>();
        groups.add(first);
        groups.addAll(java.util.List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS, java.util.List.of(), groups));
    }

    /** {@code Sum(…)} over number fields/repetition lists — unspecified fields count as 0 (§9). */
    public static NumberExpr sum(FieldOperand first, FieldOperand... rest) {
        return listAggregate(ListAggregate.AggOp.SUM, first, rest);
    }

    /** {@code SumOfProducts(a, b)} — Σ aᵢ·bᵢ over two parallel number field lists. */
    public static NumberExpr sumOfProducts(FieldOperand left, FieldOperand right) {
        return new NumberExpr(new SumOfProducts(left, right));
    }

    /** {@code RoundDownValue(field)} — the field's value rounded down to an integer. */
    public static NumberExpr roundDownValue(NumberRef field) {
        return new NumberExpr(new FieldRounding(FieldRounding.Mode.ROUND_DOWN, field.field()));
    }

    /** {@code RoundUpValue(field)} — the field's value rounded up to an integer. */
    public static NumberExpr roundUpValue(NumberRef field) {
        return new NumberExpr(new FieldRounding(FieldRounding.Mode.ROUND_UP, field.field()));
    }

    /** {@code RoundAccountingValue(field)} — the field's value rounded to an integer (banker's rounding). */
    public static NumberExpr roundAccountingValue(NumberRef field) {
        return new NumberExpr(new FieldRounding(FieldRounding.Mode.ROUND_ACCOUNTING, field.field()));
    }

    /** {@code AbsValue(field)} — the absolute value of the field. */
    public static NumberExpr absValue(NumberRef field) {
        return new NumberExpr(new FieldRounding(FieldRounding.Mode.ABS, field.field()));
    }

    /** {@code MaxValue(…)} over number fields/repetition lists — unspecified fields are ignored. */
    public static NumberExpr maxValue(FieldOperand first, FieldOperand... rest) {
        return listAggregate(ListAggregate.AggOp.MAX_VALUE, first, rest);
    }

    /** {@code MinValue(…)} over number fields/repetition lists — unspecified fields are ignored. */
    public static NumberExpr minValue(FieldOperand first, FieldOperand... rest) {
        return listAggregate(ListAggregate.AggOp.MIN_VALUE, first, rest);
    }

    /** {@code MaxValue(…)} over a date repetition list — the latest specified date (a date operand). */
    public static DateExpr latestOf(FieldListRef dateScope) {
        return new DateExpr(new DateListAggregate(DateListAggregate.Mode.LATEST, dateScope));
    }

    /** {@code MinValue(…)} over a date repetition list — the earliest specified date (a date operand). */
    public static DateExpr earliestOf(FieldListRef dateScope) {
        return new DateExpr(new DateListAggregate(DateListAggregate.Mode.EARLIEST, dateScope));
    }

    /** {@code FirstFilledValue(…)} over a number repetition list — the first specified value, as a number. */
    public static NumberExpr firstFilledNumber(FieldListRef numberScope) {
        requireKind(numberScope, io.github.mbackschat.a12.dm.core.ValueKind.NUMBER, "firstFilledNumber");
        return new NumberExpr(new FirstFilledValue(numberScope));
    }

    /** {@code FirstFilledValue(…)} over a date repetition list — the first specified value, as a date. */
    public static DateExpr firstFilledDate(FieldListRef dateScope) {
        requireKind(dateScope, io.github.mbackschat.a12.dm.core.ValueKind.DATE, "firstFilledDate");
        return new DateExpr(new FirstFilledValue(dateScope));
    }

    /** {@code NumberOfFilledGroups(/RootGroup*)} — the count of a repeatable root group's filled repetitions. */
    public static NumberExpr numberOfFilledRootGroups(GroupRef rootGroup) {
        return new NumberExpr(new RootGroupCount(rootGroup));
    }

    /** {@code FirstFilledValue(…)} over a string repetition list — the first specified value, as a string. */
    public static StringExpr firstFilledString(FieldListRef stringScope) {
        requireKind(stringScope, io.github.mbackschat.a12.dm.core.ValueKind.STRING, "firstFilledString");
        return new StringExpr(new FirstFilledValue(stringScope));
    }

    /** {@code FirstFilledValue(…)} over an enum repetition list — the first specified value, as an enum. */
    public static EnumExpr firstFilledEnum(FieldListRef enumScope) {
        requireKind(enumScope, io.github.mbackschat.a12.dm.core.ValueKind.ENUMERATION, "firstFilledEnum");
        return new EnumExpr(new FirstFilledValue(enumScope));
    }

    private static void requireKind(FieldListRef scope, io.github.mbackschat.a12.dm.core.ValueKind expected,
            String method) {
        if (scope.kind() != expected) {
            throw new IllegalArgumentException(method + " needs a " + expected + " field list; "
                    + scope.renderedPath() + " is " + scope.kind());
        }
    }

    private static NumberExpr listAggregate(ListAggregate.AggOp op, FieldOperand first, FieldOperand... rest) {
        var operands = new java.util.ArrayList<FieldOperand>();
        operands.add(first);
        operands.addAll(java.util.List.of(rest));
        return new NumberExpr(new ListAggregate(op, operands));
    }

    // --- operand-list math (over numeric value expressions) ------------------------------------

    /** {@code Abs(value)} — the absolute value of a numeric expression (e.g. {@code Abs([A] - [B])}). */
    public static NumberExpr abs(NumberExpr value) {
        return new NumberExpr(new OpListMath(OpListMath.Op.ABS, List.of(value.expression())));
    }

    /** {@code Min(v1, v2, …)} — the minimum of one or more numeric expressions. */
    public static NumberExpr min(NumberExpr first, NumberExpr... rest) {
        return new NumberExpr(new OpListMath(OpListMath.Op.MIN, operandList(first, rest)));
    }

    /** {@code Max(v1, v2, …)} — the maximum of one or more numeric expressions. */
    public static NumberExpr max(NumberExpr first, NumberExpr... rest) {
        return new NumberExpr(new OpListMath(OpListMath.Op.MAX, operandList(first, rest)));
    }

    private static List<Calculation> operandList(NumberExpr first, NumberExpr... rest) {
        var ops = new ArrayList<Calculation>();
        ops.add(first.expression());
        for (NumberExpr e : rest) {
            ops.add(e.expression());
        }
        return ops;
    }

    // --- rounding (operand-list, over an arbitrary numeric expression) -------------------------

    /** {@code RoundDown(expr, n)} — round a numeric expression down to {@code n} decimal places. */
    public static NumberExpr roundDown(NumberExpr operand, int decimalPlaces) {
        return new NumberExpr(new Rounding(Rounding.Mode.DOWN, operand.expression(), decimalPlaces));
    }

    /** {@code RoundUp(expr, n)} — round a numeric expression up to {@code n} decimal places. */
    public static NumberExpr roundUp(NumberExpr operand, int decimalPlaces) {
        return new NumberExpr(new Rounding(Rounding.Mode.UP, operand.expression(), decimalPlaces));
    }

    /** {@code RoundAccounting(expr, n)} — round a numeric expression to {@code n} decimal places (commercial rounding). */
    public static NumberExpr roundAccounting(NumberExpr operand, int decimalPlaces) {
        return new NumberExpr(new Rounding(Rounding.Mode.ACCOUNTING, operand.expression(), decimalPlaces));
    }

    // --- date family (item 9) — Today, differences (→ number), shifts (→ date) ----------------

    /** {@code Today} — the current date constant (DE {@code Heute}). */
    public static DateExpr today() {
        return new DateExpr(new Today());
    }

    /** {@code Now} — the current date-time (DE {@code Jetzt}); the date-time sibling of {@link #today()}. */
    public static DateTimeExpr now() {
        return new DateTimeExpr(new Now());
    }

    /**
     * {@code BaseYear} — the model's configured base year (DE {@code Basisjahr}), as a comparable date
     * (the start of the base year). Only valid in a model declaring a base year. To compare it as the
     * year <em>number</em>, use {@link #baseYearNumber()}.
     */
    public static DateExpr baseYear() {
        return new DateExpr(new BaseYear());
    }

    /** {@code BaseYear} as the year <em>number</em> — for a numeric comparison against a number field. */
    public static NumberExpr baseYearNumber() {
        return new NumberExpr(new BaseYear());
    }

    /**
     * A date constant for comparison/shift — {@code [DispatchDate] > dateValue("01.01.2022")}. The text is
     * the kernel's German {@code dd.MM.yyyy} form (with or without the year, e.g. {@code "13.07."}),
     * independent of the compared field's display format.
     */
    public static DateExpr dateValue(String ddMMyyyy) {
        return new DateExpr(new DateLiteral(ddMMyyyy));
    }

    /**
     * A date constant from a {@link LocalDate} — the typed form of {@link #dateValue(String)}. A
     * {@code LocalDate} can't be a malformed date, so the literal is valid by construction; it renders to
     * the kernel's German {@code dd.MM.yyyy} form, exactly as the string overload does.
     */
    public static DateExpr dateValue(LocalDate date) {
        return dateValue(date.format(DATE_LITERAL_FORMAT));
    }

    /** A time-of-day constant for comparison — {@code [PickupTime] > timeValue("17:00:00")} ({@code HH:mm:ss}). */
    public static TimeExpr timeValue(String hhmmss) {
        return new TimeExpr(new TimeLiteral(hhmmss));
    }

    /**
     * A time-of-day constant from a {@link LocalTime} — the typed form of {@link #timeValue(String)},
     * valid by construction; renders to the kernel's {@code HH:mm:ss} form, as the string overload does.
     */
    public static TimeExpr timeValue(LocalTime time) {
        return timeValue(time.format(TIME_LITERAL_FORMAT));
    }

    /** {@code DifferenceInDays(a, b)} — a numeric date difference (a floor, not a calendar count). */
    public static NumberExpr differenceInDays(TemporalOperand a, TemporalOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.DAYS, a.expression(), b.expression()));
    }

    public static NumberExpr differenceInMonths(TemporalOperand a, TemporalOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.MONTHS, a.expression(), b.expression()));
    }

    public static NumberExpr differenceInYears(TemporalOperand a, TemporalOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.YEARS, a.expression(), b.expression()));
    }

    /** {@code DifferenceInHours(dt1, dt2)} — the whole-hours difference between two date-times, as a number. */
    public static NumberExpr differenceInHours(TemporalOperand a, TemporalOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.HOURS, a.expression(), b.expression()));
    }

    /** {@code DifferenceInMinutes(dt1, dt2)} — the whole-minutes difference between two date-times. */
    public static NumberExpr differenceInMinutes(TemporalOperand a, TemporalOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.MINUTES, a.expression(), b.expression()));
    }

    /** {@code DifferenceInSeconds(dt1, dt2)} — the whole-seconds difference between two date-times. */
    public static NumberExpr differenceInSeconds(TemporalOperand a, TemporalOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.SECONDS, a.expression(), b.expression()));
    }

    /** {@code YearFromDate(date)} — the year component of a date (or date-time) as a number. */
    public static NumberExpr yearFromDate(TemporalOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.YEAR, date.expression()));
    }

    /** {@code MonthFromDate(date)} — the month component (1-12) as a number. */
    public static NumberExpr monthFromDate(TemporalOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.MONTH, date.expression()));
    }

    /** {@code DayFromDate(date)} — the day-of-month component as a number. */
    public static NumberExpr dayFromDate(TemporalOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.DAY, date.expression()));
    }

    /** {@code QuarterFromDate(date)} — the calendar quarter (1-4) as a number. */
    public static NumberExpr quarterFromDate(TemporalOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.QUARTER, date.expression()));
    }

    /** {@code HoursFromTime(time)} — the hour-of-day component (0-23) of a time (or date-time) as a number. */
    public static NumberExpr hoursFromTime(TemporalOperand time) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.HOURS, time.expression()));
    }

    /** {@code MinutesFromTime(time)} — the minute component (0-59) as a number. */
    public static NumberExpr minutesFromTime(TemporalOperand time) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.MINUTES, time.expression()));
    }

    /** {@code SecondsFromTime(time)} — the second component (0-59) as a number. */
    public static NumberExpr secondsFromTime(TemporalOperand time) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.SECONDS, time.expression()));
    }

    /** {@code Date(day, month, year)} — construct a date from numeric components (a comparable date). */
    public static DateExpr date(NumberExpr day, NumberExpr month, NumberExpr year) {
        return new DateExpr(new DateConstruction(day.expression(), month.expression(), year.expression()));
    }

    /** {@code Date(day, month, century, decade)} — a date whose year is split into century + 2-digit decade. */
    public static DateExpr date(NumberExpr day, NumberExpr month, NumberExpr century, NumberExpr decade) {
        return new DateExpr(new DateConstruction(day.expression(), month.expression(),
                century.expression(), java.util.Optional.of(decade.expression())));
    }

    /**
     * {@code Time("10", "30", "50")} — construct a time-of-day from hour/minute/second constants (a
     * comparable time). The kernel takes the components as quoted constants here (a bracketed value
     * {@code [H]} is rejected); for field components use {@link #time(FieldRef, FieldRef, FieldRef)}.
     */
    public static TimeExpr time(String hour, String minute, String second) {
        return new TimeExpr(new TimeConstruction(new StringLiteral(hour), new StringLiteral(minute),
                new StringLiteral(second)));
    }

    /** {@code Time(H, M, S)} — construct a time-of-day from three number fields (rendered as bare entity refs). */
    public static TimeExpr time(FieldRef hour, FieldRef minute, FieldRef second) {
        return new TimeExpr(new TimeConstruction(new FieldValue(hour), new FieldValue(minute), new FieldValue(second)));
    }

    /** {@code DateTime(date, time)} — combine a date and a time into a comparable date-time. */
    public static DateTimeExpr dateTime(DateOperand date, TimeOperand time) {
        return new DateTimeExpr(new DateTimeConstruction(date.expression(), time.expression()));
    }

    /**
     * {@code DateRange(start, end)} — construct a date-range value from two dates (the kernel's
     * {@code IDateRangeConstructionOperation}). Computation-only: its sole legal home is a computation
     * operation assigning a {@code DATE_RANGE} field (it has no condition use site — see
     * {@link DateRangeConstruction}).
     */
    public static DateRangeConstruction dateRange(DateOperand start, DateOperand end) {
        return new DateRangeConstruction(start.expression(), end.expression());
    }

    /** {@code Valid(dateConstruct)} — true iff the constructed date is a real calendar date. */
    public static Condition valid(DateOperand dateConstruct) {
        return new DateValidity(true, dateConstruct.expression());
    }

    /** {@code Invalid(dateConstruct)} — true iff the constructed date is not a real calendar date. */
    public static Condition invalid(DateOperand dateConstruct) {
        return new DateValidity(false, dateConstruct.expression());
    }

    /**
     * {@code Valid(field, "TypeName")} — true iff {@code field}'s value is a valid instance of the
     * predefined custom field type {@code typeName} (e.g. {@code "IBAN"}, {@code "Steuernummer"}). The
     * field must be STRING or ENUMERATION. <b>The type name is not validated at model-check time</b> — the
     * predefined types are a runtime client-SPI, so a wrong name only fails at evaluation (see
     * {@link CustomTypeValidity}).
     */
    public static Condition validType(FieldRef field, String typeName) {
        return customTypeValidity(true, field, typeName);
    }

    /** {@code Invalid(field, "TypeName")} — the negation of {@link #validType} (the field is NOT a valid {@code typeName}). */
    public static Condition invalidType(FieldRef field, String typeName) {
        return customTypeValidity(false, field, typeName);
    }

    private static Condition customTypeValidity(boolean valid, FieldRef field, String typeName) {
        if (field.kind() != ValueKind.STRING && field.kind() != ValueKind.ENUMERATION) {
            throw new IllegalArgumentException("Valid/Invalid(field, \"Type\") needs a STRING or ENUMERATION field, but "
                    + field.path() + " is " + field.kind());
        }
        return new CustomTypeValidity(valid, field, typeName);
    }

    /** {@code StartOfDateRange(range)} — the start bound of a date-range, as a comparable date. */
    public static DateExpr startOfDateRange(DateRangeRef range) {
        return new DateExpr(new DateRangeBound(DateRangeBound.Bound.START, range.expression()));
    }

    /** {@code EndOfDateRange(range)} — the end bound of a date-range, as a comparable date. */
    public static DateExpr endOfDateRange(DateRangeRef range) {
        return new DateExpr(new DateRangeBound(DateRangeBound.Bound.END, range.expression()));
    }

    /**
     * {@code ValueAsDate(field, FirstDay)} — the earliest concrete date of a partially known date field
     * (a {@code DateType} with a date precision other than FULL), as a comparable date.
     */
    public static DateExpr valueAsDateFirstDay(FieldRef partiallyKnownDate) {
        return new DateExpr(new ValueAsDate(partiallyKnownDate, true));
    }

    /** {@code ValueAsDate(field, LastDay)} — the latest concrete date of a partially known date field. */
    public static DateExpr valueAsDateLastDay(FieldRef partiallyKnownDate) {
        return new DateExpr(new ValueAsDate(partiallyKnownDate, false));
    }

    /** {@code DateFromDateTime(dt)} — the date part of a date-time, as a comparable date. */
    public static DateExpr dateFromDateTime(DateTimeRef dateTime) {
        return new DateExpr(new DateTimePart(DateTimePart.Unit.DATE, dateTime.expression()));
    }

    /** {@code TimeFromDateTime(dt)} — the time-of-day part of a date-time, as a comparable time. */
    public static TimeExpr timeFromDateTime(DateTimeRef dateTime) {
        return new TimeExpr(new DateTimePart(DateTimePart.Unit.TIME, dateTime.expression()));
    }

    /** {@code AddDays(date, n)} — shifts a date by an integer constant, yielding a date (comparable). */
    public static DateExpr addDays(TemporalOperand date, long amount) {
        return addDays(date, number(Long.toString(amount)));
    }

    public static DateExpr addMonths(TemporalOperand date, long amount) {
        return addMonths(date, number(Long.toString(amount)));
    }

    public static DateExpr addYears(TemporalOperand date, long amount) {
        return addYears(date, number(Long.toString(amount)));
    }

    /** {@code AddDays(date, n)} where the amount is a numeric expression (e.g. a field, {@code AddDays(d, [n])}). */
    public static DateExpr addDays(TemporalOperand date, NumberExpr amount) {
        return new DateExpr(new DateShift(DateShift.Unit.DAYS, date.expression(), amount.expression()));
    }

    public static DateExpr addMonths(TemporalOperand date, NumberExpr amount) {
        return new DateExpr(new DateShift(DateShift.Unit.MONTHS, date.expression(), amount.expression()));
    }

    public static DateExpr addYears(TemporalOperand date, NumberExpr amount) {
        return new DateExpr(new DateShift(DateShift.Unit.YEARS, date.expression(), amount.expression()));
    }

    /** {@code AddHours(dateTime, n)} — shifts a date-time by {@code n} hours, yielding a date-time ({@code n} may be negative). */
    public static DateExpr addHours(TemporalOperand dateTime, long amount) {
        return addHours(dateTime, number(Long.toString(amount)));
    }

    public static DateExpr addMinutes(TemporalOperand dateTime, long amount) {
        return addMinutes(dateTime, number(Long.toString(amount)));
    }

    public static DateExpr addSeconds(TemporalOperand dateTime, long amount) {
        return addSeconds(dateTime, number(Long.toString(amount)));
    }

    /** {@code AddHours(dateTime, n)} where the amount is a numeric expression. */
    public static DateExpr addHours(TemporalOperand dateTime, NumberExpr amount) {
        return new DateExpr(new DateShift(DateShift.Unit.HOURS, dateTime.expression(), amount.expression()));
    }

    public static DateExpr addMinutes(TemporalOperand dateTime, NumberExpr amount) {
        return new DateExpr(new DateShift(DateShift.Unit.MINUTES, dateTime.expression(), amount.expression()));
    }

    public static DateExpr addSeconds(TemporalOperand dateTime, NumberExpr amount) {
        return new DateExpr(new DateShift(DateShift.Unit.SECONDS, dateTime.expression(), amount.expression()));
    }

    /** A numeric literal as a comparable expression — e.g. an arithmetic operand ({@code … + 2}). */
    public static NumberExpr number(String literal) {
        return new NumberExpr(NumberLiteral.of(literal));
    }

    /** A string literal as a string expression — e.g. a concatenation part ({@code first.plus(text(" "))}). */
    public static StringExpr text(String literal) {
        return new StringExpr(new StringLiteral(literal));
    }

    /** {@code FieldValueAsString(field)} — a field's value coerced to its string form, as a string expression. */
    public static StringExpr fieldValueAsString(FieldRef field) {
        return new StringExpr(new FieldValueAsString(field));
    }

    /** {@code RangeAsString(field, start, end)} — a substring of a string field, as a string expression. */
    public static StringExpr rangeAsString(FieldRef field, int start, int end) {
        return new StringExpr(new RangeAsString(field, start, end));
    }

    /** {@code FieldValueAsNumber(field)} — a string/enum field's value coerced to a number (it must always convert). */
    public static NumberExpr fieldValueAsNumber(FieldRef field) {
        return new NumberExpr(new FieldValueAsNumber(field));
    }

    /** {@code RangeAsNumber(field, start, end)} — a substring of a string field read as a number (non-numeric → 0). */
    public static NumberExpr rangeAsNumber(FieldRef field, int start, int end) {
        return new NumberExpr(new RangeAsNumber(field, start, end));
    }

    /** {@code CustomCondition <Name>} — the app-defined escape hatch (KERNEL-SEMANTICS §14). */
    public static Condition customCondition(String name) {
        return new CustomConditionRef(name);
    }

    /** {@code CurrentRepetition(group)} — the group's current iteration index, as a number expression. */
    public static NumberExpr currentRepetition(GroupRef group) {
        return new NumberExpr(new CurrentRepetition(group));
    }

    /**
     * {@code CurrentRepetition($group)} — the <em>outer</em> iteration's index (the {@code $}
     * correlation, D13), for a self-exclusion like {@code CurrentRepetition(G) != CurrentRepetition($G)}
     * inside a {@code Having} filter.
     */
    public static NumberExpr currentRepetition(GroupRef group, boolean outer) {
        return new NumberExpr(new CurrentRepetition(group, outer));
    }

    /**
     * {@code NumberOfValueInFields(value IN f1, f2, …)} — the count of the listed fields equal to a
     * boolean constant (e.g. {@code NumberOfValueInFields(True IN OptInA, OptInB, OptInC)}). The kernel
     * needs at least two fields (or a group).
     */
    public static NumberExpr numberOfValueInFields(boolean value, FieldOperand first, FieldOperand second,
            FieldOperand... rest) {
        return new NumberExpr(new NumberOfValueInFields(new BoolLiteral(value), valueInFields(first, second, rest)));
    }

    /** {@code NumberOfValueInFields(constant IN f1, f2, …)} — count of the listed fields equal to a string constant. */
    public static NumberExpr numberOfValueInFields(String value, FieldOperand first, FieldOperand second,
            FieldOperand... rest) {
        return new NumberExpr(new NumberOfValueInFields(new StringLiteral(value), valueInFields(first, second, rest)));
    }

    /** {@code NumberOfValueInFields(n IN f1, f2, …)} — count of the listed fields equal to an integer constant. */
    public static NumberExpr numberOfValueInFields(long value, FieldOperand first, FieldOperand second,
            FieldOperand... rest) {
        return new NumberExpr(new NumberOfValueInFields(NumberLiteral.of(value), valueInFields(first, second, rest)));
    }

    private static List<FieldOperand> valueInFields(FieldOperand first, FieldOperand second, FieldOperand... rest) {
        var fields = new ArrayList<FieldOperand>();
        fields.add(first);
        fields.add(second);
        fields.addAll(List.of(rest));
        return fields;
    }

    /** {@code NumberOfDifferentValues(…)} — distinct-value count over type-homogeneous operands. */
    public static NumberExpr numberOfDifferentValues(FieldOperand first, FieldOperand... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first);
        fields.addAll(java.util.List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.DIFFERENT_VALUES, fields, java.util.List.of()));
    }

    /**
     * {@code NumberOfFilledGroups(group*)} — counts the filled repetitions of one repeatable group (the
     * wildcard scope). The group must repeat; for distinct groups use the ≥2-group form.
     */
    public static NumberExpr numberOfFilledGroups(GroupRef repeatableGroup) {
        if (!repeatableGroup.repeating()) {
            throw new IllegalArgumentException("NumberOfFilledGroups over a single group needs a repeatable"
                    + " group (its wildcard scope): " + repeatableGroup.path());
        }
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_GROUPS,
                java.util.List.of(), java.util.List.of(repeatableGroup)));
    }

    /** {@code NumberOfFilledGroups(g1, g2, …)} — ≥2 groups by signature. */
    public static NumberExpr numberOfFilledGroups(GroupRef first, GroupRef second, GroupRef... rest) {
        var groups = new java.util.ArrayList<GroupRef>();
        groups.add(first);
        groups.add(second);
        groups.addAll(java.util.List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_GROUPS, java.util.List.of(), groups));
    }

    // --- field-list × value-list quantifiers — Name(f1, f2 In v1, v2) --------------------------
    // Typed entry points keep the field list type-homogeneous (MVK_VARYING_TYPES_NOT_ALLOWED
    // erased); the enum variants domain-check every value against every field (the kernel's
    // MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY pre-checked).

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, first, rest);
    }

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, first, rest);
    }

    /** {@code DateRangesOverlap(r1, r2, …)} — true iff the date ranges of the listed fields overlap. */
    public static Condition dateRangesOverlap(FieldOperand first, FieldOperand... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first);
        fields.addAll(java.util.List.of(rest));
        return new FieldListPredicate(Quantifier.DATE_RANGES_OVERLAP, fields);
    }

    /** {@code AtLeastOneDateRangeOverlaps(range IN r1, r2, …)} — {@code range} overlaps at least one listed range. */
    public static Condition atLeastOneDateRangeOverlaps(FieldOperand range, FieldOperand first, FieldOperand... rest) {
        var values = new java.util.ArrayList<FieldOperand>();
        values.add(first);
        values.addAll(java.util.List.of(rest));
        return new FieldListEntityListCondition(ValueListQuantifier.AT_LEAST_ONE_DATE_RANGE_OVERLAPS,
                java.util.List.of(range), values);
    }

    private static Condition stringListQuantifier(
            ValueListQuantifier q, java.util.List<String> values, StringRef first, StringRef... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first.field());
        for (StringRef r : rest) {
            fields.add(r.field());
        }
        var literals = values.stream().<Calculation>map(StringLiteral::new).toList();
        return new FieldListValueListCondition(q, fields, literals);
    }

    private static Condition enumListQuantifier(
            ValueListQuantifier q, java.util.List<String> values, EnumRef first, EnumRef... rest) {
        var refs = new java.util.ArrayList<EnumRef>();
        refs.add(first);
        refs.addAll(java.util.List.of(rest));
        // every value must be valid for every listed enum field
        var literals = new java.util.ArrayList<Calculation>();
        for (String v : values) {
            for (EnumRef r : refs) {
                r.checkedLiteral(v);
            }
            literals.add(new EnumValueLiteral(v));
        }
        return new FieldListValueListCondition(q, refs.stream().<FieldOperand>map(EnumRef::field).toList(), literals);
    }

    // shape B over flattened repetition lists (string-kind fields; the corpus's dominant shape) —
    // enum-kind starred lists are deferred until FieldListRef carries the enum domain.

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<String> values, FieldListRef first, FieldListRef... rest) {
        return flattenedListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<String> values, FieldListRef first, FieldListRef... rest) {
        return flattenedListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<String> values, FieldListRef first, FieldListRef... rest) {
        return flattenedListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, first, rest);
    }

    private static Condition flattenedListQuantifier(
            ValueListQuantifier q, java.util.List<String> values, FieldListRef first, FieldListRef... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(checkedStringList(first));
        for (FieldListRef r : rest) {
            fields.add(checkedStringList(r));
        }
        var literals = values.stream().<Calculation>map(StringLiteral::new).toList();
        return new FieldListValueListCondition(q, fields, literals);
    }

    private static FieldListRef checkedStringList(FieldListRef ref) {
        if (ref.kind() != io.github.mbackschat.a12.dm.core.ValueKind.STRING) {
            throw new IllegalArgumentException("value-list quantifiers over repetition lists take"
                    + " string fields here; " + ref.renderedPath() + " is " + ref.kind()
                    + " (enum-kind lists are deferred — MVK_INVALID_TYPES_FOR_COMPARISON otherwise)");
        }
        return ref;
    }

    // shape C — the value list is itself a field list (entity-valued, not constants), e.g.
    // `Name(f1, f2 In g1, g2)` where g* are fields/repetition lists. Type compatibility is the kernel's.

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, valueFields, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, valueFields, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, valueFields, first, rest);
    }

    private static Condition entityListQuantifier(ValueListQuantifier q,
            java.util.List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first);
        fields.addAll(java.util.List.of(rest));
        return new FieldListEntityListCondition(q, fields, valueFields);
    }

    // --- field-list quantifiers — at least one field by signature ------------------------------

    public static Condition allFieldsFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.ALL_FIELDS_FILLED, first, rest);
    }

    public static Condition noFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.NO_FIELD_FILLED, first, rest);
    }

    public static Condition atLeastOneFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.AT_LEAST_ONE_FIELD_FILLED, first, rest);
    }

    public static Condition notAllFieldsFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.NOT_ALL_FIELDS_FILLED, first, rest);
    }

    public static Condition fieldsNotCollectivelyFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, first, rest);
    }

    public static Condition notExactlyOneFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED, first, rest);
    }

    /** {@code MoreThanOneFieldFilled(f1, f2, …)} — true when two or more of the listed fields are filled. */
    public static Condition moreThanOneFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.MORE_THAN_ONE_FIELD_FILLED, first, rest);
    }

    /** {@code FieldValuesNotUnique(f1, f2, …)} — true when the listed values are not all distinct. */
    public static Condition fieldValuesNotUnique(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.FIELD_VALUES_NOT_UNIQUE, first, rest);
    }

    /**
     * {@code RepetitionNotUnique(key1, key2, …)} — duplicate-key detection over repetitions
     * (KERNEL-SEMANTICS §9). Scope with {@link RepetitionNotUniqueCondition#from from(group)}
     * ({@code @From}). Keys are plain {@link FieldRef}s by signature — groups and starred lists
     * are unrepresentable here.
     */
    public static RepetitionNotUniqueCondition repetitionNotUnique(FieldRef first, FieldRef... rest) {
        List<FieldRef> keys = new ArrayList<>();
        keys.add(first);
        keys.addAll(List.of(rest));
        return new RepetitionNotUniqueCondition(keys, java.util.Optional.empty());
    }

    private static Condition fieldList(Quantifier quantifier, FieldOperand first, FieldOperand... rest) {
        List<FieldOperand> fields = new ArrayList<>();
        fields.add(first);
        fields.addAll(List.of(rest));
        return fieldList(quantifier, fields);
    }

    /** Shared construction path — also used by {@link BoundModel}'s string-path convenience forms. */
    static Condition fieldList(Quantifier quantifier, List<? extends FieldOperand> fields) {
        return new FieldListPredicate(quantifier, List.copyOf(fields));
    }

    // --- group-scoped field-fill — the fields inside a group scope (optionally across all reps) ----

    /** {@code AllFieldsFilled(Group*)} — all fields inside the group scope(s) are filled. */
    public static Condition allFieldsFilled(GroupListRef first, GroupListRef... rest) {
        return groupScopedFieldFill(Quantifier.ALL_FIELDS_FILLED, first, rest);
    }

    /** {@code NoFieldFilled(Group*)} — no field inside the group scope(s) is filled. */
    public static Condition noFieldFilled(GroupListRef first, GroupListRef... rest) {
        return groupScopedFieldFill(Quantifier.NO_FIELD_FILLED, first, rest);
    }

    /** {@code AtLeastOneFieldFilled(Group*)} — at least one field inside the group scope(s) is filled. */
    public static Condition atLeastOneFieldFilled(GroupListRef first, GroupListRef... rest) {
        return groupScopedFieldFill(Quantifier.AT_LEAST_ONE_FIELD_FILLED, first, rest);
    }

    /** {@code NotAllFieldsFilled(Group*)} — at least one field inside the group scope(s) is empty. */
    public static Condition notAllFieldsFilled(GroupListRef first, GroupListRef... rest) {
        return groupScopedFieldFill(Quantifier.NOT_ALL_FIELDS_FILLED, first, rest);
    }

    /** {@code FieldsNotCollectivelyFilled(Group*)} — the group scope's fields are partially filled. */
    public static Condition fieldsNotCollectivelyFilled(GroupListRef first, GroupListRef... rest) {
        return groupScopedFieldFill(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, first, rest);
    }

    /** {@code NotExactlyOneFieldFilled(Group*)} — not exactly one field inside the group scope(s) is filled. */
    public static Condition notExactlyOneFieldFilled(GroupListRef first, GroupListRef... rest) {
        return groupScopedFieldFill(Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED, first, rest);
    }

    /** {@code MoreThanOneFieldFilled(Group*)} — two or more fields inside the group scope(s) are filled. */
    public static Condition moreThanOneFieldFilled(GroupListRef first, GroupListRef... rest) {
        return groupScopedFieldFill(Quantifier.MORE_THAN_ONE_FIELD_FILLED, first, rest);
    }

    private static Condition groupScopedFieldFill(Quantifier q, GroupListRef first, GroupListRef... rest) {
        var scopes = new java.util.ArrayList<GroupListRef>();
        scopes.add(first);
        scopes.addAll(java.util.List.of(rest));
        return new GroupScopedFieldFill(q, scopes);
    }

    // --- localized error-message texts (F2a) ----------------------------------------------------
    // A model may declare ANY locales (header `locales`) — en/de are mere sugar for the common
    // codes; text(...) covers the rest. Builders check messages against the model's declared
    // locales at build() (every declared locale covered; no undeclared ones used).

    /** A message text for an arbitrary declared locale code, e.g. {@code text("fr_FR", "…")}. */
    public static LocalizedText text(String locale, String text) {
        return new LocalizedText(locale, text);
    }

    /**
     * A field-value <em>interpolation token</em> for an error message (F2a) — renders {@code $<path>.value$},
     * the kernel's placeholder for the field's runtime value (BA error-text §). Typed to {@link FieldRef}
     * (never a wildcard {@code FieldListRef}), so the <b>no-asterisk rule</b> holds by construction; the
     * kernel additionally requires the field to be referenced in the condition at least once without an
     * asterisk (its call, F4). Compose into message text:
     * {@code en("Fee " + fieldValue(fee) + " exceeds the cap.")}.
     */
    public static String fieldValue(FieldRef field) {
        return "$" + field.path() + ".value$";
    }

    /**
     * A field-<em>label</em> interpolation token for an error message (FEATURE-GAPS §3.6) — renders
     * {@code $<path>$} (a bare path, no {@code .value} suffix), the kernel's placeholder for the field's
     * localized <b>label</b> (its name, replaceable by the UI label) rather than its runtime value. The
     * label peer of {@link #fieldValue}: {@code en(fieldLabel(fee) + " is too high.")}.
     */
    public static String fieldLabel(FieldRef field) {
        return "$" + field.path() + "$";
    }

    /**
     * A repetition-<em>index</em> interpolation token for an error message (FEATURE-GAPS §3.6) — renders
     * {@code $#<groupPath>$}, the kernel's placeholder for the 1-based row number of the repeatable
     * {@code group}'s current repetition. Pairs naturally with a uniqueness/per-row rule:
     * {@code en("Row #" + repetitionIndex(items) + " is invalid.")}.
     */
    public static String repetitionIndex(GroupRef group) {
        return "$#" + group.path() + "$";
    }

    /** Sugar for the common {@code en_US} — only valid if the model declares it. */
    public static LocalizedText en(String text) {
        return new LocalizedText("en_US", text);
    }

    /** Sugar for the common {@code de_DE} — only valid if the model declares it. */
    public static LocalizedText de(String text) {
        return new LocalizedText("de_DE", text);
    }

    // --- n-ary composition (study finding 10) ---------------------------------------------------

    /** And-fold: {@code allOf(a, b, c)} renders {@code a And b And c} (no redundant parentheses). */
    public static Condition allOf(Condition first, Condition second, Condition... rest) {
        Condition folded = first.and(second);
        for (Condition c : rest) {
            folded = folded.and(c);
        }
        return folded;
    }

    /** Or-fold: {@code anyOf(a, b, c)} renders {@code a Or b Or c}; parenthesized when mixed with And. */
    public static Condition anyOf(Condition first, Condition second, Condition... rest) {
        Condition folded = first.or(second);
        for (Condition c : rest) {
            folded = folded.or(c);
        }
        return folded;
    }

    // --- group predicates (the group twin of the field forms) ----------------------------------

    public static Condition groupFilled(GroupRef group) {
        return new GroupPresenceCondition(group, true);
    }

    public static Condition groupNotFilled(GroupRef group) {
        return new GroupPresenceCondition(group, false);
    }

    public static Condition allGroupsFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.ALL_GROUPS_FILLED, first, rest);
    }

    public static Condition noGroupFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.NO_GROUP_FILLED, first, rest);
    }

    public static Condition atLeastOneGroupFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED, first, rest);
    }

    public static Condition notAllGroupsFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.NOT_ALL_GROUPS_FILLED, first, rest);
    }

    public static Condition groupsNotCollectivelyFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.GROUPS_NOT_COLLECTIVELY_FILLED, first, rest);
    }

    private static Condition groupList(GroupQuantifier quantifier, GroupRef first, GroupRef... rest) {
        List<GroupRef> groups = new ArrayList<>();
        groups.add(first);
        groups.addAll(List.of(rest));
        return new GroupListPredicate(quantifier, groups);
    }
}
