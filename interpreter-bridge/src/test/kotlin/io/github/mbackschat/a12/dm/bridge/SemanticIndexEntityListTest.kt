package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexValue
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexValue
import java.nio.charset.StandardCharsets

/**
 * An index-selected field (`Items/Count For "S-1"`) standing in an entity-list slot: `IndexedFieldValue`
 * is a `FieldOperand`, and it behaves there like a single field entity — it never satisfies arity alone
 * the way a starred `FieldListRef` does, and it participates in exact-duplicate detection keyed on field
 * path AND index, never on path alone.
 *
 * The duplicate/non-duplicate pairs are the load-bearing half: keying on path alone would reject a
 * legal pair of differently-keyed cells as `MVK_DUPLICATE_PARAM1`, which the kernel accepts.
 */
class SemanticIndexEntityListTest {

    private lateinit var bridge: ConditionBridge
    private lateinit var model: BoundModel

    @BeforeEach
    fun bind() {
        val json = String(
            SemanticIndexEntityListTest::class.java.getResourceAsStream("/order.json")!!.readAllBytes(),
            StandardCharsets.UTF_8,
        )
        model = BoundModel.ofJson(json)
        bridge = ConditionBridge(model, ModelIndex.of(json, ReferenceResolvers.selfContained()))
    }

    private fun translate(condition: String): Any = bridge.translate(condition, BASE, ConditionLanguage.EN)

    /** The left-hand calculation of a mapped comparison — where the entity-list aggregates land. */
    private fun left(condition: String): Calculation =
        assertInstanceOf(ComparisonCondition::class.java, translate(condition)).left()

    // --- entity-list-aggregate (Operands.aggregateOperand) ----------------------------------------

    @Test
    fun aValidIndexedOperandInAnAggregateSlotConverts() {
        assertInstanceOf(
            CountAggregate::class.java,
            left("NumberOfFilledFields(Items/Count For \"S-1\", Items/Sku For \"S-2\") > 0"),
        )
    }

