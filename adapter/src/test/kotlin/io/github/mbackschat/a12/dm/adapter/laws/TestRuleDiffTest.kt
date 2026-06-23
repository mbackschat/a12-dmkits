package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for single-rule eval ([DmInterpreter.testRule]) — the `rule test` shape. The FIRED vs
 * PASSED verdict must agree with whether the REAL kernel fires the rule over the same instance. (The
 * third verdict, SUPPRESSED, needs the formal-error increment and is not yet covered.) Also checks the
 * injected-candidate path ([DmInterpreter.evalCandidate]) used by `model eval --condition`.
 */
class TestRuleDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(Q, Kind.NUMBER), FieldDef(CN, Kind.STRING))

    private fun verdictMatchesKernel(condition: String, values: Map<String, String>, empties: Set<String>) {
        val rule = RuleDef("/Order/Cap", "/Order", condition, "CAP", "../Quantity")
        val model = RuntimeLaws.withRule(order, "/Order", "Cap", "../Quantity", "CAP", condition)
        val def = ModelDef(fields, listOf(rule))

        val kernelFired = RuntimeLaws.fires(model, "CAP", setOf("/Order"), empties, values)
        val doc = Document(def.kinds)
        values.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        empties.forEach { p -> doc.emptyField(p, 1, 1) }
        val verdict = DmInterpreter(def).testRule("/Order/Cap", doc).verdict

        assertEquals(
            if (kernelFired) Verdict.FIRED else Verdict.PASSED, verdict,
            "\"$condition\" over $values/$empties (kernel fired=$kernelFired)",
        )
    }

    @Test
    fun firedVerdictMatchesKernel() =
        verdictMatchesKernel("FieldFilled(Quantity) And [Quantity] > 100", mapOf(Q to "150"), emptySet())

    @Test
    fun passedVerdictMatchesKernel() =
        verdictMatchesKernel("FieldFilled(Quantity) And [Quantity] > 100", mapOf(Q to "50"), emptySet())

    @Test
    fun candidateRuleFiredMatchesKernel() {
        // the `model eval --condition` path: an injected, non-stored candidate over Quantity=150
        val candidate = RuleDef("/Order/EvalDocCandidate", "/Order", "[Quantity] > 100", "EVAL_DOC", "../Quantity")
        val model = RuntimeLaws.withRule(order, "/Order", "EvalDocCandidate", "../Quantity", "EVAL_DOC", candidate.conditionText)
        val def = ModelDef(fields, emptyList())   // candidate is NOT in the model

        val kernelFired = RuntimeLaws.fires(model, "EVAL_DOC", setOf("/Order"), emptySet(), mapOf(Q to "150"))
        val verdict = DmInterpreter(def).evalCandidate(candidate, Document(def.kinds).field(Q, "150", 1, 1)).verdict
        assertEquals(if (kernelFired) Verdict.FIRED else Verdict.PASSED, verdict)
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val CN = "/Order/CustomerName"
    }
}
