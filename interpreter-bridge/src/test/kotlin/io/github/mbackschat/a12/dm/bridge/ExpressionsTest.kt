package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Arithmetic
import io.github.mbackschat.a12.dm.rulekit.dsl.BaseYear
import io.github.mbackschat.a12.dm.rulekit.dsl.Concatenation
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumCategoryAccess
import io.github.mbackschat.a12.dm.rulekit.dsl.Now
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.Today

/**
 * The leaf and operator shapes [Expressions] maps beyond `NumLit`/`Field`/
 * `SemanticIndex`: the no-operand constants, the string/boolean literals, and binary arithmetic.
 *
 * None of these five rulekit node shapes carries a compact-constructor check of its own — [Arithmetic]
 * and the literal records take whatever [io.github.mbackschat.a12.dm.rulekit.dsl.Calculation] their
 * operands resolve to, and [ComparisonCondition] does not cross-check the two sides' kinds either — so
 * there is no rejection to exercise here; only `FirstDay`/`LastDay` have a genuine third outcome.
 */
class ExpressionsTest {

    private lateinit var bridge: ConditionBridge

    @BeforeEach
    fun bind() {
        val json = BridgeModels.orderJson
        bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))
    }

    private fun translate(condition: String): Condition = bridge.translate(condition, BASE, ConditionLanguage.EN)

    // --- Const ----------------------------------------------------------------------------------

    @Test
    fun todayConverts() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[OrderDate] < Today"))
        assertInstanceOf(Today::class.java, c.right())
    }

    @Test
    fun nowConverts() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[ScheduledAt] < Now"))
        assertInstanceOf(Now::class.java, c.right())
    }

    @Test
    fun baseYearConverts() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[OrderDate] < BaseYear"))
        assertInstanceOf(BaseYear::class.java, c.right())
    }

    /**
     * `BaseYear` is polymorphic by use site: one node, a DATE role against a date field and a
     * year-*number* role against a numeric one. rulekit selects the second through
     * `Dsl.baseYearNumber()` rather than through the node's own `kind()`, so a numeric slot has to
     * ask for it by name.
     */
    @Test
    fun baseYearInANumericSlotConverts() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("YearFromDate(OrderDate) < BaseYear"))
        assertInstanceOf(BaseYear::class.java, c.right())
    }

    /**
     * `FirstDay`/`LastDay` are day-specification markers (Parser.kt's `CONSTANTS` set) the
     * owner grammar consumes structurally inside `ValueAsDate`, never evaluated standalone. Condition
     * text never puts one in an ordinary value slot, so they are reached here by hand, exactly as
     * `ConditionsTest#negationIsUnmapped` reaches `Cond.Not`.
     */
    @Test
    fun firstDayIsUnmapped() {
        assertThrows(UnmappedConstructException::class.java) { bridge.calculation(Expr.Const("FirstDay")) }
    }

    @Test
    fun lastDayIsUnmapped() {
        assertThrows(UnmappedConstructException::class.java) { bridge.calculation(Expr.Const("LastDay")) }
    }

    // --- StrLit -----------------------------------------------------------------------------------

    @Test
    fun aStringLiteralConverts() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[CustomerName] == \"Acme\""))
        val lit = assertInstanceOf(StringLiteral::class.java, c.right())
        assertEquals("Acme", lit.value())
    }

    // --- BoolLit ----------------------------------------------------------------------------------

    /** Consumed by `BoolRef.isTrue()` — a boolean compares against a literal, never against a field. */
    @Test
    fun aBooleanLiteralConverts() {
        assertNotNull(translate("[ExpressShipping] == True"))
    }

    // --- Arith ------------------------------------------------------------------------------------

    @Test
    fun arithmeticAdditionConverts() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[Quantity] + [BackorderQuantity] > 10"))
        val arith = assertInstanceOf(Arithmetic::class.java, c.left())
        assertEquals(Arithmetic.Op.ADD, arith.op())
    }

    @Test
    fun everyArithmeticOperatorMaps() {
        assertEquals(Arithmetic.Op.SUBTRACT, leftArithOp("[Quantity] - [BackorderQuantity] > 0"))
        assertEquals(Arithmetic.Op.MULTIPLY, leftArithOp("[Quantity] * [BackorderQuantity] > 0"))
        assertEquals(Arithmetic.Op.DIVIDE, leftArithOp("[Quantity] / [BackorderQuantity] > 0"))
        assertEquals(Arithmetic.Op.POWER, leftArithOp("[Quantity] ^ 2 > 0"))
    }

    /** Arithmetic recurses through `mapping.calculation`, so a constant is as valid an operand as a field. */
    @Test
    fun arithmeticRecursesThroughNestedConstructs() {
        val c = assertInstanceOf(
            ComparisonCondition::class.java,
            translate("{[Quantity] + [BackorderQuantity]} * 2 > 0"),
        )
        val outer = assertInstanceOf(Arithmetic::class.java, c.left())
        assertEquals(Arithmetic.Op.MULTIPLY, outer.op())
        assertInstanceOf(Arithmetic::class.java, outer.left())
    }

    private fun leftArithOp(condition: String): Arithmetic.Op {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate(condition))
        return assertInstanceOf(Arithmetic::class.java, c.left()).op()
    }

    // --- + is overloaded ---------------------------------------------------------------------------

    /**
     * The kernel's `+` concatenates when an operand is textual and adds when they are numeric, so the
     * node has to be chosen from the operands. Building the arithmetic one regardless made the expression
     * claim NUMBER, and the comparison around it then asked a string field for a numeric handle.
     */
    @Test
    fun plusOverStringsIsConcatenation() {
        assertInstanceOf(
            Concatenation::class.java,
            Expressions(bridge).map(
                Expr.Arith(ArithOp.ADD, Expr.Field("/Order/CustomerName", false), Expr.StrLit(" ")),
            ),
        )
    }

    /**
     * A left operand that is no field keeps its place: the comparison dispatches on the left side's KIND, so a
     * number before a count and a concatenation or a quoted literal before a text side all map, in the order
     * written. The adapter's kernel-model reader had to learn the same three (`adapter.ComparisonLeftOperandReadTest`).
     */
    @Test
    fun aLeftOperandThatIsNoFieldKeepsItsPlace() {
        val count = assertInstanceOf(ComparisonCondition::class.java,
            translate("0 < NumberOfDifferentValues(Items*/Sku)"))
        assertInstanceOf(NumberLiteral::class.java, count.left())
        assertInstanceOf(CountAggregate::class.java, count.right())
        val joined = assertInstanceOf(ComparisonCondition::class.java,
            translate("[CustomerName] + \" \" + [ProductCode] == [ShippingAddress/City]"))
        assertInstanceOf(Concatenation::class.java, joined.left())
        val literal = assertInstanceOf(ComparisonCondition::class.java,
            translate("\"Guest\" != [CustomerName] + [ProductCode]"))
        assertInstanceOf(StringLiteral::class.java, literal.left())
        assertInstanceOf(Concatenation::class.java, literal.right())
    }

    /**
     * Two enumerations join their stored values as text — A12 computes `{[Tier]}+{[Plan]}` to `GOLDBASIC`
     * (KERNEL-FINDINGS KF530) — so the node is the one the typed `EnumRef.plus` builds, never arithmetic.
     */
    @Test
    fun plusOverTwoEnumerationsIsConcatenation() {
        val m = BoundModel.ofJson(BridgeModels.orderJson)
        assertEquals(
            m.enumOf("/Order/Priority").plus(m.enumOf("/Order/RushLevel")).expression(),
            Expressions(bridge).map(
                Expr.Arith(ArithOp.ADD, Expr.Field("/Order/Priority", false), Expr.Field("/Order/RushLevel", false)),
            ),
        )
    }

    /** And the numeric case still builds the numeric node. */
    @Test
    fun plusOverNumbersIsArithmetic() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[Quantity] + [BackorderQuantity] > 10"))
        assertInstanceOf(Arithmetic::class.java, c.left())
    }

    /** End to end: a concatenation of two string fields compared against a third. */
    @Test
    fun aConcatenationComparedToAStringConverts() {
        assertNotNull(translate("[CustomerName] + \" \" + [ProductCode] != [CustomerName]"))
    }

    // --- EnumCategory --------------------------------------------------------------------------

    /**
     * `[field -> Category]` maps an enum value to its category value, and rulekit publishes it as
     * [EnumCategoryAccess] — a `Calculation`, so it belongs in an ordinary comparison operand
     * slot alongside the field and constant nodes.
     */
    @Test
    fun anEnumCategoryAccessConverts() {
        val c = assertInstanceOf(ComparisonCondition::class.java, translate("[Priority -> SpeedClass] == \"FAST\""))
        val access = assertInstanceOf(EnumCategoryAccess::class.java, c.left())
        assertEquals("SpeedClass", access.category())
        assertEquals("/Order/Priority", access.field().path().value())
    }

    /** The `$` correlation rides a category access as it does any other entity reference. */
    @Test
    fun aCorrelatedEnumCategoryAccessKeepsItsCorrelation() {
        val c = assertInstanceOf(
            ComparisonCondition::class.java,
            translate("[\$Priority -> SpeedClass] == \"FAST\""),
        )
        assertTrue(assertInstanceOf(EnumCategoryAccess::class.java, c.left()).outer())
    }

    /**
     * An undeclared category name travels through unchecked: [EnumCategoryAccess] carries the name as a
     * plain string and consults no model, so whether the field declares it is kernel-arbitrated. Pinned
     * because the bridge must not grow a check of its own here — if rulekit gains one, this is where it
     * shows up.
     */
    @Test
    fun anUndeclaredCategoryNameTravelsUnchecked() {
        val c = assertInstanceOf(
            ComparisonCondition::class.java,
            translate("[Priority -> NoSuchCategory] == \"FAST\""),
        )
        assertEquals("NoSuchCategory", assertInstanceOf(EnumCategoryAccess::class.java, c.left()).category())
    }

    private companion object {
        const val BASE = "/Order"
    }
}
