package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The static-import facade (PROJECT-DESIGN §10, jOOQ-style): free-standing factories for composing
 * conditions, made for reusable combinators — {@code gate.and(fieldNotFilled(field))} instead of a
 * handle-method chain. Every factory takes <em>model-resolved handles</em> ({@link FieldRef},
 * {@link StringRef}, …), never string paths: model-awareness stays mandatory (§5a) because handles
 * only come from {@link BoundModel}. Grows with the operator catalog.
 *
 * <p><b>This file is intentionally a long, flat facade</b> — one delegating factory per operator, not a
 * candidate for splitting (a single static import is the whole point; see ARCHITECTURE §2). Read it by
 * section; the sections, in file order, are:
 * <ol>
 *   <li>presence &amp; pattern — {@code fieldFilled}, {@code matches}, {@code length}</li>
 *   <li>filled-count aggregates — {@code numberOfFilledFields}, {@code sum}, {@code maxValue}, rounding-of-field</li>
 *   <li>operand-list extrema/math &amp; rounding — {@code abs}, {@code min}/{@code max}, {@code roundDown}/{@code roundUp}</li>
 *   <li>date/time family — {@code today}, {@code dateValue}, differences, extractions, constructions, shifts</li>
 *   <li>string/number coercions — {@code text}, {@code fieldValueAsString}, {@code rangeAsNumber}, {@code customCondition}</li>
 *   <li>repetition &amp; value-in-field counts — {@code currentRepetition}, {@code numberOfValueInFields}</li>
 *   <li>field-list &amp; value-list quantifiers — {@code atLeastOneFieldValueIncludedInValueList}, {@code dateRangesOverlap}</li>
 *   <li>field-list fill quantifiers — {@code allFieldsFilled}, {@code noFieldFilled}, … (varargs, list, and {@code Group*} scopes)</li>
 *   <li>localized message tokens (F2a) — {@code en}/{@code de}/{@code text}, {@code fieldValue}, {@code fieldLabel}, {@code repetitionIndex}</li>
 *   <li>n-ary composition — {@code allOf}/{@code anyOf}</li>
 *   <li>group predicates — {@code groupFilled}, {@code allGroupsFilled}, …</li>
 * </ol>
 */
public final class Dsl {

    /** The kernel's date-constant form (German {@code dd.MM.yyyy}); see {@link DateLiteral}. */
    private static final DateTimeFormatter DATE_LITERAL_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** The kernel's time-constant form ({@code HH:mm:ss}); see {@link TimeLiteral}. */
    private static final DateTimeFormatter TIME_LITERAL_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Dsl() {
    }

    /** {@code FieldFilled(field)} — the field currently holds a value; the factory peer of {@link FieldRef#filled}. catalog:FieldFilled */
    public static Condition fieldFilled(FieldRef field) {
        return new FieldPresenceCondition(field, true);
    }

    /** {@code FieldNotFilled(field)} — the field is empty; the factory peer of {@link FieldRef#notFilled}. catalog:FieldNotFilled */
    public static Condition fieldNotFilled(FieldRef field) {
        return new FieldPresenceCondition(field, false);
    }

    /** {@code Length(field)} as a comparable numeric expression. catalog:Length */
    public static NumberExpr length(StringRef field) {
        return field.length();
    }

    /** {@code [field] PatternMatched "regex"} — anchored whole-value match; the factory peer of {@link StringRef#matches}. catalog:PatternMatched */
    public static Condition matches(StringRef field, String regex) {
        return field.matches(regex);
    }

    /** {@code [field] PatternViolated "regex"} — anchored whole-value non-match; the factory peer of {@link StringRef#violates}. catalog:PatternViolated */
    public static Condition violates(StringRef field, String regex) {
        return field.violates(regex);
    }

    // --- filled-count aggregates — comparable numeric expressions ------------------------------

    /**
     * {@code NumberOfFilledFields(f1, f2, …)} — ≥2 fields by signature; kinds may be mixed.
     * Operands may be flattened repetition lists ({@link BoundModel#flatten}). catalog:NumberOfFilledFields
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

    /** {@code Sum(…)} over number fields/repetition lists — unspecified fields count as 0 (§9). catalog:Sum */
    public static NumberExpr sum(FieldOperand first, FieldOperand... rest) {
        return listAggregate(ListAggregate.AggOp.SUM, first, rest);
    }

    /** {@code SumOfProducts(a, b)} — Σ aᵢ·bᵢ over two parallel number field lists. catalog:SumOfProducts */
    public static NumberExpr sumOfProducts(FieldOperand left, FieldOperand right) {
        return new NumberExpr(new SumOfProducts(left, right));
    }

    /** {@code RoundDownValue(field)} — floor the field value to zero decimal places. catalog:RoundDownValue */
    public static NumberExpr roundDownValue(NumberRef field) {
        return NumberExpr.fixedScale(new FieldRounding(FieldRounding.Mode.ROUND_DOWN, field.field()), 0);
    }

    /** {@code RoundDownValue(field, n)} — floor the field value to {@code n} decimal places ({@code 0..14}). catalog:RoundDownValue */
    public static NumberExpr roundDownValue(NumberRef field, int decimalPlaces) {
        return NumberExpr.fixedScale(
                new FieldRounding(FieldRounding.Mode.ROUND_DOWN, field.field(), decimalPlaces), decimalPlaces);
    }

    /** {@code RoundUpValue(field)} — ceiling the field value to zero decimal places. catalog:RoundUpValue */
    public static NumberExpr roundUpValue(NumberRef field) {
        return NumberExpr.fixedScale(new FieldRounding(FieldRounding.Mode.ROUND_UP, field.field()), 0);
    }

    /** {@code RoundUpValue(field, n)} — ceiling the field value to {@code n} decimal places ({@code 0..14}). catalog:RoundUpValue */
    public static NumberExpr roundUpValue(NumberRef field, int decimalPlaces) {
        return NumberExpr.fixedScale(
                new FieldRounding(FieldRounding.Mode.ROUND_UP, field.field(), decimalPlaces), decimalPlaces);
    }

    /** {@code RoundAccountingValue(field)} — commercial rounding to zero decimal places. catalog:RoundAccountingValue */
    public static NumberExpr roundAccountingValue(NumberRef field) {
        return NumberExpr.fixedScale(new FieldRounding(FieldRounding.Mode.ROUND_ACCOUNTING, field.field()), 0);
    }

    /** {@code RoundAccountingValue(field, n)} — commercial rounding to {@code n} decimal places ({@code 0..14}). catalog:RoundAccountingValue */
    public static NumberExpr roundAccountingValue(NumberRef field, int decimalPlaces) {
        return NumberExpr.fixedScale(
                new FieldRounding(FieldRounding.Mode.ROUND_ACCOUNTING, field.field(), decimalPlaces), decimalPlaces);
    }

    /** {@code AbsValue(field)} — the absolute value of the field. catalog:AbsValue */
    public static NumberExpr absValue(NumberRef field) {
        return NumberExpr.field(new FieldRounding(FieldRounding.Mode.ABS, field.field()), field.scale());
    }

    /** {@code MaxValue(…)} over number fields/repetition lists — unspecified fields are ignored. catalog:MaxValue */
    public static NumberExpr maxValue(FieldOperand first, FieldOperand... rest) {
        return listAggregate(ListAggregate.AggOp.MAX_VALUE, first, rest);
    }

    /** {@code MinValue(…)} over number fields/repetition lists — unspecified fields are ignored. catalog:MinValue */
    public static NumberExpr minValue(FieldOperand first, FieldOperand... rest) {
        return listAggregate(ListAggregate.AggOp.MIN_VALUE, first, rest);
    }

    /** {@code MaxValue(…)} over a date repetition list — the latest specified date (a date operand). catalog:MaxValue */
    public static DateExpr latestOf(FieldListRef dateScope) {
        return new DateExpr(new DateListAggregate(DateListAggregate.Mode.LATEST, dateScope));
    }

    /** {@code MinValue(…)} over a date repetition list — the earliest specified date (a date operand). catalog:MinValue */
    public static DateExpr earliestOf(FieldListRef dateScope) {
        return new DateExpr(new DateListAggregate(DateListAggregate.Mode.EARLIEST, dateScope));
    }

    /** {@code MaxValue(…)} over a TIME repetition list — the latest time-of-day (a time operand). catalog:MaxValue */
    public static TimeExpr latestTimeOf(FieldListRef timeScope) {
        return new TimeExpr(new DateListAggregate(DateListAggregate.Mode.LATEST, timeScope));
    }

