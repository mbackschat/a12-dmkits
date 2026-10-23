package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.ErrorEntity
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The carriers next to [GroupTargetRuntimeDiffTest]'s rows where the interpreter's group-target and nested-join
 * iteration could answer without a measurement: a keyed group target that is itself a join side, a level below a
 * group target, an error field shallower than the nested level, group reads below a joined side, a comparison over
 * the nested level beside an unmatched partner, phantom and duplicate-key rows, and a partial call relevant to a
 * nested row (KERNEL-FINDINGS KF603). Each rule is first asked of `rule check`; on the admitted ones the interpreter
 * must report as A12 does, or decline the rule on every document alike, never answer differently.
 *
 * Own domain, shared with the peer's fixture: [OrderK04Model], with gift tags keyed like the gifts.
 */
class GroupTargetCarrierDiffTest {

    @Test
    fun a12AdmitsTheCarriersAsPinned() {
        assertEquals(ADMISSION, RULES.mapValues { (name, rule) ->
            RuntimeLaws.consistencyMessages(model(mapOf(name to rule))).mapNotNull { CODE.find(it)?.value }.distinct()
        })
    }

    @Test
    fun bothKernelStrategiesAgreeOnEveryDocument() {
        listOf(rulesArtifact to DOCUMENTS.size, partialArtifact to PARTIAL_ROWS.size,
            computeArtifact to COMPUTE_DOCUMENTS.size).forEach { (artifact, rows) ->
            assertEquals(rows, artifact.path("rows").size(), "every requested row was probed")
            artifact.path("rows").forEach { row ->
                val name = row.path("name").asText()
                assertEquals(2, row.path("engines").size(), "both strategies ran on $name")
                assertTrue(row.path("enginesAgree").asBoolean(), "both strategies on $name")
            }
        }
    }

    /** The interpreter declines the same rules on every document, and reports the rest as A12 does. */
    @Test
    fun theInterpreterReportsAsA12OrDeclinesTheRule() {
        val prepared = PreparedModel.loadExpandedJson(admittedModel)
        val interpreter = prepared.createInterpreter()
        val declined = DOCUMENTS.mapValues { (_, json) ->
            interpreter.validateFull(prepared.documents.readDocument(json)).unsupported
                .map { it.path.substringAfterLast('/') }.toSet()
        }
        assertEquals(DOCUMENTS.mapValues { DECLINED }, declined, "declined alike on every document")
        val kernel = DOCUMENTS.mapValues { (_, json) ->
            RuntimeLaws.jsonMessages(admittedModel, json).filter { it.code() !in DECLINED }
                .map(RuntimeProbeComparison::signature).sorted()
        }
        assertTrue(kernel.values.any { it.isNotEmpty() }, "A12 fired on some document")
        val ours = DOCUMENTS.mapValues { (_, json) ->
            interpreter.validateFull(prepared.documents.readDocument(json)).findings
                .map(RuntimeProbeComparison::signature).sorted()
        }
        assertEquals(kernel, ours)
    }

    /**
     * Why the interpreter declines `r2` (INTERPRETER-GAP-DISPOSITIONS IG180): reporting on the keyed slots, the rule
     * fires on no document, `slots2` included, where line A holds slots and its pick a `Ref`, so A12 does not join it
     * at the line as the plan for a group target would; nor where a slot's key names a pick that has a `Ref`
     * (`slotkeys`). Which rows it does join is unmeasured.
     */
    @Test
    fun aKeyedGroupTargetIsNotJoinedAtItsLine() {
        assertEquals(DOCUMENTS.mapValues { emptyList<String>() }, DOCUMENTS.mapValues { (_, json) ->
            RuntimeLaws.jsonMessages(admittedModel, json).filter { it.code() == "r2" }.map { it.key() }
        })
    }

