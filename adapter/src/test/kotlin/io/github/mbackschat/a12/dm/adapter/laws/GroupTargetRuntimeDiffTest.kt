package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.testing.KernelMessageAddressProjector
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.ComputationOutcome
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.ValidationFinding
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A rule reporting on a group, and a parallel rule or computation whose error field sits one unkeyed level below
 * a joined side, at runtime: the interpreter fires, addresses, references and computes exactly as A12 does
 * (a12-kernel-lean SPEC-2026-10-23-02, SPEC-2026-10-23-03; KERNEL-FINDINGS KF601).
 *
 * The rows are the peer's runtime checkpoint re-measured here on [OrderK04Model]: group targets on a repeatable
 * level (`E1`, `E4`, `t1`–`t8`), on a fixed one below a repeatable parent (`E2`, `E3`) and on the root's own
 * children (`v3`–`v5`); field twins (`E5`, `E6`, `v1`, `v2`); the nested pair below the `Lines`/`Picks` join
 * (`C1`, `C2`, with `C0` the direct control); and the computations `k08` into the nested level and `k00` the direct
 * control. The documents carry the peer's shapes, an empty `{}` part row and a fixed `Ribbon` holding nothing
 * among them, so each is handed to both engines as Document JSON through each engine's own reader: the claim is
 * about row positions, and a placement builder cannot say what A12's reader makes of an empty row.
 *
 * The oracle is the Kernel twice: [KernelRuntimeProbe] proves both strategies agree, and dynamic Groovy's
 * messages are compared on the LOSSLESS pointer key, because a group pointer's terminal coordinate is a row and
 * the house form drops it (INTERPRETER-GAPS `IG145`). The group error targets are written as relative paths
 * until the typed `Rule` reports on a group (DEV-API-GAPS `DG151`).
 *
 * Own domain, shared with the peer's fixture: an order's keyed lines with their parts, keyed picks, keyed gifts
 * with a card and unkeyed wraps.
 */
class GroupTargetRuntimeDiffTest {

    @Test
    fun bothKernelStrategiesAgreeOnEveryDocument() {
        listOf(rulesArtifact, partialArtifact, computeArtifact).forEach { artifact ->
            artifact.path("rows").forEach { row ->
                assertTrue(row.path("enginesAgree").asBoolean(), "both strategies on ${row.path("name").asText()}")
            }
        }
    }

    /**
     * A12's own answers on the rows that separate the accounts, independent of the interpreter: the group's own
     * level fixed at its first row against iterating it, the blank root, and the nested rows of a join.
     */
    @Test
    fun a12FixesTheGroupsOwnLevelAndIteratesTheLevelBelowAJoin() {
        fun keys(document: String, code: String) = RuntimeLaws.jsonMessages(rulesModel, DOCUMENTS.getValue(document))
            .filter { it.code() == code }.map { it.key() }.sorted()
        // The line whose only Count is in its second part: its group's presence holds there, its Count read does not.
        assertEquals(listOf("/Order[1]/Lines[1]/Parts[1]", "/Order[1]/Lines[2]/Parts[1]"), keys("gaps", "E1"))
        assertEquals(listOf("/Order[1]/Lines[2]/Parts[1]"), keys("gaps", "t1"))
        // A root's own repeatable child reported on: once, at its first row, on a blank root too.
        assertEquals(listOf("/Order[1]/Gifts[1]"), keys("empty", "v5"))
        assertEquals(listOf("/Order[1]/Gifts[1]/Card[1]", "/Order[1]/Gifts[2]/Card[1]"), keys("empty", "E3"))
        // Below the join: the first part of line A, whose pick has no Ref, and the part of line C, which has no pick.
        assertEquals(listOf("/Order[1]/Lines[1]/Parts[1]/Count[1]", "/Order[1]/Lines[3]/Parts[1]/Count[1]"),
            keys("mixed", "C1"))
        assertTrue(RuntimeLaws.jsonMessages(rulesModel, MIXED).single {
            it.code() == "C1" && it.key() == "/Order[1]/Lines[3]/Parts[1]/Count[1]"
        }.referenced().contains("/Order[1]/Picks[-5]/Ref"))
    }

