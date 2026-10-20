package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Differential (groovy-dynamic == java-static == interpreter) for a required field's own message text, which A12
 * renders by four literal replace-all passes over the whole text: `$Feld$`, `$field$` by the label or name, then
 * `$Feld.Wert$`, `$field.value$` by the empty value, every other dollar sequence verbatim (a12-kernel-lean's
 * SPEC-2026-10-14-10 report, re-measured here on an own-domain claim model).
 *
 * The rows that tell the succession apart from a one-pass token scan are the overlaps, where one pass consumes
 * or completes text a later one reads (`$field$Feld$`), and a label carrying a token, which a later pass
 * substitutes again. The German-condition model is the row that tells a language-independent token set from the
 * language-scoped one the invalid-value producer uses (IF345). Each template opens with its row tag, so one
 * validation per engine yields every row and a text cannot be credited to the wrong field.
 */
class RequirednessTextSubstitutionDiffTest {

    private data class Row(val field: String, val label: String?, val template: String, val rendered: String)

    private val english = listOf(
        Row("Insured", "Insured person", "r01 \$field\$", "r01 Insured person"),
        Row("PolicyNo", null, "r02 \$Feld\$", "r02 PolicyNo"),
        Row("Premium", null, "r03 \$field.value\$ end", "r03  end"),
        Row("Deductible", null, "r04 \$Feld.Wert\$ end", "r04  end"),
        Row("Broker", "Broker", "r05 \$field\$Feld\$", "r05 \$fieldBroker"),
        Row("Agent", "Agent", "r06 \$Feld.Wert\$field\$", "r06 \$Feld.WertAgent"),
        Row("Branch", "Branch", "r07 A\$field\$field.value\$B", "r07 ABranchfield.value\$B"),
        Row("Region", "Region", "r08 \$field.value\$Feld.Wert\$", "r08 \$field.value"),
        Row("Channel", null, "r09 \$Other\$ \$FIELD\$ \$field.Wert\$ \$\$ \$field", "r09 \$Other\$ \$FIELD\$ \$field.Wert\$ \$\$ \$field"),
        Row("Insurer", "Insurer \$field.value\$ x", "r10 \$field\$", "r10 Insurer  x"),
        Row("Carrier", "Carrier \$field\$", "r11 \$Feld\$", "r11 Carrier Carrier \$field\$"),
    )

    @Test
    fun theFourTokensAreReplacedInSuccessionOverTheWholeText() {
        assertAgrees(model(english, "en_US"), english)
    }

    @Test
    fun aGermanConditionModelReplacesBothLanguagesTokens() {
        val rows = listOf(Row("Customer", "Kunde", "d01 \$field\$ \$Feld\$ \$field.value\$|\$Feld.Wert\$", "d01 Kunde Kunde |"))
        assertAgrees(model(rows, "de_DE"), rows)
    }

    @Test
    fun aRepeatedRowsFieldRendersOncePerRow() {
        val base = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim") { claim ->
                claim.group("Items") { items -> items.repeatable(3).stringField("Item") { it.required() } }
            }
            .build().toJson()
        val edit = EditSession.read(base)
        edit.field("/Claim/Items/Item").setLabel(mapOf("en_US" to "Damaged item"))
            .setRequiredErrorMessages(mapOf("en_US" to "r12 \$field\$ \$field.value\$in row"))
        val model = edit.toJson()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model), "the model is admitted")
        val kDoc = RuntimeLaws.doc().group("/Claim", 1).group("/Claim/Items", 1, 1).emptyField("/Claim/Items/Item", 1, 1, 1)
            .group("/Claim/Items", 1, 2).emptyField("/Claim/Items/Item", 1, 2, 1)
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Claim", 1)
            .group("/Claim/Items", 1, 1).emptyField("/Claim/Items/Item", 1, 1, 1)
            .group("/Claim/Items", 1, 2).emptyField("/Claim/Items/Item", 1, 2, 1)
        val (dynamic, generated, interpreter) = texts(model, def, kDoc, iDoc)
        assertEquals(listOf("r12 Damaged item in row", "r12 Damaged item in row"), dynamic, "dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreter, "interpreter")
    }

    private fun model(rows: List<Row>, conditionLanguage: String): String {
        val base = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim") { claim -> rows.forEach { row -> claim.stringField(row.field) { it.required() } } }
            .build().toJson()
        val edit = EditSession.read(base).setConditionLanguage(conditionLanguage)
        rows.forEach { row ->
            val field = edit.field("/Claim/${row.field}")
            row.label?.let { field.setLabel(mapOf("en_US" to it)) }
            field.setRequiredErrorMessages(mapOf("en_US" to row.template))
        }
        return edit.toJson()
    }

    private fun assertAgrees(model: String, rows: List<Row>) {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model), "the model is admitted")
        var kDoc = RuntimeLaws.doc().group("/Claim", 1)
        val def = ModelLoader.load(model)
        var iDoc = def.newDocument().group("/Claim", 1)
        rows.forEach { row ->
            kDoc = kDoc.emptyField("/Claim/${row.field}", 1, 1)
            iDoc = iDoc.emptyField("/Claim/${row.field}", 1, 1)
        }
        val (dynamic, generated, interpreter) = texts(model, def, kDoc, iDoc)
        assertEquals(rows.map { it.rendered }.sorted(), dynamic, "dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreter, "interpreter")
    }

    /** Every `mandatoryField` text per engine, sorted: the row tags make the order irrelevant. */
    private fun texts(
        model: String,
        def: ModelDef,
        kDoc: RuntimeLaws.Doc,
        iDoc: Document,
    ): Triple<List<String>, List<String>, List<String>> {
        val dynamic = RuntimeLaws.errorTextsOf(model, "mandatoryField", kDoc, Locale.US).sorted()
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextsOf(java.instance(kDoc), "mandatoryField", Locale.US).sorted()
        val interpreter = DmInterpreter(def).validate(iDoc).messages
            .filter { it.code == "mandatoryField" }.map { it.text }.sorted()
        return Triple(dynamic, generated, interpreter)
    }
}
