package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Multiplatform (JVM + JS) lock for the model-declared legal charset (`modelConfig.supportedCharacters`, IF120) —
 * the PURE interpreter (loader + [io.github.mbackschat.a12.dm.interpreter.model.LegalCharset] + eval), so it runs
 * kernel-free on Kotlin/JS too. The kernel differential (kernel bakes the slot into codegen and fires
 * `ZeichenNichtImZeichensatz`) is pinned in `adapter.laws.SupportedCharactersDiffTest`; this proves the
 * kernel-free build reproduces it end-to-end: the loader reads the slot, and a value carrying a character outside
 * the set is rejected while an in-set value is clean. An empty/absent slot means "no restriction" (BMP default).
 */
class SupportedCharactersTest {

    private val CHARSET = "ZeichenNichtImZeichensatz"

    /** A policy model whose config restricts every value to upper-case letters + digits. */
    private fun policyModel(supported: String?) = """
        {
          "header": { "id": "policy" },
          "content": {
            ${if (supported == null) "" else "\"modelConfig\": { \"supportedCharacters\": $supported },"}
            "modelRoot": { "rootGroups": [ {
              "type": "Group", "id": "/Policy", "name": "Policy", "Group": { "elements": [
                { "type": "Field", "id": "/Policy/Ref", "name": "Ref", "Field": { "fieldType": {"type":"StringType"} } }
              ] } } ] } }
        }
    """.trimIndent()

    private fun charsetFires(supported: String?, value: String): Boolean {
        val def = ModelLoader.load(policyModel(supported))
        val doc = def.newDocument().group("/Policy", 1).field("/Policy/Ref", value, 1, 1)
        return DmInterpreter(def).validateFull(doc).any { it.code == CHARSET }
    }

    @Test fun loaderReadsTheSlotIntoLegalCharset() {
        val cs = assertNotNull(ModelLoader.load(policyModel("""["A-Z","0-9"]""")).legalCharset)
        assertEquals(true, cs.allows('A'.code), "A is in A-Z")
        assertEquals(true, cs.allows('7'.code), "7 is in 0-9")
        assertEquals(false, cs.allows('a'.code), "a is outside A-Z/0-9")
    }

    @Test fun absentSlotLeavesNoModelCharset() =
        assertNull(ModelLoader.load(policyModel(null)).legalCharset, "no supportedCharacters → null (BMP default applies)")

    @Test fun emptySlotLeavesNoModelCharset() =
        assertNull(ModelLoader.load(policyModel("[]")).legalCharset, "empty supportedCharacters → null (BMP default applies)")

    @Test fun inSetValueIsClean() =
        assertEquals(false, charsetFires("""["A-Z","0-9"]""", "POL2024"), "all A-Z/0-9 → clean")

    @Test fun lowerCaseIsRejected() =
        assertEquals(true, charsetFires("""["A-Z","0-9"]""", "pol2024"), "lower-case is outside A-Z → charset fires")

    @Test fun punctuationIsRejected() =
        assertEquals(true, charsetFires("""["A-Z","0-9"]""", "POL-24"), "the '-' is outside the set → charset fires")

    @Test fun withoutTheSlotAllBmpIsClean() =
        assertEquals(false, charsetFires(null, "pol-2024"), "no slot → BMP default → a lower-case/punctuation value is clean")
}
