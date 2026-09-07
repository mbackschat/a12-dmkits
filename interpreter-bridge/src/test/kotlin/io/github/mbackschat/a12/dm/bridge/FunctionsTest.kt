package io.github.mbackschat.a12.dm.bridge


import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.ValueKind
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate
import io.github.mbackschat.a12.dm.rulekit.dsl.CurrentRepetition
import io.github.mbackschat.a12.dm.rulekit.dsl.DateConstruction
import io.github.mbackschat.a12.dm.rulekit.dsl.DateDifference
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExtraction
import io.github.mbackschat.a12.dm.rulekit.dsl.DateListAggregate
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeConstruction
import io.github.mbackschat.a12.dm.rulekit.dsl.DateShift
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeConstruction
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimePart
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRounding
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValueAsNumber
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValueAsString
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue
import io.github.mbackschat.a12.dm.rulekit.dsl.LengthOf
import io.github.mbackschat.a12.dm.rulekit.dsl.ListAggregate
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberOfValueInFields
import io.github.mbackschat.a12.dm.rulekit.dsl.OpListMath
import io.github.mbackschat.a12.dm.rulekit.dsl.RangeAsNumber
import io.github.mbackschat.a12.dm.rulekit.dsl.RangeAsString
import io.github.mbackschat.a12.dm.rulekit.dsl.Rounding
import io.github.mbackschat.a12.dm.rulekit.dsl.SumOfProducts
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeConstruction
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueAsDate

/**
 * The value-valued functions, on a model with no kernel anywhere on the classpath.
 *
 * Each operator is measured in both directions: a well-formed call that reaches a typed node, and a
 * malformed one that rulekit refuses. The refusals are the point — they are the kernel's own `MVK_*`
 * codes raised from condition TEXT, and every one of them comes out of a DSL constructor rather than out of
 * anything this bridge decides.
 */
class FunctionsTest {

    private lateinit var bridge: ConditionBridge

