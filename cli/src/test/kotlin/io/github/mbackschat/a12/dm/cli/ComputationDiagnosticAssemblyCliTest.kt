package io.github.mbackschat.a12.dm.cli

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ComputationDiagnosticAssemblyCliTest {
    @Test
    fun computeAndApplyBeforeEvalFailAfterTheIndependentCallbackWhileValidationStillReportsBoth(@TempDir dir: Path) {
        val base = DocumentModelDraft.named("parcel-cli-diagnostic-assembly").locale("en_US")
            .rootGroup("Parcel") { p ->
                p.customField("Audit") { it.type("AuditStamp") }.group("Tags") { tags ->
                    tags.repeatable(2).customField("Identifier") { it.type("ParcelIdentifier") }
                        .customField("Copy") { it.type("CopiedIdentifier") }.indexField("Identifier")
                }
            }.build().toJson()
        val bound = BoundModel.ofJson(base)
        val source = bound.custom("/Parcel/Tags/Identifier")
        val copy = bound.custom("/Parcel/Tags/Copy")
        val audit = bound.custom("/Parcel/Audit")
        val model = dir.resolve("parcel.dm.json")
        Files.writeString(model, EditSession.read(base)
            .addComputation { m -> m.computation(copy).name("CopyIdentifier").calc(source.asString())
                .message(Dsl.en("Review the copy.")).build() }
            .addComputation { m -> m.computation(audit).name("AuditParcels").calc(StringLiteral("checked"))
                .message(Dsl.en("Review the parcel audit.")).build() }.toJson())
        val document = dir.resolve("parcel.json")
        val input = """{"Parcel":{"Audit":"stale","Tags":[{"Identifier":"ABCD","Copy":"stale"},{"Identifier":"ABCD","Copy":"stale"}]}}"""
        Files.writeString(document, input)
        val trace = dir.resolve("callbacks.txt")
        val validators = Files.createDirectory(dir.resolve("validators"))
        for (name in listOf("ParcelIdentifier", "CopiedIdentifier", "AuditStamp")) {
            val answer = if (name == "ParcelIdentifier") "{valid:false,errorKey:'testIDNummer'}" else "true"
            Files.writeString(validators.resolve("$name.js"), """
                import { appendFileSync } from 'node:fs';
                export function validate(value) {
                  appendFileSync(${ObjectMapper().writeValueAsString(trace.toString())}, ${ObjectMapper().writeValueAsString("$name:")} + value + '\n');
                  return $answer;
                }
                """.trimIndent())
        }
        val rawCalls = mutableListOf<List<String>>()
        for (verb in listOf("compute", "eval")) {
            Files.writeString(trace, "")
            val result = CliTestSupport.run("-m", model.toString(), "model", verb, "--instance", document.toString(),
                "--custom-field-types-js", validators.toString(), "--strict-custom")
            assertEquals(2, result.code(), "$verb: ${result.out()}${result.err()}")
            assertEquals("", result.out(), "$verb must return no partial computation or evaluation payload")
            assertTrue(result.err().contains("Duplicate computation diagnostic"), "$verb: ${result.err()}")
            val calls = Files.readAllLines(trace)
            assertEquals(2, calls.count { it == "ParcelIdentifier:ABCD" }, "$verb raw source callback cardinality")
            assertEquals(1, calls.count { it == "AuditStamp:checked" }, "$verb completed calculation before assembly failure")
            assertEquals(0, calls.count { it.startsWith("CopiedIdentifier:") }, "$verb poisoned target callbacks")
            rawCalls += calls
            assertEquals(input, Files.readString(document), "$verb caller file is unchanged")
        }
        assertEquals(rawCalls[0], rawCalls[1], "compute and compute-before-eval retain callback order and multiplicity")

        val validation = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", document.toString(),
            "--custom-field-types-js", validators.toString(), "--strict-custom", "--no-computations")
        assertEquals(0, validation.code(), validation.err())
        val relevant = ObjectMapper().readTree(validation.out()).path("data").path("messages")
            .filter { it.path("code").asText() in setOf("testIDNummer", "uniqueIndex") }
        assertEquals(listOf("testIDNummer", "testIDNummer", "uniqueIndex", "uniqueIndex"), relevant.map { it.path("code").asText() }.sorted())
        assertEquals(listOf("Parcel/Tags/Identifier", "Parcel/Tags/Identifier", "Parcel/Tags[2]/Identifier", "Parcel/Tags[2]/Identifier"),
            relevant.map { it.path("field").asText() }.sorted(), "validation retains each generated diagnostic's full physical row")
        assertEquals(input, Files.readString(document), "validation caller file is unchanged")
    }
}