    /** Same field, same index key, twice — an exact duplicate, rulekit's own MVK_DUPLICATE_PARAM1. */
    @Test
    fun twoIndexedOperandsWithTheSameKeyAreADuplicate() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            translate("NumberOfFilledFields(Items/Count For \"S-1\", Items/Count For \"S-1\") > 0")
        }
        assertTrue(e.message!!.contains("MVK_DUPLICATE_PARAM1")) { "expected the kernel's own code in: ${e.message}" }
    }

    /** Same field, DIFFERENT index key — not a duplicate; identity is (path, key), not path alone. */
    @Test
    fun twoIndexedOperandsDifferingOnlyByIndexKeyAreNotADuplicate() {
        assertDoesNotThrow {
            translate("NumberOfFilledFields(Items/Count For \"S-1\", Items/Count For \"S-2\") > 0")
        }
    }

    /**
     * Same field, same index key, but one is `$`-correlated and the other is not — a different
     * repetition entirely, so not a duplicate either, exactly as commit `2a5c9a409` established for a
     * plain [io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef].
     */
    @Test
    fun twoIndexedOperandsDifferingOnlyByCorrelationAreNotADuplicate() {
        assertDoesNotThrow {
            translate("NumberOfFilledFields(\$Items/Count For \"S-1\", Items/Count For \"S-1\") > 0")
        }
    }

    /** The exact-duplicate gate still fires when BOTH sides carry the same correlation. */
    @Test
    fun twoCorrelatedIndexedOperandsWithTheSameKeyAreStillADuplicate() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            translate("NumberOfFilledFields(\$Items/Count For \"S-1\", \$Items/Count For \"S-1\") > 0")
        }
        assertTrue(e.message!!.contains("MVK_DUPLICATE_PARAM1")) { "expected the kernel's own code in: ${e.message}" }
    }

    // --- field-operand-semidx (Operands.fieldOperand) ---------------------------------------------

    /** `FirstFilledValue` takes the narrower [io.github.mbackschat.a12.dm.bridge.Mapping.fieldOperand] slot. */
    @Test
    fun aValidIndexedOperandInAFieldOnlySlotConverts() {
        assertInstanceOf(
            FirstFilledValue::class.java,
            left("FirstFilledValue(Items/Count For \"S-1\", Items/Count For \"S-2\") > 0"),
        )
    }

    // --- index-selected GROUP ---------------------------------------------------------------------

    /**
     * A semantic index whose target is a GROUP picks one repetition of it — the group analog of an
     * index-selected field, and an `AggregateOperand` in its own right.
     * Different keys are not a duplicate, mirroring the field form exactly.
     */
    @Test
    fun aGroupTargetedIndexConverts() {
        assertInstanceOf(
            CountAggregate::class.java,
            left("NumberOfFilledFields(Items For \"a\", Items For \"b\") > 1"),
        )
    }

    /**
     * The same, in the field-only list slot — routes to the already-existing, already-guarded
     * `BoundModel.firstFilledGroupFor` rather than `Operands.fieldOperand`'s field resolver.
     * `ShippingAddress` (all-STRING fields), not `Items` (mixed field kinds, which would draw
     * a genuine, unrelated `FirstFilledValue` owner-family rejection of its own).
     */
    @Test
    fun aGroupTargetedIndexInAFieldOnlySlotConverts() {
        assertInstanceOf(
            FirstFilledValue::class.java,
            left("FirstFilledValue(ShippingAddress For \"a\", ShippingAddress For \"b\") == \"x\""),
        )
    }

    /**
     * A field reached with an index, beside its OWN enclosing group reached with the same index — the
     * indirect-overlap case (`MVK_DUPLICATE_PARAM2`) the design report calls out as staying
     * kernel-arbitrated, exactly as it already does for a plain (unindexed) group's own indirect-overlap
     * arm. Converting here rather than being caught is the correct, deliberate answer.
     */
    @Test
    fun anIndexedFieldBesideItsOwnIndexedEnclosingGroupIsNotCaughtHere() {
        assertDoesNotThrow {
            translate("NumberOfFilledFields(Items/Count For \"a\", Items For \"a\") > 1")
        }
    }

    /** Same group, same key, twice — an exact duplicate, rulekit's own MVK_DUPLICATE_PARAM1. */
    @Test
    fun twoIndexedGroupOperandsWithTheSameKeyAreADuplicate() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            translate("NumberOfFilledFields(Items For \"a\", Items For \"a\") > 1")
        }
        assertTrue(e.message!!.contains("MVK_DUPLICATE_PARAM1")) { "expected the kernel's own code in: ${e.message}" }
    }

    /** Same group, DIFFERENT index key — not a duplicate; identity is (path, key), not path alone. */
    @Test
    fun twoIndexedGroupOperandsDifferingOnlyByIndexKeyAreNotADuplicate() {
        assertDoesNotThrow {
            translate("NumberOfFilledFields(Items For \"a\", Items For \"b\") > 1")
        }
    }

    /**
     * A starred group with an index is a contradiction — an index picks ONE repetition, a wildcard scans
     * ALL of them — so rulekit's own guard (`BoundModel.requireExactSemanticIndexGroup`, reused
     * unchanged from the `FirstFilledValue` arm this construct already had) refuses it.
     *
     * The bridge's own routing can never actually construct a starred scope here: a bare semantic
     * index's target is never itself starred in the AST (that shape is `Expr.EntitySpec`, the
     * out-of-scope entityspec-field-slot bucket), and `Operands.indexedGroup` passes the parsed
     * path straight to `flattenGroup` with no `*` in it. So this proves the TYPE's own guard
     * directly — the way `OperandsTest` already tests operand resolution below the parser — rather
     * than asserting an end-to-end shape this bridge cannot currently produce.
     */
    @Test
    fun aStarredGroupScopeWithAnIndexIsRejectedByTheTypeItself() {
        val starred: GroupListRef = model.flattenGroup("/Order/Items*")
        val e = assertThrows(IllegalArgumentException::class.java) {
            GroupSemanticIndexValue(starred, "a")
        }
        assertTrue(e.message!!.contains("MVK_SEMANTIC_INDEX_AND_WILDCARD")) {
            "expected the kernel's own code in: ${e.message}"
        }
    }

    // --- blast radius: shapes this change must NOT pull in -----------------------------------------

    /**
     * An index-selected FIELD still routes exactly as §1 shipped it, unaffected by the group arm added
     * here — the routing guard is `mapping.isGroup(indexed.getPath())`, so widening what happens on
     * the `true` side must not touch the `false` one.
     */
    @Test
    fun anIndexedFieldStillRoutesAsBefore() {
        val count = assertInstanceOf(
            CountAggregate::class.java,
            left("NumberOfFilledFields(Items/Count For \"S-1\", Items/Sku For \"S-2\") > 0"),
        )
        assertInstanceOf(SemanticIndexValue::class.java, count.fields().get(0))
    }

    /**
     * §2c (an index-selected group as a SCALAR presence/`CurrentRepetition` operand, via
     * `Operands.groupRef`) is a different, unrelated bridge call site from the entity-list one this task
     * widens (`Operands.aggregateOperand`/`fieldOperand`) — it must keep throwing exactly as
     * it did before, not silently start converting or start throwing a different exception type.
     */
    @Test
    fun aGroupTargetedIndexInAScalarPresenceSlotStillThrowsNoTypedOperation() {
        assertThrows(NoTypedOperationException::class.java) {
            translate("GroupFilled(Items For \"a\")")
        }
    }

    /**
     * §2b (an index-selected GROUP inside the entity-valued value-list quantifier form) is not evidenced
     * for this task and `ValueListOperand` carries no arm for it — [Conditions.valueListOperand]
     * explicitly refuses it rather than silently routing it through the new
     * `AggregateOperand` arm, exactly the failure mode the prior guardrail breach was.
     */
    @Test
    fun aGroupTargetedIndexInAValueListEntitySlotStaysUnmapped() {
        assertThrows(UnmappedConstructException::class.java) {
            translate("NoFieldValueIncludedInValueList(Items For \"a\" In ProductCode)")
        }
    }

    private companion object {
        const val BASE = "/Order"
    }
}
