package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A stored supplementary-plane character under a `supportedCharacters` range spanning the whole surrogate block
 * (KERNEL-FINDINGS KF560). The declaration check refuses such a character in an enum value at its high unit even
 * inside the range; this asks whether a stored value meets the same tester, against a narrow restriction that
 * covers neither unit and a plain BMP letter inside the range as controls.
 *
 * Own domain (a courier's delivery note).
 */
class SupportedCharactersSupplementaryDiffTest {

    private fun model(vararg charset: String): String {
        val session = EditSession.read(DocumentModelDraft.named("delivery-note").locale("en_US")
            .supportedCharacters(*charset)
            .rootGroup("Delivery") { d -> d.stringField("Note") { }.numberField("NoteLength") { it.fractionDigits(0) } }
            .build().toJson())
        val delivery = session.group("/Delivery")
        delivery.addComputation(delivery.num("NoteLength")) {
            it.name("NoteLengthComp").calc(Dsl.length(delivery.str("Note"))).message(Dsl.en("Check the note.")).build()
        }
        delivery.addRule {
            it.name("NoteGiven").code("NOTE_GIVEN").errorField(delivery.str("Note"))
                .`when`(delivery.str("Note").filled()).message(Dsl.en("A note is given.")).build()
        }
        return session.toJson()
    }

    private val spanning = model(" -�")
    private val narrow = model("A-Z", "a-z")

    private val documents = linkedMapOf(
        "a letter" to """{"Delivery":{"Note":"ok"}}""",
        "a BMP sign inside the range" to """{"Delivery":{"Note":"okü"}}""",
        "a supplementary character" to """{"Delivery":{"Note":"ok$CART"}}""",
        "two supplementary characters" to """{"Delivery":{"Note":"$CART$BAG"}}""",
    )

    @Test
    fun bothModelsAreKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(spanning))
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(narrow))
    }

    @Test
    fun allThreeEnginesAgreeUnderTheSpanningRange() {
        DocumentDifferential.assertAgree(spanning, documents)
    }

    @Test
    fun allThreeEnginesAgreeUnderTheNarrowRestriction() {
        DocumentDifferential.assertAgree(narrow, documents)
    }

    /**
     * The kernel's own verdicts, so the agreement above is not agreement on nothing: the spanning range admits the
     * BMP sign the narrow one refuses, and refuses the supplementary character both refuse.
     */
    @Test
    fun theSpanningRangeRefusesOnlyTheSupplementaryCharacter() {
        fun codes(model: String) = documents.keys.associateWith { document ->
            DocumentDifferential.kernelMessages(model, documents.getValue(document), true).map { it.code }.sorted()
        }
        assertEquals(mapOf("a letter" to listOf(GIVEN), "a BMP sign inside the range" to listOf(GIVEN),
            "a supplementary character" to listOf(OUTSIDE), "two supplementary characters" to listOf(OUTSIDE)),
            codes(spanning))
        assertEquals(mapOf("a letter" to listOf(GIVEN), "a BMP sign inside the range" to listOf(OUTSIDE),
            "a supplementary character" to listOf(OUTSIDE), "two supplementary characters" to listOf(OUTSIDE)),
            codes(narrow))
    }

    private companion object {
        /** U+1F6D2 and U+1F6CD, each a surrogate pair sharing the high unit U+D83D. */
        const val CART = "🛒"
        const val BAG = "🛍"
        const val GIVEN = "NOTE_GIVEN"
        const val OUTSIDE = "ZeichenNichtImZeichensatz"
    }
}
