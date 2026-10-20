package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential (groovy-dynamic == java-static == interpreter) for a computation that reads a Confirm field. A
 * replay of a12-kernel-lean's row-copy carriers (`filter-suppression-and-row-carriers-2026-10-14`, `R11_DM`)
 * found the kernel leaving a Confirm target empty where its Confirm source was empty, while the interpreter
 * wrote `false` and reported the target erroneous.
 *
 * Two accounts fit that row: an empty Confirm READS empty in a computation, or it reads `False` and a `False`
 * written into a Confirm target is stored as empty. The rows that would separate them, a Boolean target copying a
 * Confirm and a Confirm target copying a Boolean, are refused as `MVK_INVALID_COMPARE_TO_YES` (measured
 * 2026-10-20), because a computation is checked as `[target] == [source]` and a Confirm compares only with a
 * Confirm or `True`. So an empty Confirm copy is the one authorable way a Confirm target meets `False`, and the
 * law this class locks is stated at that level. The precondition row is the control that a condition still reads
 * an empty Confirm as `False`: `[Terms] != True` selects its alternative when `Terms` is empty.
 *
 * Each outcome is normalized to `value:<v>`, `error:<v>` or `empty`, because the kernel reports only changed
 * targets and the interpreter reports every target; no target is seeded, so a cleared target and an absent one
 * are the same.
 *
 * Own domain (an order with a terms acknowledgement and gift-wrapped items).
 */
class ConfirmComputationOperandDiffTest {

    private val model = run {
        val base = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order") { order -> order
                .confirmField("Terms") { }
                .confirmField("TermsCopy") { }
                .booleanField("TermsPending") { }
                .group("Items") { items -> items.repeatable(3)
                    .confirmField("GiftWrap") { }
                    .confirmField("GiftWrapCopy") { } } }
            .build().toJson()
        var json = RuntimeLaws.withComputation(base, "/Order", "CopyTerms", "../TermsCopy", "[Terms]")
        json = RuntimeLaws.withTieredComputation(json, "/Order", "MarkPending", "../TermsPending", null,
            listOf(RuntimeLaws.CompAlt("[Terms] != True", "True")))
        json = RuntimeLaws.withComputation(json, "/Order", "CopyGiftWrap", "../Items/GiftWrapCopy", "[Items/GiftWrap]")
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json), "A12 admits every computation")
        json
    }

    @Test
    fun anEmptyConfirmOperandLeavesItsTargetEmpty() {
        assertAgrees(
            stored = emptyMap(),
            rows = listOf(null, "true"),
            expected = mapOf(
                TERMS_COPY to "empty", TERMS_PENDING to "value:true",
                "$GIFT_WRAP_COPY@1" to "empty", "$GIFT_WRAP_COPY@2" to "value:true"),
        )
    }

    @Test
    fun aFilledConfirmOperandIsCopied() {
        assertAgrees(
            stored = mapOf(TERMS to "true"),
            rows = listOf("true"),
            expected = mapOf(
                TERMS_COPY to "value:true", TERMS_PENDING to "empty",
                "$GIFT_WRAP_COPY@1" to "value:true"),
        )
    }

    private fun assertAgrees(stored: Map<String, String>, rows: List<String?>, expected: Map<String, String>) {
        var kDoc = RuntimeLaws.doc().group(ROOT, 1)
        val def = ModelLoader.load(model)
        var iDoc = def.newDocument().group(ROOT, 1)
        stored.forEach { (path, value) ->
            kDoc = kDoc.field(path, value, 1, 1)
            iDoc = iDoc.field(path, value, 1, 1)
        }
        rows.forEachIndexed { i, giftWrap ->
            kDoc = kDoc.group(ITEMS, 1, i + 1)
            iDoc = iDoc.group(ITEMS, 1, i + 1)
            if (giftWrap != null) {
                kDoc = kDoc.field(GIFT_WRAP, giftWrap, 1, i + 1, 1)
                iDoc = iDoc.field(GIFT_WRAP, giftWrap, 1, i + 1, 1)
            }
        }
        val targets = expected.keys
        val dynamic = normalized(targets, RuntimeLaws.compute(model, kDoc))
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = normalized(targets, java.compute(java.instance(kDoc)))
        val interpreted = targets.associateWith { target ->
            DmInterpreter(def).compute(iDoc).firstOrNull { "${it.path}@${it.indices.getOrElse(1) { 0 }}" == target || it.path == target }
                ?.let { o ->
                    when (o.outcome) {
                        ComputeOutcome.VALUE -> "value:${o.value}"
                        ComputeOutcome.ERRORED -> "error:${o.value}"
                        else -> "empty"
                    }
                } ?: "empty"
        }
        assertEquals(expected, dynamic, "dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }

    private fun normalized(targets: Set<String>, computed: List<RuntimeLaws.Computed>): Map<String, String> =
        targets.associateWith { target ->
            computed.firstOrNull { "${it.path()}@${rowOf(it.pointer())}" == target || it.path() == target }
                ?.let { c ->
                    when {
                        c.errored() -> "error:${c.value().orElse(null)}"
                        !c.cleared() && c.value().isPresent -> "value:${c.value().get()}"
                        else -> "empty"
                    }
                } ?: "empty"
        }

    private companion object {
        const val ROOT = "/Order"
        const val ITEMS = "/Order/Items"
        const val TERMS = "/Order/Terms"
        const val TERMS_COPY = "/Order/TermsCopy"
        const val TERMS_PENDING = "/Order/TermsPending"
        const val GIFT_WRAP = "/Order/Items/GiftWrap"
        const val GIFT_WRAP_COPY = "/Order/Items/GiftWrapCopy"

        /** The repetition of an `Items` pointer such as `Order[1]/Items[2]/GiftWrapCopy`, or 0 at the root. */
        fun rowOf(pointer: Any?): Int =
            pointer?.let { Regex("""Items\[(\d+)]""").find(it.toString())?.groupValues?.get(1)?.toInt() } ?: 0
    }
}
