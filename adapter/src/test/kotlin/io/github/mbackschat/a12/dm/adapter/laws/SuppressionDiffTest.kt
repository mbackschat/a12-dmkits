package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.FieldDef
import io.github.mbackschat.a12.dm.interpreter.Kind
import io.github.mbackschat.a12.dm.interpreter.ModelDef
import io.github.mbackschat.a12.dm.interpreter.RuleDef
import io.github.mbackschat.a12.dm.interpreter.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential for the SUPPRESSED verdict + formal-error gate — the third `rule test` outcome and the
 * cli-runtime `not-a-date` scenario. When a referenced field is formally invalid the kernel does NOT
 * evaluate the rule (it reports a `formalePruefung` formal error and the author rule does not fire); the
 * interpreter must return SUPPRESSED (not PASSED, not FIRED) and name the offending field/code — and
 * validateFull must produce NO message for the suppressed rule (it would wrongly fire if it typed the
 * malformed value as text and compared).
 */
class SuppressionDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(OD, Kind.DATE), FieldDef(DD, Kind.DATE))

    // A date rule: delivery strictly before order is a violation (mirrors cli-runtime's DeliveryNotBeforeOrder).
    private val condition = "[DeliveryDate] < [OrderDate]"
    private val rule = RuleDef("/Order/DeliveryNotBeforeOrder", "/Order", condition, "DELIVERY_BEFORE_ORDER", "../DeliveryDate")
    private val model = RuntimeLaws.withRule(order, "/Order", "DeliveryNotBeforeOrder", "../DeliveryDate", "DELIVERY_BEFORE_ORDER", condition)
    private val def = ModelDef(fields, listOf(rule))

    @Test
    fun malformedDateSuppressesLikeKernel() {
        // DeliveryDate = "not-a-date" → kernel reports a formalePruefung formal error + the rule does NOT fire
        val doc = RuntimeLaws.doc().group("/Order", 1).field(OD, "2024-06-01", 1, 1).field(DD, "not-a-date", 1, 1)
        val msgs = RuntimeLaws.messages(model, doc)
        val authorFired = msgs.any { it.code() == "DELIVERY_BEFORE_ORDER" }
        val formalError = msgs.any { it.errorField().contains("DeliveryDate") && !it.code().startsWith("MVK_") }
        assertEquals(false, authorFired, "kernel does not fire the author rule on a malformed operand")
        assertTrue(formalError, "kernel reports a formal error on DeliveryDate")

        val result = DmInterpreter(def).testRule("/Order/DeliveryNotBeforeOrder",
            Document(def.kinds).field(OD, "2024-06-01", 1, 1).field(DD, "not-a-date", 1, 1))
        assertEquals(Verdict.SUPPRESSED, result.verdict)
        assertEquals(DD, result.suppressedBy.single().field)
        assertEquals("datumFormatFalsch", result.suppressedBy.single().formalErrorCode)
    }

    @Test
    fun validInstanceFiresAndCleanInstancePassesLikeKernel() {
        // violation: delivery 2024-05-15 before order 2024-06-01 → FIRED
        val violation = Document(def.kinds).field(OD, "2024-06-01", 1, 1).field(DD, "2024-05-15", 1, 1)
        val kViolation = RuntimeLaws.fires(model, "DELIVERY_BEFORE_ORDER",
            RuntimeLaws.doc().group("/Order", 1).field(OD, "2024-06-01", 1, 1).field(DD, "2024-05-15", 1, 1))
        assertEquals(kViolation, DmInterpreter(def).testRule("/Order/DeliveryNotBeforeOrder", violation).verdict == Verdict.FIRED)

        // compliant: delivery after order → PASSED
        val ok = Document(def.kinds).field(OD, "2024-06-01", 1, 1).field(DD, "2024-06-15", 1, 1)
        assertEquals(Verdict.PASSED, DmInterpreter(def).testRule("/Order/DeliveryNotBeforeOrder", ok).verdict)
    }

    @Test
    fun suppressedRuleProducesNoValidateFullMessage() {
        val doc = Document(def.kinds).field(OD, "2024-06-01", 1, 1).field(DD, "not-a-date", 1, 1)
        assertTrue(DmInterpreter(def).validateFull(doc).none { it.code == "DELIVERY_BEFORE_ORDER" },
            "a suppressed rule must not fire in validateFull")
    }

    private companion object {
        const val OD = "/Order/OrderDate"
        const val DD = "/Order/DeliveryDate"
    }
}
