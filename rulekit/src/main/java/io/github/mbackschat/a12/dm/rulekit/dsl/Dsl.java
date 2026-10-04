package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * The static-import facade (PROJECT-DESIGN §10, jOOQ-style): free-standing factories for composing
 * conditions, made for reusable combinators — {@code gate.and(fieldNotFilled(field))} instead of a
 * handle-method chain. Every factory takes <em>model-resolved handles</em> ({@link FieldRef},
 * {@link StringRef}, …), never string paths: model-awareness stays mandatory (§5a) because handles
 * only come from {@link BoundModel}. Grows with the operator catalog.
 *
 * <p><b>This file is intentionally a long, flat facade</b> — one delegating factory per operator, not a
 * candidate for splitting (a single static import is the whole point; see ARCHITECTURE §3.2). Read it by
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

    /**
     * The {@link FieldOperand} overload of {@link #fieldFilled(FieldRef)} — additive, for a caller that
     * cannot type its operand as {@link FieldRef} up front (an AST-driven bridge, never a human author, who
     * keeps the narrower, compiler-checked signature above). {@code FieldFilled} takes exactly one field,
     * unlike {@code AllFieldsFilled}/{@code NoFieldFilled}, which legitimately admit a starred member — a
     * {@link FieldListRef} here is the kernel's {@code MVK_NO_WILDCARDS_ALLOWED} (KERNEL-FINDINGS §9), stated
     * here instead of erased by the type split {@link FieldOperand}'s own doc describes. An
     * {@link IndexedFieldValue} remains one selected field and therefore uses indexed presence.
     */
    public static Condition fieldFilled(FieldOperand field) {
        return switch (field) {
            case FieldRef ref -> fieldFilled(ref);
            case IndexedFieldValue indexed -> new SemanticIndex(indexed).filled();
            case FieldListRef list -> throw Refusals.of(KernelCode.MVK_NO_WILDCARDS_ALLOWED, "FieldFilled needs a single field, not the repetition list "
                            + list.renderedPath());
        };
    }

    /** The {@link FieldOperand} overload of {@link #fieldNotFilled(FieldRef)} — see {@link #fieldFilled(FieldOperand)}. */
    public static Condition fieldNotFilled(FieldOperand field) {
        return switch (field) {
            case FieldRef ref -> fieldNotFilled(ref);
            case IndexedFieldValue indexed -> new SemanticIndex(indexed).notFilled();
            case FieldListRef list -> throw Refusals.of(KernelCode.MVK_NO_WILDCARDS_ALLOWED, "FieldNotFilled needs a single field, not the repetition list "
                            + list.renderedPath());
        };
    }

    /** {@code Length(field)} as a comparable numeric expression. catalog:Length */
    public static NumberExpr length(StringRef field) {
        return field.length();
    }

    /** {@code Length(field For key)} retains the selected cell (DG71; KF387). catalog:Length */
    public static NumberExpr length(IndexedFieldValue field) {
        return new NumberExpr(new LengthOf(field));
    }

    /** {@code [field] PatternMatched "regex"} — anchored whole-value match; the factory peer of {@link StringRef#matches}. catalog:PatternMatched */
    public static Condition matches(StringRef field, String regex) {
        return field.matches(regex);
    }

    /** The indexed-cell arm of {@code PatternMatched} (DG71; KF387). catalog:PatternMatched */
    public static Condition matches(IndexedFieldValue field, String regex) {
        return indexedPattern(field, true, regex);
    }

    /** {@code [field] PatternViolated "regex"} — anchored whole-value non-match; the factory peer of {@link StringRef#violates}. catalog:PatternViolated */
    public static Condition violates(StringRef field, String regex) {
        return field.violates(regex);
    }

    /** The indexed-cell arm of {@code PatternViolated} (DG71; KF387). catalog:PatternViolated */
    public static Condition violates(IndexedFieldValue field, String regex) {
        return indexedPattern(field, false, regex);
    }

    private static Condition indexedPattern(IndexedFieldValue field, boolean matched, String regex) {
        return new PatternCondition(field, matched, regex);
    }

    // --- filled-count aggregates — comparable numeric expressions ------------------------------

    /**
     * {@code NumberOfFilledFields(f1, f2, …)} — ≥2 fields by signature; kinds may be mixed.
     * Operands may be flattened repetition lists ({@link BoundModel#flatten}). catalog:NumberOfFilledFields
     */
    public static NumberExpr numberOfFilledFields(AggregateOperand first, AggregateOperand second,
            AggregateOperand... rest) {
        var fields = new ArrayList<AggregateOperand>();
        fields.add(first);
        fields.add(second);
        fields.addAll(List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS, fields));
    }

    /**
     * {@code NumberOfFilledFields(f1, …)} over a built list — the list peer of the varargs form above, for
     * bulk authoring (a field set gathered by navigation, then asked both "are they all filled" and "how many
     * are"). The eight fill quantifiers have carried such a peer since they were added; this one had not,
     * which forced a consumer holding a list to unroll it.
     *
     * <p>The arity law is the varargs form's, checked here because a list makes it a runtime question: fewer
     * than two operands is an {@link IllegalArgumentException}. A single repetition list or group scope is
     * genuinely valid alone and reaches the kernel through {@link #numberOfFilledFields(FieldListRef)} and
     * {@link #numberOfFilledFields(GroupListRef)}, whose separate declarations are what keep a lone bare
     * field from silently becoming legal.
     */
    public static NumberExpr numberOfFilledFields(List<? extends AggregateOperand> fields) {
        if (fields.size() < 2) {
            throw new IllegalArgumentException(
                    "NumberOfFilledFields over a list needs at least two operands, got " + fields.size()
                            + " — a single repetition list or group scope has its own overload");
        }
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS,
                List.copyOf(fields)));
    }

    /**
     * {@code NumberOfFilledFields(scope)} over a single flattened repetition list — counts the filled
     * fields across one wildcard scope (e.g. the flatten of {@code IndexFields*} then {@code indexField}).
     * The single-list form is valid on its own (the param law exempts a repetition list, verified — see
     * {@link CountAggregate}).
     */
    public static NumberExpr numberOfFilledFields(FieldListRef scope) {
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS,
                List.of(scope)));
    }

    /**
     * {@code NumberOfFilledFields(scope)} over a single GROUP scope — counts the filled fields the kernel
     * expands that group to. The single-scope form is valid on its own for the same reason the repetition
     * list is, and a repeatable group must carry its star ({@code MVK_NO_WILDCARD} otherwise), which the author
     * writes and {@link BoundModel#flattenGroup} checks against the model.
     *
     * <p>Declared separately from the two-or-more form rather than widening it, so a single BARE field stays
     * a compile error instead of becoming a runtime arity failure.
     */
    public static NumberExpr numberOfFilledFields(GroupListRef scope) {
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS,
                List.of(scope)));
    }

    /**
     * {@code NumberOfFilledFields(Group For key)} over a single index-selected group — valid alone for the same
     * reason the unindexed group scope is ({@code validate.laws.SemanticIndexGroupOperandCarrierLawsTest}).
     */
    public static NumberExpr numberOfFilledFields(IndexedGroupValue scope) {
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS,
                List.of(scope)));
    }

    /** {@code NumberOfFilledFields(group, …)} — counts the filled fields inside the group(s). */
    public static NumberExpr numberOfFilledFields(GroupRef first, GroupRef... rest) {
        var groups = new ArrayList<GroupRef>();
        groups.add(first);
        groups.addAll(List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS,
                CountAggregate.scopesOf(groups)));
    }

    /** {@code Sum(…)} over number fields/repetition lists — unspecified fields count as 0 (§9). catalog:Sum */
    public static NumberExpr sum(AggregateOperand first, AggregateOperand... rest) {
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
    public static NumberExpr maxValue(AggregateOperand first, AggregateOperand... rest) {
        return listAggregate(ListAggregate.AggOp.MAX_VALUE, first, rest);
    }

    /** {@code MinValue(…)} over number fields/repetition lists — unspecified fields are ignored. catalog:MinValue */
    public static NumberExpr minValue(AggregateOperand first, AggregateOperand... rest) {
        return listAggregate(ListAggregate.AggOp.MIN_VALUE, first, rest);
    }


    /**
     * The walk A12 runs over an entity list its leading field sends down the date-like route, in A12's order,
     * for the model-bound factories that can see what it reads.
     *
     * <p>{@code CheckEntityListenUtils.checkOpTypesAreComparableDatetimeType} asks each operand in turn three
     * questions and stops at the first that fails: is it date-like ({@code MVK_DATE_AND_NONDATE}), is its date
     * fully known ({@code MVK_PARTIAL_DATE_NOT_ALLOWED}), and does its declared component set equal the
     * leader's ({@code MVK_DATEFORMATS_NOT_COMPATIBLE}). The walk is interleaved rather than phased, so two
     * defects answer by operand POSITION ([KF389], {@code validate.laws.DateAggregateBaseTypeGateLawsTest},
     * {@code validate.laws.EntityListLeaderRouteLawsTest}); two separate passes answered the precision class
     * for a list whose earlier operand already broke the component set. Duplicates and the param law are
     * asked before any of it, as A12 asks them.
     *
     * <p>Component SETS are compared rather than format strings, after YEAR is added to every set when the
     * model declares a base year ([KF193], {@code validate.laws.TemporalAggregateComponentSetLawsTest}), so
     * {@code yyyy-MM-dd} and {@code dd.MM.yyyy} agree. Only a DATE declares a precision, so a TIME, DATETIME or
     * DATE_FRAGMENT operand passes that question ([KF427]).
     *
     * <p>A group operand is walked as its expansion, in its place, which is what A12 walks
     * ({@link BoundModel#expansionOf}). <b>The walk stops, and leaves the rest to the kernel, at the first operand
     * it cannot read</b>: a comparison with a field that declares no format, whose default this walk does not
     * compare, and a dangling type. Stopping under-reports; walking on would cite a later operand's class where
     * the unread one may answer first. A leader that is not date-like is not this route at all, so the record's
     * own gate answers.
     */
    static void requireComparableTemporalList(String operatorId, BoundModel model,
            AggregateOperand first, AggregateOperand... rest) {
        var operands = new ArrayList<AggregateOperand>();
        operands.add(first);
        operands.addAll(List.of(rest));
        readOperandsThen(operatorId, model, operands, () -> {
            EntityListRoute.requireShape(operatorId, operands);
            walkTemporalList(operatorId, model, model.expansionOf(operands));
        });
    }

    /**
     * The walk above for {@code NumberOfDifferentValues}, then the record's own kind gate, asked coded, both over
     * the expansion. Off the date-like route the walk asks nothing, and behind a textual or numeric leader every
     * mismatch is the leader's class wherever it stands ({@code validate.laws.NumberOfDifferentValuesOperandClassLawsTest}),
     * so once the groups are read the record's class is A12's. Behind a date-like leader the record never cites.
     */
    static void requireDifferentValuesList(BoundModel model, AggregateOperand first, AggregateOperand... rest) {
        var operands = new ArrayList<AggregateOperand>();
        operands.add(first);
        operands.addAll(List.of(rest));
        String operatorId = CountAggregate.CountOp.DIFFERENT_VALUES.operatorId();
        readOperandsThen(operatorId, model, operands, () -> {
            EntityListRoute.requireShape(operatorId, operands);
            List<AggregateOperand> expanded = model.expansionOf(operands);
            walkTemporalList(operatorId, model, expanded);
            CountAggregate.checkValueComparable(expanded);
        });
    }

    /**
     * Reads each entity-list operand as A12 does, then asks {@code gates} with their classes intact.
     *
     * <p>A12 reads every operand before any list gate, and the first it cannot read answers
     * ({@code validate.laws.EntityListOperandReadLawsTest}). A group's emptiness is the model's to show, so an
     * empty group is cited here, a root as its own class. Three earlier stages of the read are not: an index's
     * resolution, and the rule and filter the condition sits in, which bind a repeatable level left unstarred and
     * admit the {@code $} marker ({@code validate.laws.EntityListReadStageLawsTest}). So from the first operand
     * one of those may answer for, nothing further is read and every class {@code gates} raises is withheld, as
     * the records withhold it.
     */
    static void readOperandsThen(String operatorId, BoundModel model, List<? extends AggregateOperand> operands,
            Runnable gates) {
        boolean unread = false;
        for (AggregateOperand operand : operands) {
            if (readNeedsMoreThanTheModel(model, operand)) {
                unread = true;
                break;
            }
            if (operand instanceof GroupListRef scope && model.descendantFields(scope.group()).findAny().isEmpty()) {
                boolean root = scope.group().path().value().indexOf('/', 1) < 0;
                throw Refusals.of(root ? KernelCode.MVK_ROOT_GROUP_IS_EMPTY : KernelCode.MVK_GROUP_IS_EMPTY,
                        operatorId + " reads " + scope.renderedPath() + ", which holds no field");
            }
        }
        try {
            gates.run();
        } catch (KernelCodeRefusal refusal) {
            throw unread ? refusal.withheld() : refusal;
        }
    }

    /** Whether A12's read of this operand may answer from what the model does not hold — see {@link #readOperandsThen}. */
    private static boolean readNeedsMoreThanTheModel(BoundModel model, AggregateOperand operand) {
        return switch (operand) {
            case IndexedFieldValue ignored -> true;
            case IndexedGroupValue ignored -> true;
            case FieldRef field -> field.correlated() || model.crossesUnstarredRepetition(field);
            case FieldListRef list -> list.semanticIndex().isPresent() || list.correlated() || model.crossesUnstarredRepetition(list);
            case GroupListRef scope -> scope.correlated() || model.crossesUnstarredRepetition(scope);
        };
    }

    /** The date-like walk over an expanded list; the shape gates have been asked of the operands as written. */
    private static void walkTemporalList(String operatorId, BoundModel model, List<AggregateOperand> operands) {
        AggregateOperand first = operands.getFirst();
        String leaderPath = declaredFieldPath(first);
        if (leaderPath == null || !EntityListRoute.isDateLike(model.field(leaderPath).kind())) {
            return;
        }
        boolean supplemented = model.hasBaseYear();
        Set<TemporalFormats.Component> witness = null;
        for (int position = 0; position < operands.size(); position++) {
            String path = declaredFieldPath(operands.get(position));
            if (path == null) {
                return;
            }
            ValueKind kind = model.field(path).kind();
            if (kind == ValueKind.UNKNOWN) {
                return;
            }
            if (!EntityListRoute.isDateLike(kind)) {
                throw Refusals.of(KernelCode.MVK_DATE_AND_NONDATE, operatorId + " is led by the date-like '"
                        + leaderPath + "', so every operand must be date-like; '" + path + "' is " + kind);
            }
            String precision = model.datePrecision(path);
            if (precision != null && !DatePrecisions.guaranteedComponents(precision).containsAll(
                    EnumSet.of(TemporalFormats.Component.YEAR, TemporalFormats.Component.MONTH,
                            TemporalFormats.Component.DAY))) {
                throw Refusals.of(KernelCode.MVK_PARTIAL_DATE_NOT_ALLOWED,
                        operatorId + " does not admit a partly-known date; '"
                                + path + "' is declared " + precision);
            }
            String declared = model.temporalFormat(path);
            Set<TemporalFormats.Component> components = null;
            if (declared != null) {
                components = EnumSet.copyOf(TemporalFormats.componentsOf(declared));
                if (supplemented) {
                    components.add(TemporalFormats.Component.YEAR);
                }
            }
            if (position == 0) {
                // The leader's set is only the witness; the next operand is still asked its first two
                // questions when the leader declares none, since A12 asks both ahead of the comparison.
                witness = components;
            } else if (witness == null || components == null) {
                return;
            } else if (!witness.equals(components)) {
                throw Refusals.of(KernelCode.MVK_DATEFORMATS_NOT_COMPATIBLE,
                        operatorId + " needs every temporal operand to declare the"
                                + " same date components; '" + leaderPath + "' declares " + witness + " and '" + path
                                + "' declares " + components
                                + (supplemented ? " (both supplemented by the model's base year)" : ""));
            }
        }
    }

    /** The model path of an operand that directly names ONE field declaration, else {@code null}. */
    private static String declaredFieldPath(AggregateOperand operand) {
        return switch (operand) {
            case FieldRef ref -> ref.path().value();
            case FieldListRef list -> list.field().path().value();
            case IndexedFieldValue indexed -> indexed.field().path().value();
            default -> null;
        };
    }

    // --- the DATE extrema ---------------------------------------------------------------------
    // Each factory NAMES the kind it means, so it supplies it to the aggregate rather than letting the record
    // re-derive it from the operands. That is what lets a GROUP scope in: a group carries no kind of its own
    // (its kind lives in the expansion, kernel-delegated), while the information was always present here at
    // the call site. Only the STARRED group form is offered — see DateListAggregate (DG35, KF187).
    //
    // Each takes an operand LIST, exactly as the numeric `maxValue`/`minValue` do: a single repetition list or
    // starred group satisfies the param law alone, and two or more direct fields are equally legal — the kernel
    // compares their declared component SETS, not their format spellings (DG38, KF193).

    /** {@code MaxValue(…)} over date operands — the latest specified date (a date operand). catalog:MaxValue */
    public static DateExpr latestOf(AggregateOperand first, AggregateOperand... rest) {
        return new DateExpr(dateExtremum(DateListAggregate.Mode.LATEST, ValueKind.DATE, first, rest));
    }

    /** {@code MinValue(…)} over date operands — the earliest specified date (a date operand). catalog:MinValue */
    public static DateExpr earliestOf(AggregateOperand first, AggregateOperand... rest) {
        return new DateExpr(dateExtremum(DateListAggregate.Mode.EARLIEST, ValueKind.DATE, first, rest));
    }

    /** {@code MaxValue(…)} over time operands — the latest specified time of day. catalog:MaxValue */
    public static TimeExpr latestTimeOf(AggregateOperand first, AggregateOperand... rest) {
        return new TimeExpr(dateExtremum(DateListAggregate.Mode.LATEST, ValueKind.TIME, first, rest));
    }

    /** {@code MinValue(…)} over time operands — the earliest specified time of day. catalog:MinValue */
    public static TimeExpr earliestTimeOf(AggregateOperand first, AggregateOperand... rest) {
        return new TimeExpr(dateExtremum(DateListAggregate.Mode.EARLIEST, ValueKind.TIME, first, rest));
    }

    /** {@code MaxValue(…)} over date-time operands — the latest specified instant. catalog:MaxValue */
    public static DateTimeExpr latestDateTimeOf(AggregateOperand first, AggregateOperand... rest) {
        return new DateTimeExpr(dateExtremum(DateListAggregate.Mode.LATEST, ValueKind.DATE_TIME, first, rest));
    }

    /** {@code MinValue(…)} over date-time operands — the earliest specified instant. catalog:MinValue */
    public static DateTimeExpr earliestDateTimeOf(AggregateOperand first, AggregateOperand... rest) {
        return new DateTimeExpr(dateExtremum(DateListAggregate.Mode.EARLIEST, ValueKind.DATE_TIME, first, rest));
    }

    // --- the DATE extrema, with the model, so the component-set gate can run -------------------

    private static DateListAggregate dateExtremum(DateListAggregate.Mode mode, ValueKind kind,
            AggregateOperand first, AggregateOperand... rest) {
        var operands = new ArrayList<AggregateOperand>();
        operands.add(first);
        operands.addAll(List.of(rest));
        return new DateListAggregate(mode, operands, kind);
    }

    /** {@code FirstFilledValue(…)} over a number repetition list — the first specified value, as a number. catalog:FirstFilledValue */
    public static NumberExpr firstFilledNumber(FieldListRef numberScope) {
        requireKind(numberScope, ValueKind.NUMBER, "firstFilledNumber");
        return new NumberExpr(new FirstFilledValue(numberScope));
    }

    /** Group form; A12 visits the group's descendant number fields in owner encounter order. */
    public static NumberExpr firstFilledNumber(FirstFilledOperand group) {
        return new NumberExpr(firstFilledValue(group, ValueKind.NUMBER, "firstFilledNumber"));
    }

    /** Multi-operand {@code FirstFilledValue}: each later number field/list is visited only if no earlier operand supplied the result. */
    public static NumberExpr firstFilledNumber(FieldListRef numberScope, FieldOperand second, FieldOperand... rest) {
        return new NumberExpr(firstFilledValue(numberScope, ValueKind.NUMBER, "firstFilledNumber", second, rest));
    }

    /** Direct-field form; the owner requires at least two operands. */
    public static NumberExpr firstFilledNumber(FieldOperand first, FieldOperand second, FieldOperand... rest) {
        return new NumberExpr(firstFilledValue(first, ValueKind.NUMBER, "firstFilledNumber", second, rest));
    }

    /** Owner-entity form, including semantic-index fields and groups. */
    public static NumberExpr firstFilledNumber(
            FirstFilledOperand first, FirstFilledOperand second, FirstFilledOperand... rest) {
        return new NumberExpr(firstFilledValue(first, ValueKind.NUMBER, "firstFilledNumber", second, rest));
    }

    /** {@code FirstFilledValue(…)} over a date repetition list — the first specified value, as a date. catalog:FirstFilledValue */
    public static DateExpr firstFilledDate(FieldListRef dateScope) {
        requireKind(dateScope, ValueKind.DATE, "firstFilledDate");
        return new DateExpr(new FirstFilledValue(dateScope));
    }

    /** Group form; A12 visits the group's descendant date fields in owner encounter order. */
    public static DateExpr firstFilledDate(FirstFilledOperand group) {
        return new DateExpr(firstFilledValue(group, ValueKind.DATE, "firstFilledDate"));
    }

    /** Multi-operand {@code FirstFilledValue}: each later date field/list is visited only if no earlier operand supplied the result. */
    public static DateExpr firstFilledDate(FieldListRef dateScope, FieldOperand second, FieldOperand... rest) {
        return new DateExpr(firstFilledValue(dateScope, ValueKind.DATE, "firstFilledDate", second, rest));
    }

    /** Direct-field form; the owner requires at least two operands. */
    public static DateExpr firstFilledDate(FieldOperand first, FieldOperand second, FieldOperand... rest) {
        return new DateExpr(firstFilledValue(first, ValueKind.DATE, "firstFilledDate", second, rest));
    }

    /** Owner-entity form, including semantic-index fields and groups. */
    public static DateExpr firstFilledDate(
            FirstFilledOperand first, FirstFilledOperand second, FirstFilledOperand... rest) {
        return new DateExpr(firstFilledValue(first, ValueKind.DATE, "firstFilledDate", second, rest));
    }

    /** {@code FirstFilledValue(…)} over date-fragment repetitions — the first specified partial date. catalog:FirstFilledValue */
    public static DateExpr firstFilledDateFragment(FieldListRef fragmentScope) {
        requireKind(fragmentScope, ValueKind.DATE_FRAGMENT, "firstFilledDateFragment");
        return new DateExpr(new FirstFilledValue(fragmentScope));
    }

    /** Group form for the date/date-fragment owner family. */
    public static DateExpr firstFilledDateFragment(FirstFilledOperand group) {
        return new DateExpr(firstFilledValue(group, ValueKind.DATE_FRAGMENT, "firstFilledDateFragment"));
    }

    /** Multi-operand date-fragment {@code FirstFilledValue}, preserving authored encounter order. */
    public static DateExpr firstFilledDateFragment(
            FieldListRef fragmentScope, FieldOperand second, FieldOperand... rest) {
        return new DateExpr(firstFilledValue(
                fragmentScope, ValueKind.DATE_FRAGMENT, "firstFilledDateFragment", second, rest));
    }

    /** Direct-field form; the owner requires at least two operands. */
    public static DateExpr firstFilledDateFragment(
            FieldOperand first, FieldOperand second, FieldOperand... rest) {
        return new DateExpr(firstFilledValue(
                first, ValueKind.DATE_FRAGMENT, "firstFilledDateFragment", second, rest));
    }

    /** Owner-entity form, including semantic-index fields and groups. */
    public static DateExpr firstFilledDateFragment(
            FirstFilledOperand first, FirstFilledOperand second, FirstFilledOperand... rest) {
        return new DateExpr(firstFilledValue(
                first, ValueKind.DATE_FRAGMENT, "firstFilledDateFragment", second, rest));
    }

    /** {@code FirstFilledValue(…)} over date-range repetitions — the first specified interval. catalog:FirstFilledValue */
    public static DateRangeExpr firstFilledDateRange(FieldListRef dateRangeScope) {
        requireKind(dateRangeScope, ValueKind.DATE_RANGE, "firstFilledDateRange");
        return new DateRangeExpr(new FirstFilledValue(dateRangeScope));
    }

    /** Group form; every descendant must be a date-range value. */
    public static DateRangeExpr firstFilledDateRange(FirstFilledOperand group) {
        return new DateRangeExpr(firstFilledValue(group, ValueKind.DATE_RANGE, "firstFilledDateRange"));
    }

    /** Multi-operand date-range {@code FirstFilledValue}, preserving authored encounter order. */
    public static DateRangeExpr firstFilledDateRange(
            FieldListRef dateRangeScope, FieldOperand second, FieldOperand... rest) {
        return new DateRangeExpr(firstFilledValue(
                dateRangeScope, ValueKind.DATE_RANGE, "firstFilledDateRange", second, rest));
    }

    /** Direct-field form; the owner requires at least two operands. */
    public static DateRangeExpr firstFilledDateRange(
            FieldOperand first, FieldOperand second, FieldOperand... rest) {
        return new DateRangeExpr(firstFilledValue(
                first, ValueKind.DATE_RANGE, "firstFilledDateRange", second, rest));
    }

    /** Owner-entity form, including semantic-index fields and groups. */
    public static DateRangeExpr firstFilledDateRange(
            FirstFilledOperand first, FirstFilledOperand second, FirstFilledOperand... rest) {
        return new DateRangeExpr(firstFilledValue(
                first, ValueKind.DATE_RANGE, "firstFilledDateRange", second, rest));
    }

    /** {@code FirstFilledValue(…)} over time repetitions — the first specified time. catalog:FirstFilledValue */
    public static TimeExpr firstFilledTime(FieldListRef timeScope) {
        requireKind(timeScope, ValueKind.TIME, "firstFilledTime");
        return new TimeExpr(new FirstFilledValue(timeScope));
    }

    /** Group form; every descendant must be a time value. */
    public static TimeExpr firstFilledTime(FirstFilledOperand group) {
        return new TimeExpr(firstFilledValue(group, ValueKind.TIME, "firstFilledTime"));
    }

    /** Multi-operand time {@code FirstFilledValue}, preserving authored encounter order. */
    public static TimeExpr firstFilledTime(FieldListRef timeScope, FieldOperand second, FieldOperand... rest) {
        return new TimeExpr(firstFilledValue(timeScope, ValueKind.TIME, "firstFilledTime", second, rest));
    }

    /** Direct-field form; the owner requires at least two operands. */
    public static TimeExpr firstFilledTime(FieldOperand first, FieldOperand second, FieldOperand... rest) {
        return new TimeExpr(firstFilledValue(first, ValueKind.TIME, "firstFilledTime", second, rest));
    }

    /** Owner-entity form, including semantic-index fields and groups. */
    public static TimeExpr firstFilledTime(
            FirstFilledOperand first, FirstFilledOperand second, FirstFilledOperand... rest) {
        return new TimeExpr(firstFilledValue(first, ValueKind.TIME, "firstFilledTime", second, rest));
    }

    /** {@code FirstFilledValue(…)} over date-time repetitions — the first specified instant. catalog:FirstFilledValue */
    public static DateTimeExpr firstFilledDateTime(FieldListRef dateTimeScope) {
        requireKind(dateTimeScope, ValueKind.DATE_TIME, "firstFilledDateTime");
        return new DateTimeExpr(new FirstFilledValue(dateTimeScope));
    }

    /** Group form; every descendant must be a date-time value. */
    public static DateTimeExpr firstFilledDateTime(FirstFilledOperand group) {
        return new DateTimeExpr(firstFilledValue(group, ValueKind.DATE_TIME, "firstFilledDateTime"));
    }

    /** Multi-operand date-time {@code FirstFilledValue}, preserving authored encounter order. */
    public static DateTimeExpr firstFilledDateTime(
            FieldListRef dateTimeScope, FieldOperand second, FieldOperand... rest) {
        return new DateTimeExpr(firstFilledValue(
                dateTimeScope, ValueKind.DATE_TIME, "firstFilledDateTime", second, rest));
    }

    /** Direct-field form; the owner requires at least two operands. */
    public static DateTimeExpr firstFilledDateTime(
            FieldOperand first, FieldOperand second, FieldOperand... rest) {
        return new DateTimeExpr(firstFilledValue(
                first, ValueKind.DATE_TIME, "firstFilledDateTime", second, rest));
    }

    /** Owner-entity form, including semantic-index fields and groups. */
    public static DateTimeExpr firstFilledDateTime(
            FirstFilledOperand first, FirstFilledOperand second, FirstFilledOperand... rest) {
        return new DateTimeExpr(firstFilledValue(
                first, ValueKind.DATE_TIME, "firstFilledDateTime", second, rest));
    }

    /** {@code NumberOfFilledGroups(/RootGroup*)} — the count of a repeatable root group's filled repetitions. catalog:NumberOfFilledGroups */
    public static NumberExpr numberOfFilledRootGroups(GroupRef rootGroup) {
        return new NumberExpr(new RootGroupCount(rootGroup));
    }

    /** {@code FirstFilledValue(…)} over a string repetition list — the first specified value, as a string. catalog:FirstFilledValue */
    public static StringExpr firstFilledString(FieldListRef stringScope) {
        requireKind(stringScope, ValueKind.STRING, "firstFilledString");
        return new StringExpr(new FirstFilledValue(stringScope));
    }

    /** Group form for descendant string fields. */
    public static StringExpr firstFilledString(FirstFilledOperand group) {
        return new StringExpr(firstFilledValue(group, ValueKind.STRING, "firstFilledString"));
    }

    /** Multi-operand {@code FirstFilledValue}: each later string field/list is visited only if no earlier operand supplied the result. */
    public static StringExpr firstFilledString(FieldListRef stringScope, FieldOperand second, FieldOperand... rest) {
        return new StringExpr(firstFilledValue(stringScope, ValueKind.STRING, "firstFilledString", second, rest));
    }

    /** Direct-field form; the owner requires at least two operands. */
    public static StringExpr firstFilledString(FieldOperand first, FieldOperand second, FieldOperand... rest) {
        return new StringExpr(firstFilledValue(first, ValueKind.STRING, "firstFilledString", second, rest));
    }

    /** Owner-entity form, including semantic-index fields and groups. */
    public static StringExpr firstFilledString(
            FirstFilledOperand first, FirstFilledOperand second, FirstFilledOperand... rest) {
        return new StringExpr(firstFilledValue(first, ValueKind.STRING, "firstFilledString", second, rest));
    }

    /** {@code FirstFilledValue(…)} over custom-typed repetitions — custom values use A12's string result family. catalog:FirstFilledValue */
    public static StringExpr firstFilledCustom(FieldListRef customScope) {
        requireKind(customScope, ValueKind.CUSTOM, "firstFilledCustom");
        return new StringExpr(new FirstFilledValue(customScope));
    }

    /** Group form for the String/Custom/Enumeration owner family. */
    public static StringExpr firstFilledCustom(FirstFilledOperand group) {
        return new StringExpr(firstFilledValue(group, ValueKind.CUSTOM, "firstFilledCustom"));
    }

    /** Multi-operand custom-typed {@code FirstFilledValue}, preserving authored encounter order. */
    public static StringExpr firstFilledCustom(
            FieldListRef customScope, FieldOperand second, FieldOperand... rest) {
        return new StringExpr(firstFilledValue(
                customScope, ValueKind.CUSTOM, "firstFilledCustom", second, rest));
    }

    /** Direct-field form; the owner requires at least two operands. */
    public static StringExpr firstFilledCustom(
            FieldOperand first, FieldOperand second, FieldOperand... rest) {
        return new StringExpr(firstFilledValue(
                first, ValueKind.CUSTOM, "firstFilledCustom", second, rest));
    }

    /** Owner-entity form, including semantic-index fields and groups. */
    public static StringExpr firstFilledCustom(
            FirstFilledOperand first, FirstFilledOperand second, FirstFilledOperand... rest) {
        return new StringExpr(firstFilledValue(
                first, ValueKind.CUSTOM, "firstFilledCustom", second, rest));
    }

    /** {@code FirstFilledValue(…)} over yes/no repetitions — confirm fields remain owner-forbidden. catalog:FirstFilledValue */
    public static BoolExpr firstFilledBoolean(FieldListRef booleanScope) {
        requireKind(booleanScope, ValueKind.BOOLEAN, "firstFilledBoolean");
        return new BoolExpr(new FirstFilledValue(booleanScope));
    }

    /** Group form for descendant Boolean fields. */
    public static BoolExpr firstFilledBoolean(FirstFilledOperand group) {
        return new BoolExpr(firstFilledValue(group, ValueKind.BOOLEAN, "firstFilledBoolean"));
    }

    /** Multi-operand yes/no {@code FirstFilledValue}, preserving authored encounter order. */
    public static BoolExpr firstFilledBoolean(
            FieldListRef booleanScope, FieldOperand second, FieldOperand... rest) {
        return new BoolExpr(firstFilledValue(
                booleanScope, ValueKind.BOOLEAN, "firstFilledBoolean", second, rest));
    }

    /** Direct-field form; the owner requires at least two operands. */
    public static BoolExpr firstFilledBoolean(
            FieldOperand first, FieldOperand second, FieldOperand... rest) {
        return new BoolExpr(firstFilledValue(
                first, ValueKind.BOOLEAN, "firstFilledBoolean", second, rest));
    }

    /** Owner-entity form, including semantic-index fields and groups. */
    public static BoolExpr firstFilledBoolean(
            FirstFilledOperand first, FirstFilledOperand second, FirstFilledOperand... rest) {
        return new BoolExpr(firstFilledValue(
                first, ValueKind.BOOLEAN, "firstFilledBoolean", second, rest));
    }

    /** {@code FirstFilledValue(…)} over an enum repetition list — the first specified value, as an enum. catalog:FirstFilledValue */
    public static EnumExpr firstFilledEnum(FieldListRef enumScope) {
        requireKind(enumScope, ValueKind.ENUMERATION, "firstFilledEnum");
        return new EnumExpr(new FirstFilledValue(enumScope));
    }

    /** Group form for the String/Custom/Enumeration owner family. */
    public static EnumExpr firstFilledEnum(FirstFilledOperand group) {
        return new EnumExpr(enumFirstFilledValue(
                firstFilledValue(group, ValueKind.ENUMERATION, "firstFilledEnum")));
    }

    /** Multi-operand {@code FirstFilledValue}: each later enum field/list is visited only if no earlier operand supplied the result. */
    public static EnumExpr firstFilledEnum(FieldListRef enumScope, FieldOperand second, FieldOperand... rest) {
        return new EnumExpr(enumFirstFilledValue(
                firstFilledValue(enumScope, ValueKind.ENUMERATION, "firstFilledEnum", second, rest)));
    }

    /** Direct-field form; the owner requires at least two operands. */
    public static EnumExpr firstFilledEnum(FieldOperand first, FieldOperand second, FieldOperand... rest) {
        return new EnumExpr(enumFirstFilledValue(
                firstFilledValue(first, ValueKind.ENUMERATION, "firstFilledEnum", second, rest)));
    }

    /** Owner-entity form, including semantic-index fields and groups. */
    public static EnumExpr firstFilledEnum(
            FirstFilledOperand first, FirstFilledOperand second, FirstFilledOperand... rest) {
        return new EnumExpr(enumFirstFilledValue(
                firstFilledValue(first, ValueKind.ENUMERATION, "firstFilledEnum", second, rest)));
    }

    private static void requireKind(FieldListRef scope, ValueKind expected,
            String method) {
        if (scope.kind() != expected) {
            throw new IllegalArgumentException(method + " needs a " + expected + " field list; "
                    + scope.renderedPath() + " is " + scope.kind());
        }
    }

    private static FirstFilledValue firstFilledValue(FirstFilledOperand group, ValueKind expected, String method) {
        // Built before the routing check, whose kind() of a group would run the list's type gate ahead of its
        // root gate (the reason given in the list form below).
        FirstFilledValue value = new FirstFilledValue(group, List.of());
        if (!compatibleFirstFilledKind(expected, group.kind())) {
            throw new IllegalArgumentException(method + " needs a group compatible with " + expected
                    + "; " + group.renderedPath() + " is " + group.kind());
        }
        return value;
    }

    private static FirstFilledValue firstFilledValue(FieldOperand first, ValueKind expected, String method,
            FieldOperand second, FieldOperand... rest) {
        FirstFilledOperand[] convertedRest = java.util.Arrays.stream(rest)
                .map(FieldOperand::firstFilledOperand)
                .toArray(FirstFilledOperand[]::new);
        return firstFilledValue(first.firstFilledOperand(), expected, method,
                second.firstFilledOperand(), convertedRest);
    }

    private static FirstFilledValue firstFilledValue(FirstFilledOperand first, ValueKind expected, String method,
            FirstFilledOperand second, FirstFilledOperand... rest) {
        // The record's list gates answer first, in A12's order, and the routing check only after them: a group's
        // kind() runs the type gate over that group alone, which would cite a type code on a list A12 refuses as
        // a duplicate (validate.laws.FirstFilledValueGateOrderLawsTest). Whether the trailing operands may join
        // the first is one kernel rule with one home, the record; the factory only routes the result family.
        var trailing = new ArrayList<FirstFilledOperand>();
        trailing.add(second);
        trailing.addAll(List.of(rest));
        FirstFilledValue value = new FirstFilledValue(first, trailing);
        if (!compatibleFirstFilledKind(expected, first.kind())) {
            throw new IllegalArgumentException(method + " needs a first operand compatible with " + expected + "; "
                    + "the first operand is " + first.kind());
        }
        return value;
    }

    private static FirstFilledValue enumFirstFilledValue(FirstFilledValue value) {
        if (value.kind() != ValueKind.ENUMERATION) {
            throw new IllegalArgumentException("firstFilledEnum needs an enumeration result; mixed "
                    + "String/Custom/Enumeration operands produce STRING and belong in firstFilledString");
        }
        return value;
    }

    /** Whether a factory named for {@code expected} may build a list its first operand types as {@code actual}. */
    private static boolean compatibleFirstFilledKind(ValueKind expected, ValueKind actual) {
        return FirstFilledValue.sameOwnerFamily(expected, actual);
    }

    private static NumberExpr listAggregate(ListAggregate.AggOp op, AggregateOperand first,
            AggregateOperand... rest) {
        var operands = new ArrayList<AggregateOperand>();
        operands.add(first);
        operands.addAll(List.of(rest));
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

    /**
     * A date-time constant for comparison — {@code [ScheduledAt] > dateTimeValue("01.01.2022T17:00:00")}
     * ({@code dd.MM.yyyy'T'HH:mm:ss}), the temporal-literal family's date-time twin of {@link #dateValue}/
     * {@link #timeValue}.
     */
    public static DateTimeExpr dateTimeValue(String ddMMyyyyThhmmss) {
        return new DateTimeExpr(new DateTimeLiteral(ddMMyyyyThhmmss));
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

    /** {@code DifferenceInMonths(a, b)} — the signed whole-month date difference, truncated toward zero. catalog:DifferenceInMonths */
    public static NumberExpr differenceInMonths(DateOperand a, DateOperand b) {
        return new NumberExpr(new DateDifference(DateDifference.Unit.MONTHS, a.expression(), b.expression()));
    }

    /** {@code DifferenceInYears(a, b)} — the signed whole-year date difference, truncated toward zero. catalog:DifferenceInYears */
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
                new DateConstruction.SplitYear(century.expression(), decade.expression())));
    }

    /**
     * A quoted Date/Time component literal whose lexical digits are retained exactly. Prefer this over
     * {@link #number(String)} when a component's width is significant, such as {@code "00"} Short-Year.
     */
    public static TemporalComponent temporalComponent(String digits) {
        return new TemporalComponentLiteral(digits);
    }

    /**
     * {@code Date(day, month, year)} with the kernel's complete component union: a bounded Number field, an
     * exact digit-pattern String field, a matching extractor/constant, or a field declared exactly
     * {@code yyyy} at the Year position — on ANY date-bearing kind, since that gate reads the declaration
     * and not the kind (KF271). The kernel validates each field declaration against its position.
     * catalog:Date
     */
    public static DateExpr date(TemporalComponent day, TemporalComponent month, TemporalComponent year) {
        return new DateExpr(new DateConstruction(day.expression(), month.expression(), year.expression()));
    }

    /** Four-component century/short-year form over the same mixed component union. */
    public static DateExpr date(TemporalComponent day, TemporalComponent month,
            TemporalComponent century, TemporalComponent shortYear) {
        return new DateExpr(new DateConstruction(day.expression(), month.expression(),
                new DateConstruction.SplitYear(century.expression(), shortYear.expression())));
    }

    /**
     * {@code Date(day, month)} — the year is the model's Base Year. The kernel admits it only when the model
     * declares one and otherwise refuses with {@code MVK_MISSING_YEAR} (KF403); that check needs the model, so
     * it stays the kernel's. catalog:Date
     */
    public static DateExpr date(TemporalComponent day, TemporalComponent month) {
        return new DateExpr(new DateConstruction(day.expression(), month.expression(),
                new DateConstruction.BaseYearImplied()));
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

    /** {@code Time(component)} over a field, quoted numeric constant, or matching Hour extractor. catalog:Time */
    public static TimeExpr time(TemporalComponent hour) {
        return new TimeExpr(new TimeConstruction(List.of(hour.expression())));
    }

    /** {@code Time(hour, minute)} over a mixed prefix of legal temporal components. catalog:Time */
    public static TimeExpr time(TemporalComponent hour, TemporalComponent minute) {
        return new TimeExpr(new TimeConstruction(List.of(hour.expression(), minute.expression())));
    }

    /** {@code Time(hour, minute, second)} over a mixed prefix of legal temporal components. catalog:Time */
    public static TimeExpr time(TemporalComponent hour, TemporalComponent minute, TemporalComponent second) {
        return new TimeExpr(new TimeConstruction(List.of(
                hour.expression(), minute.expression(), second.expression())));
    }

    /** {@code DateTime(date, time)} — combine a date and a time into a comparable date-time. catalog:DateTime */
    public static DateTimeExpr dateTime(DateOperand date, TimeOperand time) {
        return new DateTimeExpr(new DateTimeConstruction(date.expression(), time.expression()));
    }

    /**
     * {@code DateRange(start, end)} — construct a date-range value from two dates (the kernel's
     * {@code IDateRangeConstructionOperation}). The result can be assigned to a {@code DATE_RANGE}
     * field or compared to one with {@code ==}/{@code !=}; see {@link DateRangeConstruction}.
     * catalog:DateRange
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
            // The admitted set is narrower than the code's name: `isStringEnumOrExtensibleEnum`'s third
            // disjunct is a FLAG on a string type, not a kind, so an extensible enum arrives here as STRING
            // and a CustomFieldType is refused by the kernel too (ValidityConditionOperandLawsTest).
            throw Refusals.of(KernelCode.MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM,
                    "Valid/Invalid(field, \"Type\") needs a STRING or ENUMERATION field, but "
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

    /** {@code StartOfDateRange(Range For key)} — the start bound of a semantically-indexed range cell. catalog:StartOfDateRange */
    public static DateExpr startOfDateRange(DateRangeIndexedValue range) {
        return new DateExpr(new DateRangeBound(DateRangeBound.Bound.START, range.expression()));
    }

    /** {@code EndOfDateRange(Range For key)} — the end bound of a semantically-indexed range cell. catalog:EndOfDateRange */
    public static DateExpr endOfDateRange(DateRangeIndexedValue range) {
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

    // ── the extraction families over a bare Calculation ───────────────────────────────────────────

    /**
     * {@code <unit>FromDate}/{@code <unit>FromTime} over an arbitrary {@link Calculation}, gated against the
     * operand's DECLARED FORMAT — the entry point for a caller reading a parsed AST rather than writing Java
     * against the typed handles, which already carry their format because {@link BoundModel} only issues them
     * for a field whose declaration fits.
     *
     * <p>Named for the family rather than per operator, unlike the typed factories above: this path takes the
     * unit as a value, which is how an AST reader holds it, and nine near-identical overloads would restate
     * one rule nine times.
     *
     * <p>The gate is exactly the measured one — an extraction is refused iff the operand's declared format
     * lacks the unit's component (KERNEL-FINDINGS KF426, locked by
     * {@code validate.laws.TemporalExtractionFormatGateLawsTest}). An operand whose format cannot be resolved
     * — an expression, a literal, anything but a plain field read — is DELEGATED rather than refused: there is
     * nothing to read, and refusing would state a rule nothing measured.
     *
     * <p><b>A format outside the admitted vocabulary is judged by the same letter scan, deliberately.</b> The
     * measurement covers the twelve strings the kernel admits, and a kernel-free binding (a lenient load, or a
     * structural-only expand) can present any string at all. Scanning it is not an unmeasured widening of the
     * rule but the kernel's own keying applied to a declaration the kernel would reject at the MODEL level, so
     * no extraction verdict over it is meaningful either way; refusing on the component it plainly lacks is
     * the more useful of two moot answers. The vocabulary itself is owned and published by
     * {@code validate.laws.TemporalFormatVocabularyGuardTest}.
     *
     * @throws IllegalArgumentException if the source is not a point in time at all
     *     ({@code MVK_NO_DATE}, stated by {@link DateExtraction} itself), if its declared format lacks the
     *     component {@code unit} extracts ({@code MVK_WRONG_DATE_FORMAT_FOR_OP}), or if its declared
     *     precision leaves that component unknown ({@code MVK_INVALID_DATE_TYPE}).
     */
    public static DateExtraction dateExtraction(BoundModel model, DateExtraction.Unit unit, Calculation source) {
        TemporalFormats.Component wanted = componentOf(unit);
        String declared = declaredFormatOf(model, source);
        if (declared != null && !TemporalFormats.componentsOf(declared).contains(wanted)) {
            throw wrongFormat(unit.operatorId(), model, source, declared,
                    "the " + wanted.name().toLowerCase(Locale.ROOT));
        }
        String precision = declaredPrecisionOf(model, source);
        if (precision != null && DatePrecisions.speaksAbout(wanted)
                && !DatePrecisions.guaranteedComponents(precision).contains(wanted)) {
            throw wrongPrecision(unit.operatorId(), source, precision, wanted);
        }
        return new DateExtraction(unit, source);
    }

    /**
     * The operand's declared date precision, or {@code null} when this rule has nothing to read — an operand
     * that is not a plain field read, or a field declaring no precision, which means FULL.
     */
    private static String declaredPrecisionOf(BoundModel model, Calculation source) {
        FieldRef read = switch (source) {
            case FieldValue value -> value.field();
            case IndexedFieldValue indexed -> indexed.field();
            default -> null;
        };
        return read == null ? null : model.datePrecision(read.path().value());
    }

    /** The precision gate's refusal — a separate code from the format gate's, as the kernel reports it. */
    private static IllegalArgumentException wrongPrecision(String operatorId, Calculation source,
            String precision, TemporalFormats.Component wanted) {
        String path = switch (source) {
            case FieldValue value -> value.field().path().value();
            case IndexedFieldValue indexed -> indexed.field().path().value();
            default -> "the operand";
        };
        return Refusals.of(KernelCode.MVK_INVALID_DATE_TYPE, operatorId + " needs a source that guarantees "
                + "the " + wanted.name().toLowerCase(Locale.ROOT) + "; '" + path
                + "' is declared " + precision + ", which admits an unknown "
                + wanted.name().toLowerCase(Locale.ROOT));
    }

    /**
     * {@code DateFromDateTime}/{@code TimeFromDateTime} over an arbitrary {@link Calculation} — the split
     * family's peer of {@link #dateExtraction}. Both units need a source carrying BOTH halves, which over the
     * admitted format vocabulary only the ISO date-time does.
     *
     * @throws IllegalArgumentException on a non-point-in-time source ({@code MVK_NO_DATE}) or a declared
     *     format missing either half ({@code MVK_WRONG_DATE_FORMAT_FOR_OP}).
     */
    public static DateTimePart dateTimePart(BoundModel model, DateTimePart.Unit unit, Calculation source) {
        String declared = declaredFormatOf(model, source);
        if (declared != null && !TemporalFormats.carriesBothHalves(declared)) {
            throw wrongFormat(unit.operatorId(), model, source, declared, "both a date and a time component");
        }
        return new DateTimePart(unit, source);
    }

    /** The component each extraction unit reads — the kernel's own keying (KF426). */
    private static TemporalFormats.Component componentOf(DateExtraction.Unit unit) {
        return switch (unit) {
            case DAY -> TemporalFormats.Component.DAY;
            case MONTH, QUARTER -> TemporalFormats.Component.MONTH;
            case YEAR -> TemporalFormats.Component.YEAR;
            case HOURS -> TemporalFormats.Component.HOUR;
            case MINUTES -> TemporalFormats.Component.MINUTE;
            case SECONDS -> TemporalFormats.Component.SECOND;
        };
    }

    /**
     * The format the operand's declaration resolves to, or {@code null} when this rule has nothing to read —
     * which is every case the kernel keeps: an operand that reads no single field (an expression or a literal
     * carries no declaration), a kind outside the component rule's domain (the non-temporal refusal is
     * {@link DateExtraction}'s own {@code MVK_NO_DATE}), and a fragment declaring no format, whose default was
     * never measured because the builder requires one.
     *
     * <p>An INDEX-SELECTED cell reads the same declaration as a plain read of the same field, because
     * selecting a row cannot change one that belongs to the field — measured in
     * {@code validate.laws.IndexedTemporalOperandFormatLawsTest} rather than assumed, which is why it was
     * delegated until then.
     */
    private static String declaredFormatOf(BoundModel model, Calculation source) {
        // An operand with no declaration of its own answers its KIND's default format — the same rule KF426
        // measures for a field declaring none, reaching the operands that are not field reads. Measured over
        // the complete unit vocabulary for the three point-in-time constants (KF432) and for every derived
        // temporal shape the grammar admits as an extraction source (KF442), each in BOTH directions: the
        // DATE arms admit date units and refuse the hour, DATE_TIME admits both, TIME inverts it.
        //
        // The three shapes NOT listed here — FirstFilledValue, the temporal extrema and ValueAsDate — render
        // plausibly and the kernel's parser rejects them in an extraction slot, so they cannot reach this gate
        // and delegating them costs nothing.
        if (source instanceof Today || source instanceof Now || source instanceof BaseYear
                || source instanceof DateShift || source instanceof DateTimePart
                || source instanceof DateRangeBound) {
            return TemporalFormats.defaultFormat(source.kind());
        }
        FieldRef read = switch (source) {
            case FieldValue value -> value.field();
            case IndexedFieldValue indexed -> indexed.field();
            default -> null;
        };
        if (read == null) {
            return null;
        }
        String path = read.path().value();
        if (model.fieldAt(path).isEmpty()) {
            // A caller that passed a model the operand does not come from has a wiring bug, and it must not
            // arrive as an IllegalArgumentException: that is the refusal type, so it would read as a kernel
            // verdict about the rule rather than a mistake about the model.
            throw new IllegalStateException(path + " is not a field of the model passed to this factory");
        }
        ValueKind kind = read.kind();
        if (!TemporalFormats.readsComponents(kind)) {
            return null;
        }
        String declared = model.temporalFormat(path);
        if (declared != null) {
            return declared;
        }
        // Fall back only where the kind's default was measured (KF426 covers DATE/TIME/DATE_TIME). A
        // fragment declares its format or cannot be bound at all, so it never reaches here.
        return TemporalFormats.isTemporal(kind) ? TemporalFormats.defaultFormat(kind) : null;
    }

    private static IllegalArgumentException wrongFormat(String operatorId, BoundModel model, Calculation source,
            String declared, String wanted) {
        String path = source instanceof FieldValue value ? value.field().path().value() : "the operand";
        return Refusals.of(KernelCode.MVK_WRONG_DATE_FORMAT_FOR_OP,
                operatorId + " needs a source whose declared format carries "
                        + wanted + "; '" + path + "' is declared " + declared);
    }

    /** Integral convenience overload for {@code AddDays(date, n)}; the {@link NumberExpr} overload accepts any
     *  signed numeric amount. Yields a comparable date. catalog:AddDays */
    public static DateExpr addDays(DateOperand date, long amount) {
        return addDays(date, number(Long.toString(amount)));
    }

    /** {@code AddDays(dateTime, n)} — shifts a date-time by whole wall days while preserving its date-time type. */
    public static DateTimeExpr addDays(DateTimeOperand dateTime, long amount) {
        return addDays(dateTime, number(Long.toString(amount)));
    }

    /** Integral convenience overload for {@code AddMonths(date, n)}; the {@link NumberExpr} overload accepts any
     *  signed numeric amount. Yields a date. catalog:AddMonths */
    public static DateExpr addMonths(DateOperand date, long amount) {
        return addMonths(date, number(Long.toString(amount)));
    }

    /** Integral convenience overload for {@code AddYears(date, n)}; the {@link NumberExpr} overload accepts any
     *  signed numeric amount. Yields a date. catalog:AddYears */
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

    /** Integral convenience overload for {@code AddMinutes(dateTime, n)}; the {@link NumberExpr} overload accepts
     *  any signed numeric amount. catalog:AddMinutes */
    public static DateTimeExpr addMinutes(DateTimeOperand dateTime, long amount) {
        return addMinutes(dateTime, number(Long.toString(amount)));
    }

    /** Integral convenience overload for {@code AddSeconds(dateTime, n)}; the {@link NumberExpr} overload accepts
     *  any signed numeric amount. catalog:AddSeconds */
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
        return new NumberExpr(new NumberOfValueInFields(new BoolLiteral(value), List.<AggregateOperand>of(list)));
    }

    /** {@code NumberOfValueInFields(constant In Group*}{@code /field)} — count over a single flattened
     *  repetition list equal to a string constant. catalog:NumberOfValueInFields */
    public static NumberExpr numberOfValueInFields(String value, FieldListRef list) {
        return new NumberExpr(new NumberOfValueInFields(new StringLiteral(value), List.<AggregateOperand>of(list)));
    }

    /** {@code NumberOfValueInFields(n In Group*}{@code /field)} — count over a single flattened repetition
     *  list equal to an integer constant. catalog:NumberOfValueInFields */
    public static NumberExpr numberOfValueInFields(long value, FieldListRef list) {
        return new NumberExpr(new NumberOfValueInFields(NumberLiteral.of(value), List.<AggregateOperand>of(list)));
    }

    // --- group-scope forms: NumberOfValueInFields(value In Group*) --------------------------------
    // The kernel routes this operator's entities through the same getRefList as the entity-list aggregates, so
    // a starred group is admitted here exactly as it is under Sum, and the kernel EXPANDS it to its contained
    // fields before counting (KF187; validate.laws.ValueInFieldsGroupOperandLawsTest). Until 2026-08-14 the
    // operand list was typed FieldOperand, which made the admitted shape unrepresentable rather than refused —
    // DG34, a type-level erasure with no measured basis. The factories take any GroupListRef, so BOTH repetition
    // shapes are authorable; the note that used to sit here scoped the offer to the starred form because the
    // unstarred one had no measurement on this carrier. It has one now — ACCEPT, on the same fixture and in the
    // same sweep as the rest of the table (validate.laws.GroupOperandCarrierAdmissionLawsTest) — so the scope
    // limit is discharged rather than carried forward as a standing caveat.

    /** {@code NumberOfValueInFields(True In Group*)} — count over a starred group's expansion equal to a
     *  boolean constant. catalog:NumberOfValueInFields */
    public static NumberExpr numberOfValueInFields(boolean value, GroupListRef scope) {
        return new NumberExpr(new NumberOfValueInFields(new BoolLiteral(value), List.<AggregateOperand>of(scope)));
    }

    /** {@code NumberOfValueInFields(constant In Group*)} — count over a starred group's expansion equal to a
     *  string constant. catalog:NumberOfValueInFields */
    public static NumberExpr numberOfValueInFields(String value, GroupListRef scope) {
        return new NumberExpr(new NumberOfValueInFields(new StringLiteral(value), List.<AggregateOperand>of(scope)));
    }

    /** {@code NumberOfValueInFields(n In Group*)} — count over a starred group's expansion equal to an integer
     *  constant. catalog:NumberOfValueInFields */
    public static NumberExpr numberOfValueInFields(long value, GroupListRef scope) {
        return new NumberExpr(new NumberOfValueInFields(NumberLiteral.of(value), List.<AggregateOperand>of(scope)));
    }

    private static List<AggregateOperand> valueInFields(FieldOperand first, FieldOperand second,
            FieldOperand... rest) {
        var fields = new ArrayList<AggregateOperand>();
        fields.add(first);
        fields.add(second);
        fields.addAll(List.of(rest));
        return fields;
    }


    /** {@code NumberOfDifferentValues(…)} — distinct-value count over type-homogeneous operands. catalog:NumberOfDifferentValues */
    public static NumberExpr numberOfDifferentValues(AggregateOperand first, AggregateOperand... rest) {
        var fields = new ArrayList<AggregateOperand>();
        fields.add(first);
        fields.addAll(List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.DIFFERENT_VALUES, fields));
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
                List.of(CountAggregate.scopeOf(repeatableGroup))));
    }

    /** {@code NumberOfFilledGroups(g1, g2, …)} — ≥2 groups by signature. */
    public static NumberExpr numberOfFilledGroups(GroupRef first, GroupRef second, GroupRef... rest) {
        var groups = new ArrayList<GroupRef>();
        groups.add(first);
        groups.add(second);
        groups.addAll(List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_GROUPS,
                CountAggregate.scopesOf(groups)));
    }

    /**
     * {@code NumberOfFilledGroups(s1, s2, …)} over group scopes, each keeping the stars its path carries. It is
     * the form for a group below a starred repeatable level, {@code Batches*}{@code /Seal}, which a
     * {@link GroupRef} cannot spell: the {@link GroupRef} factories star a group exactly when it repeats
     * (KERNEL-FINDINGS KF462). A sole scope needs a star somewhere on its path. catalog:NumberOfFilledGroups
     */
    public static NumberExpr numberOfFilledGroups(GroupListRef first, GroupListRef... rest) {
        var scopes = new ArrayList<AggregateOperand>();
        scopes.add(first);
        scopes.addAll(List.of(rest));
        return new NumberExpr(new CountAggregate(CountAggregate.CountOp.FILLED_GROUPS, scopes));
    }

    // --- field-list × value-list quantifiers — Name(f1, f2 In v1, v2) --------------------------
    // StringRef entry points keep the field list type-homogeneous; enum refs also domain-check values.
    // The scalar-cell arm keeps keyed identity and delegates kind/domain admission to the kernel.

    /** {@code AtLeastOneFieldValueIncludedInValueList(f1, f2 In "v1", "v2")} — at least one listed field's value is in the value list. catalog:AtLeastOneFieldValueIncludedInValueList */
    public static Condition atLeastOneFieldValueIncludedInValueList(
            List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    /** {@code NoFieldValueIncludedInValueList(f1, f2 In "v1", "v2")} — none of the listed fields' values is in the value list. catalog:NoFieldValueIncludedInValueList */
    public static Condition noFieldValueIncludedInValueList(
            List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    /** {@code NotAllFieldValuesIncludedInValueList(f1, f2 In "v1", "v2")} — at least one listed field's value is NOT in the value list. catalog:NotAllFieldValuesIncludedInValueList */
    public static Condition notAllFieldValuesIncludedInValueList(
            List<String> values, StringRef first, StringRef... rest) {
        return stringListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, first, rest);
    }

    /** A literal-list quantifier over plain or indexed scalar field cells (DG71). catalog:AtLeastOneFieldValueIncludedInValueList */
    public static Condition atLeastOneFieldValueIncludedInValueList(
            List<String> values, FieldOperand.Cell first, FieldOperand.Cell... rest) {
        return cellListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    /** A literal-list quantifier over plain or indexed scalar field cells (DG71). catalog:NoFieldValueIncludedInValueList */
    public static Condition noFieldValueIncludedInValueList(
            List<String> values, FieldOperand.Cell first, FieldOperand.Cell... rest) {
        return cellListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    /** A literal-list quantifier over plain or indexed scalar field cells (DG71). catalog:NotAllFieldValuesIncludedInValueList */
    public static Condition notAllFieldValuesIncludedInValueList(
            List<String> values, FieldOperand.Cell first, FieldOperand.Cell... rest) {
        return cellListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, first, rest);
    }

    // Group-scope value-list quantifiers — the field side is a GROUP, whose contained fields the kernel expands
    // (DG35). These are separate entry points rather than overloads of the ones above because those are typed BY
    // KIND (StringRef / EnumRef), which is how they erase MVK_VARYING_TYPES_NOT_ALLOWED without consulting a
    // model — and a group has no kind to type them with. Its expansion's kind is the kernel's verdict, so this
    // arm delegates that gate instead of inventing one. Admission measured in both repetition shapes and on both
    // value sides: rulekit.validate.laws.EntityListValueListGroupOperandLawsTest, KERNEL-FINDINGS kf187.

    /** {@code AtLeastOneFieldValueIncludedInValueList(G* In "v1", "v2")} — over a group's expanded fields. catalog:AtLeastOneFieldValueIncludedInValueList */
    public static Condition atLeastOneFieldValueIncludedInValueList(
            List<String> values, GroupListRef group) {
        return groupScopeValueListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, group);
    }

    /** {@code NoFieldValueIncludedInValueList(G* In "v1", "v2")} — over a group's expanded fields. catalog:NoFieldValueIncludedInValueList */
    public static Condition noFieldValueIncludedInValueList(
            List<String> values, GroupListRef group) {
        return groupScopeValueListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, group);
    }

    /** {@code NotAllFieldValuesIncludedInValueList(G* In "v1", "v2")} — over a group's expanded fields. catalog:NotAllFieldValuesIncludedInValueList */
    public static Condition notAllFieldValuesIncludedInValueList(
            List<String> values, GroupListRef group) {
        return groupScopeValueListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, group);
    }

    private static Condition groupScopeValueListQuantifier(
            ValueListQuantifier q, List<String> values, GroupListRef group) {
        var literals = values.stream().<Calculation>map(StringLiteral::new).toList();
        return new FieldListValueListCondition(q, List.of(group), literals);
    }

    // Enum field-list value-list quantifiers. Each literal is admitted against the UNION of the selected
    // members' domains (a direct EnumRef contributes its stored values, a CategoryRef its category
    // values) — a literal need not be in every listed domain; a direct ref and a category ref on the
    // same field are distinct members; an exact repeated reference is rejected (KERNEL-FINDINGS §8,
    // SPEC-2026-07-22-05). The EnumRef-only overloads stay as binary-compatible bridges; the EnumListRef
    // overloads add the mixed direct/category authoring surface.

    public static Condition atLeastOneFieldValueIncludedInValueList(
            List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, enumListRefs(first, rest));
    }

    public static Condition noFieldValueIncludedInValueList(
            List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, enumListRefs(first, rest));
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            List<String> values, EnumRef first, EnumRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, enumListRefs(first, rest));
    }

    public static Condition atLeastOneFieldValueIncludedInValueList(
            List<String> values, EnumListRef first, EnumListRef... rest) {
        return enumListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, enumListRefs(first, rest));
    }

    public static Condition noFieldValueIncludedInValueList(
            List<String> values, EnumListRef first, EnumListRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, enumListRefs(first, rest));
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            List<String> values, EnumListRef first, EnumListRef... rest) {
        return enumListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, enumListRefs(first, rest));
    }

    /** {@code DateRangesOverlap(r1, r2, …)} — true iff the date ranges of the listed fields overlap. catalog:DateRangesOverlap */
    public static Condition dateRangesOverlap(FieldOperand first, FieldOperand... rest) {
        var fields = new ArrayList<FieldOperand>();
        fields.add(first);
        fields.addAll(List.of(rest));
        return new EntityListPredicate(Quantifier.DATE_RANGES_OVERLAP, List.copyOf(fields));
    }

    /** {@code AtLeastOneDateRangeOverlaps(range IN r1, r2, …)} — {@code range} overlaps at least one listed range. catalog:AtLeastOneDateRangeOverlaps */
    public static Condition atLeastOneDateRangeOverlaps(FieldOperand range, FieldOperand first, FieldOperand... rest) {
        var values = new ArrayList<ValueListOperand>();
        values.add(first);
        values.addAll(List.of(rest));
        return new FieldListEntityListCondition(ValueListQuantifier.AT_LEAST_ONE_DATE_RANGE_OVERLAPS,
                List.<ValueListOperand>of(range), values);
    }

    private static Condition stringListQuantifier(
            ValueListQuantifier q, List<String> values, StringRef first, StringRef... rest) {
        var fields = new ArrayList<ValueListOperand>();
        fields.add(first.field());
        for (StringRef r : rest) {
            fields.add(r.field());
        }
        var literals = values.stream().<Calculation>map(StringLiteral::new).toList();
        return new FieldListValueListCondition(q, fields, literals);
    }

    private static Condition cellListQuantifier(
            ValueListQuantifier q, List<String> values, FieldOperand.Cell first, FieldOperand.Cell... rest) {
        var fields = new ArrayList<ValueListOperand>();
        fields.add(first);
        fields.addAll(List.of(rest));
        var literals = values.stream().<Calculation>map(StringLiteral::new).toList();
        return new FieldListValueListCondition(q, fields, literals);
    }

    private static List<EnumListRef> enumListRefs(EnumListRef first, EnumListRef... rest) {
        var refs = new ArrayList<EnumListRef>();
        refs.add(first);
        refs.addAll(List.of(rest));
        return refs;
    }

    private static Condition enumListQuantifier(
            ValueListQuantifier q, List<String> values, List<EnumListRef> refs) {
        var operands = new ArrayList<ValueListOperand>();
        var union = new java.util.LinkedHashSet<String>();
        for (EnumListRef ref : refs) {
            operands.add(ref.listOperand());
            union.addAll(ref.admissibleValues());   // direct → stored values; category → category values
        }
        var literals = new ArrayList<Calculation>();
        for (String v : values) {
            if (!union.contains(v)) {
                throw Refusals.of(KernelCode.MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY,
                        "'" + v + "' is not in the union of the selected"
                                + " enumeration/category domains " + union);
            }
            literals.add(new EnumValueLiteral(v));
        }
        return new FieldListValueListCondition(q, operands, literals);
    }

    // shape B over flattened repetition lists (string-kind fields; the corpus's dominant shape) —
    // enum-kind starred lists are deferred until FieldListRef carries the enum domain.

    public static Condition atLeastOneFieldValueIncludedInValueList(
            List<String> values, FieldListRef first, FieldListRef... rest) {
        return flattenedListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            List<String> values, FieldListRef first, FieldListRef... rest) {
        return flattenedListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, values, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            List<String> values, FieldListRef first, FieldListRef... rest) {
        return flattenedListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, values, first, rest);
    }

    private static Condition flattenedListQuantifier(
            ValueListQuantifier q, List<String> values, FieldListRef first, FieldListRef... rest) {
        // Starred (flattened) lists are wildcard scopes, and the kernel's DIRECT gate
        // (CheckEntityListenUtils.checkDuplicatesInDirectRefs) skips a wildcarded ref, so an identical one may
        // occupy two slots and there is no dedup here. That is one gate, not an exemption from duplicate
        // checking: the indirect-overlap arm (MVK_DUPLICATE_PARAM2) is unaffected by the star and stays
        // kernel-arbitrated, as it is for every operand list.
        var fields = new ArrayList<ValueListOperand>();
        fields.add(checkedStringList(first));
        for (FieldListRef r : rest) {
            fields.add(checkedStringList(r));
        }
        var literals = values.stream().<Calculation>map(StringLiteral::new).toList();
        return new FieldListValueListCondition(q, fields, literals);
    }

    private static FieldListRef checkedStringList(FieldListRef ref) {
        if (ref.kind() != ValueKind.STRING) {
            throw Refusals.of(KernelCode.MVK_INVALID_TYPES_FOR_COMPARISON,
                    "value-list quantifiers over repetition lists take string fields here; "
                            + ref.renderedPath() + " is " + ref.kind() + " — enum-kind lists are deferred");
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
            List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, valueFields, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, valueFields, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            List<FieldOperand> valueFields, FieldOperand first, FieldOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, valueFields, first, rest);
    }

    public static Condition atLeastOneFieldValueIncludedInValueList(
            List<? extends ValueListOperand> valueFields, ValueListOperand first, ValueListOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, valueFields, first, rest);
    }

    public static Condition noFieldValueIncludedInValueList(
            List<? extends ValueListOperand> valueFields, ValueListOperand first, ValueListOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, valueFields, first, rest);
    }

    public static Condition notAllFieldValuesIncludedInValueList(
            List<? extends ValueListOperand> valueFields, ValueListOperand first, ValueListOperand... rest) {
        return entityListQuantifier(ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED, valueFields, first, rest);
    }

    private static Condition entityListQuantifier(ValueListQuantifier q,
            List<? extends ValueListOperand> valueFields, ValueListOperand first, ValueListOperand... rest) {
        var fields = new ArrayList<ValueListOperand>();
        fields.add(first);
        fields.addAll(List.of(rest));
        var values = List.<ValueListOperand>copyOf(valueFields);
        checkEntityValuedKinds(q.operatorId(), fields, values);
        return new FieldListEntityListCondition(q, fields, values);
    }

    /** The three base kinds the value-list quantifier family accepts (the kernel's String/Enumeration/Number). */
    private enum ValueListBaseKind { STRING, ENUMERATION, NUMBER }

    /**
     * The declared kind of a value-list operand that HAS one. {@link ValueListOperand} deliberately does not
     * declare {@code kind()}, because its {@link GroupListRef} arm cannot answer it (the kind belongs to the
     * expansion); callers must therefore decide what to do about a group before asking, which is the point.
     */
    private static ValueKind kindOf(ValueListOperand op) {
        return switch (op) {
            case FieldOperand field -> field.kind();
            case EnumCategoryAccess category -> category.kind();
            case GroupListRef group -> throw new IllegalStateException(
                    "a group scope has no declared kind — its kind is the kernel's verdict on the expansion of "
                            + group.renderedPath() + "; the caller must handle the group arm before asking");
        };
    }

    /** Map a {@link io.github.mbackschat.a12.dm.core.ValueKind} to its value-list base kind, or {@code null} if disallowed. */
    private static ValueListBaseKind baseKind(ValueKind kind) {
        return switch (kind) {
            // the kernel treats predefined/custom types as strings for base-type matching
            case STRING, CUSTOM -> ValueListBaseKind.STRING;
            case ENUMERATION -> ValueListBaseKind.ENUMERATION;
            case NUMBER -> ValueListBaseKind.NUMBER;
            default -> null;
        };
    }

    /**
     * The entity-valued (shape-C) KIND gate the kernel applies: every operand across BOTH sides shares one base
     * kind (String/Enumeration/Number, {@code MVK_VARYING_TYPES_NOT_ALLOWED} / {@code MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED}).
     *
     * <p>The duplicate arms over the same combined list belong to {@link FieldListEntityListCondition}'s own
     * constructor, because they are intrinsic and one factory reaches that record without passing here.
     */
    private static void checkEntityValuedKinds(String opName,
            List<ValueListOperand> fields, List<ValueListOperand> values) {
        var combined = new ArrayList<ValueListOperand>(fields);
        combined.addAll(values);
        ValueListBaseKind base = null;
        for (ValueListOperand op : combined) {
            // A GROUP scope has no kind of its own — the base-kind rule applies to its EXPANSION, which recurses
            // into nested subgroups. Re-implementing that extent here to pre-check it is exactly what
            // `AggregateOperand` declined to do, and an over-strict copy would refuse a legal model, so the
            // kernel arbitrates this arm (MVK_VARYING_TYPES_NOT_ALLOWED / MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED).
            // The kernel ADMITS the shape in both slots — measured, EntityListValueListGroupOperandLawsTest.
            if (op instanceof GroupListRef) {
                continue;
            }
            ValueListBaseKind k = baseKind(kindOf(op));
            if (k == null) {
                throw Refusals.of(KernelCode.MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED,
                        opName + " accepts only String, Enumeration, or Number"
                                + " operands; '" + op.renderedPath() + "' is " + kindOf(op));
            }
            if (base == null) {
                base = k;
            } else if (base != k) {
                throw Refusals.of(KernelCode.MVK_VARYING_TYPES_NOT_ALLOWED,
                        opName + " requires all operands (both sides) to share one"
                                + " base kind; found " + base + " and " + k);
            }
        }
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
        return new RepetitionNotUniqueCondition(keys, Optional.empty());
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
        return new EntityListPredicate(quantifier, List.copyOf(fields));
    }

    // --- group-scoped field-fill — the fields inside a group scope (optionally across all reps) ----

    /** {@code AllFieldsFilled(Group*)} — all fields inside the group scope(s) are filled. */
    public static Condition allFieldsFilled(GroupScope first, GroupScope... rest) {
        return groupScopedFieldFill(Quantifier.ALL_FIELDS_FILLED, first, rest);
    }

    /** {@code NoFieldFilled(Group*)} — no field inside the group scope(s) is filled. */
    public static Condition noFieldFilled(GroupScope first, GroupScope... rest) {
        return groupScopedFieldFill(Quantifier.NO_FIELD_FILLED, first, rest);
    }

    /** {@code AtLeastOneFieldFilled(Group*)} — at least one field inside the group scope(s) is filled. */
    public static Condition atLeastOneFieldFilled(GroupScope first, GroupScope... rest) {
        return groupScopedFieldFill(Quantifier.AT_LEAST_ONE_FIELD_FILLED, first, rest);
    }

    /** {@code NotAllFieldsFilled(Group*)} — at least one field inside the group scope(s) is empty. */
    public static Condition notAllFieldsFilled(GroupScope first, GroupScope... rest) {
        return groupScopedFieldFill(Quantifier.NOT_ALL_FIELDS_FILLED, first, rest);
    }

    /** {@code FieldsNotCollectivelyFilled(Group*)} — the group scope's fields are partially filled. */
    public static Condition fieldsNotCollectivelyFilled(GroupScope first, GroupScope... rest) {
        return groupScopedFieldFill(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, first, rest);
    }

    /** {@code NotExactlyOneFieldFilled(Group*)} — not exactly one field inside the group scope(s) is filled. */
    public static Condition notExactlyOneFieldFilled(GroupScope first, GroupScope... rest) {
        return groupScopedFieldFill(Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED, first, rest);
    }

    /** {@code MoreThanOneFieldFilled(Group*)} — two or more fields inside the group scope(s) are filled. */
    public static Condition moreThanOneFieldFilled(GroupScope first, GroupScope... rest) {
        return groupScopedFieldFill(Quantifier.MORE_THAN_ONE_FIELD_FILLED, first, rest);
    }

    /**
     * {@code FieldValuesNotUnique(Group*)} — the values of the fields inside the group scope(s) are not all
     * distinct. The kernel expands the group to its contained fields and compares their cells, so this is the
     * group peer of {@link #fieldValuesNotUnique(FieldOperand, FieldOperand...)} exactly as the seven fill
     * quantifiers above have theirs.
     *
     * <p><b>Two gates behave differently on a group operand</b>, both kernel-measured (KERNEL-FINDINGS
     * {@code kf173}, {@code validate.laws.FieldValuesNotUniqueAdmissionLawsTest}): the <em>arity</em> gate is
     * satisfied by the group slot alone — a group expanding to a single field is admitted where that field named
     * directly is {@code MVK_PARAMSIZE_INVALIDN} — while the <em>comparability</em> gate reads the EXPANSION, so a
     * group whose fields mix categories is {@code MVK_VARYING_TYPES_NOT_ALLOWED} just as the equivalent explicit
     * list would be. That second gate stays kernel-arbitrated (F4) rather than pre-checked here, for the same
     * reason {@link AggregateOperand} delegates it: owning it would mean owning the expansion's extent.
     *
     * <p>Added 2026-08-12 (DG33). It had been unauthorable, and the omission was a <b>type-level erasure with no
     * measured basis</b> — the kernel admitted the shape all along. Widening it also required fixing a real
     * evaluator divergence first: both kernel strategies fire on a duplicate among the expanded cells and the
     * interpreter did not, so authoring this would otherwise have admitted a form that was silently answered
     * wrong ({@code adapter.laws.FieldValuesNotUniqueGroupOperandDiffTest}).
     */
    public static Condition fieldValuesNotUnique(GroupScope first, GroupScope... rest) {
        return groupScopedFieldFill(Quantifier.FIELD_VALUES_NOT_UNIQUE, first, rest);
    }

    private static Condition groupScopedFieldFill(Quantifier q, GroupScope first, GroupScope... rest) {
        var scopes = new ArrayList<AggregateOperand>();
        scopes.add(first);
        scopes.addAll(List.of(rest));
        return new EntityListPredicate(q, scopes);
    }

    /**
     * {@code <quantifier>(a, Group*, …)} over a MIXED entity list — fields, flattened repetition lists and
     * group scopes in any order, exactly as the kernel's entity list admits them (KERNEL-FINDINGS
     * {@code kf187}: accepted in both operand orders). The two families above stay as the ergonomic entry
     * points for a homogeneous list; this is the one a kernel model's read path needs.
     */
    public static Condition entityListPredicate(Quantifier quantifier, List<? extends AggregateOperand> operands) {
        return new EntityListPredicate(quantifier, List.copyOf(operands));
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
     *
     * <p>The named group may be the rule's own group, an ancestor, or a group reached by its condition.
     * A root-level rule reading a descendant's field may name that descendant; a group unavailable to the
     * declaration draws {@code INVALID_GROUP}. A first segment naming no declared root draws
     * {@code INVALID_ROOT_GROUP} (KERNEL-FINDINGS {@code KF214}; {@code MessageGroupFootprintAgreementTest}).
     * Acquiring the {@link GroupRef} from a bound model ensures that the group exists:
     * {@code BoundModel.group(String)} refuses a path that does not resolve, so the token is emitted absolute. The
     * constructor is public and {@code EntityPath} is deliberately permissive, so a hand-built relative ref renders
     * one verbatim. Relative group parameters can also be legal; this helper does not decide admission.
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

    /**
     * {@code GroupFilled($group)} — the group's presence read in the outer repetition environment a
     * {@code Having} captured (the {@code $} correlation, D13). Admitted inside a filter; on a level the
     * enclosing iteration binds and the star does not reopen, the marker is redundant and both spellings
     * behave alike (KERNEL-FINDINGS §9). catalog:GroupFilled
     */
    public static Condition groupFilled(GroupRef group, boolean outer) {
        return new GroupPresenceCondition(group, true, outer);
    }

    /** {@code GroupNotFilled(group)} — the group is absent; negative conditions on a repeatable group need guarding (§5). catalog:GroupNotFilled */
    public static Condition groupNotFilled(GroupRef group) {
        return new GroupPresenceCondition(group, false);
    }

    /** {@code GroupNotFilled($group)} — the {@code $}-correlated peer of {@link #groupFilled(GroupRef, boolean)}. catalog:GroupNotFilled */
    public static Condition groupNotFilled(GroupRef group, boolean outer) {
        return new GroupPresenceCondition(group, false, outer);
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
