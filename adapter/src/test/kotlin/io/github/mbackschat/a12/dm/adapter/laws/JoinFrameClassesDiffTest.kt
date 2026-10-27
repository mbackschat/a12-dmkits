package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.signatures
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The two join frames a12-kernel-lean SPEC-2026-10-26-13 left open ([KERNEL-FINDINGS KF636]). A join under a
 * common repeatable frame binds both sides inside each frame row, so equal keys in different weeks never meet,
 * and the frame visits only the rows within its capacity, flagging the rest; a join whose sides sit under two
 * different repeatable frames is refused before it runs, by A12 and by the checker alike. The runtime rows are
 * three-engine agreed.
 *
 * Own domain (a shop's weekly orders and stock).
 */
class JoinFrameClassesDiffTest {

    private val base = DocumentModelDraft.named("shop-join-frames").locale("en_US")
        .rootGroup("Shop") { s -> s
            .group("Weeks") { w -> w.repeatable(2).stringField("Label") { }
                .group("Orders") { o -> o.repeatable(5).indexField("Sku").stringField("Sku") { }.numberField("Qty") { it.fractionDigits(0) } }
                .group("Stock") { o -> o.repeatable(5).indexField("Sku").stringField("Sku") { }.numberField("Have") { it.fractionDigits(0) } } }
            .group("Stores") { w -> w.repeatable(3)
                .group("Wants") { o -> o.repeatable(5).indexField("Sku").stringField("Sku") { }.numberField("Qty") { it.fractionDigits(0) } } }
            .group("Depots") { w -> w.repeatable(3)
                .group("Holds") { o -> o.repeatable(5).indexField("Sku").stringField("Sku") { }.numberField("Have") { it.fractionDigits(0) } } } }
        .build().toJson()

    private val common = RuntimeLaws.withRule(base, "/Shop/Weeks/Orders", "ShortRule", "../Qty", "Short",
        "FieldFilled(/Shop/Weeks/Orders/Qty) And FieldFilled(/Shop/Weeks/Stock/Have) And " +
            "[/Shop/Weeks/Orders/Qty] > [/Shop/Weeks/Stock/Have]")

    private val diverging = RuntimeLaws.withRule(base, "/Shop/Stores/Wants", "ShortRule", "../Qty", "Short",
        "FieldFilled(/Shop/Stores/Wants/Qty) And FieldFilled(/Shop/Depots/Holds/Have) And " +
            "[/Shop/Stores/Wants/Qty] > [/Shop/Depots/Holds/Have]")

    @Test
    fun aJoinAcrossTwoFramesIsRefused() {
        val models = mapOf("common" to common, "diverging" to diverging)
        val observed = models.mapValues { (_, json) -> RuntimeLaws.consistencyMessages(json).filter { it.startsWith("ERROR") } }
        assertEquals(models.mapValues { (_, json) -> MessageParameterProbes.kernel(json) },
            models.mapValues { (_, json) -> MessageParameterProbes.portable(json) }, "the checker's classes")
        assertEquals(mapOf(
            "common" to emptyList(),
            "diverging" to listOf("ERROR: Rule with path '/Shop/Stores/Wants/ShortRule': Missing specification of an " +
                "asterisk for '/Shop/Depots' in '/Shop/Depots/Holds/Have'. [MVK_NO_WILDCARD]"),
        ), observed)
    }

    private fun week(sku: String?, qty: Int?, stockSku: String?, have: Int?) =
        "{\"Label\":\"w\"" + (if (sku != null) ",\"Orders\":[{\"Sku\":\"$sku\",\"Qty\":$qty}]" else "") +
            (if (stockSku != null) ",\"Stock\":[{\"Sku\":\"$stockSku\",\"Have\":$have}]" else "") + "}"

    @Test
    fun aCommonFrameJoinsInsideEachOfItsRows() {
        val docs = linkedMapOf(
            "three weeks over a capacity of two, each short" to listOf(week("A", 5, "A", 1), week("A", 5, "A", 1), week("A", 5, "A", 1)),
            "equal keys in different weeks" to listOf(week("A", 5, null, null), week(null, null, "A", 1)),
            "a week holding only stock" to listOf(week(null, null, "A", 1), week("A", 5, "A", 1)),
        )
        val observed = docs.mapValues { (_, weeks) -> signatures(common, "{\"Shop\":{\"Weeks\":[${weeks.joinToString(",")}]}}") }
        val overCapacity = listOf("/Label", "/Orders", "/Orders[1]/Qty", "/Orders[1]/Sku", "/Stock", "/Stock[1]/Have", "/Stock[1]/Sku")
            .map { "zuGrosseKontextnummer|VALUE_ERROR|/Shop[1]/Weeks[3]$it" }
        assertEquals(linkedMapOf(
            "three weeks over a capacity of two, each short" to listOf(
                "Short|VALUE_ERROR|/Shop[1]/Weeks[1]/Orders[1]/Qty", "Short|VALUE_ERROR|/Shop[1]/Weeks[2]/Orders[1]/Qty") +
                overCapacity + "zuGrosseZeile|VALUE_ERROR|/Shop[1]/Weeks",
            "equal keys in different weeks" to emptyList(),
            "a week holding only stock" to listOf("Short|VALUE_ERROR|/Shop[1]/Weeks[2]/Orders[1]/Qty"),
        ), observed)
    }
}
