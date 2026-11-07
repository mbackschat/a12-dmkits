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
 * **The public boundary publishes a group of possibly-causing entities the engine shares, never a copy of it.**
 *
 * `CausingGroupClusterSharingTest` pins that the ENGINE shares a duplicate cluster's cells across its messages;
 * this pins that `ValidationFinding.possiblyCausing` keeps that sharing, as `PublishedPointerListNoCopyTest` does
 * for `referenced`: a per-finding copy is the O(N²) allocation of INTERPRETER-FINDINGS IF256/IF261 again.
 */
class PublishedCauseGroupNoCopyTest {

    @Test
    fun aClusterGroupIsPublishedItself() {
        fire("RepetitionNotUnique(Items/Count)").forEach { message ->
            assertSame(message.groupsOfPossiblyCausingEntities.single(), validationFinding(message).possiblyCausing.single())
        }
    }

    /** A row's own entity joined to the cluster is a view, and the view is published as-is too. */
    @Test
    fun aJoinedGroupIsPublishedItself() {
        fire("FieldFilled(Items/Sku) And RepetitionNotUnique(Items/Count)").forEach { message ->
            val group = message.groupsOfPossiblyCausingEntities.single()
            assertTrue(group is CausingUnion<*>, "this shape must join the row's entity to the cluster: $group")
            assertSame(group, validationFinding(message).possiblyCausing.single())
        }
    }

    /** A set the engine did NOT build is snapshotted, so the published group is immutable either way. */
    @Test
    fun aForeignSetIsStillCopiedBeforePublication() {
        val message = fire("RepetitionNotUnique(Items/Count)").first()
        val mutable = LinkedHashSet(message.groupsOfPossiblyCausingEntities.single())

        val published = validationFinding(message.copy(groupsOfPossiblyCausingEntities = listOf(mutable))).possiblyCausing

        assertEquals(listOf<Set<A12PartiallyKnownMultiPointer>>(mutable), published)
        assertTrue(published.single() !== mutable, "a caller-supplied set must be snapshotted")
    }

    private fun fire(condition: String): List<io.github.mbackschat.a12.dm.interpreter.model.Message> {
        val model = ModelDef(
            fields = listOf(FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER)),
            rules = listOf(RuleDef("/Order/RU", "/Order", condition, "RU", "../Items/Count")),
            repeatableGroups = setOf(ITEMS),
            declaredReps = mapOf(ITEMS to 10),
            indexFields = setOf(SKU),
        )
        val document = model.newDocument().group("/Order", 1).also { doc ->
            (1..4).forEach { row -> doc.group(ITEMS, 1, row).field(SKU, "K$row", 1, row, 1).field(COUNT, "5", 1, row, 1) }
        }
        return DmInterpreter(model).validateFull(document).filter { it.code == "RU" }
            .also { assertEquals(4, it.size, "every row of the cluster must fire") }
    }

    private companion object {
        const val ITEMS = "/Order/Items"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
