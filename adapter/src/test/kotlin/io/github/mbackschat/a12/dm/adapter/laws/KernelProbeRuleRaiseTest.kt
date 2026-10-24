package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A rule whose evaluation raises is reported by A12 as one VALUE message, and the probe publishes the exception
 * behind it with its origin (a12-kernel-lean's instrument request, 2026-10-24 note 02).
 *
 * The origin is decided by the recorder's identity record of what its own conditions threw. A request cannot make
 * a declared condition raise, since both behaviours are total, so the `kernel` origin is locked end to end and the
 * `probe` origin at the recorder, whose argument projection is the probe code a call can fail in. Own domain: an
 * insurance policy with keyed claims, their items, and payments keyed to the same claim numbers.
 */
class KernelProbeRuleRaiseTest {

    /**
     * A custom-condition rule targeting the items GROUP raises inside A12's call path, joined or not, before the
     * condition is called; the same condition targeting a field of that group is called and raises nothing.
     */
    @Test
    fun aRaiseInsideTheKernelsCallPathIsAttributedToTheKernel() {
        artifact.rowNamed("claimed").path("engines").forEach { engine ->
            val raises = engine.path("evaluationRaises")
            assertEquals(listOf("kernel", "kernel"), raises.map { it.path("origin").asText() }, engine.toString())
            raises.forEach { assertEquals("java.lang.IllegalArgumentException", it.path("chain")[0].path("type").asText()) }
            assertEquals(listOf("VALUE_ERROR", "VALUE_ERROR"),
                engine.path("messages").filter { it.path("code").asText() in setOf("JOINED", "UNJOINED") }
                    .map { it.path("type").asText() }, engine.toString())
            assertEquals(listOf("Policy/Claims/Items/ItemNo"),
                engine.path("customConditionCalls").map { it.path("errorEntity").asText() },
                "only the field target's call is made: $engine")
            assertEquals(1, engine.path("messages").count { it.path("code").asText() == "ITEM_NO" }, engine.toString())
        }
    }

    /** Absent means A12 caught none, which the capture's install check makes a measurement. */
    @Test
    fun aRowWhoseRulesAllEvaluatePublishesNoRaises() {
        artifact.rowNamed("empty").path("engines").forEach { engine ->
            assertFalse(engine.has("evaluationRaises"), engine.toString())
        }
    }

    @Test
    fun bothStrategiesAgree() {
        listOf("claimed", "empty").forEach { assertTrue(artifact.rowNamed(it).path("enginesAgree").asBoolean(), it) }
    }

    /**
     * A call that raises in the probe is recorded as failed rather than omitted, and the raise A12 wraps around it
     * is attributed to the probe; an unrelated raise in the same block stays the kernel's.
     */
    @Test
    fun aRaiseInsideTheProbesConditionIsAttributedToTheProbeAndRecordedAsAFailedCall() {
        val recorder = DeclarativeCustomConditions.Recorder(mapOf("Approved" to DeclarativeCustomConditions.Returns(true)))
        recorder.enter(KernelRuntimeProbe.Observation.VALIDATE_FULL)
        // A formally incorrect set that cannot be read, so projecting the arguments is what raises.
        val unreadable = object : AbstractSet<DocumentPointer>() {
            override val size = 1
            override fun iterator(): Iterator<DocumentPointer> = throw IllegalStateException("unreadable set")
        }
        val thrown = assertThrows(IllegalStateException::class.java) {
            recorder.conditions().getValue("Approved").check(DocumentV2.empty("probe-rule-raise"), null, unreadable,
                PartiallyKnownDocumentMultiPointer.of("Policy/Holder", listOf()))
        }
        recorder.caught(listOf(RuntimeException("wrapped by A12", thrown), IllegalArgumentException("A12's own")))

        assertEquals(listOf(KernelProbeArtifact.EvaluationRaise.Origin.PROBE, KernelProbeArtifact.EvaluationRaise.Origin.KERNEL),
            recorder.raises().map { it.origin() })
        val failed = recorder.calls().orElseThrow().single()
        assertEquals(Optional.empty<Boolean>(), failed.result())
        assertEquals(Optional.empty<KernelProbeArtifact.ConditionArguments>(), failed.arguments())
        assertEquals(IllegalStateException::class.java.name, failed.raised().orElseThrow()[0].type())

        val written = MAPPER.readTree(KernelProbeArtifact(header, listOf(KernelProbeArtifact.Row("r", listOf(
            KernelProbeArtifact.Engine.of("kernel-groovy-dynamic").customConditionCalls(recorder.calls())
                .evaluationRaises(recorder.raises()).build())))).toJson())
        assertEquals(emptyList<String>(), schema.validate(written).map { it.message })
        val engine = written.rowNamed("r").path("engines")[0]
        val call = engine.path("customConditionCalls")[0]
        assertTrue(call.path("result").isNull && call.path("raised").isArray && !call.has("errorEntity"), call.toString())
        assertEquals(listOf("probe", "kernel"), engine.path("evaluationRaises").map { it.path("origin").asText() })
    }

    @Test
    fun theArtifactSchemaAdmitsTheKernelRaises() {
        assertEquals(emptyList<String>(), schema.validate(artifact).map { it.message })
    }

    private fun JsonNode.rowNamed(name: String): JsonNode = path("rows").first { it.path("name").asText() == name }

    companion object {

        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        private val schema by lazy {
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(MAPPER.readTree(
                KernelProbeRuleRaiseTest::class.java.classLoader.getResource("kernel-probe/artifact.schema.json")!!.readText()))
        }

        private val header = KernelProbeArtifact.Header.builder()
            .dmtoolVersion("0.0.0").kernelBuiltAgainst("30.8.1").kernelRuntime("30.8.1")
            .sourceRevision("(unknown)").sourceState("UNKNOWN").model("m.json").locale("en_US")
            .build()

        private val model: String by lazy {
            val base = DocumentModelDraft.named("probe-rule-raise").locale("en_US")
                .rootGroup("Policy") { p -> p
                    .group("Claims") { c -> c.repeatable(9).indexField("ClaimNo")
                        .stringField("ClaimNo") { }
                        .group("Items") { i -> i.repeatable(5).stringField("ItemNo") { } } }
                    .group("Payments") { y -> y.repeatable(9).indexField("ClaimNo")
                        .stringField("ClaimNo") { }
                        .stringField("Ref") { } } }
                .build().toJson()
            val validator = RuleValidator()
            listOf(
                DraftRule("/Policy", "JoinedItems", "../Claims/Items",
                    "GroupFilled(Claims/Items) And FieldFilled(Payments/Ref) And CustomCondition Approved", "JOINED", "ERROR"),
                DraftRule("/Policy", "UnjoinedItems", "../Claims/Items",
                    "GroupFilled(Claims/Items) And CustomCondition Approved", "UNJOINED", "ERROR"),
                DraftRule("/Policy", "ItemNumbered", "../Claims/Items/ItemNo",
                    "FieldFilled(Claims/Items/ItemNo) And CustomCondition Approved", "ITEM_NO", "ERROR"),
            ).fold(base) { json, rule -> validator.inject(json, rule) }
        }

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        private val artifact: JsonNode by lazy {
            val rows = listOf(
                """{ "name": "claimed", "documentInline": { "Policy": {
                     "Claims": [ { "ClaimNo": "c1", "Items": [ { "ItemNo": "i1" } ] } ],
                     "Payments": [ { "ClaimNo": "c1", "Ref": "p1" } ] } } }""",
                """{ "name": "empty", "documentInline": { "Policy": {} } }""",
            ).joinToString(",")
            val request = write("rule-raise.request.json", """{ "model": "${write("rule-raise.model.json", model).fileName}",
                "customConditions": { "Approved": { "returns": true } }, "rows": [ $rows ] }""")
            val out = dir.resolve("rule-raise.json")
            KernelRuntimeProbe.main(arrayOf(request.toString(), out.toString()))
            MAPPER.readTree(Files.readString(out))
        }
    }
}
