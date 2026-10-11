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

class KernelEvalCanonicalIngressTest {
    @Test
    fun everyHarnessRouteUsesCanonicalNumberConversion(@TempDir dir: Path) {
        val base = DocumentModelDraft.named("invoice-json-ingress").locale("en_US")
            .rootGroup("Invoice") { group ->
                group.numberField("Amount") { it.fractionDigits(2, 2) }
                    .numberField("Copy") { it.fractionDigits(2, 2) }
            }.build().toJson()
        val edit = EditSession.read(base)
        val invoice = edit.group("/Invoice")
        val amount = invoice.num("Amount")
        val copy = invoice.num("Copy")
        invoice.addRule { it.name("OriginalFive").code("ORIGINAL").errorField(amount).`when`(amount.eq(5))
            .message(Dsl.en("Original is five.")).build() }
        invoice.addRule { it.name("CopyFive").code("COPY").errorField(copy).`when`(copy.eq(5))
            .message(Dsl.en("Copy is five.")).build() }
        invoice.addComputation(copy) { it.name("CopyAmount").calc(amount.value()).message(Dsl.en("Copy amount.")).build() }
        val model = dir.resolve("invoice.dm.json")
        val document = dir.resolve("invoice.json")
        val json = """{"Invoice":{"Amount":5}}"""
        Files.writeString(model, edit.toJson())
        Files.writeString(document, json)
        val common = arrayOf("-m", model.toString())
        val input = arrayOf("--instance", document.toString())
        val modes = listOf(
            arrayOf("model", "eval", "--no-computations") to listOf("ORIGINAL"),
            arrayOf("model", "eval") to listOf("COPY", "ORIGINAL"),
            arrayOf("model", "eval", "--rule", "/Invoice/OriginalFive", "--no-computations") to listOf("ORIGINAL"),
            arrayOf("model", "eval", "--group", "/Invoice", "--field", "/Invoice/Amount",
                "--condition", "[Amount] == 5", "--code", "CANDIDATE", "--no-computations") to listOf("CANDIDATE", "ORIGINAL"),
        )
        val mapper = ObjectMapper()
        for ((mode, expected) in modes) {
            val result = CliTestSupport.runKernel(*common, *mode, *input)
            assertEquals(0, result.code()) { result.out() + result.err() }
            val data = mapper.readTree(result.out()).path("data")
            assertEquals(expected, data.path("fired").map { it.asText() }.sorted(), mode.toList().toString())
        }
        for (flag in listOf(emptyArray(), arrayOf("--no-computations"))) {
            val result = CliTestSupport.runKernel(*common, "rule", "eval", "/Invoice/OriginalFive", *input, *flag)
            assertEquals(0, result.code()) { result.out() + result.err() }
            assertEquals("fired", mapper.readTree(result.out()).path("data").path("verdict").asText())
        }
        val computed = CliTestSupport.runKernel(*common, "model", "compute", *input)
        assertEquals(0, computed.code()) { computed.out() + computed.err() }
        assertEquals(listOf("5.00"), mapper.readTree(computed.out()).path("data").path("computed")
            .map { it.path("value").asText() })
        assertEquals(json, Files.readString(document))
    }
}