    /**
     * Which nested rows a parallel rule visits: on parts declared five times, every declared part below each
     * joined line, an unmatched one included; on the same model with six, only the first part and the stored ones.
     * Where the line is joined its capacity takes no part in the product that decides (KERNEL-FINDINGS KF603).
     */
    @Test
    fun theNestedLevelIsDenseOnlyWhileItsCapacityIsAtMostFive() {
        fun parts(model: String, document: String) = RuntimeLaws.jsonMessages(model, DOCUMENTS.getValue(document))
            .filter { it.code() == "r5" }.map { it.key() }.sorted()
        assertEquals((1..5).map { "/Order[1]/Lines[1]/Parts[$it]/Count[1]" }, parts(admittedModel, "phantom"))
        assertEquals(listOf("/Order[1]/Lines[1]/Parts[1]/Count[1]"), parts(sixPartsModel, "phantom"))
        assertEquals(listOf("/Order[1]/Lines[1]/Parts[1]/Count[1]", "/Order[1]/Lines[1]/Parts[2]/Count[1]"),
            parts(sixPartsModel, "slots2"))
        // Occupancy is the document's: line A holds one part, yet visits the second that line B holds.
        assertEquals(listOf("/Order[1]/Lines[1]/Parts[1]/Count[1]", "/Order[1]/Lines[1]/Parts[2]/Count[1]"),
            parts(sixPartsModel, "widerlater"))
        val prepared = PreparedModel.loadExpandedJson(sixPartsModel)
        val interpreter = prepared.createInterpreter()
        DOCUMENTS.forEach { (name, json) ->
            assertEquals(RuntimeLaws.jsonMessages(sixPartsModel, json).map(RuntimeProbeComparison::signature).sorted(),
                interpreter.validateFull(prepared.documents.readDocument(json)).findings
                    .map(RuntimeProbeComparison::signature).sorted(), "six parts, $name")
        }
    }

    @Test
    fun theInterpreterValidatesPartAsA12() {
        val prepared = PreparedModel.loadExpandedJson(admittedModel)
        val interpreter = prepared.createInterpreter()
        val kernel = partialArtifact.path("rows").associate { row ->
            row.path("name").asText() to row.path("engines").path(0).path("partialMessages")
                .filter { it.path("code").asText() !in DECLINED }.map(RuntimeProbeComparison::projected).sorted()
        }
        val ours = PARTIAL_ROWS.associate { (name, document, path, reps) ->
            val report = interpreter.validatePart(prepared.documents.readDocument(DOCUMENTS.getValue(document)),
                listOf(prepared.model.pointer(path, reps)))
            name to report.findings.map(RuntimeProbeComparison::projected).sorted()
        }
        assertEquals(kernel, ours)
    }

    /** `k08` on the duplicate-key and joined documents: the interpreter computes as A12 does. */
    @Test
    fun theInterpreterComputesAsA12() {
        val prepared = PreparedModel.loadExpandedJson(computeModel)
        val interpreter = prepared.createInterpreter()
        val kernel = computeArtifact.path("rows").associate { row ->
            row.path("name").asText() to row.path("engines").path(0).path("computations").path("outcomes")
                .map(RuntimeProbeComparison::outcome).sorted()
        }
        assertTrue(kernel.values.any { it.isNotEmpty() }, "the probe's outcomes were read")
        val ours = COMPUTE_DOCUMENTS.mapValues { (name, json) ->
            val report = interpreter.compute(prepared.documents.readDocument(json))
            assertEquals(emptyList<Any>(), report.unsupported, "interpreter support on $name")
            report.actions.map(RuntimeProbeComparison::outcome).sorted()
        }
        assertEquals(kernel, ours)
    }

