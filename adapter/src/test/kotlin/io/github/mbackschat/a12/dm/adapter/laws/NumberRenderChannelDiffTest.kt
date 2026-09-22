package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The interpreter's side of [NumberRenderChannelKernelTest] — A12 renders one stored decimal TWICE, and this
 * proves we reproduce **both** renderings in **both** storage regimes (IF231):
 *
 * - a document read from **Document JSON** holds a converted value, so every evaluation channel reads the
 *   runtime rendering `setScale(max(stripTrailingZeros().scale(), minFractionalDigits))` while the encoder still
 *   writes the serializer's `toPlainString()` — read→write identity is preserved *and* the eval side is right;
 * - a document **filled directly** (`Document.field(path, text)`) is A12's String-valued cell, where
 *   `ValidationData.getValue` returns the text unchanged — so the authored text IS the evaluation text.
 *
 * The V1 half is not decoration: it is the arm that refutes the tempting one-line "just derive it in the formal
 * check" fix. Deriving unconditionally makes `250` on a `minFractionalDigits: 2` field legal, which the kernel
 * says it is only when the value was converted.
 *
 * The `minLength`/`maxLength` cases are the sharp instrument — they count characters of the rendered text, so
 * `250` (3) and `250.00` (6) are told apart by a code that has nothing to do with decimal separators. That is
 * how the PAD half of the formula is pinned independently of `zahlOhneDezimalTrenner`.
 */
class NumberRenderChannelDiffTest {

    private val model = RuntimeLaws.model("models/decimal-render.dm.json")

    private val m = BoundModel.ofJson(model)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** The error field must be referenced in the condition (MVK_ERROR_FIELD_NOT_REFERENCED). */
    private fun withRule(c: Condition): String = RuntimeLaws.withRule(
        model, "/Shipment", "P", "../Reference", "P", render(m.str(REFERENCE).filled().and(c)),
    )

    // ── the two regimes, each as (kernel outcome, interpreter outcome) ───────────────────────────────

    /** V2 — the Document-JSON ingress: the kernel's own serializer against `readDocument`/`encodeDocument`. */
    private fun v2(modelJson: String, documentJson: String): Pair<String, String> {
        val kernel = RuntimeLaws.jsonIngress(modelJson, documentJson)
        val prepared = PreparedModel.loadExpandedJson(modelJson)
        val snapshot = prepared.documents.readDocument(documentJson)
        val codes = prepared.createInterpreter().validateFull(snapshot).findings.map { it.code }.sorted()
        return "${kernel.codes()} ${kernel.rewritten()}" to
            "$codes ${prepared.documents.encodeDocument(snapshot)}"
    }

    /** V1 — a directly filled cell, the regime in which the stored text is what validation sees. */
    private fun v1(modelJson: String, fields: List<Pair<String, String>>): Pair<String, String> {
        val kDoc = RuntimeLaws.doc().group(SHIPMENT, 1)
        val def = ModelLoader.load(modelJson)
        val iDoc = def.newDocument().group(SHIPMENT, 1)
        for ((path, value) in fields) {
            kDoc.field(path, value, 1, 1)
            iDoc.field(path, value, 1, 1)
        }
        val codes = DmInterpreter(def).validateFull(iDoc).map { it.code }.sorted()
        return RuntimeLaws.firedCodes(modelJson, kDoc).sorted().toString() to codes.toString()
    }

    private fun agree(label: String, observed: Pair<String, String>) =
        assertEquals(observed.first, observed.second, label)

    // ── Channel 1+2: the formal check — number format AND the value-string length bounds ─────────────

    @Test
    fun theFormalCheckAgreesOnBothRenderingsInBothRegimes() {
        val cases = listOf(
            // strip direction — the stored text carries a fractional trailing zero the runtime drops
            "Weight" to "250.00",          // maxFrac 0            → runtime "250" is legal, the stored text is not
            "Price" to "250.00",           // maxFrac 2            → both texts are format-legal
            "PriceCapped" to "250.00",     // maxFrac 2, maxLen 3  → "250" fits, "250.00" is too long
            "PriceFloored" to "250.00",    // maxFrac 2, minLen 4  → "250" is too short, "250.00" is not
            // pad direction — the runtime rendering is LONGER than the stored text
            "Freight" to "250",            // minFrac 2            → runtime "250.00" is legal, the stored text is not
            "Freight" to "250.0",
            "Freight" to "250.5",
            "Freight" to "250.00",
            "Freight" to "250.555",        //                      → 3 significant decimals survive: still invalid
            "FreightCapped" to "250",      // minFrac 2, maxLen 5  → "250.00" is too long, "250" is not
            "FreightCapped" to "1",        //                      → "1.00" fits: the control
        )
        for ((field, text) in cases) {
            val path = "/Shipment/$field"
            agree("V2 $field=$text", v2(model, """{"Shipment":{"$field":$text}}"""))
            agree("V1 $field=$text", v1(model, listOf(path to text)))
        }
    }

