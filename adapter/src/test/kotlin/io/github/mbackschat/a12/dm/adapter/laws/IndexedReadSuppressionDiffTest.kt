package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the VALIDATION-side suppression of indexed reads over an INVALID index state (the IG56 E4
 * hostile interp-extra class: the interpreter fired the implicit concat rule where the kernel suppresses).
 *
 * The kernel's IndexFieldCache marking (IF107) and the matched-malformed cell (IF108) make an indexed read
 * NOT-CHECK-RELEVANT in validation too: the `nichtPruefungsrelevant` flag ORs through a concatenation
 * (`VkString.add`) and `isStringNotRelevant` suppresses the comparison (`FALSE_OR_UNKNOWN`) — the referencing
 * rule does not fire. Distinct from the NOT-GIVEN state (a clean no-match), which concatenates as `""` and
 * FIRES with OMISSION polarity (IF113). The interpreter's compute-mode poison gates did not cover validation:
 * the invalid-state read resolved through the concat as `""` and the rule fired.
 */
class IndexedReadSuppressionDiffTest {

    private companion object {
        const val KEY = "/Shop/LookupKey"
        const val INFO = "/Shop/Info"
        const val ITEM_NO = "/Shop/Items/ItemNo"
        const val PRICE = "/Shop/Items/Price"
        const val LABEL = "/Shop/Items/Label"
        const val ITEMS = "/Shop/Items"
    }

    private val kModel = RuntimeLaws.withRule(
        RuntimeLaws.withComputation(
            DocumentModelDraft.named("infosuppress").locale("en_US")
                .rootGroup("Shop") { s ->
                    s.numberField("LookupKey") { it.fractionDigits(0, 0) }
                    s.stringField("Info") {}
                    s.group("Items") { i ->
                        i.repeatable(10).numberField("ItemNo") { it.fractionDigits(0, 0) }
                            .numberField("Price") { it.fractionDigits(2, 2) }
                            .stringField("Label") {}.indexField("ItemNo")
                    }
                }.build().toJson(),
            "/Shop", "InfoComp", "../Info",
            "\"P \" + [Items/Label For LookupKey] + \" Q \" + FieldValueAsString(Items/Price For LookupKey) + \" R\""),
        "/Shop", "PriceRule", "../LookupKey", "PRC",
        "FieldFilled(LookupKey) And [Items/Price For LookupKey] == 999.00")

    /** [rows]: (itemNo?, price?, label?). Returns the agreed kernel signatures (code|type only, sorted). */
    private fun sigs(key: String, rows: List<Triple<String?, String?, String?>>): List<String> {
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Shop", 1).field(KEY, key, 1, 1).field(INFO, "WRONG", 1, 1)
        val iDoc = def.newDocument().group("/Shop", 1).field(KEY, key, 1, 1).field(INFO, "WRONG", 1, 1)
        rows.forEachIndexed { idx, (no, price, label) ->
            val r = idx + 1
            kDoc.group(ITEMS, 1, r); iDoc.group(ITEMS, 1, r)
            no?.let { kDoc.field(ITEM_NO, it, 1, r, 1); iDoc.field(ITEM_NO, it, 1, r, 1) }
            price?.let { kDoc.field(PRICE, it, 1, r, 1); iDoc.field(PRICE, it, 1, r, 1) }
            label?.let { kDoc.field(LABEL, it, 1, r, 1); iDoc.field(LABEL, it, 1, r, 1) }
        }
        val k = RuntimeLaws.messages(kModel, kDoc).map { "${it.code()}|${it.type().name}" }.sorted()
        val i = DmInterpreter(def).validateFull(iDoc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(k, i, "key=$key rows=$rows — kernel vs interpreter")
        return k
    }

    // ── a DEFINITE match resolves the read: a malformed key cell ELSEWHERE does not suppress (validation
    //    differs from compute here — IF107's whole-column poison is compute-only) ────────────────────────────
    @Test fun aCleanMatchWithAMalformedCellElsewhereFires() =
        assertEquals(listOf("InfoComp|VALUE_ERROR", "PRC|VALUE_ERROR"),
            sigs("12", listOf(Triple("12", "999.00", "AB"), Triple("9999999999999999", "0.01", "CD")))
                .filter { it.startsWith("InfoComp") || it.startsWith("PRC") })

    // ── the corpus instance: NO definite match + a malformed key cell = the match is UNKNOWN → the read is
    //    not-check-relevant, the referencing concat rule is suppressed ────────────────────────────────────────
    @Test fun aNoMatchOverAMalformedKeyCellSuppressesTheConcatRule() =
        assertEquals(emptyList<String>(),
            sigs("274", listOf(Triple("12", "5.25", "AB"), Triple("9999999999999999", "0.01", "CD")))
                .filter { it.startsWith("InfoComp") },
            "the malformed cell could have matched the key — the lookup is unresolvable, not empty")

    // ── a MATCHED-malformed cell read through FieldValueAsString suppresses the concat rule too ───────────
    @Test fun aMatchedMalformedCellSuppressesTheConcatRule() {
        val sigs = sigs("12", listOf(Triple("12", "1.234", "AB")))
        assertEquals(emptyList<String>(), sigs.filter { it.startsWith("InfoComp") },
            "Price=1.234 (3 frac > max 2): the coercion read is not-check-relevant, the concat comparison is suppressed")
    }

    // ── an EMPTY key cell (auto-required, not-check-relevant) suppresses a no-match lookup too ────────────
    @Test fun aNoMatchOverAnEmptyKeyCellSuppresses() =
        assertEquals(emptyList<String>(),
            sigs("274", listOf(Triple("12", "5.25", "AB"), Triple(null, "0.01", "CD")))
                .filter { it.startsWith("InfoComp") })

    // ── a DUPLICATED key pair not matching the key: the uniqueIndex marking reaches the no-match lookup ──
    @Test fun aNoMatchOverADuplicatedPair() =
        assertEquals(emptyList<String>(),
            sigs("274", listOf(Triple("7", "1.00", "AA"), Triple("7", "2.00", "BB")))
                .filter { it.startsWith("InfoComp") })

    // ── a match ON a duplicated key: the kernel decides (first-match-wins vs the uniqueIndex np marking) ──
    @Test fun aMatchOnADuplicatedKey() =
        assertEquals(emptyList<String>(),
            sigs("7", listOf(Triple("7", "1.00", "AA"), Triple("7", "2.00", "BB")))
                .filter { it.startsWith("InfoComp") })

    // ── controls: a clean NO-MATCH is NOT-GIVEN, not not-relevant — the concat rule FIRES (IF113) ─────────
    @Test fun aCleanNoMatchStillFiresTheConcatRule() =
        assertEquals(listOf("InfoComp|OMISSION_ERROR"),
            sigs("274", listOf(Triple("12", "5.25", "AB"))).filter { it.startsWith("InfoComp") })

    @Test fun aCleanMatchedMismatchFiresValue() =
        assertEquals(listOf("InfoComp|VALUE_ERROR"),
            sigs("12", listOf(Triple("12", "5.25", "AB"))).filter { it.startsWith("InfoComp") })

    @Test fun theDirectRuleFiresOverACleanColumn() =
        assertEquals(listOf("PRC|VALUE_ERROR"),
            sigs("12", listOf(Triple("12", "999.00", "AB"))).filter { it.startsWith("PRC") })
}