    /**
     * A computation reading a group at the nested level, `kg`, is admitted by A12 and declined by the interpreter on
     * every document, one whose precondition fails before it reaches that read included: a decline the values can
     * hide would be a guess on the others.
     */
    @Test
    fun aComputationReadingTheNestedGroupIsDeclinedOnEveryDocument() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(nestedGroupReadModel))
        val prepared = PreparedModel.loadExpandedJson(nestedGroupReadModel)
        val interpreter = prepared.createInterpreter()
        // A computation's decline stops the pass, so it carries the pass's name rather than the computation's.
        assertEquals(COMPUTE_DOCUMENTS.mapValues { listOf("<compute>") }, COMPUTE_DOCUMENTS.mapValues { (_, json) ->
            interpreter.compute(prepared.documents.readDocument(json)).unsupported.map { it.path }
        })
    }

    private companion object {
        @JvmStatic
        @TempDir
        lateinit var dir: Path

        private val CODE = Regex("MVK_[A-Z0-9_]+")

        private val base = OrderK04Model.json(withGiftTags = true)
        private val order = EditSession.read(base).group("/Order")
        private val lines = order.group("Lines")
        private val parts = order.group("Lines/Parts")
        private val slots = order.group("Lines/Slots")
        private val gifts = order.group("Gifts")
        private val card = order.group("Gifts/Card")
        private val count = order.num("Lines/Parts/Count")
        private val ref = order.str("Picks/Ref")
        private val sku = order.str("Lines/Sku")
        private val note = order.str("Gifts/Note")
        private val label = order.str("Tags/Label")
        private val style = order.str("Wraps/Style")
        private val partNoOrRef = listOf(order.field("Lines/Parts/PartNo"), order.field("Picks/Ref"))
        private val countOrRef = listOf(order.field("Lines/Parts/Count"), order.field("Picks/Ref"))

        /** A rule at `/Order` named and coded alike. */
        private fun rule(name: String, entity: ErrorEntity, condition: Condition): Rule =
            order.rule { it.name(name).errorEntity(entity).`when`(condition).code(name).message(Dsl.en(name)).build() }

        /** Rule name → the rule. */
        private val RULES: Map<String, Rule> = listOf(
            rule("r1", lines.ref(), Dsl.groupFilled(lines).and(ref.filled())),
            rule("r2", slots.ref(), Dsl.groupFilled(slots).and(ref.filled())),
            rule("r3", gifts.ref(), Dsl.groupFilled(gifts).and(style.filled())),
            rule("r4", lines.ref(), Dsl.groupFilled(lines).and(count.filled())),
            rule("r5", count.field(), count.lt(5).and(ref.filled())),
            rule("r6", count.field(), Dsl.numberOfFilledFields(countOrRef).gt(0)),
            rule("r7", count.field(), Dsl.atLeastOneFieldFilled(countOrRef)),
            rule("r8", sku.field(), count.filled().and(ref.filled())),
            rule("r9", ref.field(), count.filled().and(ref.filled())),
            rule("r12", sku.field(), sku.filled().and(Dsl.numberOfFilledGroups(parts).gt(0)).and(ref.filled())),
            rule("r13", ref.field(), ref.filled().and(Dsl.groupFilled(parts))),
            rule("r14", note.field(), note.filled().and(Dsl.groupFilled(card)).and(label.filled())),
            rule("r15", note.field(), note.filled().and(Dsl.groupNotFilled(card)).and(label.filled())),
            rule("C1", count.field(), count.filled().and(Dsl.noFieldFilled(partNoOrRef))),
        ).associateBy { it.name() }

        /** Rule name → A12's rejection classes, empty when admitted. */
        private val ADMISSION: Map<String, List<String>> = mapOf(
            "r1" to listOf("MVK_ERROR_FIELD_NO_INDEX"), "r2" to emptyList(), "r3" to listOf("MVK_NO_WILDCARD"),
            "r4" to listOf("MVK_NO_WILDCARD"), "r5" to emptyList(), "r6" to listOf("MVK_NEG_CONDITION_IN_ITERATION"),
            "r7" to listOf("MVK_NEG_CONDITION_IN_ITERATION"), "r8" to listOf("MVK_NO_WILDCARD"),
            "r9" to listOf("MVK_NO_WILDCARD"), "r12" to emptyList(), "r13" to listOf("MVK_NO_WILDCARD"),
            "r14" to emptyList(), "r15" to emptyList(), "C1" to emptyList(),
        )

        /** The admitted rules the interpreter declines. */
        private val DECLINED = setOf("r2")

        private fun model(rules: Map<String, Rule>) = rules.values.fold(base, ModelWriter::addRule)

        private val admittedModel: String = model(RULES.filterKeys { ADMISSION.getValue(it).isEmpty() })

        /** The comparison over the nested level alone, on parts declared six times. */
        private val sixPartsModel: String =
            ModelWriter.addRule(OrderK04Model.json(withGiftTags = true, partCapacity = 6), RULES.getValue("r5"))

        private val computeModel: String = ModelWriter.addComputation(base, order.computation(count) { c -> c.name("k08")
            .commonPrecondition(Dsl.noFieldFilled(partNoOrRef)).calc(Dsl.number(1)).message(Dsl.en("x")).build() })

        private val nestedGroupReadModel: String = ModelWriter.addComputation(base, order.computation(count) { c ->
            c.name("kg").commonPrecondition(Dsl.noFieldFilled(partNoOrRef).and(Dsl.groupFilled(parts)))
                .calc(Dsl.number(1)).message(Dsl.en("x")).build() })

        private const val JOIN = """{"Order":{"Lines":[""" +
            """{"LineNo":"A","Sku":"s","Code":"c","Parts":[{"Count":"2"},{"Count":"7","PartNo":"p"}],""" +
            """"Slots":[{"SlotNo":"1","Mark":"m"}]},""" +
            """{"LineNo":"B","Sku":"s","Parts":[{"Count":"4"}]},{"LineNo":"C","Parts":[{"Count":"1"}]}],""" +
            """"Picks":[{"LineNo":"A"},{"LineNo":"B","Ref":"r"},{"LineNo":"D","Ref":"r"}],""" +
            """"Gifts":[{"GiftNo":"g1","Note":"n","Card":{"Text":"t"}},{"GiftNo":"g2","Note":"n"}],""" +
            """"Tags":[{"GiftNo":"g1","Label":"l"},{"GiftNo":"g3","Label":"l"}],""" +
            """"Wraps":[{"Style":"x"},{"Style":"y"}]}}"""

        private val DOCUMENTS = mapOf(
            "join" to JOIN,
            // A matched line with no part: the nested level's first row exists only by declaration.
            "phantom" to """{"Order":{"Lines":[{"LineNo":"A"}],"Picks":[{"LineNo":"A","Ref":"r"}]}}""",
            // A line holding two slots and one part.
            "slots2" to """{"Order":{"Lines":[{"LineNo":"A","Parts":[{"Count":"2"}],""" +
                """"Slots":[{"SlotNo":"1"},{"SlotNo":"2"}]}],"Picks":[{"LineNo":"A","Ref":"r"}]}}""",
            // A slot keyed like a pick: under line A, slot B against picks A and B.
            "slotkeys" to """{"Order":{"Lines":[{"LineNo":"A","Slots":[{"SlotNo":"B"}]}],""" +
                """"Picks":[{"LineNo":"A"},{"LineNo":"B","Ref":"r"}]}}""",
            // The first line holds one part and the second two, while only the first line's pick has a Ref.
            "widerlater" to """{"Order":{"Lines":[{"LineNo":"A","Parts":[{"Count":"2"}]},""" +
                """{"LineNo":"B","Parts":[{"Count":"3"},{"Count":"4"}]}],"Picks":[{"LineNo":"A","Ref":"r"},{"LineNo":"B"}]}}""",
            "dupkey" to """{"Order":{"Lines":[{"LineNo":"A","Parts":[{"Count":"2"}]},""" +
                """{"LineNo":"A","Parts":[{"Count":"4"}]}],"Picks":[{"LineNo":"A"}]}}""",
            "nested" to """{"Order":{"Lines":[{"LineNo":"A","Parts":[{"Count":"2"},{"Count":"3"}]},""" +
                """{"LineNo":"C","Parts":[{"Count":"1"},{"Count":"5"}]}],"Picks":[{"LineNo":"A"}]}}""",
        )

        private val COMPUTE_DOCUMENTS = mapOf(
            "join" to JOIN,
            "dupkey" to DOCUMENTS.getValue("dupkey"),
            // Every part has a PartNo, so `kg`'s precondition fails before it reads the parts' group.
            "numbered" to """{"Order":{"Lines":[{"LineNo":"A","Parts":[{"PartNo":"p","Count":"1"}]}],""" +
                """"Picks":[{"LineNo":"A"}]}}""",
        )

        private data class PartialRow(val name: String, val document: String, val path: String, val reps: List<Int>)

        private val PARTIAL_ROWS = listOf(
            // The second part of the first line, below the join.
            PartialRow("nested:second-part", "nested", "/Order/Lines/Parts/Count", listOf(1, 1, 2, 1)),
            // The pick that has no line.
            PartialRow("join:lonely-pick", "join", "/Order/Picks/Ref", listOf(1, 3, 1)),
        )

        private val rulesArtifact: JsonNode by lazy {
            RuntimeProbeComparison.probe(dir, "carrier-rules", admittedModel, """["validateFull"]""", DOCUMENTS)
        }
        private val partialArtifact: JsonNode by lazy {
            RuntimeProbeComparison.probe(dir, "carrier-partial", admittedModel, """["validateFull","validatePart"]""",
                PARTIAL_ROWS.associate { it.name to DOCUMENTS.getValue(it.document) },
                PARTIAL_ROWS.associate { it.name to """[{"path":"${it.path}","reps":${it.reps}}]""" })
        }
        private val computeArtifact: JsonNode by lazy {
            RuntimeProbeComparison.probe(dir, "carrier-compute", computeModel, """["validateFull","compute"]""",
                COMPUTE_DOCUMENTS)
        }
    }
}
