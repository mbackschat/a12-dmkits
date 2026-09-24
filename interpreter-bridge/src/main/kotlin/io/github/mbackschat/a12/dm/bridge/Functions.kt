package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal
import io.github.mbackschat.a12.dm.core.ValueKind
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate

import io.github.mbackschat.a12.dm.rulekit.dsl.DateDifference
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExtraction
import io.github.mbackschat.a12.dm.rulekit.dsl.DateLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.DateOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeRef
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeConstruction
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimePart
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRounding
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexFieldValue
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexValue
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedGroupValue
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberOfValueInFields
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef
import io.github.mbackschat.a12.dm.rulekit.dsl.OpListMath
import io.github.mbackschat.a12.dm.rulekit.dsl.Rounding
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeConstruction

/**
 * Value-valued functions — `Expr.Func`, the interpreter's shape for the aggregates, the temporal constructors
 * and the arithmetic helpers.
 *
 * This is where most of rulekit's operand laws are reachable: arity, operand kind, root-group bans, duplicate
 * operands, type homogeneity and the temporal component/position rules all live in the constructors these
 * builders call. Five further classes reach it only because this boundary holds a MODEL, so they are stated
 * here rather than delegated to the kernel: an operand's declared FORMAT ([dateExtraction], [dateTimePart]),
 * its declared PRECISION ([valueAsDate]), the agreement of a date range's two endpoint formats ([dateRange]),
 * a date construction's per-slot NUMBER bounds ([date]), and whether the model DECLARES the base year a
 * point-in-time constant needs ([dateRangeBound]) — each routed through the receiver that can read the
 * declaration, never through the model-free `Dsl` factory of the same name.
 *
 * The name reaching [map] is the catalog's canonical ENGLISH keyword whatever language the condition was
 * authored in; the interpreter's parser canonicalises before the AST exists.
 */
internal class Functions(private val mapping: Mapping) {

    fun map(func: Expr.Func): Calculation {
        val args = func.args
        return when (val name = func.name) {
            "NumberOfFilledFields" -> countOfFilledFields(args)
            "NumberOfFilledGroups" -> countOfFilledGroups(args)
            "NumberOfDifferentValues" -> countOfDifferentValues(args)
            "MaxValue" -> maxValue(args)
            "MinValue" -> minValue(args)
            "Sum" -> sum(args)
            "Max" -> opListMath(OpListMath.Op.MAX, args)
            "Min" -> opListMath(OpListMath.Op.MIN, args)
            "DateRange" -> dateRange(args)
            "SumOfProducts" -> sumOfProducts(args)
            "Date" -> date(args)
            "Time" -> time(args)
            "DateTime" -> dateTime(args)
            "FirstFilledValue" -> firstFilledValue(args)
            "NumberOfValueInFields" -> numberOfValueInFields(args)
            "Abs" -> opListMath(OpListMath.Op.ABS, args)
            "AbsValue" -> absValue(args)
            "Length" -> length(args)
            "RoundDown" -> rounding(Rounding.Mode.DOWN, args)
            "RoundUp" -> rounding(Rounding.Mode.UP, args)
            "RoundAccounting" -> rounding(Rounding.Mode.ACCOUNTING, args)
            "RoundDownValue" -> roundValue(FieldRounding.Mode.ROUND_DOWN, args)
            "RoundUpValue" -> roundValue(FieldRounding.Mode.ROUND_UP, args)
            "RoundAccountingValue" -> roundValue(FieldRounding.Mode.ROUND_ACCOUNTING, args)
            "CurrentRepetition" -> currentRepetition(args)
            "YearFromDate" -> dateExtraction(DateExtraction.Unit.YEAR, args)
            "MonthFromDate" -> dateExtraction(DateExtraction.Unit.MONTH, args)
            "DayFromDate" -> dateExtraction(DateExtraction.Unit.DAY, args)
            "QuarterFromDate" -> dateExtraction(DateExtraction.Unit.QUARTER, args)
            "HoursFromTime" -> dateExtraction(DateExtraction.Unit.HOURS, args)
            "MinutesFromTime" -> dateExtraction(DateExtraction.Unit.MINUTES, args)
            "SecondsFromTime" -> dateExtraction(DateExtraction.Unit.SECONDS, args)
            "DateFromDateTime" -> dateTimePart(DateTimePart.Unit.DATE, args)
            "TimeFromDateTime" -> dateTimePart(DateTimePart.Unit.TIME, args)
            "ValueAsDate" -> valueAsDate(args)
            "DifferenceInDays" -> dateDifference(DateDifference.Unit.DAYS, args)
            "DifferenceInMonths" -> dateDifference(DateDifference.Unit.MONTHS, args)
            "DifferenceInYears" -> dateDifference(DateDifference.Unit.YEARS, args)
            "DifferenceInHours" -> dateTimeDifference(DateDifference.Unit.HOURS, args)
            "DifferenceInMinutes" -> dateTimeDifference(DateDifference.Unit.MINUTES, args)
            "DifferenceInSeconds" -> dateTimeDifference(DateDifference.Unit.SECONDS, args)
            "StartOfDateRange" -> dateRangeBound(true, args)
            "EndOfDateRange" -> dateRangeBound(false, args)
            "FieldValueAsString" -> fieldValueAsString(args)
            "FieldValueAsNumber" -> fieldValueAsNumber(args)
            "RangeAsString" -> rangeAsString(args)
            "RangeAsNumber" -> rangeAsNumber(args)
            "AddDays" -> addDays(args)
            "AddMonths" -> dateOnlyShift(args, "AddMonths") { d, n -> Dsl.addMonths(d, n) }
            "AddYears" -> dateOnlyShift(args, "AddYears") { d, n -> Dsl.addYears(d, n) }
            "AddHours" -> dateTimeOnlyShift(args, "AddHours") { d, n -> Dsl.addHours(d, n) }
            "AddMinutes" -> dateTimeOnlyShift(args, "AddMinutes") { d, n -> Dsl.addMinutes(d, n) }
            "AddSeconds" -> dateTimeOnlyShift(args, "AddSeconds") { d, n -> Dsl.addSeconds(d, n) }
            else -> throw UnmappedConstructException("function $name")
        }
    }

