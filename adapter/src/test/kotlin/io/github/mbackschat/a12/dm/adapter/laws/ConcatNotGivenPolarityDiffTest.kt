package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the POLARITY of a fired string comparison whose operand is a CONCATENATION carrying a
 * NOT-GIVEN part (the IG56 E3 nice-mode flip class: kernel OMISSION, interp VALUE, 9 units).
 *
 * The kernel rule (source, both strategies): `VkString.add` ORs the `nichtAngegeben` flags of its parts — an
 * empty check-relevant field read, a no-match/empty indexed cell, or a not-given `FieldValueAsString` keeps
 * the whole concatenation "not given" — and `RuntimeController.vergleicheSTRING` types a FIRED comparison
 * `TRUE_AF` (→ OMISSION: filling could still change the concatenated value) iff either operand carries the
 * flag, else `TRUE_WF` (→ VALUE). The interpreter's `arithFill` typed every fired string-concat comparison
 * VALUE ("the fixed leaf" — wrong whenever a part is a not-given read).
 *
 * Two shapes: an explicit rule `[Label] != [Code] + "-suffix"`, and the corpus shape — an implicit
 * computation-validation whose operation concatenates a NO-MATCH semantic-index read.
 */
class ConcatNotGivenPolarityDiffTest {

    // ── shape 1: an explicit rule comparing against a concat over a plain field ───────────────────────────

    private val productModel = RuntimeLaws.withRule(
        DocumentModelDraft.named("labelcheck").locale("en_US")
            .rootGroup("Product") { p ->
                p.stringField("Code") {}
                p.stringField("Label") {}
            }.build().toJson(),
        "/Product", "LabelRule", "../Label", "LBL", "[Label] != [Code] + \"-suffix\"")

    private fun ruleSigs(code: String?, label: String?): Pair<List<String>, List<String>> {
        val def = ModelLoader.load(productModel)
        val kDoc = RuntimeLaws.doc().group("/Product", 1)
        val iDoc = def.newDocument().group("/Product", 1)
        code?.let { kDoc.field("/Product/Code", it, 1, 1); iDoc.field("/Product/Code", it, 1, 1) }
        label?.let { kDoc.field("/Product/Label", it, 1, 1); iDoc.field("/Product/Label", it, 1, 1) }
        val k = RuntimeLaws.messages(productModel, kDoc).map { "${it.code()}|${it.type().name}|${it.field()}" }.sorted()
        val i = DmInterpreter(def).validateFull(iDoc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}" }.sorted()
        assertEquals(k, i, "code=$code label=$label — kernel vs interpreter")
        return k to i
    }

    @Test fun aFiredMismatchOverAnEmptyConcatPartIsOmission() =
        assertEquals(listOf("LBL|OMISSION_ERROR|/Product[1]/Label"),
            ruleSigs(code = null, label = "X").first,
            "Code is empty: the concat is still fillable — filling Code could resolve the mismatch")

    @Test fun aFiredMismatchOverAFullyGivenConcatIsValue() =
        assertEquals(listOf("LBL|VALUE_ERROR|/Product[1]/Label"),
            ruleSigs(code = "AB", label = "X").first)

    @Test fun aMatchingLabelIsSilent() =
        assertEquals(emptyList<String>(), ruleSigs(code = "AB", label = "AB-suffix").first)

    // ── shape 2 (the corpus instance): the implicit computation-validation of an indexed concat ───────────

    private val shopModel = RuntimeLaws.withComputation(
        DocumentModelDraft.named("infolookup").locale("en_US")
            .rootGroup("Shop") { s ->
                s.numberField("LookupKey") { it.fractionDigits(0, 0) }
                s.stringField("Info") {}
                s.group("Items") { i ->
                    i.repeatable(10).numberField("ItemNo") { it.fractionDigits(0, 0) }
                        .stringField("Label") {}.indexField("ItemNo")
                }
            }.build().toJson(),
        "/Shop", "InfoComp", "../Info", "\"P \" + [Items/Label For LookupKey] + \" Q\"")

    private fun implicitSigs(key: String, itemNo: String, itemLabel: String): List<String> {
        val def = ModelLoader.load(shopModel)
        val kDoc = RuntimeLaws.doc().group("/Shop", 1)
            .field("/Shop/LookupKey", key, 1, 1).field("/Shop/Info", "WRONG", 1, 1)
        val iDoc = def.newDocument().group("/Shop", 1)
            .field("/Shop/LookupKey", key, 1, 1).field("/Shop/Info", "WRONG", 1, 1)
        kDoc.group("/Shop/Items", 1, 1).field("/Shop/Items/ItemNo", itemNo, 1, 1, 1)
            .field("/Shop/Items/Label", itemLabel, 1, 1, 1)
        iDoc.group("/Shop/Items", 1, 1).field("/Shop/Items/ItemNo", itemNo, 1, 1, 1)
            .field("/Shop/Items/Label", itemLabel, 1, 1, 1)
        val k = RuntimeLaws.messages(shopModel, kDoc).map { "${it.code()}|${it.type().name}|${it.field()}" }.sorted()
        val i = DmInterpreter(def).validateFull(iDoc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}" }.sorted()
        assertEquals(k, i, "key=$key — kernel vs interpreter")
        return k
    }

    @Test fun aNoMatchIndexedConcatMismatchIsOmission() =
        assertEquals(listOf("InfoComp|OMISSION_ERROR|/Shop[1]/Info"), implicitSigs("274", "12", "AB"),
            "no row matches the key: the indexed read is not-given, so the concat stays fillable")

    @Test fun aMatchedIndexedConcatMismatchIsValue() =
        assertEquals(listOf("InfoComp|VALUE_ERROR|/Shop[1]/Info"), implicitSigs("12", "12", "AB"))
}
