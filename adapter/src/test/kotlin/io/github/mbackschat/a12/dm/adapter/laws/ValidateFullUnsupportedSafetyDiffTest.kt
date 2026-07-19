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
    private val sku = "/Order/Items/Sku"
    private val count = "/Order/Items/Count"
    private val condition = DslRenderer().render(
        authored.group("/Order/Items").filled().and(
            Dsl.repetitionNotUnique(authored.field(count)).or(authored.str(customerName).filled()),
        ),
    )

    @Test
    fun kernelLegalUnsupportedRuleFailsLoudlyThroughValidateFull() {
        val kernelCheck = RuleValidator().check(
            order,
            DraftRule("/Order", "UnsupportedSafety", "../Items/Count", condition, "UNSUPPORTED", "ERROR"),
        )
        assertTrue(kernelCheck.ok()) {
            "the IF139 safety discriminator must be legal kernel input: ${kernelCheck.diagnostics()}"
        }

        val model = ModelDef(
            fields = listOf(
                FieldDef(customerName, Kind.STRING),
                FieldDef(sku, Kind.STRING),
                FieldDef(count, Kind.NUMBER),
            ),
            rules = listOf(
                RuleDef("/Order/UnsupportedSafety", "/Order", condition, "UNSUPPORTED", "../Items/Count"),
            ),
            repeatableGroups = setOf("/Order/Items"),
            declaredReps = mapOf("/Order/Items" to 50),
            indexFields = setOf(sku),
        )
        val document = model.newDocument()
            .group("/Order", 1)
            .group("/Order/Items", 1, 1)
            .field(sku, "K1", 1, 1, 1).field(count, "2", 1, 1, 1)
            .group("/Order/Items", 1, 2)
            .field(sku, "K2", 1, 2, 1).field(count, "2", 1, 2, 1)

        val outcome = DmInterpreter(model).validate(document)
        assertTrue(outcome.unsupported.any { it.rulePath == "/Order/UnsupportedSafety" }) {
            "the legal unmodeled rule must reach the unsupported-result safety path: ${outcome.unsupported}"
        }
        assertThrows(IllegalStateException::class.java) {
            DmInterpreter(model).validateFull(document)
        }
    }
}