    private fun countOfFilledFields(args: List<Expr>): Calculation {
        // A MARKED group cannot ride the bare-GroupRef arm — `CountAggregate.groups` is a `List<GroupRef>`
        // and a group reference carries no marker; the aggregate-operand arm below maps it to a
        // `GroupListRef`, which does (DG49). Nor can a REPEATABLE one, which that arm renders starred while
        // the author wrote it bare; the aggregate-operand arm keeps it as written.
        if (args.isNotEmpty() && args.all {
                it is Expr.Field && mapping.isGroup(it.path) && !it.outer && !mapping.groupRef(it).repeating()
            }) {
            val groups = inReadOrder(args) { mapping.groupRef(it) }
            return mapping.model().numberOfFilledFields(groups[0], *groups.drop(1).toTypedArray()).expression()
        }
        val operands = aggregateOperands(args)
        // A one-operand call has no Dsl signature to land on, and inventing a second operand here would
        // pre-empt the very check being measured. Construct the node instead: its compact constructor is where
        // the arity law lives, so MVK_PARAMSIZE_INVALIDN still comes from rulekit.
        if (operands.size == 1) {
            return CountAggregate(CountAggregate.CountOp.FILLED_FIELDS, operands, listOf())
        }
        return mapping.model().numberOfFilledFields(operands[0], operands[1], *operands.drop(2).toTypedArray())
            .expression()
    }

    private fun countOfFilledGroups(args: List<Expr>): Calculation {
        val soleOperand = args.size == 1
        val groups = inReadOrder(args) { countedGroup(it, soleOperand) }
        if (groups.size == 1) {
            // A root group is banned in the ordinary count and legal as the sole starred operand, and rulekit
            // splits that across two nodes: CountAggregate refuses any root group, RootGroupCount exists for
            // exactly this shape. Picking the node is the bridge's job; the ban is still rulekit's.
            val only = groups[0]
            return if (isRootGroup(only)) {
                mapping.model().numberOfFilledRootGroups(only).expression()
            } else {
                mapping.model().numberOfFilledGroups(only).expression()
            }
        }
        return mapping.model().numberOfFilledGroups(groups[0], groups[1], *groups.drop(2).toTypedArray()).expression()
    }

    /**
     * One operand of `NumberOfFilledGroups`, which the count holds as a [GroupRef] and so renders starred exactly
     * when the group repeats, and always as a sole root. A group the author wrote otherwise has no typed form:
     * `NumberOfFilledGroups(Keyed)`, refused by A12 at a rule that does not iterate `Keyed`, would read as the
     * admitted `Keyed*` (`rulekit.validate.laws.EntityListOperandReadLawsTest`). A star written on a group that
     * does not repeat is refused while resolving it, with A12's class for it.
     */
    private fun countedGroup(node: Expr, soleOperand: Boolean): GroupRef {
        val group = mapping.groupRef(node)
        val written = (mapping.aggregateOperand(node) as GroupListRef).flattened().map { it.path() }
        val rendersStarred = group.repeating() || soleOperand && isRootGroup(group)
        if (written != (if (rendersStarred) listOf(group.path()) else listOf())) {
            throw NoTypedOperationException(
                "NumberOfFilledGroups over " + group.path() + " as written: the typed count stars a group exactly" +
                    " when it repeats, and a sole root always"
            )
        }
        return group
    }

    /**
     * `NumberOfDifferentValues(…)` — the same [CountAggregate] the fill counts use, under `DIFFERENT_VALUES`.
     * The varargs entry point admits a single operand, so the arity law and the type-homogeneity law both stay
     * where rulekit put them.
     */
    private fun countOfDifferentValues(args: List<Expr>): Calculation {
        val operands = aggregateOperands(args)
        return mapping.model().numberOfDifferentValues(operands[0], *rest(operands)).expression()
    }

