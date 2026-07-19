package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JVM evidence bridge for IF139's multiplatform `ValidateFullUnsupportedSafetyTest`. It proves that the
 * legal-but-unmodeled discriminator first passes the real kernel's structural gate, then verifies that
 * `validateFull` fails loudly instead of returning a false-clean messages list. The capability matrix references
 * this class by KClass; the JVM/Node safety siblings stay in `:interpreter` commonTest.
 */
class ValidateFullUnsupportedSafetyDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val authored = BoundModel.ofJson(order)
    private val customerName = "/Order/CustomerName"
    // An unregistered `CustomCondition` — a legal kernel construct (a host-callback predicate the rule language
    // can't express) that the interpreter reports unsupported when no matching SPI is registered. (RepetitionNotUnique
    // under Or is no longer a discriminator here — it graduated to a supported 3VL leaf, IF149.)
    private val condition = DslRenderer().render(
        authored.str(customerName).filled().and(Dsl.customCondition("UnsupportedSafetyCheck")),
    )

    @Test
    fun kernelLegalUnsupportedRuleFailsLoudlyThroughValidateFull() {
        val kernelCheck = RuleValidator().check(
            order,
            DraftRule("/Order", "UnsupportedSafety", "../CustomerName", condition, "UNSUPPORTED", "ERROR"),
        )
        assertTrue(kernelCheck.ok()) {
            "the IF139 safety discriminator must be legal kernel input: ${kernelCheck.diagnostics()}"
        }

        val model = ModelDef(
            fields = listOf(FieldDef(customerName, Kind.STRING)),
            rules = listOf(
                RuleDef("/Order/UnsupportedSafety", "/Order", condition, "UNSUPPORTED", "../CustomerName"),
            ),
        )
        val document = model.newDocument().group("/Order", 1).field(customerName, "Acme", 1, 1)

        // No CustomCondition registered → the interpreter reports the rule unsupported (never silently drops it).
        val outcome = DmInterpreter(model).validate(document)
        assertTrue(outcome.unsupported.any { it.rulePath == "/Order/UnsupportedSafety" }) {
            "the legal unregistered-SPI rule must reach the unsupported-result safety path: ${outcome.unsupported}"
        }
        assertThrows(IllegalStateException::class.java) {
            DmInterpreter(model).validateFull(document)
        }
    }
}