    @Test
    fun theInterpreterReportsAsA12OnEveryDocument() {
        val prepared = PreparedModel.loadExpandedJson(rulesModel)
        val interpreter = prepared.createInterpreter()
        val kernel = DOCUMENTS.mapValues { (_, json) ->
            RuntimeLaws.jsonMessages(rulesModel, json).map { m ->
                signature(m.code(), m.key(), m.type().name.removeSuffix("_ERROR"), m.referenced(), m.omissionResponsible())
            }.sorted()
        }
        val ours = DOCUMENTS.mapValues { (name, json) ->
            val report = interpreter.validateFull(prepared.documents.readDocument(json))
            assertEquals(emptyList<Any>(), report.unsupported, "interpreter support on $name")
            report.findings.map(::signature).sorted()
        }
        assertEquals(kernel, ours)
    }

    /**
     * Partial validation over the same rules, on both documents that hold the nested rows. A relevant field or group
     * starts the call where it sits; this compares the projected address, which is lossless here because every
     * group pointer this model produces names its first row.
     */
    @Test
    fun theInterpreterValidatesPartAsA12() {
        val prepared = PreparedModel.loadExpandedJson(rulesModel)
        val interpreter = prepared.createInterpreter()
        val kernel = partialArtifact.path("rows").associate { row ->
            row.path("name").asText() to row.path("engines").path(0).path("partialMessages").map {
                projected(it.path("code").asText(), it.path("field").asText(), it.path("type").asText().removeSuffix("_ERROR"),
                    it.path("referenced").map(JsonNode::asText))
            }.sorted()
        }
        assertTrue(kernel.values.any { it.isNotEmpty() }, "the probe's partial messages were read")
        val ours = PARTIAL_ROWS.associate { (name, document, path, reps) ->
            val report = interpreter.validatePart(prepared.documents.readDocument(DOCUMENTS.getValue(document)),
                listOf(prepared.model.pointer(path, reps)))
            assertEquals(emptyList<Any>(), report.unsupported, "interpreter support on $name")
            name to report.findings.map { finding ->
                projected(finding.code, KernelMessageAddressProjector.address(finding.errorPath), finding.type.name,
                    finding.referenced.map(KernelMessageAddressProjector::address))
            }.sorted()
        }
        assertEquals(kernel, ours)
    }

    @Test
    fun theInterpreterComputesAsA12OnEveryDocument() {
        val prepared = PreparedModel.loadExpandedJson(computeModel)
        val interpreter = prepared.createInterpreter()
        val kernel = computeArtifact.path("rows").associate { row ->
            row.path("name").asText() to row.path("engines").path(0).path("computations").path("outcomes").map {
                outcome(it.path("field").asText(), it.path("cleared").asBoolean(), it.path("value").textValue())
            }.sorted()
        }
        assertTrue(kernel.getValue("seeded").isNotEmpty(), "the probe's outcomes were read")
        val ours = COMPUTE_DOCUMENTS.mapValues { (name, json) ->
            val report = interpreter.compute(prepared.documents.readDocument(json))
            assertEquals(emptyList<Any>(), report.unsupported, "interpreter support on $name")
            // A12 reports what computation changes, so the comparison is against the source-relative actions.
            report.actions.map { result ->
                outcome(KernelMessageAddressProjector.address(
                    A12PartiallyKnownMultiPointer.of(result.pointer.path, result.pointer.coordinates)),
                    result.outcome == ComputationOutcome.CLEARED, result.value)
            }.sorted()
        }
        assertEquals(kernel, ours)
    }