    /**
     * `MaxValue(…)` — one keyword over four factories, the numeric aggregate and the three temporal ones, so
     * the operand kind picks the entry point. A kind that is not sortable at all takes the numeric arm on
     * purpose: `MVK_NOT_SORTABLE` is then rulekit's verdict rather than a guard here.
     */
    private fun maxValue(args: List<Expr>): Calculation {
        val operands = aggregateOperands(args)
        val first = operands[0]
        val rest = rest(operands)
        return when (routingKind(operands, "MaxValue")) {
            ValueKind.DATE, ValueKind.DATE_FRAGMENT -> mapping.model().latestOf(first, *rest).expression()
            ValueKind.TIME -> mapping.model().latestTimeOf(first, *rest).expression()
            ValueKind.DATE_TIME -> mapping.model().latestDateTimeOf(first, *rest).expression()
            else -> mapping.model().maxValue(first, *rest).expression()
        }
    }

    /** `MinValue(…)` — [maxValue]'s twin, over the same four factories. */
    private fun minValue(args: List<Expr>): Calculation {
        val operands = aggregateOperands(args)
        val first = operands[0]
        val rest = rest(operands)
        return when (routingKind(operands, "MinValue")) {
            ValueKind.DATE, ValueKind.DATE_FRAGMENT -> mapping.model().earliestOf(first, *rest).expression()
            ValueKind.TIME -> mapping.model().earliestTimeOf(first, *rest).expression()
            ValueKind.DATE_TIME -> mapping.model().earliestDateTimeOf(first, *rest).expression()
            else -> mapping.model().minValue(first, *rest).expression()
        }
    }

    /** `Sum(…)` — number operands only, and one factory covers the whole slot (`MVK_NO_NUMBER`). */
    private fun sum(args: List<Expr>): Calculation {
        val operands = aggregateOperands(args)
        return mapping.model().sum(operands[0], *rest(operands)).expression()
    }

    /**
     * `Min(…)` / `Max(…)` — the operand-LIST forms, which take value expressions rather than the entity refs
     * their `MinValue`/`MaxValue` namesakes aggregate. No factory to route to: the record reads the result kind
     * off its own first operand, and its constructor holds the arity, the admissible-kind and the homogeneity
     * laws.
     */
    private fun opListMath(op: OpListMath.Op, args: List<Expr>): Calculation {
        // A quoted literal carries no type of its own: the kernel types it from its SIBLINGS, so inside a
        // Min/Max whose other operand is date-like, "12.04.2020" and the omitted-year "25.07." are dates.
        // ExprEval's producesDate/dateAggregate do this at evaluation time; the same call needs it here,
        // because rulekit's operands are typed and a StringLiteral beside a date is a kind mismatch.
        val dateContext = args.any { isDateLike(it) }
        val operands = args.map {
            if (dateContext && it is Expr.StrLit) DateLiteral(it.v) else mapping.calculation(it)
        }
        return OpListMath(op, operands)
    }

    /** Whether an operand names something the model types as date-like, ignoring untyped literals. */
    private fun isDateLike(node: Expr): Boolean {
        if (node is Expr.StrLit || node is Expr.NumLit || node is Expr.BoolLit) {
            return false
        }
        return try {
            when (mapping.calculation(node).kind()) {
                ValueKind.DATE, ValueKind.DATE_FRAGMENT, ValueKind.TIME, ValueKind.DATE_TIME -> true
                else -> false
            }
        } catch (e: RuntimeException) {
            // Deciding the sibling context must not turn an operand's own refusal into this one's verdict;
            // the mapping below raises it for real, in the slot that owns it.
            false
        }
    }

    /**
     * `DateRange(start, end)` — routed through the model's opaque-expression form, which carries the endpoint
     * gate the record cannot: whether both endpoints declare the same date components is a property of the
     * MODEL, and the kernel refuses a mismatch at the construction itself
     * (`MVK_WRONG_DATE_FORMAT_FOR_OP`). The record's compact constructor still applies the intrinsic half.
     */
    private fun dateRange(args: List<Expr>): Calculation {
        if (args.size != 2) {
            throw UnmappedConstructException("DateRange with " + args.size + " operands")
        }
        return mapping.model().dateRangeOf(mapping.calculation(args[0]), mapping.calculation(args[1]))
    }

    /**
     * `SumOfProducts(a, b)` — Σ aᵢ·bᵢ over two parallel field lists. `SumOfProducts` is a fixed
     * two-[FieldOperand] record with no other constructor, so any other arity has no builder to reach for —
     * not a gap in this bridge, but the same "no operation published" shape as an illegal operand kind (a lone
     * group argument is one the kernel's own grammar refuses with "no context may be given").
     */
    private fun sumOfProducts(args: List<Expr>): Calculation {
        if (args.size != 2) {
            throw NoTypedOperationException(
                "SumOfProducts with " + args.size + " operands — the DSL record takes exactly two field" +
                    " operands, with no other arity"
            )
        }
        return Dsl.sumOfProducts(fieldOperand(args[0]), fieldOperand(args[1])).expression()
    }

    /**
     * An operand slot that admits no group scope. [Mapping.aggregateOperand] resolves a group to a
     * `GroupListRef`, which no `SumOfProducts` signature takes; whether the kernel admits one there is
     * unmeasured, so the bridge says nothing.
     */
    private fun fieldOperand(node: Expr): FieldOperand =
        mapping.aggregateOperand(node) as? FieldOperand
            ?: throw UnmappedConstructException("group-scope operand in a field-list slot")

