package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A probe request can REGISTER declarative custom conditions, and the artifact then records every invocation the
 * kernel made of them (a12-kernel-lean's F1 instrument request, 2026-10-14).
 *
 * **Why the probe grew it.** A `CustomCondition` name resolves only through the per-call
 * `DocumentProcessingConfig.customConditionFactory`, so without a registration route no registered CustomCondition
 * rule is observable at all, and a peer calibrating its account of when the kernel calls one (its relevant set,
 * its formally incorrect set, its error entity) had no instrument. The behaviours are declarative so the artifact
 * stays reproducible from the request bytes alone: a constant, or true exactly when one A12 pointer holds a value.
 *
 * **What the invocation record is checked against is the message channel of the same row**: a recorded call that
 * returned true is a rule that fired, so a recorder that invented, dropped or misattributed calls would disagree
 * with messages the kernel produced independently of it.
 */
class KernelProbeCustomConditionTest {

    @Test
    fun everyCallThatReturnedTrueIsARuleThatFired() {
        listOf("noted", "unnoted", "malformed-payout").forEach { name ->
            artifact.rowNamed(name).path("engines").forEach { engine ->
                val fired = engine.path("messages").map { it.path("code").asText() }.toSet()
                val calls = engine.path("customConditionCalls").filter { it.path("observation").asText() == "validateFull" }
                assertFalse(calls.isEmpty(), "row '$name' must record calls, or this check is vacuous")
                calls.forEach { call ->
                    assertEquals(call.path("result").asBoolean(), CODES.getValue(call.path("name").asText()) in fired,
                        "row '$name' on ${engine.path("engine").asText()}: $call against $fired")
                }
            }
        }
    }

    @Test
    fun eachDeclaredBehaviourAnswersAsDeclared() {
        fun results(row: String, condition: String) = artifact.rowNamed(row).path("engines").path(0)
            .path("customConditionCalls").filter { it.path("name").asText() == condition }
            .map { it.path("result").asBoolean() }.toSet()

        assertEquals(setOf(true), results("noted", "AdjusterApproved"))
        assertEquals(setOf(false), results("noted", "FraudSuspected"))
        assertEquals(setOf(true), results("noted", "ItemReceipted"), "the note is filled")
        assertEquals(setOf(false), results("unnoted", "ItemReceipted"), "…and here it is not")
    }

    /** The error entity carries its row, and the formally incorrect set names a malformed cell. */
    @Test
    fun theCallRecordCarriesTheKernelsOwnPointers() {
        val calls = artifact.rowNamed("noted").path("engines").path(0).path("customConditionCalls")
        assertEquals(setOf("Claim/Items/Remark", "Claim/Items[2]/Remark"),
            calls.filter { it.path("name").asText() == "ItemReceipted" }.map { it.path("errorEntity").asText() }.toSet(),
            "one call per item row, each naming its own row")

        val malformed = artifact.rowNamed("malformed-payout").path("engines").path(0).path("customConditionCalls")
        assertTrue(malformed.any { call -> call.path("formallyIncorrect").any { it.asText() == "Claim/Payout" } },
            "a call on that document sees the malformed payout: $malformed")
        assertTrue(calls.all { it.path("formallyIncorrect").isEmpty }, "…and none on a clean document")
    }

    /** Full validation hands the kernel no relevant set; partial validation hands it the caller's. */
    @Test
    fun theRelevantSetIsNullUnderFullValidationAndTheCallersUnderPartial() {
        val calls = artifact.rowNamed("noted").path("engines").path(0).path("customConditionCalls")
        val full = calls.filter { it.path("observation").asText() == "validateFull" }
        val partial = calls.filter { it.path("observation").asText() == "validatePart" }
        assertFalse(full.isEmpty() || partial.isEmpty(), "both channels must record calls: $calls")
        assertTrue(full.all { it.path("relevant").isNull }, "validateFull passes no relevant set: $full")
        assertTrue(partial.all { it.path("relevant").isArray }, "validatePart passes the caller's: $partial")
    }

    @Test
    fun bothStrategiesAgreeOnTheCallsTheyMake() {
        listOf("noted", "unnoted", "malformed-payout").forEach { name ->
            assertTrue(artifact.rowNamed(name).path("enginesAgree").asBoolean(), "row '$name'")
        }
    }

    /** A declared condition no rule reaches records an empty list, which is a measurement and not an absence. */
    @Test
    fun aDeclaredConditionNoRuleReachesRecordsAnEmptyList() {
        val out = dir.resolve("unreached.json")
        KernelRuntimeProbe.main(arrayOf(request("unreached", plainModel,
            rows = """{ "name": "r", "documentInline": { "Claim": {} } }""",
            customConditions = """"AdjusterApproved": { "returns": true }""").toString(), out.toString()))
        MAPPER.readTree(Files.readString(out)).rowNamed("r").path("engines").forEach { engine ->
            assertTrue(engine.path("customConditionCalls").let { it.isArray && it.isEmpty },
                "${engine.path("engine").asText()}: $engine")
        }
    }

    /**
     * A name the request does not declare stays unregistered even beside one it does, so the missing lookup stays
     * observable. A12 raises the lookup failure itself and catches it, so no fault reaches the probe: it reports the
     * rule under the rule's own code, naming the missing condition and referencing no field, publishes the raise as
     * the kernel's, and still calls the declared condition.
     */
    @Test
    fun anUndeclaredNameStaysUnregisteredBesideADeclaredOne() {
        val out = dir.resolve("undeclared-name.json")
        KernelRuntimeProbe.main(arrayOf(request("undeclared-name", model,
            rows = """{ "name": "r", "documentInline": { "Claim": { "Note": "n" } } }""",
            customConditions = """"FraudSuspected": { "returns": false }""").toString(), out.toString()))
        MAPPER.readTree(Files.readString(out)).rowNamed("r").path("engines").forEach { engine ->
            assertFalse(engine.has("fault"), engine.toString())
            val missing = engine.path("messages").single { it.path("code").asText() == "ADJUSTER_APPROVED" }
            assertTrue("Custom condition 'AdjusterApproved'" in missing.path("message").asText(), missing.toString())
            assertTrue(missing.path("referenced").isEmpty, missing.toString())
            val raise = engine.path("evaluationRaises").single()
            assertEquals("kernel", raise.path("origin").asText(), raise.toString())
            assertEquals("com.mgmtp.a12.kernel.core.rt.a12internal.ValidatorException",
                raise.path("chain")[0].path("type").asText(), raise.toString())
            assertEquals(listOf("FraudSuspected"), engine.path("customConditionCalls").map { it.path("name").asText() },
                engine.toString())
        }
    }

    /** A request that declares none keeps its bytes: the key appears on no block. */
    @Test
    fun aRequestDeclaringNoConditionPublishesNoCallRecord() {
        val out = dir.resolve("undeclared.json")
        KernelRuntimeProbe.main(arrayOf(request("undeclared", plainModel,
            rows = """{ "name": "r", "documentInline": { "Claim": {} } }""").toString(), out.toString()))
        MAPPER.readTree(Files.readString(out)).rowNamed("r").path("engines").forEach { engine ->
            assertFalse(engine.has("customConditionCalls"), engine.toString())
        }
    }

    @Test
    fun aMalformedDeclarationIsRefusedByName() {
        mapOf(
            "" to "must be a non-empty object",
            """"Approved": { "always": true }""" to """unknown key "always"""",
            """"Approved": { "returns": "yes" }""" to "'returns' must be true or false",
            """"Approved": { "returns": true, "whenFilled": "Claim/Note" }""" to "exactly one of",
            """"Approved": {}""" to "exactly one of",
            """"Approved": { "whenFilled": "Claim/Items[0]/Cost" }""" to "is not an A12 pointer",
            """"Approved": { "whenFilled": "Claim/Items[0]" }""" to "names one cell",
        ).entries.forEachIndexed { i, (declaration, expected) ->
            val refused = assertThrows(IllegalArgumentException::class.java) {
                KernelRuntimeProbe.main(arrayOf(request("refused-$i", plainModel,
                    rows = """{ "name": "r", "documentInline": { "Claim": {} } }""",
                    customConditions = declaration).toString(), dir.resolve("unused-$i.json").toString()))
            }
            assertTrue(expected in refused.message.orEmpty(), "$declaration: ${refused.message}")
        }
    }

    /** The published schema refuses the same mistakes and demonstrates the key it advertises. */
    @Test
    fun theSchemaAgreesWithTheProducer() {
        val schemaNode = MAPPER.readTree(javaClass.classLoader.getResource("kernel-probe/request.schema.json")!!.readText())
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode)
        fun invalidities(declared: String) = schema.validate(MAPPER.readTree(
            """{ "model": "m.dm.json", "customConditions": $declared, "rows": [ { "name": "r", "documentInline": {} } ] }"""))

        assertTrue(invalidities("""{ "A": { "returns": true }, "B": { "whenFilled": "Claim/Note" } }""").isEmpty(),
            "a well-formed declaration must be ACCEPTED, or the refusals below prove nothing")
        listOf("{}", """{ "A": {} }""", """{ "A": { "always": true } }""", """{ "A": { "returns": "yes" } }""",
            """{ "A": { "returns": true, "whenFilled": "Claim/Note" } }""").forEach {
            assertTrue(invalidities(it).isNotEmpty(), "the schema must refuse $it")
        }
        assertTrue(schemaNode.path("examples").any { it.has("customConditions") }, "the key needs a worked example")

        val artifactSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
            MAPPER.readTree(javaClass.classLoader.getResource("kernel-probe/artifact.schema.json")!!.readText()))
        assertEquals(emptyList<String>(), artifactSchema.validate(artifact).map { it.message },
            "the call-carrying artifact must satisfy the published artifact schema")
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun JsonNode.rowNamed(name: String): JsonNode = path("rows").first { it.path("name").asText() == name }

    companion object {

        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        /** Each condition's rule code, so a recorded call can be matched to the message its rule raises. */
        private val CODES = mapOf(
            "AdjusterApproved" to "ADJUSTER_APPROVED",
            "FraudSuspected" to "FRAUD_SUSPECTED",
            "ItemReceipted" to "ITEM_RECEIPTED",
        )

        private const val DECLARED = """"AdjusterApproved": { "returns": true },
            "FraudSuspected": { "returns": false },
            "ItemReceipted": { "whenFilled": "Claim/Note" }"""

        /**
         * An insurance claim: a note, a payout and repeatable claimed items, each rule guarded by one condition.
         * A12 refuses a rule whose condition does not reference its error field, so each rule reads
         * `FieldFilled(<target>) Or CustomCondition <Name>` over a target no document fills: the rule then fires
         * exactly when the condition answers true, which is what lets the message channel check the call record.
         * The item rule also needs a per-row guard, which every item row in [artifact] satisfies.
         */
        private val model: String by lazy {
            val json = DocumentModelDraft.named("probe-custom-condition").locale("en_US")
                .rootGroup("Claim") { claim ->
                    claim.stringField("Note") { }
                        .stringField("Flag") { }
                        .numberField("Payout") { it.fractionDigits(2, 2) }
                        .group("Items") { items -> items.repeatable(5)
                            .numberField("Cost") { it.fractionDigits(2, 2) }
                            .stringField("Remark") { } }
                }.build().toJson()
            val base = BoundModel.ofJson(json)
            fun rule(group: String, target: String, condition: String, guard: Condition? = null) =
                base.field(target).let { field ->
                    val guarded = field.filled().or(Dsl.customCondition(condition))
                    base.rule(base.group(group)).name("${condition}Rule").errorField(field)
                        .`when`(guard?.and(guarded) ?: guarded).code(CODES.getValue(condition))
                        .severity(Severity.ERROR).message(Dsl.en("Review the claim.")).build()
                }
            base.addAll(listOf(
                rule("/Claim", "/Claim/Flag", "AdjusterApproved"),
                rule("/Claim", "/Claim/Flag", "FraudSuspected"),
                // A row iteration must not fire on an unfilled row; every item row here fills its cost.
                rule("/Claim/Items", "/Claim/Items/Remark", "ItemReceipted", base.field("/Claim/Items/Cost").filled()),
            ), emptyList()).toJson()
        }

        /** The same claim with no CustomCondition rule, so a declared condition is reached by nothing. */
        private val plainModel: String by lazy {
            DocumentModelDraft.named("probe-custom-condition-plain").locale("en_US")
                .rootGroup("Claim") { claim -> claim.stringField("Note") { } }.build().toJson()
        }

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        private fun request(name: String, model: String, rows: String, customConditions: String? = null,
                observe: String? = null): Path {
            val keys = buildList {
                add(""" "model": "${write("$name.model.json", model).fileName}" """)
                if (customConditions != null) add(""" "customConditions": { $customConditions } """)
                if (observe != null) add(""" "observe": [ $observe ] """)
                add(""" "rows": [ $rows ] """)
            }
            return write("$name.request.json", keys.joinToString(",", "{", "}"))
        }

        private val artifact: JsonNode by lazy {
            val items = """ "Items": [ { "Cost": "10.00" }, { "Cost": "20.00" } ] """
            val coverage = """ "relevant": [ { "path": "/Claim" } ] """
            val rows = listOf(
                """{ "name": "noted", "documentInline": { "Claim": { "Note": "water damage", $items } }, $coverage }""",
                """{ "name": "unnoted", "documentInline": { "Claim": { $items } }, $coverage }""",
                """{ "name": "malformed-payout", "documentInline": { "Claim": { "Note": "n", "Payout": "lots" } }, $coverage }""",
            ).joinToString(",")
            val out = dir.resolve("declared.json")
            KernelRuntimeProbe.main(arrayOf(
                request("declared", model, rows, DECLARED, observe = "\"validateFull\", \"validatePart\"").toString(),
                out.toString()))
            MAPPER.readTree(Files.readString(out))
        }
    }
}