    private companion object {
        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        private fun outcome(address: String, cleared: Boolean, value: String?) =
            "$address|" + if (cleared) "CLEARED" else "=$value"

        private fun projected(code: String, address: String, type: String, referenced: List<String>) =
            "$code@$address|$type|ref=${referenced.sorted()}"

        private fun signature(code: String, key: String, type: String, referenced: List<String>, fillToFix: List<String>) =
            "$code@$key|$type|ref=${referenced.sorted()}|fix=${fillToFix.sorted()}"

        private fun signature(finding: ValidationFinding): String {
            val render = { p: A12PartiallyKnownMultiPointer -> formatPointer(p.fullName, p.repetitionIndexes) }
            return signature(finding.code,
                pointerComparisonKey(finding.errorPath.fullName, finding.errorPath.repetitionIndexes),
                finding.type.name, finding.referenced.map(render), finding.fillToFix.map(render))
        }

        private val base = OrderK04Model.json()
        private val order = EditSession.read(base).group("/Order")
        private val lines = order.group("Lines")
        private val parts = order.group("Lines/Parts")
        private val ribbon = order.group("Wraps/Ribbon")
        private val card = order.group("Gifts/Card")
        private val wraps = order.group("Wraps")
        private val gifts = order.group("Gifts")
        private val count = order.num("Lines/Parts/Count")
        private val partNo = order.str("Lines/Parts/PartNo")
        private val ref = order.str("Picks/Ref")
        private val partNoOrRef = listOf(order.field("Lines/Parts/PartNo"), order.field("Picks/Ref"))
        private val codeOrRef = listOf(order.field("Lines/Code"), order.field("Picks/Ref"))
        private val sku = order.str("Lines/Sku")

        /** Rule name → (error entity below `/Order`, condition). */
        private val RULES: Map<String, Pair<String, Condition>> = mapOf(
            "E1" to ("Lines/Parts" to Dsl.groupFilled(parts)),
            "E2" to ("Wraps/Ribbon" to Dsl.groupFilled(ribbon)),
            "E3" to ("Gifts/Card" to Dsl.groupNotFilled(card)),
            "E4" to ("Lines/Parts" to sku.filled().and(Dsl.groupFilled(parts))),
            "E5" to ("Lines/Parts/Count" to Dsl.groupFilled(parts).and(count.filled())),
            "E6" to ("Wraps/Ribbon/Color" to Dsl.groupFilled(ribbon).and(order.str("Wraps/Ribbon/Color").filled())),
            "t1" to ("Lines/Parts" to count.filled().and(Dsl.groupFilled(parts))),
            "t2" to ("Lines/Parts" to partNo.notFilled().and(Dsl.groupFilled(parts))),
            "t4" to ("Lines/Parts" to Dsl.groupFilled(lines)),
            "t6" to ("Lines/Parts" to Dsl.groupFilled(lines).and(count.filled())),
            "t7" to ("Lines/Parts" to Dsl.sum(order.flatten("Lines/Parts*/Count")).gt(0).and(Dsl.groupFilled(parts))),
            "t8" to ("Lines/Parts" to Dsl.groupFilled(parts).and(ref.filled())),
            "C0" to ("Lines/Sku" to sku.filled().and(Dsl.noFieldFilled(codeOrRef))),
            "C1" to ("Lines/Parts/Count" to count.filled().and(Dsl.noFieldFilled(partNoOrRef))),
            "C2" to ("Lines/Parts/Count" to count.filled().and(Dsl.noFieldFilled(partNoOrRef)).and(count.ne(1))),
            "v1" to ("Gifts/Card/Text" to order.str("Gifts/Card/Text").notFilled()),
            "v2" to ("Gifts/Card/Text" to Dsl.groupNotFilled(card)),
            "v3" to ("Wraps" to Dsl.groupFilled(wraps).and(Dsl.groupFilled(ribbon))),
            "v4" to ("Wraps" to Dsl.groupFilled(wraps).and(order.str("Wraps/Style").filled())),
            "v5" to ("Gifts" to Dsl.groupNotFilled(gifts)),
        )

        private val rulesModel: String = RULES.entries.fold(base) { json, (name, rule) ->
            RuntimeLaws.withRule(json, "/Order", name, "../${rule.first}", name, RuntimeLaws.render("/Order", rule.second))
        }

        private val computeModel: String = listOf(
            order.computation(count) { c -> c.name("k08").commonPrecondition(Dsl.noFieldFilled(partNoOrRef))
                .calc(Dsl.number(1)).message(Dsl.en("x")).build() },
            order.computation(order.num("Lines/Price")) { c -> c.name("k00")
                .commonPrecondition(Dsl.noFieldFilled(codeOrRef)).calc(Dsl.number(1)).message(Dsl.en("x")).build() },
        ).fold(base) { json, computation -> ModelWriter.addComputation(json, computation) }

        private const val MIXED = """{"Order":{"Email":"e","Lines":[""" +
            """{"LineNo":"A","Sku":"s","Code":"c","Parts":[{"Count":"2"},{"Count":"3","PartNo":"p"},{}]},""" +
            """{"LineNo":"B","Sku":"s","Parts":[{"Count":"4"}]},{"LineNo":"C","Sku":"s","Parts":[{"Count":"1"}]}],""" +
            """"Picks":[{"LineNo":"A"},{"LineNo":"B","Ref":"r"},{"LineNo":"D","Ref":"r"}],""" +
            """"Gifts":[{"GiftNo":"g1","Card":{"Text":"t"}},{"GiftNo":"g2"}],""" +
            """"Wraps":[{"Style":"x","Ribbon":{"Color":"red"}},{"Style":"y"}]}}"""

        private val DOCUMENTS = mapOf(
            "mixed" to MIXED,
            "gaps" to """{"Order":{"Lines":[{"LineNo":"A","Parts":[{},{"Count":"5"}]},{"Sku":"s","Parts":[{"Count":"6"}]}],""" +
                """"Gifts":[{"GiftNo":"g1"}],"Wraps":[{"Ribbon":{}},{"Style":"z","Tag":{"Label":"l"}}]}}""",
            "empty" to """{"Order":{}}""",
            "lonely" to """{"Order":{"Email":"e"}}""",
            "ribbon2" to """{"Order":{"Wraps":[{"Style":"x"},{"Style":"y","Ribbon":{"Color":"c"}}]}}""",
            "style2" to """{"Order":{"Wraps":[{"Ribbon":{"Color":"c"}},{"Style":"y"}]}}""",
        )

        private val COMPUTE_DOCUMENTS = mapOf(
            "seeded" to """{"Order":{"Lines":[""" +
                """{"LineNo":"A","Code":"c","Parts":[{"Count":"9"},{"Count":"9","PartNo":"p"},{}]},""" +
                """{"LineNo":"B","Parts":[{"Count":"9"}]},{"LineNo":"C","Parts":[{"Count":"9"}]}],""" +
                """"Picks":[{"LineNo":"A"},{"LineNo":"B","Ref":"r"},{"LineNo":"D","Ref":"r"}]}}""",
            "mixed" to MIXED,
            "empty" to """{"Order":{}}""",
        )

        /** Name, document, and the one relevant pointer: a nested part, a partner pick, an unmatched line's part,
         *  and a part reported on as a group. */
        private data class PartialRow(val name: String, val document: String, val path: String, val reps: List<Int>)

        private val PARTIAL_ROWS = listOf("mixed", "gaps").flatMap { document ->
            listOf(
                PartialRow("$document:first-part", document, "/Order/Lines/Parts/Count", listOf(1, 1, 1, 1)),
                PartialRow("$document:second-pick", document, "/Order/Picks/Ref", listOf(1, 2, 1)),
                PartialRow("$document:third-line-part", document, "/Order/Lines/Parts/Count", listOf(1, 3, 1, 1)),
                PartialRow("$document:second-line-parts", document, "/Order/Lines/Parts", listOf(1, 2, 1)),
                PartialRow("$document:second-line", document, "/Order/Lines", listOf(1, 2)),
                PartialRow("$document:root", document, "/Order", listOf(1)),
            )
        }

        private fun probe(label: String, model: String, observe: String, documents: Map<String, String>,
                relevant: Map<String, String> = emptyMap()): JsonNode {
            val modelFile = Files.writeString(dir.resolve("$label.model.json"), model)
            val rows = documents.entries.joinToString(",") { (name, json) ->
                """{"name":"$name","documentInline":$json""" +
                    (relevant[name]?.let { ""","relevant":$it""" } ?: "") + "}"
            }
            val request = Files.writeString(dir.resolve("$label.request.json"),
                """{"model":"${modelFile.fileName}","observe":$observe,"rows":[$rows]}""")
            val out = dir.resolve("$label.artifact.json")
            KernelRuntimeProbe.main(arrayOf(request.toString(), out.toString()))
            return MAPPER.readTree(Files.readString(out))
        }

        private val rulesArtifact: JsonNode by lazy { probe("rules", rulesModel, """["validateFull"]""", DOCUMENTS) }
        private val partialArtifact: JsonNode by lazy {
            probe("partial", rulesModel, """["validateFull","validatePart"]""",
                PARTIAL_ROWS.associate { it.name to DOCUMENTS.getValue(it.document) },
                PARTIAL_ROWS.associate { it.name to """[{"path":"${it.path}","reps":${it.reps}}]""" })
        }
        private val computeArtifact: JsonNode by lazy {
            probe("compute", computeModel, """["validateFull","compute"]""", COMPUTE_DOCUMENTS)
        }
    }
}