    /**
     * `Date(day, month, year)` / `Date(day, month, century, decade)` — built through the record: the component
     * gate (each must be a bounded field, a digit-patterned expression, or a quoted digit string;
     * `MVK_WRONG_DATE_FORMAT_FOR_OP`) reads the live model and is the kernel's call, not pre-checked here.
     * Only the component COUNT is a Java-level arity gate, since the record has no three-vs-four-argument
     * constructor of its own to delegate to.
     */
    private fun date(args: List<Expr>): Calculation = when (args.size) {
        3 -> mapping.model().dateOf(
            mapping.calculation(args[0]), mapping.calculation(args[1]), mapping.calculation(args[2])
        )
        4 -> mapping.model().dateOf(
            mapping.calculation(args[0]), mapping.calculation(args[1]), mapping.calculation(args[2]),
            mapping.calculation(args[3]),
        )
        else -> throw UnmappedConstructException("Date with " + args.size + " operands")
    }

    /**
     * `Time(hour?, minute?, second?)` — a zero-to-three-component prefix; the record's own compact constructor
     * caps the count at three, so no arity gate is needed here.
     */
    private fun time(args: List<Expr>): Calculation =
        TimeConstruction(args.map { mapping.calculation(it) })

    /** `DateTime(date, time)` — a fixed two-operand construction. */
    private fun dateTime(args: List<Expr>): Calculation {
        if (args.size != 2) {
            throw UnmappedConstructException("DateTime with " + args.size + " operands")
        }
        return DateTimeConstruction(mapping.calculation(args[0]), mapping.calculation(args[1]))
    }

    /**
     * `FirstFilledValue(…)` — built through the record directly rather than the many kind-specific
     * `Dsl.firstFilledNumber`/`firstFilledDate`/… wrappers: those exist to hand back a typed `Expr` for further
     * composition, which a bare [Calculation] slot has no use for, and the record's own compact constructor
     * already carries every check those wrappers re-verify — arity, owner result family, the CONFIRM ban, and
     * the two duplicate-entity gates.
     */
    private fun firstFilledValue(args: List<Expr>): Calculation {
        if (args.isEmpty()) {
            throw UnmappedConstructException("FirstFilledValue with no operands")
        }
        val operands = inReadOrder(args) { firstFilledOperand(it) }
        return FirstFilledValue(operands[0], operands.subList(1, operands.size))
    }

    /**
     * One `FirstFilledValue` operand. A group scope has no PUBLIC constructor on [FirstFilledOperand] —
     * `BoundModel.firstFilledGroup` is the only reachable route — so the group's starred path is read back off
     * [Mapping.aggregateOperand]'s own resolution rather than reconstructed here. An index-selected group
     * routes the same way, to the already-existing, already-guarded `BoundModel.firstFilledGroupFor`.
     * Everything else is a field, a repetition list, or an index-selected field, which [FirstFilledOperand.of]
     * accepts directly.
     */
    private fun firstFilledOperand(node: Expr): FirstFilledOperand {
        if (node is Expr.EntitySource && mapping.isGroup(node.path)) {
            val group = mapping.aggregateOperand(node) as? GroupListRef
                ?: throw UnmappedConstructException("FirstFilledValue group operand " + node.path)
            return mapping.model().firstFilledGroup(group.renderedPath())
        }
        if (node is Expr.SemanticIndex && mapping.isGroup(node.path)) {
            return firstFilledIndexedGroup(mapping.indexedGroup(node))
        }
        return FirstFilledOperand.of(mapping.fieldOperand(node))
    }

    /**
     * `FirstFilledValue(Group For key)` — the resolved [IndexedGroupValue] already carries a validated group
     * and key (existence and correlation ran building it), so this just re-routes those components into
     * `BoundModel.firstFilledGroupFor` — code that already exists, not new construction logic. The star-free
     * [GroupListRef.path] is the whole scope only because a starred entity with an index never reaches here: it
     * parses as an entity spec, which [Operands.fieldRef] leaves unmapped, although A12 admits a star below the
     * index group (`rulekit.validate.laws.SemanticIndexWildcardPlacementLawsTest`).
     */
    private fun firstFilledIndexedGroup(indexed: IndexedGroupValue): FirstFilledOperand {
        val groupPath = indexed.group().path()
        return when (indexed) {
            is GroupSemanticIndexValue -> mapping.model().firstFilledGroupFor(groupPath, indexed.indexValue())
            is GroupSemanticIndexFieldValue ->
                mapping.model().firstFilledGroupFor(groupPath, indexed.indexField())
        }
    }

    /**
     * `NumberOfValueInFields(value In f1, f2, …)` — built through the record: its `value` slot is any
     * [Calculation], wider than the three literal-typed `Dsl.numberOfValueInFields` overloads, and the kernel's
     * arity law ("at least two fields, or at least one group"; `MVK_PARAMSIZE_INVALIDN`) is documented as
     * kernel-delegated on the record itself, not pre-checked by either.
     */
    private fun numberOfValueInFields(args: List<Expr>): Calculation {
        if (args.size < 2) {
            throw UnmappedConstructException("NumberOfValueInFields with " + args.size + " operands")
        }
        val value = mapping.calculation(args[0])
        val fields = args.subList(1, args.size).map { mapping.aggregateOperand(it) }
        return NumberOfValueInFields(value, fields)
    }

