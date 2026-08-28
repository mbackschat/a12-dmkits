package io.github.mbackschat.a12.dm.bridge

import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomConditionRef
import io.github.mbackschat.a12.dm.rulekit.dsl.DateLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListEntityListCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListValueListCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.PatternCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeLiteral

/**
 * The non-predicate condition shapes — the infix forms and `CustomCondition`.
 *
 * Each construct is covered in both directions where rulekit has a check to fire: a well-formed condition
 * that converts, and a malformed one that arrives as rulekit's own rejection. Where a shape is unmapped the
 * test says so with [UnmappedConstructException], which is neither a pass nor a finding.
 */
class ConditionsTest {

    private val BASE = "/Order"

    private lateinit var bridge: ConditionBridge

    @BeforeEach
    fun bind() {
        val json = String(
            ConditionsTest::class.java.getResourceAsStream("/order.json")!!.readAllBytes(),
            StandardCharsets.UTF_8,
        )
        bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))
    }

    private fun translate(condition: String): Condition = bridge.translate(condition, BASE, ConditionLanguage.EN)

    // --- Pattern ------------------------------------------------------------------------------

    @Test
    fun aWellFormedPatternMatchConverts() {
        val c = assertInstanceOf(
            PatternCondition::class.java,
            translate("[ProductCode] PatternMatched \"[A-Z]{2}-[0-9]{4}\""),
        )
        assertEquals("PatternMatched", c.operatorId())
        assertEquals("/Order/ProductCode", c.field().path().value())
    }

    @Test
    fun aWellFormedPatternViolationConverts() {
        val c = assertInstanceOf(
            PatternCondition::class.java,
            translate("[BillingAddress/PostalCode] PatternViolated \"[0-9]{5}\""),
        )
        assertEquals("PatternViolated", c.operatorId())
    }

    /** The German keyword reaches the same node — the parser canonicalises before the AST exists. */
    @Test
    fun theGermanPatternKeywordConverts() {
        val c = assertInstanceOf(
            PatternCondition::class.java,
            bridge.translate("[ProductCode] NichtWieMuster \"[ax]?x*[bc]+[de]{1,3}\"", BASE, ConditionLanguage.DE),
        )
        assertEquals("PatternViolated", c.operatorId())
    }

    /** MVK_INVALID_PATTERN — a lookbehind is Java-valid but outside the kernel's admission gate. */
    @Test
    fun aPatternTheKernelWillNotAdmitIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("[ProductCode] PatternMatched \"(?<=A)B\"") }
        assertTrue(e.message!!.contains("MVK_INVALID_PATTERN")) {
            "expected the kernel's own code in: ${e.message}"
        }
    }

    /** MVK_INVALID_PATTERN — an unclosed group is not a Java pattern either. */
    @Test
    fun aSyntacticallyBrokenRegexIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("[ProductCode] PatternViolated \"[unclosed\"") }
        assertTrue(e.message!!.contains("MVK_INVALID_PATTERN")) {
            "expected the kernel's own code in: ${e.message}"
        }
    }

    /** The left slot is a bare STRING field; a number field is refused by the model's typed handle. */
    @Test
    fun aPatternOnANumberFieldIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("[Quantity] PatternMatched \"A+\"") }
        assertTrue(e.message!!.contains("not a string field")) {
            "expected rulekit's own refusal in: ${e.message}"
        }
    }

    /**
     * MVK_MISSING_CONST_IN_PATTERN_COMP is structurally erased: `PatternCondition` holds the regex as
     * a `String`, so a field on the right has no representation and rulekit has no verdict to give.
     */
    @Test
    fun aFieldInThePatternSlotIsUnmapped() {
        assertThrows<UnmappedConstructException> { translate("[CustomerName] PatternMatched [ProductCode]") }
    }

    // --- ValueListQuant -----------------------------------------------------------------------

    @Test
    fun aStringFieldListValueListQuantifierConverts() {
        val c = assertInstanceOf(
            FieldListValueListCondition::class.java,
            translate("NoFieldValueIncludedInValueList(CustomerName, ProductCode In \"test\", \"demo\")"),
        )
        assertEquals("NoFieldValueIncludedInValueList", c.operatorId())
        assertEquals(2, c.fields().size)
        assertEquals(2, c.values().size)
    }

    @Test
    fun theAtLeastOneAndNotAllQuantifiersConvert() {
        assertEquals(
            "AtLeastOneFieldValueIncludedInValueList",
            translate(
                "AtLeastOneFieldValueIncludedInValueList(" +
                    "ShippingAddress/Street, BillingAddress/PostalCode In \"unknown\")",
            ).operatorId(),
        )
        assertEquals(
            "NotAllFieldValuesIncludedInValueList",
            translate("NotAllFieldValuesIncludedInValueList(CustomerName, ProductCode In \"a\", \"b\")")
                .operatorId(),
        )
    }

    /** The German keyword for the NO case. */
    @Test
    fun theGermanValueListQuantifierKeywordConverts() {
        assertEquals(
            "NoFieldValueIncludedInValueList",
            bridge.translate(
                "KeinFeldWertInWerteListe(CustomerName, ProductCode In \"test\")",
                BASE,
                ConditionLanguage.DE,
            ).operatorId(),
        )
    }

    /** An enum field list checks every constant against the model's declared domain. */
    @Test
    fun anEnumFieldListValueListQuantifierConverts() {
        assertEquals(
            "AtLeastOneFieldValueIncludedInValueList",
            translate("AtLeastOneFieldValueIncludedInValueList(Priority In \"EXPRESS\", \"OVERNIGHT\")")
                .operatorId(),
        )
    }

    /** MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY — the constant is outside the enum's domain. */
    @Test
    fun aConstantOutsideTheEnumDomainIsRejected() {
        val e = assertThrows<IllegalArgumentException> {
            translate("NoFieldValueIncludedInValueList(Priority In \"NOPE\")")
        }
        assertTrue(e.message!!.contains("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY")) {
            "expected the kernel's own code in: ${e.message}"
        }
    }

    /** MVK_DUPLICATE_PARAM1 — the same field listed twice. */
    @Test
    fun aRepeatedFieldInTheFieldListIsRejected() {
        val e = assertThrows<IllegalArgumentException> {
            translate("NoFieldValueIncludedInValueList(CustomerName, CustomerName In \"x\")")
        }
        assertTrue(e.message!!.contains("MVK_DUPLICATE_PARAM1")) {
            "expected the kernel's own code in: ${e.message}"
        }
    }

    /**
     * The kernel's MVK_VARYING_TYPES_NOT_ALLOWED for the constant-valued form. rulekit erases it by TYPING
     * the entry points per kind, so the refusal arrives from the model's typed handle instead of as the code.
     */
    @Test
    fun aMixedKindFieldListIsRejected() {
        val e = assertThrows<IllegalArgumentException> {
            translate("AtLeastOneFieldValueIncludedInValueList(CustomerName, Quantity In \"x\")")
        }
        assertTrue(e.message!!.contains("not a string field")) {
            "expected rulekit's own refusal in: ${e.message}"
        }
    }

    /** The flattened-repetition shape — a starred field list against constants. */
    @Test
    fun aRepetitionListValueListQuantifierConverts() {
        val c = assertInstanceOf(
            FieldListValueListCondition::class.java,
            translate("NoFieldValueIncludedInValueList(Items*/Sku In \"A\", \"B\")"),
        )
        assertEquals(1, c.fields().size)
        assertEquals("/Order/Items*/Sku", c.fields().first().renderedPath())
    }

    /** A starred GROUP is a group scope — the kernel expands its contained fields. */
    @Test
    fun aGroupScopeValueListQuantifierConverts() {
        val c = assertInstanceOf(
            FieldListValueListCondition::class.java,
            translate("NoFieldValueIncludedInValueList(Items* In \"A\")"),
        )
        assertEquals("/Order/Items*", c.fields().first().renderedPath())
    }

    /** MVK_INVALID_TYPES_FOR_COMPARISON — a starred list of number fields against string constants. */
    @Test
    fun aNumberRepetitionListAgainstStringConstantsIsRejected() {
        val e = assertThrows<IllegalArgumentException> {
            translate("NoFieldValueIncludedInValueList(Items*/Count In \"1\")")
        }
        assertTrue(e.message!!.contains("MVK_INVALID_TYPES_FOR_COMPARISON")) {
            "expected the kernel's own code in: ${e.message}"
        }
    }

    /** A starred field list on the fields side with a plain field as the value list. */
    @Test
    fun aRepetitionListAgainstAFieldValueListConverts() {
        assertEquals(
            "AtLeastOneFieldValueIncludedInValueList",
            translate("AtLeastOneFieldValueIncludedInValueList(Items*/Sku In ProductCode)").operatorId(),
        )
    }

    /**
     * A repetition list of ENUM fields is refused with `MVK_INVALID_TYPES_FOR_COMPARISON`, and the
     * kernel would accept it — `Dsl.checkedStringList` admits STRING only and says so itself ("enum-kind
     * lists are deferred"). The code in the message makes a rulekit limitation indistinguishable from a kernel
     * rule, which reads as a kernel rejection to anyone comparing the two engines. Pinned here so that the
     * day rulekit lifts the deferral, this test fails and the divergence is retired with it.
     */
    @Test
    fun anEnumRepetitionListIsRefusedWithACodeTheKernelWouldNotRaise() {
        val json = String(
            ConditionsTest::class.java.getResourceAsStream("/enum-rows.json")!!.readAllBytes(),
            StandardCharsets.UTF_8,
        )
        val baskets = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))

        val e = assertThrows<IllegalArgumentException> {
            baskets.translate(
                "NoFieldValueIncludedInValueList(Lines*/Status In \"OPEN\")",
                "/Basket",
                ConditionLanguage.EN,
            )
        }
        assertTrue(e.message!!.contains("MVK_INVALID_TYPES_FOR_COMPARISON")) {
            "expected the kernel's own code in: ${e.message}"
        }
        assertTrue(e.message!!.contains("ENUMERATION")) { "expected the refused kind in: ${e.message}" }

        // The STRING sibling in the same repetition is what rulekit does admit.
        assertEquals(
            "NoFieldValueIncludedInValueList",
            baskets.translate(
                "NoFieldValueIncludedInValueList(Lines*/Label In \"x\")",
                "/Basket",
                ConditionLanguage.EN,
            ).operatorId(),
        )
    }

    /** The entity-valued form — both sides are references, and rulekit checks the combined list. */
    @Test
    fun anEntityValuedValueListQuantifierConverts() {
        val c = assertInstanceOf(
            FieldListEntityListCondition::class.java,
            translate("NoFieldValueIncludedInValueList(CustomerName In ProductCode)"),
        )
        assertEquals(1, c.fields().size)
        assertEquals(1, c.valueFields().size)
    }

    /** MVK_VARYING_TYPES_NOT_ALLOWED — the entity-valued form does carry the code, across both sides. */
    @Test
    fun anEntityValuedListMixingKindsIsRejected() {
        val e = assertThrows<IllegalArgumentException> {
            translate("NoFieldValueIncludedInValueList(CustomerName In Quantity)")
        }
        assertTrue(e.message!!.contains("MVK_VARYING_TYPES_NOT_ALLOWED")) {
            "expected the kernel's own code in: ${e.message}"
        }
    }

    /** A number field list against integer constants is a legal kernel shape rulekit offers no builder for. */
    @Test
    fun aNumberFieldListWithConstantsIsUnmapped() {
        assertThrows<UnmappedConstructException> {
            translate("NoFieldValueIncludedInValueList(Quantity, BackorderQuantity In 1, 2)")
        }
    }

    /** The kernel forbids mixing constants and field entries in one value list; rulekit cannot express it. */
    @Test
    fun aValueListMixingConstantsAndReferencesIsUnmapped() {
        assertThrows<UnmappedConstructException> {
            translate("NoFieldValueIncludedInValueList(CustomerName In \"x\", ProductCode)")
        }
    }

    // --- Tolerance ----------------------------------------------------------------------------

    @Test
    fun aWellFormedToleranceComparisonConverts() {
        val c = assertInstanceOf(
            ComparisonCondition::class.java,
            translate("[Quantity] DiffersWithToleranceRange1 [BackorderQuantity]"),
        )
        assertEquals("DiffersWithToleranceRange1", c.operatorId())
    }

    @Test
    fun everyToleranceBandConverts() {
        assertEquals(
            "DiffersWithToleranceRange2",
            translate("[Quantity] DiffersWithToleranceRange2 [BackorderQuantity]").operatorId(),
        )
        assertEquals(
            "DiffersWithToleranceRange5",
            translate("[Quantity] DiffersWithToleranceRange5 [BackorderQuantity]").operatorId(),
        )
        assertEquals(
            "DiffersWithToleranceRange10",
            translate("[Quantity] DiffersWithToleranceRange10 [BackorderQuantity]").operatorId(),
        )
    }

    /** Both slots are numeric; a string field is refused by the model's typed handle. */
    @Test
    fun aToleranceComparisonOnAStringFieldIsRejected() {
        val e = assertThrows<IllegalArgumentException> {
            translate("[Quantity] DiffersWithToleranceRange1 [ProductCode]")
        }
        assertTrue(e.message!!.contains("not a number field")) {
            "expected rulekit's own refusal in: ${e.message}"
        }
    }

    // --- Cmp: the typed comparison router ------------------------------------------------------

    /**
     * The headline: a date range has no ordering, and rulekit says so by publishing no `after` on
     * `DateRangeOperand`. There is no builder to call, so this is unmapped — the kernel's own
     * MVK_INVALID_COMPARE_TO_DATE_RANGE has no rulekit runtime verdict behind it.
     */
    @Test
    fun orderingTwoDateRangesIsUnmapped() {
        val e = assertThrows<UnmappedConstructException> {
            translate("[Items/ItemCoverage] > [CoverageWindow]")
        }
        assertEquals("no typed rulekit comparison for DATE_RANGE GT DATE_RANGE", e.message)
        assertThrows<UnmappedConstructException> { translate("[Items/ItemCoverage] <= [CoverageWindow]") }
    }

    @Test
    fun dateRangeEqualityConverts() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[Items/ItemCoverage] == [CoverageWindow]"))
        assertEquals("Equal", c.operatorId())
    }

    /**
     * `FirstFilledValue` over DATE_RANGE fields is a DATE_RANGE-kind [Calculation] that implements no
     * `DateRangeOperand` arm: rulekit publishes a date range only as an owner entity reference, a keyed
     * cell, or a `DateRange(start, end)` construction, and holds `DateRangeExpr` closed so no other
     * expression can occupy the slot. The bridge records that as [NoTypedOperationException] — the
     * kernel accepts the condition, and rulekit declines to model it — rather than reaching past the
     * closed surface to fabricate the operand.
     */
    @Test
    fun firstFilledValueOverDateRangesHasNoTypedOperandForEquality() {
        val e = assertThrows<NoTypedOperationException> {
            translate("FirstFilledValue(CoverageWindow, Items/ItemCoverage) == [CoverageWindow]")
        }
        assertTrue(e.message!!.contains("DATE_RANGE EQ DATE_RANGE"), e.message)
    }

    /** The inequality mirror of [firstFilledValueOverDateRangesHasNoTypedOperandForEquality]. */
    @Test
    fun firstFilledValueOverDateRangesHasNoTypedOperandForInequality() {
        val e = assertThrows<NoTypedOperationException> {
            translate("FirstFilledValue(CoverageWindow, Items/ItemCoverage) != [CoverageWindow]")
        }
        assertTrue(e.message!!.contains("DATE_RANGE NE DATE_RANGE"), e.message)
    }

    /** A date range publishes `ne` exactly as it publishes `eq` — no ordering, but both equality
     *  verbs — so the two sides route through the same [Conditions.dateRangeOperand] either way. */
    @Test
    fun dateRangeInequalityConverts() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[Items/ItemCoverage] != [CoverageWindow]"))
        assertEquals("NotEqual", c.operatorId())
    }

    /** A semantically-indexed date-range cell is its own arm of the operand, not a computed range. */
    @Test
    fun aKeyedDateRangeCellComparesForEquality() {
        assertEquals("Equal", translate("[Items/ItemCoverage For \"a\"] == [CoverageWindow]").operatorId())
    }

    /** The keyed cell is exactly as good an inequality operand as a plain field. */
    @Test
    fun aKeyedDateRangeCellComparesForInequality() {
        assertEquals("NotEqual", translate("[Items/ItemCoverage For \"a\"] != [CoverageWindow]").operatorId())
    }

    /** A date range compares to a date range and nothing else — the model's handle refuses the date. */
    @Test
    fun comparingADateRangeToADateIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("[Items/ItemCoverage] == [OrderDate]") }
        assertTrue(e.message!!.contains("not a date-range field")) {
            "expected rulekit's own refusal in: ${e.message}"
        }
    }

    /** The mirror image: a date operand has no date-range arm either. */
    @Test
    fun orderingADateAgainstADateRangeIsUnmapped() {
        assertThrows<UnmappedConstructException> { translate("[OrderDate] < [CoverageWindow]") }
    }

    /** Strings have no ordering in the kernel; `StringExpr` publishes equality only. */
    @Test
    fun orderingTwoStringsIsUnmapped() {
        val e = assertThrows<UnmappedConstructException> { translate("[ProductCode] < [CustomerName]") }
        assertEquals("no typed rulekit comparison for STRING LT STRING", e.message)
    }

    @Test
    fun stringEqualityConverts() {
        assertEquals("NotEqual", translate("[ProductCode] != [CustomerName]").operatorId())
    }

    // --- Cmp: a quoted literal typed by its date sibling -------------------------------------

    /**
     * The kernel has no `DateLit` AST node — a quoted literal is always `StrLit`, typed from
     * its SIBLING at evaluation time (`ExprEval.evalCmpOperand`/`producesDate`). Beside a
     * `DATE`-kind field it is a date, in the kernel's own `dd.MM.yyyy` form.
     */
    @Test
    fun aLiteralBesideADateFieldIsTypedAsADate() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[OrderDate] > \"12.04.2020\""))
        assertEquals("GreaterThan", c.operatorId())
        assertEquals(DateLiteral("12.04.2020"), c.right())
    }

    /** Sibling typing reads whichever side is the field, regardless of which side is the literal. */
    @Test
    fun aLiteralBesideADateFieldIsTypedAsADateFromTheLeft() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("\"12.04.2020\" < [OrderDate]"))
        assertEquals(DateLiteral("12.04.2020"), c.left())
    }

    /** The omitted-year reference-year form (IF52) carries through verbatim, unparsed, like a full date. */
    @Test
    fun anOmittedYearLiteralBesideADateFieldIsTypedAsADate() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[OrderDate] > \"12.04.\""))
        assertEquals(DateLiteral("12.04."), c.right())
    }

    /** With no date sibling to consult, the literal is exactly what it looks like: a string. */
    @Test
    fun aLiteralWithNoDateSiblingStaysAString() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[ProductCode] == \"12.04.2020\""))
        assertEquals(StringLiteral("12.04.2020"), c.right())
    }

    /** Two literals compared to each other have no field on either side to consult — both stay strings. */
    @Test
    fun twoLiteralsWithNoFieldSiblingStayStrings() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("\"12.04.2020\" == \"13.04.2020\""))
        assertEquals(StringLiteral("12.04.2020"), c.left())
        assertEquals(StringLiteral("13.04.2020"), c.right())
    }

    /** Enumerations compare by stored value — ordering has no builder, and neither does a string peer. */
    @Test
    fun enumerationsCompareOnlyForEquality() {
        assertEquals("Equal", translate("[Priority] == [RushLevel]").operatorId())
        assertThrows<UnmappedConstructException> { translate("[Priority] < [RushLevel]") }
        assertThrows<UnmappedConstructException> { translate("[Priority] == [ProductCode]") }
    }

    @Test
    fun dateOrderingAndEqualityConvert() {
        assertEquals("LessThan", translate("[OrderDate] < [DeliveryDate]").operatorId())
        assertEquals("Equal", translate("[OrderDate] == [DeliveryDate]").operatorId())
    }

    @Test
    fun dateTimesCompareWithinTheirOwnFamily() {
        assertEquals("GreaterThanOrEqual", translate("[ScheduledAt] >= [CompletedAt]").operatorId())
    }

    /**
     * A date and a date-time order against each other on the common timeline, but the equality verbs take
     * their own family — so `==` across the two has no builder.
     */
    @Test
    fun aDateTimeOrdersAgainstADateButDoesNotEqualOne() {
        assertEquals("LessThan", translate("[ScheduledAt] < [OrderDate]").operatorId())
        assertEquals("GreaterThan", translate("[OrderDate] > [ScheduledAt]").operatorId())
        assertThrows<UnmappedConstructException> { translate("[ScheduledAt] == [OrderDate]") }
    }

    /** A time is its own family — it pairs with neither a date nor a date-time. */
    @Test
    fun comparingATimeToADateIsUnmapped() {
        assertThrows<UnmappedConstructException> { translate("[PickupTime] < [OrderDate]") }
    }

    /**
     * The kernel's authoring convention writes a TIME constant as a quoted string too — `Zeit(...) >
     * "11:11:11"` is an ordinary valid rule — typed by whatever it is compared against, exactly
     * like the date literal above. Unlike `isDateLike`'s DATE-only sibling typing, this is resolved
     * inside `Conditions.temporalOperand` itself, so it converts however the comparison recurses.
     */
    @Test
    fun aLiteralBesideATimeFieldIsTypedAsATimeValue() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[PickupTime] > \"11:11:11\""))
        assertEquals("GreaterThan", c.operatorId())
        assertEquals(TimeLiteral("11:11:11"), c.right())
    }

    /** The date-time twin of the above, via the new `Dsl.dateTimeValue` entry point. */
    @Test
    fun aLiteralBesideADateTimeFieldIsTypedAsADateTimeValue() {
        val c = assertInstanceOf(
            ComparisonCondition::class.java,
            translate("[ScheduledAt] > \"11.11.2018T11:11:11\""),
        )
        assertEquals("GreaterThan", c.operatorId())
        assertEquals(DateTimeLiteral("11.11.2018T11:11:11"), c.right())
    }

    /** Ordering, equality and inequality all reach the same literal branch, not just `>`. */
    @Test
    fun aTimeLiteralComparesForEqualityAndInequalityToo() {
        assertEquals("Equal", translate("[PickupTime] == \"11:11:11\"").operatorId())
        assertEquals("NotEqual", translate("[PickupTime] != \"11:11:11\"").operatorId())
        assertEquals("LessThan", translate("[PickupTime] < \"11:11:11\"").operatorId())
    }

    /** Booleans and confirms compare only to True/False, and rulekit publishes no field-vs-field builder. */
    @Test
    fun comparingTwoYesNoFieldsIsUnmapped() {
        assertThrows<UnmappedConstructException> { translate("[ExpressShipping] == [TermsConfirmed]") }
    }

    /**
     * MVK_INVALID_COMPARE_DEC_PLACES — routing a bare number field through the model's handle carries its
     * DECLARED SCALE, which is what arms rulekit's equality gate. Ordering is exempt from it.
     */
    @Test
    fun equalityAcrossDifferentNumericScalesIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("[Quantity] == [UnitPrice]") }
        assertTrue(e.message!!.contains("MVK_INVALID_COMPARE_DEC_PLACES")) {
            "expected the kernel's own code in: ${e.message}"
        }
        assertEquals("LessThan", translate("[Quantity] < [UnitPrice]").operatorId())
    }

    /** A scale-0 literal is padding-capable, so it still compares to a two-decimal field. */
    @Test
    fun anIntegerLiteralStillComparesToADecimalField() {
        assertEquals("Equal", translate("[UnitPrice] == 5").operatorId())
    }

    /** A number slot goes through the model's number handle, which refuses a field of another kind. */
    @Test
    fun comparingANumberToAStringIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("[Quantity] == [ProductCode]") }
        assertTrue(e.message!!.contains("not a number field")) {
            "expected rulekit's own refusal in: ${e.message}"
        }
    }

    /** A derived numeric expression has no model scale, so the gate defers — the aggregate still converts. */
    @Test
    fun aDerivedNumericExpressionStillCompares() {
        assertEquals("LessThan", translate("NumberOfFilledFields(Quantity, BackorderQuantity) < 2").operatorId())
    }

    // --- Cmp: the arms the literal mappers unblocked --------------------------------------------

    /** An enum against a constant goes through the model's handle, which knows the declared domain. */
    @Test
    fun anEnumComparesToAConstant() {
        assertEquals("Equal", translate("[Priority] == \"EXPRESS\"").operatorId())
        assertEquals("NotEqual", translate("[Priority] != \"OVERNIGHT\"").operatorId())
    }

    /** A constant outside the declared domain is refused by `EnumRef`, with the domain listed. */
    @Test
    fun anEnumConstantOutsideTheDomainIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("[Priority] == \"NOPE\"") }
        assertTrue(e.message!!.contains("is not a value of")) {
            "expected rulekit's own refusal in: ${e.message}"
        }
    }

    /** Booleans compare to True/False in both polarities — four distinct rulekit builders. */
    @Test
    fun aBooleanComparesToTrueAndFalse() {
        assertEquals("Equal", translate("[ExpressShipping] == True").operatorId())
        assertEquals("Equal", translate("[ExpressShipping] == False").operatorId())
        assertEquals("NotEqual", translate("[ExpressShipping] != True").operatorId())
        assertEquals("NotEqual", translate("[ExpressShipping] != False").operatorId())
    }

    /** But not to each other — rulekit publishes no field-vs-field boolean comparison. */
    @Test
    fun twoBooleanFieldsStillHaveNoBuilder() {
        assertThrows<UnmappedConstructException> { translate("[ExpressShipping] == [TermsConfirmed]") }
    }

    /**
     * A confirm compares to True only. `== False` is the kernel's MVK_INVALID_COMPARE_TO_YES, and
     * `ConfirmRef` erases it by publishing no `isFalse` — so there is no builder, not a refusal.
     */
    @Test
    fun aConfirmComparesToTrueOnly() {
        assertEquals("Equal", translate("[TermsConfirmed] == True").operatorId())
        assertEquals("NotEqual", translate("[TermsConfirmed] != True").operatorId())
        assertThrows<UnmappedConstructException> { translate("[TermsConfirmed] == False") }
    }

    /** A clock constant is a date operand like any other. */
    @Test
    fun aDateComparesToTheClockConstant() {
        assertEquals("LessThan", translate("[OrderDate] < Today").operatorId())
    }

    /** Arithmetic reaches the numeric slot, and its derived scale feeds the same equality gate. */
    @Test
    fun arithmeticComparesAsANumericExpression() {
        assertEquals("LessThan", translate("[Quantity] < {[BackorderQuantity] + 2}").operatorId())
    }

    // --- Not ----------------------------------------------------------------------------------

    /**
     * Negation has no rulekit target and no route in from condition text: the kernel has no `Not`, so
     * `LogicCondition` is `And`/`Or` only and the parser never builds the node. Reached
     * here by hand, it is unmapped — the honest third outcome, not a gap to close.
     */
    @Test
    fun negationIsUnmapped() {
        val negated: Cond = Cond.Not(Cond.Custom("NotReverse"))
        assertThrows<UnmappedConstructException> { bridge.condition(negated) }
    }

    // --- Custom -------------------------------------------------------------------------------

    @Test
    fun aCustomConditionConverts() {
        val c = assertInstanceOf(CustomConditionRef::class.java, translate("CustomCondition NotReverse"))
        assertEquals("NotReverse", c.name())
        assertEquals("CustomCondition", c.operatorId())
    }

    /** It composes like any other leaf — the error-field conjunct the kernel wants is an ordinary And. */
    @Test
    fun aCustomConditionComposesWithAPredicate() {
        val c = assertInstanceOf(
            LogicCondition::class.java,
            translate("FieldFilled(CustomerName) And CustomCondition NotReverse"),
        )
        assertInstanceOf(CustomConditionRef::class.java, c.right())
    }
}
