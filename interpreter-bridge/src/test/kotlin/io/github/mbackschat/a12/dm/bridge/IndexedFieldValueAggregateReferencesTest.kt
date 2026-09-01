package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef

/**
 * `IndexedFieldValue` became a [FieldOperand] (commit
 * `72107376a`) so it can fill an entity-list slot — `Sum(Field For key, other)`. Reached
 * directly as a value expression, an indexed operand is already classified correctly:
 * [ConditionReferences]'s main `Calculation` switch has dedicated
 * `SemanticIndexValue`/`SemanticIndexFieldValue` arms that mark the read `PROJECTED` and
 * additionally register the field-keyed form's KEY field (KF182 — the kernel matches a self-reference
 * against the key too).
 *
 * `ConditionReferences.collectAggregateOperand`/`collectOperand` were never touched by that
 * commit, and their `instanceof` fallback — not an exhaustive switch, so the compiler raises nothing
 * — routes any `FieldOperand` that is neither an `EnumCategoryAccess` nor a `GroupListRef`
 * to a PLAIN `refs.field(op.fieldPath())`. An indexed operand reached THROUGH an entity list (the
 * shape the commit just made legal) therefore loses both properties the direct path already gets right: the
 * read is recorded PLAIN instead of PROJECTED, and the key field is not recorded as a reference at all. The
 * inconsistency is invisible to a `case FieldRef` grep because nothing here is a missing switch arm —
 * it is an `instanceof` chain silently widening to a type nobody updated it for.
 *
 * Lives here rather than in rulekit's own suite because that suite compiles against `:adapter` and
 * so needs the kernel artifacts; this source set reaches the same builders without them.
 */
class IndexedFieldValueAggregateReferencesTest {

    private lateinit var model: BoundModel

    @BeforeEach
    fun bind() {
        val json = BridgeModels.orderJson
        model = BoundModel.ofJson(json)
    }

    /** The direct path — an indexed operand reached as a bare value expression — already gets this right. */
    @Test
    fun aDirectSemanticIndexReadIsProjected() {
        val direct: Calculation = model.field("/Order/Items/Count").semanticIndex("S-1").index()
        assertEquals(
            ConditionReferences.ReadMode.PROJECTED,
            ConditionReferences.readMode(direct, "/Order/Items/Count"),
        )
    }

    /**
     * The SAME operand, reached through an entity-list aggregate (`Sum`) instead of directly. Since
     * `IndexedFieldValue` is now a `FieldOperand`, `Sum(Count For "S-1", StockQty)` is a
     * legal condition — but `ConditionReferences` still reports the read as PLAIN.
     */
    @Test
    fun theSameIndexedReadInAnAggregateSlotStaysProjected() {
        val viaAggregate: Calculation = Dsl.sum(
            model.field("/Order/Items/Count").semanticIndex("S-1").index(),
            model.field("/Order/Items/StockQty"),
        ).expression()
        assertEquals(
            ConditionReferences.ReadMode.PROJECTED,
            ConditionReferences.readMode(viaAggregate, "/Order/Items/Count"),
            "an indexed operand inside Sum(...) is read exactly as it would be read directly — the" +
                " aggregate slot is not a different kind of read",
        )
    }

    /**
     * The field-keyed form's KEY field is a reference in its own right (KF182) when read directly. Through
     * the aggregate path it is dropped entirely — not merely misclassified, but absent from
     * `fieldPaths()` altogether.
     */
    @Test
    fun theKeyFieldOfAFieldKeyedIndexIsReferencedThroughAnAggregateSlotToo() {
        val key: FieldRef = model.field("/Order/Items/Sku")
        val direct: Calculation = model.field("/Order/Items/Count").semanticIndex(key).index()
        assertTrue(
            ConditionReferences.of(direct).fieldPaths().contains("/Order/Items/Sku"),
            "sanity check: the direct path already records the key field",
        )

        val viaAggregate: Calculation = Dsl.sum(
            model.field("/Order/Items/Count").semanticIndex(key).index(),
            model.field("/Order/Items/StockQty"),
        ).expression()
        assertTrue(
            ConditionReferences.of(viaAggregate).fieldPaths().contains("/Order/Items/Sku"),
            "the key field must stay a reference when the same indexed operand sits inside Sum(...)",
        )
    }
}
