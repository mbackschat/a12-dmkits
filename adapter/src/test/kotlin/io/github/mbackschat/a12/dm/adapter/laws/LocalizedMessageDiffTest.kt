package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.Kind
import io.github.mbackschat.a12.dm.interpreter.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.renderMessage
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential (scoped) — localized message text. A fired rule's per-locale errorMessage template
 * with a `$field.value$` placeholder is rendered, and the interpreter's rendering matches the kernel's
 * `getErrorText` in BOTH en_US and de_DE — proving value interpolation + locale selection from one
 * engine. The `$field$` display-name placeholder (needs the field's label metadata) is out of scope.
 */
class LocalizedMessageDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val kinds = mapOf(PC to Kind.STRING)

    @Test
    fun renderedTextMatchesKernelInEachLocale() {
        val en = "Code \$ProductCode.value\$ is reserved"
        val de = "Code \$ProductCode.value\$ ist reserviert"
        // order.dm.json declares only en_US — add de_DE to its supported locales so the kernel renders both.
        val bilingual = order.replaceFirst("\"locales\": [", "\"locales\": [ { \"code\": \"de_DE\" }, ")
        val model = RuntimeLaws.withRuleMessages(bilingual, "/Order", "CodeReserved", "../ProductCode",
            "CODE_RESERVED", "[ProductCode] == \"ABC\"", en, de)
        val doc = RuntimeLaws.doc().group("/Order", 1).field(PC, "ABC", 1, 1)
        val ctx = SingleRowDoc("/Order", mapOf(PC to "ABC"), kinds)

        val kernelEn = RuntimeLaws.errorTextOf(model, "CODE_RESERVED", doc, Locale.US).orElse(null)
        val kernelDe = RuntimeLaws.errorTextOf(model, "CODE_RESERVED", doc, Locale.GERMANY).orElse(null)

        // sanity: the kernel actually rendered the interpolation + localized the surrounding text
        assertEquals("Code ABC is reserved", kernelEn)
        assertTrue(kernelDe != null && kernelDe.contains("ABC") && kernelDe.contains("reserviert"))

        assertEquals(kernelEn, renderMessage(en, "/Order", ctx), "en_US rendered text")
        assertEquals(kernelDe, renderMessage(de, "/Order", ctx), "de_DE rendered text")
    }

    private companion object {
        const val PC = "/Order/ProductCode"
    }
}
