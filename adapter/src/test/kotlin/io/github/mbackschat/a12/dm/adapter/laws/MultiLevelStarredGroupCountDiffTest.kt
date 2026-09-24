package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Differential + tri-check — `NumberOfFilledGroups` over an operand that crosses TWO starred repeatable levels,
 * where the declared extent that decides the count's polarity could be either level's capacity or their product.
 *
 * `Batches` (2) holds `Boxes` (2), each box holding a fixed `Seal`. Every document seals boxes in one batch only,
 * so a count of 2 reaches the inner level's capacity while staying below the product; the four-seal document
 * reaches the product. Read as the one `count == n` rung that fires and its message type, as in
 * `AncestorStarredGroupCountDiffTest`.
 */
class MultiLevelStarredGroupCountDiffTest {

    private companion object {
        const val ITEM = "/Item"
        const val TITLE = "/Item/Title"
        const val BATCHES = "/Item/Batches"
        const val BOXES = "/Item/Batches/Boxes"
        const val CODE = "/Item/Batches/Boxes/Seal/Code"
        const val MSG = "MLGC"
        const val MAX_COUNT = 5
    }

    private val base = DocumentModelDraft.named("warehouse-nested-boxes").locale("en_US")
        .rootGroup("Item") { i ->
            i.field("Title", FieldKind.STRING)
                .group("Batches") { b ->
                    b.repeatable(2).field("Lot", FieldKind.STRING)
                        .group("Boxes") { x ->
                            x.repeatable(2).field("Tag", FieldKind.STRING)
                                .group("Seal") { it.field("Code", FieldKind.STRING) }
                        }
                }
        }.build().toJson()

    private fun sealed(b: Both, batch: Int, box: Int) =
        b.group(BATCHES, 1, batch).group(BOXES, 1, batch, box).field(CODE, "S$batch$box", 1, batch, box, 1, 1)

    private val documents: Map<String, (Both) -> Unit> = linkedMapOf(
        "one batch, both boxes sealed" to { b -> sealed(sealed(b.group(ITEM, 1).field(TITLE, "crate", 1, 1), 1, 1), 1, 2) },
        "two batches, one box each sealed" to { b ->
            sealed(sealed(b.group(ITEM, 1).field(TITLE, "crate", 1, 1), 1, 1), 2, 1)
        },
        "two batches, every box sealed" to { b ->
            listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2).fold(b.group(ITEM, 1).field(TITLE, "crate", 1, 1)) { acc, (x, y) ->
                sealed(acc, x, y)
            }
        },
    )

    @Test
    fun aSealBelowTwoStarredLevels() = assertAllEnginesRead(
        "$BATCHES*/Boxes*/Seal",
        listOf("2:O", "2:O", "4:V"),
    )

    @Test
    fun aBoxRowBelowAStarredBatch() = assertAllEnginesRead(
        "$BATCHES*/Boxes*",
        listOf("2:O", "2:O", "4:V"),
    )

    private fun assertAllEnginesRead(operands: String, expected: List<String>) {
        val model = (0..MAX_COUNT).fold(base) { json, n ->
            RuntimeLaws.withRule(json, ITEM, "R$n", "../Title", "$MSG$n",
                "FieldFilled($TITLE) And NumberOfFilledGroups($operands) == $n")
        }
        val definition = ModelLoader.load(model)
        val javaEngine = RuntimeLaws.kernelJavaEngine(model)
        val readings = documents.mapValues { (_, build) ->
            val dynamic = Both(RuntimeLaws.doc(), definition.newDocument()).also(build)
            val kernel = RuntimeLaws.messages(model, dynamic.kDoc)
            assertTrue(kernel.none { it.code().startsWith("MVK_") }) { "the kernel refused $operands: $kernel" }
            val staticDoc = Both(RuntimeLaws.doc(), definition.newDocument()).also(build).kDoc
            val outcome = DmInterpreter(definition).validate(dynamic.iDoc)
            Triple(
                reading(kernel.filter { it.code().startsWith(MSG) }.map { it.code() to (it.type().name == "VALUE_ERROR") }),
                reading(javaEngine.messagesModern(staticDoc, null, Locale.US).filter { it.code().startsWith(MSG) }
                    .map { it.code() to (it.type().name == "VALUE_ERROR") }),
                if (outcome.unsupported.isNotEmpty()) "unsupported"
                else reading(outcome.messages.filter { it.code.startsWith(MSG) }.map { it.code to (it.type == MsgType.VALUE) }),
            )
        }
        assertEquals(documents.keys.zip(expected).toMap(), readings.mapValues { it.value.first }, "$operands — dynamic Groovy")
        assertEquals(readings.mapValues { it.value.first }, readings.mapValues { it.value.second }, "$operands — static Java")
        assertEquals(readings.mapValues { it.value.first }, readings.mapValues { it.value.third }, "$operands — interpreter")
    }

    private fun reading(fired: List<Pair<String, Boolean>>): String =
        fired.map { (code, value) -> "${code.removePrefix(MSG)}:${if (value) "V" else "O"}" }.sorted().joinToString(",")
}
