package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [KernelRuntimeProbe]'s `validatePart` observation — the kernel's message channel under a CALLER-SUPPLIED
 * relevant set, reached through a request file.
 *
 * **Why the probe grew it.** a12-kernel-lean has two rows blocked on exactly this and on nothing else: a
 * call-local PARTIAL coverage, and the empty (`noneRelevant`) coverage. Neither is expressible through
 * `validateFull`, because both are properties of the CALLER'S coverage rather than of the document — the same
 * document answers differently depending on what the caller declared relevant. Our adapter's published surface
 * still exposes `validateFull` alone; this observation reaches past it through the test harness, which is where
 * `IDocumentRtService.validatePart` already lived.
 *
 * **The empty relevant set is the trap the whole design turns on.** An absent `relevant` key and an empty one
 * are different requests: absent means "not observed", empty means "observe with no coverage". Collapsing them
 * would make the second unreachable, which is half of what was asked for. So `relevant` is an
 * `Optional<Set<…>>` end to end, the request refuses the key and the observation apart in both directions, and
 * `partialMessages` renders even when empty.
 */
class KernelProbeValidatePartTest {

    /**
     * The whole point, in one artifact: the same document answers differently per declared coverage. Full
     * validation is the invariant control — it cannot see the relevant set at all, so it must report the same
     * two findings on every row, and a change there would mean the coverage had leaked somewhere it must not.
     */
    @Test
    fun theSameDocumentAnswersDifferentlyPerDeclaredCoverage() {
        val expected = mapOf(
            "whole-order-relevant" to listOf("AMOUNT_REQUIRED", "REFERENCE_REQUIRED"),
            "reference-only-relevant" to listOf("REFERENCE_REQUIRED"),
            "none-relevant" to emptyList(),
        )

        assertEquals(expected, artifact.partialCodesByRow(),
            "validatePart gates on the caller's coverage, and the empty set gates everything out")
        assertEquals(
            expected.keys.associateWith { listOf("AMOUNT_REQUIRED", "REFERENCE_REQUIRED") },
            artifact.fullCodesByRow(),
            "validateFull cannot see the relevant set, so it answers the document identically on every row",
        )
    }

    /**
     * `noneRelevant` produces an EMPTY list and not an absent key — the distinction the `Optional` exists for.
     * Asserted on the rendered JSON rather than on the projection above, because it is a claim about the
     * artifact's shape: a consumer must be able to tell "observed, nothing fired" from "not observed".
     */
    @Test
    fun theNoneRelevantCoverageRendersAnEmptyArrayRatherThanOmittingTheKey() {
        val engines = artifact.row("none-relevant").path("engines")
        assertTrue(engines.size() > 0, "the row carries its engine blocks")
        engines.forEach { engine ->
            assertTrue(engine.has("partialMessages"),
                "the key is present on ${engine.path("engine").asText()} — observed, not omitted")
            assertTrue(engine.path("partialMessages").isEmpty,
                "…and empty, which is the measurement rather than an absence")
        }
    }

    /** Both strategies gate the relevant set identically here, so the split this probe watches for is absent. */
    @Test
    fun bothKernelStrategiesGateTheCoverageIdentically() {
        artifact.path("rows").forEach { row ->
            assertTrue(row.path("enginesAgree").asBoolean(),
                "row '${row.path("name").asText()}' — enginesAgree covers partialMessages too")
        }
    }

    /** A request that does not observe `validatePart` is byte-identical to one written before it existed. */
    @Test
    fun anUnaskedObservationLeavesTheArtifactBytesUnchanged() {
        val withoutKey = probe(
            request("plain", model, rows = """{ "name": "r", "document": "${documentFile.fileName}" }"""),
            "plain-artifact.json",
        )
        assertTrue(withoutKey.path("rows").isArray && withoutKey.path("rows").size() == 1, "the row was observed")
        withoutKey.path("rows").path(0).path("engines").forEach { engine ->
            assertTrue(!engine.has("partialMessages"), "absent when unobserved, so an existing SHA-256 holds")
        }
    }

    /**
     * The two directions of the request contract. Each is a silent no-op if unenforced: a relevant set nobody
     * reads, and a `validatePart` with no coverage that would quietly behave as `validateFull`.
     */
    @Test
    fun theRequestRefusesTheKeyAndTheObservationApartInBothDirections() {
        val coverage = """"relevant": [ { "path": "/Order" } ]"""
        val missingCoverage = assertThrows(IllegalArgumentException::class.java) {
            KernelRuntimeProbe.main(
                arrayOf(
                    request(
                        "no-coverage", model, observe = BOTH,
                        rows = """{ "name": "r", "document": "${documentFile.fileName}" }""",
                    ).toString(),
                    dir.resolve("unused-a.json").toString(),
                ),
            )
        }
        assertTrue("must carry 'relevant'" in missingCoverage.message.orEmpty(), missingCoverage.message)

        val unreadCoverage = assertThrows(IllegalArgumentException::class.java) {
            KernelRuntimeProbe.main(
                arrayOf(
                    request(
                        "unread-coverage", model,
                        rows = """{ "name": "r", "document": "${documentFile.fileName}", $coverage }""",
                    ).toString(),
                    dir.resolve("unused-b.json").toString(),
                ),
            )
        }
        assertTrue("does not name validatePart" in unreadCoverage.message.orEmpty(), unreadCoverage.message)
    }

