package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **partial validation** (`validatePart`): the interpreter's [DmInterpreter.validatePart] vs the
 * kernel's `validatePart(DocumentV2, relevantEntities, config)`. The kernel runs a rule only if its **error
 * field instance** is relevant, and treats a referenced field that is **not** relevant as UNKNOWN (3VL), so
 * Kleene logic suppresses (`And`) or still fires (`Or`) — "no possible value could prevent the error". Both
 * engines get the SAME relevant set (ALL_SEMANTIC-wildcarded, the encouraged form) over a two-field rule;
 * identical firing proves the gate + the non-relevant→Unknown resolution match the kernel. KERNEL-FINDINGS
 * "Partial validation".
 */
class PartialValidationDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private fun def(cond: String) = ModelDef(
        listOf(FieldDef(NAME, Kind.STRING), FieldDef(CODE, Kind.STRING)),
        listOf(RuleDef("/Order/R", "/Order", cond, "PV", "../CustomerName")),
    )

    /** Both fields filled, single /Order row, on both engines. */
    private fun kernelDoc() = RuntimeLaws.doc().group("/Order", 1)
        .field(NAME, "Alice", 1, 1).field(CODE, "SKU-9", 1, 1)

    private fun interpDoc(def: ModelDef) = def.newDocument().group("/Order", 1).field(NAME, "Alice", 1, 1).field(CODE, "SKU-9", 1, 1)

    /** Compare the kernel's and interpreter's partial validation for the SAME relevant set; both must agree on
     *  whether "PV" fires (and match [expectFired], documenting intent). */
    private fun agree(cond: String, kRel: Set<Relevant>, iRel: Set<RelevantEntity>, expectFired: Boolean, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "PV", cond)
        val kFired = RuntimeLaws.firedCodesPart(kModel, kernelDoc(), kRel).contains("PV")
        val def = def(cond)
        val iFired = DmInterpreter(def).validatePart(interpDoc(def), iRel).messages.any { it.code == "PV" }
        assertEquals(kFired, iFired, "$msg  (kernel=$kFired interp=$iFired)")
        assertEquals(expectFired, kFired, "$msg — kernel firing vs expectation")
    }

    // ALL_SEMANTIC (wildcard) relevant pointers, kept in sync across both engines.
    private val kName = Relevant.of(NAME, 0, 0)
    private val kCode = Relevant.of(CODE, 0, 0)
    private val kGroup = Relevant.of("/Order", 0)
    private val iName = RelevantEntity.anyRep(NAME)
    private val iCode = RelevantEntity.anyRep(CODE)
    private val iGroup = RelevantEntity.anyRep("/Order")

    private val and = render(Dsl.fieldFilled(m.field(NAME)).and(Dsl.fieldFilled(m.field(CODE))))
    private val or = render(Dsl.fieldFilled(m.field(NAME)).or(Dsl.fieldFilled(m.field(CODE))))

    @Test fun andBothRelevantFires() =
        agree(and, setOf(kName, kCode), setOf(iName, iCode), true, "And, both relevant → fires")

    @Test fun andOnlyErrorFieldRelevantSuppressed() =
        agree(and, setOf(kName), setOf(iName), false, "And, only error field relevant → true And Unknown = Unknown → suppressed")

    @Test fun andOnlyOtherRelevantNotRun() =
        agree(and, setOf(kCode), setOf(iCode), false, "And, only the other field relevant → error field not relevant → rule not run")

    @Test fun orOnlyErrorFieldRelevantFires() =
        agree(or, setOf(kName), setOf(iName), true, "Or, only error field relevant → true Or Unknown = true → fires")

    @Test fun wholeGroupRelevantEqualsFull() =
        agree(and, setOf(kGroup), setOf(iGroup), true, "whole group relevant → all descendants relevant → equals full validation")

    private companion object {
        const val NAME = "/Order/CustomerName"
        const val CODE = "/Order/ProductCode"
    }
}
