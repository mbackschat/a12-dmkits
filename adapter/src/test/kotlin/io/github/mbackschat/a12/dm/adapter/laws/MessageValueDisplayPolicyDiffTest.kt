package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Both-strategy differential for the exact `$field.value$` message-display boundary: scaled empty-number
 * defaults retain their declared minimum scale, while a CRLF cell is normalized for condition evaluation but
 * rendered from its original display text. Models and rules are authored through modelkit/edit/rulekit.
 */
class MessageValueDisplayPolicyDiffTest {

    @Test
    fun missingExactTwoDecimalNumberUsesPresentationDefaultNotMessageLocale() {
        val model = model()
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(ANCHOR, "x", 1, 1)
        val definition = ModelLoader.load(model)
        val interpreterDocument = definition.newDocument().field(ANCHOR, "x", 1, 1)

        agree(model, kernelDocument, definition, interpreterDocument, AMOUNT_CODE, "[0.00]", Locale.US, "en_US")
        agree(model, kernelDocument, definition, interpreterDocument, AMOUNT_CODE, "[0.00]", Locale.GERMANY, "de_DE")
    }

    @Test
    fun crlfMessageKeepsOriginalDisplayWhileConditionReadsNormalizedValue() {
        val model = model()
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(NOTE, CRLF, 1, 1)
        val definition = ModelLoader.load(model)
        val interpreterDocument = definition.newDocument().field(NOTE, CRLF, 1, 1)

        agree(model, kernelDocument, definition, interpreterDocument, NOTE_CODE, CRLF, Locale.US, "en_US")
    }

    @Test
    fun literalDollarDecodesWithoutReparsingTheInterpolatedValue() {
        val model = model()
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(ANCHOR, "\$Note\$", 1, 1)
        val definition = ModelLoader.load(model)
        val interpreterDocument = definition.newDocument().field(ANCHOR, "\$Note\$", 1, 1)

        agree(
            model,
            kernelDocument,
            definition,
            interpreterDocument,
            LITERAL_DOLLAR_CODE,
            "Cost \$ [\$Note\$]",
            Locale.US,
            "en_US",
        )
    }

    private fun agree(
        model: String,
        kernelDocument: RuntimeLaws.Doc,
        definition: io.github.mbackschat.a12.dm.interpreter.model.ModelDef,
        interpreterDocument: io.github.mbackschat.a12.dm.interpreter.eval.Document,
        code: String,
        expected: String,
        locale: Locale,
        language: String,
    ) {
        val dynamic = RuntimeLaws.errorTextOf(model, code, kernelDocument, locale).orElseThrow()
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextOf(java.instance(kernelDocument), code, locale).orElseThrow()
        val interpreted = DmInterpreter(definition).validateFull(interpreterDocument, language)
            .single { it.code == code }.text

        assertEquals(expected, dynamic, "$language: normative Groovy-dynamic")
        assertEquals(dynamic, generated, "$language: generated Java")
        assertEquals(dynamic, interpreted, "$language: interpreter")
    }

    private fun model(): String {
        val base = DocumentModelDraft.named("message-display").locale("en_US").locale("de_DE")
            .rootGroup("Invoice") { root ->
                root.stringField("Anchor") { }
                root.numberField("Amount") { it.fractionDigits(2, 2) }
                root.stringField("Note") { it.lineBreaks() }
            }
            .build()
            .toJson()
        return EditSession.read(base).also { edit ->
            edit.addRule { bound ->
                val amount = bound.num(AMOUNT)
                bound.rule(bound.group(ROOT)).name("MissingAmount").code(AMOUNT_CODE).errorField(amount)
                    .`when`(amount.notFilled()).severity(Severity.ERROR)
                    .message(Dsl.en("[\$Amount.value\$]"), Dsl.de("[\$Amount.value\$]")).build()
            }
            edit.addRule { bound ->
                val note = bound.str(NOTE)
                bound.rule(bound.group(ROOT)).name("NormalizedLength").code(NOTE_CODE).errorField(note)
                    .`when`(note.length().eq(5)).severity(Severity.ERROR)
                    .message(Dsl.en("\$Note.value\$"), Dsl.de("\$Note.value\$")).build()
            }
            edit.addRule { bound ->
                val anchor = bound.str(ANCHOR)
                bound.rule(bound.group(ROOT)).name("LiteralDollar").code(LITERAL_DOLLAR_CODE).errorField(anchor)
                    .`when`(anchor.filled()).severity(Severity.ERROR)
                    .message(
                        Dsl.en("Cost \$\$ [\$Anchor.value\$]"),
                        Dsl.de("Cost \$\$ [\$Anchor.value\$]"),
                    ).build()
            }
            edit.validate()
        }.toJson()
    }

    private companion object {
        const val ROOT = "/Invoice"
        const val ANCHOR = "$ROOT/Anchor"
        const val AMOUNT = "$ROOT/Amount"
        const val NOTE = "$ROOT/Note"
        const val AMOUNT_CODE = "MISSING_AMOUNT"
        const val NOTE_CODE = "NORMALIZED_NOTE"
        const val LITERAL_DOLLAR_CODE = "LITERAL_DOLLAR"
        const val CRLF = "AB\r\nCD"
    }
}
