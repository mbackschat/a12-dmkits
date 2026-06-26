package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the `$field$` display-name placeholder. A fired rule's message with a `$Field$` token
 * (the field's identifier display string) renders identically through the interpreter
 * ([DmInterpreter.validate] → `renderMessage` → [ModelDef.labelOf]) and the kernel (`getErrorText`). Both
 * resolve `$field$` to the field's label for the locale, falling back to the field NAME — and for our
 * label-free corpus that is the name. The kernel side carries a label provider in [RuntimeLaws] (mirroring
 * `KernelAdapter`'s); without one the kernel would emit a `/Order/Quantity/1.1` path+index fallback, so
 * this also pins that the provider wiring is in force. Companion to the value tier ([LocalizedMessageDiffTest]).
 */
class DisplayNameDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(Q, Kind.NUMBER))

    // The rule condition is authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to
    // BOTH engines — no free-text condition literal. Re-authoring the same condition does not affect the test,
    // which checks the $field$ display-name rendering on the fired message text. The renderer emits absolute
    // paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun interpRender(cond: String, msg: String, doc: Document, locale: String): String =
        DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "POL", "../Quantity",
            messages = mapOf(locale to msg)))))
            .validate(doc, locale).messages.single { it.code == "POL" }.text

    private fun kernelRender(model: String, cond: String, en: String, de: String, locale: Locale): String =
        RuntimeLaws.errorTextOf(
            RuntimeLaws.withRuleMessages(model, "/Order", "R", "../Quantity", "POL", cond, en, de),
            "POL", RuntimeLaws.doc().group("/Order", 1).field(Q, "50", 1, 1), locale,
        ).orElseThrow()

    @Test
    fun displayNameAndValueMatchKernel() {
        // $Quantity$ (name) + $Quantity.value$ (value) in one message — fires on Quantity 50 < 100.
        val cond = render(m.num(Q).lt(100))
        val msg = "\$Quantity\$ is below 100 (was \$Quantity.value\$)"
        val kernel = kernelRender(order, cond, msg, msg, Locale.US)
        val interp = interpRender(cond, msg, Document(kinds).field(Q, "50", 1, 1), "en_US")

        assertEquals("Quantity is below 100 (was 50)", kernel, "sanity: kernel resolved name + value")
        assertEquals(kernel, interp, "interpreter renders \$field\$ + \$field.value\$ like the kernel")
    }

    @Test
    fun displayNameOnlyMatchesKernel() {
        val cond = render(m.num(Q).lt(100))
        val msg = "Please review \$Quantity\$."
        val kernel = kernelRender(order, cond, msg, msg, Locale.US)
        val interp = interpRender(cond, msg, Document(kinds).field(Q, "50", 1, 1), "en_US")

        assertEquals("Please review Quantity.", kernel, "sanity: bare \$field\$ → the field name")
        assertEquals(kernel, interp)
    }

    @Test
    fun displayNameIsLocaleStableForUnlabeledField() {
        // order.dm.json declares only en_US — add de_DE so the kernel renders both; the NAME is the same in
        // either locale (no field label is defined), so the display name does not vary by locale here.
        val bilingual = order.replaceFirst("\"locales\": [", "\"locales\": [ { \"code\": \"de_DE\" }, ")
        val cond = render(m.num(Q).lt(100))
        val en = "Check \$Quantity\$"
        val de = "Prüfe \$Quantity\$"
        val kernelDe = kernelRender(bilingual, cond, en, de, Locale.GERMANY)
        val interpDe = interpRender(cond, de, Document(kinds).field(Q, "50", 1, 1), "de_DE")

        assertEquals("Prüfe Quantity", kernelDe, "sanity: name is locale-independent (no label)")
        assertEquals(kernelDe, interpDe)
    }

    private val kinds = mapOf(Q to Kind.NUMBER)

    private companion object {
        const val Q = "/Order/Quantity"
    }
}
