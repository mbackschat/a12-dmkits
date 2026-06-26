package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for the public API: a [ModelDef] + [Document] run through [DmInterpreter.validateFull]
 * must fire exactly the codes the REAL kernel fires over the same instance (the `RuntimeLaws` oracle).
 * Exercises the whole-document path end to end — multiple rules, a flat single-row rule, and a per-row
 * rule over a repeatable subgroup — driven from condition TEXT (not a hand-built AST).
 */
class ValidateFullDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(Q, Kind.NUMBER), FieldDef(BQ, Kind.NUMBER), FieldDef(CN, Kind.STRING),
        FieldDef(IC, Kind.NUMBER), FieldDef(IS, Kind.STRING),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines (the interpreter [RuleDef] + the kernel injection) — no free-text condition literals.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    @Test
    fun flatSingleRuleAgreesWithKernel() {
        val rule = RuleDef("/Order/QtyCap", "/Order", render(m.num(Q).lt(100)), "QTY_LOW", "../Quantity")
        val model = RuntimeLaws.withRule(order, "/Order", "QtyCap", "../Quantity", "QTY_LOW", rule.conditionText)
        val def = ModelDef(fields, listOf(rule))

        // empty Quantity → 0 < 100 fires (empty-as-0); filled 200 does not
        assertEquals(
            RuntimeLaws.fires(model, "QTY_LOW", setOf("/Order"), setOf(), mapOf(CN to "Acme")),
            DmInterpreter(def).validateFull(Document(def.kinds).field(CN, "Acme", 1, 1)).any { it.code == "QTY_LOW" },
            "empty Quantity → fires",
        )
        assertEquals(
            RuntimeLaws.fires(model, "QTY_LOW", setOf("/Order"), setOf(), mapOf(Q to "200")),
            DmInterpreter(def).validateFull(Document(def.kinds).field(Q, "200", 1, 1)).any { it.code == "QTY_LOW" },
            "Quantity 200 → no fire",
        )
    }

    @Test
    fun multipleRulesOverFlatAndRepetitionAgreeWithKernel() {
        val qtyRule = RuleDef("/Order/QtyCap", "/Order", render(m.num(Q).filled().and(m.num(Q).lt(100))), "QTY_LOW", "../Quantity")
        val rowRule = RuleDef("/Order/CountCap", "/Order", render(m.num(IC).filled().and(m.num(IC).gt(50))), "COUNT_HIGH", "../Items/Count")

        var model = RuntimeLaws.withRule(order, "/Order", "QtyCap", "../Quantity", "QTY_LOW", qtyRule.conditionText)
        model = RuntimeLaws.withRule(model, "/Order", "CountCap", "../Items/Count", "COUNT_HIGH", rowRule.conditionText)
        val def = ModelDef(fields, listOf(qtyRule, rowRule), repeatableGroups = setOf("/Order/Items"))

        // Quantity 200 (no QTY_LOW); Items counts 10/80/90 (COUNT_HIGH on rows 2 & 3)
        val kernelDoc = RuntimeLaws.doc().group("/Order", 1).field(Q, "200", 1, 1).field(CN, "Acme", 1, 1)
        val interpDoc = Document(def.kinds).field(Q, "200", 1, 1).field(CN, "Acme", 1, 1)
        listOf("10", "80", "90").forEachIndexed { i, c ->
            val r = i + 1
            kernelDoc.group("/Order/Items", 1, r).field(IC, c, 1, r, 1)
            interpDoc.field(IC, c, 1, r, 1)
        }

        val kernelCodes = RuntimeLaws.firedCodes(model, kernelDoc).filter { it == "QTY_LOW" || it == "COUNT_HIGH" }.toSet()
        val interpMsgs = DmInterpreter(def).validateFull(interpDoc)
        assertEquals(kernelCodes, interpMsgs.map { it.code }.toSet(), "fired code set matches the kernel")
        // the per-row rule fires once per offending row (rows 2 & 3)
        assertEquals(2, interpMsgs.count { it.code == "COUNT_HIGH" }, "COUNT_HIGH fires on two rows")
    }

    @Test
    fun unsupportedRuleIsToleratedNotCrashed() {
        // A model mixing a supported rule with an external CustomCondition rule (cli-runtime's
        // EligibilityCheck shape): validate() reports the latter as unsupported and still fires the former.
        val ok = RuleDef("/Order/QtyCap", "/Order", render(m.num(Q).filled().and(m.num(Q).gt(100))), "QTY_HIGH", "../Quantity")
        val external = RuleDef("/Order/Eligibility", "/Order",
            render(Dsl.fieldFilled(m.field(CN)).and(Dsl.customCondition("ExternalEligibility"))), "INELIGIBLE", "../CustomerName")
        val def = ModelDef(fields, listOf(ok, external))

        val outcome = DmInterpreter(def).validate(Document(def.kinds).field(Q, "150", 1, 1).field(CN, "Acme", 1, 1))
        assertEquals(listOf("QTY_HIGH"), outcome.messages.map { it.code }, "the supported rule still fires")
        assertEquals(listOf("/Order/Eligibility"), outcome.unsupported.map { it.rulePath }, "the external rule is reported, not crashed")
    }

    @Test
    fun firedPolarityMatchesKernel() {
        // And: filled VALUE branch + empty-substituted OMISSION branch → OMISSION (omission wins)
        val rule = RuleDef("/Order/Pol", "/Order", render(m.num(Q).gt(5).and(m.num(BQ).ge(0))), "POL", "../Quantity")
        val model = RuntimeLaws.withRule(order, "/Order", "Pol", "../Quantity", "POL", rule.conditionText)
        val def = ModelDef(fields, listOf(rule))

        val kernelType = RuntimeLaws.typeOf(model, "POL", setOf("/Order"), setOf(BQ), mapOf(Q to "10")).orElse(null)
        val msg = DmInterpreter(def).validateFull(
            Document(def.kinds).field(Q, "10", 1, 1).emptyField(BQ, 1, 1),
        ).single { it.code == "POL" }
        assertEquals(MsgType.OMISSION, msg.type)
        assertEquals("OMISSION_ERROR", kernelType.toString())
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val BQ = "/Order/BackorderQuantity"
        const val CN = "/Order/CustomerName"
        const val IC = "/Order/Items/Count"
        const val IS = "/Order/Items/Sku"
    }
}