    // ── Channel 3: FieldValueAsString(numberField) — the second channel that reads a number's text ───

    @Test
    fun fieldValueAsStringAgreesOnBothRenderingsInBothRegimes() {
        val cases = listOf(
            Triple("Price", "250.00", "250"),        // maxFrac 2 — the runtime strips, so this MATCHES
            Triple("Price", "250.00", "250.00"),     //           — …and this does not
            Triple("Freight", "250.00", "250"),      // minFrac 2 — the runtime keeps both decimals
            Triple("Freight", "250.00", "250.00"),
        )
        for ((field, text, literal) in cases) {
            val path = "/Shipment/$field"
            val ruleModel = withRule(Dsl.fieldValueAsString(m.field(path)).eq(literal))
            agree(
                "V2 FieldValueAsString($field)==\"$literal\" stored=$text",
                v2(ruleModel, """{"Shipment":{"Reference":"R","$field":$text}}"""),
            )
            agree(
                "V1 FieldValueAsString($field)==\"$literal\" stored=$text",
                v1(ruleModel, listOf(REFERENCE to "R", path to text)),
            )
        }
    }

    // ── Channel 4: the semantic index [F For "key"] on a NUMBER index field ─────────────────────────

    @Test
    fun theSemanticIndexKeyAgreesWhateverSpellingEitherSideCarries() {
        // Tag declares minFrac 2, so "250.00" is the only authorable literal — yet a stored `250` (written back
        // as `250`) must still match it, because both sides normalize through the runtime rendering.
        for (stored in listOf("250", "250.00")) {
            val ruleModel = withRule(m.num(LOAD).forIndex("250.00").gt(0))
            agree(
                "[Load For \"250.00\"]>0 Tag=$stored",
                v2(ruleModel, """{"Shipment":{"Reference":"R","Parcels":[{"Tag":$stored,"Load":7}]}}"""),
            )
        }
        // Code declares NO minFrac, so both spellings of the KEY are authorable — which is what shows the
        // literal is normalized too, not only the stored side.
        for (key in listOf("250", "250.00")) {
            val ruleModel = withRule(m.num(UNITS).forIndex(key).gt(0))
            agree(
                "[Units For \"$key\"]>0 Code=250.00",
                v2(ruleModel, """{"Shipment":{"Reference":"R","Crates":[{"Code":250.00,"Units":7}]}}"""),
            )
        }
    }

    // ── Channel 5: both uniqueness channels, across rows whose STORED renderings differ ──────────────

    @Test
    fun bothUniquenessChannelsAgreeAcrossRowsWhoseStoredTextsDiffer() {
        agree(
            "uniqueIndex Tag rows [250, 250.00]",
            v2(model, """{"Shipment":{"Parcels":[{"Tag":250},{"Tag":250.00}]}}"""),
        )
        val rnu = RuntimeLaws.withRule(
            model, "/Shipment", "P", "../Crates/Units", "P",
            render(Dsl.repetitionNotUnique(m.field(UNITS))),
        )
        agree(
            "RepetitionNotUnique(Units) rows [250, 250.00]",
            v2(
                rnu,
                """{"Shipment":{"Reference":"R","Crates":[{"Code":1,"Units":250},{"Code":2,"Units":250.00}]}}""",
            ),
        )
    }

    // ── read→write identity: the whole point of storing the SERIALIZER rendering ─────────────────────

    @Test
    fun aDocumentA12WroteStillRoundTripsByteIdentically() {
        val documentJson = """{"Shipment":{"Freight":250.00,"Price":250.00,"Weight":250.00}}"""
        val prepared = PreparedModel.loadExpandedJson(model)
        assertEquals(
            documentJson,
            prepared.documents.encodeDocument(prepared.documents.readDocument(documentJson)),
            "the evaluation rendering must not leak into the written document",
        )
    }

    private companion object {
        const val SHIPMENT = "/Shipment"
        const val REFERENCE = "/Shipment/Reference"
        const val LOAD = "/Shipment/Parcels/Load"
        const val UNITS = "/Shipment/Crates/Units"
    }
}
