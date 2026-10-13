package io.github.mbackschat.a12.dm.cli

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `model eval` reports a stored value's input gates as the kernel orders them (KERNEL-FINDINGS KF547): a raw
 * `noValueValidation` String is never checked, and any other value longer than 99,999 UTF-16 units is
 * `wertZuLang` before its charset or blanks are looked at. The kernel differential for every row is
 * `adapter.laws.CheckedInputOrderDiffTest`; this pins that dmtool's evaluation path carries the same gates.
 */
class CheckedInputOrderCliTest {
    private val mapper = ObjectMapper()

    private val model = DocumentModelDraft.named("orderinput").locale("en_US")
        .rootGroup("Order") { order ->
            order.stringField("Note") { it.lineBreaks() }
                .stringField("Memo") { it.noValueValidation().lineBreaks() }
        }
        .build().toJson()

    @Test
    fun aRawStringIsNeverCheckedAndAnOverlongValueIsTooLongFirst(@TempDir dir: Path) {
        val modelFile = dir.resolve("orderinput.dm.json").also { Files.writeString(it, model) }
        val overlong = " " + "a".repeat(99_999)
        val rows = linkedMapOf(
            "a raw String with blanks" to (mapOf("Memo" to " x ") to emptyList()),
            "a raw String above the limit" to (mapOf("Memo" to overlong) to emptyList()),
            "a String above the limit, led by a blank" to (mapOf("Note" to overlong) to listOf("wertZuLang")),
            "the same blank below the limit" to (mapOf("Note" to " x") to listOf("fuehrendesBlank")),
        )
        val observed = rows.mapValues { (label, row) ->
            val document = dir.resolve("${label.hashCode()}.json")
            Files.writeString(document, mapper.writeValueAsString(mapOf("Order" to row.first)))
            val result = CliTestSupport.run("-m", modelFile.toString(), "model", "eval", "--instance", document.toString())
            assertEquals(0, result.code(), result::err)
            mapper.readTree(result.out()).path("data").path("messages").map { it.path("code").asText() }
        }
        assertEquals(rows.mapValues { it.value.second }, observed)
    }
}