    /** {@code MinValue(…)} over a TIME repetition list — the earliest time-of-day (a time operand). catalog:MinValue */
    public static TimeExpr earliestTimeOf(FieldListRef timeScope) {
        return new TimeExpr(new DateListAggregate(DateListAggregate.Mode.EARLIEST, timeScope));
    }

    /** {@code MaxValue(…)} over a DATETIME repetition list — the latest instant (a date-time operand). catalog:MaxValue */
    public static DateTimeExpr latestDateTimeOf(FieldListRef dateTimeScope) {
        return new DateTimeExpr(new DateListAggregate(DateListAggregate.Mode.LATEST, dateTimeScope));
    }

    /** {@code MinValue(…)} over a DATETIME repetition list — the earliest instant (a date-time operand). catalog:MinValue */
    public static DateTimeExpr earliestDateTimeOf(FieldListRef dateTimeScope) {
        return new DateTimeExpr(new DateListAggregate(DateListAggregate.Mode.EARLIEST, dateTimeScope));
    }

    /** {@code FirstFilledValue(…)} over a number repetition list — the first specified value, as a number. catalog:FirstFilledValue */
    public static NumberExpr firstFilledNumber(FieldListRef numberScope) {
        requireKind(numberScope, io.github.mbackschat.a12.dm.core.ValueKind.NUMBER, "firstFilledNumber");
        return new NumberExpr(new FirstFilledValue(numberScope));
    }

    /** Multi-operand {@code FirstFilledValue}: each later number field/list is visited only if no earlier operand supplied the result. */
    public static NumberExpr firstFilledNumber(FieldListRef numberScope, FieldOperand second, FieldOperand... rest) {
        return new NumberExpr(firstFilledValue(numberScope, ValueKind.NUMBER, "firstFilledNumber", second, rest));
    }

    /** {@code FirstFilledValue(…)} over a date repetition list — the first specified value, as a date. catalog:FirstFilledValue */
    public static DateExpr firstFilledDate(FieldListRef dateScope) {
        requireKind(dateScope, io.github.mbackschat.a12.dm.core.ValueKind.DATE, "firstFilledDate");
        return new DateExpr(new FirstFilledValue(dateScope));
    }

    /** Multi-operand {@code FirstFilledValue}: each later date field/list is visited only if no earlier operand supplied the result. */
    public static DateExpr firstFilledDate(FieldListRef dateScope, FieldOperand second, FieldOperand... rest) {
        return new DateExpr(firstFilledValue(dateScope, ValueKind.DATE, "firstFilledDate", second, rest));
    }

    /** {@code NumberOfFilledGroups(/RootGroup*)} — the count of a repeatable root group's filled repetitions. catalog:NumberOfFilledGroups */
    public static NumberExpr numberOfFilledRootGroups(GroupRef rootGroup) {
        return new NumberExpr(new RootGroupCount(rootGroup));
    }

    /** {@code FirstFilledValue(…)} over a string repetition list — the first specified value, as a string. catalog:FirstFilledValue */
    public static StringExpr firstFilledString(FieldListRef stringScope) {
        requireKind(stringScope, io.github.mbackschat.a12.dm.core.ValueKind.STRING, "firstFilledString");
        return new StringExpr(new FirstFilledValue(stringScope));
    }

    /** Multi-operand {@code FirstFilledValue}: each later string field/list is visited only if no earlier operand supplied the result. */
    public static StringExpr firstFilledString(FieldListRef stringScope, FieldOperand second, FieldOperand... rest) {
        return new StringExpr(firstFilledValue(stringScope, ValueKind.STRING, "firstFilledString", second, rest));
    }

    /** {@code FirstFilledValue(…)} over an enum repetition list — the first specified value, as an enum. catalog:FirstFilledValue */
    public static EnumExpr firstFilledEnum(FieldListRef enumScope) {
        requireKind(enumScope, io.github.mbackschat.a12.dm.core.ValueKind.ENUMERATION, "firstFilledEnum");
        return new EnumExpr(new FirstFilledValue(enumScope));
    }

    /** Multi-operand {@code FirstFilledValue}: each later enum field/list is visited only if no earlier operand supplied the result. */
    public static EnumExpr firstFilledEnum(FieldListRef enumScope, FieldOperand second, FieldOperand... rest) {
        return new EnumExpr(firstFilledValue(enumScope, ValueKind.ENUMERATION, "firstFilledEnum", second, rest));
    }

    private static void requireKind(FieldListRef scope, io.github.mbackschat.a12.dm.core.ValueKind expected,
            String method) {
        if (scope.kind() != expected) {
            throw new IllegalArgumentException(method + " needs a " + expected + " field list; "
                    + scope.renderedPath() + " is " + scope.kind());
        }
    }

    private static FirstFilledValue firstFilledValue(FieldListRef scope, ValueKind expected, String method,
            FieldOperand second, FieldOperand... rest) {
        requireKind(scope, expected, method);
        var trailing = new ArrayList<FieldOperand>();
        trailing.add(second);
        trailing.addAll(List.of(rest));
        for (FieldOperand operand : trailing) {
            if (operand.kind() != expected) {
                throw new IllegalArgumentException(method + " needs " + expected + " operands; "
                        + operand.renderedPath() + " is " + operand.kind());
            }
        }
        return new FirstFilledValue(scope, trailing);
    }

    private static NumberExpr listAggregate(ListAggregate.AggOp op, FieldOperand first, FieldOperand... rest) {
        var operands = new java.util.ArrayList<FieldOperand>();
        operands.add(first);
        operands.addAll(java.util.List.of(rest));
        return new NumberExpr(new ListAggregate(op, operands));
    }

    // --- operand-list extrema / numeric math ----------------------------------------------------

    /** {@code Abs(value)} — the absolute value of a numeric expression (e.g. {@code Abs([A] - [B])}). catalog:Abs */
    public static NumberExpr abs(NumberExpr value) {
        return new NumberExpr(new OpListMath(OpListMath.Op.ABS, List.of(value.expression())));
    }

    /** {@code Min(v1, v2, …)} — the minimum of one or more numeric expressions. catalog:Min */
    public static NumberExpr min(NumberExpr first, NumberExpr... rest) {
        return new NumberExpr(new OpListMath(OpListMath.Op.MIN, operandList(first, rest)));
    }

    /** {@code Max(v1, v2, …)} — the maximum of one or more numeric expressions. catalog:Max */
    public static NumberExpr max(NumberExpr first, NumberExpr... rest) {
        return new NumberExpr(new OpListMath(OpListMath.Op.MAX, operandList(first, rest)));
    }

    /**
     * {@code Min(d1, d2, …)} over <b>date</b> operands — the earliest of one or more dates, as a
     * comparable {@link DateExpr}. The kernel evaluates it through its {@code DateCombiner}
     * ({@code minDatum}, KERNEL-FINDINGS §6); a valueless operand is skipped. Number/date mixing is
     * impossible: the operands are {@link DateOperand}s, so the result compares only to date operands.
     */
    public static DateExpr min(DateOperand first, DateOperand... rest) {
        return new DateExpr(new OpListMath(OpListMath.Op.MIN, temporalOperandList(first, rest)));
    }

    /** {@code Max(d1, d2, …)} over date operands — the latest of one or more dates ({@code maxDatum}). */
    public static DateExpr max(DateOperand first, DateOperand... rest) {
        return new DateExpr(new OpListMath(OpListMath.Op.MAX, temporalOperandList(first, rest)));
    }

    /** {@code Min(t1, t2, …)} over time operands — the earliest time-of-day ({@code minDatum}). */
    public static TimeExpr min(TimeOperand first, TimeOperand... rest) {
        return new TimeExpr(new OpListMath(OpListMath.Op.MIN, temporalOperandList(first, rest)));
    }

    /** {@code Max(t1, t2, …)} over time operands — the latest time-of-day ({@code maxDatum}). */
    public static TimeExpr max(TimeOperand first, TimeOperand... rest) {
        return new TimeExpr(new OpListMath(OpListMath.Op.MAX, temporalOperandList(first, rest)));
    }

    /** {@code Min(dt1, dt2, …)} over date-time operands — the earliest instant ({@code minDatum}). */
    public static DateTimeExpr min(DateTimeOperand first, DateTimeOperand... rest) {
        return new DateTimeExpr(new OpListMath(OpListMath.Op.MIN, temporalOperandList(first, rest)));
    }

    /** {@code Max(dt1, dt2, …)} over date-time operands — the latest instant ({@code maxDatum}). */
    public static DateTimeExpr max(DateTimeOperand first, DateTimeOperand... rest) {
        return new DateTimeExpr(new OpListMath(OpListMath.Op.MAX, temporalOperandList(first, rest)));
    }

