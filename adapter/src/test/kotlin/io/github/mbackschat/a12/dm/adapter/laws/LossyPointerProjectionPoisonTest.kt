package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The control for the harness's own collapse guard (`RuntimeLaws.toMsgs`, INTERPRETER-GAPS `IG145`).
 *
 * **Why this file exists at all.** The guard poisons `Msg.field()` wherever the house-form projection collapses
 * findings that `Msg.key()` tells apart, so a differential comparing the lossy string fails at its assertion
 * instead of passing on a wrong row. Once the two affected differentials moved to the key, *nothing left in the
 * estate read a poisoned field* — a mutant disabling the poisoning survived a full lane. A guard whose trigger
 * no live test exercises is a claim rather than a mechanism, so its control is written here rather than assumed
 * from the two tests that no longer need it.
 *
 * **The fixture is measured, not predicted.** `Lines` caps at 2 and the kernel draws one `zuGrosseZeile` per row
 * index ABOVE the cap up to the highest written — so writing row 4 alone already yields findings at rows 3 and
 * 4, and a "single over-limit row" fixture has to write row 3. Getting that wrong is what the first draft did.
 *
 * Own domain (retail ordering).
 */
class LossyPointerProjectionPoisonTest {

    private companion object {
        const val ORDER = "/Order"
        const val SKU = "/Order/Lines/Sku"

        /** The over-repetition ROW code — the one finding that attaches to a repeatable GROUP node. */
        const val ROW_CODE = "zuGrosseZeile"

        /** Its per-node sibling, which attaches to a FIELD and so never collapses. */
        const val NODE_CODE = "zuGrosseKontextnummer"
    }

    private val model = DocumentModelDraft.named("retail-order-rows").locale("en_US")
        .rootGroup("Order", Consumer { o ->
            o.stringField("Reference", Consumer { })
                .group("Lines", Consumer<GroupBuilder> { l ->
                    l.repeatable(2).stringField("Sku", Consumer { })
                })
        }).build().toJson()

    private fun doc(vararg rows: Int) = RuntimeLaws.doc().group(ORDER, 1).also { d ->
        d.field("/Order/Reference", "R-1", 1, 1)
        rows.forEach { row -> d.field(SKU, "S-$row", 1, row, 1) }
    }

    /**
     * Three over-limit rows collapse onto one house form, so the guard must poison it — and must leave the two
     * channels that are still trustworthy alone. Without the poisoning, a differential asserting
     * `zuGrosseZeile@/Order[1]/Lines` three times would pass however the rows were attributed.
     */
    @Test
    fun aCollapsingHouseFormIsPoisonedWhileTheKeyAndTheRawPointerStayIntact() {
        val rowFindings = RuntimeLaws.messages(model, doc(4, 5)).filter { it.code() == ROW_CODE }

        assertEquals(
            listOf("/Order[1]/Lines[3]", "/Order[1]/Lines[4]", "/Order[1]/Lines[5]"),
            rowFindings.map { it.key() }.sorted(),
            "the lossless key must tell the three rows apart — that is the whole point of it",
        )
        rowFindings.forEach { finding ->
            assertTrue(finding.field().startsWith("<LOSSY-IG145 "),
                "the collapsing house form must be poisoned, not silently compared: ${finding.field()}")
            assertTrue(finding.field().contains("compare Msg.key()"),
                "the poison must name the remedy: ${finding.field()}")
            assertTrue(finding.errorField().contains("/Order/Lines"),
                "the kernel's own pointer toString stays untouched — it is the key's independent oracle")
        }
    }

    /**
     * The control that keeps the poisoning from reading as "the harness poisons group findings". Writing row 3
     * puts exactly ONE row over the cap, so nothing collapses and the house form must come through verbatim —
     * and the per-node finding beside it, which attaches to a field, is untouched in both documents. A guard
     * that fired on every group finding would pass the test above and be useless.
     */
    @Test
    fun nothingIsPoisonedWhenTheHouseFormLosesNothing() {
        val messages = RuntimeLaws.messages(model, doc(3))

        assertEquals(
            listOf("/Order[1]/Lines" to "/Order[1]/Lines[3]"),
            messages.filter { it.code() == ROW_CODE }.map { it.field() to it.key() },
            "one finding on a path loses nothing, so the house form is left exactly as it was",
        )
        assertEquals(
            listOf("/Order[1]/Lines[3]/Sku" to "/Order[1]/Lines[3]/Sku[1]"),
            messages.filter { it.code() == NODE_CODE }.map { it.field() to it.key() },
            "a FIELD pointer never collapses — its terminal coordinate is the constant 1",
        )
    }
}
