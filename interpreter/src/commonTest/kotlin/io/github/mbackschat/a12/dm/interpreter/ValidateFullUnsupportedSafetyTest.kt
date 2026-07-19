package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * IF139 (graduated from IG97) — the `validateFull` convenience must NOT silently drop unsupported rules.
 * [DmInterpreter.validate] returns `ValidationOutcome(messages, unsupported)`; [DmInterpreter.validateFull] is
 * the messages-only kernel twin. When a legal-but-unmodeled construct (or a missing external SPI) leaves rules
 * unsupported, a messages-only result would look CLEAN — a false-clean the kernel can never produce (it supports
 * every construct). So `validateFull` **fails loudly** on unsupported, naming the rules and pointing to
 * `validate`; the empty-unsupported path (every real kernel-equivalent case) is unchanged. Multiplatform (JVM + JS).
 */
class ValidateFullUnsupportedSafetyTest {

    private val cn = "/Order/CustomerName"

    // R1 references an unregistered CustomCondition — a legal kernel construct the interpreter reports unsupported
    // (no SPI registered). R2 is healthy, so validate() reports unsupported=[R1], messages=[R2] (the mixed case:
    // a healthy rule still produces messages while the unmodeled one is surfaced, not silently dropped).
    // ValidateFullUnsupportedSafetyDiffTest proves this discriminator passes the real kernel gate.
    private val withUnsupported = ModelDef(
        fields = listOf(FieldDef(cn, Kind.STRING)),
        rules = listOf(
            RuleDef("/Order/R1", "/Order", "FieldFilled(CustomerName) And CustomCondition RiskCheck", "R1", "../CustomerName"),
            RuleDef("/Order/R2", "/Order", "FieldFilled(CustomerName)", "R2", "../CustomerName"),
        ),
    )

    // The model construct is supported, but the consumer-owned CustomCondition registration is deliberately absent.
    private val withMissingRegistration = ModelDef(
        fields = listOf(FieldDef(cn, Kind.STRING)),
        rules = listOf(
            RuleDef(
                "/Order/External",
                "/Order",
                "FieldFilled(CustomerName) And CustomCondition NameIsAcme",
                "EXT",
                "../CustomerName",
            ),
        ),
    )

    // A supported-only model: validateFull returns messages exactly like the kernel twin, no throw.
    private val supportedOnly = ModelDef(
        fields = listOf(FieldDef(cn, Kind.STRING)),
        rules = listOf(RuleDef("/Order/R2", "/Order", "FieldFilled(CustomerName)", "R2", "../CustomerName")),
    )

    private fun doc(m: ModelDef) = m.newDocument().group("/Order", 1).field(cn, "Acme", 1, 1)

    @Test
    fun validateFullThrowsWhenAnUnsupportedRuleWouldBeSilentlyDropped() {
        val input = doc(withUnsupported)
        val ex = assertFailsWith<IllegalStateException> {
            DmInterpreter(withUnsupported).validateFull(input)
        }
        val msg = ex.message ?: ""
        assertTrue("/Order/R1" in msg, "the error names the unsupported rule: $msg")
        assertTrue("validate" in msg, "the error points the caller to validate(): $msg")
        // the safe API still surfaces it — the loud failure is the convenience's, not a lost report — and the
        // healthy rule R2 still produces its message (the mixed model: unsupported and messages coexist).
        val outcome = DmInterpreter(withUnsupported).validate(input)
        assertTrue(outcome.unsupported.any { it.rulePath == "/Order/R1" },
            "validate() reports the unsupported rule the caller should read")
        assertEquals(listOf("R2"), outcome.messages.map { it.code }, "the healthy rule still fires")
    }

    @Test
    fun validateFullThrowsWhenAnExternalRegistrationIsMissing() {
        val input = doc(withMissingRegistration)
        val ex = assertFailsWith<IllegalStateException> {
            DmInterpreter(withMissingRegistration).validateFull(input)
        }
        val msg = ex.message ?: ""
        assertTrue("/Order/External" in msg, "the error names the rule whose custom condition is unregistered: $msg")
        assertTrue("validate" in msg, "the error points the caller to validate(): $msg")
        assertTrue(
            DmInterpreter(withMissingRegistration).validate(input).unsupported.any {
                it.rulePath == "/Order/External" && "NameIsAcme" in it.reason
            },
            "validate() exposes the missing external registration",
        )
    }

    @Test
    fun validateFullReturnsMessagesWhenNothingIsUnsupported() {
        // the kernel-twin path is unchanged: supported constructs → messages, never a throw.
        assertEquals(listOf("R2"), DmInterpreter(supportedOnly).validateFull(doc(supportedOnly)).map { it.code })
    }
}
