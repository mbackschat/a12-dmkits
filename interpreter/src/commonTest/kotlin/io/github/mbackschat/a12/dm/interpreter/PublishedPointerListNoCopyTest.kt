package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **The public boundary must not copy a pointer list the engine already built immutably.**
 *
 * `RepetitionNotUniqueProjectionReuseTest` pins that the ENGINE shares one cluster projection across the
 * findings of a duplicate cluster. This pins the other half: the projection into `ValidationFinding` publishes
 * that list rather than snapshotting it again. A per-finding copy is O(N²) allocation on a dense cluster and
 * exhausted the heap at 8,000 rows (IF256); a bounded reuse cache did not fix it, because interleaved clusters
 * miss every slot (IF261).
 *
 * **Both engine shapes are exercised, and the second is the one that got missed.** A pure `RepetitionNotUnique`
 * rule publishes the cluster snapshot directly. A rule that ALSO references a field publishes an immutable
 * *concatenation* over that snapshot — a different class, immutable just the same, and a boundary recognizing
 * only the snapshot copied it on every message. The identity guard in `:adapter` reads the public façade and
 * cannot tell the two apart, so the discriminating case has to be here, at the seam.
 */
class PublishedPointerListNoCopyTest {

    @Test
    fun aPureRnuMessagePublishesTheClusterProjectionItself() {
        val messages = fire("RepetitionNotUnique(Items/Count)")

        assertEquals(4, messages.size)
        assertTrue(
            messages.all { it.referencedPointers.size == 4 },
            "each firing row must reference the whole cluster, else there is no shared list to publish",
        )
        messages.forEach { message ->
            assertSame(
                message.referencedPointers, validationFinding(message).referenced,
                "the boundary must publish the engine's shared cluster list, not a copy of it",
            )
        }
    }

    /**
     * The joined shape: a direct field reference in front of the shared cluster. The published list must still
     * be the engine's instance — the concatenation is immutable, so copying it buys nothing and costs one
     * N-element allocation per finding.
     */
    @Test
    fun anRnuMessageCarryingDirectReferencesPublishesTheJoinedListItself() {
        val messages = fire("FieldFilled(Items/Sku) And RepetitionNotUnique(Items/Count)")

        assertEquals(4, messages.size)
        // Non-vacuity: this shape must actually produce the JOINED list, not fall back to the bare cluster.
        // 4 cluster peers + this row's own Sku reference; if it read 4 the direct ref never made it in and the
        // test would be re-running the case above under a different name.
        assertTrue(
            messages.all { it.referencedPointers.size > 4 },
            "expected the direct reference to join the cluster projection, got sizes " +
                messages.map { it.referencedPointers.size },
        )
        messages.forEach { message ->
            assertSame(
                message.referencedPointers, validationFinding(message).referenced,
                "an immutable concatenation must publish as-is; recognizing only the snapshot class is what " +
                    "left this shape copying one N-element list per finding",
            )
        }
    }

    /** A list the engine did NOT build stays defensively copied, so the published list is immutable either way. */
    @Test
    fun aForeignListIsStillCopiedBeforePublication() {
        val message = fire("RepetitionNotUnique(Items/Count)").first()
        val mutable = ArrayList(message.referencedPointers)

        val published = validationFinding(message.copy(referencedPointers = mutable)).referenced

        assertEquals(mutable.toList(), published.toList())
        assertTrue(
            published !== mutable,
            "a caller-supplied list must be snapshotted; publishing it would let the caller mutate a finding",
        )
    }

    private fun fire(condition: String) = DmInterpreter(model(condition))
        .validateFull(document(model(condition)))
        .filter { it.code == "RU" }

    private fun model(condition: String) = ModelDef(
        fields = listOf(FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER)),
        rules = listOf(RuleDef("/Order/RU", "/Order", condition, "RU", "../Items/Count")),
        repeatableGroups = setOf(ITEMS),
        declaredReps = mapOf(ITEMS to 10),
        indexFields = setOf(SKU),
    )

    /** Four rows sharing one `Count`, so every row fires and the cluster is the whole column. */
    private fun document(model: ModelDef) = model.newDocument().group("/Order", 1).also { doc ->
        repeat(4) { index ->
            val row = index + 1
            doc.group(ITEMS, 1, row).field(SKU, "K$row", 1, row, 1).field(COUNT, "5", 1, row, 1)
        }
    }

    private companion object {
        const val ITEMS = "/Order/Items"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