    private static List<Calculation> operandList(NumberExpr first, NumberExpr... rest) {
        var ops = new ArrayList<Calculation>();
        ops.add(first.expression());
        for (NumberExpr e : rest) {
            ops.add(e.expression());
        }
        return ops;
    }

    private static List<Calculation> temporalOperandList(TemporalOperand first, TemporalOperand... rest) {
        var ops = new ArrayList<Calculation>();
        ops.add(first.expression());
        for (TemporalOperand e : rest) {
            ops.add(e.expression());
        }
        return ops;
    }

    // --- rounding (operand-list, over an arbitrary numeric expression) -------------------------

    /** {@code RoundDown(expr)} — floor a numeric expression to zero decimal places. catalog:RoundDown */
    public static NumberExpr roundDown(NumberExpr operand) {
        return NumberExpr.fixedScale(new Rounding(Rounding.Mode.DOWN, operand.expression()), 0);
    }

    /** {@code RoundDown(expr, n)} — floor a numeric expression to {@code n} decimal places ({@code 0..14}). catalog:RoundDown */
    public static NumberExpr roundDown(NumberExpr operand, int decimalPlaces) {
        return NumberExpr.fixedScale(
                new Rounding(Rounding.Mode.DOWN, operand.expression(), decimalPlaces), decimalPlaces);
    }

    /** {@code RoundUp(expr)} — ceiling a numeric expression to zero decimal places. catalog:RoundUp */
    public static NumberExpr roundUp(NumberExpr operand) {
        return NumberExpr.fixedScale(new Rounding(Rounding.Mode.UP, operand.expression()), 0);
    }

    /** {@code RoundUp(expr, n)} — ceiling a numeric expression to {@code n} decimal places ({@code 0..14}). catalog:RoundUp */
    public static NumberExpr roundUp(NumberExpr operand, int decimalPlaces) {
        return NumberExpr.fixedScale(
                new Rounding(Rounding.Mode.UP, operand.expression(), decimalPlaces), decimalPlaces);
    }

    /** {@code RoundAccounting(expr)} — commercial rounding to zero decimal places. catalog:RoundAccounting */
    public static NumberExpr roundAccounting(NumberExpr operand) {
        return NumberExpr.fixedScale(new Rounding(Rounding.Mode.ACCOUNTING, operand.expression()), 0);
    }

    /** {@code RoundAccounting(expr, n)} — commercial rounding to {@code n} decimal places ({@code 0..14}). catalog:RoundAccounting */
    public static NumberExpr roundAccounting(NumberExpr operand, int decimalPlaces) {
        return NumberExpr.fixedScale(
                new Rounding(Rounding.Mode.ACCOUNTING, operand.expression(), decimalPlaces), decimalPlaces);
    }

    // --- date family (item 9) — Today, differences (→ number), shifts (→ date) ----------------

    /** {@code Today} — the current date constant (DE {@code Heute}). catalog:Today */
    public static DateExpr today() {
        return new DateExpr(new Today());
    }

    /** {@code Now} — the current date-time (DE {@code Jetzt}); the date-time sibling of {@link #today()}. catalog:Now */
    public static DateTimeExpr now() {
        return new DateTimeExpr(new Now());
    }

    /**
     * {@code BaseYear} — the model's configured base year (DE {@code Basisjahr}), as a comparable date
     * (the start of the base year). Only valid in a model declaring a base year. To compare it as the
     * year <em>number</em>, use {@link #baseYearNumber()}. catalog:BaseYear
     */
    public static DateExpr baseYear() {
        return new DateExpr(new BaseYear());
    }

    /** {@code BaseYear} as the year <em>number</em> — for comparisons, arithmetic, computations, and numeric wrappers. catalog:BaseYear */
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