    /** `AbsValue(field)` — the field-only sibling of `Abs`, via [Dsl.absValue]. */
    private fun absValue(args: List<Expr>): Calculation {
        if (args.size != 1) {
            throw UnmappedConstructException("AbsValue with " + args.size + " operands")
        }
        return Dsl.absValue(numberRef(args[0], "AbsValue")).expression()
    }

    /** `Length(field)` — the field's UTF-16 code-unit length, via [Dsl.length]. */
    private fun length(args: List<Expr>): Calculation {
        if (args.size != 1) {
            throw UnmappedConstructException("Length with " + args.size + " operands")
        }
        return Dsl.length(stringRef(args[0], "Length")).expression()
    }

    /**
     * `RoundDown(expr[, n])` / `RoundUp(expr[, n])` / `RoundAccounting(expr[, n])` — the operand-LIST rounding
     * family, over an arbitrary numeric expression; built through the record, whose compact constructor holds
     * the `0..14` decimal-places bound.
     */
    private fun rounding(mode: Rounding.Mode, args: List<Expr>): Calculation {
        if (args.isEmpty() || args.size > 2) {
            throw UnmappedConstructException(mode.operatorId() + " with " + args.size + " operands")
        }
        val operand = mapping.calculation(args[0])
        val places = if (args.size == 2) intLiteral(args[1], mode.operatorId()) else 0
        return if (places > 0) Rounding(mode, operand, places) else Rounding(mode, operand)
    }

    /**
     * `RoundDownValue(field[, n])` / `RoundUpValue(field[, n])` / `RoundAccountingValue(field[, n])` — the
     * field-only rounding siblings, via the matching `Dsl.round*Value` factory.
     */
    private fun roundValue(mode: FieldRounding.Mode, args: List<Expr>): Calculation {
        if (args.isEmpty() || args.size > 2) {
            throw UnmappedConstructException(mode.operatorId() + " with " + args.size + " operands")
        }
        val field = numberRef(args[0], mode.operatorId())
        val places = if (args.size == 2) intLiteral(args[1], mode.operatorId()) else 0
        val explicit = places > 0
        return when (mode) {
            FieldRounding.Mode.ROUND_DOWN ->
                (if (explicit) Dsl.roundDownValue(field, places) else Dsl.roundDownValue(field)).expression()
            FieldRounding.Mode.ROUND_UP ->
                (if (explicit) Dsl.roundUpValue(field, places) else Dsl.roundUpValue(field)).expression()
            FieldRounding.Mode.ROUND_ACCOUNTING ->
                (if (explicit) {
                    Dsl.roundAccountingValue(field, places)
                } else {
                    Dsl.roundAccountingValue(field)
                }).expression()
            else -> throw IllegalStateException("AbsValue is built via absValue(), not roundValue()")
        }
    }

    /**
     * `CurrentRepetition(group)`, in both correlation forms. The `$`-marked spelling is a real admitted shape
     * — it is what the whole `Having` scope/iteration account is about (KERNEL-FINDINGS `KF244`) — and
     * `CurrentRepetition` is the one group slot whose rulekit node has carried an `outer` component all along,
     * so the marker is threaded here rather than refused (DEV-API-GAPS `DG49`).
     */
    private fun currentRepetition(args: List<Expr>): Calculation {
        if (args.size != 1) {
            // The record takes one GroupRef, with no multi-group form — the kernel's own grammar agrees (a
            // second group argument is an upstream PARSE error, "unexpected ','"), so there is no builder for
            // more than one group rather than a mapping this bridge has yet to write.
            throw NoTypedOperationException(
                "CurrentRepetition with " + args.size + " operands — the DSL record takes a single group," +
                    " with no multi-group form"
            )
        }
        val group = args[0]
        val outer = group is Expr.EntitySource && group.outer
        return Dsl.currentRepetition(mapping.groupRef(group, allowOuter = true), outer).expression()
    }

    /**
     * `YearFromDate`/`MonthFromDate`/`DayFromDate`/`QuarterFromDate` (a date, date-fragment or date-time
     * source) and `HoursFromTime`/`MinutesFromTime`/`SecondsFromTime` (a time or date-time source) — a numeric
     * component. The catalog admits "a field or expression" here, wider than a typed handle, so this routes
     * through [Dsl.dateExtraction] — the family factory that takes the unit as a value and resolves the
     * operand's DECLARED FORMAT through the model, refusing an extraction whose component that format does not
     * carry. Both that refusal and the non-point one on [DateExtraction]'s own constructor are rulekit's
     * verdicts, not bridge-authored; an operand with no declaration to read, and an UNKNOWN kind, stay
     * delegated.
     */
    private fun dateExtraction(unit: DateExtraction.Unit, args: List<Expr>): Calculation {
        if (args.size != 1) {
            throw UnmappedConstructException(unit.operatorId() + " with " + args.size + " operands")
        }
        return Dsl.dateExtraction(mapping.model(), unit, mapping.calculation(args[0]))
    }

