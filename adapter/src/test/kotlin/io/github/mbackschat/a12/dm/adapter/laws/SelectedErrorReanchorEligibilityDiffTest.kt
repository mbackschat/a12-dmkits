package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which fired rules move their error address to a selected row (KERNEL-FINDINGS KF549, a12-kernel-lean
 * SPEC-2026-10-12-01). A rule reports on `Bays/Load` while its condition reads `Load` under a semantic-index key;
 * the address is replaced by the key's association only when every such read names ONE key and the rule does
 * not iterate the selected level. Each eligible rule fires through an independent `Note` branch, so the address
 * is observable even when the selected value is missing or invalid.
 *
 * The rules pair each eligible shape with its rival: the same key written twice against two different literal
 * keys, and against a literal and a field-valued key that select the same row; a depot-level rule against a
 * bay-level rule that iterates the selected level itself. Every document compares the full message signatures
 * of both kernel strategies and the interpreter, and the pinned rows keep the comparison from being vacuous.
 *
 * Own domain (a fleet's depots and their keyed loading bays).
 */
class SelectedErrorReanchorEligibilityDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("fleet-selected-errors").locale("en_US")
            .rootGroup("Fleet") { f -> f
                .group("Depots") { d -> d.repeatable(2)
                    .stringField("Wanted") { }
                    .stringField("Short") { it.maxLength(1) }
                    .stringField("Note") { }
                    .group("Bays") { b -> b.repeatable(4).indexField("BayCode")
                        .stringField("BayCode") { }
                        .numberField("Load") { it.fractionDigits(0) }
                        .group("Slots") { s -> s.repeatable(3).numberField("Qty") { it.fractionDigits(0) } } } } }
            .build().toJson()
        val session = EditSession.read(draft)
        val depot = session.group(DEPOT)
        val note = depot.str("Note")
        val wanted = depot.field("Wanted")
        val short = depot.field("Short")
        val load = depot.num("Bays/Load")
        val loadField = depot.field("Bays/Load")
        val depotRules: List<Pair<String, Condition>> = listOf(
            "OneKey" to note.filled().or(load.forIndex(wanted).gt(0)),
            "SameKeyTwice" to note.filled().or(load.forIndex(wanted).gt(0)).or(load.forIndex(wanted).lt(-100)),
            "SameLiteralTwice" to note.filled().or(load.forIndex("b").gt(0)).or(load.forIndex("b").lt(-100)),
            "TwoLiterals" to note.filled().or(load.forIndex("a").gt(0)).or(load.forIndex("b").gt(0)),
            "LiteralAndField" to note.filled().or(load.forIndex("b").gt(0)).or(load.forIndex(wanted).gt(0)),
            "ShortKey" to note.filled().or(load.forIndex(short).gt(0)),
        )
        for ((code, condition) in depotRules) depot.addRule {
            it.name(code).code(code).errorField(loadField).`when`(condition)
                .message(Dsl.en("Check the bay.")).build()
        }
        for ((code, key) in listOf("WantedToken" to wanted, "ShortToken" to short)) depot.addRule {
            it.name(code).code(code).errorField(note).`when`(note.filled().and(loadField.semanticIndex(key).filled()))
                .message(Dsl.en("Bay [" + Dsl.repetitionIndex(depot.flattenGroup("Bays").forIndex(key)) + "]")).build()
        }
        val bays = session.group("$DEPOT/Bays")
        bays.addRule {
            it.name("IteratedLevel").code("IteratedLevel").errorField(bays.field("Load"))
                .`when`(bays.num("Load").filled().and(load.forIndex("b").ge(0)))
                .message(Dsl.en("Check the bay.")).build()
        }
        session.toJson()
    }

    private fun bay(code: String, load: String, vararg slots: String) =
        """{"BayCode":"$code","Load":"$load","Slots":[${slots.joinToString(",") { """{"Qty":"$it"}""" }}]}"""

    private fun depot(wanted: String?, vararg bays: String, short: String? = null) = "{" + listOfNotNull(
        wanted?.let { "\"Wanted\":\"$it\"" }, short?.let { "\"Short\":\"$it\"" }, "\"Note\":\"n\"",
        "\"Bays\":[" + bays.joinToString(",") + "]",
    ).joinToString(",") + "}"

    private fun fleet(vararg depots: String) = """{"Fleet":{"Depots":[${depots.joinToString(",")}]}}"""

    private val documents = linkedMapOf(
        "the second bay selected" to fleet(depot("b", bay("a", "1"), bay("b", "2"), bay("c", "3"))),
        "two depots select different bays" to
            fleet(depot("b", bay("a", "1"), bay("b", "2")), depot("a", bay("a", "4"), bay("b", "5"))),
        "no bay carries the key" to fleet(depot("zz", bay("x", "1"), bay("y", "2"))),
        "the selected load is invalid" to fleet(depot("b", bay("a", "1"), bay("b", "x"))),
        "an invalid key matches a bay" to fleet(depot(null, bay("a", "1"), bay("bb", "2"), short = "bb")),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** The positive and negative controls: the eligible rules name the selected row, their rivals do not. */
    @Test
    fun onlyAUniqueKeyOnANonIteratedLevelMovesTheAddress() {
        val messages = DocumentDifferential.kernelMessages(model, documents.getValue("two depots select different bays"), true)
        fun rows(code: String) = messages.filter { it.code == code }.map { it.pointerKey }.sorted()
        assertEquals(listOf("$DEPOT_1/Bays[2]/Load[1]", "$DEPOT_2/Bays[1]/Load[1]"), rows("OneKey"))
        assertEquals(rows("OneKey"), rows("SameKeyTwice"), "a repeated key is still one key")
        assertEquals(listOf("$DEPOT_1/Bays[2]/Load[1]", "$DEPOT_2/Bays[2]/Load[1]"), rows("SameLiteralTwice"))
        assertEquals(listOf("$DEPOT_1/Bays[1]/Load[1]", "$DEPOT_2/Bays[1]/Load[1]"), rows("TwoLiterals"),
            "two keys compete, so the address keeps its unselected row")
        assertEquals(rows("TwoLiterals"), rows("LiteralAndField"), "a literal and a field key compete too")
    }

    /** A rule iterating the selected level keeps each row's own address; the depot-level rule moves to row 2. */
    @Test
    fun iteratingTheSelectedLevelKeepsTheRowAddress() {
        val messages = DocumentDifferential.kernelMessages(model, documents.getValue("the second bay selected"), true)
        assertEquals(listOf(1, 2, 3).map { "$DEPOT_1/Bays[$it]/Load[1]" },
            messages.filter { it.code == "IteratedLevel" }.map { it.pointerKey }.sorted())
        assertEquals(listOf("$DEPOT_1/Bays[2]/Load[1]"), messages.filter { it.code == "OneKey" }.map { it.pointerKey })
    }

    /** The association, not the payload, decides the row: an invalid selected `Load` still names row 2. */
    @Test
    fun anUnavailablePayloadStillNamesTheAssociatedRow() {
        val messages = DocumentDifferential.kernelMessages(model, documents.getValue("the selected load is invalid"), true)
        assertEquals(listOf("$DEPOT_1/Bays[2]/Load[1]"), messages.filter { it.code == "OneKey" }.map { it.pointerKey })
    }

    @Test
    fun aMissingAssociationNamesTheUnknownRow() {
        val messages = DocumentDifferential.kernelMessages(model, documents.getValue("no bay carries the key"), true)
        assertEquals(listOf("$DEPOT_1/Bays[-5]/Load[1]"), messages.filter { it.code == "OneKey" }.map { it.pointerKey })
    }

    /**
     * A key longer than its field's maximum is formally invalid, so the reanchoring reads no key even though its
     * text equals the second bay's code: the sibling of the irrelevant key below, through the other half of
     * "check-relevant". `Short` stays a formal error of its own; only the address of `ShortKey` is pinned here.
     */
    @Test
    fun aFormallyInvalidKeyNamesTheUnknownRow() {
        val messages = DocumentDifferential.kernelMessages(model, documents.getValue("an invalid key matches a bay"), true)
        assertEquals(listOf("$DEPOT_1/Bays[-5]/Load[1]"), messages.filter { it.code == "ShortKey" }.map { it.pointerKey })
    }

    /**
     * The `$#…$` group-index token over a selected expression shares the key read, but an invalid key cannot reach
     * it: the kernel admits the token only when the condition requires the selected row to be filled
     * (`INVALID_USE_OF_SEMANTIC_INDEX`), and that operand is unknown under the same invalid key, so the rule is
     * silent. The valid twin is the positive control.
     */
    @Test
    fun theGroupIndexTokenReadsTheCheckedKey() {
        DocumentDifferential.assertMessageTexts(model, documents.getValue("the second bay selected"), "WantedToken",
            listOf("$DEPOT_1/Note" to "Bay [2]"))
        DocumentDifferential.assertMessageTexts(model, documents.getValue("an invalid key matches a bay"), "ShortToken",
            emptyList())
    }

    /**
     * Partial validation that leaves the key field out of the relevant set: the selected reanchoring reads the key
     * field's checked value, so the irrelevant key names no row. The relevant set keeps the error field and the
     * independent `Note` branch, so the rule still fires.
     */
    @Test
    fun anIrrelevantKeyFieldNamesNoRow() {
        val input = documents.getValue("the second bay selected")
        val relevant = setOf(RelevantEntity.anyRep("$DEPOT_PATH/Note"), RelevantEntity.anyRep("$DEPOT_PATH/Bays/Load"),
            RelevantEntity.anyRep("$DEPOT_PATH/Bays/BayCode"))
        val dynamic = DocumentDifferential.kernelMessages(model, input, true, relevant)
        assertEquals(dynamic, DocumentDifferential.kernelMessages(model, input, false, relevant), "static-Java")
        assertEquals(dynamic, DocumentDifferential.interpreterMessages(model, input, relevant), "interpreter")
    }

    private companion object {
        const val DEPOT_PATH = "/Fleet/Depots"
        const val DEPOT = DEPOT_PATH
        const val DEPOT_1 = "/Fleet[1]/Depots[1]"
        const val DEPOT_2 = "/Fleet[1]/Depots[2]"
    }
}