    /** {@code DifferenceInDays(a, b)} over dates — signed complete model-zone legacy-calendar day steps. catalog:DifferenceInDays */
    public static NumberExpr differenceInDays(DateOperand a, DateOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.DAYS, a.expression(), b.expression()));
    }

    /** Mixed date/date-time {@code DifferenceInDays}; a date is model-zone midnight. */
    public static NumberExpr differenceInDays(DateOperand a, DateTimeOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.DAYS, a.expression(), b.expression()));
    }

    /** Mixed date-time/date {@code DifferenceInDays}; a date is model-zone midnight. */
    public static NumberExpr differenceInDays(DateTimeOperand a, DateOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.DAYS, a.expression(), b.expression()));
    }

    /** Date-time {@code DifferenceInDays}; clocks participate in complete calendar-step counting. */
    public static NumberExpr differenceInDays(DateTimeOperand a, DateTimeOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.DAYS, a.expression(), b.expression()));
    }

    /** {@code DifferenceInMonths(a, b)} — the whole-months date difference, as a number (a floor, not rounded). catalog:DifferenceInMonths */
    public static NumberExpr differenceInMonths(DateOperand a, DateOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.MONTHS, a.expression(), b.expression()));
    }

    /** {@code DifferenceInYears(a, b)} — the whole-years date difference, as a number (a floor, not rounded). catalog:DifferenceInYears */
    public static NumberExpr differenceInYears(DateOperand a, DateOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.YEARS, a.expression(), b.expression()));
    }

    /** {@code DifferenceInHours(dt1, dt2)} — the whole-hours difference between two date-times, as a number. catalog:DifferenceInHours */
    public static NumberExpr differenceInHours(DateTimeOperand a, DateTimeOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.HOURS, a.expression(), b.expression()));
    }

    /** {@code DifferenceInMinutes(dt1, dt2)} — the whole-minutes difference between two date-times. catalog:DifferenceInMinutes */
    public static NumberExpr differenceInMinutes(DateTimeOperand a, DateTimeOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.MINUTES, a.expression(), b.expression()));
    }

    /** {@code DifferenceInSeconds(dt1, dt2)} — the whole-seconds difference between two date-times. catalog:DifferenceInSeconds */
    public static NumberExpr differenceInSeconds(DateTimeOperand a, DateTimeOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.SECONDS, a.expression(), b.expression()));
    }

    /** {@code YearFromDate(date)} — the year component of a date as a number. catalog:YearFromDate */
    public static NumberExpr yearFromDate(DateOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.YEAR, date.expression()));
    }

    /** {@code YearFromDate(dateTime)} — the year component of a date-time as a number. */
    public static NumberExpr yearFromDate(DateTimeOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.YEAR, date.expression()));
    }

    /** {@code MonthFromDate(date)} — the month component (1-12) as a number. catalog:MonthFromDate */
    public static NumberExpr monthFromDate(DateOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.MONTH, date.expression()));
    }

    /** {@code MonthFromDate(dateTime)} — the month component (1-12) of a date-time. */
    public static NumberExpr monthFromDate(DateTimeOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.MONTH, date.expression()));
    }

    /** {@code DayFromDate(date)} — the day-of-month component as a number. catalog:DayFromDate */
    public static NumberExpr dayFromDate(DateOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.DAY, date.expression()));
    }

    /** {@code DayFromDate(dateTime)} — the day-of-month component of a date-time. */
    public static NumberExpr dayFromDate(DateTimeOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.DAY, date.expression()));
    }

    /** {@code QuarterFromDate(date)} — the calendar quarter (1-4) as a number. catalog:QuarterFromDate */
    public static NumberExpr quarterFromDate(DateOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.QUARTER, date.expression()));
    }

    /** {@code QuarterFromDate(dateTime)} — the calendar quarter (1-4) of a date-time. */
    public static NumberExpr quarterFromDate(DateTimeOperand date) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.QUARTER, date.expression()));
    }

    /** {@code HoursFromTime(time)} — the hour-of-day component (0-23) of a time (or date-time) as a number. catalog:HoursFromTime */
    public static NumberExpr hoursFromTime(TimeOperand time) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.HOURS, time.expression()));
    }

    /** {@code HoursFromTime(dateTime)} — the hour-of-day component (0-23) of a date-time. */
    public static NumberExpr hoursFromTime(DateTimeOperand time) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.HOURS, time.expression()));
    }

    /** {@code MinutesFromTime(time)} — the minute component (0-59) as a number. catalog:MinutesFromTime */
    public static NumberExpr minutesFromTime(TimeOperand time) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.MINUTES, time.expression()));
    }

    /** {@code MinutesFromTime(dateTime)} — the minute component (0-59) of a date-time. */
    public static NumberExpr minutesFromTime(DateTimeOperand time) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.MINUTES, time.expression()));
    }

    /** {@code SecondsFromTime(time)} — the second component (0-59) as a number. catalog:SecondsFromTime */
    public static NumberExpr secondsFromTime(TimeOperand time) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.SECONDS, time.expression()));
    }

    /** {@code SecondsFromTime(dateTime)} — the second component (0-59) of a date-time. */
    public static NumberExpr secondsFromTime(DateTimeOperand time) {
        return new NumberExpr(new DateExtraction(DateExtraction.Unit.SECONDS, time.expression()));
    }

    /** {@code Date(day, month, year)} — construct a date from numeric components (a comparable date). catalog:Date */
    public static DateExpr date(NumberExpr day, NumberExpr month, NumberExpr year) {
        return new DateExpr(new DateConstruction(day.expression(), month.expression(), year.expression()));
    }

    /** {@code Date(day, month, century, decade)} — a date whose year is split into century + 2-digit decade. */
    public static DateExpr date(NumberExpr day, NumberExpr month, NumberExpr century, NumberExpr decade) {
        return new DateExpr(new DateConstruction(day.expression(), month.expression(),
                century.expression(), java.util.Optional.of(decade.expression())));
    }

    /** {@code Time()} — midnight: every omitted trailing component defaults to zero. catalog:Time */
    public static TimeExpr time() {
        return new TimeExpr(new TimeConstruction(List.of()));
    }

    /** {@code Time("10")} — 10:00:00; omitted minute and second default to zero. catalog:Time */
    public static TimeExpr time(String hour) {
        return new TimeExpr(new TimeConstruction(List.of(new StringLiteral(hour))));
    }

    /** {@code Time("10", "30")} — 10:30:00; the omitted second defaults to zero. catalog:Time */
    public static TimeExpr time(String hour, String minute) {
        return new TimeExpr(new TimeConstruction(List.of(
                new StringLiteral(hour), new StringLiteral(minute))));
    }

    /**
     * {@code Time("10", "30", "50")} — construct a time-of-day from hour/minute/second constants (a
     * comparable time). The kernel takes the components as quoted constants here (a bracketed value
     * {@code [H]} is rejected); for field components use {@link #time(FieldRef, FieldRef, FieldRef)}. catalog:Time
     */
    public static TimeExpr time(String hour, String minute, String second) {
        return new TimeExpr(new TimeConstruction(List.of(
                new StringLiteral(hour), new StringLiteral(minute), new StringLiteral(second))));
    }

    /** {@code Time(H)} — construct an hour with minute/second zero from a bare number field. catalog:Time */
    public static TimeExpr time(FieldRef hour) {
        return new TimeExpr(new TimeConstruction(List.of(new FieldValue(hour))));
    }

    /** {@code Time(H, M)} — construct hour/minute with second zero from bare number fields. catalog:Time */
    public static TimeExpr time(FieldRef hour, FieldRef minute) {
        return new TimeExpr(new TimeConstruction(List.of(new FieldValue(hour), new FieldValue(minute))));
    }

    /** {@code Time(H, M, S)} — construct a time from three bare number fields. catalog:Time */
    public static TimeExpr time(FieldRef hour, FieldRef minute, FieldRef second) {
        return new TimeExpr(new TimeConstruction(List.of(
                new FieldValue(hour), new FieldValue(minute), new FieldValue(second))));
    }

    /** {@code DateTime(date, time)} — combine a date and a time into a comparable date-time. catalog:DateTime */
    public static DateTimeExpr dateTime(DateOperand date, TimeOperand time) {
        return new DateTimeExpr(new DateTimeConstruction(date.expression(), time.expression()));
    }

    /**
     * {@code DateRange(start, end)} — construct a date-range value from two dates (the kernel's
     * {@code IDateRangeConstructionOperation}). Computation-only: its sole legal home is a computation
     * operation assigning a {@code DATE_RANGE} field (it has no condition use site — see
     * {@link DateRangeConstruction}). catalog:DateRange
     */
    public static DateRangeConstruction dateRange(DateOperand start, DateOperand end) {
        return new DateRangeConstruction(start.expression(), end.expression());
    }

    /** {@code Valid(dateConstruct)} — true iff the constructed date is complete and real. Its strong-Kleene
     *  truth is the complement of {@link #invalid(DateOperand)}; full error polarity is not. catalog:Valid */
    public static Condition valid(DateOperand dateConstruct) {
        return new DateValidity(true, dateConstruct.expression());
    }

    /** {@code Invalid(dateConstruct)} — true for an unreal or incomplete construction; incomplete yields
     *  OMISSION while present-but-unreal yields VALUE, and malformed stays Unknown. catalog:Invalid */
    public static Condition invalid(DateOperand dateConstruct) {
        return new DateValidity(false, dateConstruct.expression());
    }

    /**
     * {@code Valid(field, "TypeName")} — true iff {@code field}'s value is a valid instance of the
     * predefined custom field type {@code typeName} (e.g. {@code "IBAN"}, {@code "Steuernummer"}). The
     * field must be STRING or ENUMERATION. <b>The type name is not validated at model-check time</b> — the
     * predefined types are a runtime client-SPI, so a wrong name only fails at evaluation (see
     * {@link CustomTypeValidity}). catalog:ValidType
     */
    public static Condition validType(FieldRef field, String typeName) {
        return customTypeValidity(true, field, typeName);
    }

    /** {@code Invalid(field, "TypeName")} — the negation of {@link #validType} (the field is NOT a valid {@code typeName}). catalog:InvalidType */
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

    /** {@code StartOfDateRange(range)} — the start bound of a date-range, as a comparable date. catalog:StartOfDateRange */
    public static DateExpr startOfDateRange(DateRangeRef range) {
        return new DateExpr(new DateRangeBound(DateRangeBound.Bound.START, range.expression()));
    }

    /** {@code EndOfDateRange(range)} — the end bound of a date-range, as a comparable date. catalog:EndOfDateRange */
    public static DateExpr endOfDateRange(DateRangeRef range) {
        return new DateExpr(new DateRangeBound(DateRangeBound.Bound.END, range.expression()));
    }

    /**
     * {@code StartOfDateRange(BaseYear)} — 01.01 of the model's base year, as a comparable date. The kernel
     * treats {@code BaseYear} as a date range spanning the reference year, so it is the one point-in-time
     * usable as a date-range source (needs a declared base year, else {@code MVK_NO_BASE_YEAR}). catalog:StartOfDateRange
     */
    public static DateExpr startOfBaseYear() {
        return new DateExpr(new DateRangeBound(DateRangeBound.Bound.START, new BaseYear()));
    }

    /** {@code EndOfDateRange(BaseYear)} — 31.12 of the model's base year, as a comparable date. catalog:EndOfDateRange */
    public static DateExpr endOfBaseYear() {
        return new DateExpr(new DateRangeBound(DateRangeBound.Bound.END, new BaseYear()));
    }

    /**
     * {@code ValueAsDate(field, FirstDay)} — the earliest concrete date of a partially known date field
     * (a {@code DateType} with a date precision other than FULL), as a comparable date. catalog:ValueAsDate
     */
    public static DateExpr valueAsDateFirstDay(FieldRef partiallyKnownDate) {
        return new DateExpr(new ValueAsDate(partiallyKnownDate, true));
    }

    /** {@code ValueAsDate(field, LastDay)} — the latest concrete date of a partially known date field. catalog:ValueAsDate */
    public static DateExpr valueAsDateLastDay(FieldRef partiallyKnownDate) {
        return new DateExpr(new ValueAsDate(partiallyKnownDate, false));
    }

    /** {@code DateFromDateTime(dt)} — the date part of a date-time, as a comparable date. Accepts any
     *  {@link DateTimeOperand}: a date-time field OR a {@code DateTime(date, time)} construction (DG9). catalog:DateFromDateTime */
    public static DateExpr dateFromDateTime(DateTimeOperand dateTime) {
        return new DateExpr(new DateTimePart(DateTimePart.Unit.DATE, dateTime.expression()));
    }

    /** {@code TimeFromDateTime(dt)} — the time-of-day part of a date-time, as a comparable time. Accepts any
     *  {@link DateTimeOperand}: a date-time field OR a {@code DateTime(date, time)} construction (DG9). catalog:TimeFromDateTime */
    public static TimeExpr timeFromDateTime(DateTimeOperand dateTime) {
        return new TimeExpr(new DateTimePart(DateTimePart.Unit.TIME, dateTime.expression()));
    }

    /** {@code AddDays(date, n)} — shifts a date by an integer constant, yielding a date (comparable). catalog:AddDays */
    public static DateExpr addDays(DateOperand date, long amount) {
        return addDays(date, number(Long.toString(amount)));
    }

    /** {@code AddDays(dateTime, n)} — shifts a date-time by whole wall days while preserving its date-time type. */
    public static DateTimeExpr addDays(DateTimeOperand dateTime, long amount) {
        return addDays(dateTime, number(Long.toString(amount)));
    }

    /** {@code AddMonths(date, n)} — shifts a date by an integer constant number of months, yielding a date. catalog:AddMonths */
    public static DateExpr addMonths(DateOperand date, long amount) {
        return addMonths(date, number(Long.toString(amount)));
    }

    /** {@code AddYears(date, n)} — shifts a date by an integer constant number of years, yielding a date. catalog:AddYears */
    public static DateExpr addYears(DateOperand date, long amount) {
        return addYears(date, number(Long.toString(amount)));
    }

    /** {@code AddDays(date, n)} where the amount is a numeric expression (e.g. a field, {@code AddDays(d, [n])}). */
    public static DateExpr addDays(DateOperand date, NumberExpr amount) {
        return new DateExpr(new DateShift(DateShift.Unit.DAYS, date.expression(), amount.expression()));
    }

    /** {@code AddDays(dateTime, n)} with a numeric expression, preserving the date-time result type. */
    public static DateTimeExpr addDays(DateTimeOperand dateTime, NumberExpr amount) {
        return new DateTimeExpr(new DateShift(DateShift.Unit.DAYS, dateTime.expression(), amount.expression()));
    }

    public static DateExpr addMonths(DateOperand date, NumberExpr amount) {
        return new DateExpr(new DateShift(DateShift.Unit.MONTHS, date.expression(), amount.expression()));
    }

    public static DateExpr addYears(DateOperand date, NumberExpr amount) {
        return new DateExpr(new DateShift(DateShift.Unit.YEARS, date.expression(), amount.expression()));
    }

    /** {@code AddHours(dateTime, n)} — shifts a date-time by {@code n} hours, yielding a **date-time**
     *  ({@code n} may be negative). Returns a {@link DateTimeExpr} (a sub-day shift is a date-time, matching
     *  {@code DateShift.Unit.HOURS}'s declared kind), so it composes as a date-time operand — comparisons AND
     *  {@code dateFromDateTime}/{@code timeFromDateTime} (DG9). catalog:AddHours */
    public static DateTimeExpr addHours(DateTimeOperand dateTime, long amount) {
        return addHours(dateTime, number(Long.toString(amount)));
    }

    /** {@code AddMinutes(dateTime, n)} — shifts a date-time by an integer constant number of minutes ({@code n} may be negative). catalog:AddMinutes */
    public static DateTimeExpr addMinutes(DateTimeOperand dateTime, long amount) {
        return addMinutes(dateTime, number(Long.toString(amount)));
    }

    /** {@code AddSeconds(dateTime, n)} — shifts a date-time by an integer constant number of seconds ({@code n} may be negative). catalog:AddSeconds */
    public static DateTimeExpr addSeconds(DateTimeOperand dateTime, long amount) {
        return addSeconds(dateTime, number(Long.toString(amount)));
    }

    /** {@code AddHours(dateTime, n)} where the amount is a numeric expression (yields a date-time — DG9). */
    public static DateTimeExpr addHours(DateTimeOperand dateTime, NumberExpr amount) {
        return new DateTimeExpr(new DateShift(DateShift.Unit.HOURS, dateTime.expression(), amount.expression()));
    }

    public static DateTimeExpr addMinutes(DateTimeOperand dateTime, NumberExpr amount) {
        return new DateTimeExpr(new DateShift(DateShift.Unit.MINUTES, dateTime.expression(), amount.expression()));
    }

    public static DateTimeExpr addSeconds(DateTimeOperand dateTime, NumberExpr amount) {
        return new DateTimeExpr(new DateShift(DateShift.Unit.SECONDS, dateTime.expression(), amount.expression()));
    }

    /** A numeric literal as a comparable expression — e.g. an arithmetic operand ({@code … + 2}). */
    public static NumberExpr number(String literal) {
        return new NumberExpr(NumberLiteral.of(literal));
    }

    /** A whole-number literal as a comparable expression — the typed peer of {@link #number(String)}
     *  (e.g. {@code date(number(13), number(1), number(2022))}). */
    public static NumberExpr number(long literal) {
        return new NumberExpr(NumberLiteral.of(literal));
    }

    /** A decimal literal as a comparable expression — scale-faithful, exactly as {@link #number(String)}. */
    public static NumberExpr number(java.math.BigDecimal literal) {
        return new NumberExpr(new NumberLiteral(literal));
    }

    /** A string literal as a string expression — e.g. a concatenation part ({@code first.plus(text(" "))}). */
    public static StringExpr text(String literal) {
        return new StringExpr(new StringLiteral(literal));
    }

    /** {@code FieldValueAsString(field)} — a field's value coerced to its string form, as a string expression. catalog:FieldValueAsString */
    public static StringExpr fieldValueAsString(FieldRef field) {
        return new StringExpr(new FieldValueAsString(field));
    }

    /**
     * {@code FieldValueAsString([Field For key])} — a semantically-indexed cell's value coerced to string,
     * as a string expression. The indexed operand is an {@link SemanticIndex} handle ({@code ref.semanticIndex(key)},
     * literal or field key); the peer of {@link #fieldValueAsString(FieldRef)} for the {@code For}-indexed shape.
     */
    public static StringExpr fieldValueAsString(SemanticIndex cell) {
        return new StringExpr(new IndexedFieldValueAsString(cell.index()));
    }

    /** {@code RangeAsString(field, start, end)} — a substring of a string field, as a string expression. catalog:RangeAsString */
    public static StringExpr rangeAsString(FieldRef field, int start, int end) {
        return new StringExpr(new RangeAsString(field, start, end));
    }

    /** {@code FieldValueAsNumber(field)} — a string/enum field's value coerced to a number (it must always convert). catalog:FieldValueAsNumber */
    public static NumberExpr fieldValueAsNumber(FieldRef field) {
        return new NumberExpr(new FieldValueAsNumber(field));
    }

    /** {@code FieldValueAsNumber(enumRef)} — the enumeration's stored token coerced to a number. catalog:FieldValueAsNumber */
    public static NumberExpr fieldValueAsNumber(EnumRef field) {
        return new NumberExpr(new FieldValueAsNumber(field));
    }

    /** {@code FieldValueAsNumber(field -> category)} — the selected category token coerced to a number. catalog:FieldValueAsNumber */
    public static NumberExpr fieldValueAsNumber(CategoryRef category) {
        return new NumberExpr(new FieldValueAsNumber(category));
    }

    /** {@code RangeAsNumber(field, start, end)} — a substring of a string field read as a number (non-numeric → 0). catalog:RangeAsNumber */
    public static NumberExpr rangeAsNumber(FieldRef field, int start, int end) {
        return new NumberExpr(new RangeAsNumber(field, start, end));
    }

    /** {@code CustomCondition <Name>} — the app-defined escape hatch (KERNEL-SEMANTICS §14). catalog:CustomCondition */
    public static Condition customCondition(String name) {
        return new CustomConditionRef(name);
    }

    /** {@code CurrentRepetition(group)} — the group's current iteration index, as a number expression. catalog:CurrentRepetition */
    public static NumberExpr currentRepetition(GroupRef group) {
        return new NumberExpr(new CurrentRepetition(group));
    }

    /**
     * {@code CurrentRepetition($group)} — the named group's coordinate in the complete <em>outer</em>
     * repetition environment (the {@code $} correlation, D13). Different group paths can select
     * different captured coordinates in one nested outer row; this is not one scalar outer index.
     */
    public static NumberExpr currentRepetition(GroupRef group, boolean outer) {
        return new NumberExpr(new CurrentRepetition(group, outer));
    }

    /**
     * {@code NumberOfValueInFields(value IN f1, f2, …)} — the count of the listed fields equal to a
     * boolean constant (e.g. {@code NumberOfValueInFields(True IN OptInA, OptInB, OptInC)}). The kernel
     * needs at least two fields (or a group). catalog:NumberOfValueInFields
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

    // --- single-list forms: NumberOfValueInFields(value In Group*/field) --------------------------
    // The kernel's arity gate is "at least two fields OR at least one group" (MVK_PARAMSIZE_INVALIDN), so ONE
    // flattened repetition list (a "group" operand, optionally Having-filtered) is legal on its own — the
    // multi-field overloads above require two operands and cannot express it. F4/N7-arbitrated like those.

    /** {@code NumberOfValueInFields(True In Group*}{@code /field)} — count over a single flattened repetition
     *  list equal to a boolean constant. catalog:NumberOfValueInFields */
    public static NumberExpr numberOfValueInFields(boolean value, FieldListRef list) {
        return new NumberExpr(new NumberOfValueInFields(new BoolLiteral(value), List.<FieldOperand>of(list)));
    }

    /** {@code NumberOfValueInFields(constant In Group*}{@code /field)} — count over a single flattened
     *  repetition list equal to a string constant. catalog:NumberOfValueInFields */
    public static NumberExpr numberOfValueInFields(String value, FieldListRef list) {
        return new NumberExpr(new NumberOfValueInFields(new StringLiteral(value), List.<FieldOperand>of(list)));
    }

    /** {@code NumberOfValueInFields(n In Group*}{@code /field)} — count over a single flattened repetition
     *  list equal to an integer constant. catalog:NumberOfValueInFields */
    public static NumberExpr numberOfValueInFields(long value, FieldListRef list) {
        return new NumberExpr(new NumberOfValueInFields(NumberLiteral.of(value), List.<FieldOperand>of(list)));
    }

    private static List<FieldOperand> valueInFields(FieldOperand first, FieldOperand second, FieldOperand... rest) {
        var fields = new ArrayList<FieldOperand>();
        fields.add(first);
        fields.add(second);
        fields.addAll(List.of(rest));
        return fields;
    }

    /** {@code NumberOfDifferentValues(…)} — distinct-value count over type-homogeneous operands. catalog:NumberOfDifferentValues */
    public static NumberExpr numberOfDifferentValues(FieldOperand first, FieldOperand... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first);
        fields.addAll(java.util.List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.DIFFERENT_VALUES, fields, java.util.List.of()));
    }

    /**
     * {@code NumberOfFilledGroups(group*)} — counts the filled repetitions of one repeatable group (the
     * wildcard scope). The group must repeat; for distinct groups use the ≥2-group form. catalog:NumberOfFilledGroups
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

    /** {@code AtLeastOneFieldValueIncludedInValueList(f1, f2 In "v1", "v2")} — at least one listed field's value is in the value list. catalog:AtLeastOneFieldValueIncludedInValueList */
    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    /** {@code NoFieldValueIncludedInValueList(f1, f2 In "v1", "v2")} — none of the listed fields' values is in the value list. catalog:NoFieldValueIncludedInValueList */
    public static Condition noFieldValueIncludedInValueList(
            java.util.List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    /** {@code NotAllFieldValuesIncludedInValueList(f1, f2 In "v1", "v2")} — at least one listed field's value is NOT in the value list. catalog:NotAllFieldValuesIncludedInValueList */
    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, first, rest);
    }

    // Enum field-list value-list quantifiers. Each literal is admitted against the UNION of the selected
    // members' domains (a direct EnumRef contributes its stored values, a CategoryRef its category
    // values) — a literal need not be in every listed domain; a direct ref and a category ref on the
    // same field are distinct members; an exact repeated reference is rejected (KERNEL-FINDINGS §8,
    // SPEC-2026-07-22-05). The EnumRef-only overloads stay as binary-compatible bridges; the EnumListRef
    // overloads add the mixed direct/category authoring surface.

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, enumListRefs(first, rest));
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, enumListRefs(first, rest));
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, enumListRefs(first, rest));
    }

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<String> values, EnumListRef first, EnumListRef... rest) {
        return enumListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, enumListRefs(first, rest));
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<String> values, EnumListRef first, EnumListRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, enumListRefs(first, rest));
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<String> values, EnumListRef first, EnumListRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, enumListRefs(first, rest));
    }

    /** {@code DateRangesOverlap(r1, r2, …)} — true iff the date ranges of the listed fields overlap. catalog:DateRangesOverlap */
    public static Condition dateRangesOverlap(FieldOperand first, FieldOperand... rest) {
        var fields = new java.util.ArrayList<FieldOperand>();
        fields.add(first);
        fields.addAll(java.util.List.of(rest));
        return new FieldListPredicate(Quantifier.DATE_RANGES_OVERLAP, fields);
    }

    /** {@code AtLeastOneDateRangeOverlaps(range IN r1, r2, …)} — {@code range} overlaps at least one listed range. catalog:AtLeastOneDateRangeOverlaps */
    public static Condition atLeastOneDateRangeOverlaps(FieldOperand range, FieldOperand first, FieldOperand... rest) {
        var values = new java.util.ArrayList<ValueListOperand>();
        values.add(first);
        values.addAll(java.util.List.of(rest));
        return new FieldListEntityListCondition(ValueListQuantifier.AT_LEAST_ONE_DATE_RANGE_OVERLAPS,
                java.util.List.<ValueListOperand>of(range), values);
    }

    private static Condition stringListQuantifier(
            ValueListQuantifier q, java.util.List<String> values, StringRef first, StringRef... rest) {
        var fields = new java.util.ArrayList<ValueListOperand>();
        fields.add(first.field());
        for (StringRef r : rest) {
            fields.add(r.field());
        }
        rejectDuplicateReferences(q.operatorId(), fields);
        var literals = values.stream().<Calculation>map(StringLiteral::new).toList();
        return new FieldListValueListCondition(q, fields, literals);
    }

    private static java.util.List<EnumListRef> enumListRefs(EnumListRef first, EnumListRef... rest) {
        var refs = new java.util.ArrayList<EnumListRef>();
        refs.add(first);
        refs.addAll(java.util.List.of(rest));
        return refs;
    }

    private static Condition enumListQuantifier(
            ValueListQuantifier q, java.util.List<String> values, java.util.List<EnumListRef> refs) {
        var operands = new java.util.ArrayList<ValueListOperand>();
        var union = new java.util.LinkedHashSet<String>();
        for (EnumListRef ref : refs) {
            operands.add(ref.listOperand());
            union.addAll(ref.admissibleValues());   // direct → stored values; category → category values
        }
        rejectDuplicateReferences(q.operatorId(), operands);
        var literals = new java.util.ArrayList<Calculation>();
        for (String v : values) {
            if (!union.contains(v)) {
                throw new IllegalArgumentException("'" + v + "' is not in the union of the selected"
                        + " enumeration/category domains " + union
                        + " (MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY)");
            }
            literals.add(new EnumValueLiteral(v));
        }
        return new FieldListValueListCondition(q, operands, literals);
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
        // Starred (flattened) lists are wildcard scopes — the kernel skips duplicate-reference checking
        // for wildcarded refs (CheckEntityListenUtils.checkDuplicatesInDirectRefs), so no dedup here.
        var fields = new java.util.ArrayList<ValueListOperand>();
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
    // `Name(f1, f2 In g1, g2)` where g* are fields/repetition lists/category projections. Both sides must
    // share one base kind (String/Enumeration/Number) and carry no exact-duplicate reference across the
    // combined lists — enum-domain containment is NOT required (KERNEL-FINDINGS §8, SPEC-2026-07-22-05).
    // The FieldOperand overloads stay as binary-compatible bridges; the ValueListOperand overloads add
    // the category-projection surface.

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

    public static Condition atLeastOneFieldValueIncludedInValueList(
            java.util.List<? extends ValueListOperand> valueFields, ValueListOperand first, ValueListOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, valueFields, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            java.util.List<? extends ValueListOperand> valueFields, ValueListOperand first, ValueListOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, valueFields, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            java.util.List<? extends ValueListOperand> valueFields, ValueListOperand first, ValueListOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, valueFields, first, rest);
    }

    private static Condition entityListQuantifier(ValueListQuantifier q,
            java.util.List<? extends ValueListOperand> valueFields, ValueListOperand first, ValueListOperand... rest) {
        var fields = new java.util.ArrayList<ValueListOperand>();
        fields.add(first);
        fields.addAll(java.util.List.of(rest));
        var values = java.util.List.<ValueListOperand>copyOf(valueFields);
        checkEntityValuedKindsAndDuplicates(q.operatorId(), fields, values);
        return new FieldListEntityListCondition(q, fields, values);
    }

    /** The three base kinds the value-list quantifier family accepts (the kernel's String/Enumeration/Number). */
    private enum ValueListBaseKind { STRING, ENUMERATION, NUMBER }

    /** Map a {@link io.github.mbackschat.a12.dm.core.ValueKind} to its value-list base kind, or {@code null} if disallowed. */
    private static ValueListBaseKind baseKind(io.github.mbackschat.a12.dm.core.ValueKind kind) {
        return switch (kind) {
            // the kernel treats predefined/custom types as strings for base-type matching
            case STRING, CUSTOM -> ValueListBaseKind.STRING;
            case ENUMERATION -> ValueListBaseKind.ENUMERATION;
            case NUMBER -> ValueListBaseKind.NUMBER;
            default -> null;
        };
    }

    /**
     * The entity-valued (shape-C) gate the kernel applies: every operand across BOTH sides shares one base
     * kind (String/Enumeration/Number, {@code MVK_VARYING_TYPES_NOT_ALLOWED} / {@code MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED}),
     * and no exact reference occurs twice across the combined lists ({@code MVK_DUPLICATE_PARAM}).
     */
    private static void checkEntityValuedKindsAndDuplicates(String opName,
            java.util.List<ValueListOperand> fields, java.util.List<ValueListOperand> values) {
        var combined = new java.util.ArrayList<ValueListOperand>(fields);
        combined.addAll(values);
        ValueListBaseKind base = null;
        for (ValueListOperand op : combined) {
            ValueListBaseKind k = baseKind(op.kind());
            if (k == null) {
                throw new IllegalArgumentException(opName + " accepts only String, Enumeration, or Number"
                        + " operands; '" + op.renderedPath() + "' is " + op.kind()
                        + " (MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED)");
            }
            if (base == null) {
                base = k;
            } else if (base != k) {
                throw new IllegalArgumentException(opName + " requires all operands (both sides) to share one"
                        + " base kind; found " + base + " and " + k + " (MVK_VARYING_TYPES_NOT_ALLOWED)");
            }
        }
        rejectDuplicateReferences(opName, combined);
    }

    /**
     * Reject an exact repeated reference in a value-list operand list — the kernel's
     * {@code CheckEntityListenUtils.checkParamsReferencedOnlyOnce}. Identity is the reference itself
     * (field path, plus {@code -> category} for a projection, plus {@code $} for a correlation); a
     * <b>wildcard</b> (flattened {@link FieldListRef}) scope is skipped, exactly as the kernel skips
     * wildcarded refs in {@code checkDuplicatesInDirectRefs}.
     */
    private static void rejectDuplicateReferences(String opName, java.util.List<ValueListOperand> operands) {
        var seen = new java.util.HashSet<String>();
        for (ValueListOperand op : operands) {
            String identity = referenceIdentity(op);
            if (identity == null) {
                continue;   // a wildcard scope — not deduped (matches the kernel)
            }
            if (!seen.add(identity)) {
                throw new IllegalArgumentException("'" + identity + "' occurs more than once in " + opName
                        + " — the kernel rejects an exact duplicate reference (MVK_DUPLICATE_PARAM)");
            }
        }
    }

    /** The exact-reference identity for duplicate detection, or {@code null} for a wildcard scope (skipped). */
    private static String referenceIdentity(ValueListOperand op) {
        return switch (op) {
            case FieldListRef ignored -> null;   // wildcard/flattened list — kernel skips dedup here
            case FieldRef f -> (f.correlated() ? "$" : "") + f.path();
            case EnumCategoryAccess c -> (c.outer() ? "$" : "") + c.field().path() + " -> " + c.category();
        };
    }

    // --- field-list quantifiers — at least one field by signature ------------------------------

    /** {@code AllFieldsFilled(f1, f2, …)} — every listed field is filled (≥1 by signature; also over {@code List} / {@code Group*} scopes). catalog:AllFieldsFilled */
    public static Condition allFieldsFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.ALL_FIELDS_FILLED, first, rest);
    }

    /** {@code NoFieldFilled(f1, f2, …)} — none of the listed fields is filled. catalog:NoFieldFilled */
    public static Condition noFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.NO_FIELD_FILLED, first, rest);
    }

    /** {@code AtLeastOneFieldFilled(f1, f2, …)} — at least one of the listed fields is filled. catalog:AtLeastOneFieldFilled */
    public static Condition atLeastOneFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.AT_LEAST_ONE_FIELD_FILLED, first, rest);
    }

    /** {@code NotAllFieldsFilled(f1, f2, …)} — at least one of the listed fields is empty. catalog:NotAllFieldsFilled */
    public static Condition notAllFieldsFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.NOT_ALL_FIELDS_FILLED, first, rest);
    }

    /** {@code FieldsNotCollectivelyFilled(f1, f2, …)} — the listed fields are partially filled (some but not all). catalog:FieldsNotCollectivelyFilled */
    public static Condition fieldsNotCollectivelyFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, first, rest);
    }

    /** {@code NotExactlyOneFieldFilled(f1, f2, …)} — the count of filled fields among those listed is not exactly one. catalog:NotExactlyOneFieldFilled */
    public static Condition notExactlyOneFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED, first, rest);
    }

    /** {@code MoreThanOneFieldFilled(f1, f2, …)} — true when two or more of the listed fields are filled. catalog:MoreThanOneFieldFilled */
    public static Condition moreThanOneFieldFilled(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.MORE_THAN_ONE_FIELD_FILLED, first, rest);
    }

    /** {@code FieldValuesNotUnique(f1, f2, …)} — true when the listed values are not all distinct. catalog:FieldValuesNotUnique */
    public static Condition fieldValuesNotUnique(FieldOperand first, FieldOperand... rest) {
        return fieldList(Quantifier.FIELD_VALUES_NOT_UNIQUE, first, rest);
    }

    /**
     * {@code RepetitionNotUnique(key1, key2, …)} — duplicate-key detection over repetitions
     * (KERNEL-SEMANTICS §9). Scope with {@link RepetitionNotUniqueCondition#from from(group)}
     * ({@code @From}). Keys are plain {@link FieldRef}s by signature — groups and starred lists
     * are unrepresentable here. catalog:RepetitionNotUnique
     */
    public static RepetitionNotUniqueCondition repetitionNotUnique(FieldRef first, FieldRef... rest) {
        List<FieldRef> keys = new ArrayList<>();
        keys.add(first);
        keys.addAll(List.of(rest));
        return new RepetitionNotUniqueCondition(keys, java.util.Optional.empty());
    }

    /** {@code AllFieldsFilled(f1, …)} over a built list — the list peer of the varargs form, for bulk
     *  authoring (e.g. a presence guard over a set of fields gathered by navigation). Empty list is an error. */
    public static Condition allFieldsFilled(List<? extends FieldOperand> fields) {
        return nonEmptyFieldList(Quantifier.ALL_FIELDS_FILLED, fields);
    }

    /** {@code NoFieldFilled(f1, …)} over a built list. */
    public static Condition noFieldFilled(List<? extends FieldOperand> fields) {
        return nonEmptyFieldList(Quantifier.NO_FIELD_FILLED, fields);
    }

    /** {@code AtLeastOneFieldFilled(f1, …)} over a built list. */
    public static Condition atLeastOneFieldFilled(List<? extends FieldOperand> fields) {
        return nonEmptyFieldList(Quantifier.AT_LEAST_ONE_FIELD_FILLED, fields);
    }

    /** {@code NotAllFieldsFilled(f1, …)} over a built list — the list peer of the varargs form. */
    public static Condition notAllFieldsFilled(List<? extends FieldOperand> fields) {
        return nonEmptyFieldList(Quantifier.NOT_ALL_FIELDS_FILLED, fields);
    }

    /** {@code FieldsNotCollectivelyFilled(f1, …)} over a built list. */
    public static Condition fieldsNotCollectivelyFilled(List<? extends FieldOperand> fields) {
        return nonEmptyFieldList(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, fields);
    }

    /** {@code NotExactlyOneFieldFilled(f1, …)} over a built list. */
    public static Condition notExactlyOneFieldFilled(List<? extends FieldOperand> fields) {
        return nonEmptyFieldList(Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED, fields);
    }

    /** {@code MoreThanOneFieldFilled(f1, …)} over a built list. */
    public static Condition moreThanOneFieldFilled(List<? extends FieldOperand> fields) {
        return nonEmptyFieldList(Quantifier.MORE_THAN_ONE_FIELD_FILLED, fields);
    }

    /** {@code FieldValuesNotUnique(f1, …)} over a built list. */
    public static Condition fieldValuesNotUnique(List<? extends FieldOperand> fields) {
        return nonEmptyFieldList(Quantifier.FIELD_VALUES_NOT_UNIQUE, fields);
    }

    private static Condition nonEmptyFieldList(Quantifier quantifier, List<? extends FieldOperand> fields) {
        if (fields.isEmpty()) {
            throw new IllegalArgumentException(quantifier + " needs at least one field");
        }
        return fieldList(quantifier, fields);
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
     * A field-<em>label</em> interpolation token for an error message (FEATURE-GAPS) — renders
     * {@code $<path>$} (a bare path, no {@code .value} suffix), the kernel's placeholder for the field's
     * localized <b>label</b> (its name, replaceable by the UI label) rather than its runtime value. The
     * label peer of {@link #fieldValue}: {@code en(fieldLabel(fee) + " is too high.")}.
     */
    public static String fieldLabel(FieldRef field) {
        return "$" + field.path() + "$";
    }

    /**
     * A repetition-<em>index</em> interpolation token for an error message (FEATURE-GAPS) — renders
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

    /** And-fold: {@code allOf(a, b, c)} renders {@code a And b And c} (no redundant parentheses). catalog:And */
    public static Condition allOf(Condition first, Condition second, Condition... rest) {
        Condition folded = first.and(second);
        for (Condition c : rest) {
            folded = folded.and(c);
        }
        return folded;
    }

    /** Or-fold: {@code anyOf(a, b, c)} renders {@code a Or b Or c}; parenthesized when mixed with And. catalog:Or */
    public static Condition anyOf(Condition first, Condition second, Condition... rest) {
        Condition folded = first.or(second);
        for (Condition c : rest) {
            folded = folded.or(c);
        }
        return folded;
    }

    /** And-fold over a built list (the generated/bulk-authoring form, e.g. one clause per navigated field):
     *  a single element returns as-is, an empty list is an error. Renders identically to the varargs form. */
    public static Condition allOf(List<Condition> conditions) {
        requireNonEmpty(conditions, "allOf");
        return conditions.stream().reduce(Condition::and).orElseThrow();
    }

    /** Or-fold over a built list — the list peer of {@link #anyOf(Condition, Condition, Condition...)}. */
    public static Condition anyOf(List<Condition> conditions) {
        requireNonEmpty(conditions, "anyOf");
        return conditions.stream().reduce(Condition::or).orElseThrow();
    }

    private static void requireNonEmpty(List<Condition> conditions, String name) {
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException(name + " needs at least one condition");
        }
    }

    // --- group predicates (the group twin of the field forms) ----------------------------------

    /**
     * {@code RuleGroup} (DE {@code RegelKontext}) — the self-reference to the rule's OWN group, for
     * {@code GroupFilled(RuleGroup)} / {@code GroupNotFilled(RuleGroup)} (KERNEL-FINDINGS "Condition
     * directives"). Renders the reserved keyword, never a path; use {@code Dsl.ruleGroup().filled()}.
     */
    public static RuleGroupRef ruleGroup() {
        return new RuleGroupRef();
    }

    /** {@code GroupFilled(group)} — the group is present (for a repeatable group: the row exists); the peer of {@link GroupRef#filled}. catalog:GroupFilled */
    public static Condition groupFilled(GroupRef group) {
        return new GroupPresenceCondition(group, true);
    }

    /** {@code GroupNotFilled(group)} — the group is absent; negative conditions on a repeatable group need guarding (§5). catalog:GroupNotFilled */
    public static Condition groupNotFilled(GroupRef group) {
        return new GroupPresenceCondition(group, false);
    }

    /** {@code AllGroupsFilled(g1, g2, …)} — every listed group is present. catalog:AllGroupsFilled */
    public static Condition allGroupsFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.ALL_GROUPS_FILLED, first, rest);
    }

    /** {@code NoGroupFilled(g1, g2, …)} — none of the listed groups is present. catalog:NoGroupFilled */
    public static Condition noGroupFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.NO_GROUP_FILLED, first, rest);
    }

    /** {@code AtLeastOneGroupFilled(g1, g2, …)} — at least one of the listed groups is present. catalog:AtLeastOneGroupFilled */
    public static Condition atLeastOneGroupFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED, first, rest);
    }

    /**
     * {@code NoGroupFilled(G*)} over an exact group scope. A starred operand counts instantiated
     * repetitions rather than testing scalar group presence. catalog:NoGroupFilled
     */
    public static Condition noGroupFilled(GroupListRef first, GroupListRef... rest) {
        return GroupListPredicate.overScopes(GroupQuantifier.NO_GROUP_FILLED, first, rest);
    }

    /**
     * {@code AtLeastOneGroupFilled(G*)} over an exact group scope. A starred operand counts
     * instantiated repetitions rather than testing scalar group presence.
     * catalog:AtLeastOneGroupFilled
     */
    public static Condition atLeastOneGroupFilled(GroupListRef first, GroupListRef... rest) {
        return GroupListPredicate.overScopes(
                GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED, first, rest);
    }

    /** {@code NotAllGroupsFilled(g1, g2, …)} — at least one of the listed groups is absent. catalog:NotAllGroupsFilled */
    public static Condition notAllGroupsFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.NOT_ALL_GROUPS_FILLED, first, rest);
    }

    // --- the common entity list: the five fixed forms admit field AND group operands ------------

    /**
     * {@code AllGroupsFilled(entity1, entity2, …)} over the common field-or-group entity list —
     * despite the name, a checked field is a legal operand beside a group scope
     * (SPEC-2026-07-23-09). Duplicate/overlap/arity gates stay kernel-arbitrated.
     * catalog:AllGroupsFilled
     */
    public static Condition allGroupsFilled(GroupListOperand first, GroupListOperand... rest) {
        return GroupListPredicate.overEntities(GroupQuantifier.ALL_GROUPS_FILLED, first, rest);
    }

    /** {@code NoGroupFilled(entity1, …)} over the common field-or-group entity list; a fixed singleton
     *  has the scalar presence semantics. catalog:NoGroupFilled */
    public static Condition noGroupFilled(GroupListOperand first, GroupListOperand... rest) {
        return GroupListPredicate.overEntities(GroupQuantifier.NO_GROUP_FILLED, first, rest);
    }

    /** {@code AtLeastOneGroupFilled(entity1, …)} over the common field-or-group entity list; a fixed
     *  singleton has the scalar presence semantics. catalog:AtLeastOneGroupFilled */
    public static Condition atLeastOneGroupFilled(GroupListOperand first, GroupListOperand... rest) {
        return GroupListPredicate.overEntities(GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED, first, rest);
    }

    /** {@code NotAllGroupsFilled(entity1, …)} over the common field-or-group entity list. catalog:NotAllGroupsFilled */
    public static Condition notAllGroupsFilled(GroupListOperand first, GroupListOperand... rest) {
        return GroupListPredicate.overEntities(GroupQuantifier.NOT_ALL_GROUPS_FILLED, first, rest);
    }

    /** {@code GroupsNotCollectivelyFilled(entity1, …)} over the common field-or-group entity list. catalog:GroupsNotCollectivelyFilled */
    public static Condition groupsNotCollectivelyFilled(GroupListOperand first, GroupListOperand... rest) {
        return GroupListPredicate.overEntities(GroupQuantifier.GROUPS_NOT_COLLECTIVELY_FILLED, first, rest);
    }

    /** {@code GroupsNotCollectivelyFilled(g1, g2, …)} — the listed groups are partially present (some but not all). catalog:GroupsNotCollectivelyFilled */
    public static Condition groupsNotCollectivelyFilled(GroupRef first, GroupRef... rest) {
        return groupList(GroupQuantifier.GROUPS_NOT_COLLECTIVELY_FILLED, first, rest);
    }

    /** {@code AllGroupsFilled(g1, …)} over a built list — the group peer of {@link #allFieldsFilled(List)}. Empty list is an error. */
    public static Condition allGroupsFilled(List<? extends GroupRef> groups) {
        return nonEmptyGroupList(GroupQuantifier.ALL_GROUPS_FILLED, groups);
    }

    /** {@code NoGroupFilled(g1, …)} over a built list. */
    public static Condition noGroupFilled(List<? extends GroupRef> groups) {
        return nonEmptyGroupList(GroupQuantifier.NO_GROUP_FILLED, groups);
    }

    /** {@code AtLeastOneGroupFilled(g1, …)} over a built list. */
    public static Condition atLeastOneGroupFilled(List<? extends GroupRef> groups) {
        return nonEmptyGroupList(GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED, groups);
    }

    /** {@code NotAllGroupsFilled(g1, …)} over a built list. */
    public static Condition notAllGroupsFilled(List<? extends GroupRef> groups) {
        return nonEmptyGroupList(GroupQuantifier.NOT_ALL_GROUPS_FILLED, groups);
    }

    /** {@code GroupsNotCollectivelyFilled(g1, …)} over a built list. */
    public static Condition groupsNotCollectivelyFilled(List<? extends GroupRef> groups) {
        return nonEmptyGroupList(GroupQuantifier.GROUPS_NOT_COLLECTIVELY_FILLED, groups);
    }

    private static Condition groupList(GroupQuantifier quantifier, GroupRef first, GroupRef... rest) {
        List<GroupRef> groups = new ArrayList<>();
        groups.add(first);
        groups.addAll(List.of(rest));
        return new GroupListPredicate(quantifier, groups);
    }

    private static Condition nonEmptyGroupList(GroupQuantifier quantifier, List<? extends GroupRef> groups) {
        if (groups.isEmpty()) {
            throw new IllegalArgumentException(quantifier + " needs at least one group");
        }
        return new GroupListPredicate(quantifier, List.copyOf(groups));
    }
}
