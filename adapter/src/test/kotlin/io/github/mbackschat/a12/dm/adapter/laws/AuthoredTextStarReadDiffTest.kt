package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The technical length guard counts a cell's AUTHORED text (KERNEL-FINDINGS KF547), and a starred read meets the
 * cell through the document's own scan rather than one cell at a time. Two notes, the second 99,999 units once
 * its CRLF folds to one and 100,000 as written: a reader measuring the folded text would count it as filled.
 *
 * Own domain (an order's line notes).
 */
class AuthoredTextStarReadDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("order-line-notes").locale("en_US")
            .rootGroup("Order") { o -> o.stringField("Ref") { }.numberField("NoteCount") { it.fractionDigits(0) }
                .group("Lines") { l -> l.repeatable(3).stringField("Text") { it.lineBreaks() } } }
            .build().toJson())
        val order = session.group("/Order")
        val texts = order.flatten("Lines*/Text")
        order.addRule {
            it.name("TwoNotes").code("TWO_NOTES").errorField(order.str("Ref"))
                .`when`(order.str("Ref").filled().and(Dsl.numberOfFilledFields(texts).gt(1)))
                .message(Dsl.en("Two notes.")).build()
        }
        order.addRule {
            it.name("SomeNoteMissing").code("NOTE_MISSING").errorField(order.str("Ref"))
                .`when`(order.str("Ref").filled().and(Dsl.notAllFieldsFilled(texts)))
                .message(Dsl.en("A note is missing.")).build()
        }
        order.addComputation(order.num("NoteCount")) {
            it.name("CountNotes").calc(Dsl.numberOfFilledFields(texts)).message(Dsl.en("Check the note count.")).build()
        }
        session.toJson()
    }

    private fun order(second: String) = """{"Order":{"Ref":"O-1","Lines":[{"Text":"x"},{"Text":${json(second)}}]}}"""

    private fun json(text: String) = "\"" + text.replace("\r", "\\r").replace("\n", "\\n") + "\""

    private val documents = linkedMapOf(
        "the second note at the limit" to order("a".repeat(LIMIT - 2) + "\r\n"),
        "the second note one unit over as written" to order("a".repeat(LIMIT - 1) + "\r\n"),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    @Test
    fun theStarredReadsCountTheAuthoredText() {
        val observed = documents.mapValues { (_, json) ->
            DocumentDifferential.kernelMessages(model, json, true).map { "${it.code}@${it.pointer.removePrefix("/Order[1]/")}" } +
                DocumentDifferential.kernel(model, json, dynamic = true).outcomes.map { (k, v) -> "$k=$v" }
        }
        println("[authored] $observed")
    }

    private companion object {
        const val LIMIT = 99_999
    }
}
