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
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedPresenceCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexFieldValue
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexValue

/**
 * Operand resolution on its own — the two shapes an operator hands down, `Star` and `SemanticIndex`.
 *
 * The star's stars are the point. `Expr.Star` carries the path with them stripped, so resolving one
 * means rebuilding the starred path rulekit reads; a reconstruction that lost a star would turn
 * `MVK_INVALID_WILDCARD` into silence, and one that never wrote a star at all reaches
 * `flatten` as a plain path and comes back as a rulekit throw that is not a finding.
 */
class OperandsTest {

    private lateinit var bridge: ConditionBridge
    private lateinit var operands: Operands

    @BeforeEach
    fun bind() {
        val json = BridgeModels.orderJson
        bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))
        operands = Operands(bridge)
    }

    /** One operand, parsed in the argument position that gives it its `Having` filter and its stars. */
    private fun operand(operandText: String): Expr {
        val parsed = parseCondition("NumberOfFilledFields($operandText) > 0", BASE, ConditionLanguage.EN)
        val func = (parsed as Cond.Cmp).left as Expr.Func
        return func.args[0]
    }

    // --- Star ------------------------------------------------------------------------------------

    @Test
    fun aStarredFieldIsAFlattenedRepetitionList() {
        val list = assertInstanceOf(FieldListRef::class.java, operands.aggregateOperand(operand("Items*/Count")))
        assertEquals("/Order/Items*/Count", list.renderedPath())
    }

    @Test
    fun aStarredGroupIsAGroupScope() {
        val scope = assertInstanceOf(GroupListRef::class.java, operands.aggregateOperand(operand("Items*")))
        assertEquals("/Order/Items*", scope.renderedPath())
    }

    /** MVK_INVALID_WILDCARD — the authored star survives onto a group the model says does not repeat. */
    @Test
    fun aStarOnANonRepeatableGroupIsRejected() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            operands.aggregateOperand(operand("ShippingAddress*/Street"))
        }
        assertTrue(e.message!!.contains("MVK_INVALID_WILDCARD")) { "expected the kernel's own code in: ${e.message}" }
    }

    /** The same code on the group arm — `flattenGroup` sees the star only if it is re-rendered. */
    @Test
    fun aStarOnANonRepeatableGroupScopeIsRejected() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            operands.aggregateOperand(operand("ShippingAddress*"))
        }
        assertTrue(e.message!!.contains("MVK_INVALID_WILDCARD")) { "expected the kernel's own code in: ${e.message}" }
    }

    @Test
    fun aStarredFieldFillsAFieldOnlySlot() {
        assertInstanceOf(FieldListRef::class.java, operands.fieldOperand(operand("Items*/Count")))
    }

    @Test
    fun aGroupScopeDoesNotFitAFieldOnlySlot() {
        assertThrows(UnmappedConstructException::class.java) { operands.fieldOperand(operand("Items*")) }
    }

    /** An indexed cell fills a field-only slot too — `IndexedFieldValue` is a `FieldOperand`. */
    @Test
    fun anIndexedCellFillsAFieldOnlySlot() {
        val cell = assertInstanceOf(
            SemanticIndexValue::class.java,
            operands.fieldOperand(operand("Items/Count For \"S-1\"")),
        )
        assertEquals("/Order/Items/Count", cell.field().path().value())
        assertEquals("S-1", cell.indexValue())
    }

    @Test
    fun aStarIsNotAScalarOperand() {
        assertThrows(UnmappedConstructException::class.java) { operands.fieldRef(operand("Items*/Count")) }
    }

    @Test
    fun aHavingFilterNarrowsTheRepetitionList() {
        val list = assertInstanceOf(
            FieldListRef::class.java,
            operands.aggregateOperand(operand("Items*/Count Having FieldFilled(Items/Sku)")),
        )
        assertTrue(list.filter().isPresent())
    }

    @Test
    fun aHavingFilterOnAGroupScopeIsUnmapped() {
        assertThrows(UnmappedConstructException::class.java) {
            operands.aggregateOperand(operand("Items* Having FieldFilled(Items/Sku)"))
        }
    }

    @Test
    fun aCorrelatedStarIsTheOuterRepetitionList() {
        val list = assertInstanceOf(FieldListRef::class.java, operands.aggregateOperand(operand("\$Items*/Count")))
        assertTrue(list.correlated())
    }

    @Test
    fun aCorrelatedFieldIsTheOuterReference() {
        val ref = operands.fieldRef(operand("\$Quantity"))
        assertTrue(ref.correlated())
    }

    /** A starred group stays one repeating `GroupRef` where the operator counts repetitions. */
    @Test
    fun aStarredGroupResolvesAsARepeatingGroupRef() {
        val group = operands.groupRef(operand("Items*"))
        assertTrue(group.repeating())
        assertNotNull(bridge.translate("NumberOfFilledGroups(Items*) < 1", BASE, ConditionLanguage.EN))
    }

    /**
     * Two starred operands in one entity list, end to end. The kernel skips wildcarded refs in
     * `checkDuplicatesInDirectRefs`, so a repetition list carries no dedup identity and two of them sit
     * in the same call without drawing MVK_DUPLICATE_PARAM1.
     */
    @Test
    fun twoRepetitionListsShareOneEntityList() {
        assertNotNull(
            bridge.translate("NumberOfFilledFields(Items*/Count, Items*/StockQty) < 2", BASE, ConditionLanguage.EN),
        )
    }

    // --- SemanticIndex ---------------------------------------------------------------------------

    @Test
    fun aLiteralKeyedIndexResolvesToTheIndexedCell() {
        val cell = assertInstanceOf(
            SemanticIndexValue::class.java,
            operands.indexedValue(operand("Items/Count For \"S-1\"")),
        )
        assertEquals("/Order/Items/Count", cell.field().path().value())
        assertEquals("S-1", cell.indexValue())
    }

    @Test
    fun aFieldKeyedIndexResolvesBothFields() {
        val cell = assertInstanceOf(
            SemanticIndexFieldValue::class.java,
            operands.indexedValue(operand("Items/Count For ProductCode")),
        )
        assertEquals("/Order/Items/Count", cell.field().path().value())
        assertEquals("/Order/ProductCode", cell.indexField().path().value())
    }

    @Test
    fun anIndexedCellHasAPresenceOfItsOwn() {
        assertInstanceOf(
            IndexedPresenceCondition::class.java,
            operands.semanticIndex(operand("Items/Count For \"S-1\"")).filled(),
        )
    }

    /**
     * rulekit's entity list is `FieldOperand | GroupListRef`, and `IndexedFieldValue` is a
     * `FieldOperand` — an indexed cell behaves like a single field entity there. See
     * [SemanticIndexEntityListTest] for the duplicate/arity behaviour end to end; this test only pins
     * the operand resolution itself.
     */
    @Test
    fun anIndexedCellInAnEntityListSlotResolvesToTheIndexedCell() {
        val cell = assertInstanceOf(
            SemanticIndexValue::class.java,
            operands.aggregateOperand(operand("Items/Count For \"S-1\"")),
        )
        assertEquals("/Order/Items/Count", cell.field().path().value())
        assertEquals("S-1", cell.indexValue())
    }

    @Test
    fun anIndexedCellIsNotAScalarFieldReference() {
        assertThrows(UnmappedConstructException::class.java) {
            operands.fieldRef(operand("Items/Count For \"S-1\""))
        }
    }

    // --- EntitySpec (starred source + semantic index / category) ---------------------------------

    /**
     * A starred source combined with a semantic index parses as `Expr.EntitySpec`, not
     * `Expr.SemanticIndex` — the interpreter reserves the latter for a plain field's cell. Neither
     * a repetition list nor an indexed cell is a [FieldRef], so a scalar slot has no shape for their
     * combination; the message must name the actual construct rather than fall back to the class name.
     */
    @Test
    fun anEntitySpecOnAStarredSourceIsUnmappedInAScalarSlot() {
        val e = assertThrows(UnmappedConstructException::class.java) {
            operands.fieldRef(operand("Items*/Count For \"S-1\""))
        }
        assertTrue(e.message!!.contains("starred")) { "expected 'starred' in: ${e.message}" }
        assertTrue(e.message!!.contains("semantic index")) { "expected 'semantic index' in: ${e.message}" }
    }

    /** The same EntitySpec shape, reached through the aggregate slot's fallback to [Operands.fieldRef]. */
    @Test
    fun anEntitySpecOnAStarredSourceIsUnmappedInAnAggregateSlotToo() {
        assertThrows(UnmappedConstructException::class.java) {
            operands.aggregateOperand(operand("Items*/Count For \"S-1\""))
        }
    }

    private companion object {
        const val BASE = "/Order"
    }
}
