package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences
import io.github.mbackschat.a12.dm.rulekit.dsl.EntityListPredicate
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexFieldValue
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexValue
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier

/**
 * `IndexedGroupValue` is an `AggregateOperand`/`GroupListOperand`, so it can fill an entity-list slot
 * — `AllFieldsFilled(Group For key, other)`. Reference collection is the half no compiler checks:
 * `ConditionReferences.collectAggregateOperand` and `GroupListPredicate`'s own entity-list collection
 * both dispatch by `switch`, so a missing arm there is a compile error, but the SHARED
 * `collectIndexedGroup` helper both switches call reports whatever it reports. The key FIELD of a
 * field-keyed form is a reference in its own right, and these tests are what holds that.
 *
 * Unlike the field form, `IndexedGroupValue` is never a `Calculation` — a group has no
 * value of its own — so it can never flow through the OTHER exhaustive switch (the one with dedicated
 * `SemanticIndexValue`/`SemanticIndexFieldValue` arms) at all, and the PROJECTED/PLAIN
 * `ReadMode` distinction (which exists to feed self-reference detection for a computed field's
 * own formula) does not apply to a group; only whether the key field counts as a reference does.
 *
 * Lives here rather than in rulekit's own suite because that suite compiles against `:adapter`
 * and so needs the kernel artifacts; this source set reaches the same builders without them.
 */
class IndexedGroupValueAggregateReferencesTest {

    private lateinit var model: BoundModel

    @BeforeEach
    fun bind() {
        val json = BridgeModels.orderJson
        model = BoundModel.ofJson(json)
    }

    private fun items(): GroupListRef = model.flattenGroup("/Order/Items")

    /** The literal-keyed form references only the group — there is no key field to drop. */
    @Test
    fun aLiteralKeyedIndexedGroupReferencesTheGroup() {
        val indexed: AggregateOperand = GroupSemanticIndexValue(items(), "S-1")
        val refs = ConditionReferences.of(
            EntityListPredicate(Quantifier.ALL_FIELDS_FILLED, listOf(indexed, model.field("/Order/CustomerName"))),
        )
        assertTrue(refs.groupPaths().contains("/Order/Items")) { "expected the group among ${refs.groupPaths()}" }
    }

    /**
     * The field-keyed form's KEY field is a reference in its own right — its value is what the
     * condition depends on to pick the repetition — and must count toward `fieldPaths()`/
     * `covers()` exactly as the direct-value path's own key already does.
     */
    @Test
    fun theKeyFieldOfAFieldKeyedIndexedGroupIsReferenced() {
        val key: FieldRef = model.field("/Order/ProductCode")
        val indexed: AggregateOperand = GroupSemanticIndexFieldValue(items(), key)
        val refs = ConditionReferences.of(
            EntityListPredicate(Quantifier.ALL_FIELDS_FILLED, listOf(indexed, model.field("/Order/CustomerName"))),
        )
        assertTrue(refs.groupPaths().contains("/Order/Items")) { "expected the group among ${refs.groupPaths()}" }
        assertTrue(refs.fieldPaths().contains("/Order/ProductCode")) {
            "the key field must be a reference too — got ${refs.fieldPaths()}"
        }
    }

    /** Sanity check: an UNKEYED field the condition never mentions stays unreferenced. */
    @Test
    fun anUnrelatedFieldIsNotReferenced() {
        val indexed: AggregateOperand = GroupSemanticIndexValue(items(), "S-1")
        val refs = ConditionReferences.of(
            EntityListPredicate(Quantifier.ALL_FIELDS_FILLED, listOf(indexed, model.field("/Order/CustomerName"))),
        )
        assertFalse(refs.fieldPaths().contains("/Order/ProductCode"))
    }
}