    /**
     * `DateFromDateTime`/`TimeFromDateTime` — splits a date-time into its date or time part, through
     * [Dsl.dateTimePart]. Completeness IS decided by the declared format rather than the kind, and that format
     * is reachable from the model, so the factory states it: a source whose declaration carries only one half
     * is refused, which over the admitted vocabulary is everything but the ISO date-time.
     */
    private fun dateTimePart(unit: DateTimePart.Unit, args: List<Expr>): Calculation {
        if (args.size != 1) {
            throw UnmappedConstructException(unit.operatorId() + " with " + args.size + " operands")
        }
        return Dsl.dateTimePart(mapping.model(), unit, mapping.calculation(args[0]))
    }

    /**
     * `ValueAsDate(field, FirstDay|LastDay)` — resolves a partially known date field to a concrete date.
     * Routed through the model-bound facade rather than [Dsl], because the operand's admissibility is a fact
     * about its DECLARED PRECISION and this boundary holds the model: a field declaring no optional component
     * is refused where the kernel refuses it, the same reasoning as [dateExtraction] and [dateTimePart]. The
     * day-specification keyword is a bare `Expr.Const` the interpreter's parser emits structurally rather
     * than an expression to recurse into, so its name is read directly here.
     */
    private fun valueAsDate(args: List<Expr>): Calculation {
        if (args.size != 2) {
            throw UnmappedConstructException("ValueAsDate with " + args.size + " operands")
        }
        val field = mapping.fieldRef(args[0])
        if (field.kind() != ValueKind.DATE) {
            throw IllegalArgumentException(
                "ValueAsDate: " + field.path().value() + " is not a date field (kind=" + field.kind() + ")"
            )
        }
        val dayKeyword = args[1]
        if (dayKeyword !is Expr.Const) {
            throw UnmappedConstructException(
                "ValueAsDate day-keyword operand " + dayKeyword.javaClass.simpleName
            )
        }
        return when (dayKeyword.name) {
            "FirstDay" -> mapping.model().valueAsDateFirstDay(field).expression()
            "LastDay" -> mapping.model().valueAsDateLastDay(field).expression()
            else -> throw UnmappedConstructException("ValueAsDate day-keyword " + dayKeyword.name)
        }
    }

    /**
     * `DifferenceInDays(a, b)` — a date/date-time pair, mixed allowed; `DifferenceInMonths`/
     * `DifferenceInYears` — a date-only pair (no `DATE_TIME` side). Built through the [DateDifference] record
     * directly, for the same "field or expression" reason as [dateExtraction].
     */
    private fun dateDifference(unit: DateDifference.Unit, args: List<Expr>): Calculation {
        if (args.size != 2) {
            throw UnmappedConstructException(unit.operatorId() + " with " + args.size + " operands")
        }
        val allowDateTime = unit == DateDifference.Unit.DAYS
        val left = dateOperandCalculation(args[0], unit.operatorId(), allowDateTime)
        val right = dateOperandCalculation(args[1], unit.operatorId(), allowDateTime)
        return DateDifference(unit, left, right)
    }

    /** `DifferenceInHours`/`DifferenceInMinutes`/`DifferenceInSeconds` — a date-time-only pair. */
    private fun dateTimeDifference(unit: DateDifference.Unit, args: List<Expr>): Calculation {
        if (args.size != 2) {
            throw UnmappedConstructException(unit.operatorId() + " with " + args.size + " operands")
        }
        val left = mapping.calculation(args[0])
        val right = mapping.calculation(args[1])
        if (left.kind() != ValueKind.DATE_TIME || right.kind() != ValueKind.DATE_TIME) {
            throw IllegalArgumentException(
                unit.operatorId() + ": operands are kind=" + left.kind() + "/" + right.kind() + ", not date-time"
            )
        }
        return DateDifference(unit, left, right)
    }

    /**
     * `AddDays(date, n)` — a date OR date-time shifted by whole days. The two overloads are how rulekit states
     * which family the first operand must be — a compile-time split a human author gets from the compiler and
     * this untyped boundary has to state as a kind switch instead.
     */
    private fun addDays(args: List<Expr>): Calculation {
        val source = shiftSource(args, "AddDays")
        val amount = shiftAmount(args)
        return when (source.kind()) {
            ValueKind.DATE, ValueKind.DATE_FRAGMENT -> Dsl.addDays(DateExpr(source), amount).expression()
            ValueKind.DATE_TIME -> Dsl.addDays(DateTimeExpr(source), amount).expression()
            else -> throw NoTypedOperationException(
                "AddDays: operand is kind=" + source.kind() + ", not a date or date-time"
            )
        }
    }

    /** A date-only shift: `AddMonths`/`AddYears` publish no date-time overload at all. */
    private fun dateOnlyShift(
        args: List<Expr>,
        operator: String,
        shift: (DateOperand, NumberExpr) -> DateExpr,
    ): Calculation {
        val source = shiftSource(args, operator)
        val amount = shiftAmount(args)
        return when (source.kind()) {
            ValueKind.DATE, ValueKind.DATE_FRAGMENT -> shift(DateExpr(source), amount).expression()
            else -> throw NoTypedOperationException(
                operator + ": operand is kind=" + source.kind() + ", not a date"
            )
        }
    }

