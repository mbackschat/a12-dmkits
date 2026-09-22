package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — a **GLOBAL** field is auto-relevant in partial validation (IG11). The kernel's
 * md-runtime-service layer (`LowKernelApiUtils.addGlobalFieldsToRelevantEntities`) adds every global field
 * (wildcarded at all reps) to the caller's relevant set **before** validating — `IField.isGlobal` javadoc:
 * *"Global fields should always be passed to a partial validation in addition to the usual relevant entities."*
 * So a rule whose error field is a global field RUNS even when the caller's relevant set never mentions it.
 *
 * `/Policy { Premium, RiskScore }`, rule error field `RiskScore`, fires when `[RiskScore] < 0`. Validated
 * partially with **only `Premium`** relevant: when `RiskScore` is `global`, the kernel auto-adds it and the rule
 * fires; the interpreter must match (auto-add globals to the relevant predicate). The **non-global control**
 * proves the auto-add is specifically the `global` flag — without it neither engine fires (the error field is
 * not relevant). KERNEL-FINDINGS §6/"Partial validation".
 */
class PartialValidationGlobalFieldDiffTest {

    private fun json(global: Boolean) = DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy", Consumer { g: GroupBuilder ->
            g.numberField("Premium", Consumer { })
                .numberField("RiskScore", Consumer { if (global) it.global() })
        })
        .build().toJson()

    // FieldFilled(RiskScore) And [RiskScore] < 0 — authored with the typed DSL, rendered to both engines (dogfood).
    private fun cond(global: Boolean): String {
        val m = BoundModel.ofJson(json(global))
        return RuntimeLaws.render(Dsl.fieldFilled(m.field(RISK)).and(m.num(RISK).lt(0)))
    }

    private fun kModel(global: Boolean) =
        RuntimeLaws.withRule(json(global), POLICY, "RISK", "../RiskScore", CODE, cond(global))

    private fun def(global: Boolean) = ModelDef(
        listOf(FieldDef(PREMIUM, Kind.NUMBER), FieldDef(RISK, Kind.NUMBER, global = global)),
        listOf(RuleDef("$POLICY/RISK", POLICY, cond(global), CODE, "../RiskScore")),
    )

    // RiskScore = -5 → the rule fires; Premium present and fine.
    private fun kDoc() = RuntimeLaws.doc().group(POLICY, 1)
        .field(PREMIUM, "100", 1, 1).field(RISK, "-5", 1, 1)
    private fun iDoc() = Document(def(true).kinds).group(POLICY, 1)
        .field(PREMIUM, "100", 1, 1).field(RISK, "-5", 1, 1)

    /** Sanity: the rule fires in FULL validation on both engines. */
    @Test fun firesInFull() {
        assertTrue(RuntimeLaws.firedCodes(kModel(true), kDoc()).contains(CODE), "kernel full fires")
        assertTrue(DmInterpreter(def(true)).validate(iDoc()).messages.any { it.code == CODE }, "interp full fires")
    }

    /** IG11: only `Premium` relevant; `RiskScore` is GLOBAL → the kernel auto-adds it, so the rule runs and
     *  fires. The interpreter must auto-add globals too. */
    @Test fun globalFieldIsAutoRelevantLikeKernel() {
        val kRel = setOf(Relevant.of(PREMIUM, 0, 0))
        val iRel = setOf(RelevantEntity.anyRep(PREMIUM))
        val k = RuntimeLaws.firedCodesPart(kModel(true), kDoc(), kRel).contains(CODE)
        val i = DmInterpreter(def(true)).validatePart(iDoc(), iRel).messages.any { it.code == CODE }
        assertTrue(k, "kernel auto-adds the global field → fires")
        assertEquals(k, i, "global field auto-relevant — kernel=$k interp=$i")
    }

    /** Control: the SAME rule with `RiskScore` NOT global, only `Premium` relevant → the error field is not
     *  relevant on either engine, so neither fires. Proves the auto-add is specifically the `global` flag. */
    @Test fun nonGlobalFieldIsNotAutoRelevant() {
        val kRel = setOf(Relevant.of(PREMIUM, 0, 0))
        val iRel = setOf(RelevantEntity.anyRep(PREMIUM))
        val k = RuntimeLaws.firedCodesPart(kModel(false), kDoc(), kRel).contains(CODE)
        val i = DmInterpreter(def(false)).validatePart(iDoc(), iRel).messages.any { it.code == CODE }
        assertEquals(false, k, "non-global error field not relevant → kernel does not fire")
        assertEquals(k, i, "non-global control — kernel=$k interp=$i")
    }

    private companion object {
        const val POLICY = "/Policy"
        const val PREMIUM = "/Policy/Premium"
        const val RISK = "/Policy/RiskScore"
        const val CODE = "RISK_NEGATIVE"
    }
}
