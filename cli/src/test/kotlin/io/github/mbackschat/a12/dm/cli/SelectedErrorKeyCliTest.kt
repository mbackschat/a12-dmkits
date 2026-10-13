package io.github.mbackschat.a12.dm.cli

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `model eval` moves a selected error address only for a check-relevant key (KERNEL-FINDINGS KF549): a key longer
 * than its field's maximum names the unknown bay `-5` although its text equals a stored bay code, and a valid key
 * names its bay. The kernel differential is `adapter.laws.SelectedErrorReanchorEligibilityDiffTest`; this pins that
 * dmtool's evaluation path carries the same address.
 */
class SelectedErrorKeyCliTest {
    private val mapper = ObjectMapper()

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("fleetkey").locale("en_US")
            .rootGroup("Fleet") { f -> f
                .group("Depots") { d -> d.repeatable(2)
                    .stringField("Short") { it.maxLength(1) }
                    .stringField("Note") { }
                    .group("Bays") { b -> b.repeatable(4).indexField("BayCode")
                        .stringField("BayCode") { }
                        .numberField("Load") { it.fractionDigits(0) } } } }
            .build().toJson())
        val depot = session.group("/Fleet/Depots")
        val load = depot.num("Bays/Load")
        depot.addRule {
            it.name("ShortKey").code("ShortKey").errorField(depot.field("Bays/Load"))
                .`when`(depot.str("Note").filled().or(load.forIndex(depot.field("Short")).gt(0)))
                .message(Dsl.en("Check the bay.")).build()
        }
        session.toJson()
    }

    @Test
    fun anInvalidKeyAddressesTheUnknownBay(@TempDir dir: Path) {
        val modelFile = dir.resolve("fleetkey.dm.json").also { Files.writeString(it, model) }
        fun address(key: String): List<String> {
            val document = dir.resolve("$key.json")
            Files.writeString(document, """{"Fleet":{"Depots":[{"Short":"$key","Note":"n","Bays":""" +
                """[{"BayCode":"a","Load":"1"},{"BayCode":"$key","Load":"2"}]}]}}""")
            val result = CliTestSupport.run("-m", modelFile.toString(), "model", "eval", "--instance", document.toString())
            assertEquals(0, result.code(), result::err)
            return mapper.readTree(result.out()).path("data").path("messages")
                .filter { it.path("code").asText() == "ShortKey" }.map { it.path("field").asText() }
        }
        assertEquals(listOf("Fleet/Depots/Bays[2]/Load"), address("b"))
        assertEquals(listOf("/Fleet[1]/Depots[1]/Bays[-5]/Load"), address("bb"))
    }
}