    @BeforeEach
    fun bind() {
        val json = BridgeModels.orderJson
        bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))
    }

    private fun translate(condition: String): Condition = bridge.translate(condition, BASE, ConditionLanguage.EN)

    /** The left-hand calculation of a mapped comparison. */
    private fun left(condition: String): Calculation =
        assertInstanceOf(ComparisonCondition::class.java, translate(condition)).left()

    /** rulekit refused it, carrying the kernel's own code in the message. */
    private fun assertRejectedWith(code: String, call: Executable) {
        val e = assertThrows(IllegalArgumentException::class.java, call)
        assertTrue(e.message!!.contains(code)) { "expected the kernel's own code in: ${e.message}" }
    }

    /**
     * The BRIDGE could not map it. Distinct from the above on purpose: some rulekit rules are stated as a
     * Java type split, which the compiler enforces when a human authors through the API and nothing enforces
     * at this untyped boundary. Reporting it as a rulekit verdict would credit rulekit with a check it does
     * not run.
     */
    private fun assertUnmappedWith(reason: String, call: Executable) {
        val e = assertThrows(UnmappedConstructException::class.java, call)
        assertTrue(e.message!!.contains(reason)) { "unexpected message: ${e.message}" }
    }

    // --- DateRange ------------------------------------------------------------------------------

    @Test
    fun aWellFormedDateRangeConverts() {
        assertInstanceOf(DateRangeConstruction::class.java, left("DateRange(OrderDate, DeliveryDate) == [CoverageWindow]"))
    }

    /**
     * The endpoint gate. What it reads is the operand's DECLARED FORMAT — a TIME- or DATE_TIME-declared field
     * carrying `yyyy-MM-dd` is an admitted endpoint (`KF271`) — so the client-side check stops at the kinds
     * whose declaration cannot carry date components at all. A NUMBER is one of the three measured refusals.
     */
    @Test
    fun aNumberEndpointIsRejected() {
        assertRejectedWith("expected a date-bearing or date-fragment field") {
            translate("DateRange(OrderDate, Quantity) == [CoverageWindow]")
        }
    }

    /** A stored range is not an endpoint either — the second of the three measured kind refusals. */
    @Test
    fun aDateRangeEndpointIsRejected() {
        assertRejectedWith("expected a date-bearing or date-fragment field") {
            translate("DateRange(CoverageWindow, DeliveryDate) == [CoverageWindow]")
        }
    }

    // --- NumberOfDifferentValues ----------------------------------------------------------------

    @Test
    fun aWellFormedDifferentValuesCountConverts() {
        val count = assertInstanceOf(
            CountAggregate::class.java,
            left("NumberOfDifferentValues(CustomerName, ProductCode) > 1"),
        )
        assertEquals(CountAggregate.CountOp.DIFFERENT_VALUES, count.op())
    }

    /** MVK_STRING_ENUM_AND_NON_STRING_ENUM — the operands must be one homogeneous value class. */
    @Test
    fun aMixedValueClassIsRejected() {
        assertRejectedWith("MVK_STRING_ENUM_AND_NON_STRING_ENUM") {
            translate("NumberOfDifferentValues(CustomerName, Quantity) > 1")
        }
    }

    /** MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED — booleans and confirms have no distinct-value count. */
    @Test
    fun aBooleanOperandIsRejected() {
        assertRejectedWith("MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED") {
            translate("NumberOfDifferentValues(ExpressShipping, TermsConfirmed) > 1")
        }
    }

    /** MVK_PARAMSIZE_INVALIDN — one direct field is not a field list. */
    @Test
    fun aSingleFieldDifferentValuesCountIsRejected() {
        assertRejectedWith("MVK_PARAMSIZE_INVALIDN") { translate("NumberOfDifferentValues(CustomerName) > 1") }
    }

    /** MVK_DUPLICATE_PARAM1 — the same direct field twice. */
    @Test
    fun aDuplicateDifferentValuesOperandIsRejected() {
        assertRejectedWith("MVK_DUPLICATE_PARAM1") {
            translate("NumberOfDifferentValues(CustomerName, CustomerName) > 1")
        }
    }

    // --- MaxValue -------------------------------------------------------------------------------

    @Test
    fun aNumericMaxValueConverts() {
        val max = assertInstanceOf(ListAggregate::class.java, left("MaxValue(Quantity, UnitPrice) > 1"))
        assertEquals(ListAggregate.AggOp.MAX_VALUE, max.op())
    }

    /** The same keyword over dates is a different rulekit node, and the operand kind is what picks it. */
    @Test
    fun aDateMaxValueConvertsToTheDateExtremum() {
        val latest = assertInstanceOf(
            DateListAggregate::class.java,
            left("MaxValue(OrderDate, DeliveryDate) == [OrderDate]"),
        )
        assertEquals(DateListAggregate.Mode.LATEST, latest.mode())
        assertEquals(ValueKind.DATE, latest.kind())
    }

    @Test
    fun aDateTimeMaxValueConvertsToTheDateTimeExtremum() {
        val latest = assertInstanceOf(
            DateListAggregate::class.java,
            left("MaxValue(ScheduledAt, CompletedAt) == [ScheduledAt]"),
        )
        assertEquals(ValueKind.DATE_TIME, latest.kind())
    }

    /**
     * The time arm of the same routing. The model carries one TIME field, so the arm is reached through its
     * refusal of a number beside it rather than through a second time operand.
     */
    @Test
    fun aTimeMaxValueRoutesToTheTimeExtremum() {
        assertRejectedWith("MaxValue was built for TIME") { translate("MaxValue(PickupTime, Quantity) > 1") }
    }

    /** MVK_NOT_SORTABLE — strings have no maximum. */
    @Test
    fun anUnsortableMaxValueIsRejected() {
        assertRejectedWith("MVK_NOT_SORTABLE") { translate("MaxValue(CustomerName, ProductCode) > 1") }
    }

    /** A date beside a number: the date routes the call, and the temporal aggregate refuses the number. */
    @Test
    fun aDateBesideANumberIsRejected() {
        assertRejectedWith("is NUMBER") { translate("MaxValue(OrderDate, Quantity) == [OrderDate]") }
    }

    /** MVK_DUPLICATE_PARAM1 — shared with the other entity-list aggregates. */
    @Test
    fun aDuplicateMaxValueOperandIsRejected() {
        assertRejectedWith("MVK_DUPLICATE_PARAM1") { translate("MaxValue(Quantity, Quantity) > 1") }
    }

    // --- MinValue, and the aggregates that share its node -----------------------------------------

    @Test
    fun aNumericMinValueConverts() {
        val min = assertInstanceOf(ListAggregate::class.java, left("MinValue(Quantity, UnitPrice) > 1"))
        assertEquals(ListAggregate.AggOp.MIN_VALUE, min.op())
    }

    @Test
    fun aDateMinValueConvertsToTheDateExtremum() {
        val earliest = assertInstanceOf(
            DateListAggregate::class.java,
            left("MinValue(OrderDate, DeliveryDate) == [OrderDate]"),
        )
        assertEquals(DateListAggregate.Mode.EARLIEST, earliest.mode())
        assertEquals(ValueKind.DATE, earliest.kind())
    }

    @Test
    fun aDateTimeMinValueConvertsToTheDateTimeExtremum() {
        val earliest = assertInstanceOf(
            DateListAggregate::class.java,
            left("MinValue(ScheduledAt, CompletedAt) == [ScheduledAt]"),
        )
        assertEquals(ValueKind.DATE_TIME, earliest.kind())
    }

    @Test
    fun aTimeMinValueRoutesToTheTimeExtremum() {
        assertRejectedWith("MinValue was built for TIME") { translate("MinValue(PickupTime, Quantity) > 1") }
    }

    /** MVK_NOT_SORTABLE — the twin of MaxValue's, and a different factory reaches it. */
    @Test
    fun anUnsortableMinValueIsRejected() {
        assertRejectedWith("MVK_NOT_SORTABLE") { translate("MinValue(CustomerName, ProductCode) > 1") }
    }

    /** MVK_PARAMSIZE_INVALIDN — the numeric aggregate's arity law, the peer of the count family's. */
    @Test
    fun aSingleFieldMinValueIsRejected() {
        assertRejectedWith("MVK_PARAMSIZE_INVALIDN") { translate("MinValue(Quantity) > 1") }
    }

    @Test
    fun aWellFormedSumConverts() {
        val sum = assertInstanceOf(ListAggregate::class.java, left("Sum(Quantity, UnitPrice) > 1"))
        assertEquals(ListAggregate.AggOp.SUM, sum.op())
    }

    /** MVK_NO_NUMBER — Sum's own code where the extrema raise MVK_NOT_SORTABLE. */
    @Test
    fun aNonNumberSumOperandIsRejected() {
        assertRejectedWith("MVK_NO_NUMBER") { translate("Sum(CustomerName, ProductCode) > 1") }
    }

    // --- Min/Max, the operand-list forms ----------------------------------------------------------

    @Test
    fun aNumericOperandListMaxConverts() {
        val max = assertInstanceOf(OpListMath::class.java, left("Max([Quantity], [UnitPrice]) > 1"))
        assertEquals(OpListMath.Op.MAX, max.op())
        assertEquals(ValueKind.NUMBER, max.kind())
    }

    /** The same node over dates — it carries its operands' kind rather than routing to a second factory. */
    @Test
    fun aDateOperandListMaxCarriesTheDateKind() {
        val max = assertInstanceOf(OpListMath::class.java, left("Max([OrderDate], [DeliveryDate]) == [OrderDate]"))
        assertEquals(ValueKind.DATE, max.kind())
    }

    @Test
    fun aMixedKindOperandListMaxIsRejected() {
        assertRejectedWith("needs operands of one kind") { translate("Max([Quantity], [OrderDate]) > 1") }
    }

    @Test
    fun anUnorderableOperandListMinIsRejected() {
        assertRejectedWith("needs NUMBER or date-like operands") {
            translate("Min([CustomerName], [ProductCode]) > 1")
        }
    }

    // --- SumOfProducts --------------------------------------------------------------------------

    /**
     * MVK_REPEATABLE_LEVEL_REQUIRED — the pairwise sum needs two parallel repetition lists, and two direct
     * fields carry no repetition to pair over.
     */
    @Test
    fun twoDirectFieldsAreRejected() {
        assertRejectedWith("MVK_REPEATABLE_LEVEL_REQUIRED") { translate("SumOfProducts(Quantity, UnitPrice) > 1") }
    }

    /** Both operands are number fields; a string one is refused before the parallelism gate. */
    @Test
    fun aNonNumberProductOperandIsRejected() {
        assertRejectedWith("takes number fields") { translate("SumOfProducts(CustomerName, ProductCode) > 1") }
    }

    @Test
    fun twoParallelRepetitionListsConvert() {
        assertInstanceOf(SumOfProducts::class.java, left("SumOfProducts(Items*/Count, Items*/UnitWeight) > 1"))
    }

    /**
     * The [SumOfProducts] record is a fixed two-`FieldOperand` constructor — there is no other
     * arity to build, so a lone operand is [NoTypedOperationException], not a rulekit rejection or a
     * bridge gap — the kernel refuses the same shape with its own "no context may be given".
     */
    @Test
    fun aSingleOperandIsNoTypedOperation() {
        val e = assertThrows(NoTypedOperationException::class.java) {
            translate("SumOfProducts(Items*/Count) > 1")
        }
        assertTrue(e.message!!.contains("exactly two field operands")) { "unexpected message: ${e.message}" }
    }

    // --- Date/Time/DateTime -----------------------------------------------------------------------

    @Test
    fun aWellFormedDateConverts() {
        assertInstanceOf(DateConstruction::class.java, left("Date(BirthDay, BirthMonth, BirthYear) == [OrderDate]"))
    }

    /** Wrong component count is a Java-level arity gate — the record has no 2-argument constructor to fall to. */
    @Test
    fun aTwoComponentDateIsUnmapped() {
        assertThrows(UnmappedConstructException::class.java) {
            translate("Date(BirthDay, BirthMonth) == [OrderDate]")
        }
    }

    @Test
    fun aWellFormedTimeConverts() {
        assertInstanceOf(TimeConstruction::class.java, left("Time(10, 30) == [PickupTime]"))
    }

    /** The record's own compact constructor caps the component count at three — no MVK code, rulekit's own bound. */
    @Test
    fun aFourComponentTimeIsRejected() {
        assertRejectedWith("at most hour, minute, and second") { translate("Time(10, 30, 40, 50) == [PickupTime]") }
    }

    @Test
    fun aWellFormedDateTimeConverts() {
        assertInstanceOf(DateTimeConstruction::class.java, left("DateTime(OrderDate, PickupTime) == [ScheduledAt]"))
    }

    // --- FirstFilledValue -------------------------------------------------------------------------

    @Test
    fun aFieldListFirstFilledValueConverts() {
        assertInstanceOf(FirstFilledValue::class.java, left("FirstFilledValue(Items*/Count) > 0"))
    }

    @Test
    fun aMultiFieldFirstFilledValueConverts() {
        assertInstanceOf(FirstFilledValue::class.java, left("FirstFilledValue(Quantity, BackorderQuantity) > 0"))
    }

    /**
     * A group scope's kind is its expansion's, and the expansion must be family-homogeneous — unlike
     * `Items*` (a STRING `Sku` beside NUMBER fields), `ShippingAddress` is all-STRING.
     */
    @Test
    fun aGroupFirstFilledValueConverts() {
        assertInstanceOf(FirstFilledValue::class.java, left("FirstFilledValue(ShippingAddress) == [CustomerName]"))
    }

    /** MVK_PARAMSIZE_INVALIDN — one scalar field is neither a repetition list nor a group. */
    @Test
    fun aSingleScalarFirstFilledValueIsRejected() {
        assertRejectedWith("MVK_PARAMSIZE_INVALIDN") { translate("FirstFilledValue(CustomerName) > 0") }
    }

    /** MVK_ROOT_GROUP_REFERENCED — the rule's own root group is not an admissible group scope. */
    @Test
    fun aRootGroupFirstFilledValueIsRejected() {
        assertRejectedWith("MVK_ROOT_GROUP_REFERENCED") { translate("FirstFilledValue(RuleGroup) > 0") }
    }

    /** A genuine rulekit refusal with no MVK code — the owner family gate is plain English. */
    @Test
    fun aMixedFamilyFirstFilledValueIsRejected() {
        assertRejectedWith("must have one owner result family") {
            translate("FirstFilledValue(CustomerName, Quantity) > 0")
        }
    }

    // --- NumberOfValueInFields --------------------------------------------------------------------

    @Test
    fun aFieldListNumberOfValueInFieldsConverts() {
        assertInstanceOf(NumberOfValueInFields::class.java, left("NumberOfValueInFields(0 In Quantity, BackorderQuantity) > 0"))
    }

    @Test
    fun aGroupNumberOfValueInFieldsConverts() {
        assertInstanceOf(NumberOfValueInFields::class.java, left("NumberOfValueInFields(0 In Items*) > 0"))
    }

    // --- Length / Abs / AbsValue ------------------------------------------------------------------

    @Test
    fun aWellFormedLengthConverts() {
        assertInstanceOf(LengthOf::class.java, left("Length(CustomerName) > 10"))
    }

    /** No MVK code — the typed-string accessor's own kind gate. */
    @Test
    fun aNonStringLengthOperandIsRejected() {
        assertRejectedWith("is not a string field") { translate("Length(Quantity) > 10") }
    }

    /**
     * A single field value operand — arithmetic composition (`Abs([A] - [B])`) is not yet reachable
     * here, since `Expressions.map` has no `Expr.Arith` arm (a boundary gap, not this file's).
     */
    @Test
    fun aWellFormedAbsConverts() {
        val abs = assertInstanceOf(OpListMath::class.java, left("Abs([BackorderQuantity]) < 1"))
        assertEquals(OpListMath.Op.ABS, abs.op())
    }

    /** No MVK code — Abs takes exactly one operand (shared with Min/Max's arity/kind checks in OpListMath). */
    @Test
    fun aTwoOperandAbsIsRejected() {
        assertRejectedWith("Abs takes exactly one operand") {
            translate("Abs([BackorderQuantity], [Quantity]) < 1")
        }
    }

    @Test
    fun aWellFormedAbsValueConverts() {
        assertInstanceOf(FieldRounding::class.java, left("AbsValue(BackorderQuantity) > 1"))
    }

    /** No MVK code — the typed-number accessor's own kind gate. */
    @Test
    fun aNonNumberAbsValueOperandIsRejected() {
        assertRejectedWith("is not a number field") { translate("AbsValue(CustomerName) > 1") }
    }

    // --- the rounding family ----------------------------------------------------------------------

    @Test
    fun aWellFormedRoundDownConverts() {
        val rounding = assertInstanceOf(Rounding::class.java, left("RoundDown([UnitPrice], 2) > 1"))
        assertEquals(Rounding.Mode.DOWN, rounding.mode())
    }

    /** No MVK code — the record's own 0..14 decimal-places bound. */
    @Test
    fun anOutOfRangeRoundUpIsRejected() {
        assertRejectedWith("must be in 0..14") { translate("RoundUp([UnitPrice], 15) > 1") }
    }

    @Test
    fun aWellFormedRoundAccountingValueConverts() {
        val rounding = assertInstanceOf(FieldRounding::class.java, left("RoundAccountingValue(UnitPrice, 2) > 1"))
        assertEquals(FieldRounding.Mode.ROUND_ACCOUNTING, rounding.mode())
    }

    /** No MVK code — the field-only siblings share the same 0..14 bound. */
    @Test
    fun anOutOfRangeRoundDownValueIsRejected() {
        assertRejectedWith("must be in 0..14") { translate("RoundDownValue(UnitPrice, 15) > 1") }
    }

    // --- CurrentRepetition ------------------------------------------------------------------------

    /**
     * No rejection is reachable here: both the field/group gate (`MVK_NO_FIELDS_ALLOWED`) and the
     * must-iterate gate (`MVK_NO_WILDCARD`) are documented as kernel-only on [CurrentRepetition]
     * — `BoundModel.group` performs no validation of its own, and the record adds none either.
     */
    @Test
    fun aWellFormedCurrentRepetitionConverts() {
        assertInstanceOf(CurrentRepetition::class.java, left("CurrentRepetition(Items) > 0"))
    }

    /**
     * The record takes one `GroupRef`, with no multi-group form — the kernel's own grammar agrees,
     * refusing a second group upstream of any check as a PARSE error ("unexpected ','"). So this is
     * [NoTypedOperationException] rather than a rulekit rejection or a bridge gap.
     */
    @Test
    fun twoGroupsAreNoTypedOperation() {
        val e = assertThrows(NoTypedOperationException::class.java) {
            translate("CurrentRepetition(Items, ShippingAddress) > 0")
        }
        assertTrue(e.message!!.contains("a single group")) { "unexpected message: ${e.message}" }
    }

    // --- the date/date-time component extractors ---------------------------------------------------

    @Test
    fun aWellFormedYearFromDateOverADateConverts() {
        val extraction = assertInstanceOf(DateExtraction::class.java, left("YearFromDate(OrderDate) > 2000"))
        assertEquals(DateExtraction.Unit.YEAR, extraction.unit())
    }

    /** The date extractors also admit a date-time source, per the catalog's `YearFromDate` entry. */
    @Test
    fun aWellFormedMonthFromDateOverADateTimeConverts() {
        val extraction = assertInstanceOf(DateExtraction::class.java, left("MonthFromDate(ScheduledAt) > 1"))
        assertEquals(DateExtraction.Unit.MONTH, extraction.unit())
    }

    @Test
    fun aWellFormedQuarterFromDateConverts() {
        val extraction = assertInstanceOf(DateExtraction::class.java, left("QuarterFromDate(OrderDate) > 1"))
        assertEquals(DateExtraction.Unit.QUARTER, extraction.unit())
    }

    /**
     * STRING is one member of the exact owner-backed refusal denominator [DateExtraction] can state from
     * condition text. The kernel reports `MVK_NO_DATE` for every member on both carriers, measured in
     * `validate.laws.DateExtractionLawsTest.everyRepresentableNonPointKindIsNotADateOnBothCarriers`; temporal
     * format refusals remain a different, delegated code.
     */
    @Test
    fun aStringDayFromDateOperandIsRejected() {
        assertRejectedWith("MVK_NO_DATE") { translate("DayFromDate(CustomerName) > 1") }
    }

    @Test
    fun aWellFormedHoursFromTimeOverATimeConverts() {
        val extraction = assertInstanceOf(DateExtraction::class.java, left("HoursFromTime(PickupTime) < 9"))
        assertEquals(DateExtraction.Unit.HOURS, extraction.unit())
    }

    /** The time extractors also admit a date-time source, per the catalog's `HoursFromTime` entry. */
    @Test
    fun aWellFormedMinutesFromTimeOverADateTimeConverts() {
        val extraction = assertInstanceOf(DateExtraction::class.java, left("MinutesFromTime(ScheduledAt) != 0"))
        assertEquals(DateExtraction.Unit.MINUTES, extraction.unit())
    }

    @Test
    fun aWellFormedSecondsFromTimeConverts() {
        val extraction = assertInstanceOf(DateExtraction::class.java, left("SecondsFromTime(PickupTime) == 0"))
        assertEquals(DateExtraction.Unit.SECONDS, extraction.unit())
    }

    /**
     * Which extraction a TEMPORAL source answers is decided by its declared FORMAT, which condition text does
     * not carry, so this boundary maps the shape and leaves the verdict to the kernel — a TIME field declared
     * `yyyy-MM-dd` answers the date extractions and is refused the time ones, the mirror of what its kind
     * says (KERNEL-FINDINGS KF211). Refusing on kind here made a stored rule of the legal shape UNREADABLE,
     * which is the sharper half of API-FINDINGS 127. The kernel-side refusal of this particular pair is
     * locked in `validate.laws.DateExtractionLawsTest.aWrongTemporalKindIsAFormatRefusal`.
     */
    @Test
    fun aDateSecondsFromTimeOperandIsLeftToTheKernel() {
        val extraction = assertInstanceOf(DateExtraction::class.java, left("SecondsFromTime(OrderDate) == 0"))
        assertEquals(DateExtraction.Unit.SECONDS, extraction.unit())
    }

    /** `BaseYear` resolves to a DATE-kind [Calculation], so it rides the same delegation, one operator over. */
    @Test
    fun aBaseYearSecondsFromTimeOperandIsLeftToTheKernel() {
        val extraction = assertInstanceOf(DateExtraction::class.java, left("SecondsFromTime(BaseYear) == 0"))
        assertEquals(DateExtraction.Unit.SECONDS, extraction.unit())
    }

    // --- DateFromDateTime / TimeFromDateTime --------------------------------------------------------

    @Test
    fun aWellFormedDateFromDateTimeConverts() {
        val part = assertInstanceOf(DateTimePart::class.java, left("DateFromDateTime(ScheduledAt) > Today"))
        assertEquals(DateTimePart.Unit.DATE, part.unit())
    }

    @Test
    fun aWellFormedTimeFromDateTimeConverts() {
        val part = assertInstanceOf(
            DateTimePart::class.java,
            left("TimeFromDateTime(CompletedAt) > TimeFromDateTime(ScheduledAt)"),
        )
        assertEquals(DateTimePart.Unit.TIME, part.unit())
    }

    /**
     * Completeness is a property of the DECLARED FORMAT too, so the split family delegates on the same
     * grounds as the extractions above; the kernel-side refusal of a plain date is locked in
     * `validate.laws.DateExtractionLawsTest.splittingAPlainDateIntoItsDatePartIsAFormatRefusal`.
     */
    @Test
    fun aPlainDateDateFromDateTimeOperandIsLeftToTheKernel() {
        val part = assertInstanceOf(DateTimePart::class.java, left("DateFromDateTime(OrderDate) > Today"))
        assertEquals(DateTimePart.Unit.DATE, part.unit())
    }

    /** But a NON-temporal split operand is still refused here, under the code the kernel reports for it. */
    @Test
    fun aStringDateFromDateTimeOperandIsRejected() {
        assertRejectedWith("MVK_NO_DATE") { translate("DateFromDateTime(CustomerName) > Today") }
    }

    /** A plain time rides the same delegation. */
    @Test
    fun aPlainTimeTimeFromDateTimeOperandIsLeftToTheKernel() {
        val part = assertInstanceOf(
            DateTimePart::class.java,
            left("TimeFromDateTime(PickupTime) > TimeFromDateTime(ScheduledAt)"),
        )
        assertEquals(DateTimePart.Unit.TIME, part.unit())
    }

    /** `BaseYear` (kind DATE) likewise, on both sides of the split. */
    @Test
    fun aBaseYearDateFromDateTimeOperandIsLeftToTheKernel() {
        val part = assertInstanceOf(DateTimePart::class.java, left("DateFromDateTime(BaseYear) > Today"))
        assertEquals(DateTimePart.Unit.DATE, part.unit())
    }

    @Test
    fun aBaseYearTimeFromDateTimeOperandIsLeftToTheKernel() {
        val part = assertInstanceOf(
            DateTimePart::class.java,
            left("TimeFromDateTime(BaseYear) > TimeFromDateTime(ScheduledAt)"),
        )
        assertEquals(DateTimePart.Unit.TIME, part.unit())
    }

    // --- ValueAsDate ---------------------------------------------------------------------------------

    @Test
    fun aWellFormedValueAsDateFirstDayConverts() {
        val value = assertInstanceOf(ValueAsDate::class.java, left("ValueAsDate(ApproxDate, FirstDay) > Today"))
        assertTrue(value.firstDay())
    }

    @Test
    fun aWellFormedValueAsDateLastDayConverts() {
        val value = assertInstanceOf(ValueAsDate::class.java, left("ValueAsDate(ApproxDate, LastDay) > Today"))
        assertFalse(value.firstDay())
    }

    /** No MVK code — the typed-date accessor's own kind gate, `numberRef`'s rationale applied to dates. */
    @Test
    fun aNonDateValueAsDateOperandIsRejected() {
        assertRejectedWith("is not a date field") { translate("ValueAsDate(Quantity, FirstDay) > Today") }
    }

    // --- the DifferenceIn* family --------------------------------------------------------------------

    @Test
    fun aWellFormedDifferenceInDaysOverAMixedPairConverts() {
        val diff = assertInstanceOf(DateDifference::class.java, left("DifferenceInDays(OrderDate, ScheduledAt) > 0"))
        assertEquals(DateDifference.Unit.DAYS, diff.unit())
    }

    @Test
    fun aWellFormedDifferenceInYearsConverts() {
        val diff = assertInstanceOf(DateDifference::class.java, left("DifferenceInYears(OrderDate, DeliveryDate) > 0"))
        assertEquals(DateDifference.Unit.YEARS, diff.unit())
    }

    /** No MVK code — DifferenceInMonths/Years admit no date-time side, unlike their Days sibling. */
    @Test
    fun aDateTimeDifferenceInMonthsOperandIsRejected() {
        assertRejectedWith("not a date") { translate("DifferenceInMonths(OrderDate, ScheduledAt) > 0") }
    }

    @Test
    fun aWellFormedDifferenceInHoursConverts() {
        val diff = assertInstanceOf(DateDifference::class.java, left("DifferenceInHours(ScheduledAt, CompletedAt) > 0"))
        assertEquals(DateDifference.Unit.HOURS, diff.unit())
    }

    /** No MVK code — the Hours/Minutes/Seconds family admits date-time only, never a bare time. */
    @Test
    fun aTimeDifferenceInSecondsOperandIsRejected() {
        assertRejectedWith("not date-time") { translate("DifferenceInSeconds(PickupTime, ScheduledAt) > 0") }
    }

    // --- StartOfDateRange / EndOfDateRange -----------------------------------------------------------

    @Test
    fun aWellFormedStartOfDateRangeOverAFieldConverts() {
        assertNotNull(left("StartOfDateRange(CoverageWindow) > Today"))
    }

    @Test
    fun aWellFormedEndOfDateRangeOverBaseYearConverts() {
        assertNotNull(left("EndOfDateRange(BaseYear) > Today"))
    }

    /** No MVK code — the typed date-range accessor's own kind gate. */
    @Test
    fun aNonRangeStartOfDateRangeOperandIsRejected() {
        assertRejectedWith("is not a date-range field") { translate("StartOfDateRange(OrderDate) > Today") }
    }

    // --- FieldValueAsString / RangeAsString / RangeAsNumber ------------------------------------------

    @Test
    fun aWellFormedFieldValueAsStringConverts() {
        assertInstanceOf(FieldValueAsString::class.java, left("FieldValueAsString(Quantity) == \"1\""))
    }

    /** No MVK code — reuses `numberRef`'s own kind gate rather than re-stating it. */
    @Test
    fun aStringFieldValueAsStringOperandIsRejected() {
        assertRejectedWith("is not a number field") { translate("FieldValueAsString(CustomerName) == \"1\"") }
    }

    // --- FieldValueAsNumber -----------------------------------------------------------------------

    @Test
    fun aWellFormedFieldValueAsNumberOverAStringFieldConverts() {
        assertInstanceOf(FieldValueAsNumber::class.java, left("FieldValueAsNumber(CustomerName) == 1"))
    }

    /**
     * `FieldRef` itself is a `NumericTextSource`, so the bare-field overload
     * `Dsl.fieldValueAsNumber(FieldRef)` already reaches an enumeration field too — the domain gate
     * (every stored token obeying the decimal grammar, `MVK_INVALID_TABLE_ENUM` on a table-referencing
     * enumeration) is the kernel's call, not pre-checked here.
     */
    @Test
    fun aWellFormedFieldValueAsNumberOverAnEnumerationFieldConverts() {
        assertInstanceOf(FieldValueAsNumber::class.java, left("FieldValueAsNumber(RushLevel) == 1"))
    }

    /** No MVK code — the arity gate; the catalog admits exactly one operand. */
    @Test
    fun aTwoOperandFieldValueAsNumberIsUnmapped() {
        assertUnmappedWith("FieldValueAsNumber with 2 operands") {
            translate("FieldValueAsNumber(CustomerName, ProductCode) == 1")
        }
    }

    @Test
    fun aWellFormedRangeAsStringConverts() {
        val range = assertInstanceOf(RangeAsString::class.java, left("RangeAsString(CustomerName, 1, 3) == \"ABC\""))
        assertEquals(1, range.start())
        assertEquals(3, range.end())
    }

    /** No MVK code — [RangeAsString]'s own compact constructor (kernel: MVK_INVALID_INTERVAL). */
    @Test
    fun anInvertedRangeAsStringIntervalIsRejected() {
        assertRejectedWith("start <= end") { translate("RangeAsString(CustomerName, 3, 1) == \"ABC\"") }
    }

    @Test
    fun aWellFormedRangeAsNumberConverts() {
        val range = assertInstanceOf(RangeAsNumber::class.java, left("RangeAsNumber(CustomerName, 1, 2) == 0"))
        assertEquals(1, range.start())
        assertEquals(2, range.end())
    }

    // --- the third outcome ----------------------------------------------------------------------

    /**
     * SumOfTerms is deliberately not mapped — it is a real kernel operator dmkits knows nothing about
     * (absent from both the catalog and `ConditionLanguage`). A gap must stay an admission of
     * ignorance: were it to arrive as a rejection, it would read as a rulekit finding.
     */
    @Test
    fun anUnmappedFunctionIsNotARejection() {
        assertThrows(UnmappedConstructException::class.java) { translate("SumOfTerms(Quantity, UnitPrice) > 1") }
    }

    /** The pre-existing arms still map — the switch grew, it did not move. */
    @Test
    fun theFillCountsStillConvert() {
        assertNotNull(translate("NumberOfFilledFields(Quantity, BackorderQuantity) < 2"))
    }

    // --- sibling-typed literals, and the sole starred root group -----------------------------------

    /**
     * A quoted literal carries no type of its own — the kernel types it from its siblings — so beside a date
     * operand it is a date, not a string. Without that, rulekit sees a kind mismatch on a rule the kernel
     * accepts, which is a false finding rather than a missing mapping.
     */
    @Test
    fun aQuotedDateBesideADateOperandIsADate() {
        assertNotNull(translate("Max([OrderDate], \"12.04.2020\") > Today"))
        assertNotNull(translate("Min(\"12.04.2020\", [OrderDate]) > Today"))
    }

    /** The omitted-year form the kernel also accepts. */
    @Test
    fun aQuotedDayAndMonthBesideADateOperandIsADate() {
        assertNotNull(translate("Max([OrderDate], \"25.07.\") > Today"))
    }

    /** With no date sibling the same literal stays a string, and the operand law then refuses it. */
    @Test
    fun aQuotedLiteralWithNoDateSiblingStaysAString() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            translate("Max(\"12.04.2020\", \"25.07.\") > Today")
        }
        assertTrue(e.message!!.contains("NUMBER or date-like")) { "unexpected message: ${e.message}" }
    }

    /**
     * rulekit splits the root-group rule across two nodes — `CountAggregate` bans any root group,
     * `RootGroupCount` exists for the sole starred one — so choosing the node is the bridge's job.
     * Routing every single-group call to the first made the legal shape draw MVK_ROOT_GROUP_REFERENCED.
     */
    @Test
    fun theSoleStarredRootGroupDoesNotDrawTheRootGroupBan() {
        try {
            translate("NumberOfFilledGroups(/Order*) < 3")
        } catch (e: RuntimeException) {
            assertFalse(e.message.toString().contains("MVK_ROOT_GROUP_REFERENCED")) {
                "the sole starred root group took the banning node: ${e.message}"
            }
        }
    }

    // --- AnzahlAngegebenerVordrucke (the legacy root-group-count keyword) --------------------------

    /**
     * The whole `Vordruck` family carries ONE kernel rule — each keyword "darf nur in H-DVs verwendet
     * werden", legal only inside a main document variant. rulekit has no representation of
     * document-variant context, so aliasing the keyword onto the structurally similar
     * `Dsl.numberOfFilledRootGroups` would answer "fine" to a rule the kernel refuses, and would unlock
     * no correct verdict at all. Staying unmapped says the true thing.
     */
    @Test
    fun anzahlAngegebenerVordruckeIsNotAliasedOntoTheRootGroupCount() {
        assertUnmappedWith("function AnzahlAngegebenerVordrucke") {
            translate("AnzahlAngegebenerVordrucke(RuleGroup) < 3")
        }
    }

    // --- the temporal shift family (AddDays / AddMonths / AddYears / AddHours / AddMinutes / AddSeconds) ----

    @Test
    fun aWellFormedAddDaysOverADateConverts() {
        val shift = assertInstanceOf(DateShift::class.java, left("AddDays(OrderDate, 5) > Today"))
        assertEquals(DateShift.Unit.DAYS, shift.unit())
    }

    /**
     * `AddDays` is the one shift admitting BOTH families — `Dsl.addDays(DateOperand, NumberExpr)`
     * and `Dsl.addDays(DateTimeOperand, NumberExpr)` — so a DATE_TIME source must convert here rather
     * than draw the kind gate's default arm. Both overloads render the same [DateShift] node, so this
     * cannot observe WHICH overload ran — only that the DATE_TIME arm was taken instead of refused.
     */
    @Test
    fun aWellFormedAddDaysOverADateTimeConverts() {
        val shift = assertInstanceOf(DateShift::class.java, left("AddDays(ScheduledAt, 5) > Today"))
        assertEquals(DateShift.Unit.DAYS, shift.unit())
    }

    /** No MVK code — the kind gate translating `AddDays`' compile-time DateOperand/DateTimeOperand split. */
    @Test
    fun aNumberAddDaysOperandIsNoTypedOperation() {
        val e = assertThrows(NoTypedOperationException::class.java) { translate("AddDays(Quantity, 5) > Today") }
        assertTrue(e.message!!.contains("not a date or date-time")) { "unexpected message: ${e.message}" }
    }

    @Test
    fun aWellFormedAddMonthsConverts() {
        val shift = assertInstanceOf(DateShift::class.java, left("AddMonths(OrderDate, 3) > Today"))
        assertEquals(DateShift.Unit.MONTHS, shift.unit())
    }

    /** No MVK code — unlike `AddDays`, `AddMonths` publishes no date-time overload at all. */
    @Test
    fun aDateTimeAddMonthsOperandIsNoTypedOperation() {
        val e = assertThrows(NoTypedOperationException::class.java) {
            translate("AddMonths(ScheduledAt, 3) > Today")
        }
        assertTrue(e.message!!.contains("not a date")) { "unexpected message: ${e.message}" }
    }

    @Test
    fun aWellFormedAddYearsConverts() {
        val shift = assertInstanceOf(DateShift::class.java, left("AddYears(OrderDate, 1) > Today"))
        assertEquals(DateShift.Unit.YEARS, shift.unit())
    }

    /** No MVK code — the same date-only gate as `AddMonths`, over a plain NUMBER operand instead. */
    @Test
    fun aNumberAddYearsOperandIsNoTypedOperation() {
        assertThrows(NoTypedOperationException::class.java) { translate("AddYears(Quantity, 1) > Today") }
    }

    @Test
    fun aWellFormedAddHoursConverts() {
        val shift = assertInstanceOf(DateShift::class.java, left("AddHours(ScheduledAt, 2) > Today"))
        assertEquals(DateShift.Unit.HOURS, shift.unit())
    }

    /** No MVK code — `AddHours`/`AddMinutes`/`AddSeconds` admit a date-time source only. */
    @Test
    fun aDateAddHoursOperandIsNoTypedOperation() {
        val e = assertThrows(NoTypedOperationException::class.java) { translate("AddHours(OrderDate, 2) > Today") }
        assertTrue(e.message!!.contains("not a date-time")) { "unexpected message: ${e.message}" }
    }

    @Test
    fun aWellFormedAddMinutesConverts() {
        val shift = assertInstanceOf(DateShift::class.java, left("AddMinutes(ScheduledAt, 30) > Today"))
        assertEquals(DateShift.Unit.MINUTES, shift.unit())
    }

    /** No MVK code — the same date-time-only gate as `AddHours`, over a plain NUMBER operand instead. */
    @Test
    fun aNumberAddMinutesOperandIsNoTypedOperation() {
        assertThrows(NoTypedOperationException::class.java) { translate("AddMinutes(Quantity, 30) > Today") }
    }

    @Test
    fun aWellFormedAddSecondsConverts() {
        val shift = assertInstanceOf(DateShift::class.java, left("AddSeconds(ScheduledAt, 45) > Today"))
        assertEquals(DateShift.Unit.SECONDS, shift.unit())
    }

    /** No MVK code — the same date-time-only gate, over a plain date operand instead. */
    @Test
    fun aDateAddSecondsOperandIsNoTypedOperation() {
        assertThrows(NoTypedOperationException::class.java) { translate("AddSeconds(OrderDate, 45) > Today") }
    }

    private companion object {
        const val BASE = "/Order"
    }
}
