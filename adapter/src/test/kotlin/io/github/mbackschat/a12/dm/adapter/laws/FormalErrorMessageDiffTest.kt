package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.Severity
import io.github.mbackschat.a12.dm.interpreter.eval.FormalErrorCode
import io.github.mbackschat.a12.dm.interpreter.eval.formalErrorText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — formal-error MESSAGES. On a full validation the kernel emits a `formalePruefung` message
 * per malformed filled field; the interpreter's [DmInterpreter.validate] must emit the same set. We compare
 * the **structural channels** (error code + offending-field pointer) as a SET — the text is the interpreter's
 * OWN wording, not the kernel's German bundle (Option A; KERNEL-INTEGRATION-NOTES "Formal-error message
 * text"), so the text is asserted separately, not against the kernel. Severity/type are pinned to ERROR/VALUE
 * (the kernel's constant for formal checks). Suppression of rules over malformed fields stays
 * [SuppressionDiffTest]'s job; this locks the *emission*.
 */
class FormalErrorMessageDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(Q, Kind.NUMBER, scale = 0),
        FieldDef(BQ, Kind.NUMBER, scale = 0),
        FieldDef(UP, Kind.NUMBER, scale = 2),
        FieldDef(OD, Kind.DATE),
    )

    /** Formal messages as a comparable (code, field-pointer) set — the interpreter's side. */
    private fun interpFormal(doc: Document): Set<Pair<String, String>> =
        DmInterpreter(ModelDef(fields, emptyList())).validate(doc).messages
            .filter { it.rulePath == FORMAL_RULE_PATH }
            .map { it.code to it.errorPath }.toSet()

    /** Formal messages as a (code, field-pointer) set — the kernel's side (filtered to the formal codes). */
    private fun kernelFormal(doc: RuntimeLaws.Doc): Set<Pair<String, String>> =
        RuntimeLaws.messages(order, doc)
            .filter { it.code() in FORMAL_CODES }
            .map { it.code() to it.field() }.toSet()

    @Test
    fun emittedFormalErrorsMatchTheKernel() {
        // Quantity scale-0 "abc" → invalid chars; BackorderQuantity scale-0 "1.5" → decimal separator;
        // UnitPrice scale-2 "10.999" → too many decimals; OrderDate "not-a-date" → bad date format.
        val kernel = kernelFormal(RuntimeLaws.doc().group("/Order", 1)
            .field(Q, "abc", 1, 1).field(BQ, "1.5", 1, 1).field(UP, "10.999", 1, 1).field(OD, "not-a-date", 1, 1))
        val interp = interpFormal(Document(kinds)
            .field(Q, "abc", 1, 1).field(BQ, "1.5", 1, 1).field(UP, "10.999", 1, 1).field(OD, "not-a-date", 1, 1))

        assertEquals(
            setOf(
                "zahlHatUngueltigeZeichen" to "/Order[1]/Quantity",
                "zahlHatDezimalTrenner" to "/Order[1]/BackorderQuantity",
                "zahlFalscheAnzahlNachkommastellen" to "/Order[1]/UnitPrice",
                "datumFormatFalsch" to "/Order[1]/OrderDate",
            ),
            kernel,
            "sanity: the kernel's formal-error set",
        )
        assertEquals(kernel, interp, "interpreter emits the same formal-error (code, field) set as the kernel")
    }

    @Test
    fun leadingBlankNumberMatchesTheKernel() {
        // A leading blank on a number is a formal error checked before parsing.
        val kernel = kernelFormal(RuntimeLaws.doc().group("/Order", 1).field(Q, " 5", 1, 1))
        val interp = interpFormal(Document(kinds).field(Q, " 5", 1, 1))
        assertEquals(setOf("fuehrendesBlank" to "/Order[1]/Quantity"), kernel, "sanity: leading-blank number")
        assertEquals(kernel, interp)
    }

    @Test
    fun formalMessageStructuralChannels() {
        // The non-pointer channels: rule path = the formal sentinel, severity ERROR, type VALUE.
        val msg: Message = DmInterpreter(ModelDef(fields, emptyList()))
            .validate(Document(kinds).field(OD, "not-a-date", 1, 1)).messages.single()
        assertEquals(FORMAL_RULE_PATH, msg.rulePath)
        assertEquals(Severity.ERROR, msg.severity)
        assertEquals(MsgType.VALUE, msg.type)
        assertEquals("datumFormatFalsch", msg.code)
    }

    @Test
    fun ownLocalizedText() {
        // Our wording (not the kernel's), en + de — the deliberate Option-A divergence.
        assertEquals("Value is not a valid date (expected YYYY-MM-DD).", formalErrorText(FormalErrorCode.DATE_FORMAT, "en_US"))
        assertEquals("Wert ist kein gültiges Datum (erwartet JJJJ-MM-TT).", formalErrorText(FormalErrorCode.DATE_FORMAT, "de_DE"))
        assertEquals("Value has too many decimal places (max 2).", formalErrorText(FormalErrorCode.TOO_MANY_DECIMALS, "en_US", 2))
    }

    private val kinds = mapOf(Q to Kind.NUMBER, BQ to Kind.NUMBER, UP to Kind.NUMBER, OD to Kind.DATE)

    private companion object {
        const val Q = "/Order/Quantity"
        const val BQ = "/Order/BackorderQuantity"
        const val UP = "/Order/UnitPrice"
        const val OD = "/Order/OrderDate"
        val FORMAL_CODES = setOf(
            "datumFormatFalsch", "fuehrendesBlank", "zahlHatUngueltigeZeichen",
            "zahlHatDezimalTrenner", "zahlFalscheAnzahlNachkommastellen",
        )
    }
}