    /**
     * …and the published schema refuses the same two mistakes. An agent reads the schema before it runs the
     * probe, so a schema that accepts what the producer rejects teaches the wrong shape — the same parity
     * `KernelProbeComputeShapeTest` holds for the `observe` rules.
     */
    @Test
    fun theSchemaRefusesTheSameTwoCoverageMistakes() {
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(MAPPER.readTree(
                javaClass.classLoader.getResource("kernel-probe/request.schema.json")!!.readText()))

        val missingCoverage = MAPPER.readTree(
            """{ "model": "m.dm.json", "observe": [$BOTH],
                 "rows": [ { "name": "r", "documentInline": {} } ] }""")
        assertTrue(schema.validate(missingCoverage).isNotEmpty(),
            "the schema must require 'relevant' on every row when validatePart is observed")

        val unreadCoverage = MAPPER.readTree(
            """{ "model": "m.dm.json",
                 "rows": [ { "name": "r", "documentInline": {},
                             "relevant": [ { "path": "/Order" } ] } ] }""")
        assertTrue(schema.validate(unreadCoverage).isNotEmpty(),
            "the schema must refuse a relevant set that no observation reads")

        val wellFormed = MAPPER.readTree(
            """{ "model": "m.dm.json", "observe": [$BOTH],
                 "rows": [ { "name": "r", "documentInline": {}, "relevant": [] } ] }""")
        assertTrue(schema.validate(wellFormed).isEmpty(),
            "…and it must ACCEPT the noneRelevant coverage, or the refusals above prove nothing")
    }

    /** Two runs over one request are byte-identical, so a consumer can pin a partial observation by SHA-256. */
    @Test
    fun twoRunsProduceByteIdenticalArtifacts() {
        artifact
        val repeat = dir.resolve("partial-repeat.json")
        KernelRuntimeProbe.main(arrayOf(partialRequest.toString(), repeat.toString()))

        assertEquals(
            Files.readString(dir.resolve("partial-artifact.json")),
            Files.readString(repeat),
            "the partial channel is sorted on the same total order as messages, so a repeat run cannot reorder",
        )
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun JsonNode.row(name: String): JsonNode =
        path("rows").first { it.path("name").asText() == name }

    private fun JsonNode.partialCodesByRow(): Map<String, List<String>> = codesByRow("partialMessages")

    private fun JsonNode.fullCodesByRow(): Map<String, List<String>> = codesByRow("messages")

    /** The first engine block's codes per row — [bothKernelStrategiesGateTheCoverageIdentically] pins that the
     *  strategies agree, so reading one of them here keeps these assertions about the coverage rather than
     *  about the split. */
    private fun JsonNode.codesByRow(channel: String): Map<String, List<String>> =
        path("rows").associate { row ->
            row.path("name").asText() to
                row.path("engines").path(0).path(channel).map { it.path("code").asText() }.sorted()
        }

    companion object {

        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        private const val BOTH = "\"validateFull\", \"validatePart\""

        /**
         * A parcel order with two independently required fields in different groups, so one coverage can admit
         * one finding and exclude the other. Both rules are plain presence rules: the point under test is the
         * caller's coverage, so anything subtler would confound it.
         */
        private val model: String by lazy {
            val json = DocumentModelDraft.named("probe-partial").locale("en_US")
                .rootGroup("Order") { order ->
                    order.field("Reference", FieldKind.STRING)
                        .group("Billing") { billing -> billing.numberField("Amount") { it.fractionDigits(2, 2) } }
                }.build().toJson()
            val base = BoundModel.ofJson(json)
            val reference = base.field("/Order/Reference")
            val amount = base.field("/Order/Billing/Amount")
            base.addAll(
                listOf(
                    base.rule(base.group("/Order")).name("ReferenceRequired").errorField(reference)
                        .`when`(Dsl.fieldNotFilled(reference))
                        .code("REFERENCE_REQUIRED").severity(Severity.ERROR).message(Dsl.en("x")).build(),
                    base.rule(base.group("/Order/Billing")).name("AmountRequired").errorField(amount)
                        .`when`(Dsl.fieldNotFilled(amount))
                        .code("AMOUNT_REQUIRED").severity(Severity.ERROR).message(Dsl.en("x")).build(),
                ),
                emptyList(),
            ).toJson()
        }

        /** An empty order, so both rules fire under full validation and the coverage is the only variable. */
        private val documentFile: Path by lazy { write("partial.document.json", """{ "Order": {} }""") }

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        private fun request(name: String, model: String, rows: String? = null, observe: String? = null): Path {
            val keys = buildList {
                add(""" "model": "${write("$name.model.json", model).fileName}" """)
                if (observe != null) add(""" "observe": [ $observe ] """)
                if (rows != null) add(""" "rows": [ $rows ] """)
            }
            return write("$name.request.json", keys.joinToString(",", "{", "}"))
        }

        /**
         * Three coverages over one document: the whole root group, one field, and nothing. The middle row is
         * the discriminator — a coverage that admits one rule and excludes the other, which neither of the
         * extremes can show on its own.
         */
        private val partialRequest: Path by lazy {
            val doc = documentFile.fileName
            request(
                "partial", model, observe = BOTH,
                rows = listOf(
                    """{ "name": "whole-order-relevant", "document": "$doc",
                         "relevant": [ { "path": "/Order" } ] }""",
                    """{ "name": "reference-only-relevant", "document": "$doc",
                         "relevant": [ { "path": "/Order/Reference" } ] }""",
                    """{ "name": "none-relevant", "document": "$doc", "relevant": [] }""",
                ).joinToString(","),
            )
        }

        private fun probe(request: Path, out: String): JsonNode {
            KernelRuntimeProbe.main(arrayOf(request.toString(), dir.resolve(out).toString()))
            return MAPPER.readTree(Files.readString(dir.resolve(out)))
        }

        private val artifact: JsonNode by lazy { probe(partialRequest, "partial-artifact.json") }
    }
}