    /** A date-time-only shift: `AddHours`/`AddMinutes`/`AddSeconds` take no date operand. */
    private fun dateTimeOnlyShift(
        args: List<Expr>,
        operator: String,
        shift: (DateTimeOperand, NumberExpr) -> DateTimeExpr,
    ): Calculation {
        val source = shiftSource(args, operator)
        val amount = shiftAmount(args)
        if (source.kind() != ValueKind.DATE_TIME) {
            throw NoTypedOperationException(
                operator + ": operand is kind=" + source.kind() + ", not a date-time"
            )
        }
        return shift(DateTimeExpr(source), amount).expression()
    }

    /** The shift family's first operand — any [Calculation], since rulekit types the family by kind. */
    private fun shiftSource(args: List<Expr>, operator: String): Calculation {
        if (args.size != 2) {
            throw UnmappedConstructException(operator + " with " + args.size + " operands")
        }
        return mapping.calculation(args[0])
    }

    /** The shift family's amount operand — usually a literal, but the catalog admits any numeric expression. */
    private fun shiftAmount(args: List<Expr>): NumberExpr = NumberExpr(mapping.calculation(args[1]))

    /** One [dateDifference] operand — [dateExtraction]'s kind gate, applied to a named side. */
    private fun dateOperandCalculation(node: Expr, operator: String, allowDateTime: Boolean): Calculation {
        val source = mapping.calculation(node)
        val ok = when (source.kind()) {
            ValueKind.DATE, ValueKind.DATE_FRAGMENT -> true
            ValueKind.DATE_TIME -> allowDateTime
            else -> false
        }
        if (!ok) {
            throw IllegalArgumentException(
                operator + ": operand is kind=" + source.kind() + ", not a " +
                    (if (allowDateTime) "date or date-time" else "date")
            )
        }
        return source
    }

    /**
     * `StartOfDateRange`/`EndOfDateRange` — the bound of a date-range field, or of the model's declared
     * `BaseYear` (the interpreter's parser emits the latter as the same bare `Expr.Const` shape [valueAsDate]'s
     * day keyword uses, never itself a field).
     */
    private fun dateRangeBound(start: Boolean, args: List<Expr>): Calculation {
        val operator = if (start) "StartOfDateRange" else "EndOfDateRange"
        if (args.size != 1) {
            throw UnmappedConstructException(operator + " with " + args.size + " operands")
        }
        val arg = args[0]
        if (arg is Expr.Const && arg.name == "BaseYear") {
            val model = mapping.model()
            return (if (start) model.startOfBaseYear() else model.endOfBaseYear()).expression()
        }
        val field = mapping.fieldRef(arg)
        if (field.kind() != ValueKind.DATE_RANGE) {
            throw IllegalArgumentException(
                operator + ": " + field.path().value() + " is not a date-range field (kind=" +
                    field.kind() + ")"
            )
        }
        val range = DateRangeRef(field)
        return (if (start) Dsl.startOfDateRange(range) else Dsl.endOfDateRange(range)).expression()
    }

    /**
     * `FieldValueAsString(field)` — a NUMBER field's value as a string, via [Dsl.fieldValueAsString]. That
     * factory takes a bare [FieldRef] with no kind restriction of its own, so the NUMBER gate the catalog
     * documents (`MVK_NO_NUMBER` on a string/enum operand) is this bridge's, reusing [numberRef]'s check rather
     * than re-stating it.
     */
    private fun fieldValueAsString(args: List<Expr>): Calculation {
        if (args.size != 1) {
            throw UnmappedConstructException("FieldValueAsString with " + args.size + " operands")
        }
        return Dsl.fieldValueAsString(numberRef(args[0], "FieldValueAsString").field()).expression()
    }

    /**
     * `FieldValueAsNumber(field)` — a string or enumeration field's stored value coerced to a number.
     * `FieldRef` itself is a `NumericTextSource`, so the bare-field overload already reaches an enum field too;
     * the domain gates the catalog documents are the kernel's call — not pre-checked by the record, so not
     * pre-checked here either.
     */
    private fun fieldValueAsNumber(args: List<Expr>): Calculation {
        if (args.size != 1) {
            throw UnmappedConstructException("FieldValueAsNumber with " + args.size + " operands")
        }
        return Dsl.fieldValueAsNumber(mapping.fieldRef(args[0])).expression()
    }

    /**
     * `RangeAsString(field, start, end)` — a substring of a string field's value. The 1-based `start <= end`
     * bound (`MVK_INVALID_INTERVAL`) is the `RangeAsString` record's own compact constructor.
     */
    private fun rangeAsString(args: List<Expr>): Calculation {
        if (args.size != 3) {
            throw UnmappedConstructException("RangeAsString with " + args.size + " operands")
        }
        val field = stringRef(args[0], "RangeAsString").field()
        return Dsl.rangeAsString(
            field, intLiteral(args[1], "RangeAsString"), intLiteral(args[2], "RangeAsString")
        ).expression()
    }

    /** `RangeAsNumber(field, start, end)` — [rangeAsString]'s number-valued sibling. */
    private fun rangeAsNumber(args: List<Expr>): Calculation {
        if (args.size != 3) {
            throw UnmappedConstructException("RangeAsNumber with " + args.size + " operands")
        }
        val field = stringRef(args[0], "RangeAsNumber").field()
        return Dsl.rangeAsNumber(
            field, intLiteral(args[1], "RangeAsNumber"), intLiteral(args[2], "RangeAsNumber")
        ).expression()
    }

    /**
     * A field in a typed-numeric slot (`AbsValue`, the `Round*Value` family). Resolved through
     * [Mapping.fieldRef] rather than `BoundModel.num(path)` directly: the latter re-resolves the raw path
     * itself and would let a path the model does not contain escape as a bare [IllegalArgumentException] —
     * indistinguishable from a genuine rulekit refusal. Going through the operand boundary first means an
     * absent or wrongly-shaped path surfaces as whatever THAT boundary raises for it, and only a real,
     * resolved field reaches the kind check below (also correctly `$`-carrying, since [Mapping.fieldRef]
     * preserves it).
     */
    private fun numberRef(node: Expr, operator: String): NumberRef {
        val field = mapping.fieldRef(node)
        if (field.kind() != ValueKind.NUMBER) {
            throw IllegalArgumentException(
                operator + ": " + field.path().value() + " is not a number field (kind=" + field.kind() + ")"
            )
        }
        return NumberRef(field, mapping.index().numberScale(field.path().value()))
    }

    /** A field in a typed-string slot (`Length`) — the [numberRef] rationale applies identically. */
    private fun stringRef(node: Expr, operator: String): StringRef {
        val field = mapping.fieldRef(node)
        if (field.kind() != ValueKind.STRING) {
            throw IllegalArgumentException(
                operator + ": " + field.path().value() + " is not a string field (kind=" + field.kind() + ")"
            )
        }
        return StringRef(field)
    }

    private fun aggregateOperands(args: List<Expr>): List<AggregateOperand> =
        inReadOrder(args) { mapping.aggregateOperand(it) }

    /**
     * Builds an entity list's operands in list order, as A12 reads them. The first operand that fails
     * answers, so a refusal met while building a later one keeps its class only when every operand before it
     * is a field A12 cannot fail to read ([readsCleanly]); otherwise the refusal stands and the class is
     * withheld, since an earlier read may answer first (KERNEL-FINDINGS KF458).
     */
    private fun <T> inReadOrder(args: List<Expr>, build: (Expr) -> T): List<T> =
        args.mapIndexed { at, arg ->
            try {
                build(arg)
            } catch (refusal: KernelCodeRefusal) {
                throw if (args.subList(0, at).all { readsCleanly(it) }) refusal else refusal.withheld()
            }
        }

    /**
     * Whether A12's read of this operand cannot fail: a plain, uncorrelated field that no repeatable group
     * encloses. An index, the `$` marker, a group (its emptiness, its star), a starred list and a repeatable
     * level left unstarred can each answer (`rulekit.validate.laws.EntityListReadStageLawsTest`).
     */
    private fun readsCleanly(node: Expr): Boolean {
        if (node !is Expr.Field || node.outer || mapping.isGroup(node.path)) {
            return false
        }
        val level = StringBuilder()
        for (segment in node.path.substring(1).split("/").dropLast(1)) {
            level.append('/').append(segment)
            if (mapping.model().group(level.toString()).repeating()) {
                return false
            }
        }
        return true
    }

    /**
     * The class that picks the factory — the first operand that has one, read as its DECLARED temporal class
     * rather than its kind. A group scope has none: a group's kind lives in the expansion the kernel resolves,
     * so a list of nothing but group scopes leaves the choice unmade rather than guessed.
     *
     * Reading the kind here typed `MaxValue(<TIME yyyy-MM-dd>, <DATE yyyy-MM-dd>)` as a TIME extremum, whose
     * comparison against a DATE then had no signature — a kernel-admitted condition (KF271) that came out
     * untranslatable, measured in [CrossKindTemporalTranslationTest]. Not a companion member, because
     * resolving a declaration needs the model.
     */
    private fun routingKind(operands: List<AggregateOperand>, operator: String): ValueKind =
        operands.filterIsInstance<FieldOperand>().firstOrNull()?.let { mapping.operandTemporalClass(it) }
            ?: throw UnmappedConstructException(
                "$operator over group scopes only — the result kind is the expansion's"
            )

    private companion object {

        /** A root group is a single path segment: `/Order` has no second slash. */
        private fun isRootGroup(group: GroupRef): Boolean = group.path().value().indexOf('/', 1) < 0

        /**
         * An integer-literal argument — the rounding families' decimal-places operand, and the range families'
         * start/end bounds — always an immediate numeric literal. A non-integral value is a shape this bridge
         * does not handle, not a rulekit verdict, so it surfaces as [UnmappedConstructException] rather than
         * the [ArithmeticException] `intValueExact` would otherwise throw.
         */
        private fun intLiteral(node: Expr, operator: String): Int {
            if (node is Expr.NumLit) {
                return try {
                    node.v.v.intValueExact()
                } catch (e: ArithmeticException) {
                    throw UnmappedConstructException(
                        operator + " operand " + node.v.v + " is not a whole number"
                    )
                }
            }
            throw UnmappedConstructException(operator + " operand " + node.javaClass.simpleName)
        }

        private fun rest(operands: List<AggregateOperand>): Array<AggregateOperand> =
            operands.subList(1, operands.size).toTypedArray()
    }
}
